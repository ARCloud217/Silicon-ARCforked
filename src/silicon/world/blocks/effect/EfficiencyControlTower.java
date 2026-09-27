package silicon.world.blocks.effect;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import silicon.util.BuildingBoostSystem;
import silicon.util.boosts.EnergySavingBoost;
import silicon.util.boosts.OverclockBoost;

import static mindustry.Vars.tilesize;

/**
 * 效率控制塔：3x3 支援方块（{@link BuildingBoostSystem.Provider}）。以塔为中心、{@code 15×15} 格
 * 的方形区域内，<b>消耗电力的己方工厂</b>按模式附上对应强化：
 *
 * <ul>
 *   <li><b>关闭</b>：不提供任何强化；</li>
 *   <li><b>节能</b>（{@link EnergySavingBoost}）：电力 −20%、生产速度 −10%；</li>
 *   <li><b>超频</b>（{@link OverclockBoost}）：生产速度 +50%、耗电 +75%，且<b>每 3 秒扣 10 点生命</b>
 *       （血量归零即正常爆炸拆除）。</li>
 * </ul>
 *
 * <p>模式由配置面板的滑块切换（走标准 {@code Call.tileConfig} 链路，联网全端一致，
 * 存盘经 {@code write}/{@code read} 持久化）。
 *
 * <p><b>关键实现约束：{@code boosts()} 与模式无关，永远返回完整效果列表。</b>
 * System 撤销某 Provider 贡献的唯一路径是「该 Provider 仍被遍历到、但对某个 boost id 的意愿为 false」
 * （见 {@code collectContributions}）。若某模式下让 {@code boosts()} 返回空列表、或只返回「该模式之外」
 * 的效果、或让 {@code targets()} 返回空列表，System 会因 {@code targets().isEmpty()} 提前 return，
 * <b>旧模式的贡献永远不会被撤销</b>——区域内工厂会被永久锁死在旧模式。
 * 故模式判断放在逐效果的 {@link #provides(BuildingBoostSystem.Boost)}（返回 false 即触发正规撤销路径），
 * {@code canTarget} 只判「本机是否开机」，而 {@code targets()} 与模式无关地照常返回区域内耗电建筑。
 *
 * <p>范围查询走本队 {@code buildingTree} 空间树（与 ItemTransferHub 同款），并按 tick 缓存复用同一 Seq。
 *
 * <p>门禁：只作用于<b>同队</b>工厂，队伍比较用 {@code ==}（{@link mindustry.game.Team} 枚举单例，
 * 跨端一致，天然排除敌队/derelict），多人安全。本塔不消耗任何资源，故不涉及网络化状态的扣减。
 */
public class EfficiencyControlTower extends Block{

    /** 本方块提供的效果单元：全方块类型共享一份只读 Seq，避免每帧新建。 */
    private final Seq<BuildingBoostSystem.Boost> boostList =
        Seq.with(EnergySavingBoost.instance, OverclockBoost.instance);

    public EfficiencyControlTower(String name){
        super(name);
        update = true;
        solid = true;
        configurable = true;
        saveConfig = true;
        copyConfig = true;

        // 模式经标准 config 链路同步（客户端 configure → Call.tileConfig → 两端 configured → 本处理器）
        config(Integer.class, (EfficiencyControlTowerBuild b, Integer value) -> {
            if(value != null){
                b.mode = Mathf.clamp(value, 0, EfficiencyControlTowerBuild.maxMode);
            }
        });
    }

    /** 影响区域边长（格）：以本方块中心为中心的正方形区域。 */
    public float range = 15f;

    /** 区域半边长（像素）：range 为奇数时中心恰好落在中间一格上。 */
    public float halfRangePx(){
        return (range - 1f) / 2f * tilesize;
    }

    /** 区域边长（像素）：两个塔的中心距小于该值即视为范围重叠。 */
    public float rangePx(){
        return range * tilesize;
    }

    /**
     * 由「放置锚点格坐标」求本方块实例的几何中心（像素）。
     *
     * <p>放置预览与放置校验都用它，保证「看到的范围」「校验的范围」「建成后实际生效的范围」三者同口径。
     * 运行期则直接用建筑自身的 {@code x}/{@code y}（引擎写入，几何中心）。
     */
    public float centerX(int tileX){
        return tileX * tilesize + (size - 1) / 2f * tilesize + tilesize / 2f;
    }

    public float centerY(int tileY){
        return tileY * tilesize + (size - 1) / 2f * tilesize + tilesize / 2f;
    }

