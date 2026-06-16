package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.client.keybind.GateKeyBindings;
import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.state.VgOverlayState;
import com.kajiwara.visualizegate.ui.GateColors;

//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 画面右下の小アイコン (クリック不可・視覚的目印のみ) を HUD パスで描画する。
 *
 * <p>図形 (fill) ＋ 文字のみで描く (= テクスチャ資産を増やさない)。 横に現在割当キーをヒント表示
 * (keybind から取得 ＝ 再割当に追従)。 F1(hideGui) 中・F3(デバッグ) 中・他 Screen 表示中・
 * 「隅アイコン表示」 トグル OFF 時は描画しない。 Mixin 不使用 (Fabric HUD API のみ)。
 *
 * <p>HUD 登録 API は OmniChest の現物どおり //? で版橋渡し:
 * 26.1 = {@code HudElementRegistry.addLast} / legacy = {@code HudRenderCallback.EVENT}。
 */
public final class CornerIconRenderer {

    private static final CornerIconRenderer INSTANCE = new CornerIconRenderer();

    private static final int SIZE = 16;
    private static final int MARGIN = 6;
    // Mod カラー (GateColors) を参照 — 配色は 1 箇所定義。
    private static final int BG_ARGB = GateColors.HUD_BG;
    private static final int ICON_ARGB = GateColors.MAIN;
    private static final int TEXT_ARGB = GateColors.TEXT;

    private CornerIconRenderer() {
    }

    public static void register() {
        //? if >=26.1 {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("visualizegate", "corner_icon"),
                (g, deltaTracker) -> INSTANCE.onHudRender(g));
        //?} else {
        /*HudRenderCallback.EVENT.register((g, deltaTracker) -> INSTANCE.onHudRender(g));*/
        //?}
    }

    private void onHudRender(GuiGraphicsExtractor g) {
        if (!GateMenuState.isHudIconEnabled())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui)                       // F1
            return;
        if (mc.screen != null)                        // 他 Screen 表示中
            return;
        if (mc.getDebugOverlay().showDebugScreen())   // F3 デバッグ中
            return;
        if (VgOverlayState.isCloudSolo())             // ⑤⑤ 点群ソロ中は [V] も抑止 (パネルだけ残す)
            return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int x1 = sw - MARGIN;
        int x0 = x1 - SIZE;
        int y1 = sh - MARGIN;
        int y0 = y1 - SIZE;

        // 背景 (薄い黒帯)。
        g.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, BG_ARGB);
        // アイコン: ポータル枠を模した四角の縁 (4 本の細い fill)。
        g.fill(x0, y0, x1, y0 + 2, ICON_ARGB);        // 上辺
        g.fill(x0, y1 - 2, x1, y1, ICON_ARGB);        // 下辺
        g.fill(x0, y0, x0 + 2, y1, ICON_ARGB);        // 左辺
        g.fill(x1 - 2, y0, x1, y1, ICON_ARGB);        // 右辺
        // キーヒント (アイコン左)。 再割当に追従。
        String hint = "[" + GateKeyBindings.boundKeyDisplay() + "]";
        int tw = mc.font.width(hint);
        g.text(mc.font, hint, x0 - 4 - tw, y0 + (SIZE - 9) / 2, TEXT_ARGB);
    }
}
