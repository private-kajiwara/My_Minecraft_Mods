package com.kajiwara.omnichest.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * カーソル追従ポップアップ (= ALT シュルカープレビュー / 倉庫検索の sticky preview /
 * テンプレート管理のプレビュー) の<b>配置</b>の回帰テスト。
 *
 * <p>
 * <b>捉えている不具合</b>: 旧実装 ({@code AdaptiveTooltipPositioner} の
 * {@code y = mouseY + CURSOR_OFFSET(12)}) はポップアップをカーソルの<b>右下</b>へ置いていた。
 * user 実機で 「カーソルの真横に出したい」 という指摘があり、 縦をカーソル中心へ揃える方式に変えた。
 * 同時に、 縦のオフセットが無くなると非交差を担保するのが水平間隔だけになるため、 間隔を
 * 12 → {@link CursorPopupFit#CURSOR_GAP}(18 = スロット 1 マス) へ広げた。
 *
 * <p>
 * <b>旧実装が落ちることを本テストで固定している</b> ({@link #legacyImplementationFailsBothConditions}):
 * 縦中心一致は {@code 0 / 1620} で全滅、 ホバー中スロットとの非交差は {@code 4860 / 4860} で全滅。
 * つまりテストは症状そのものを捉えている。
 *
 * <p>
 * <b>★パネル寸法のミラーについて</b>: パネルの幅・高さの式は MC 側の
 * {@code AltPreviewPopupRenderer.panelWidth/panelHeight} と {@code PopupThemeResolver} が持っており、
 * {@code common} からは参照できないためここに<b>写して</b>いる
 * ({@code ContainerPeekPlacementTest} / {@code SidePanelFitTest} と同流儀)。
 * <b>{@code PopupThemeResolver} の寸法定数を変えたらここも更新すること。</b>
 *
 * <p>
 * <b>★除外している条件 (今回のスコープ外)</b>: 54 スロット (= テンプレート管理の大型テンプレート)
 * ではパネル高が 244px になり、 MC が保証する論理画面高 240px を超えるため<b>どう置いても
 * 画面外にはみ出す</b>。 これは配置の問題ではなくパネル寸法の問題 (縮小表示 / スクロール /
 * コンパクト化のいずれかが必要) なので本タスクでは扱わず、 掃き出しの軸からも外してある
 * (ALT プレビューは常に 27 スロット固定なので、 この経路では発生しない = 最大高 154px)。
 */
class CursorPopupFitTest {

    // ════════════════════════════════════════════════════════════════════
    // 掃き出しの軸
    // ════════════════════════════════════════════════════════════════════

    /** 代表解像度 20 種 ({@code ContainerPeekPlacementTest} / {@code ExistingCategoriesFitTest} と同一)。 */
    private static final int[][] RESOLUTIONS = {
            { 640, 480 }, { 854, 480 }, { 1024, 768 }, { 1280, 720 }, { 1280, 800 },
            { 1280, 1024 }, { 1366, 768 }, { 1440, 900 }, { 1600, 900 }, { 1600, 1200 },
            { 1680, 1050 }, { 1920, 1080 }, { 1920, 1200 }, { 2048, 1536 }, { 2560, 1080 },
            { 2560, 1440 }, { 2560, 1600 }, { 3440, 1440 }, { 3835, 2076 }, { 3840, 2160 },
    };

    /** GUI スケール設定。 {@code 0} = Auto。 */
    private static final int[] GUI_SCALES = { 1, 2, 3, 4, 5, 6, 7, 8, 0 };

    /**
     * グリッド列数。 {@code PopupThemeResolver.MIN_COLUMNS}(5) / 既定(9) /
     * {@code MAX_COLUMNS}(11) の 3 種。 ALT プレビューのスロット数は常に
     * {@code RecursiveContainerHelper.DEFAULT_CONTAINER_SLOTS} = 27。
     */
    private static final int[] COLUMNS = { 5, 9, 11 };

    /** ALT プレビューのスロット数 (固定)。 */
    private static final int SLOT_COUNT = 27;

    /** 総ケース数 (= 20 解像度 x 9 スケール x 3 パネル x 9 カーソル点)。 */
    private static final int TOTAL = 4860;

    /** 縦中心揃えが成立する (= クランプが働かない) ケース数。 */
    private static final int CENTERABLE = 1620;

    /** カーソルの左右どちらにもパネルが入らない極小論理画面のケース数 (= ③ の除外対象)。 */
    private static final int NOT_FITTING_BESIDE = 351;

    /** 右端で左反転が要求されるケース数。 */
    private static final int FLIP_CASES = 1620;

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

    private static final int PANEL_BASE =
            PANEL_PADDING * 2 + TITLE_HEIGHT + (SEPARATOR_GAP + 1 + SEPARATOR_GAP) * 2 + SUMMARY_HEIGHT;

    private static int panelWidth(int columns) {
        return PANEL_PADDING * 2 + columns * CELL;
    }

    private static int panelHeight(int columns, int slotCount) {
        int rows = Math.max(1, (slotCount + columns - 1) / columns);
        return PANEL_BASE + rows * CELL;
    }

    // ── 旧実装のミラー (= 修正前の AdaptiveTooltipPositioner.place / LTR) ──
    private static final int LEGACY_OFFSET = 12;

    private static int[] legacyPlace(int mouseX, int mouseY, int w, int h, int screenW, int screenH) {
        int x = mouseX + LEGACY_OFFSET;
        if (x + w > screenW - CursorPopupFit.SCREEN_MARGIN) {
            x = mouseX - LEGACY_OFFSET - w;
        }
        int y = mouseY + LEGACY_OFFSET;
        if (y + h > screenH - CursorPopupFit.SCREEN_MARGIN) {
            y = screenH - CursorPopupFit.SCREEN_MARGIN - h;
        }
        return new int[] {
                Math.max(x, CursorPopupFit.SCREEN_MARGIN),
                Math.max(y, CursorPopupFit.SCREEN_MARGIN),
        };
    }

    // ── 判定ヘルパ ──

    private static boolean intersects(int ax, int ay, int aw, int ah,
            int bx, int by, int bw, int bh) {
        return !(ax >= bx + bw || ax + aw <= bx || ay >= by + bh || ay + ah <= by);
    }

    /** カーソル スプライトの矩形 (最悪ケース = GUI スケール 1 で 16x16)。 */
    private static boolean hitsCursor(int x, int y, int w, int h, int cx, int cy) {
        return intersects(x, y, w, h, cx, cy,
                CursorPopupFit.CURSOR_SPRITE, CursorPopupFit.CURSOR_SPRITE);
    }

    /**
     * ホバー中スロットが取りうる矩形の<b>和</b>。 カーソルはスロット内のどこにでも置けるので、
     * スロットは最悪 {@code cx - 17 .. cx + 17} (= 一辺 {@code 2 * SLOT_SIZE - 1}) まで伸びる。
     * この保守的な矩形と交差しなければ、 実際のスロット位置がどこであっても被らない。
     */
    private static boolean hitsHoveredSlot(int x, int y, int w, int h, int cx, int cy) {
        int s = CursorPopupFit.SLOT_SIZE;
        return intersects(x, y, w, h, cx - s + 1, cy - s + 1, s * 2 - 1, s * 2 - 1);
    }

    /** 掃き出しの 1 ケース。 */
    private record Case(int screenW, int screenH, int panelW, int panelH, int cursorX, int cursorY) {
    }

    /** 全ケースを列挙する (カーソルは画面四隅・各辺中央・中心の 9 点)。 */
    private static java.util.List<Case> allCases() {
        java.util.List<Case> out = new java.util.ArrayList<>(TOTAL);
        for (int[] res : RESOLUTIONS) {
            for (int gs : GUI_SCALES) {
                int scale = calculateScale(gs, res[0], res[1], false);
                int sw = logical(res[0], scale);
                int sh = logical(res[1], scale);
                for (int cols : COLUMNS) {
                    int w = panelWidth(cols);
                    int h = panelHeight(cols, SLOT_COUNT);
                    int[] ys = { CursorPopupFit.SCREEN_MARGIN, sh / 2, sh - 1 - CursorPopupFit.SCREEN_MARGIN };
                    int[] xs = { CursorPopupFit.SCREEN_MARGIN, sw / 2, sw - 1 - CursorPopupFit.SCREEN_MARGIN };
                    for (int cy : ys) {
                        for (int cx : xs) {
                            out.add(new Case(sw, sh, w, h, cx, cy));
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void sweepCoversTheDeclaredMatrix() {
        assertEquals(TOTAL, allCases().size(), "掃き出しの軸を変えたら TOTAL も更新すること");
    }

    // ════════════════════════════════════════════════════════════════════
    // ① 画面外に出ない / ② 縦中心一致 / ③ 非交差 / ④ 右端で左反転
    // ════════════════════════════════════════════════════════════════════

    @Test
    void sweepNeverLeavesTheScreen() {
        for (Case c : allCases()) {
            int x = CursorPopupFit.placeX(c.cursorX(), c.panelW(), c.screenW(), false);
            int y = CursorPopupFit.placeY(c.cursorY(), c.panelH(), c.screenH());
            String at = describe(c, x, y);
            assertTrue(x >= 0 && x + c.panelW() <= c.screenW(), "横がはみ出した: " + at);
            assertTrue(y >= 0 && y + c.panelH() <= c.screenH(), "縦がはみ出した: " + at);
        }
    }

    /**
     * ★本タスクの本題。 {@link CursorPopupFit#canCenterVertically} が true のケースでは
     * パネルの縦中心が必ずカーソル Y と一致する (= 「カーソルの真横」)。
     * false のケース (= カーソルが上端 / 下端寄りで中心揃えが画面外へ出る) だけがクランプされる。
     */
    @Test
    void sweepCentersVerticallyWheneverPossible() {
        int centerable = 0;
        for (Case c : allCases()) {
            int y = CursorPopupFit.placeY(c.cursorY(), c.panelH(), c.screenH());
            if (!CursorPopupFit.canCenterVertically(c.cursorY(), c.panelH(), c.screenH())) {
                // クランプされる: 上下端のどちらかに張り付いているはず。
                assertTrue(y == CursorPopupFit.SCREEN_MARGIN
                        || y == c.screenH() - CursorPopupFit.SCREEN_MARGIN - c.panelH(),
                        "クランプ時は画面端に張り付くはず: " + describe(c, 0, y));
                continue;
            }
            centerable++;
            assertEquals(c.cursorY(), y + c.panelH() / 2,
                    "縦中心がカーソル Y と一致しない: " + describe(c, 0, y));
        }
        assertEquals(CENTERABLE, centerable, "中心揃えできるケース数が変わった");
    }

    /**
     * ★{@link CursorPopupFit#fitsBesideCursor} が true のケースでは、 パネルは
     * カーソル スプライトともホバー中スロットとも <b>1px も交差しない</b>。
     * false のケース (= パネル幅がカーソルの左右どちらの余白よりも広い極小論理画面) は
     * 幾何学的に非交差が不可能なので除外する。
     */
    @Test
    void sweepNeverOverlapsCursorOrHoveredSlotWhenItFitsBeside() {
        int notFitting = 0;
        for (Case c : allCases()) {
            int x = CursorPopupFit.placeX(c.cursorX(), c.panelW(), c.screenW(), false);
            int y = CursorPopupFit.placeY(c.cursorY(), c.panelH(), c.screenH());
            if (!CursorPopupFit.fitsBesideCursor(c.cursorX(), c.panelW(), c.screenW())) {
                notFitting++;
                continue;
            }
            String at = describe(c, x, y);
            assertFalse(hitsCursor(x, y, c.panelW(), c.panelH(), c.cursorX(), c.cursorY()),
                    "カーソルに被った: " + at);
            assertFalse(hitsHoveredSlot(x, y, c.panelW(), c.panelH(), c.cursorX(), c.cursorY()),
                    "ホバー中スロットに被った: " + at);
        }
        assertEquals(NOT_FITTING_BESIDE, notFitting, "除外対象 (左右に入らない) のケース数が変わった");
    }

    /** ④ 右側に入らないなら必ずカーソルの左へ反転する (= 右へはみ出させない)。 */
    @Test
    void sweepFlipsToTheLeftAtTheRightEdge() {
        int flips = 0;
        for (Case c : allCases()) {
            boolean rightFits = c.cursorX() + CursorPopupFit.CURSOR_GAP + c.panelW()
                    <= c.screenW() - CursorPopupFit.SCREEN_MARGIN;
            boolean leftFits = c.cursorX() - CursorPopupFit.CURSOR_GAP - c.panelW()
                    >= CursorPopupFit.SCREEN_MARGIN;
            if (rightFits || !leftFits) {
                continue;
            }
            flips++;
            int x = CursorPopupFit.placeX(c.cursorX(), c.panelW(), c.screenW(), false);
            assertTrue(x + c.panelW() <= c.cursorX(),
                    "右に入らないのに左へ反転しなかった: " + describe(c, x, 0));
        }
        assertEquals(FLIP_CASES, flips, "左反転が要求されるケース数が変わった");
    }

    // ════════════════════════════════════════════════════════════════════
    // ★旧実装が落ちること (= テストが症状を捉えている証明)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void legacyImplementationFailsBothConditions() {
        int legacyCentered = 0;
        int legacySlotOverlap = 0;
        int legacyCursorOverlap = 0;
        for (Case c : allCases()) {
            int[] xy = legacyPlace(c.cursorX(), c.cursorY(), c.panelW(), c.panelH(),
                    c.screenW(), c.screenH());
            if (CursorPopupFit.canCenterVertically(c.cursorY(), c.panelH(), c.screenH())
                    && xy[1] + c.panelH() / 2 == c.cursorY()) {
                legacyCentered++;
            }
            if (hitsHoveredSlot(xy[0], xy[1], c.panelW(), c.panelH(), c.cursorX(), c.cursorY())) {
                legacySlotOverlap++;
            }
            if (hitsCursor(xy[0], xy[1], c.panelW(), c.panelH(), c.cursorX(), c.cursorY())) {
                legacyCursorOverlap++;
            }
        }
        // ② 旧実装は y = mouseY + 12 なので、 縦中心が一致することは構造的にあり得ない。
        assertEquals(0, legacyCentered, "旧実装で縦中心が一致してしまった (前提が崩れている)");
        // ③ 旧実装は間隔 12 < スロット 18 なので、 ホバー中スロットには必ず被る。
        assertEquals(TOTAL, legacySlotOverlap, "旧実装のスロット重複が全件でなくなった");
        // カーソル スプライト (16px) には、 左反転しない 2/3 のケースで被る。
        assertEquals(3240, legacyCursorOverlap, "旧実装のカーソル重複件数が変わった");
    }

    /**
     * ★{@link CursorPopupFit#CURSOR_GAP} が 18 でなければならない理由を将来も固定する。
     *
     * <p>
     * 16 未満ではカーソル スプライト (16px) に、 18 未満ではホバー中スロット (18px) に被る。
     * 18 が両方を満たす<b>最小</b>値であり、 これは {@code PopupThemeResolver.CELL} と同値である。
     */
    @Test
    void gapMustBeAtLeastOneSlotWide() {
        assertEquals(CursorPopupFit.SLOT_SIZE, CursorPopupFit.CURSOR_GAP,
                "間隔はスロット 1 マス (= PopupThemeResolver.CELL) と同値であること");
        for (int gap = 10; gap <= 20; gap += 2) {
            int cursorHits = 0;
            int slotHits = 0;
            for (Case c : allCases()) {
                if (!fitsBesideWithGap(c.cursorX(), c.panelW(), c.screenW(), gap)) {
                    continue;
                }
                int x = placeXWithGap(c.cursorX(), c.panelW(), c.screenW(), gap);
                int y = CursorPopupFit.placeY(c.cursorY(), c.panelH(), c.screenH());
                if (hitsCursor(x, y, c.panelW(), c.panelH(), c.cursorX(), c.cursorY())) {
                    cursorHits++;
                }
                if (hitsHoveredSlot(x, y, c.panelW(), c.panelH(), c.cursorX(), c.cursorY())) {
                    slotHits++;
                }
            }
            if (gap < CursorPopupFit.CURSOR_SPRITE) {
                assertTrue(cursorHits > 0, "gap=" + gap + " ではカーソルに被るはず");
            }
            if (gap < CursorPopupFit.SLOT_SIZE) {
                assertTrue(slotHits > 0, "gap=" + gap + " ではホバー中スロットに被るはず");
            } else {
                assertEquals(0, cursorHits, "gap=" + gap + " でカーソルに被った");
                assertEquals(0, slotHits, "gap=" + gap + " でスロットに被った");
            }
        }
    }

    private static int placeXWithGap(int cursorX, int panelW, int screenW, int gap) {
        int x = cursorX + gap;
        if (x + panelW > screenW - CursorPopupFit.SCREEN_MARGIN) {
            x = cursorX - gap - panelW;
        }
        return Math.max(x, CursorPopupFit.SCREEN_MARGIN);
    }

    private static boolean fitsBesideWithGap(int cursorX, int panelW, int screenW, int gap) {
        return cursorX + gap + panelW <= screenW - CursorPopupFit.SCREEN_MARGIN
                || cursorX - gap - panelW >= CursorPopupFit.SCREEN_MARGIN;
    }

    // ════════════════════════════════════════════════════════════════════
    // 単体 (= 掃き出しでは見えにくい個別規則)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void ltrPrefersTheRightSide() {
        assertEquals(100 + CursorPopupFit.CURSOR_GAP,
                CursorPopupFit.placeX(100, 174, 640, false));
    }

    @Test
    void rtlIsMirrored() {
        // RTL は左優先。 十分な余白があれば必ずカーソルの左へ出る。
        assertEquals(300 - CursorPopupFit.CURSOR_GAP - 174,
                CursorPopupFit.placeX(300, 174, 640, true));
        // 左に入らなければ右へ折り返す (= LTR の鏡像)。
        assertEquals(20 + CursorPopupFit.CURSOR_GAP,
                CursorPopupFit.placeX(20, 174, 640, true));
    }

    @Test
    void placeReturnsTheSameValuesAsTheAxisMethods() {
        int[] xy = CursorPopupFit.place(123, 200, 174, 100, 640, 480, false);
        assertEquals(CursorPopupFit.placeX(123, 174, 640, false), xy[0]);
        assertEquals(CursorPopupFit.placeY(200, 100, 480), xy[1]);
    }

    /**
     * パネルが画面より高い場合は上端 (= マージン) を優先する。
     * これは 54 スロットのテンプレートプレビューでのみ起きる状況で、 本タスクのスコープ外
     * (クラス javadoc の 「除外している条件」 参照)。 挙動が変わらないことだけ固定する。
     */
    @Test
    void panelTallerThanTheScreenAnchorsToTheTop() {
        int tall = 244;
        int screenH = 240;
        assertFalse(CursorPopupFit.canCenterVertically(120, tall, screenH));
        assertEquals(CursorPopupFit.SCREEN_MARGIN, CursorPopupFit.placeY(120, tall, screenH));
    }

    private static String describe(Case c, int x, int y) {
        return "screen=" + c.screenW() + "x" + c.screenH()
                + " panel=" + c.panelW() + "x" + c.panelH()
                + " cursor=(" + c.cursorX() + "," + c.cursorY() + ")"
                + " -> (" + x + "," + y + ")";
    }
}
