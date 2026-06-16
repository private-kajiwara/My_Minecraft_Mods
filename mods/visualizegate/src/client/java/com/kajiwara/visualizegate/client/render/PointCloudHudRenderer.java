package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.state.VgOverlayState;
import com.kajiwara.visualizegate.ui.GateColors;

//? if >=26.1 {
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.pointcloud.DockRadar;
import com.kajiwara.visualizegate.pointcloud.PointCloudSnapshot;
import com.kajiwara.visualizegate.state.PointCloudViewState;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * ⑤④ 案C ミニマップ風 点群オーバーレイ — 画面<b>右下</b> (ハブ目印 {@code [V]} の真上・右端=画面マージン) に
 * <b>独立 HUD パネル</b>として点群プレビューを描く (Mixin 不使用・{@code HudElementRegistry}/{@code HudRenderCallback})。
 *
 * <p>従来は {@link VgDockRenderer} 展開ドックの最下部にあった点群サムネを<b>ここへ移設</b>した
 * (ドックはヘッダ/パフォーマンス/ゲート状態/注記のみで短くなる)。 描画経路・座標変換は<b>不変</b>:
 * {@link DockRadar} のライブ局所レーダー → {@link PointCloudGpuRenderer} の自前オービット投影を FBO へ描き、
 * 任意の GUI 矩形へ縮小 blit する。 移したのは<b>描画先の矩形だけ</b> (÷8/per-dim 表示スケール/重心/spacing/
 * リンク端・各マーカーのアライメントは現状のまま)。
 *
 * <p><b>>=26.1 限定</b>で雲を描く (真の GPU3D)。 legacy(1.21.x) は元々 HUD で点群を描いておらず
 * (texbatch はフル画面 V メニュー専用)、 ここでも「GPU3D N/A」ノートを出すのみ＝現状の legacy 挙動を保存。
 *
 * <p>表示条件: {@code /vg point-cloud} ON のときだけ (={@link VgOverlayState#isPointCloud()})。
 * F1(hideGui)/F3/他 Screen 表示中は非表示・入力は一切奪わない。
 */
public final class PointCloudHudRenderer {

    private static final PointCloudHudRenderer INSTANCE = new PointCloudHudRenderer();

    // パネル寸法 (GUI px)。 右端=画面右マージン、 下端=[V] アイコン行の上。
    private static final int MARGIN = 6;      // 画面端からの余白 ([V] と同一)
    private static final int PAD = 4;         // パネル内パディング
    private static final int PANEL_W = 158;   // 既定パネル幅 (上限・画面に合わせ収縮)
    private static final int PREVIEW_H = 96;   // 既定プレビュー高 (画面に合わせ収縮)
    private static final int PREVIEW_MIN_H = 40; // これ未満になる極小画面では非表示
    private static final int GAP = 4;         // [V] アイコン行との隙間
    private static final int CORNER_ICON = 16; // CornerIconRenderer の SIZE (この上に乗せる)
    // ㊺C/㊼A 下部中央 HUD (ホットバー＋体力/空腹/XP) の安全帯 (パネル x 帯が中央に掛かる時だけ下端を上に止める)。
    private static final int HOTBAR_HALF_W = 100;
    private static final int BOTTOM_SAFE = 44;

    private PointCloudHudRenderer() {
    }

    public static void register() {
        //? if >=26.1 {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("visualizegate", "pointcloud_hud"),
                (g, deltaTracker) -> INSTANCE.onHudRender(g));
        //?} else {
        /*HudRenderCallback.EVENT.register((g, deltaTracker) -> INSTANCE.onHudRender(g));*/
        //?}
    }

    private void onHudRender(GuiGraphicsExtractor g) {
        if (!VgOverlayState.isPointCloud()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // パネル幅 (画面に収まるよう制約)。
        int panelW = Math.min(PANEL_W, sw - MARGIN * 2);
        if (panelW < 60) {
            return; // 極小画面: 描かない
        }
        int previewW = panelW - PAD * 2;

        // 下端 = [V] アイコン行の上。 パネル x 帯が下部中央 HUD に掛かる時は安全帯ぶん上で止める。
        int panelRight = sw - MARGIN;
        int panelLeft = panelRight - panelW;
        int iconTop = sh - MARGIN - CORNER_ICON - 2;
        int bottom = iconTop - GAP;
        boolean overlapsCenterHud = panelLeft < (sw / 2 + HOTBAR_HALF_W);
        if (overlapsCenterHud) {
            bottom = Math.min(bottom, sh - BOTTOM_SAFE);
        }

        // プレビュー高 (上端が画面に収まるよう収縮)。
        int previewH = PREVIEW_H;
        int panelH = PAD + previewH + PAD;
        int top = bottom - panelH;
        if (top < MARGIN) {
            top = MARGIN;
            panelH = bottom - top;
            previewH = panelH - PAD * 2;
        }
        if (previewH < PREVIEW_MIN_H) {
            return; // 真に余地が無い極小画面: 描かない
        }

        int px = panelLeft;
        int py = top;
        // パネル背景 (ほぼ黒・低不透明) ＋ 細い紫枠 (ドックと同じ半透明感)。
        g.fill(px, py, px + panelW, py + panelH, 0xC00F0A17);
        frame(g, px, py, panelW, panelH, GateColors.MAIN_DIM);

        int ix = px + PAD;
        int iy = py + PAD;
        g.fill(ix, iy, ix + previewW, iy + previewH, GateColors.BASE); // プレビュー背景
        //? if >=26.1 {
        drawCloud(g, mc, ix, iy, previewW, previewH);
        //?} else {
        /*note(g, mc, ix, iy, previewW, previewH, "visualizegate.pc.hud.legacy");*/
        //?}
    }

    /** 細い 1px 枠。 */
    private void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** プレビュー枠中央に淡色注記 (データなし/legacy)。 */
    private void note(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int w, int h, String key) {
        Component c = Component.translatable(key);
        int cw = mc.font.width(c);
        g.text(mc.font, c, x + Math.max(0, (w - cw) / 2), y + h / 2 - 4, GateColors.LINK_GRAY);
    }

    //? if >=26.1 {
    // ── 点群プレビュー (VgDockRenderer から移設・描画経路/座標変換は不変)。 ──────────────────
    private static final float PITCH = 0.32f; // Vメニュー既定 pitch に合わせる (固定)
    private static final float YAW_EPS = 0.02f; // yaw 変化の再描画しきい値 (~1.1°)
    private static final float CENTER_EPS = 0.05f; // カメラ中心 (プレイヤー追従) の再ラスタしきい値
    private static final int PC_MAX_DIM = 2048; // SS FBO の各辺上限
    private static final int DIM_TINT_OW = GateColors.PC_OW_HIGH;
    private static final int DIM_TINT_NETHER = GateColors.PC_NETHER_HIGH;
    private static final float DIM_TINT_FRAC = 0.15f;
    private static final float PC_FIT_K = 1.2f;      // 雲半径フィット係数 (近距離ズーム)
    private static final float GPU_MARKER_ARM_FRAC = 0.03f;
    private static final float GPU_MARKER_W_FRAC = 0.0022f;
    private static final float GPU_GATE_FRAME_HALF_H_FRAC = 0.022f;
    private static final float GPU_GATE_FRAME_HALF_W_FRAC = 0.0176f;
    private static final float GPU_GATE_BAR_W_FRAC = 0.0026f;
    private static final float GPU_GATE_GRID_W_FRAC = 0.0014f;
    private float pcDistance = 200f;
    private boolean pcWasVisible = false;
    private final float[] pcEmpty = new float[0];
    private final int[] pcEmptyI = new int[0];
    private float pcRenderYaw = Float.NaN;
    private int pcRenderW = -1;
    private int pcRenderH = -1;
    private float pcRenderCx = Float.NaN;
    private float pcRenderCy = Float.NaN;
    private float pcRenderCz = Float.NaN;
    private PointCloudSnapshot gSnap;
    private boolean gShowOw;
    private boolean gShowN;
    private boolean gDimTint;
    private int gSpacing;
    private int gDetail;
    private int gPointSize;
    private float gOwScale = Float.NaN;
    private float gNetherScale = Float.NaN;
    private int gHiddenVer = -1;

    private void drawCloud(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int w, int h) {
        if (!PointCloudGpuRenderer.usable()) {
            note(g, mc, x, y, w, h, "visualizegate.pc.hud.legacy");
            pcWasVisible = false;
            return;
        }
        DockRadar.get().maybeCapture(System.nanoTime());
        PointCloudSnapshot snap = DockRadar.get().snapshot();
        if (snap == null || snap.isEmpty()) {
            note(g, mc, x, y, w, h, "visualizegate.pc.hud.empty");
            pcWasVisible = false;
            return;
        }
        try {
            boolean takeover = !pcWasVisible; // 再表示時は共有 VBO/FBO が画面のものかもしれない＝必ず組み直す
            boolean geomDirty = false;
            if (takeover || gpuGeomChanged(snap)) {
                pcUpload(snap);
                geomDirty = true;
            }
            int ss = Math.max(1, mc.getWindow().getGuiScale());
            while (ss > 1 && (w * ss > PC_MAX_DIM || h * ss > PC_MAX_DIM)) {
                ss--;
            }
            int rw = w * ss;
            int rh = h * ss;
            // 向き＝プレイヤーの yaw に追従 (⑤③ +π で正面が手前)。
            float yaw = (float) Math.toRadians(mc.player != null ? mc.player.getYRot(1.0f) : 0f)
                    + (float) Math.PI;
            // カメラ中心 = プレイヤー現在地 (毎フレーム追従)。 geometry は capture-player 基準 (3Hz)。
            float cx = 0f;
            float cy = 0f;
            float cz = 0f;
            if (snap.hasMarker && mc.player != null) {
                boolean neth = snap.markerNether;
                float pivotY = PointCloudViewState.getDimensionSpacing() * 0.5f;
                float ms = neth ? PointCloudViewState.getNetherDisplayScale()
                        : PointCloudViewState.getOwDisplayScale();
                float xzW = neth ? PointCloudSnapshot.NETHER_XZ_SCALE : 1f; // ネザーは水平 1/8 (terrain と同変換)
                double dX = mc.player.getX() - DockRadar.get().capX();
                double dY = mc.player.getY() - DockRadar.get().capY();
                double dZ = mc.player.getZ() - DockRadar.get().capZ();
                cx = (snap.markerX + (float) (dX * xzW)) * ms;
                cy = (snap.markerY + (float) dY) + (neth ? -pivotY : pivotY);
                cz = (snap.markerZ + (float) (dZ * xzW)) * ms;
            }
            boolean centerMoved = Float.isNaN(pcRenderCx) || Math.abs(cx - pcRenderCx) > CENTER_EPS
                    || Math.abs(cy - pcRenderCy) > CENTER_EPS || Math.abs(cz - pcRenderCz) > CENTER_EPS;
            boolean rerender = takeover || geomDirty || rw != pcRenderW || rh != pcRenderH
                    || Float.isNaN(pcRenderYaw) || Math.abs(yaw - pcRenderYaw) > YAW_EPS || centerMoved;
            if (rerender && PointCloudGpuRenderer.render(rw, rh, yaw, PITCH, pcDistance, cx, cy, cz, GateColors.BASE)) {
                pcRenderYaw = yaw;
                pcRenderW = rw;
                pcRenderH = rh;
                pcRenderCx = cx;
                pcRenderCy = cy;
                pcRenderCz = cz;
            }
            GpuTextureView cv = PointCloudGpuRenderer.colorView();
            if (cv != null) {
                g.blit(cv, PointCloudGpuRenderer.sampler(), x, y, x + w, y + h, 0f, 1f, 1f, 0f);
                drawOverlay(g, mc, x, y, w, h, snap); // ⑤④ ミニマップ風 四隅オーバーレイ (層別)
            }
            pcWasVisible = true;
        } catch (Throwable t) {
            note(g, mc, x, y, w, h, "visualizegate.pc.hud.legacy");
            pcWasVisible = false;
        }
    }

    // ── ⑤④ ミニマップ風 四隅オーバーレイ (層別・スクリムで明背景でも可読)。 ──────────────────
    private static final int SCRIM_STRONG = 0xB0000000;
    private static final int SCRIM_LIGHT = 0x70000000;

    /**
     * プレビュー矩形 (x,y,w,h) の上に値をミニマップ風に重ねる。 視覚階層はスクリム強度＋色＋配置で表現
     * (MC ビットマップフォントは単一サイズのため文字寸では分けない)。
     *
     * <p><b>層の振り分けはこのメソッド 1 か所で管理</b> (項目→層を user 指定で移動できるよう grouped):
     * <ul>
     *   <li>第1層 (スクリム強・白・最下): 座標 XYZ … 高頻度・最重要。</li>
     *   <li>第2層 (スクリム弱・色): 現次元 / 総点群数 / 表示スケール / 簡略・詳細状態 … 中頻度。</li>
     *   <li>第3層 (詳細トグル ON のみ): 範囲 / 間隔 / 点サイズ / GPU(描画/母数) / 層別点群 / 配色凡例 … 低頻度。</li>
     * </ul>
     */
    private void drawOverlay(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int w, int h, PointCloudSnapshot snap) {
        boolean detail = PointCloudViewState.isOverlayDetail();
        int left = x + 3;
        int right = x + w - 3;
        int top = y + 2;
        int bottomLine = y + h - 10;

        // 値の取得 (HUD から直接・新規配線なし)。
        boolean neth = snap.markerNether;
        int owN = snap.owX.length;
        int neN = snap.nX.length;
        int totalN = owN + neN;
        int sampledN = Math.max(snap.owSampled + snap.netherSampled, totalN);
        int range = Math.round(snap.radius);
        int spacing = PointCloudViewState.getDimensionSpacing();
        int pointSz = PointCloudViewState.getPointSize();
        float owSc = PointCloudViewState.getOwDisplayScale();
        float neSc = PointCloudViewState.getNetherDisplayScale();
        double px = mc.player != null ? mc.player.getX() : 0;
        double py = mc.player != null ? mc.player.getY() : 0;
        double pz = mc.player != null ? mc.player.getZ() : 0;

        // ── 第2層: 上ストリップ (現次元 + 総点群数 / 右端=簡略・詳細状態)。 ──
        Component dimC = Component.literal(neth ? "Ne" : "OW");
        Component cntC = Component.literal(fmtCount(totalN) + " pts");
        scrimLine(g, x, top, w, SCRIM_LIGHT);
        g.text(mc.font, dimC, left, top, neth ? DIM_TINT_NETHER : DIM_TINT_OW);
        g.text(mc.font, cntC, left + mc.font.width(dimC) + 6, top, GateColors.TEXT);
        Component modeC = Component.translatable(
                detail ? "visualizegate.pc.panel.detail" : "visualizegate.pc.panel.brief");
        g.text(mc.font, modeC, right - mc.font.width(modeC), top, GateColors.LINK_GRAY);

        // ── 第3層: 詳細 (トグル ON かつ縦に余地がある時のみ・上ストリップの下に小さく積む)。 ──
        if (detail && h >= 78) {
            int dy = top + 11;
            scrimLine(g, x, dy, w, SCRIM_LIGHT);
            int dx = left;
            dx = field(g, mc, dx, dy, "visualizegate.pc.panel.range", Integer.toString(range));
            dx = field(g, mc, dx, dy, "visualizegate.pc.panel.spacing", Integer.toString(spacing));
            field(g, mc, dx, dy, "visualizegate.pc.panel.point", pointSz + "px");
            dy += 10;
            scrimLine(g, x, dy, w, SCRIM_LIGHT);
            g.text(mc.font, Component.literal("GPU " + fmtCount(totalN) + "/" + fmtCount(sampledN)),
                    left, dy, GateColors.LINK_GRAY);
            Component perDim = Component.literal("OW " + fmtCount(owN) + "  Ne " + fmtCount(neN));
            g.text(mc.font, perDim, right - mc.font.width(perDim), dy, GateColors.LINK_GRAY);
            dy += 10;
            scrimLine(g, x, dy, w, SCRIM_LIGHT); // 配色凡例 (OW=teal / Ne=橙 / リンク=紫)
            int lx = legendDot(g, mc, left, dy, DIM_TINT_OW, "OW");
            lx = legendDot(g, mc, lx, dy, DIM_TINT_NETHER, "Ne");
            legendDot(g, mc, lx, dy, GateColors.PC_LINK,
                    Component.translatable("visualizegate.legend.link_line").getString());
        }

        // ── 第2層: 表示スケール (座標の 1 行上・右端)。 ──
        Component scaleC = Component.literal("OW" + fmt1(owSc) + " Ne" + fmt1(neSc));
        int scY = bottomLine - 10;
        g.fill(right - mc.font.width(scaleC) - 2, scY - 1, right + 1, scY + 9, SCRIM_LIGHT);
        g.text(mc.font, scaleC, right - mc.font.width(scaleC), scY, GateColors.LINK_GRAY);

        // ── 第1層: 座標 XYZ (最下・スクリム強・白)。 ──
        Component coordC = Component.literal(
                (int) Math.floor(px) + " / " + (int) Math.floor(py) + " / " + (int) Math.floor(pz));
        scrimLine(g, x, bottomLine, w, SCRIM_STRONG);
        g.text(mc.font, coordC, left, bottomLine, GateColors.TEXT);
    }

    /** 全幅の薄いスクリム帯 (テキスト 1 行ぶん)。 */
    private void scrimLine(GuiGraphicsExtractor g, int x, int y, int w, int argb) {
        g.fill(x, y - 1, x + w, y + 9, argb);
    }

    /** label(灰) + value(白) を描き次の x を返す。 */
    private int field(GuiGraphicsExtractor g, Minecraft mc, int x, int y, String labelKey, String val) {
        Component l = Component.translatable(labelKey);
        g.text(mc.font, l, x, y, GateColors.LINK_GRAY);
        x += mc.font.width(l) + 2;
        Component v = Component.literal(val);
        g.text(mc.font, v, x, y, GateColors.TEXT);
        return x + mc.font.width(v) + 6;
    }

    /** 色ドット + ラベル (灰) を描き次の x を返す。 */
    private int legendDot(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int color, String label) {
        g.fill(x, y + 1, x + 5, y + 6, color);
        x += 7;
        Component c = Component.literal(label);
        g.text(mc.font, c, x, y, GateColors.LINK_GRAY);
        return x + mc.font.width(c) + 6;
    }

    /** 点数の短縮表記 (>=1000 → "x.xk")。 */
    private static String fmtCount(int n) {
        if (n >= 1000) {
            return String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0);
        }
        return Integer.toString(n);
    }

    /** スケールの 1 桁表記。 */
    private static String fmt1(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Vメニューと同じ署名の変化検出 (snapshot/トグル/スケール/detail/pointSize/spacing/hidden版)。 */
    private boolean gpuGeomChanged(PointCloudSnapshot snap) {
        boolean showOw = PointCloudViewState.isShowOverworld();
        boolean showN = PointCloudViewState.isShowNether();
        boolean tint = PointCloudViewState.isDimTint();
        int spacing = PointCloudViewState.getDimensionSpacing();
        int detail = PointCloudViewState.getGpuDetail();
        int pointSize = PointCloudViewState.getPointSize();
        float owScale = PointCloudViewState.getOwDisplayScale();
        float nScale = PointCloudViewState.getNetherDisplayScale();
        int hiddenVer = PortalMemory.displayVersion();
        if (snap == gSnap && showOw == gShowOw && showN == gShowN && tint == gDimTint
                && spacing == gSpacing && detail == gDetail && pointSize == gPointSize
                && owScale == gOwScale && nScale == gNetherScale && hiddenVer == gHiddenVer) {
            return false;
        }
        gSnap = snap;
        gShowOw = showOw;
        gShowN = showN;
        gDimTint = tint;
        gSpacing = spacing;
        gDetail = detail;
        gPointSize = pointSize;
        gOwScale = owScale;
        gNetherScale = nScale;
        gHiddenVer = hiddenVer;
        return true;
    }

    /**
     * Vメニュー (buildGpuGeometry) の品質設定を流用し、 プレビューを<b>データ基準のタイトフィット</b>で組む:
     * 地形点 (両層)＋ゲート枠 (5状態色 wireframe)＋現在地マーカー (金十字) を重心中心のまま置き、 距離を全データが
     * 枠内に収まる範囲で詰める＝常に非空。 ÷8/per-dim 表示スケール/重心/spacing/各マーカー座標は一切不変。
     */
    private void pcUpload(PointCloudSnapshot snap) {
        float pivotY = PointCloudViewState.getDimensionSpacing() * 0.5f;
        boolean tint = PointCloudViewState.isDimTint();
        boolean showOw = PointCloudViewState.isShowOverworld();
        boolean showN = PointCloudViewState.isShowNether();
        float owScale = PointCloudViewState.getOwDisplayScale();
        float nScale = PointCloudViewState.getNetherDisplayScale();
        int detail = PointCloudViewState.getGpuDetail();

        // ── 地形点 (両層・⑤頂点色・重心センタリングの生座標)。 ──
        int owN = showOw ? snap.owX.length : 0;
        int nN = showN ? snap.nX.length : 0;
        int owStride = (owN > detail) ? (owN + detail - 1) / detail : 1;
        int nStride = (nN > detail) ? (nN + detail - 1) / detail : 1;
        int total = (owN + owStride - 1) / owStride + (nN + nStride - 1) / nStride;
        float[] xyz = new float[total * 3];
        int[] col = new int[total];
        int k = 0;
        for (int i = 0; i < owN; i += owStride) {
            xyz[k * 3] = snap.owX[i] * owScale;
            xyz[k * 3 + 1] = snap.owY[i] + pivotY;
            xyz[k * 3 + 2] = snap.owZ[i] * owScale;
            col[k] = tint ? mix(snap.owColor[i], DIM_TINT_OW, DIM_TINT_FRAC) : snap.owColor[i];
            k++;
        }
        for (int i = 0; i < nN; i += nStride) {
            xyz[k * 3] = snap.nX[i] * nScale;
            xyz[k * 3 + 1] = snap.nY[i] - pivotY;
            xyz[k * 3 + 2] = snap.nZ[i] * nScale;
            col[k] = tint ? mix(snap.nColor[i], DIM_TINT_NETHER, DIM_TINT_FRAC) : snap.nColor[i];
            k++;
        }
        int pointSize = Math.max(1, Math.min(2, PointCloudViewState.getPointSize()));
        PointCloudGpuRenderer.uploadPoints(xyz, col, k, pointSize);

        // ── overlay: 現在次元のゲート枠 (5状態色・範囲外は縁クランプ) ＋ 現在地マーカー (金十字)。 ──
        int[] gateState = snap.gateMeta != null ? snap.gateMeta.gateState() : null;
        boolean pNeth = snap.markerNether;
        float ms = pNeth ? nScale : owScale;
        int visGates = 0;
        for (int i = 0; i < snap.gateX.length; i++) {
            if (snap.gateNether[i] == pNeth && (pNeth ? showN : showOw) && !gateHidden(snap, i)) {
                visGates++;
            }
        }
        float gateHalfH = Math.max(1.2f, snap.radius * GPU_GATE_FRAME_HALF_H_FRAC);
        float gateHalfW = Math.max(0.9f, snap.radius * GPU_GATE_FRAME_HALF_W_FRAC);
        float gateBarW = Math.max(0.08f, snap.radius * GPU_GATE_BAR_W_FRAC);
        float gateGridW = Math.max(0.06f, snap.radius * GPU_GATE_GRID_W_FRAC);
        float mcx = snap.hasMarker ? snap.markerX * ms : 0f;
        float mcy = snap.hasMarker ? (pNeth ? snap.markerY - pivotY : snap.markerY + pivotY) : 0f;
        float mcz = snap.hasMarker ? snap.markerZ * ms : 0f;
        float clampR = Math.max(snap.radius, 1f);
        int ov = visGates * 112 + (snap.hasMarker ? 48 : 0); // ゲート枠=112頂点 / 現在地十字=48頂点
        if (ov > 0) {
            float[] oxyz = new float[ov * 3];
            int[] ocol = new int[ov];
            int j = 0;
            for (int i = 0; i < snap.gateX.length; i++) {
                if (snap.gateNether[i] != pNeth || !(pNeth ? showN : showOw) || gateHidden(snap, i)) {
                    continue;
                }
                float gx = snap.gateX[i] * ms;
                float gy = snap.gateY[i] + (pNeth ? -pivotY : pivotY);
                float gz = snap.gateZ[i] * ms;
                float ddx = gx - mcx;
                float ddz = gz - mcz;
                float dd = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                if (dd > clampR) {
                    float s = clampR / dd;
                    gx = mcx + ddx * s;
                    gz = mcz + ddz * s;
                }
                int st = (gateState != null && i < gateState.length) ? gateState[i] : 0;
                j = emitGateFrame(oxyz, ocol, j, gx, gy, gz,
                        gateHalfW, gateHalfH, gateBarW, gateGridW, GateColors.forStateOrdinal(st));
            }
            if (snap.hasMarker) {
                float markArm = Math.max(2f, snap.radius * GPU_MARKER_ARM_FRAC);
                float markW = Math.max(0.1f, snap.radius * GPU_MARKER_W_FRAC);
                int gold = 0xFF000000 | (GateColors.ACCENT & 0xFFFFFF);
                j = emitCross(oxyz, ocol, j, mcx, mcy, mcz, markArm, markW, gold);
            }
            PointCloudGpuRenderer.uploadOverlay(oxyz, ocol, j);
        } else {
            PointCloudGpuRenderer.uploadOverlay(pcEmpty, pcEmptyI, 0);
        }

        // 局所単層の近距離ズーム: プレイヤー周辺 (snap.radius≈局所半径) を詰めて見せる。
        pcDistance = Math.max(snap.radius * PC_FIT_K, 30f);
    }

    /** 非表示ゲート判定 (Vメニューの isGateHidden と同一)。 */
    private boolean gateHidden(PointCloudSnapshot snap, int i) {
        if (snap.gateMeta == null || i >= snap.gateMeta.gateWx().length) {
            return false;
        }
        return PortalMemory.get().isHidden(
                snap.gateNether[i] ? PortalDimension.NETHER : PortalDimension.OVERWORLD,
                snap.gateMeta.gateWx()[i], snap.gateMeta.gateWy()[i], snap.gateMeta.gateWz()[i]);
    }

    /** ARGB 線形ブレンド (dimTint 用)。 */
    private static int mix(int a, int b, float t) {
        int al = (a >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int r = Math.round(ar + (((b >> 16) & 0xFF) - ar) * t);
        int gg = Math.round(ag + (((b >> 8) & 0xFF) - ag) * t);
        int bl = Math.round(ab + ((b & 0xFF) - ab) * t);
        return (al << 24) | (r << 16) | (gg << 8) | bl;
    }

    // ── 3D ワイヤージオメトリ emit (Vメニュー/旧ドックと同一ロジック・純 float[] 書込み)。 ──
    private static int emitGateFrame(float[] xyz, int[] col, int v, float x, float y, float z,
            float halfW, float halfH, float barW, float gridW, int c) {
        v = emitPortalFrame(xyz, col, v, x, y, z, halfW, halfH, barW, c);
        float vx = halfW * 0.34f;
        v = emitBox(xyz, col, v, x - vx, y - halfH, z, x - vx, y + halfH, z, gridW, c);
        v = emitBox(xyz, col, v, x + vx, y - halfH, z, x + vx, y + halfH, z, gridW, c);
        v = emitBox(xyz, col, v, x - halfW, y, z, x + halfW, y, z, gridW, c);
        return v;
    }

    private static int emitPortalFrame(float[] xyz, int[] col, int v, float x, float y, float z,
            float halfW, float halfH, float barW, int c) {
        float x0 = x - halfW;
        float x1 = x + halfW;
        float y0 = y - halfH;
        float y1 = y + halfH;
        v = emitBox(xyz, col, v, x0, y0, z, x0, y1, z, barW, c);
        v = emitBox(xyz, col, v, x1, y0, z, x1, y1, z, barW, c);
        v = emitBox(xyz, col, v, x0, y1, z, x1, y1, z, barW, c);
        v = emitBox(xyz, col, v, x0, y0, z, x1, y0, z, barW, c);
        return v;
    }

    private static int emitCross(float[] xyz, int[] col, int v, float x, float y, float z,
            float arm, float w, int c) {
        v = emitBox(xyz, col, v, x - arm, y, z, x + arm, y, z, w, c);
        v = emitBox(xyz, col, v, x, y - arm, z, x, y + arm, z, w, c);
        v = emitBox(xyz, col, v, x, y, z - arm, x, y, z + arm, w, c);
        return v;
    }

    private static int emitBox(float[] xyz, int[] col, int v, float ax, float ay, float az,
            float bx, float by, float bz, float w, int c) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) {
            return v;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        float ux = 0f;
        float uy = 1f;
        float uz = 0f;
        if (Math.abs(dy) > 0.9f) {
            ux = 1f;
            uy = 0f;
            uz = 0f;
        }
        float s1x = dy * uz - dz * uy;
        float s1y = dz * ux - dx * uz;
        float s1z = dx * uy - dy * ux;
        float s1l = (float) Math.sqrt(s1x * s1x + s1y * s1y + s1z * s1z);
        s1x = s1x / s1l * w;
        s1y = s1y / s1l * w;
        s1z = s1z / s1l * w;
        float s2x = dy * s1z - dz * s1y;
        float s2y = dz * s1x - dx * s1z;
        float s2z = dx * s1y - dy * s1x;
        float s2l = (float) Math.sqrt(s2x * s2x + s2y * s2y + s2z * s2z);
        s2x = s2x / s2l * w;
        s2y = s2y / s2l * w;
        s2z = s2z / s2l * w;
        float a0x = ax - s1x - s2x, a0y = ay - s1y - s2y, a0z = az - s1z - s2z;
        float a1x = ax + s1x - s2x, a1y = ay + s1y - s2y, a1z = az + s1z - s2z;
        float a2x = ax + s1x + s2x, a2y = ay + s1y + s2y, a2z = az + s1z + s2z;
        float a3x = ax - s1x + s2x, a3y = ay - s1y + s2y, a3z = az - s1z + s2z;
        float b0x = bx - s1x - s2x, b0y = by - s1y - s2y, b0z = bz - s1z - s2z;
        float b1x = bx + s1x - s2x, b1y = by + s1y - s2y, b1z = bz + s1z - s2z;
        float b2x = bx + s1x + s2x, b2y = by + s1y + s2y, b2z = bz + s1z + s2z;
        float b3x = bx - s1x + s2x, b3y = by - s1y + s2y, b3z = bz - s1z + s2z;
        v = emitQuad(xyz, col, v, a0x, a0y, a0z, a1x, a1y, a1z, b1x, b1y, b1z, b0x, b0y, b0z, c);
        v = emitQuad(xyz, col, v, a1x, a1y, a1z, a2x, a2y, a2z, b2x, b2y, b2z, b1x, b1y, b1z, c);
        v = emitQuad(xyz, col, v, a2x, a2y, a2z, a3x, a3y, a3z, b3x, b3y, b3z, b2x, b2y, b2z, c);
        v = emitQuad(xyz, col, v, a3x, a3y, a3z, a0x, a0y, a0z, b0x, b0y, b0z, b3x, b3y, b3z, c);
        return v;
    }

    private static int emitQuad(float[] xyz, int[] col, int v,
            float x0, float y0, float z0, float x1, float y1, float z1,
            float x2, float y2, float z2, float x3, float y3, float z3, int c) {
        v = putV(xyz, col, v, x0, y0, z0, c);
        v = putV(xyz, col, v, x1, y1, z1, c);
        v = putV(xyz, col, v, x2, y2, z2, c);
        v = putV(xyz, col, v, x3, y3, z3, c);
        return v;
    }

    private static int putV(float[] xyz, int[] col, int v, float x, float y, float z, int c) {
        xyz[v * 3] = x;
        xyz[v * 3 + 1] = y;
        xyz[v * 3 + 2] = z;
        col[v] = c;
        return v + 1;
    }
    //?}
}
