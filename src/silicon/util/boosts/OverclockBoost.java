package silicon.util.boosts;

import arc.Core;
import arc.graphics.Color;
import arc.struct.ObjectMap;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.world.Block;
import mindustry.world.blocks.production.GenericCrafter;
import silicon.util.BuildingBoostSystem;

/**
 * 超频（钩子式 Boost + 持续掉血）：作用对象与 {@link EnergySavingBoost} 完全一致（耗电工厂），
 * 但方向相反，是纯粹的「以机器寿命换产能」：
 *
 * <ul>
 *   <li><b>生产速度 +50% / +100% / +300%</b>：<b>不能</b>靠 {@code efficiency}——引擎取所有非可选
 *       consumer 的最小值且初值即 1，故 {@code efficiency ≤ 1}，提速无效；改为经
 *       {@link BlockConsumerHooks#boostProgress} 向 {@code progress} 追加「超出 1 倍的那一份」；</li>
 *   <li><b>耗电 +30% / +125% / +500%</b>：经 {@link BlockConsumerHooks.ScaledConsumePower} 把
 *       {@code requestedPower(build)} 乘档位倍率，只提高该建筑自己的请求电量；</li>
 *   <li><b>每 3 秒扣 10 点生命</b>：注入式，按 tick 累计真实时间，跨过间隔即
 *       {@code target.damage(damage)}（走引擎标准伤害链路，含 {@code Rules.blockHealth} 与
 *       {@code Call.buildDestroyed}），血量归零即正常爆炸拆除。</li>
 * </ul>
 *
 * <p>与节能共用同一对引擎钩子：钩子每次被查询时取<b>所有生效钩子式效果倍率之积</b>
 * （见 {@link BlockConsumerHooks#powerFactor}），故两者若同时生效会相乘——
 * 但本效果用 {@link #conflictsWith(String)} 与节能<b>互斥</b>，正常情况下不会同时生效。
 *
 * <p><b>掉血计时</b>：需要「每建筑」的累计时间，引擎未提供可借用的字段，故用
 * {@link #damageTimers} 记录。清理依赖 System 的撤销保证——
 * {@code reconcile} 在不再生效时、以及 {@code sweepInvalid} 清扫失效目标时，
 * 都必定回调一次 {@link #remove(Building)}，故不会泄漏表项。
 */
public class OverclockBoost implements BuildingBoostSystem.Boost, BlockConsumerHooks.FactorSource{

    /** 单例：注册进 System 供 Provider 引用。 */
    public static final OverclockBoost instance = new OverclockBoost();

    static{
        BuildingBoostSystem.register(instance);
        BlockConsumerHooks.register(instance);
    }

    /** id 常量：供「节能」侧对称声明互斥，避免两处字面量写错。 */
    public static final String id_const = "overclock";

    /**
     * 生产速率倍率档位表（索引 0 = 1 级）：1.5 = +50%、2.0 = +100%、4.0 = +300%。
     * 「生产效率」在本引擎中的实现即建筑 {@code efficiency}（见类注释）。
     */
    public float[] speedScales = {1.5f, 2.0f, 4.0f};

    /**
     * 电力倍率档位表（索引 0 = 1 级）：1.3 = +30%、2.25 = +125%、6.0 = +500%。
     */
    public float[] powerScales = {1.3f, 2.25f, 6.0f};

    /**
     * 掉血速率档位表（索引 0 = 1 级，单位：生命/秒）：4 / 10 / 45。
     * 以 {@link #damageInterval} 秒为周期离散扣除（见 {@link #tickDamage}），故实际扣血量 = 速率 × 周期。
     */
    public float[] damageRates = {4f, 10f, 45f};

    /** 扣血周期（秒）：每隔这么久扣一次「速率 × 周期」点生命。 */
    public float damageInterval = 1f;

    /** 每建筑累计的扣血计时（秒）。在 remove() 中清理，见类注释。 */
    private static final ObjectMap<Building, Float> damageTimers = new ObjectMap<>();

    @Override
    public String id(){
        return id_const;
    }

    @Override
    public String name(){
        return Core.bundle.get("boost.overclock.name", "Overclock");
    }

    /** 某档的显示名（含档位号），如「2级超频」。 */
    public String name(int level){
        return Core.bundle.format("boost.overclock.levelName", Math.max(1, level));
    }

    // 目标上的显示名：带其实际生效档位，供消息/面板逐建筑展示
    @Override
    public String name(Building target){
        int level = BuildingBoostSystem.levelOf(target, id());
        return level > 0 ? name(level) : name();
    }

    // 目标上的显示描述：只给当前生效档的加成（不列全部三档）
    @Override
    public String description(Building target){
        int level = BuildingBoostSystem.levelOf(target, id());
        return level > 0 ? summary(level) : description();
    }

