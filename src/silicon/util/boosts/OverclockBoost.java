package silicon.util.boosts;

import arc.Core;
import arc.graphics.Color;
import arc.struct.ObjectMap;
import arc.util.Time;
import mindustry.content.StatusEffects;
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
 *   <li><b>生产速度 +50%</b>：与节能同一入口——经 {@link BlockConsumerHooks.SpeedTaxConsume}
 *       把 {@code efficiency} 抬到 1.5，生产进度随之等比变快；</li>
 *   <li><b>耗电 +75%</b>：经 {@link BlockConsumerHooks.ScaledConsumePower} 把
 *       {@code requestedPower(build)} 乘 1.75，只提高该建筑自己的请求电量；</li>
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

    /** 生产速率倍率：1.5 = 生产速度 +50%。 */
    public float speedScale = 1.5f;

    /** 电力倍率：1.75 = 耗电 +75%。 */
    public float powerScale = 1.75f;

    /** 每次扣血量（生命值）。 */
    public float damage = 10f;

    /** 扣血间隔（秒）。 */
    public float damageInterval = 3f;

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

    @Override
    public String description(){
        // 描述需与实际生效值一致：生产 +50%、耗电 +75%、每 3 秒 -10 生命
        return Core.bundle.get("boost.overclock.desc",
            "+50% production speed; +75% power consumption; -10 HP every 3s");
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

        tickDamage(target);
    }

    @Override
    public void remove(Building target){
        // 清理每建筑计时，避免表项随建筑增删无限增长
        damageTimers.remove(target);
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

    @Override
    public float powerFactor(Building build){
        return powerScale;
    }

    @Override
    public float speedFactor(Building build){
        return speedScale;
    }

    /**
     * 扣血计时：{@code apply} 每 tick 调用一次，故按 tick 累计真实秒数
     * （{@code Time.delta} 每 tick ≈ 1，{@code /60} 折算为秒，与注入器的耗油口径一致）。
     * 跨过间隔即扣一次血，并保留余量以免长期漂移。
     */
    private void tickDamage(Building target){
        if(damage <= 0f || damageInterval <= 0f) return;

        float timer = damageTimers.get(target, 0f) + Time.delta / 60f;
        if(timer < damageInterval){
            damageTimers.put(target, timer);
            return;
        }

        damageTimers.put(target, timer - damageInterval);
        target.damage(damage);
    }

    /**
     * 视觉描述：原版「超速」状态图标（overdrive）——「超频」语义贴合，火焰橙底板
     * （{@link Pal#lightFlame}）表达过热风险，与节能的电量蓝、润滑油的默认绿区分开。
     */
    private static class OverclockVisual implements BuildingBoostSystem.BoostVisual{
        static final OverclockVisual instance = new OverclockVisual();

        @Override
        public arc.graphics.g2d.TextureRegion icon(Building target){
            return StatusEffects.overdrive.uiIcon;
        }

        @Override
        public Color color(){
            return Pal.lightFlame;
        }
    }
}
