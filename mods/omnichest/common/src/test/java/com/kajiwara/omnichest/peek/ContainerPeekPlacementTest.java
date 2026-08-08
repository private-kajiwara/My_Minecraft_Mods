package com.kajiwara.omnichest.peek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.kajiwara.omnichest.peek.ContainerPeekFit.Layout;
import com.kajiwara.omnichest.peek.ContainerPeekFit.Placement;
import org.junit.jupiter.api.Test;

/**
 * コンテナ ピーク ポップアップの<b>配置</b>の回帰テスト。
 *
 * <p>
 * <b>捉えている不具合は 2 世代ある</b>:
 * <ol>
 *   <li><b>第 1 世代</b>: 下端の基準が 「画面下端 − マージン 4px」 でしかなく、 下部 HUD を
 *       知らなかったため、 ラージチェスト (54 スロット = 6 行 = 154px) のサマリ行が
 *       ホットバーに重なった。</li>
 *   <li><b>第 2 世代</b>: 上下どちらにも入らないときパネルを画面中央へ置き、 描画順で
 *       クロスヘアを手前に出して 「見える」 ようにしていた。 しかし<b>クロスヘアが
 *       グリッド内のアイテムに重なってそのアイテムが読めない</b>。 「見える」 と 「読める」 は
 *       別なので、 重ねること自体をやめ、 <b>横に逃がす</b> → それでも無理なら
 *       <b>要約リスト (コンパクトモード)</b> に切り替える方式にした。</li>
 * </ol>
 * 判定条件 ③ は 「クロスヘアが可視」 ではなく <b>「クロスヘアの矩形と交差しない」</b> である。
 *
 * <p>
 * <b>下部 HUD の高さは発明せず、 バニラのバイトコード実測値を使う</b>
 * ({@link ContainerPeekFit#BOTTOM_HUD_HEIGHT} とその周辺定数の javadoc に出典を明記)。
 *
 * <p>
 * <b>★パネル寸法のミラーについて</b>: パネルの幅・高さの式は MC 側の
 * {@code AltPreviewPopupRenderer.panelWidth/panelHeight} と {@code PopupThemeResolver} が
 * 持っており、 {@code common} からは参照できないため、 ここに<b>写して</b>いる
 * ({@code TextContrastFitTest} がカテゴリ色表を、 {@code SidePanelFitTest} がラベル実測幅を
 * 写しているのと同流儀)。 <b>{@code PopupThemeResolver} の寸法定数を変えたらここも更新すること。</b>
 * production 側は今までどおり {@code AltPreviewPopupRenderer} の値をそのまま使うので、
 * 真実の源は 1 つのままである。
 */
class ContainerPeekPlacementTest {

    // ════════════════════════════════════════════════════════════════════
    // 掃き出しの軸
    // ════════════════════════════════════════════════════════════════════

    /** 代表解像度 20 種 ({@code ExistingCategoriesFitTest} と同一)。 */
    private static final int[][] RESOLUTIONS = {
            { 640, 480 }, { 854, 480 }, { 1024, 768 }, { 1280, 720 }, { 1280, 800 },
            { 1280, 1024 }, { 1366, 768 }, { 1440, 900 }, { 1600, 900 }, { 1600, 1200 },
            { 1680, 1050 }, { 1920, 1080 }, { 1920, 1200 }, { 2048, 1536 }, { 2560, 1080 },
            { 2560, 1440 }, { 2560, 1600 }, { 3440, 1440 }, { 3835, 2076 }, { 3840, 2160 },
    };

    /** GUI スケール設定。 {@code 0} = Auto。 */
    private static final int[] GUI_SCALES = { 1, 2, 3, 4, 5, 6, 7, 8, 0 };

    /** スロット数。 {@code -1} = 未登録 / 設定 OFF のお知らせパネル (= グリッドなし)。 */
    private static final int[] SLOT_COUNTS = { -1, 5, 9, 27, 54 };

    /** 総組み合わせ数 (= 20 x 9 x 5)。 */
    private static final int TOTAL = 900;

    // ════════════════════════════════════════════════════════════════════
    // MC 側の再現
    // ════════════════════════════════════════════════════════════════════

