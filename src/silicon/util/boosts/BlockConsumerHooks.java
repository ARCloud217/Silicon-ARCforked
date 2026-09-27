package silicon.util.boosts;

import arc.struct.Seq;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumePower;
import silicon.util.BuildingBoostSystem;

/**
 * 把「按建筑个体缩放」的钩子装进方块的 consumer 列表。
 *
 * <p><b>为什么需要它</b>：MJ 的工厂里，电力需求与生产速率都<b>不是</b>每建筑可写的字段——
 * {@code Block.consPower}（{@link ConsumePower#usage}）与 {@code GenericCrafter.craftTime} 都是
 * <b>方块类型级</b>的共享对象，直接改会影响该类型的<b>所有</b>建筑（含敌方），故不可用。
 * 引擎真正留给「按建筑个体」的唯一入口就是 consumer 扩展点，本类即基于这两点：
 *
 * <ul>
 *   <li><b>电力</b>：{@code PowerGraph.getPowerNeeded} 按 {@code block.consPower.requestedPower(build) × build.delta()}
 *       汇总需求，故覆写 {@link ConsumePower#requestedPower(Building)} 即可得到<b>每建筑独立</b>的电力倍率。
 *       引擎自带的 {@code ConsumePowerDynamic} / {@code ConsumePowerCondition} 也正是用这个方法做按建筑动态电量。</li>
 *   <li><b>生产速率</b>：{@code progress += (1 / craftTime) × edelta()}，而 {@code edelta = efficiency × delta()}；
 *       {@code efficiency} 由 {@code Building.updateConsumption()} 取所有<b>非可选</b> consumer 的
 *       {@code efficiency(build)} 的最小值得出（初值 1）。故塞一个返回 {@code 1.5} 的「速率税」consumer，
 *       即可让该建筑 {@code efficiency = 1.5} → 生产速率 ×1.5。</li>
 * </ul>
 *
 * <p><b>两者互不干扰</b>：电力需求只乘 {@code delta()}、不乘 {@code efficiency}；
 * 生产速率两者都乘。故「改电力」与「改速率」可以各自独立取值，无需任何系数换算。
 *
 * <p><b>多效果叠加</b>：同一栋建筑上可同时挂着多个钩子式效果（如节能 + 超频）。
 * 钩子不持有某个效果的引用，而是在每次被查询时遍历 {@link #sources} 注册表，
 * 取<b>所有当前生效效果的倍率之积</b>（故安装是「每方块类型一次」，与效果数量无关）。
 *
 * <p><b>安装是幂等且惰性的</b>：只在某方块<b>真的被钩子式效果命中</b>时才安装（见 {@link #install}），
 * 未被命中的方块完全不受影响。安装后若被 {@code Block.reinitializeConsumers()} 冲掉，
 * 下次命中会重新安装（见 {@link #installed}）。
 *
 * <p><b>多人安全</b>：倍率由钩子在<b>每次被引擎查询时</b>回调 {@link #powerFactor}/{@link #speedFactor} 实时查询，
 * 钩子自身<b>不缓存</b>任何每建筑状态，因此不存在两端不同步的窗口；
 * 判定只用两端一致的量（{@link Building} 引用 + System 生效状态）。
 */
public class BlockConsumerHooks{

    /**
     * 参与「按建筑缩放」的效果源：钩子式效果实现本接口并在静态块 {@link #register} 一次。
     *
     * <p>倍率方法<b>只会在本效果于该建筑上生效时被调用</b>，故实现里无需再判生效状态。
     */
    public interface FactorSource{
        /** 效果 id（与 {@link BuildingBoostSystem.Boost#id()} 同一个方法，两边共用一份实现）。 */
        String id();

        /** 电力需求倍率（1 = 不影响）。 */
        float powerFactor(Building target);

        /** 生产速率倍率（1 = 不影响）。 */
        float speedFactor(Building target);
    }

    /** 已注册的倍率来源（加载期填充，运行期只读）。 */
    private static final Seq<FactorSource> sources = new Seq<>();

    /** 注册一个倍率来源（加载期调用，重复注册同一实例会被忽略）。 */
    public static void register(FactorSource source){
        if(source != null && !sources.contains(source, true)){
            sources.add(source);
        }
    }

    /**
     * 该建筑当前的电力倍率：所有已注册且<b>在该建筑上生效</b>的来源倍率之积（无则 1）。
     *
     * <p>注意：读的是 System 本 tick 冲洗后的状态，若电力图结算早于本 System 冲洗，
     * 则该 tick 读到的是上一 tick 的状态——对倍率类效果只是 1 tick 滞后，不影响正确性。
     */
    public static float powerFactor(Building build){
        float factor = 1f;
        for(int i = 0, n = sources.size; i < n; i++){
            FactorSource source = sources.get(i);
            if(BuildingBoostSystem.isActive(build, source.id())){
                factor *= source.powerFactor(build);
            }
        }
        return factor;
    }

