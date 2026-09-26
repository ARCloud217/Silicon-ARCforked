package silicon.util;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.GlyphLayout;
import arc.math.geom.Rect;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.ui.Fonts;

/**
 * 强化调试覆盖层：让 {@link BuildingBoostSystem} 在「生效中的强化目标上方」绘制效果名。
 *
 * <p>调试阶段的辅助显示：凡目标方块当前挂着至少一个 boost，就在其顶边上方绘制该 boost 的
 * 显示名（{@link BuildingBoostSystem.BoostVisual#label(Building)}，颜色取
 * {@link BuildingBoostSystem.BoostVisual#color()}）。仅绘制本地玩家同队的方块、并且做视野
 * 裁剪，避免多人透视对手强化与海量目标时的绘制开销。
 *
 * <p>接入方式：{@link #init()}（客户端加载时调用一次）把自身注册进 System 的
 * {@link BuildingBoostSystem#visualRenderer}，并挂载每帧渲染钩子
 * {@code Trigger.draw} → {@link BuildingBoostSystem#drawBoosts()}；System 负责在绘制阶段
 * 遍历 active 状态逐个回调 {@link #render(Building, BoostVisual)}。
 */
public class BoostOverlay implements BuildingBoostSystem.VisualRenderer {

    /** 有文字无颜色时的默认色（白） */
    private static final Color DEFAULT_COLOR = Color.valueOf("ffffff");
    /** 文字缩放（相对原版字体基准） */
    private static final float FONT_SCALE = 0.25f;
    /** 文字绘制高度：方块顶边往上 10px（方块高 = size × 8px） */
    private static final float ABOVE_OFFSET = 10f;
    /** 复用实例，避免每帧绘制分配 */
    private static final BoostOverlay instance = new BoostOverlay();

    private static boolean inited = false;

    private BoostOverlay() {
    }

    public static void init() {
        if (inited) return;
        inited = true;
        // 无头服务器跳过（无渲染循环）
        if (Vars.headless) return;
        BuildingBoostSystem.visualRenderer = instance;
        // 渲染循环方块层绘制后触发（每帧）
        Events.run(EventType.Trigger.draw, BuildingBoostSystem::drawBoosts);
    }

    @Override
    public void render(Building target, BuildingBoostSystem.BoostVisual visual) {
        if (target == null || visual == null) return;
        // 只显示己方（多人下防止透视对手强化）
        if (Vars.player != null && target.team != Vars.player.team()) return;
        String label = visual.label(target);
        if (label == null || label.isEmpty()) return;
        // 视野裁剪：屏幕外目标跳过
        Rect view = Core.camera.bounds(Tmp.r1);
        if (!view.contains(target.x, target.y)) return;

        float x = target.x;
        float y = target.y + target.block.size / 2f * 8f + ABOVE_OFFSET;

        Color oldColor = Fonts.def.getColor();
        float oldScaleX = Fonts.def.getData().scaleX;
        float oldScaleY = Fonts.def.getData().scaleY;
        try {
            Fonts.def.getData().setScale(FONT_SCALE);
            Color c = visual.color() == null ? DEFAULT_COLOR : visual.color();
            Fonts.def.setColor(c);
            // 先量宽再居中绘制（GlyphLayout 测量文字宽度，避免左缘对不齐）
            GlyphLayout layout = new GlyphLayout(Fonts.def, label);
            Fonts.def.draw(layout, x - layout.width / 2f, y);
        } finally {
            Fonts.def.setColor(oldColor);
            Fonts.def.getData().setScale(oldScaleX, oldScaleY);
        }
    }
}