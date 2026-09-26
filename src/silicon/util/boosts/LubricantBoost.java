package silicon.util.boosts;

import arc.Core;
import arc.graphics.Color;
import arc.math.Angles;
import mindustry.gen.Building;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import silicon.util.BuildingBoostSystem;

/**
 * 润滑油综合强化效果（注入式 Boost）：润滑油带给炮塔的全部强化打包为一个效果单元，内部
 * 包含两个子效果——每个子效果的生效条件独立判定，激活期间每帧调用 apply 时各自按需注入：
 *
 * <ul>
 *   <li><b>攻速</b>：炮塔 {@code isShooting()} 时，按强化液方式注入充能点数
 *       {@code reloadCounter += firePotency × edelta() × ammoReloadMultiplier}（与强化液同加法池）;</li>
 *   <li><b>转角</b>：炮塔 {@code hasAmmo() && shouldTurn()} 时，与引擎 turnToTarget 同式再推一格，
 *       目标角随引擎分支同源（玩家控制=unit 瞄准角、逻辑控制=logic 写入的 targetPos、
 *       自动索敌=targetPosition(target) 预测点），否则与引擎目标不一致会互相抵消。</li>
 * </ul>
 *
 * <p>两个子效果均注入式：条件消失即自动失效，无残留、无需 remove 还原。
 */
public class LubricantBoost implements BuildingBoostSystem.Boost {

    /** 单例：注册进 System 供注入器引用。 */
    public static final LubricantBoost instance = new LubricantBoost();

    static {
        BuildingBoostSystem.register(instance);
    }

    /** 攻速加成：每 tick 注入的固定充能点数（参考基准 efficiency=timeScale=ammoRM=1 时即 +20% 射速）。 */
    public float firePotency = 0.2f;

    /** 转角速率加成倍率：1.0 = 引擎 1× + 本效果 1× = 总转角 ×2（+100%）。 */
    public float rotationPotency = 1.0f;

    @Override
    public String id() {
        return "lubricant";
    }

    // 目标过滤：只作用于炮塔。System 登记前读取本方法，非炮塔目标不登记贡献、不会进 apply。
    @Override
    public boolean canTarget(Building target) {
        return target instanceof Turret.TurretBuild;
    }

    @Override
    public boolean shouldApply(Building target) {
        // 任一子效果具备生效条件即保持激活；具体注入逐项按需进行
        return target instanceof Turret.TurretBuild t && (t.isShooting() || (t.hasAmmo() && t.shouldTurn()));
    }

    @Override
    public void apply(Building target) {
        if (!(target instanceof Turret.TurretBuild t)) {
            return;
        }

        // —— 攻速子效果：仅攻击中的炮塔 ——
        if (t.isShooting()) {
            float ammoRM = t.hasAmmo() ? t.peekAmmo().reloadMultiplier : 1f;
            t.reloadCounter += firePotency * t.edelta() * ammoRM;
        }

        // —— 转角子效果：有弹药且允许转身时 ——
        if (t.hasAmmo() && t.shouldTurn()) {
            // 本帧无可瞄准角（目标无效/未锁定）则跳过，不干扰引擎
            float des;
            if (t.controlled()) {
                des = Angles.angle(t.x, t.y, t.unit.aimX(), t.unit.aimY());
            } else if (t.logicControlled()) {
                des = t.angleTo(t.targetPos);
            } else if (t.target != null) {
                t.targetPosition(t.target);
                des = t.angleTo(t.targetPos);
            } else {
                return;
            }
            t.rotation = Angles.moveToward(t.rotation, des,
                    rotationPotency * ((BaseTurret) t.block).rotateSpeed * t.delta() * t.potentialEfficiency);
        }
    }

    @Override
    public void remove(Building target) {
        // 注入式：条件消失即自动失效，无需还原
    }

    @Override
    public BuildingBoostSystem.BoostVisual visual(Building target) {
        return LubricantVisual.instance;
    }

    /** 调试视觉：在受惠炮塔上方显示润滑油强化名（颜色取润滑油液体的 8a5a2b）。 */
    private static class LubricantVisual implements BuildingBoostSystem.BoostVisual {
        static final LubricantVisual instance = new LubricantVisual();

        @Override
        public String label(Building target) {
            return Core.bundle.get("boost.lubricant.name", "lubricant");
        }

        @Override
        public Color color() {
            return Color.valueOf("8a5a2b");
        }
    }
}