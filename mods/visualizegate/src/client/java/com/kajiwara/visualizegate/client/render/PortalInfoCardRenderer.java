package com.kajiwara.visualizegate.client.render;

import java.util.ArrayList;
import java.util.List;

import com.kajiwara.visualizegate.domain.DomainPortal;
import com.kajiwara.visualizegate.domain.GridPos;
import com.kajiwara.visualizegate.domain.LinkPrediction;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.domain.PredictedLinkState;
import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.state.VgOverlayState;
import com.kajiwara.visualizegate.terrain.TerrainStore;
import com.kajiwara.visualizegate.ui.GateColors;

//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 機能① 自動インフォカード (HUD パス・<b>Mixin 不使用</b>)。
 *
 * <p>照準が「ネザーポータルブロック or 既知ポータルの黒曜石 frame」に当たったときだけ表示し、 外れたら
 * 自動で消す (= 常駐しない)。 source は注視ポータル、 予測は {@link PortalGaze} 経由で機能2 と<b>同じ三値</b>を
 * キャッシュ読みする (ほぼ無コスト)。 状態別に平易な言葉＋色ドットで描く:
 * <ul>
 *   <li>LINKED(緑): 既存ゲートにつながる / ズレ量を平易表現。</li>
 *   <li>WILL_CREATE(赤): 新規ゲートがズレ無しで生成 (赤＝悪いではなく状態)。</li>
 *   <li>UNKNOWN(灰): リンク先未確認。</li>
 * </ul>
 * かんたん (既定) は見出し＋1行。 詳細 ({@link GateMenuState#isAdvancedMode()}) で座標/ズレ/対象ゲートID/確度を追加。
 * 配置は画面下中央 (照準とホットバーを塞がない)。 F1(hideGui)/F3/他 Screen 表示中は非表示。
 */
public final class PortalInfoCardRenderer {

    private static final PortalInfoCardRenderer INSTANCE = new PortalInfoCardRenderer();

    private static final int PAD = 5;
    private static final int LINE_H = 11;
    private static final int DOT = 7;
    private static final int BOTTOM_MARGIN = 48; // ホットバー上の余白
    // 半透明パネル (GateColors.PANEL を ~88% alpha 化＝背後を透かす)。
    private static final int PANEL_BG = 0xE01A1326;

    // 平易表現のズレしきい値。
    private static final double ALIGNED_BLOCKS = 2.0;       // これ未満は「ほぼぴったり」
    private static final double BIG_OFFSET_FRACTION = 0.5;  // searchRadius のこの割合超で「ズレ大」
    // 予定地が観測サーフェスからこれ以上下なら「地中/洞窟に生成され得る」警告 (ヒューリスティック)。
    private static final int UNDERGROUND_DEPTH = 8;

    private PortalInfoCardRenderer() {
    }

    public static void register() {
        //? if >=26.1 {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("visualizegate", "info_card"),
                (g, deltaTracker) -> INSTANCE.onHudRender(g));
        //?} else {
        /*HudRenderCallback.EVENT.register((g, deltaTracker) -> INSTANCE.onHudRender(g));*/
        //?}
    }

    /** 1 行 = テキスト＋色。 */
    private record Line(Component text, int color) {
    }

    private void onHudRender(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui)                       // F1
            return;
        if (mc.screen != null)                        // 他 Screen 表示中
            return;
        if (mc.getDebugOverlay().showDebugScreen())   // F3 デバッグ中
            return;
        if (VgOverlayState.isCloudSolo())             // ⑤⑤ 点群ソロ中は注視カードも抑止 (パネルだけ残す)
            return;

        PortalGaze.Result r = PortalGaze.resolve(mc);
        if (r == null) {
            return; // ポータルを見ていない or OW↔Nether 以外
        }

        LinkPrediction pred = r.prediction();
        int dotColor = dotColor(pred.state());
        Component headline = headline(pred.state());
        boolean advanced = GateMenuState.isAdvancedMode();

        List<Line> body = new ArrayList<>();
        addSummaryLine(body, pred);
        addCaveRiskLine(body, r, pred, advanced); // ③ 地中/洞窟生成の可能性
        if (advanced) {
            addDetailLines(body, r, pred);
        }

        // ─── レイアウト ───
        int headlineW = DOT + 4 + mc.font.width(headline);
        int contentW = headlineW;
        for (Line l : body) {
            contentW = Math.max(contentW, mc.font.width(l.text()));
        }
        int rows = 1 + body.size();
        int panelW = contentW + PAD * 2;
        int panelH = rows * LINE_H + PAD * 2 - 2;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int x0 = (sw - panelW) / 2;
        int y1 = sh - BOTTOM_MARGIN;
        int y0 = y1 - panelH;

        // パネル＋紫枠 (1px)。
        g.fill(x0, y0, x0 + panelW, y1, PANEL_BG);
        g.fill(x0, y0, x0 + panelW, y0 + 1, GateColors.MAIN);
        g.fill(x0, y1 - 1, x0 + panelW, y1, GateColors.MAIN);
        g.fill(x0, y0, x0 + 1, y1, GateColors.MAIN);
        g.fill(x0 + panelW - 1, y0, x0 + panelW, y1, GateColors.MAIN);

        int tx = x0 + PAD;
        int ty = y0 + PAD;
        // 見出し: 状態ドット＋テキスト。
        g.fill(tx, ty + 1, tx + DOT, ty + 1 + DOT, dotColor);
        g.text(mc.font, headline, tx + DOT + 4, ty, GateColors.TEXT);
        ty += LINE_H;
        for (Line l : body) {
            g.text(mc.font, l.text(), tx, ty, l.color());
            ty += LINE_H;
        }
    }

    // ── 文面組み立て ────────────────────────────────────────────────────

    private static int dotColor(com.kajiwara.visualizegate.domain.PredictedLinkState state) {
        return switch (state) {
            case LINKED -> GateColors.LINK_GREEN;
            case WILL_CREATE -> GateColors.LINK_RED;
            case UNKNOWN -> GateColors.LINK_GRAY;
        };
    }

    private static Component headline(com.kajiwara.visualizegate.domain.PredictedLinkState state) {
        return switch (state) {
            case LINKED -> Component.translatable("visualizegate.card.linked.title");
            case WILL_CREATE -> Component.translatable("visualizegate.card.will_create.title");
            case UNKNOWN -> Component.translatable("visualizegate.card.unknown.title");
        };
    }

    /** かんたん/詳細 共通の 1 行サマリ。 */
    private static void addSummaryLine(List<Line> body, LinkPrediction pred) {
        switch (pred.state()) {
            case LINKED -> {
                double off = pred.offsetDistance();
                if (off < ALIGNED_BLOCKS) {
                    body.add(new Line(Component.translatable("visualizegate.card.linked.aligned"),
                            GateColors.LINK_GREEN));
                } else if (off > pred.searchRadius() * BIG_OFFSET_FRACTION) {
                    body.add(new Line(Component.translatable("visualizegate.card.linked.offset_big"),
                            GateColors.ACCENT));
                } else {
                    body.add(new Line(Component.translatable("visualizegate.card.linked.offset",
                            (int) Math.round(off)), GateColors.TEXT));
                }
            }
            case WILL_CREATE -> body.add(new Line(
                    Component.translatable("visualizegate.card.will_create.desc"), GateColors.TEXT));
            case UNKNOWN -> body.add(new Line(
                    Component.translatable("visualizegate.card.unknown.desc"), GateColors.TEXT));
        }
    }

    /**
     * ③「地中/洞窟生成の可能性」行 (ヒューリスティック・事実でなく可能性として表現)。
     *
     * <p>LINKED は既存ゲートへ接続＝新規生成なしなので対象外。 ターゲットがネザー側なら基本囲って生成される旨を
     * 一言。 ターゲットが OW 側のとき、 予定地 XZ の観測サーフェス Y と予測ターゲット Y を比較し、 十分下なら
     * 「地中/洞窟に生成され得る」、 サーフェス未観測なら UNKNOWN (向こう未探索＝判定不可)。 詳細モードでは
     * 対象 Y・推定サーフェス Y を併記。 バニラ配置ロジックの完全再現はしない (Y とサーフェス高の比較のみ)。
     */
    private static void addCaveRiskLine(List<Line> body, PortalGaze.Result r, LinkPrediction pred,
            boolean advanced) {
        if (pred.state() == PredictedLinkState.LINKED) {
            return; // 既存ゲートへ接続＝新規生成なし
        }
        if (r.other() == PortalDimension.NETHER) {
            body.add(new Line(Component.translatable("visualizegate.card.cave.nether_enclosed"),
                    GateColors.LINK_GRAY));
            return;
        }
        GridPos ideal = pred.idealTarget();
        java.util.OptionalInt surf =
                TerrainStore.get().surfaceYAt(PortalDimension.OVERWORLD, ideal.x(), ideal.z());
        if (surf.isEmpty()) {
            body.add(new Line(Component.translatable("visualizegate.card.cave.unknown"),
                    GateColors.LINK_GRAY));
            return;
        }
        int surfaceY = surf.getAsInt();
        int depth = surfaceY - ideal.y();
        if (depth > UNDERGROUND_DEPTH) {
            body.add(new Line(Component.translatable("visualizegate.card.cave.risk", depth),
                    GateColors.ACCENT));
        } else {
            body.add(new Line(Component.translatable("visualizegate.card.cave.surface_ok"),
                    GateColors.LINK_GREEN));
        }
        if (advanced) {
            body.add(new Line(Component.translatable("visualizegate.card.cave.detail",
                    ideal.y(), surfaceY), GateColors.TEXT));
        }
    }

    /** 詳細モード追加行: 向こう側座標 / ズレ / 対象ゲートID / 確度。 */
    private static void addDetailLines(List<Line> body, PortalGaze.Result r, LinkPrediction pred) {
        Component otherDim = dimName(r.other());
        if (pred.state() == com.kajiwara.visualizegate.domain.PredictedLinkState.LINKED
                && pred.matched().isPresent()) {
            DomainPortal m = pred.matched().get();
            GridPos a = m.anchor();
            body.add(new Line(Component.translatable("visualizegate.card.detail.target",
                    otherDim, a.x(), a.y(), a.z()), GateColors.MAIN));
            body.add(new Line(Component.translatable("visualizegate.card.detail.offset",
                    (int) Math.round(pred.offsetDistance())), GateColors.TEXT));
            body.add(new Line(Component.translatable("visualizegate.card.detail.gate",
                    a.x(), a.y(), a.z()), GateColors.TEXT));
            body.add(new Line(m.liveConfirmed()
                    ? Component.translatable("visualizegate.card.detail.live")
                    : Component.translatable("visualizegate.card.detail.memory"), GateColors.TEXT));
        } else {
            // WILL_CREATE / UNKNOWN: 写像した予定地座標を見せる。
            GridPos ideal = pred.idealTarget();
            body.add(new Line(Component.translatable("visualizegate.card.detail.ideal",
                    otherDim, ideal.x(), ideal.y(), ideal.z()), GateColors.MAIN));
        }
    }

    private static Component dimName(PortalDimension dim) {
        return (dim == PortalDimension.NETHER)
                ? Component.translatable("visualizegate.dim.nether")
                : Component.translatable("visualizegate.dim.overworld");
    }
}
