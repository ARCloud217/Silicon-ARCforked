package silicon.world.blocks.defense;

import arc.math.Angles;
import arc.struct.Seq;
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
 * 成员均公开。只要注入器内存有润滑油，就对所有紧贴的己方炮塔在同一目标角上再推一格
 * ({@code rotationBoost})×同式，等效转角速率提升至 {@code ×2}(+100%，绕过 rotateSpeed 上限、
 * 随超速乘法叠加、与射速无关)。目标角随引擎各分支而取：自动索敌用 {@code targetPosition(target)}
 * 的预测瞄准点，玩家控制用 {@code unit.aimX/aimY}（引擎受控分支同样以该瞄准点写 targetPos），
 * 逻辑控制直接用 logic 写入的 targetPos——保证注入与引擎目标角一致、不互相抵消。
 * 该强化只要求「有润滑液」且炮塔有弹药，不额外耗油。
 *
* <p>门禁（与原版语义一致）：只对同队炮塔生效（含 derelict 判定），润滑油输入也只接受同队供给。
 * 多人游戏下 {@link Team} 是跨端一致的枚举单例，直接以 {@code ==} 比较即网络安全、确定的同队判断——
 * 不依赖本地身份/客户端次数，杜绝借客户端视角伪造队伍。
 *
 * <p>规则名单：{@code boostTargets} 列出「可被强化」的方块类型，只有命中名单的方块才允许应用任何强化
 * （攻速/转角）。默认仅放行 {@link Turret.TurretBuild}（炮塔）。所有强化在应用前都会先经
 * {@link #isBoostable(Building)} 检查，名单外或未实现强化的类型一律直接跳过，
 * 绝不会把炮塔专用 API 用到非炮塔方块上造成崩溃。
 */
public class LubricantInjector extends Block {

    /** 攻速加成：每 tick 注入的固定充能点数（参考基准 efficiency=timeScale=ammoRM=1 时即 +20% 射速） */
    public float fireBoost = 0.2f;
/** 转角速率加成倍率：1.0 = 旋转速度 ×2（+100%） */
    public float rotationBoost = 1.0f;
/** 每个正在攻击的受惠炮塔的润滑油消耗（单位/秒） */
    public float consumePerTurret = 5f;
    /** 规则名单：只有命中名单的方块类型才可被强化（默认仅放行炮塔 TurretBuild） */
    public Seq<Class<? extends Building>> boostTargets = new Seq<>(Class.class);

    public LubricantInjector(String name) {
        super(name);
        update = true;
        solid = true;
        hasLiquids = true;
        outputsLiquid = false;
        liquidCapacity = 300f;
        boostTargets.add(Turret.TurretBuild.class);
    }

    /** 向规则名单追加一种可被强化的方块类型。 */
    public void addBoostTarget(Class<? extends Building> type) {
        boostTargets.add(type);
    }

    /** 规则名单检查：b 是名单中任一类型的实例才可被强化（null 恒为否）。 */
    public boolean isBoostable(Building b) {
        if (b == null) {
            return false;
        }
        for (Class<? extends Building> type : boostTargets) {
            if (type.isInstance(b)) {
                return true;
            }
        }
        return false;
    }

public class LubricantInjectorBuild extends Building {

        /** 己方判定：多人下 Team 为跨端一致的枚举单例，== 即网络安全同队判（与仓库其余方块一致，天然排除敌队/derelict）。 */
        private boolean sameTeam(Building b) {
            return b != null && b.team == team;
        }

        @Override
        public void update() {
            super.update();

            boolean hasLubricant = liquids.get(lubricant) > 0.001f;

            // —— 攻速强化：仅统计「己方 + 命中规则名单 + 正在攻击」的炮塔（isShooting），攻击才消耗 ——
            int attacking = 0;
            for (Building b : proximity) {
                if (!sameTeam(b) || !isBoostable(b) || !(b instanceof Turret.TurretBuild)) {
                    continue;
                }
                Turret.TurretBuild t = (Turret.TurretBuild) b;
                if (t.isShooting()) {
                    attacking++;
                }
            }

// 有润滑油且有炮塔在攻击：按数量扣油（Time.delta≈1/60fps帧，除 60 折算为真实秒→5/s），
            // 并向攻击中的炮塔按「强化液同池」注入固定充能点数
            if (attacking > 0 && hasLubricant) {
                float held = liquids.get(lubricant);
                float need = consumePerTurret * attacking * Time.delta / 60f;
                liquids.remove(lubricant, Math.min(need, held));

                for (Building b : proximity) {
                    if (!sameTeam(b) || !isBoostable(b) || !(b instanceof Turret.TurretBuild)) {
                        continue;
                    }
                    Turret.TurretBuild t = (Turret.TurretBuild) b;
                    if (t.isShooting()) {
                        float ammoRM = t.hasAmmo() ? t.peekAmmo().reloadMultiplier : 1f;
                        t.reloadCounter += fireBoost * t.edelta() * ammoRM;
                    }
                }
            }

// —— 转角强化：只要注入器内有润滑液，命中规则名单的己方炮塔旋转速度 ×2（无需攻击、不额外耗油）——
            if (hasLubricant) {
                for (Building b : proximity) {
                    // 应用前先检查队伍 + 规则名单；名单外、或名单内但未实现强化的类型一律跳过，
                    // 绝不把炮塔专用 API 应用到非炮塔方块（避免误用导致崩溃）。
                    if (!sameTeam(b) || !isBoostable(b) || !(b instanceof Turret.TurretBuild t)) {
                        continue;
                    }
                    if (!t.hasAmmo()) {
                        continue;
                    }
                    // 目标角必须与引擎各分支同源：玩家控制=unit 瞄准角(鼠标)；逻辑控制=logic 写入的 targetPos；
                    // 自动索敌=targetPosition(target) 刷新预测瞄准点。否则注入目标与引擎目标不一致会互相抵消。
                    float des;
                    if (t.controlled()) {
                        des = Angles.angle(t.x, t.y, t.unit.aimX(), t.unit.aimY());
                    } else if (t.logicControlled()) {
                        des = t.angleTo(t.targetPos);
                    } else if (t.target != null) {
                        t.targetPosition(t.target);
                        des = t.angleTo(t.targetPos);
                    } else {
                        continue;
                    }
                    if (t.shouldTurn()) {
                        t.rotation = Angles.moveToward(t.rotation, des,
                                rotationBoost * ((BaseTurret) t.block).rotateSpeed * t.delta() * t.potentialEfficiency);
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