    /** {@code Window#calculateScale} (MC 26.1.2 バイトコードの実写)。 */
    private static int calculateScale(int guiScaleSetting, int fbW, int fbH, boolean forceUnicode) {
        int i = 1;
        while (i != guiScaleSetting && i < fbW && i < fbH
                && fbW / (i + 1) >= 320 && fbH / (i + 1) >= 240) {
            i++;
        }
        if (forceUnicode && i % 2 != 0) {
            i++;
        }
        return i;
    }

    /** {@code Window#setGuiScale} と同じ {@code ceil(framebuffer / scale)}。 */
    private static int logical(int framebuffer, int scale) {
        return (framebuffer + scale - 1) / scale;
    }

    // ── PopupThemeResolver / AltPreviewPopupRenderer のミラー (上の javadoc 参照) ──
    private static final int CELL = 18;
    private static final int PANEL_PADDING = 6;
    private static final int TITLE_HEIGHT = 9;
    private static final int SUMMARY_HEIGHT = 11;
    private static final int SEPARATOR_GAP = 3;
    private static final int LINE_H = 9;

    /** パネルの固定部 (= 余白 + タイトル + セパレータ 2 本 + サマリ)。 グリッドもコンパクトも共通。 */
    private static final int PANEL_BASE =
            PANEL_PADDING * 2 + TITLE_HEIGHT + (SEPARATOR_GAP + 1 + SEPARATOR_GAP) * 2 + SUMMARY_HEIGHT;

    /** {@code AltPreviewPopupRenderer.panelWidth} のミラー。 */
    private static int panelWidth(int columns) {
        return PANEL_PADDING * 2 + columns * CELL;
    }

    /** {@code AltPreviewPopupRenderer.panelHeight} のミラー。 */
    private static int panelHeight(int columns, int slotCount) {
        int rows = Math.max(1, (slotCount + columns - 1) / columns);
        return PANEL_BASE + rows * CELL;
    }

    /** {@code ContainerPeekRenderer.drawNotice} のパネル高 (= タイトル + 区切り + 2 行)。 */
    private static int noticePanelHeight() {
        return PANEL_PADDING * 2 + TITLE_HEIGHT + SEPARATOR_GAP + 1 + SEPARATOR_GAP
                + LINE_H + SEPARATOR_GAP + LINE_H;
    }

    /** 未登録パネルの代表幅 (= 日本語の案内文がおよそ収まる幅)。 */
    private static final int NOTICE_PANEL_WIDTH = 220;

    // ── コンパクトモードのミラー ({@code ContainerPeekRenderer.compactHeight/Width}) ──
    /** 1 行の高さ = {@code font.lineHeight} (= 既存ピン行と同じ、 アイコンも同サイズ)。 */
    private static final int COMPACT_ROW_H = LINE_H;

    /** コンパクトの高さ。 行数 = 上位 N 種 + (他 M 種 の行)。 */
    private static int compactHeight(int slotCount) {
        if (slotCount < 0) {
            return noticePanelHeight();
        }
        // 最悪ケース: 種類数が上限を超えて 「他 M 種」 行が付く。
        int rows = Math.min(PeekSummary.TOP_N, slotCount) + 1;
        return PANEL_BASE + rows * COMPACT_ROW_H;
    }

    /** コンパクトの幅は上限で頭打ち ({@code ContainerPeekRenderer.COMPACT_MAX_WIDTH})。 */
    private static final int COMPACT_MAX_WIDTH = 174;

    private static int compactWidth(int slotCount) {
        return slotCount < 0 ? NOTICE_PANEL_WIDTH : COMPACT_MAX_WIDTH;
    }

    private static int gridWidth(int slotCount) {
        return slotCount < 0 ? NOTICE_PANEL_WIDTH
                : panelWidth(ContainerPeekFit.gridColumns(slotCount));
    }

    private static int gridHeight(int slotCount) {
        return slotCount < 0 ? noticePanelHeight()
                : panelHeight(ContainerPeekFit.gridColumns(slotCount), slotCount);
    }

    // ════════════════════════════════════════════════════════════════════
    // 判定
    // ════════════════════════════════════════════════════════════════════

    private record Case(int fbW, int fbH, int scaleSetting, int slots,
            int guiW, int guiH, Layout layout) {

        @Override
        public String toString() {
            return String.format("%dx%d scale=%s slots=%s logical=%dx%d -> %s%s panel=%dx%d at (%d,%d)",
                    fbW, fbH, scaleSetting == 0 ? "Auto" : String.valueOf(scaleSetting),
                    slots < 0 ? "notice" : String.valueOf(slots),
                    guiW, guiH, layout.placement(), layout.compact() ? "/compact" : "",
                    layout.width(), layout.height(), layout.x(), layout.y());
        }
    }

