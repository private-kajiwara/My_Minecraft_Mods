package com.kajiwara.omnichest.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SidePanelFit} の単体テスト (= サイドパネルのラベル見切れ 回帰テスト)。
 *
 * <p>
 * <b>不具合</b>: パネル幅が定数 146px 固定で、 2 列セルが 72px → 実際に文字を描ける幅は
 * {@code 72 - 2*TEXT_MARGIN = 68px}。 en_us の "Sort by Category" は 88px あるため、
 * バニラのスクロール表示に落ちて<b>左右とも見切れて</b>いた。
 *
 * <p>
 * <b>{@link #LABEL_WIDTHS} について</b>: MC 26.1.2 の実フォント資産
 * ({@code ascii.png} / {@code accented.png} / {@code nonlatin_european.png} /
 * {@code include/space.json} / {@code unifont(_jp).zip}) を読み、 vanilla の advance 算出式
 * (bitmap: {@code (int)(0.5 + 実ピクセル幅 * scale) + 1} / unihex: {@code (right-left+1)/2 + 1})
 * をそのまま適用して得た <b>実測値</b>。 既知の advance ({@code A}=6 / {@code I}=4 / {@code i}=2 /
 * {@code l}=3 / {@code t}=4 / {@code f}=5 / 空白=4) と一致することを確認済み。
 * lang を編集したらこの表も更新すること (ズレても「収まるはずが収まらない」 側には倒れない:
 * 実行時は {@code Font#width} で測り直すため、 この表はあくまで<b>現行 lang の回帰検出用</b>)。
 */
class SidePanelFitTest {

    /** {@code GenericContainerScreenMixin} と同じ値。 */
    private static final int GAP = 2;
    private static final int MIN_W = 146;
    private static final int MAX_W = 200;

    /**
     * ロケールごとのラベル実測幅。
     * 並びは {@code search_network, category_sort, deposit, compact,
     * sort_by_type, sort_by_count, save_template, manage_templates,
     * auto_distribute, set_category}。
     */
    private static final Object[][] LABEL_WIDTHS = {
            { "ar_sa", 42, 20, 43, 12, 12, 12, 41, 40, 45, 40 },
            { "cs_cz", 71, 27, 65, 33, 18, 28, 62, 95, 65, 87 },
            { "da_dk", 62, 34, 67, 31, 24, 25, 53, 100, 59, 70 },
            { "de_de", 72, 48, 47, 37, 16, 20, 88, 98, 73, 86 },
            { "en_gb", 68, 88, 36, 40, 24, 28, 62, 89, 74, 66 },
            { "en_us", 68, 88, 36, 40, 24, 28, 62, 89, 74, 66 },
            { "es_es", 75, 42, 87, 29, 20, 24, 78, 45, 72, 91 },
            { "fi_fi", 56, 43, 66, 34, 32, 30, 87, 76, 44, 63 },
            { "fr_fr", 98, 26, 71, 35, 24, 16, 128, 92, 68, 85 },
            { "he_il", 55, 67, 30, 54, 13, 20, 56, 50, 66, 63 },
            { "hi_in", 60, 58, 107, 54, 42, 36, 77, 108, 78, 94 },
            { "hu_hu", 65, 36, 94, 49, 27, 12, 101, 75, 69, 101 },
            { "id_id", 72, 39, 72, 51, 26, 15, 84, 69, 90, 67 },
            { "it_it", 72, 32, 75, 44, 20, 30, 62, 74, 80, 90 },
            { "ja_jp", 36, 54, 36, 54, 18, 18, 45, 54, 54, 54 },
            { "ko_kr", 36, 52, 36, 36, 16, 16, 36, 44, 36, 36 },
            { "ms_my", 54, 30, 72, 34, 26, 20, 92, 63, 46, 90 },
            { "nb_no", 41, 34, 62, 31, 24, 28, 65, 76, 59, 66 },
            { "nl_nl", 53, 46, 86, 66, 24, 24, 77, 97, 71, 94 },
            { "pl_pl", 94, 34, 84, 35, 18, 25, 64, 111, 67, 79 },
            { "pt_br", 74, 42, 80, 41, 20, 18, 68, 93, 72, 85 },
            { "ro_ro", 79, 46, 81, 56, 14, 24, 92, 109, 72, 92 },
            { "ru_ru", 81, 66, 107, 69, 18, 20, 111, 118, 83, 95 },
            { "sv_se", 55, 40, 74, 31, 18, 25, 65, 74, 65, 69 },
            { "th_th", 29, 28, 61, 9, 15, 19, 34, 39, 57, 41 },
            { "tr_tr", 65, 29, 87, 84, 18, 22, 69, 82, 67, 78 },
            { "uk_ua", 68, 54, 89, 48, 18, 27, 80, 112, 75, 91 },
            { "vi_vn", 53, 40, 69, 18, 20, 12, 58, 59, 93, 66 },
            { "zh_cn", 36, 36, 36, 36, 18, 18, 36, 36, 36, 36 },
            { "zh_tw", 36, 36, 36, 36, 18, 18, 36, 36, 36, 36 },
    };

    // ── 不具合そのものの再現と是正 ────────────────────────────────

    @Test
    void oldFixedLayout_clipsEnglishCategorySort() {
        // 旧: パネル 146 固定 → セル 72 → 使える文字幅 68 に対し "Sort by Category" は 88px。
        int oldCell = SidePanelFit.cellWidth(MIN_W, GAP);
        assertEquals(72, oldCell);
        assertEquals(68, SidePanelFit.usableTextWidth(oldCell));
        assertFalse(SidePanelFit.fits(88, oldCell), "旧レイアウトでは見切れていたはず");
    }

    @Test
    void englishNowFitsByWideningPanel() {
        // en_us: maxGrid=88, maxFull=89 → 2*(88+4)+2 = 186 (<= MAX 200) なので拡幅だけで収まる。
        int panel = SidePanelFit.panelWidth(88, 89, GAP, MIN_W, MAX_W);
        assertEquals(186, panel);
        assertTrue(SidePanelFit.fits(88, SidePanelFit.cellWidth(panel, GAP)));
        assertFalse(SidePanelFit.shouldStackRow(68, 88, panel, GAP), "英語は 2 列のままで収まる");
    }

    @Test
    void shortLabelsKeepLegacyWidthExactly() {
        // zh_cn / ja_jp のように元々収まるロケールは下限のまま = 従来とピクセル同一。
        assertEquals(MIN_W, SidePanelFit.panelWidth(36, 36, GAP, MIN_W, MAX_W));
        assertEquals(MIN_W, SidePanelFit.panelWidth(54, 54, GAP, MIN_W, MAX_W));
    }

    // ── 全ロケール × 実測ラベル幅で「はみ出しゼロ」 ────────────────

    @Test
    void everyLocaleFitsWithNoOverflow() {
        for (Object[] row : LABEL_WIDTHS) {
            String locale = (String) row[0];
            int[] w = new int[10];
            for (int i = 0; i < 10; i++) {
                w[i] = (Integer) row[i + 1];
            }
            int maxGrid = 0;
            for (int i = 0; i < 6; i++) {         // grid 4 + sort 2 が 2 列セルに入る
                maxGrid = Math.max(maxGrid, w[i]);
            }
            int maxFull = 0;
            for (int i = 6; i < 10; i++) {
                maxFull = Math.max(maxFull, w[i]);
            }
            int panel = SidePanelFit.panelWidth(maxGrid, maxFull, GAP, MIN_W, MAX_W);
            assertTrue(panel >= MIN_W && panel <= MAX_W, locale + ": パネル幅が範囲外 " + panel);

            int cell = SidePanelFit.cellWidth(panel, GAP);

            // 2 列グリッドの行: [search|category] と [deposit|compact]。
            assertRowFits(locale, "grid0", w[0], w[1], panel, cell);
            assertRowFits(locale, "grid1", w[2], w[3], panel, cell);
            // 種類 | 数量 の行 (常に 2 列)。
            assertTrue(SidePanelFit.fits(Math.max(w[4], w[5]), cell),
                    locale + ": 種類/数量 が 2 列セルに収まらない");

            // 全幅行。
            for (int i = 6; i < 10; i++) {
                assertTrue(SidePanelFit.fits(w[i], panel),
                        locale + ": 全幅行のラベルが収まらない (w=" + w[i] + ", panel=" + panel + ")");
            }
        }
    }

    /** 行が 2 列で収まる、 または縦積みへ落として全幅で収まることを確認する。 */
    private static void assertRowFits(String locale, String row, int left, int right,
            int panel, int cell) {
        if (SidePanelFit.shouldStackRow(left, right, panel, GAP)) {
            assertTrue(SidePanelFit.fits(Math.max(left, right), panel),
                    locale + "/" + row + ": 縦積みにしても収まらない (要 切り詰め)");
        } else {
            assertTrue(SidePanelFit.fits(Math.max(left, right), cell),
                    locale + "/" + row + ": 2 列のままなのに収まっていない");
        }
    }

    @Test
    void onlyExpectedLocalesNeedStacking() {
        // 実測上、 縦積みへ落ちるのは fr_fr / hi_in / ru_ru の各 1 行のみ。
        StringBuilder stacked = new StringBuilder();
        for (Object[] row : LABEL_WIDTHS) {
            String locale = (String) row[0];
            int[] w = new int[10];
            for (int i = 0; i < 10; i++) {
                w[i] = (Integer) row[i + 1];
            }
            int maxGrid = 0;
            for (int i = 0; i < 6; i++) {
                maxGrid = Math.max(maxGrid, w[i]);
            }
            int maxFull = 0;
            for (int i = 6; i < 10; i++) {
                maxFull = Math.max(maxFull, w[i]);
            }
            int panel = SidePanelFit.panelWidth(maxGrid, maxFull, GAP, MIN_W, MAX_W);
            if (SidePanelFit.shouldStackRow(w[0], w[1], panel, GAP)
                    || SidePanelFit.shouldStackRow(w[2], w[3], panel, GAP)) {
                stacked.append(locale).append(' ');
            }
        }
        assertEquals("fr_fr hi_in ru_ru ", stacked.toString());
    }

    // ── 不変条件 (合成値・極端な入力でも破綻しない) ──────────────

    @Test
    void panelWidthNeverBelowMinNorAboveMax() {
        for (int grid = 0; grid <= 400; grid += 7) {
            for (int full = 0; full <= 400; full += 13) {
                int panel = SidePanelFit.panelWidth(grid, full, GAP, MIN_W, MAX_W);
                assertTrue(panel >= MIN_W, "下限割れ " + panel);
                assertTrue(panel <= MAX_W, "上限超え " + panel);
            }
        }
    }

    @Test
    void stackedRowIsAlwaysWiderThanTwoColumnCell() {
        // 縦積みは必ず「収まる可能性が上がる」 方向でなければ意味が無い。
        for (int panel = MIN_W; panel <= MAX_W; panel++) {
            assertTrue(panel > SidePanelFit.cellWidth(panel, GAP),
                    "全幅がセル幅以下になっている panel=" + panel);
        }
    }

    @Test
    void fitsIsConsistentWithMinButtonWidth() {
        for (int label = 0; label <= 300; label++) {
            int need = SidePanelFit.minButtonWidth(label);
            assertTrue(SidePanelFit.fits(label, need), "ちょうどの幅で収まらない label=" + label);
            if (need > 0) {
                assertFalse(SidePanelFit.fits(label, need - 1), "1px 足りないのに収まっている label=" + label);
            }
        }
    }

    // ── 幅解決の決定順・冪等性・重なり不変条件 ────────────────────

    /** {@code GenericContainerScreenMixin} の定数と同じ値 (重なり判定の再現用)。 */
    private static final int PANEL_GAP = 8;
    private static final int PANEL_MARGIN = 3;
    private static final int SHADOW = 2;
    private static final int EDGE_PAD = 2;

    private static int roomFor(int screenW, int leftPos, int imageWidth) {
        return screenW - (leftPos + imageWidth) - PANEL_GAP - PANEL_MARGIN - SHADOW - EDGE_PAD;
    }

    @Test
    void clampToRoomIsIdempotent() {
        // 単一パスで確定させるため、 2 回目以降の適用が結果を動かさないこと。
        for (int desired = MIN_W; desired <= MAX_W; desired++) {
            for (int room = -50; room <= 260; room += 3) {
                int once = SidePanelFit.clampToRoom(desired, room, MIN_W);
                int twice = SidePanelFit.clampToRoom(once, room, MIN_W);
                assertEquals(once, twice, "desired=" + desired + " room=" + room);
                assertTrue(once >= MIN_W, "下限割れ");
                assertTrue(once <= Math.max(MIN_W, Math.min(desired, room)) , "広がっている");
            }
        }
    }

    @Test
    void resolvedWidthNeverPullsPanelIntoChestMoreThanBefore() {
        // maxX >= natural (= クランプ不発 = チェストへ食い込まない) が
        // 「room >= MIN のとき」 常に成り立つこと。 room < MIN のときだけ従来と同じ食い込みになる。
        final int imageWidth = 176;
        for (int screenW = 320; screenW <= 1200; screenW += 7) {
            for (int leftPos = 0; leftPos + imageWidth <= screenW; leftPos += 11) {
                int room = roomFor(screenW, leftPos, imageWidth);
                int panel = SidePanelFit.clampToRoom(MAX_W, room, MIN_W);
                int natural = leftPos + imageWidth + PANEL_GAP;
                int maxX = screenW - EDGE_PAD - SHADOW - PANEL_MARGIN - panel;
                if (room >= MIN_W) {
                    assertTrue(maxX >= natural,
                            "room>=MIN なのにパネルが左へ引かれた screenW=" + screenW
                                    + " leftPos=" + leftPos + " panel=" + panel);
                } else {
                    // 従来 (常に 146) と同一の状態に落ちること。
                    assertEquals(MIN_W, panel,
                            "余地不足時は下限へ戻るべき screenW=" + screenW + " leftPos=" + leftPos);
                }
            }
        }
    }

    @Test
    void widthDependsOnlyOnLabelsSoLayoutPassesConverge() {
        // 幅の第 1 段 (ラベル起点) は幾何に依存しない → 何度呼んでも同じ値。
        // これが「shift 量 → 余地 → 幅」 の循環を断ち、 単一パスで確定する根拠。
        int a = SidePanelFit.panelWidth(88, 89, GAP, MIN_W, MAX_W);
        int b = SidePanelFit.panelWidth(88, 89, GAP, MIN_W, MAX_W);
        assertEquals(a, b);
        // 2 回目のレイアウトでは余地が広がっている (= shift 済み) ケースでも、
        // 第 1 段が毎回リセットされるので結果は同じに落ち着く。
        int firstPass = SidePanelFit.clampToRoom(a, 150, MIN_W);   // 余地不足 → 150
        int secondPass = SidePanelFit.clampToRoom(a, 150, MIN_W);  // shift は no-op なので同じ余地
        assertEquals(firstPass, secondPass);
        assertEquals(150, firstPass);
    }

    @Test
    void hiddenButtonsDoNotInflateWidth() {
        // 非表示ボタンはラベル幅 0 として渡る (Mixin 側は null → 0)。
        // en_us で "Sort by Category"(88) を隠したら、 残り (68/36/40/24/28) だけで決まる。
        int maxGridWithout = Math.max(Math.max(68, 36), Math.max(40, Math.max(24, 28)));
        assertEquals(68, maxGridWithout);
        assertEquals(MIN_W, SidePanelFit.panelWidth(maxGridWithout, 89, GAP, MIN_W, MAX_W),
                "隠したのに拡幅を負担してはいけない");
    }

    // ── 「操作方法」 パネルが右パネル / チェスト / 画面端と重ならない ──────────

    private static final int HELP_MARGIN = 4;
    private static final int HELP_MIN_W = 60;
    private static final int CHEST_W = 176;

    /** パネルの視覚的占有域 (背景外周 + 影) を実装と同じ規則で求める。 */
    private static int panelVisualLeft(int chestLeft, boolean onRight, int panelWidth) {
        int buttonsLeft = onRight
                ? chestLeft + CHEST_W + PANEL_GAP
                : chestLeft - PANEL_GAP - panelWidth;
        return buttonsLeft - PANEL_MARGIN;
    }

    private static int panelVisualRight(int chestLeft, boolean onRight, int panelWidth) {
        int buttonsLeft = onRight
                ? chestLeft + CHEST_W + PANEL_GAP
                : chestLeft - PANEL_GAP - panelWidth;
        return buttonsLeft + panelWidth + PANEL_MARGIN + SHADOW;
    }

    @Test
    void helpPanelNeverIntersectsSidePanelChestOrScreenEdge() {
        int checked = 0;
        int drawn = 0;
        for (int screenW = 320; screenW <= 1200; screenW += 11) {
            for (int panelWidth = MIN_W; panelWidth <= MAX_W; panelWidth += 6) {
                for (int chestLeft = 0; chestLeft + CHEST_W <= screenW; chestLeft += 13) {
                    for (boolean onRight : new boolean[] { true, false }) {
                        for (int desired : new int[] { 60, 90, 140, 220 }) {
                            checked++;
                            int pl = panelVisualLeft(chestLeft, onRight, panelWidth);
                            int pr = panelVisualRight(chestLeft, onRight, panelWidth);
                            SidePanelFit.HelpPlacement p = SidePanelFit.placeHelp(
                                    screenW, chestLeft, chestLeft + CHEST_W, pl, pr, onRight,
                                    desired, HELP_MIN_W, HELP_MARGIN, EDGE_PAD);
                            if (p == null) {
                                continue; // 置けない → 描画しない (= 許容)
                            }
                            drawn++;
                            int hl = p.x();
                            int hr = p.x() + p.width();
                            String at = "screenW=" + screenW + " panelW=" + panelWidth
                                    + " chestLeft=" + chestLeft + " onRight=" + onRight
                                    + " desired=" + desired + " -> help[" + hl + "," + hr + "]";
                            assertTrue(p.width() >= HELP_MIN_W, "下限未満なのに描画 " + at);
                            assertTrue(p.width() <= desired, "希望幅より広い " + at);
                            // 画面内
                            assertTrue(hl >= EDGE_PAD, "画面左へはみ出し " + at);
                            assertTrue(hr <= screenW - EDGE_PAD, "画面右へはみ出し " + at);
                            // チェスト本体と非交差
                            assertFalse(hl < chestLeft + CHEST_W && hr > chestLeft,
                                    "チェストと重なった " + at);
                            // OmniChest パネル (背景 + 影) と非交差
                            assertFalse(hl < pr && hr > pl, "サイドパネルと重なった " + at);
                        }
                    }
                }
            }
        }
        assertTrue(checked > 20000, "総当たりが少なすぎる: " + checked);
        assertTrue(drawn > 0, "1 件も描画されないのはテストとして無意味");
    }

    @Test
    void reportedRegressionCase_screen400_panel186_isNotDrawnOverlapping() {
        // 報告した回帰の再現点: 論理幅 400 / パネル 186 でチェストが左へ寄せられた状態。
        // 旧実装は「同じ側」 に 110px 決め打ちで置いてパネルへ重なっていた。
        final int screenW = 400;
        final int panelWidth = 186;
        final int chestLeft = screenW - CHEST_W - (PANEL_GAP + panelWidth + PANEL_MARGIN
                + SHADOW + EDGE_PAD); // = shiftAsideForPanel 後の位置
        assertEquals(23, chestLeft);

        int pl = panelVisualLeft(chestLeft, true, panelWidth);
        int pr = panelVisualRight(chestLeft, true, panelWidth);
        SidePanelFit.HelpPlacement p = SidePanelFit.placeHelp(
                screenW, chestLeft, chestLeft + CHEST_W, pl, pr, true,
                140, HELP_MIN_W, HELP_MARGIN, EDGE_PAD);

        // 反対側は 23-4-2 = 17px しか無く、同じ側もパネル右端 (=398) の外に 60px 取れない
        // → 描画しない。旧実装のような「重なって描く」 にはならない。
        assertEquals(null, p, "置き場所が無いので非表示になるべき");
    }

    @Test
    void helpPrefersOppositeSideAndIgnoresPanelWidthThere() {
        // 反対側に余地があるうちはパネル幅に一切影響されない (= 通常ケースは従来と同じ挙動)。
        final int screenW = 640;
        final int chestLeft = (screenW - CHEST_W) / 2; // 232
        SidePanelFit.HelpPlacement a = SidePanelFit.placeHelp(screenW, chestLeft,
                chestLeft + CHEST_W, panelVisualLeft(chestLeft, true, MIN_W),
                panelVisualRight(chestLeft, true, MIN_W), true, 140, HELP_MIN_W, HELP_MARGIN, EDGE_PAD);
        SidePanelFit.HelpPlacement b = SidePanelFit.placeHelp(screenW, chestLeft,
                chestLeft + CHEST_W, panelVisualLeft(chestLeft, true, MAX_W),
                panelVisualRight(chestLeft, true, MAX_W), true, 140, HELP_MIN_W, HELP_MARGIN, EDGE_PAD);
        assertEquals(a, b, "反対側配置はパネル幅に依存してはいけない");
        assertEquals(chestLeft - 140 - HELP_MARGIN, a.x());
        assertEquals(140, a.width());
    }

    @Test
    void helpIsHiddenRatherThanClippedWhenNoRoom() {
        // 極端に狭い画面: どちらにも 60px 取れない → null (= 非表示)。
        SidePanelFit.HelpPlacement p = SidePanelFit.placeHelp(
                200, 12, 12 + CHEST_W, panelVisualLeft(12, true, MIN_W),
                panelVisualRight(12, true, MIN_W), true, 140, HELP_MIN_W, HELP_MARGIN, EDGE_PAD);
        assertEquals(null, p);
    }

    @Test
    void hugeLabelFallsBackToTruncationNotOverflow() {
        // 上限まで広げても収まらない極端なラベルは「縦積みでも収まらない」 と判定され、
        // 呼び出し側の切り詰め (省略記号 + ツールチップ) へ落ちる。
        int panel = SidePanelFit.panelWidth(500, 500, GAP, MIN_W, MAX_W);
        assertEquals(MAX_W, panel);
        assertTrue(SidePanelFit.shouldStackRow(500, 500, panel, GAP));
        assertFalse(SidePanelFit.fits(500, panel), "切り詰めが必要な状態として検出されるべき");
    }
}