    /**
     * 放置校验：与<b>同队</b>已建成的效率控制塔范围重叠则拒绝放置。
     *
     * <p>只拦同队：敌队塔与本塔的覆盖对象本就不重叠（各自只强化本队工厂），
     * 互相拦只会被敌方用来「用一座废塔废掉你的塔」，故不拦。
     *
     * <p>{@code canPlaceOn} 对覆盖的每一格都会被调用，故用传入格的坐标推算中心：
     * 3x3 的锚点是覆盖区左下角，故中心 = 锚点 + 1 格 + 半格。
     * 该判定客户端与服务器都会执行（规则由本方法单方面决定，不依赖本地状态），故两端一致。
     */
    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation){
        if(!super.canPlaceOn(tile, team, rotation)) return false;

        return !rangeConflicts(centerX(tile.x), centerY(tile.y), team, null);
    }

    /**
     * 范围重叠判定：中心距在两轴上都小于 {@link #rangePx()} 即重叠（恰好相切不算）。
     *
     * @param cx,cy 待判定区域的中心（像素）
     * @param team  只与该队的塔比较
     * @param exclude 排除的建筑（自身），可为 null
     */
    public boolean rangeConflicts(float cx, float cy, Team team, Building exclude){
        var tree = team.data().buildingTree;
        if(tree == null) return false;

        float half = rangePx();
        boolean[] conflict = {false};
        // 只查询本塔自身范围（最宽的判定域），再逐个比对中心距
        tree.intersect(cx - half, cy - half, half * 2f, half * 2f, b -> {
            if(conflict[0] || b == null || b == exclude) return;
            if(!(b.block instanceof EfficiencyControlTower)) return;
            if(b.team != team) return;
            if(Math.abs(b.x - cx) < half && Math.abs(b.y - cy) < half){
                conflict[0] = true;
            }
        });
        return conflict[0];
    }

    /** 放置预览：画出影响区域（与选中时同口径）；与同队塔重叠时画红色。 */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        super.drawPlace(x, y, rotation, valid);

        float cx = centerX(x), cy = centerY(y);
        boolean free = valid && !rangeConflicts(cx, cy, Vars.player.team(), null);
        if(!free){
            drawArea(cx, cy, halfRangePx(), Pal.remove, 0.1f, 0.7f);
            return;
        }
        drawArea(cx, cy, halfRangePx(), Pal.placing, 0.08f, 0.4f);
    }

    /**
     * 画出影响区域：半透明填充 + 描边。
     *
     * <p><b>arc 的两个 API 锚点不一致，务必注意</b>：
     * <ul>
     *   <li>{@code Fill.rect}（经 {@code Draw.rect} → {@code Batch.draw(x - w/2, y - h/2, ...)}）
     *       是<b>中心锚点</b>；</li>
     *   <li>{@code Lines.rect(x, y, w, h)}（6 参重载按 center=(0,0) 换算）是<b>左下角锚点</b>。</li>
     * </ul>
     * 两者传同一组坐标必然错位，故这里分别按各自锚点换算——否则会出现「填充对了边框错 /
     * 边框对了填充错」的反复现象。
     */
    public static void drawArea(float cx, float cy, float half, Color color, float fillAlpha, float lineAlpha){
        float size = half * 2f;
        // 填充：中心锚点，直接传中心
        Draw.color(color, fillAlpha);
        Fill.rect(cx, cy, size, size);
        // 描边：左下角锚点，须减去半边长
        Draw.color(color, lineAlpha);
        Lines.stroke(1f);
        Lines.rect(cx - half, cy - half, size, size);
        Draw.reset();
    }

    public class EfficiencyControlTowerBuild extends Building implements BuildingBoostSystem.Provider{

        /** 模式：关闭。 */
        public static final int modeOff = 0;
        /** 模式：节能（区域内耗电工厂附上 EnergySavingBoost）。 */
        public static final int modeEnergySaving = 1;
        /** 模式：超频（区域内耗电工厂附上 OverclockBoost：更快、更耗电、持续掉血）。 */
        public static final int modeOverclock = 2;

        /** 模式上界（滑块最大档）。 */
        public static final int maxMode = modeOverclock;

        /** 当前模式（0=关闭 / 1=节能 / 2=超频）。经 config 链路同步、经 write/read 持久化。 */
        int mode = modeOff;

        /** 区域内耗电建筑缓存：按 tick 重建并复用同一 Seq（零分配）。 */
        private final Seq<Building> targetCache = new Seq<>();
        private double cacheTick = Double.MIN_VALUE;

        /**
         * 本 tick 是否与其他同队塔范围重叠（重叠即不运行）。
         * 每 tick 在 {@link #update()} 开头重算，供 {@link #provides} 读取。
         */
        private boolean conflicted;

        @Override
        public Building building(){
            return this;
        }

        // 与模式无关地返回完整列表：见类注释「关键实现约束」——空列表/缺项会让旧模式的贡献无法被撤销
        @Override
        public Seq<BuildingBoostSystem.Boost> boosts(){
            return boostList;
        }

        // 逐效果开关：当前模式决定本机提供哪个效果（关闭 → 都不提供）；
        // 且范围与其他同队塔重叠时（conflicted）一律不提供——放置校验之外的兜底（旧存档/强制放置）。
        @Override
        public boolean provides(BuildingBoostSystem.Boost boost){
            return !conflicted && mode == modeOf(boost.id());
        }

        // 本机是否开机（粗筛，逐效果的模式判断在 provides）
        @Override
        public boolean canTarget(Building target){
            return enabled;
        }

        /** 该效果 id 对应的模式档位。 */
        private int modeOf(String boostId){
            if(boostId == null) return modeOff;
            if(EnergySavingBoost.instance.id().equals(boostId)) return modeEnergySaving;
            if(OverclockBoost.instance.id().equals(boostId)) return modeOverclock;
            return modeOff;
        }

        // 前置目标过滤：区域内「耗电」建筑才交给 System（非耗电对象不进 System 循环）
        @Override
        public Seq<Building> targets(){
            if(cacheTick != Vars.state.tick){
                rebuildTargets();
                cacheTick = Vars.state.tick;
            }
            return targetCache;
        }

        /** 按 tick 重建区域内耗电建筑列表（走本队空间树，非逐格扫描）。 */
        private void rebuildTargets(){
            targetCache.clear();

            var tree = team.data().buildingTree;
            if(tree == null) return;

            float half = halfRangePx();
            tree.intersect(x - half, y - half, half * 2f, half * 2f, b -> {
                if(b == null || b == this) return;
                // 队伍用 !=（Team 枚举单例，跨端一致）；同队判定 System 也会再做一次，这里只为减少无用登记
                if(b.team != team) return;
                // 只把耗电建筑交给 System（工厂判定由 EnergySavingBoost.canTarget 负责）
                if(b.block == null || b.block.consPower == null) return;
                targetCache.add(b);
            });
        }

        // 拆除/摧毁时撤销本机提供的一切强化，避免贡献残留
        @Override
        public void onRemoved(){
            super.onRemoved();
            removeProviderBoosts();
        }

        @Override
        public void update(){
            super.update();

            // 先重算「是否与其他同队塔范围重叠」：重叠则本塔不运行（provides 全 false → 贡献被撤销）。
            // 必须在 updateBoosts() 之前算完——provides() 在登记过程中被同步读取。
            conflicted = ((EfficiencyControlTower)block).rangeConflicts(x, y, team, this);

            // 一行接入 System：资格/队伍/名单/不叠加/apply-remove 均由 System 统一驱动。
            // 模式为「关闭」或范围冲突时仍须调用（靠 provides/canTarget 返回 false 走撤销路径）。
            updateBoosts();
        }

        // —— 配置面板：模式滑块（关闭 / 节能 / 超频）——

        @Override
        public void buildConfiguration(Table table){
            table.top();

            Table inner = new Table();
            inner.background(Tex.pane);
            inner.margin(8f, 10f, 8f, 10f);
            table.add(inner).growX();

            Label value = new Label(Core.bundle.get(modeKey(mode)), Styles.defaultLabel);
            value.setColor(Pal.accent);

            inner.add(Core.bundle.get("block.silicon-efficiency-control-tower.modeLabel")).left().padRight(8f);
            // 步长 1 的滑块 = 三个离散档位（0 关闭 / 1 节能 / 2 超频）
            inner.slider(0f, maxMode, 1f, mode, v -> {
                int m = Mathf.round(v);
                value.setText(Core.bundle.get(modeKey(m)));
                configure(m);
            }).growX().height(28f).padRight(8f);
            inner.add(value).right();
            inner.row();
        }

        @Override
        public Object config(){
            return mode;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.s(mode);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            mode = Mathf.clamp(read.s(), 0, maxMode);
        }

        // 选中时显示影响区域；与其他同队塔范围重叠（旧存档/强制放置）时画红色提示「本塔未运行」
        @Override
        public void drawSelect(){
            super.drawSelect();
            if(conflicted){
                drawArea(x, y, halfRangePx(), Pal.remove, 0.1f, 0.7f);
            }else{
                drawArea(x, y, halfRangePx(), Pal.accent, 0.08f, 0.4f);
            }
        }

        private static String modeKey(int mode){
            return switch(mode){
                case modeEnergySaving -> "block.silicon-efficiency-control-tower.mode.energySaving";
                case modeOverclock -> "block.silicon-efficiency-control-tower.mode.overclock";
                default -> "block.silicon-efficiency-control-tower.mode.off";
            };
        }
    }
}