    @Override
    public String description(){
        // 描述须与实际生效值一致；逐档列出（实际生效档由 System 按提供者决定）
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < speedScales.length; i++){
            if(i > 0) sb.append('\n');
            sb.append(Core.bundle.format("boost.overclock.line", name(i + 1), summary(i + 1)));
        }
        return sb.toString();
    }

    /**
     * 某一档的加成摘要（单行、不含档位号），供 Provider 的配置面板显示。
     * 与 {@link #description()} 共用同一份数值与文案，避免两处各写一遍而不同步。
     *
     * @param level 档位（自 1 起；越界夹到最近合法档）
     */
    public String summary(int level){
        // 逐表独立取档（levelValue），不用「一张表算出的下标去索引另一张表」：
        // 三张表都是 public 可变字段，长度不一致时那种写法会抛 AIOOBE。
        // 掉血表另走显式判空（空表 = 不掉血，印 0 而不是 levelValue 的默认 1f）。
        float damage = damageRates.length == 0 ? 0f
            : damageRates[BuildingBoostSystem.levelIndex(level, damageRates.length)];
        return Core.bundle.format("boost.overclock.bonus",
            BuildingBoostSystem.percentText(BuildingBoostSystem.levelValue(speedScales, level) - 1f),
            BuildingBoostSystem.percentText(BuildingBoostSystem.levelValue(powerScales, level) - 1f),
            (int)damage);
    }

    // 目标过滤与节能完全一致：耗电工厂（consPower != null 且 GenericCrafter）
    @Override
    public boolean canTarget(Building target){
        Block block = target.block;
        return block != null && block.consPower != null && block instanceof GenericCrafter;
    }

    // 与节能同口径：工厂已启用才生效（被玩家关闭的工厂不超频、不掉血）
    @Override
    public boolean shouldApply(Building target){
        return target.enabled;
    }

    @Override
    public void apply(Building target){
        // 惰性安装引擎钩子（与节能共用同一对钩子，无需重复安装）
        BlockConsumerHooks.install(target.block);

        // 档位由 System 按目标回查（Provider 持有档位）——本效果是单例，不能存实例字段
        int level = BuildingBoostSystem.levelOf(target, id());
        if(level <= 0) return;

        // 提速：efficiency 恒 ≤ 1（引擎取最小值、初值即 1），无法用来加速，
        // 故按档位向 progress 追加「超出 1 倍的那一份」。见 BlockConsumerHooks#boostProgress。
        // 档位表被置空（异常配置）时按「不改变任何量」处理：既不提速也不掉血，而不是抛数组越界。
        if(speedScales.length > 0){
            BlockConsumerHooks.boostProgress(target, speedScales[BuildingBoostSystem.levelIndex(level, speedScales.length)]);
        }

        if(damageRates.length > 0){
            tickDamage(target, damageRates[BuildingBoostSystem.levelIndex(level, damageRates.length)]);
        }
    }

    @Override
    public void remove(Building target){
        // 清理每建筑计时，避免表项随建筑增删无限增长
        damageTimers.remove(target);
    }

    /** 换图/读档：清空掉血计时表（其键是 Building，会把旧世界钉在内存里）。 */
    @Override
    public void onWorldReset(){
        damageTimers.clear();
    }

    // 与「节能」互斥：同一概念的两个相反档位，不应叠加（否则 0.8×1.75、0.9×1.5 得 hybrids）
    @Override
    public boolean conflictsWith(String otherId){
        return EnergySavingBoost.instance.id().equals(otherId);
    }

    @Override
    public BuildingBoostSystem.BoostVisual visual(Building target){
        return OverclockVisual.instance;
    }

    // —— FactorSource：仅在本效果于该建筑上生效时被调用 ——
    // level 由钩子按建筑回查（Provider 持有档位，见 BuildingBoostSystem#levelOf）

    @Override
    public float powerFactor(Building build, int level){
        return BuildingBoostSystem.levelValue(powerScales, level);
    }

    @Override
    public float speedFactor(Building build, int level){
        return BuildingBoostSystem.levelValue(speedScales, level);
    }

    /**
     * 扣血计时：{@code apply} 每 tick 调用一次，故按 tick 累计真实秒数
     * （{@code Time.delta} 每 tick ≈ 1，{@code /60} 折算为秒，与注入器的耗油口径一致）。
     * 每跨过 {@link #damageInterval} 秒扣一次「{@code ratePerSec × 间隔}」点生命，
     * 并保留余量以免长期漂移。离散扣除（而非逐 tick 连续扣）是为了保留原版受击反馈
     * （{@code Building.damage} 会刷新 {@code hitTime}，逐 tick 扣会让建筑常驻受击色）。
     */
    private void tickDamage(Building target, float ratePerSec){
        if(ratePerSec <= 0f || damageInterval <= 0f) return;

        float timer = damageTimers.get(target, 0f) + Time.delta / 60f;
        if(timer < damageInterval){
            damageTimers.put(target, timer);
            return;
        }

        damageTimers.put(target, timer - damageInterval);
        target.damage(ratePerSec * damageInterval);
    }

    /**
     * 视觉描述：强化徽记（<b>统一图标</b>，见 {@link BuildingBoostSystem#badgeIcon()}），底板取火焰橙
     * （{@link Pal#lightFlame}）表达过热风险，与节能的电量蓝、润滑油的默认绿区分开。
     */
    private static class OverclockVisual implements BuildingBoostSystem.BoostVisual{
        static final OverclockVisual instance = new OverclockVisual();

        @Override
        public arc.graphics.g2d.TextureRegion icon(Building target){
            return BuildingBoostSystem.badgeIcon();   // 统一图标，见 BuildingBoostSystem.badgeIcon()
        }

        @Override
        public Color color(){
            return Pal.lightFlame;
        }
    }
}