    /** ① 下部 HUD 帯に侵入していない。 */
    private static boolean clearsBottomHud(Case c) {
        return c.layout.y() + c.layout.height() <= c.guiH - ContainerPeekFit.BOTTOM_HUD_HEIGHT;
    }

    /** ② 画面外に出ていない (上下左右)。 */
    private static boolean insideScreen(Case c) {
        return c.layout.x() >= 0 && c.layout.y() >= 0
                && c.layout.x() + c.layout.width() <= c.guiW
                && c.layout.y() + c.layout.height() <= c.guiH;
    }

    /** ③ ★クロスヘアの矩形と 1px も交差しない (= 「可視」 ではなく 「非重複」)。 */
    private static boolean clearsCrosshair(Case c) {
        int cx = c.guiW / 2;
        int cy = c.guiH / 2;
        int half = ContainerPeekFit.CROSSHAIR_HALF;
        boolean overlapX = c.layout.x() < cx + half && c.layout.x() + c.layout.width() > cx - half;
        boolean overlapY = c.layout.y() < cy + half && c.layout.y() + c.layout.height() > cy - half;
        return !(overlapX && overlapY);
    }

    /** ④ タイトル行とサマリ行がともに可視 (= パネル全体が安全帯の中)。 */
    private static boolean titleAndSummaryVisible(Case c) {
        return c.layout.y() >= ContainerPeekFit.SCREEN_MARGIN && clearsBottomHud(c);
    }

