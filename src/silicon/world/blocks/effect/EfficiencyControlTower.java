package silicon.world.blocks.effect;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.graphics.Drawf;
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
 * 的方形区域内，<b>消耗电力的己方工厂</b>按档位附上对应强化：
 *
 * <ul>
 *   <li><b>关闭</b>（0，默认）：不提供任何强化；</li>
 *   <li><b>节能</b>（−1 ~ −3）：附上 {@link EnergySavingBoost}，逐档递增省电与减产；</li>
 *   <li><b>超频</b>（+1 ~ +3）：附上 {@link OverclockBoost}，逐档递增加速与耗电，并<b>持续扣血</b>。</li>
 * </ul>
 *
 * <p>档位由配置面板的<b>单个滑块</b>切换，范围 {@code [-3, +3]}、步长 1，故自左至右为
 * 「3级节能 → 2级 → 1级 → <b>关闭</b> → 1级超频 → 2级 → 3级」——关闭恰在正中。
 * 走标准 {@code Call.tileConfig} 链路，联网全端一致，存盘经 {@code write}/{@code read} 持久化。
 *
 * <p><b>档位由本塔持有、不存在效果单例里</b>：两个效果实现都是单例（倍率表在其中），
 * 若把档位存成实例字段，多台塔会互相覆盖。故档位经 {@link #levelOf(BuildingBoostSystem.Boost)}
 * 由本 Provider 提供，System/引擎钩子按目标回查（{@link BuildingBoostSystem#levelOf}）。
 * 由于同队两塔范围不得重叠（见下），一台工厂的档位本就唯一，回查最多命中一个提供者。
 *
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
                b.mode = Mathf.clamp(value, -EfficiencyControlTowerBuild.maxLevel,
                    EfficiencyControlTowerBuild.maxLevel);
            }
        });
    }

    /** 影响区域边长（格）：以本方块中心为中心的正方形区域。 */
    public float range = 15f;

    /**
     * 范围预览色（随模式）：关闭=淡灰、节能=绿、超频=红。
     * 供放置预览与选中时共用，保证「看到的颜色」与「当前模式」一致。
     */
    public static Color modeColor(int mode){
        if(mode < 0) return Pal.accent;   // 节能：绿
        if(mode > 0) return Pal.remove;   // 超频：红
        return Color.lightGray;           // 关闭：淡灰
    }

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
     * <p>口径与引擎一致：建筑位置 = {@code tile.worldx() + block.offset}（原版
     * {@code UnitAssembler.canPlaceOn} 等亦用此式；{@code Block.offset = ((size+1)%2)*8/2f}，
     * 3x3 为 0）。放置预览、放置校验都用它，保证「看到的范围」「校验的范围」
     * 「建成后实际生效的范围」三者同口径——早前按「锚点 + 1 格 + 半格」算会使预览向右上偏 12px。
     */
    public float centerX(int tileX){
        return tileX * tilesize + offset;
    }

    public float centerY(int tileY){
        return tileY * tilesize + offset;
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

    /**
     * 放置预览：影响区域着色；与同队塔范围重叠（或本身不可放置）时改为红色 + 红色提示文字。
     *
     * <p>预览色按<b>新塔的默认模式</b>（关闭 = 淡灰）绘制：模式是<b>每建筑</b>的配置，
     * 放置前并不存在（玩家从建造菜单拿到的永远是默认「关闭」），故预览只能是淡灰；
     * 选中已建成的塔时才显示其真实模式色（见 {@code drawSelect}）。
     *
     * <p>提示文字走原版 {@link #drawPlaceText(String, int, int, boolean)}（与钻头显示
     * 「挖掘速率」用的是同一个入口），传 {@code valid = false} 即以 {@link Pal#remove} 红色渲染。
     */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        super.drawPlace(x, y, rotation, valid);

        float cx = centerX(x), cy = centerY(y);
        if(valid && !rangeConflicts(cx, cy, Vars.player.team(), null)){
            drawArea(cx, cy, halfRangePx(), modeColor(EfficiencyControlTowerBuild.modeOff), 0.08f);
            return;
        }

        // 范围重叠（或本身不可放置）：红框 + 红色说明文字
        drawArea(cx, cy, halfRangePx(), Pal.remove, 0.1f);
        drawPlaceText(Core.bundle.get("block.silicon-efficiency-control-tower.rangeConflict"), x, y, false);
    }

    /**
     * 画出影响区域：半透明填充 + <b>原版风格虚线框</b>。
     *
     * <p><b>arc 的两个 API 锚点不一致，务必注意</b>：
     * <ul>
     *   <li>{@code Fill.rect}（经 {@code Draw.rect} → {@code Batch.draw(x - w/2, y - h/2, ...)}）
     *       是<b>中心锚点</b>；</li>
     *   <li>{@code Drawf.dashRect(color, x, y, w, h)}（首条线段为 {@code (x,y) → (x+w,y)}）
     *       是<b>左下角锚点</b>。</li>
     * </ul>
     * 两者传同一组坐标必然错位，故这里分别按各自锚点换算——否则会出现「填充对了边框错 /
     * 边框对了填充错」的反复现象。
     *
     * <p>虚线框与原版放置预览（电力桥/钻头/传送带）一致，用 {@link Drawf#dashRect}。
     * <b>注意 {@code Drawf} 的两个硬编码</b>：它内部会 {@code Lines.stroke(3f)}（线宽固定 3，
     * 外部设线宽无效）且用 {@code Pal.gray} 的 RGB —— 传入的 {@link Color} <b>只有 alpha 生效</b>。
     * 故虚线框恒为<b>不透明</b>的灰色（与原版一致，alpha 固定 1），状态区分靠<b>填充色</b>与提示文字承担。
     */
    public static void drawArea(float cx, float cy, float half, Color color, float fillAlpha){
        float size = half * 2f;

        // 填充：中心锚点，直接传中心（颜色与透明度完全生效）
        Draw.color(color, fillAlpha);
        Fill.rect(cx, cy, size, size);

        // 描边：左下角锚点 + 原版虚线，**不透明**。
        // dashRect 只取传入 Color 的 alpha（RGB 被内部固定为 Pal.gray），故显式置 alpha = 1。
        Color border = color.cpy();
        border.a = 1f;
        Drawf.dashRect(border, cx - half, cy - half, size, size);

        Draw.reset();
    }

    public class EfficiencyControlTowerBuild extends Building implements BuildingBoostSystem.Provider{

        /** 模式：关闭（默认值）。 */
        public static final int modeOff = 0;
        /** 最高档位（节能/超频各 3 级）。滑块范围为 {@code [-maxLevel, +maxLevel]}。 */
        public static final int maxLevel = 3;

        /**
         * 当前模式：<b>负数 = 节能，0 = 关闭，正数 = 超频</b>，绝对值即档位（1~3）。
         * 滑块自左至右为「3级节能 → 2级 → 1级 → 关闭 → 1级超频 → 2级 → 3级」，
         * 故「关闭」恰在正中（7 个档位的第 4 格）。
         */
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
            return !conflicted && levelOf(boost) > 0;
        }

        // 本机是否开机（粗筛，逐效果的模式判断在 provides）
        @Override
        public boolean canTarget(Building target){
            return enabled;
        }

        /**
         * 本机为该效果提供的档位：节能取 {@code -mode}、超频取 {@code mode}，关闭/越界为 0。
         * 档位由本 Provider 持有（效果是单例，不能存实例字段），System 按目标回查时向本方法索取。
         */
        @Override
        public int levelOf(BuildingBoostSystem.Boost boost){
            if(conflicted || boost == null) return 0;
            String id = boost.id();
            if(EnergySavingBoost.instance.id().equals(id)){
                return mode < 0 ? Math.min(-mode, maxLevel) : 0;
            }
            if(OverclockBoost.instance.id().equals(id)){
                return mode > 0 ? Math.min(mode, maxLevel) : 0;
            }
            return 0;
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

        // —— 配置面板：档位滑块（3级节能 | 关闭 | 3级超频）——

        /**
         * 配置面板固定宽度（px）。
         *
         * <p><b>必须固定</b>：若面板随内容宽度变化，滑块长度就会跟着变，
         * 同一个像素位置在「关闭」与「1级节能」下可能对应不同档位，无法精确点选。
         * 故所有单元格一律用 {@code defaults().width(uiWidth)} 锁死，
         * 两行文本设 {@code wrap} 在该宽度内换行，绝不撑宽面板。
         */
        private static final float uiWidth = 320f;

        @Override
        public void buildConfiguration(Table table){
            table.top();

            Table pane = new Table();
            pane.background(Tex.pane);
            pane.margin(10f);
            pane.top();
            pane.defaults().width(uiWidth).left();
            table.add(pane).width(uiWidth).row();

            // 标题
            pane.add(Core.bundle.get("block.silicon-efficiency-control-tower.modeLabel"))
                .color(Pal.accent).left().padBottom(2f).row();

            // 两行文本：先建好、滑块回调需要刷新它们，故用数组持有引用（加入表格的顺序在下面）
            Label levelText = new Label(modeName(mode), Styles.defaultLabel);
            levelText.setAlignment(Align.center);
            levelText.setColor(modeColor(mode));
            levelText.setWrap(true);

            Label bonusText = new Label(bonusText(mode), Styles.defaultLabel);
            bonusText.setAlignment(Align.center);
            bonusText.setColor(Pal.accent);
            bonusText.setWrap(true);

            Label[] rows = {levelText, bonusText};

            // 滑块：范围 [-3, +3]、步长 1 = 7 个离散档位，
            // 自左至右 3级节能 → 2级 → 1级 → 关闭 → 1级超频 → 2级 → 3级（关闭恰在正中）
            pane.slider(-maxLevel, maxLevel, 1f, mode, v -> {
                int m = Mathf.round(v);
                rows[0].setText(modeName(m));
                rows[0].setColor(modeColor(m));
                rows[1].setText(bonusText(m));
                configure(m);
            }).height(28f).padBottom(2f).row();

            // 当前档位（滑块下方）
            pane.add(levelText).width(uiWidth).padBottom(2f).row();
            // 当前档位的加成信息（文本下方）
            pane.add(bonusText).width(uiWidth).row();
        }

        /**
         * 当前档位的加成摘要：关闭 → 「无效果」；否则取对应效果的 {@code summary(level)}——
         * 与强化详情面板用的是同一份数值与文案，不会两处不同步。
         */
        private String bonusText(int mode){
            if(mode == 0 || mode > maxLevel || mode < -maxLevel){
                return Core.bundle.get("block.silicon-efficiency-control-tower.bonus.off");
            }
            return mode < 0
                ? EnergySavingBoost.instance.summary(-mode)
                : OverclockBoost.instance.summary(mode);
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
            mode = Mathf.clamp(read.s(), -maxLevel, maxLevel);
        }

        // 选中时显示影响区域（虚线框）；与其他同队塔范围重叠（旧存档/强制放置）时画红色，提示本塔未运行
        // 选中时显示影响区域（虚线框），颜色随模式；与其他同队塔范围重叠（旧存档/强制放置）时画红，提示本塔未运行
        @Override
        public void drawSelect(){
            super.drawSelect();
            if(conflicted){
                drawArea(x, y, halfRangePx(), Pal.remove, 0.1f);
            }else{
                drawArea(x, y, halfRangePx(), modeColor(mode), 0.08f);
            }
        }

        /**
         * 档位的显示文本：直接取<b>对应效果的档位名</b>（如「2级节能」），不另建一套 key——
         * 单一数据源，改文案时配置面板与强化消息面板同步生效，不会两处不同步。
         * 越界值回落到「关闭」。
         */
        private static String modeName(int mode){
            if(mode == 0 || mode > maxLevel || mode < -maxLevel){
                return Core.bundle.get("block.silicon-efficiency-control-tower.mode.off");
            }
            return mode < 0
                ? EnergySavingBoost.instance.name(-mode)
                : OverclockBoost.instance.name(mode);
        }
    }
}
