package silicon.util;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.geom.Rect;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;

/**
 * 强化信息按钮：把「生效中的强化」在目标方块左下角渲染为一个<b>可点击按钮</b>，
 * 点击后把该目标的强化详情投递到消息面板（{@link MessageSystem}），10 秒后自动消失。
 *
 * <p><b>按钮</b>：固定 0.5 格（4×4px）大小，锚在 footprint 左下角那一格的<b>内侧</b>（紧贴角、不越出方块）。
 * 外观为游戏风格：半透明强调色底板 + 原版图标 + 亮色描边，鼠标悬停时提亮并放大描边。
 * 只要目标身上有任意一个生效的 boost 就显示（多个效果合并为一个按钮）。
 *
 * <p><b>点击行为</b>：向消息面板投递一条 10s 时限消息——
 * 标题「{@code {方块名称}中生效的Boost}」，内容逐行列出「{@code 强化名称-强化效果}」。
 * 文案经 bundle 归类管理（见 {@code 建筑强化系统} 分区），标题用 {@code {0}} 占位符注入方块名。
 *
 * <p>其它约定：只绘制本地玩家同队的方块（多人下防止透视对手强化）、屏幕外目标跳过
 * （视野矩形每帧只算一次）。命中测试复用上一帧记录的按钮世界矩形，{@code Hit} 按索引复用、无每帧分配。
 * 接入方式：{@link #init()}（客户端加载时调用一次）把自身注册进 System 的
 * {@link BuildingBoostSystem#visualRenderer}，并挂载渲染与输入钩子。
 */
public class BoostOverlay implements BuildingBoostSystem.VisualRenderer {

    /** 一格边长（像素，Mindustry 1 tile = 8px） */
    private static final float tile = 8f;
    /** 按钮边长：0.5 格 */
    private static final float size = tile * 0.5f;
    /** 图标占按钮比例（其余留白） */
    private static final float iconRatio = 0.8f;
    /** 按钮底色（含 alpha，与消息气泡同色） */
    private static final Color buttonColor = Color.valueOf("99cc3366");
    /** 常态底板不透明度（= 0x66） */
    private static final float backAlpha = 0.4f;
    /** 悬停时底板不透明度（更亮，提示可点） */
    private static final float hoverBackAlpha = 0.62f;
    /** 描边宽度 */
    private static final float borderWidth = 0.6f;
    /** 描边基础不透明度（悬停时提升到 hoverBorderAlpha） */
    private static final float borderAlpha = 0.85f;
    /** 悬停时描边不透明度 */
    private static final float hoverBorderAlpha = 1f;
    /** 悬停时额外放大幅度 */
    private static final float hoverScale = 1.25f;
    /** 消息显示时限（秒） */
    private static final float messageLife = 10f;
    /** 标题本地化 key：{0} 为方块名称（以 [accent] 强调） */
    private static final String titleKey = "boost.info.title";
    /** 单条强化行的格式 key：{0}=强化名（青色）{1}=强化效果 */
    private static final String lineKey = "boost.info.line";
    /** 消息气泡色（同时用作消失时间覆盖层色） */
    private static final Color bubbleColor = Color.valueOf("99cc3366");
    /** 复用实例 */
    private static final BoostOverlay instance = new BoostOverlay();
    /** 视野裁剪矩形：每帧算一次，供本帧所有按钮复用 */
    private static final Rect view = new Rect();
    /** 上一帧绘制出的按钮命中区（世界坐标），供本帧点击命中测试 */
    private static final Seq<Hit> hits = new Seq<>();

    private static boolean inited = false;

    private BoostOverlay() {
    }

    /** 按钮命中区（世界坐标矩形 + 目标），按索引复用避免每帧分配。 */
    private static class Hit {
        Building target;
        final Rect rect = new Rect();
    }

    public static void init() {
        if (inited) return;
        inited = true;
        // 无头服务器跳过（无渲染循环与 UI）
        if (Vars.headless) return;
        BuildingBoostSystem.visualRenderer = instance;
        // 渲染阶段：重置命中区 → 算视野 → 调度 System 绘制
        Events.run(EventType.Trigger.draw, () -> {
            hits.clear();
            Core.camera.bounds(view);
            BuildingBoostSystem.drawBoosts();
        });
        // 输入阶段：按钮点击 → 投递强化详情到消息面板
        Events.run(EventType.Trigger.update, BoostOverlay::checkInput);
    }