    /** 该建筑当前的速率倍率：语义同 {@link #powerFactor}。 */
    public static float speedFactor(Building build){
        float factor = 1f;
        for(int i = 0, n = sources.size; i < n; i++){
            FactorSource source = sources.get(i);
            if(BuildingBoostSystem.isActive(build, source.id())){
                factor *= source.speedFactor(build);
            }
        }
        return factor;
    }

    /** 该方块是否已装本钩子（以电力钩子为标记，两个钩子总是一起安装）。 */
    public static boolean installed(Block block){
        return block != null && block.consPower instanceof ScaledConsumePower;
    }

    /**
     * 把钩子装进方块的 consumer 列表（幂等）。
     *
     * <p>只改运行时用于查询的 {@code consumers} / {@code nonOptionalConsumers} / {@code consPower} 三个数组引用，
     * <b>不动</b> {@code Block.consumeBuilder}（protected，且是内容加载期数据）——
     * 代价是 {@code reinitializeConsumers()} 会把钩子冲掉，靠 {@link #installed} 自愈重装。
     *
     * <p>替换而非新增电力 consumer，是为了保持
     * {@code Building.updateConsumption()} 里 {@code cons == block.consPower} 的身份判断成立
     * （该判断会跳过「efficiency ≤ 1e-7 则不计电力需求」分支，对电力 consumer 本身必须跳过，
     * 否则断电的建筑会退出电网需求、导致供电震荡）。
     */
    public static void install(Block block){
        if(block == null || block.consPower == null || installed(block)) return;

        ConsumePower base = block.consPower;

        ConsumePower power = new ScaledConsumePower(base);
        Consume tax = new SpeedTaxConsume();

        block.consPower = power;
        block.consumers = replace(block.consumers, base, power);
        block.nonOptionalConsumers = replace(block.nonOptionalConsumers, base, power);

        block.consumers = append(block.consumers, tax);
        block.nonOptionalConsumers = append(block.nonOptionalConsumers, tax);
    }

    /** 数组中所有 {@code from} 引用换成 {@code to}；无匹配时原样返回（不复制）。 */
    private static Consume[] replace(Consume[] src, Consume from, Consume to){
        if(src == null) return null;

        Consume[] out = null;
        for(int i = 0; i < src.length; i++){
            if(src[i] != from) continue;
            if(out == null){
                out = new Consume[src.length];
                System.arraycopy(src, 0, out, 0, src.length);
            }
            out[i] = to;
        }
        return out == null ? src : out;
    }

    private static Consume[] append(Consume[] src, Consume add){
        if(src == null) return new Consume[]{add};

        Consume[] out = new Consume[src.length + 1];
        System.arraycopy(src, 0, out, 0, src.length);
        out[src.length] = add;
        return out;
    }

    /**
     * 电力钩子：把请求电量整体乘以该建筑的电力倍率（所有生效钩子式效果之积）。
     *
     * <p>字段镜像自被包装的实例：方块信息面板与图纸导出读的是<b>本实例</b>的
     * {@code usage} / {@code capacity} / {@code buffered}，不镜像会让 UI 显示 0 功率、图纸存下 0 耗电。
     */
    public static class ScaledConsumePower extends ConsumePower{
        final ConsumePower base;

        ScaledConsumePower(ConsumePower base){
            this.base = base;

            this.usage = base.usage;
            this.capacity = base.capacity;
            this.buffered = base.buffered;
            this.optional = base.optional;
            this.booster = base.booster;
            this.update = base.update;
            this.multiplier = base.multiplier;
        }

        @Override
        public float requestedPower(Building build){
            return base.requestedPower(build) * powerFactor(build);
        }
    }

    /**
     * 速率税：作为非可选 consumer 参与 {@code efficiency} 取最小值，
     * 从而把该建筑的 {@code efficiency} 压到 {@link #speedFactor}，生产进度随之等比变化。
     *
     * <p><b>副作用须知</b>：{@code efficiency} 是引擎「这座建筑跑多快」的总标量，
     * 故凡依赖它的判定（生产进度、{@code optionalEfficiency}、依赖 {@code efficiency > 0} 的
     * 产出节流等）都会随之等比变化——这正是「生产速度 ±X%」的期望语义。
     * 但它<b>不影响</b>方块信息面板的<b>耗电量</b>与<b>电力条</b>：
     * 耗电读的是本钩子镜像的 {@code usage}（原值），电力条显示的是供电满足度 {@code power.status}。
     */
    public static class SpeedTaxConsume extends Consume{
        @Override
        public float efficiency(Building build){
            return speedFactor(build);
        }
    }
}
