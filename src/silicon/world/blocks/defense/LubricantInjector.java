package silicon.world.blocks.defense;

import arc.math.Angles;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.Turret;

import static silicon.content.liquid.Liquids.lubricant;

/**
 * 润滑油注入器：2x2 支援方块，消耗润滑油强化「紧贴」的己方炮塔。
 *
 * <p>攻速强化（v159.7 换弹模型）：炮塔充能由 {@code ReloadTurretBuild.reloadCounter} 每 tick 累加
 * {@code updateReload() = delta()×ammoReloadMultiplier()×efficiency}，达到 {@code Turret.reload}
 * 阈值即开火并取模清零；强化液则会在 updateCooling() 里往同一个计数器追加
 * {@code 冷却量×edelta()×heatCapacity×coolantMultiplier×ammoReloadMultiplier}。
 * 本方块照强化液的方式把 {@code fireBoost}（0.2 = 参考基准下 +20% 射速）以
 * {@code fireBoost×edelta()×ammoReloadMultiplier} 注入同一加法池：
 * <ul>
 *   <li>与强化液呈加法叠加（固定充能点数入池，不随效率/超速倍率放大），不会互相稀释出乘法偏离；</li>
 *   <li>仅当炮塔进入攻击状态（isShooting）才消耗润滑油（默认 5/s 每个正在攻击的炮塔），
 *       无炮塔攻击或润滑油耗尽时零消耗零加成。</li>
 * </ul>
 *
 * <p>转角强化（v159.7 旋转模型）：引擎转身为
 * {@code rotation = Angles.moveToward(rotation, 目标角, rotateSpeed×delta()×potentialEfficiency)}，
 * 成员均公开。只要注入器内存有润滑油，就对所有紧贴的己方炮塔再进一格
 * ({@code rotationBoost})×同式，等效转角速率 {@code +50%}（绕过 rotateSpeed 上限、随超速乘法叠加、
 * 与射速无关；无目标/锁定转身时不干扰）。该强化只要求「有润滑液」，不额外耗油。
 *
 * <p>门禁（与原版语义一致）：只对同队炮塔生效（含 derelict 判定），润滑油输入也只接受同队供给。
 */
public class LubricantInjector extends Block {

    /** 攻速加成：每 tick 注入的固定充能点数（参考基准 efficiency=timeScale=ammoRM=1 时即 +20% 射速） */
    public float fireBoost = 0.2f;
    /** 转角速率加成倍率：0.5 = 旋转速度 +50% */
    public float rotationBoost = 0.5f;
    /** 每个正在攻击的受惠炮塔的润滑油消耗（单位/秒） */
    public float consumePerTurret = 5f;

    public LubricantInjector(String name) {
        super(name);
        update = true;
        solid = true;
        hasLiquids = true;
        outputsLiquid = false;
        liquidCapacity = 300f;
    }

    public class LubricantInjectorBuild extends Building {

        @Override
        public void update() {
            super.update();

            boolean hasLubricant = liquids.get(lubricant) > 0.001f;

            // —— 攻速强化：仅统计正在攻击的己方炮塔（isShooting），攻击才消耗 ——
            int attacking = 0;
            for (Building b : proximity) {
                if (b.team == team && b instanceof Turret.TurretBuild) {
                    Turret.TurretBuild t = (Turret.TurretBuild) b;
                    if (t.isShooting()) {
                        attacking++;
                    }
                }
            }

// 有润滑油且有炮塔在攻击：按数量扣油（Time.delta≈1/60fps帧，除 60 折算为真实秒→5/s），
            // 并向攻击中的炮塔按「强化液同池」注入固定充能点数
            if (attacking > 0 && hasLubricant) {
                float held = liquids.get(lubricant);
                float need = consumePerTurret * attacking * Time.delta / 60f;
                liquids.remove(lubricant, Math.min(need, held));

                for (Building b : proximity) {
                    if (b.team == team && b instanceof Turret.TurretBuild) {
                        Turret.TurretBuild t = (Turret.TurretBuild) b;
                        if (t.isShooting()) {
                            float ammoRM = t.hasAmmo() ? t.peekAmmo().reloadMultiplier : 1f;
                            t.reloadCounter += fireBoost * t.edelta() * ammoRM;
                        }
                    }
                }
            }

            // —— 转角强化：只要注入器内有润滑液，全部紧贴的己方炮塔旋转速度 +50%（无需攻击、不额外耗油）——
            if (hasLubricant) {
                for (Building b : proximity) {
                    if (b.team == team && b instanceof Turret.TurretBuild) {
                        Turret.TurretBuild t = (Turret.TurretBuild) b;
if (t.target != null && t.shouldTurn()) {
                            // 与引擎 turnToTarget 完全一致：先刷新弹道预测瞄准点 targetPos，再取 angleTo(targetPos)，
                            // 否则注入目标(当前位置)与引擎目标(预测位置)打架抵消旋转加速
                            t.targetPosition(t.target);
                            float des = t.angleTo(t.targetPos);
                            t.rotation = Angles.moveToward(t.rotation, des,
                                    rotationBoost * ((BaseTurret) t.block).rotateSpeed * t.delta() * t.potentialEfficiency);
                        }
                    }
                }
            }
        }

        // 只接受同队供给的润滑油，且留有余量
        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            return source.team == team && liquid == lubricant && liquids.get(lubricant) < liquidCapacity - 0.001f;
        }
    }
}