    @Override
    public void render(Building target, BuildingBoostSystem.BoostVisual visual, int index) {
        if (target == null || visual == null) return;
        TextureRegion icon = visual.icon(target);
        if (icon == null) return;
        // 只显示己方（多人下防止透视对手强化）
        if (Vars.player != null && target.team != Vars.player.team()) return;
        // 视野裁剪：屏幕外目标跳过（矩形本帧已算好）
        if (!view.contains(target.x, target.y)) return;
        // 多个效果合并为一个按钮（详情在消息面板里），故只画第一个
        if (index > 0) return;

        // footprint 左下角向右上一格，再取该格中心 → 按钮落在左下角格内侧，不越出方块
        float blockSize = target.block.size * tile;
        float x = target.x - blockSize / 2f + size / 2f;
        float y = target.y - blockSize / 2f + size / 2f;

        // 记录命中区（点击测试用上一帧的位置，避免与相机拖拽冲突）
        Hit hit = hit(index);
        hit.target = target;
        hit.rect.set(x - size / 2f, y - size / 2f, size, size);

        // 悬停判定（用世界坐标，光标在按钮矩形内即视为悬停）
        boolean hovered = Core.input.mouseWorldX() >= hit.rect.x
                && Core.input.mouseWorldX() <= hit.rect.x + hit.rect.width
                && Core.input.mouseWorldY() >= hit.rect.y
                && Core.input.mouseWorldY() <= hit.rect.y + hit.rect.height;

        float draw = hovered ? size * hoverScale : size;
        float half = draw / 2f;
        Color tint = visual.color();
        float prevZ = Draw.z();
        try {
            // 浮于方块之上，避免被建筑贴图盖住
            Draw.z(Layer.overlayUI);
            // 底板：统一色（#99cc3366，与消息气泡同色），悬停时更亮以示可点
            Draw.color(tint == null ? buttonColor : tint, hovered ? hoverBackAlpha : backAlpha);
            Draw.rect(Core.atlas.white(), x, y, draw, draw);
            // 描边：亮色细框，强化按钮观感
            Draw.color(Color.white, hovered ? hoverBorderAlpha : borderAlpha);
            Draw.rect(Core.atlas.white(), x, y, draw, draw, borderWidth);
            // 图标本体（原版美术，不额外染色以保持原味）
            Draw.color(Color.white);
            Draw.rect(icon, x, y, draw * iconRatio, draw * iconRatio);
        } finally {
            Draw.reset();
            Draw.z(prevZ);
        }
    }

    private static Hit hit(int i) {
        while (hits.size <= i) {
            hits.add(new Hit());
        }
        return hits.get(i);
    }

    // —— 点击交互：投递强化详情到消息面板 ——

    private static void checkInput() {
        if (Vars.state == null || !Vars.state.isGame()) return;
        if (!Core.input.keyTap(KeyCode.mouseLeft)) return;
        float wx = Core.input.mouseWorldX(), wy = Core.input.mouseWorldY();
        for (Hit hit : hits) {
            if (hit.rect.contains(wx, wy)) {
                postBoostInfo(hit.target);
                return;
            }
        }
    }

    /**
     * 把目标的强化详情投递到消息面板：标题「[accent]{方块名}[]中生效的Boost」，
     * 内容逐行「[cyan]{强化名}[]：{强化效果}」，气泡色 #99cc3366，10 秒后消失。
     */
    private static void postBoostInfo(Building target) {
        if (target == null) return;
        Seq<BuildingBoostSystem.Boost> boosts = BuildingBoostSystem.activeBoosts(target);
        if (boosts.isEmpty()) return;

        // 内容：每个生效 boost 一行，行格式由 bundle 管理（名称青色 + 全角冒号分隔）
        StringBuilder content = new StringBuilder();
        for (BuildingBoostSystem.Boost boost : boosts) {
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(Core.bundle.format(lineKey, boost.name(), boost.description()));
        }

        // 标题用本地化 key + {0} 占位符注入方块名（方块名以 [accent] 强调）
        // 图标用该建筑自身的贴图（uiIcon），便于一眼看出是哪个方块上的强化
        MessageSystem.instance.post(MessageSystem.info("", content.toString(), messageLife)
                .titleKey(titleKey)
                .var(target.block.localizedName)
                .background(bubbleColor)
                .icon(blockIcon(target)));
    }

    /** 取建筑图标（uiIcon）作为消息图标；缺失时回退默认信息图标。 */
    private static Drawable blockIcon(Building target) {
        TextureRegion region = target.block != null ? target.block.uiIcon : null;
        if (region == null) {
            return Icon.info;
        }
        // 显式染白：uiIcon 自带描边/底色，避免被消息面板配色二次染色导致偏色
        return new TextureRegionDrawable(region).tint(Color.white);
    }
}
