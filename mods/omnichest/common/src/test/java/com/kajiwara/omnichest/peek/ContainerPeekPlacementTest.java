package com.kajiwara.omnichest.peek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * コンテナ ピーク ポップアップの<b>配置</b>の回帰テスト。
 *
 * <p>
 * <b>捉えている不具合</b>: 実機 (26.1.2 / シェーダー ON) で、 ラージチェスト (54 スロット = 6 行 =
 * 高さ 154px) のときだけポップアップ下端が<b>ホットバーに重なり</b>、 サマリ行
 * 「3 / 54 · ×4」 が読めなくなった。 27 スロット (3 行 = 100px) や未登録表示 (49px) では
 * 起きなかった。 原因は {@link ContainerPeekFit#popupY} の下端基準が
 * 「画面下端 − マージン 4px」 でしかなく、 <b>画面下部 HUD の存在を知らなかった</b>こと。
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

    /**
     * スロット数。 {@code -1} = 未登録 / 設定 OFF のお知らせパネル (= グリッドなし)。
     * 5 = ホッパー相当、 9 = ディスペンサー相当、 27 = チェスト / 樽 / シュルカー、
     * 54 = ラージチェスト (= 症状が出たケース)。
     */
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

    /** {@code AltPreviewPopupRenderer.panelWidth} のミラー。 */
    private static int panelWidth(int columns) {
        return PANEL_PADDING * 2 + columns * CELL;
    }

    /** {@code AltPreviewPopupRenderer.panelHeight} のミラー。 */
    private static int panelHeight(int columns, int slotCount) {
        int rows = Math.max(1, (slotCount + columns - 1) / columns);
        return PANEL_PADDING * 2
                + TITLE_HEIGHT
                + SEPARATOR_GAP + 1 + SEPARATOR_GAP
                + rows * CELL
                + SEPARATOR_GAP + 1 + SEPARATOR_GAP
                + SUMMARY_HEIGHT;
    }

    /** {@code ContainerPeekRenderer.drawNotice} のパネル高 (= タイトル + 区切り + 2 行)。 */
    private static int noticePanelHeight() {
        return PANEL_PADDING * 2 + TITLE_HEIGHT + SEPARATOR_GAP + 1 + SEPARATOR_GAP
                + LINE_H + SEPARATOR_GAP + LINE_H;
    }

    /** 未登録パネルの代表幅 (= 日本語の案内文がおよそ収まる幅)。 */
    private static final int NOTICE_PANEL_WIDTH = 220;

    private static int panelWidthFor(int slotCount) {
        return slotCount < 0 ? NOTICE_PANEL_WIDTH
                : panelWidth(ContainerPeekFit.gridColumns(slotCount));
    }

    private static int panelHeightFor(int slotCount) {
        return slotCount < 0 ? noticePanelHeight()
                : panelHeight(ContainerPeekFit.gridColumns(slotCount), slotCount);
    }

    // ════════════════════════════════════════════════════════════════════
    // 判定
    // ════════════════════════════════════════════════════════════════════

    private record Case(int fbW, int fbH, int scaleSetting, int slots,
            int guiW, int guiH, int panelW, int panelH, int x, int y) {

        @Override
        public String toString() {
            return String.format("%dx%d scale=%s slots=%s logical=%dx%d panel=%dx%d at (%d,%d)",
                    fbW, fbH, scaleSetting == 0 ? "Auto" : String.valueOf(scaleSetting),
                    slots < 0 ? "notice" : String.valueOf(slots),
                    guiW, guiH, panelW, panelH, x, y);
        }
    }

    /** ① 下部 HUD 帯に侵入していない。 */
    private static boolean clearsBottomHud(Case c) {
        return c.y + c.panelH <= c.guiH - ContainerPeekFit.BOTTOM_HUD_HEIGHT;
    }

    /** ② 画面外に出ていない (上下左右)。 */
    private static boolean insideScreen(Case c) {
        return c.x >= 0 && c.y >= 0
                && c.x + c.panelW <= c.guiW
                && c.y + c.panelH <= c.guiH;
    }

    /** ③ クロスヘア (中央 15x15) を覆っていない。 */
    private static boolean clearsCrosshair(Case c) {
        int cx = c.guiW / 2;
        int cy = c.guiH / 2;
        int half = ContainerPeekFit.CROSSHAIR_HALF;
        boolean overlapX = c.x < cx + half && c.x + c.panelW > cx - half;
        boolean overlapY = c.y < cy + half && c.y + c.panelH > cy - half;
        return !(overlapX && overlapY);
    }

    /** ④ タイトル行とサマリ行がともに可視 (= パネルの上端と下端が安全帯の中)。 */
    private static boolean titleAndSummaryVisible(Case c) {
        boolean titleOk = c.y >= 0 && c.y + TITLE_HEIGHT <= c.guiH;
        boolean summaryOk = c.y + c.panelH <= c.guiH - ContainerPeekFit.BOTTOM_HUD_HEIGHT;
        return titleOk && summaryOk;
    }

    /**
     * 「クロスヘアを避けた配置が幾何的に可能か」 を<b>実装とは独立に</b>判定する。
     * 上側にも下側にも入らないパネルは、 どう置いてもクロスヘア帯と縦に重なる。
     */
    private static boolean crosshairAvoidanceIsPossible(int guiH, int panelH) {
        int gap = ContainerPeekFit.CROSSHAIR_GAP;
        int margin = ContainerPeekFit.SCREEN_MARGIN;
        int cy = guiH / 2;
        boolean below = cy + gap + panelH <= guiH - ContainerPeekFit.BOTTOM_HUD_HEIGHT;
        boolean above = cy - gap - panelH >= margin;
        return below || above;
    }

    /** 安全帯 (= 上マージン 〜 HUD 帯上端) にパネルが収まるか。 */
    private static boolean fitsInSafeBand(int guiH, int panelH) {
        return panelH <= guiH - ContainerPeekFit.BOTTOM_HUD_HEIGHT - ContainerPeekFit.SCREEN_MARGIN;
    }

    /** 現行実装で全組み合わせを算出する。 */
    private static List<Case> sweep() {
        List<Case> out = new ArrayList<>(TOTAL);
        for (int[] res : RESOLUTIONS) {
            for (int setting : GUI_SCALES) {
                int scale = calculateScale(setting, res[0], res[1], false);
                int guiW = logical(res[0], scale);
                int guiH = logical(res[1], scale);
                for (int slots : SLOT_COUNTS) {
                    int panelW = panelWidthFor(slots);
                    int panelH = panelHeightFor(slots);
                    int x = ContainerPeekFit.popupX(guiW, panelW, guiW / 2);
                    int y = ContainerPeekFit.popupY(guiH, panelH, guiH / 2);
                    out.add(new Case(res[0], res[1], setting, slots, guiW, guiH, panelW, panelH, x, y));
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
        assertTrue(bad.isEmpty(),
                "下部 HUD に侵入した組み合わせ " + bad.size() + " 件: " + head(bad));
    }

    @Test
    void neverLeavesTheScreen() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (!insideScreen(c)) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(), "画面外へ出た組み合わせ " + bad.size() + " 件: " + head(bad));
    }

    @Test
    void titleAndSummaryAreAlwaysVisible() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (!titleAndSummaryVisible(c)) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(),
                "タイトル / サマリが隠れた組み合わせ " + bad.size() + " 件: " + head(bad));
    }

    /**
     * ③ クロスヘア非被り。 <b>幾何的に可能な組み合わせでは必ず避ける</b>ことを固定する。
     *
     * <p>
     * 縦長パネル (= 54 スロット) × 縦の狭い論理画面では、 上下どちらの側にも入らないため
     * どう置いても重なる。 その組み合わせでは重なりを許すが、 <b>実装が勝手にサボっていない</b>
     * ことを保証するため、 「可能かどうか」 は実装と独立な {@link #crosshairAvoidanceIsPossible}
     * で判定している。 重なる場合でもバニラのクロスヘアが上に描かれる (=
     * {@code ContainerPeekRenderer} を {@code VanillaHudElements.CROSSHAIR} の<b>前</b>に登録)
     * ため、 照準は画面上では見えたままになる。
     */
    @Test
    void avoidsTheCrosshairWheneverGeometricallyPossible() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (crosshairAvoidanceIsPossible(c.guiH, c.panelH) && !clearsCrosshair(c)) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(),
                "避けられるのに覆った組み合わせ " + bad.size() + " 件: " + head(bad));
    }

    /** 重なりを許した組み合わせでも、 ①②④ は必ず満たしていること (= 逃げ道にしない)。 */
    @Test
    void unavoidableCrosshairOverlapStillSatisfiesEveryOtherRule() {
        int overlaps = 0;
        for (Case c : sweep()) {
            if (crosshairAvoidanceIsPossible(c.guiH, c.panelH)) {
                continue;
            }
            overlaps++;
            assertTrue(clearsBottomHud(c), "HUD 侵入: " + c);
            assertTrue(insideScreen(c), "画面外: " + c);
            assertTrue(titleAndSummaryVisible(c), "タイトル/サマリ不可視: " + c);
            assertEquals(ContainerPeekFit.Placement.CENTERED,
                    ContainerPeekFit.placeY(c.guiH, c.panelH, c.guiH / 2),
                    "重なる場合は安全帯中央に置くこと: " + c);
        }
        // 実際に発生する組み合わせがあること (= この分岐が死んでいないことの確認)。
        assertTrue(overlaps > 0, "重なりが起きる組み合わせが 1 つも無い = 判定式が誤っている疑い");
    }

    /**
     * 掃き出しの全域で {@link ContainerPeekFit.Placement#CLAMPED_TOP} に落ちないこと。
     * = MC が許すどのウィンドウでもグリッドが下端で切れることはない。
     */
    @Test
    void neverFallsBackToTopClampInTheSupportedMatrix() {
        List<String> bad = new ArrayList<>();
        for (Case c : sweep()) {
            if (ContainerPeekFit.placeY(c.guiH, c.panelH, c.guiH / 2)
                    == ContainerPeekFit.Placement.CLAMPED_TOP) {
                bad.add(c.toString());
            }
        }
        assertTrue(bad.isEmpty(), "上端クランプに落ちた組み合わせ " + bad.size() + " 件: " + head(bad));
    }

    // ════════════════════════════════════════════════════════════════════
    // ★ 修正前の実装が同じ判定式で落ちること (= テストが症状を捉えている証明)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 修正前の {@code popupY} をそのまま写したもの。 下端の基準が
     * 「画面下端 − マージン」 でしかなく、 下部 HUD を知らない。
     */
    private static int legacyPopupY(int guiHeight, int panelHeight, int crosshairY) {
        int gap = ContainerPeekFit.CROSSHAIR_GAP;
        int margin = ContainerPeekFit.SCREEN_MARGIN;
        int below = crosshairY + gap;
        if (below + panelHeight + margin <= guiHeight) {
            return below;
        }
        int above = crosshairY - gap - panelHeight;
        if (above >= margin) {
            return above;
        }
        int max = guiHeight - margin - panelHeight;
        if (max < margin) {
            return margin;
        }
        return Math.min(Math.max(below, margin), max);
    }

    /**
     * スロット数ごとの「修正前に HUD へ侵入した組み合わせ数 / 180」を返す。
     * 添字は {@link #SLOT_COUNTS} と同じ並び。
     */
    private static int[] legacyIntrusionsPerSlotCount() {
        int[] bad = new int[SLOT_COUNTS.length];
        for (int[] res : RESOLUTIONS) {
            for (int setting : GUI_SCALES) {
                int scale = calculateScale(setting, res[0], res[1], false);
                int guiH = logical(res[1], scale);
                for (int s = 0; s < SLOT_COUNTS.length; s++) {
                    int panelH = panelHeightFor(SLOT_COUNTS[s]);
                    int y = legacyPopupY(guiH, panelH, guiH / 2);
                    if (y + panelH > guiH - ContainerPeekFit.BOTTOM_HUD_HEIGHT) {
                        bad[s]++;
                    }
                }
            }
        }
        return bad;
    }

    @Test
    void legacyImplementationIntrudesIntoTheHotbarAndTheFixRemovesIt() {
        int[] legacy = legacyIntrusionsPerSlotCount();
        int perSlotTotal = RESOLUTIONS.length * GUI_SCALES.length;   // = 180

        StringBuilder breakdown = new StringBuilder();
        for (int s = 0; s < SLOT_COUNTS.length; s++) {
            breakdown.append(SLOT_COUNTS[s] < 0 ? "notice" : String.valueOf(SLOT_COUNTS[s]))
                    .append('=').append(legacy[s]).append('/').append(perSlotTotal).append(' ');
        }

        // 症状の再現: ラージチェスト (= user が実機で見たケース) が最も高い率で落ちる。
        int idx54 = SLOT_COUNTS.length - 1;
        assertEquals(54, SLOT_COUNTS[idx54]);
        assertTrue(legacy[idx54] > 0,
                "修正前の実装が 54 スロットで HUD に侵入しない = テストが症状を捉えていない ["
                        + breakdown + "]");
        for (int s = 0; s < idx54; s++) {
            assertTrue(legacy[idx54] >= legacy[s],
                    "54 スロットの侵入率が最大でないのはおかしい [" + breakdown + "]");
        }
        // 修正前は全体でも相当数落ちていたことを固定する
        // (= 症状は 54 だけの問題ではなく、 縦の狭い画面では 27 や未登録でも起きていた)。
        int legacyTotal = 0;
        for (int n : legacy) {
            legacyTotal += n;
        }
        assertTrue(legacyTotal >= 400,
                "修正前の総侵入数が想定より少ない [" + breakdown + " total=" + legacyTotal + "]");

        // 修正後は同じ判定式で 0 件。
        int fixedBad = 0;
        for (Case c : sweep()) {
            if (!clearsBottomHud(c)) {
                fixedBad++;
            }
        }
        assertEquals(0, fixedBad,
                "修正後は 0 件でなければならない (修正前 [" + breakdown + "] total=" + legacyTotal + ")");
    }

    // ════════════════════════════════════════════════════════════════════
    // 個別の境界 (= 上の掃き出しでは見えにくい代表点)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void reportedSymptomCaseIsFixed() {
        // 1920x1080 / GUI スケール 3 → 論理 640x360。 54 スロット (154px)。
        int guiH = 360;
        int panelH = panelHeight(9, 54);
        assertEquals(154, panelH, "54 スロットのパネル高");
        // 修正前: 180+12 = 192、 下端 346 > ホットバー上端 338 → 侵入していた。
        assertTrue(legacyPopupY(guiH, panelH, guiH / 2) + panelH
                > guiH - ContainerPeekFit.HOTBAR_TOP_OFFSET, "修正前は侵入していたはず");
        // 修正後: 上へ反転して 180-12-154 = 14。
        assertEquals(ContainerPeekFit.Placement.ABOVE,
                ContainerPeekFit.placeY(guiH, panelH, guiH / 2));
        assertEquals(14, ContainerPeekFit.popupY(guiH, panelH, guiH / 2));
    }

    @Test
    void tallScreensStillPlaceThePopupBelowTheCrosshair() {
        // 1920x1080 / GUI スケール 2 → 論理 960x540。 下に余裕があるので反転しない。
        assertEquals(ContainerPeekFit.Placement.BELOW,
                ContainerPeekFit.placeY(540, panelHeight(9, 54), 270));
        assertEquals(270 + ContainerPeekFit.CROSSHAIR_GAP,
                ContainerPeekFit.popupY(540, panelHeight(9, 54), 270));
    }

    @Test
    void smallPanelsAreUnaffectedByTheFix() {
        // 27 スロット / 未登録は従来どおりクロスヘアの下。
        for (int panelH : new int[] { noticePanelHeight(), panelHeight(9, 27) }) {
            assertEquals(ContainerPeekFit.Placement.BELOW,
                    ContainerPeekFit.placeY(360, panelH, 180), "panelH=" + panelH);
            assertEquals(192, ContainerPeekFit.popupY(360, panelH, 180), "panelH=" + panelH);
        }
    }

    @Test
    void bottomHudHeightMatchesTheVanillaMeasurements() {
        // 出典が動いていないことの自己点検 (= 定数を書き換えたら気付ける)。
        assertEquals(22, ContainerPeekFit.HOTBAR_TOP_OFFSET);
        assertEquals(29, ContainerPeekFit.CONTEXTUAL_BAR_TOP_OFFSET);
        assertEquals(39, ContainerPeekFit.STATUS_BAR_TOP_OFFSET);
        assertEquals(49, ContainerPeekFit.ARMOR_BAR_TOP_OFFSET);
        assertEquals(59, ContainerPeekFit.HELD_ITEM_NAME_TOP_OFFSET);
        // 予約帯は最も高い要素まで含む。
        assertEquals(59, ContainerPeekFit.BOTTOM_HUD_HEIGHT);
        assertTrue(ContainerPeekFit.BOTTOM_HUD_HEIGHT >= ContainerPeekFit.ARMOR_BAR_TOP_OFFSET);
        // クロスヘア回避の下駄は、 クロスヘア半径より大きいこと。
        assertTrue(ContainerPeekFit.CROSSHAIR_GAP > ContainerPeekFit.CROSSHAIR_HALF);
    }

    private static String head(List<String> list) {
        return list.size() <= 3 ? String.join(" | ", list)
                : String.join(" | ", list.subList(0, 3)) + " …";
    }
}
