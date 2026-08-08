package com.kajiwara.omnichest.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ExistingCategoriesFit} の単体テスト (= 「既存カテゴリ」 画面が全解像度 / 全 GUI スケールで
 * 破綻しないことの回帰テスト)。
 *
 * <p>
 * <b>なぜこのテストが要るのか</b>: 以前この画面の適合率は「その場かぎりの分析スクリプト」で
 * 測られ、 リポジトリに残らなかった。 結果、 再現できない数字 (「73/180」 等) と
 * 「必要高 = ceil(n/2)*22 + 96」 のような<b>誤った前提が下流に伝播</b>した。 同じことを
 * 繰り返さないため、 判定式そのものをテストとして固定する。 レイアウト算術は
 * {@link ExistingCategoriesFit} に一元化してあり、 画面はその戻り値を読むだけなので、
 * <b>ここで通ることは実挙動が通ることと同じ</b>である。
 *
 * <p>
 * <b>「表示 OK」 の判定式</b> — 旧 (スクロール無し時代) との対比:
 * <table border="1">
 *   <caption>判定式の変更点</caption>
 *   <tr><th>#</th><th>旧 (全項目同時表示)</th><th>新 (スクロール前提)</th><th>変化</th></tr>
 *   <tr><td>1</td><td>タイトルが画面内</td><td>同左</td><td>同一</td></tr>
 *   <tr><td>2</td><td>副題とリストが非重複</td><td>同左</td><td>同一</td></tr>
 *   <tr><td>3</td><td>—</td><td>リスト帯 &gt;= {@value ExistingCategoriesFit#MIN_VISIBLE_ROWS} 行</td>
 *       <td><b>追加 (より厳しい)</b></td></tr>
 *   <tr><td>4</td><td>Back がフッターヒント帯と非重複</td><td>同左 <b>+ Back がリスト帯とも非重複</b></td>
 *       <td>据置 + 追加</td></tr>
 *   <tr><td>5</td><td>グリッドが画面内 (左端 &gt;= 0 のみ)</td>
 *       <td>1 行の内容がリスト帯幅に収まり、 スクロールバーにも被らない</td>
 *       <td><b>強化 (横の見切れを新たに検出)</b></td></tr>
 *   <tr><td>6</td><td><b>全 rows が同時に見える</b></td><td>スクロールで最終行に到達できる</td>
 *       <td><b>ここだけ撤回</b> (スクロール導入により要件でなくなった)</td></tr>
 * </table>
 * すなわち<b>緩めたのは 1 句だけ</b>で、 同時に 3 句を追加している。 判定を甘くして数字を
 * 作ったのではないことは {@link #legacyImplementationFailsTheSameCriteria()} が示す
 * (= 同じ新判定式を旧レイアウトに当てても大半が落ちる)。
 *
 * <p>
 * <b>「180 通り」 の意味と、 それより強い保証</b>: 下の {@link #RESOLUTIONS} x
 * {@link #GUI_SCALES} は代表的なモニタでの確認にすぎない。 本当の根拠は vanilla の
 * {@code com.mojang.blaze3d.platform.Window#calculateScale} (MC 26.1.2 の実バイトコードで確認):
 * <pre>
 *   i = 1;
 *   while (i != guiScaleSetting &amp;&amp; i &lt; fbW &amp;&amp; i &lt; fbH
 *          &amp;&amp; fbW / (i + 1) &gt;= 320 &amp;&amp; fbH / (i + 1) &gt;= 240) i++;
 *   if (forceUnicode &amp;&amp; i % 2 != 0) i++;
 * </pre>
 * ループは「次の 1 段で 320x240 を割る」 直前で止まるため、 <b>論理サイズは常に 320x240 以上</b>
 * (ユーザが GUI スケール 8 を選んでも、 小さいウィンドウでは vanilla 自身が実効スケールを下げる)。
 * 唯一の例外が {@code forceUnicode} の {@code i++} で、 1 段だけ押し上げるため論理サイズは
 * 最悪 {@code 160x120} まで下がりうる。 {@link ExistingCategoriesFit#MIN_SCREEN_HEIGHT} = 120 /
 * {@link ExistingCategoriesFit#MIN_SCREEN_WIDTH} = 114 はその最悪値以下なので、
 * <b>MC が許すどのウィンドウでも破綻しない</b> ({@link #fitsAtTheAbsoluteWorstCaseLogicalSize()})。
 */
class ExistingCategoriesFitTest {

    /** {@code font.width("x999")} の実測値 (MC 26.1.2 のフォント実資産から算出。 x=6 / 数字=6 x3)。 */
    private static final int COUNT_W = 24;
    /** {@code font.lineHeight}。 */
    private static final int LINE_H = 9;

    /** 1.3.0 現在の concrete カテゴリ数。 */
    private static final int N_CURRENT = 27;

    /** 代表解像度 20 種 (4:3 / 5:4 / 16:9 / 16:10 / ウルトラワイド / 4K / 実機報告値 を含む)。 */
    private static final int[][] RESOLUTIONS = {
            { 640, 480 }, { 854, 480 }, { 1024, 768 }, { 1280, 720 }, { 1280, 800 },
            { 1280, 1024 }, { 1366, 768 }, { 1440, 900 }, { 1600, 900 }, { 1600, 1200 },
            { 1680, 1050 }, { 1920, 1080 }, { 1920, 1200 }, { 2048, 1536 }, { 2560, 1080 },
            { 2560, 1440 }, { 2560, 1600 }, { 3440, 1440 }, { 3835, 2076 }, { 3840, 2160 },
    };

    /** GUI スケール設定。 {@code 0} = Auto。 */
    private static final int[] GUI_SCALES = { 1, 2, 3, 4, 5, 6, 7, 8, 0 };

    /** 総組み合わせ数 (= 20 x 9)。 */
    private static final int TOTAL = 180;

    // ════════════════════════════════════════════════════════════════════
    // MC 側の再現 (Window#calculateScale / setGuiScale)
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

    // ════════════════════════════════════════════════════════════════════
    // 判定式
    // ════════════════════════════════════════════════════════════════════

    /** 新判定式 (スクロール前提)。 上の表の 1..6 をそのままコードにしたもの。 */
    private static boolean displaysOk(ExistingCategoriesFit.Layout l) {
        // (1) タイトルが画面内
        if (l.titleY() < 0) {
            return false;
        }
        // (2) 副題がリスト帯に食い込まない
        if (l.subtitleY() + LINE_H > l.listTop()) {
            return false;
        }
        // (3) リスト帯が最低行数ぶんの高さを持つ
        if (l.viewHeight() < ExistingCategoriesFit.MIN_VISIBLE_ROWS * ExistingCategoriesFit.CELL_H) {
            return false;
        }
        // (4) Back がリスト帯ともフッターヒント帯とも重ならない
        if (l.backY() < l.listBottom()) {
            return false;
        }
        if (l.backY() + l.backH() > l.hintBandTop()) {
            return false;
        }
        // (5) 1 行の内容が横に収まり、 スクロールバーにも被らない
        if (l.gridLeft() < 0 || l.gridLeft() + l.gridWidth() > l.scrollbarX()) {
            return false;
        }
        // (6) スクロールで最終行に到達できる (= 下端まで送ったとき最終行がリスト帯に入る)
        if (l.itemCount() > 0) {
            int lastTop = l.cellY(l.itemCount() - 1, l.maxScroll());
            if (lastTop < l.listTop()
                    || lastTop + ExistingCategoriesFit.CELL_H > l.listBottom()) {
                return false;
            }
        }
        return true;
    }

    // ── 旧実装 (スクロール無し) の座標再現 ─────────────────────────────
    //   ExistingCategoriesScreen の 1.3.0 時点のコードをそのまま写している。
    //   これを残しておくことで「何がどう直ったのか」 がテストだけで再導出できる。

    private record Legacy(int rows, int gridW, int gridLeft, int gridTop, int gridBottom,
            int backY, int titleTop, int subtitleTop, int hintBandTop) {
    }

    private static Legacy legacy(int n, int cols, int w, int h) {
        int rows = (n + cols - 1) / cols;
        int colW = ExistingCategoriesFit.CHIP_W_MAX + ExistingCategoriesFit.COUNT_GAP + COUNT_W;
        int gridW = cols * colW + (cols - 1) * ExistingCategoriesFit.COL_GAP;
        int gridLeft = (w - gridW) / 2;
        int gridTop = h / 2 - (rows * ExistingCategoriesFit.CELL_H) / 2;
        int backY = gridTop + rows * ExistingCategoriesFit.CELL_H + 8 /* SECTION_GAP */ + 4;
        return new Legacy(rows, gridW, gridLeft, gridTop,
                gridTop + rows * ExistingCategoriesFit.CELL_H, backY,
                gridTop - 34, gridTop - 20,
                h - ExistingCategoriesFit.FOOTER_HINT_FROM_BOTTOM - ExistingCategoriesFit.HINT_BAND_PAD);
    }

    /** 旧判定式 (= 全項目が同時に見えること)。 1.3.0 時点の実装をこれで測ると 44/180。 */
    private static boolean legacyOkUnderOldCriteria(int n, int cols, int w, int h) {
        Legacy g = legacy(n, cols, w, h);
        return g.gridLeft() >= 0
                && g.titleTop() >= 0
                && g.subtitleTop() + LINE_H <= g.gridTop()
                && g.backY() + ExistingCategoriesFit.BACK_H <= g.hintBandTop();
    }

    /**
     * 旧実装に<b>新判定式</b>を当てたもの。 旧実装には scissor もスクロールも無いため、
     * 「リスト帯」 = グリッドが実際に描かれる矩形、 「到達性」 = 全行が画面内、 と読み替える。
     */
    private static boolean legacyOkUnderNewCriteria(int n, int cols, int w, int h) {
        Legacy g = legacy(n, cols, w, h);
        int listTop = g.gridTop();
        int listBottom = g.gridBottom();
        return g.titleTop() >= 0                                        // (1)
                && g.subtitleTop() + LINE_H <= listTop                  // (2)
                && listBottom - listTop >= ExistingCategoriesFit.MIN_VISIBLE_ROWS
                        * ExistingCategoriesFit.CELL_H                  // (3)
                && g.backY() >= listBottom                              // (4a)
                && g.backY() + ExistingCategoriesFit.BACK_H <= g.hintBandTop()  // (4b)
                && g.gridLeft() >= 0 && g.gridLeft() + g.gridW() <= w   // (5)
                && listTop >= 0 && listBottom <= h;                     // (6) 全行が画面内
    }

    // ════════════════════════════════════════════════════════════════════
    // 掃き出し
    // ════════════════════════════════════════════════════════════════════

    private interface Predicate {
        boolean test(int logicalW, int logicalH);
    }

    private static int sweep(Predicate p, boolean forceUnicode) {
        int ok = 0;
        for (int[] res : RESOLUTIONS) {
            for (int s : GUI_SCALES) {
                int scale = calculateScale(s, res[0], res[1], forceUnicode);
                if (p.test(logical(res[0], scale), logical(res[1], scale))) {
                    ok++;
                }
            }
        }
        return ok;
    }

    private static Predicate fitFor(int n) {
        return (w, h) -> displaysOk(ExistingCategoriesFit.compute(w, h, n, COUNT_W, LINE_H));
    }

    // ════════════════════════════════════════════════════════════════════
    // 中核: 全 180 通りで表示 OK、 かつ n に依存しない
    // ════════════════════════════════════════════════════════════════════

    @Test
    void allCombinationsDisplayOk() {
        assertEquals(TOTAL, sweep(fitFor(N_CURRENT), false),
                "通常 (Force Unicode OFF) で 180/180 でなければならない");
    }

    @Test
    void allCombinationsDisplayOkWithForceUnicode() {
        assertEquals(TOTAL, sweep(fitFor(N_CURRENT), true),
                "Force Unicode ON でも 180/180 でなければならない");
    }

    @Test
    void displayOkCountDoesNotDependOnItemCount() {
        // カテゴリが今後増えても必要高さが増えない構造であることの実証。
        for (int n : new int[] { 0, 1, 18, 27, 40, 60, 200 }) {
            assertEquals(TOTAL, sweep(fitFor(n), false), "n=" + n + " (通常) で 180/180 でない");
            assertEquals(TOTAL, sweep(fitFor(n), true), "n=" + n + " (Unicode) で 180/180 でない");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 「判定を緩めたのではない」 ことの実証
    // ════════════════════════════════════════════════════════════════════

    @Test
    void legacyImplementationFailsTheSameCriteria() {
        // 同じ新判定式を旧レイアウト (スクロール無し) に当てると、 圧倒的多数が落ちる。
        // = 数字が良くなったのは判定を甘くしたからではなく、 レイアウトが直ったから。
        int legacyNew = sweep((w, h) -> legacyOkUnderNewCriteria(N_CURRENT, 2, w, h), false);
        int legacyOld = sweep((w, h) -> legacyOkUnderOldCriteria(N_CURRENT, 2, w, h), false);
        assertEquals(44, legacyOld, "旧実装 n=27 を旧判定式で測ると 44/180");
        assertEquals(44, legacyNew, "旧実装 n=27 を新判定式で測っても改善しない (= 判定は緩んでいない)");
        assertTrue(sweep(fitFor(N_CURRENT), false) > legacyNew,
                "新実装は同一判定式で旧実装を上回っていなければ意味が無い");
    }

    @Test
    void legacyGetsWorseAsCategoriesGrow() {
        // 「カテゴリが増えるほど壊れる」 という旧実装の性質を固定しておく
        // (= 新実装の n 非依存性と対になる回帰テスト)。
        assertEquals(80, sweep((w, h) -> legacyOkUnderOldCriteria(18, 2, w, h), false),
                "1.2.0 まで (n=18) は 80/180");
        assertEquals(44, sweep((w, h) -> legacyOkUnderOldCriteria(27, 2, w, h), false),
                "1.3.0 (n=27) は 44/180");
        assertEquals(31, sweep((w, h) -> legacyOkUnderOldCriteria(40, 2, w, h), false));
        assertEquals(21, sweep((w, h) -> legacyOkUnderOldCriteria(60, 2, w, h), false));
    }

    // ════════════════════════════════════════════════════════════════════
    // 下限サイズ (= 列挙に依存しない保証)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void fitsAtTheAbsoluteWorstCaseLogicalSize() {
        // Force Unicode の 1 段押し上げを考えた理論最悪 = 160x120。
        for (int n : new int[] { 0, 27, 60, 200 }) {
            assertTrue(displaysOk(ExistingCategoriesFit.compute(160, 120, n, COUNT_W, LINE_H)),
                    "160x120 (理論最悪) で n=" + n + " が破綻した");
        }
        // vanilla が保証する下限 320x240 でも当然 OK。
        for (int n : new int[] { 0, 27, 60, 200 }) {
            assertTrue(displaysOk(ExistingCategoriesFit.compute(320, 240, n, COUNT_W, LINE_H)),
                    "320x240 (vanilla 下限) で n=" + n + " が破綻した");
        }
    }

    @Test
    void minimumScreenSizeConstantsAreExact() {
        // MIN_SCREEN_HEIGHT / MIN_SCREEN_WIDTH が「ちょうど収まる最小値」 であること
        // (= 1px 削ると落ちる)。 定数を後からいじったら気づけるようにする。
        assertTrue(displaysOk(ExistingCategoriesFit.compute(
                640, ExistingCategoriesFit.MIN_SCREEN_HEIGHT, N_CURRENT, COUNT_W, LINE_H)));
        assertFalse(displaysOk(ExistingCategoriesFit.compute(
                640, ExistingCategoriesFit.MIN_SCREEN_HEIGHT - 1, N_CURRENT, COUNT_W, LINE_H)));
        assertTrue(displaysOk(ExistingCategoriesFit.compute(
                ExistingCategoriesFit.MIN_SCREEN_WIDTH, 480, N_CURRENT, COUNT_W, LINE_H)));
        assertFalse(displaysOk(ExistingCategoriesFit.compute(
                ExistingCategoriesFit.MIN_SCREEN_WIDTH - 1, 480, N_CURRENT, COUNT_W, LINE_H)));
        assertEquals(120, ExistingCategoriesFit.MIN_SCREEN_HEIGHT);
        assertEquals(114, ExistingCategoriesFit.MIN_SCREEN_WIDTH);
    }

    @Test
    void logicalSizeNeverDropsBelow320x240WithoutForceUnicode() {
        // 上の javadoc に書いた根拠そのものをテストにしておく (= 次に読む人が再導出できる)。
        int minW = Integer.MAX_VALUE;
        int minH = Integer.MAX_VALUE;
        for (int[] res : RESOLUTIONS) {
            for (int s : GUI_SCALES) {
                int scale = calculateScale(s, res[0], res[1], false);
                minW = Math.min(minW, logical(res[0], scale));
                minH = Math.min(minH, logical(res[1], scale));
            }
        }
        assertEquals(320, minW);
        assertEquals(240, minH);
    }

    // ════════════════════════════════════════════════════════════════════
    // 見た目の非回帰 (= 広い画面では従来と同じ位置・同じ 2 列)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void wideScreensKeepTwoColumnsAndTheLegacyHorizontalPosition() {
        for (int[] res : RESOLUTIONS) {
            for (int s : GUI_SCALES) {
                int scale = calculateScale(s, res[0], res[1], false);
                int w = logical(res[0], scale);
                int h = logical(res[1], scale);
                ExistingCategoriesFit.Layout l =
                        ExistingCategoriesFit.compute(w, h, N_CURRENT, COUNT_W, LINE_H);
                assertEquals(2, l.cols(), "vanilla 下限 320 幅では常に 2 列のはず w=" + w);
                assertEquals(ExistingCategoriesFit.CHIP_W_MAX, l.chipW(),
                        "チップは標準幅のままのはず w=" + w);
                // 旧実装の中央寄せと完全同値 (= スクロールバーを足しても横位置が動かない)。
                assertEquals((w - l.gridWidth()) / 2, l.gridLeft(),
                        "横位置が旧実装からずれた w=" + w);
            }
        }
    }

    @Test
    void narrowScreensFallBackToOneColumnOnly() {
        // 片方向の適応: 狭いときに 1 列へ落ちるだけで、 広いときに 3 列以上へは広がらない。
        assertEquals(1, ExistingCategoriesFit.compute(160, 240, N_CURRENT, COUNT_W, LINE_H).cols());
        assertEquals(2, ExistingCategoriesFit.compute(320, 240, N_CURRENT, COUNT_W, LINE_H).cols());
        assertEquals(ExistingCategoriesFit.COLS_MAX,
                ExistingCategoriesFit.compute(3840, 2160, N_CURRENT, COUNT_W, LINE_H).cols());
        // 160 幅でもチップは標準幅を保てる (= CHIP_W_MIN は発火しない)。
        assertEquals(ExistingCategoriesFit.CHIP_W_MAX,
                ExistingCategoriesFit.compute(160, 240, N_CURRENT, COUNT_W, LINE_H).chipW());
    }

    @Test
    void chipShrinksOnlyBelowTheReachableWidth() {
        // チップが標準幅を保てる下限は 158 (= 2*(INSET_X+SCROLLBAR_W) + CHIP_W_MAX + COUNT_GAP + 24)。
        // MC の論理幅は Force Unicode の最悪でも 160 なので、 実運用では常に標準幅のままになる。
        assertEquals(158, 2 * (ExistingCategoriesFit.INSET_X + ExistingCategoriesFit.SCROLLBAR_W)
                + ExistingCategoriesFit.CHIP_W_MAX + ExistingCategoriesFit.COUNT_GAP + COUNT_W);
        assertEquals(ExistingCategoriesFit.CHIP_W_MAX,
                ExistingCategoriesFit.compute(158, 240, N_CURRENT, COUNT_W, LINE_H).chipW());
        assertTrue(ExistingCategoriesFit.compute(157, 240, N_CURRENT, COUNT_W, LINE_H).chipW()
                < ExistingCategoriesFit.CHIP_W_MAX);
        assertTrue(ExistingCategoriesFit.compute(120, 240, N_CURRENT, COUNT_W, LINE_H).chipW()
                >= ExistingCategoriesFit.CHIP_W_MIN);
    }

    // ════════════════════════════════════════════════════════════════════
    // スクロールの不変条件
    // ════════════════════════════════════════════════════════════════════

    @Test
    void scrollIsAlwaysClampedToRange() {
        ExistingCategoriesFit.Layout l =
                ExistingCategoriesFit.compute(640, 360, N_CURRENT, COUNT_W, LINE_H);
        assertTrue(l.maxScroll() > 0, "この寸法ではスクロールが必要なはず");
        for (double v : new double[] { -1e9, -1, -0.5, 0, 1, 100, 1e9, Double.NaN }) {
            double c = l.clampScroll(v);
            assertTrue(c >= 0 && c <= l.maxScroll(), "クランプ外 " + v + " -> " + c);
        }
        assertEquals(0.0, l.clampScroll(-1));
        assertEquals(l.maxScroll(), l.clampScroll(1e9));
        assertEquals(0.0, l.clampScroll(Double.NaN), "NaN も 0 へ落ちること");
    }

    @Test
    void scrollIsDisabledWhenEverythingFits() {
        // 4K スケール 1: 27 項目 x 2 列は余裕で収まる → スクロール不要 = バー非表示。
        ExistingCategoriesFit.Layout l =
                ExistingCategoriesFit.compute(3840, 2160, N_CURRENT, COUNT_W, LINE_H);
        assertEquals(0, l.maxScroll());
        assertFalse(l.scrollbarVisible());
        assertEquals(0.0, l.clampScroll(500), "収まるときは何をしても 0");
        // 収まるときは旧実装同様リスト帯の中央へ寄せる。
        assertTrue(l.contentOffsetY() > 0);
    }

    @Test
    void everyRowIsReachableByScrolling() {
        for (int n : new int[] { 1, 27, 60, 200 }) {
            ExistingCategoriesFit.Layout l =
                    ExistingCategoriesFit.compute(320, 240, n, COUNT_W, LINE_H);
            // 先頭行はスクロール 0 で、 最終行は maxScroll でリスト帯に完全に入る。
            assertTrue(l.cellY(0, 0) >= l.listTop(), "n=" + n + " 先頭行が上に隠れている");
            int lastTop = l.cellY(n - 1, l.maxScroll());
            assertTrue(lastTop >= l.listTop() && lastTop + ExistingCategoriesFit.CELL_H <= l.listBottom(),
                    "n=" + n + " 最終行に到達できない");
        }
    }

    @Test
    void thumbGeometryStaysInsideTheTrack() {
        ExistingCategoriesFit.Layout l =
                ExistingCategoriesFit.compute(640, 300, 60, COUNT_W, LINE_H);
        assertTrue(l.scrollbarVisible());
        assertTrue(l.thumbHeight() >= ExistingCategoriesFit.THUMB_MIN_H);
        assertTrue(l.thumbHeight() <= l.viewHeight());
        for (double v = 0; v <= l.maxScroll(); v += 1) {
            int y = l.thumbY(v);
            assertTrue(y >= l.listTop(), "つまみが帯の上へ出た v=" + v);
            assertTrue(y + l.thumbHeight() <= l.listBottom(), "つまみが帯の下へ出た v=" + v);
        }
        assertEquals(l.listTop(), l.thumbY(0));
        assertEquals(l.listBottom() - l.thumbHeight(), l.thumbY(l.maxScroll()));
    }

    @Test
    void draggingTheThumbMapsMonotonicallyAndReachesBothEnds() {
        ExistingCategoriesFit.Layout l =
                ExistingCategoriesFit.compute(640, 300, 60, COUNT_W, LINE_H);
        double prev = -1;
        for (int my = l.listTop() - 50; my <= l.listBottom() + 50; my++) {
            double v = l.scrollFromMouseY(my);
            assertTrue(v >= 0 && v <= l.maxScroll(), "範囲外 my=" + my);
            assertTrue(v >= prev, "単調でない my=" + my);
            prev = v;
        }
        assertEquals(0.0, l.scrollFromMouseY(l.listTop() - 50));
        assertEquals(l.maxScroll(), l.scrollFromMouseY(l.listBottom() + 50));
    }

    @Test
    void scrollbarNeverOverlapsTheGrid() {
        for (int[] res : RESOLUTIONS) {
            for (int s : GUI_SCALES) {
                for (boolean unicode : new boolean[] { false, true }) {
                    int scale = calculateScale(s, res[0], res[1], unicode);
                    int w = logical(res[0], scale);
                    int h = logical(res[1], scale);
                    ExistingCategoriesFit.Layout l =
                            ExistingCategoriesFit.compute(w, h, N_CURRENT, COUNT_W, LINE_H);
                    assertTrue(l.gridLeft() + l.gridWidth() <= l.scrollbarX(),
                            "グリッドがスクロールバーに被った " + w + "x" + h);
                    assertTrue(l.scrollbarX() + l.scrollbarW() <= w, "バーが画面外 " + w);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 端のケース
    // ════════════════════════════════════════════════════════════════════

    @Test
    void emptyStateIsSafe() {
        // 登録 0 件 (= グリッドを描かない) でも座標が壊れないこと。
        ExistingCategoriesFit.Layout l =
                ExistingCategoriesFit.compute(320, 240, 0, COUNT_W, LINE_H);
        assertEquals(0, l.rows());
        assertEquals(0, l.contentHeight());
        assertEquals(0, l.maxScroll());
        assertFalse(l.scrollbarVisible());
        assertTrue(l.emptyTextY(LINE_H) >= l.listTop());
        assertTrue(l.emptyTextY(LINE_H) + LINE_H <= l.listBottom());
    }

    @Test
    void degenerateScreenSizesDoNotThrow() {
        // resize の途中などで極端な値が来ても例外を出さない (= 描画は破綻して良いが落ちない)。
        List<int[]> sizes = new ArrayList<>();
        for (int w : new int[] { 0, 1, 40, 114, 320 }) {
            for (int h : new int[] { 0, 1, 40, 120, 240 }) {
                sizes.add(new int[] { w, h });
            }
        }
        for (int[] wh : sizes) {
            ExistingCategoriesFit.Layout l =
                    ExistingCategoriesFit.compute(wh[0], wh[1], N_CURRENT, COUNT_W, LINE_H);
            assertTrue(l.cols() >= 1, "列数は必ず 1 以上");
            assertTrue(l.maxScroll() >= 0);
            assertTrue(l.thumbHeight() >= 0);
            l.clampScroll(1234);
            l.scrollFromMouseY(0);
            l.cellX(0);
            l.cellY(0, 0);
        }
    }

    @Test
    void layoutIsPureAndDeterministic() {
        ExistingCategoriesFit.Layout a =
                ExistingCategoriesFit.compute(1920, 1080, N_CURRENT, COUNT_W, LINE_H);
        ExistingCategoriesFit.Layout b =
                ExistingCategoriesFit.compute(1920, 1080, N_CURRENT, COUNT_W, LINE_H);
        assertEquals(a, b, "同じ入力なら同じレイアウトでなければならない (record の等価性)");
    }
}