    private static List<Case> sweep() {
        List<Case> out = new ArrayList<>(TOTAL);
        for (int[] res : RESOLUTIONS) {
            for (int setting : GUI_SCALES) {
                int scale = calculateScale(setting, res[0], res[1], false);
                int guiW = logical(res[0], scale);
                int guiH = logical(res[1], scale);
                for (int slots : SLOT_COUNTS) {
                    Layout l = ContainerPeekFit.layout(guiW, guiH, guiW / 2, guiH / 2,
                            gridWidth(slots), gridHeight(slots),
                            compactWidth(slots), compactHeight(slots));
                    out.add(new Case(res[0], res[1], setting, slots, guiW, guiH, l));
                }
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // 本体
    // ════════════════════════════════════════════════════════════════════

    @Test
    void sweepCoversTheWholeMatrix() {
        assertEquals(TOTAL, sweep().size(), "20 解像度 x 9 スケール x 5 スロット数");
    }

    @Test
    void neverIntrudesIntoTheBottomHud() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (!clearsBottomHud(c)) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(), "下部 HUD に侵入 " + bad.size() + " 件: " + head(bad));
    }

    @Test
    void neverLeavesTheScreen() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (!insideScreen(c)) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(), "画面外へ出た " + bad.size() + " 件: " + head(bad));
    }

    /** ★今回の症状。 クロスヘアと 1px も重ならないことを<b>全通りで</b>要求する。 */
    @Test
    void neverOverlapsTheCrosshair() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (!clearsCrosshair(c)) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(), "クロスヘアと重なった " + bad.size() + " 件: " + head(bad));
    }

    @Test
    void titleAndSummaryAreAlwaysVisible() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (!titleAndSummaryVisible(c)) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(), "タイトル / サマリが隠れた " + bad.size() + " 件: " + head(bad));
    }

    /** サポート範囲では最後の砦 ({@link Placement#CLAMPED}) に落ちないこと。 */
    @Test
    void neverFallsBackToTheClampedPlacement() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (c.layout.placement() == Placement.CLAMPED) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(), "CLAMPED に落ちた " + bad.size() + " 件: " + head(bad));
    }

    // ════════════════════════════════════════════════════════════════════
    // 配置モードの内訳 (= 想定どおりの分布か)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void placementBreakdownMatchesTheMeasuredDistribution() {
        Map<Placement, Integer> byPlacement = new EnumMap<>(Placement.class);
        int compact = 0;
        int compactNon54 = 0;
        for (Case c : sweep()) {
            byPlacement.merge(c.layout.placement(), 1, Integer::sum);
            if (c.layout.compact()) {
                compact++;
                if (c.slots != 54) {
                    compactNon54++;
                }
            }
        }
        String s = byPlacement + " compact=" + compact;
        // 大半は素直にクロスヘアの下。
        assertTrue(byPlacement.getOrDefault(Placement.BELOW, 0) > TOTAL / 2, s);
        // 横に逃がす手が実際に効いていること (= 死んだ分岐ではない)。
        assertTrue(byPlacement.getOrDefault(Placement.SIDE_RIGHT, 0)
                + byPlacement.getOrDefault(Placement.SIDE_LEFT, 0) > 0, s);
        // ★コンパクトはあくまでフォールバック。 全体の 5% 未満に収まっていること。
        assertTrue(compact * 100 < TOTAL * 5, "コンパクトが多すぎる: " + s);
        // コンパクトへ落ちるのはラージチェストだけ (= 27 以下は必ずグリッドで置ける)。
        assertEquals(0, compactNon54, "54 スロット以外がコンパクトへ落ちた: " + s);
    }

    // ════════════════════════════════════════════════════════════════════
    // ★ 前世代の実装が新条件で落ちること (= テストが症状を捉えている証明)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 前世代 (= 横逃がし無し・上下に入らなければ安全帯の中央へ重ねる) の配置を写したもの。
     */
    private static int[] legacyCenteredXY(int guiW, int guiH, int pw, int ph) {
        int gap = ContainerPeekFit.CROSSHAIR_GAP;
        int m = ContainerPeekFit.SCREEN_MARGIN;
        int safeBottom = guiH - Math.max(m, ContainerPeekFit.BOTTOM_HUD_HEIGHT);
        int x = ContainerPeekFit.popupX(guiW, pw, guiW / 2);
        if (guiH / 2 + gap + ph <= safeBottom) {
            return new int[] { x, guiH / 2 + gap };
        }
        if (guiH / 2 - gap - ph >= m) {
            return new int[] { x, guiH / 2 - gap - ph };
        }
        if (ph <= safeBottom - m) {
            return new int[] { x, m + (safeBottom - m - ph) / 2 };
        }
        return new int[] { x, m };
    }

    @Test
    void previousImplementationOverlapsTheCrosshairAndTheFixRemovesIt() {
        int legacyOverlap = 0;
        int legacyOverlap54 = 0;
        for (int[] res : RESOLUTIONS) {
            for (int setting : GUI_SCALES) {
                int scale = calculateScale(setting, res[0], res[1], false);
                int guiW = logical(res[0], scale);
                int guiH = logical(res[1], scale);
                for (int slots : SLOT_COUNTS) {
                    int pw = gridWidth(slots);
                    int ph = gridHeight(slots);
                    int[] xy = legacyCenteredXY(guiW, guiH, pw, ph);
                    int cx = guiW / 2;
                    int cy = guiH / 2;
                    int half = ContainerPeekFit.CROSSHAIR_HALF;
                    boolean ox = xy[0] < cx + half && xy[0] + pw > cx - half;
                    boolean oy = xy[1] < cy + half && xy[1] + ph > cy - half;
                    if (ox && oy) {
                        legacyOverlap++;
                        if (slots == 54) {
                            legacyOverlap54++;
                        }
                    }
                }
            }
        }
        assertTrue(legacyOverlap > 0,
                "前世代がクロスヘアと重ならない = テストが症状を捉えていない");
        assertEquals(legacyOverlap, legacyOverlap54,
                "重なりは 54 スロット限定のはず (合計=" + legacyOverlap + ")");

        int fixedOverlap = 0;
        for (Case c : sweep()) {
            if (!clearsCrosshair(c)) {
                fixedOverlap++;
            }
        }
        assertEquals(0, fixedOverlap, "修正後は 0 件 (前世代: " + legacyOverlap + " 件)");
    }

    // ════════════════════════════════════════════════════════════════════
    // 個別の境界
    // ════════════════════════════════════════════════════════════════════

    @Test
    void largeChestOnATallScreenStaysBelowTheCrosshair() {
        // 1920x1080 / GUI スケール 2 → 論理 960x540。
        Layout l = ContainerPeekFit.layout(960, 540, 480, 270,
                gridWidth(54), gridHeight(54), compactWidth(54), compactHeight(54));
        assertEquals(Placement.BELOW, l.placement());
        assertEquals(false, l.compact());
        assertEquals(270 + ContainerPeekFit.CROSSHAIR_GAP, l.y());
    }

    @Test
    void largeChestFlipsAboveOnAMediumScreen() {
        // 1920x1080 / GUI スケール 3 → 論理 640x360。
        Layout l = ContainerPeekFit.layout(640, 360, 320, 180,
                gridWidth(54), gridHeight(54), compactWidth(54), compactHeight(54));
        assertEquals(Placement.ABOVE, l.placement());
        assertEquals(false, l.compact());
        assertEquals(180 - ContainerPeekFit.CROSSHAIR_GAP - 154, l.y());
    }

    @Test
    void largeChestGoesToTheSideOnAShortWideScreen() {
        // 1920x1080 / GUI スケール 4 → 論理 480x270。 上下に入らないので右へ逃がす。
        // (前世代はここでクロスヘアに重ねていた。)
        Layout l = ContainerPeekFit.layout(480, 270, 240, 135,
                gridWidth(54), gridHeight(54), compactWidth(54), compactHeight(54));
        assertEquals(Placement.SIDE_RIGHT, l.placement());
        assertEquals(false, l.compact(), "横に逃がせるならグリッドのままであること");
        assertEquals(240 + ContainerPeekFit.CROSSHAIR_GAP, l.x());
        assertTrue(l.x() + l.width() <= 480 - ContainerPeekFit.SCREEN_MARGIN);
    }

    @Test
    void largeChestFallsBackToCompactOnASmallScreen() {
        // 640x480 / GUI スケール 2 以上 → 論理 320x240。 上下にも横にも入らない。
        Layout l = ContainerPeekFit.layout(320, 240, 160, 120,
                gridWidth(54), gridHeight(54), compactWidth(54), compactHeight(54));
        assertEquals(true, l.compact(), "グリッドを諦めてコンパクトへ落ちること");
        assertTrue(l.placement() != Placement.CLAMPED, "CLAMPED まで落ちてはいけない");
        assertEquals(compactHeight(54), l.height());
        assertTrue(l.y() + l.height() <= 240 - ContainerPeekFit.BOTTOM_HUD_HEIGHT);
    }

    @Test
    void smallPanelsAreUnaffected() {
        for (int slots : new int[] { -1, 5, 9, 27 }) {
            Layout l = ContainerPeekFit.layout(640, 360, 320, 180,
                    gridWidth(slots), gridHeight(slots), compactWidth(slots), compactHeight(slots));
            assertEquals(false, l.compact(), "slots=" + slots);
            assertTrue(l.placement() == Placement.BELOW || l.placement() == Placement.ABOVE,
                    "slots=" + slots + " -> " + l.placement());
        }
    }

    @Test
    void compactIsShorterThanTheGridForLargeChests() {
        // フォールバックの意味があること (= コンパクトのほうが確実に低い)。
        assertTrue(compactHeight(54) < gridHeight(54),
                "compact=" + compactHeight(54) + " grid=" + gridHeight(54));
        // 論理 320x240 の安全帯 (= 240-59-4 = 177) に収まること。
        assertTrue(compactHeight(54) <= 240 - ContainerPeekFit.BOTTOM_HUD_HEIGHT
                - ContainerPeekFit.SCREEN_MARGIN, "compact=" + compactHeight(54));
    }

    @Test
    void bottomHudHeightMatchesTheVanillaMeasurements() {
        assertEquals(22, ContainerPeekFit.HOTBAR_TOP_OFFSET);
        assertEquals(29, ContainerPeekFit.CONTEXTUAL_BAR_TOP_OFFSET);
        assertEquals(39, ContainerPeekFit.STATUS_BAR_TOP_OFFSET);
        assertEquals(49, ContainerPeekFit.ARMOR_BAR_TOP_OFFSET);
        assertEquals(59, ContainerPeekFit.HELD_ITEM_NAME_TOP_OFFSET);
        assertEquals(59, ContainerPeekFit.BOTTOM_HUD_HEIGHT);
        // クロスヘア非重複が構造的に保証される条件。
        assertTrue(ContainerPeekFit.CROSSHAIR_GAP > ContainerPeekFit.CROSSHAIR_HALF);
    }

    private static String head(List<String> list) {
        return list.size() <= 3 ? String.join(" | ", list)
                : String.join(" | ", list.subList(0, 3)) + " …";
    }
}
