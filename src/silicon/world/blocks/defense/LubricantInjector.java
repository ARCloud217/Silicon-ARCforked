package silicon.world.blocks.defense;

import mindustry.gen.Building;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;

import static silicon.content.liquid.Liquids.lubricant;

/**
 * 润滑油注入器：2x2 支援方块，消耗润滑油为「紧贴」的己方炮塔提供射速加成。
 *
 * <p>效果：注入器按受惠炮塔数量 × consumePerTurret 持续消耗润滑油（默认 5/s 每个）；
 * 润滑油不足时全部炮塔不享受加成（耗量与效果强绑定，不赊账）。
 *
 * <p>射速加成实现（v159.7 换弹模型）：炮塔充能节奏由
 * {@code ReloadTurretBuild.reloadCounter} 每 tick 经 updateReload() 累加
 * {@code delta() × ammoReloadMultiplier() × baseReloadSpeed()（=efficiency）}，达到
 * {@code Turret.reload} 阈值即开火并取模清零。本方块每 tick 再对相邻炮塔注入同构公式的
 * ({@code boostMultiplier} - 1)（默认 0.5）倍，净充能即 boostMultiplier 倍 -> 射速精确 150%。
 * {@code reloadCounter}、{@code efficiency} 为 public 字段，{@code delta()}/{@code hasAmmo()}/
 * {@code peekAmmo()} 为公开方法；注入为纯加法，与炮塔自身 update 执行顺序无关，无竞态。
 *
 * <p>门禁（与原版语义一致）：只对同队炮塔生效（含 derelict 判定），润滑油输入也只接受同队供给。
 */
public class LubricantInjector extends Block {

    /** 射速倍率：1.5 = 攻击速度提升至 150% */
    public float boostMultiplier = 1.5f;
    /** 每个受惠炮塔的润滑油消耗（单位/秒） */
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

            // 统计紧邻的己方炮塔数量（proximity 已包含贴建的所有相邻建筑）
            int turrets = 0;
            for (Building b : proximity) {
                if (b.team == team && b instanceof Turret.TurretBuild) {
                    turrets++;
                }
            }

            // 润滑油不足时视为无加成，耗量与效果强绑定
            if (turrets <= 0) {
                return;
            }
            float held = liquids.get(lubricant);
            if (held <= 0.001f) {
                return;
            }
            float need = consumePerTurret * turrets * edelta();
            liquids.remove(lubricant, Math.min(need, held));

            // 逐炮塔注入与引擎同构的充能增量，使净充能速率变为 boostMultiplier 倍
            float extra = boostMultiplier - 1f;
            for (Building b : proximity) {
                if (b.team == team && b instanceof Turret.TurretBuild) {
                    Turret.TurretBuild t = (Turret.TurretBuild) b;
                    float ammoRM = t.hasAmmo() ? t.peekAmmo().reloadMultiplier : 1f;
                    t.reloadCounter += extra * t.delta() * ammoRM * t.efficiency;
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
