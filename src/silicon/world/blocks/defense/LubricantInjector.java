package silicon.world.blocks.defense;

import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
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
 * 锁油量 + 认领检查（canTarget：存油 且 本机独占认领该目标才提供）、按「本机独占认领且攻击中」的
 * 炮塔数扣油、声明自己提供润滑油 Boost。资格/队伍/互斥/撤销生命周期全由 System 兜底。
 *
 * <p><b>认领制</b>（落实「不叠加 + 唯生效者耗油」）：同一炮塔即使两台注入器紧贴，也只有认领者提供效果
 * 并扣油，另一台主动让出（不叠加，不会成倍放大/双倍油耗）。认领用<strong>确定性规则</strong>——紧贴同一炮塔的
 * 同队有油注入器中 (tileX, tileY) 字典序最小者胜出；tile 坐标客户端/服务器一致，故两端认领者相同、
 * 扣同一台机器的油，<strong>液体量不会分歧（多人安全）</strong>。
 *
 * <p>门禁（与原版语义一致）：只对同队炮塔/同队供给生效，队伍比较一律用 {@code ==}
 * （{@link mindustry.game.Team} 枚举单例，跨端一致，天然排除敌队/derelict），多人安全。
 */
public class LubricantInjector extends Block {

    /** 每个正在攻击的受惠炮塔的润滑油消耗（单位/秒） */
    public float consumePerTurret = 5f;

    /** 本方块提供的效果单元列表：全方块共享一份只读 Seq，避免每帧每目标新建。 */
    private final Seq<BuildingBoostSystem.Boost> boostList = Seq.with(LubricantBoost.instance);

    public LubricantInjector(String name) {
        super(name);
        update = true;
        solid = true;
        hasLiquids = true;
        outputsLiquid = false;
        liquidCapacity = 300f;
    }

    public class LubricantInjectorBuild extends Building implements BuildingBoostSystem.Provider {

        /** 紧贴炮塔缓存：按 tick 重建并复用同一 Seq（零分配，供 targets() 与扣油共用）。 */
        private final Seq<Building> turretCache = new Seq<>();
        private double cacheTick = Double.MIN_VALUE;

        @Override
        public Building building() {
            return this;
        }

        @Override
        public Seq<BuildingBoostSystem.Boost> boosts() {
            return boostList;
        }

        // 前置目标过滤：只把炮塔交给 System；非炮塔对象不尝试附 boost（不进 System 循环，避免无谓登记）
        @Override
        public Seq<Building> targets() {
            if (cacheTick != Vars.state.tick) {
                rebuildTurretCache();
            }
            return turretCache;
        }

        /** 按 tick 重建紧贴炮塔列表（仅扫一次 proximity，扣油阶段复用同一份结果）。 */
        private void rebuildTurretCache() {
            turretCache.clear();
            for (Building b : proximity) {
                if (b instanceof Turret.TurretBuild) {
                    turretCache.add(b);
                }
            }
            cacheTick = Vars.state.tick;
        }

        // 存油 + 本机是该目标的「确定性认领者」才提供（多人安全：认领者由 tile 坐标决定，不依赖 update 顺序）
        @Override
        public boolean canTarget(Building target) {
            return liquids.get(lubricant) > 0.001f && owns(target);
        }

        /**
         * 确定性认领：紧贴同一炮塔的多台注入器里，按 (tileX, tileY) 字典序最小者独占该炮塔的强化与油耗。
         *
         * <p><b>多人安全</b>：tile 坐标来自地图、客户端与服务器完全一致，因此两端算出同一个认领者，
         * 扣的也是同一台机器的油——不会因 update 顺序差异导致液体量分歧（order-based 认领会）。
         *
         * <p>候选须「同队 + 存油」；本机不满足则直接出局。持认领者油耗尽/失效后认领自然交接。
         */
        private boolean owns(Building target) {
            int myX = tileX(), myY = tileY();
            for (Building b : target.proximity) {
                // 跳过自己与非本方块
                if (b == this || !(b instanceof LubricantInjectorBuild other)) {
                    continue;
                }
                // 队伍用 ==（Team 枚举单例，跨端一致）——只与同队注入器竞争
                if (other.team != team) {
                    continue;
                }
                // 无油的注入器不参与竞争（不提供、也不该挡住本机）
                if (other.liquids.get(lubricant) <= 0.001f) {
                    continue;
                }
                int ox = other.tileX(), oy = other.tileY();
                if (ox < myX || (ox == myX && oy < myY)) {
                    return false; // 存在坐标更小的候选，本机让出
                }
            }
            return true;
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

            // 再按「本机独占认领且攻击中」的己方炮塔数扣油（Time.delta≈1/60fps帧，除 60 折算为真实秒→5/s）；
            // 认领用与 canTarget 相同的确定性判定（owns）——与 System 登记口径一致，且客户端/服务器算出的
            // 认领者相同，扣的同一台机器的油，液体量不分歧（多人安全）。
            // 与攻速 Boost 的激活口径一致（isShooting）。无认领/无攻击时零消耗。
            if (liquids.get(lubricant) > 0.001f) {
                // 复用 targets() 的本 tick 缓存，不再重扫 proximity
                Seq<Building> turrets = targets();
                int provided = 0;
                for (int i = 0, n = turrets.size; i < n; i++) {
                    Building b = turrets.get(i);
                    if (b instanceof Turret.TurretBuild t && t.isShooting()
                            && owns(b)
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