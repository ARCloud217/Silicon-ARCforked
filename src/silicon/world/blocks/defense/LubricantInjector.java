package silicon.world.blocks.defense;

import arc.struct.Seq;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;
import silicon.util.BuildingBoostSystem;
import silicon.util.boosts.LubricantBoost;

import static silicon.content.liquid.Liquids.lubricant;

/**
 * 润滑油注入器：2x2 支援方块（{@link BuildingBoostSystem.Provider}），消耗润滑油，把润滑油
 * 带来的全部强化作为一个 {@link LubricantBoost} 交予强化系统驱动：
 *
 * <ul>
 *   <li>攻速：攻击中的炮塔攻速 +20%（与强化液同加法池，固定充能点入池）；仅炮塔攻击时消耗润滑油；</li>
 *   <li>转角：存油期间紧贴炮塔转角速率 ×2（自动索敌/玩家控制同等生效）。</li>
 * </ul>
 *
 * <p>本方块只负责四件事：前置目标过滤（targets()：只把炮塔交给 System，非可用对象连 System 都不会进入）、
 * 锁油量 + 认领检查（canTarget：存油 且 目标未被其它注入器抢先认领才提供）、按「本机实际认领且攻击中」的
 * 炮塔数扣油、声明自己提供润滑油 Boost。资格/队伍/互斥/撤销生命周期全由 System 兜底。认领制落实用户规则
 * 「不叠加 + 唯生效者耗油」：同一炮塔即使两台注入器紧贴，也只有先占到认领的那台提供效果并扣油，另一台检查到
 * 目标已有 boost 主动取消提供（不叠加，不会成倍放大/双倍油耗）。
 *
 * <p>门禁（与原版语义一致）：只对同队炮塔生效（含 derelict 判定），润滑油输入也只接受同队供给。
 */
public class LubricantInjector extends Block {

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

    public class LubricantInjectorBuild extends Building implements BuildingBoostSystem.Provider {

        @Override
        public Building building() {
            return this;
        }

        @Override
        public Iterable<BuildingBoostSystem.Boost> boosts() {
            return Seq.with(LubricantBoost.instance);
        }

        // 前置目标过滤：只把炮塔交给 System；非炮塔对象不尝试附 boost（不进 System 循环，避免无谓登记）
        @Override
        public Iterable<Building> targets() {
            Seq<Building> out = new Seq<>();
            for (Building b : proximity) {
                if (b instanceof Turret.TurretBuild) {
                    out.add(b);
                }
            }
            return out;
        }

        // 存油且未被其它注入器抢先认领才提供（认领制：目标已有润滑油 boost 时本机取消提供）
        @Override
        public boolean canTarget(Building target) {
            return liquids.get(lubricant) > 0.001f
                    && !BuildingBoostSystem.claimedByOther(target, this, LubricantBoost.instance.id());
        }

        // 拆除/摧毁时撤销自己提供的一切强化，避免贡献残留（System 立即重算受影响目标）
        @Override
        public void onRemoved() {
            super.onRemoved();
            removeProviderBoosts();
        }

        @Override
        public void update() {
            super.update();

            // 先接入 System：登记/更新本帧认领（资格、互斥、去重、apply/remove 均由 System 统一驱动）
            updateBoosts();

            // 再按「本机实际认领且攻击中」的己方炮塔数扣油（Time.delta≈1/60fps帧，除 60 折算为真实秒→5/s）；
            // 认领制下唯有生效者才扣油——被其它注入器抢先认领的炮塔本机不提供也不耗油。
            // 与攻速 Boost 的激活口径一致（isShooting）。无认领/无攻击时零消耗。
            if (liquids.get(lubricant) > 0.001f) {
                int provided = 0;
                for (Building b : proximity) {
                    if (BuildingBoostSystem.sameTeam(this, b)
                            && b instanceof Turret.TurretBuild t && t.isShooting()
                            && BuildingBoostSystem.isProviderOf(b, this, LubricantBoost.instance.id())) {
                        provided++;
                    }
                }
                if (provided > 0) {
                    float held = liquids.get(lubricant);
                    float need = consumePerTurret * provided * Time.delta / 60f;
                    liquids.remove(lubricant, Math.min(need, held));
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