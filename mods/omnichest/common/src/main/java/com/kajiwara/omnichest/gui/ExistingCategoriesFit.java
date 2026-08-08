package com.kajiwara.omnichest.gui;

/**
 * 「既存カテゴリ」 画面 ({@code ExistingCategoriesScreen}) のレイアウトを求める純粋関数。
 * Minecraft 型に一切依存しないため {@code common} 側に置き、 単体テスト可能にしている
 * ({@link GuiScaleFit} / {@link SidePanelFit} と同じ流儀)。
 *
 * <p>
 * <b>背景 (= なぜ必要か)</b>: 旧実装はグリッドを画面中央に縦センタリングし
 * ({@code gridTop = H/2 - rows*CELL_H/2})、 その下端に Back ボタンを積んでいた。 このため
 * 必要な論理高さが {@code rows * CELL_H + 100} と<b>項目数 n に比例して増え</b>、
 * カテゴリが 18 → 27 に増えた 1.3.0 では多くの解像度 / GUI スケールで
 * 「項目が画面外へ見切れる」「Back ボタンがフッターヒントに重なる」 が起きていた。
 *
 * <p>
 * <b>本クラスの構造</b>: 上下のバンド (タイトル / 副題 … Back ボタン / フッターヒント) を
 * 画面端に固定し、 その間を<b>スクロールするリスト帯</b>にする。 これにより必要高さが
 * <b>n に依存しない定数</b>になる。
 *
 * <p>
 * <b>不変条件 (invariant)</b>:
 * <ul>
 *   <li>必要論理サイズは {@link #MIN_SCREEN_WIDTH} × {@link #MIN_SCREEN_HEIGHT} で、
 *       <b>項目数 n に依存しない</b> (n が増えてもリストがスクロールするだけ)。</li>
 *   <li>スクロール量は常に {@code [0, max(0, contentHeight - viewHeight)]} にクランプされる。
 *       項目が全部収まるときは {@code maxScroll == 0} となりスクロールは自動的に無効化される。</li>
 *   <li>グリッドの左端 {@link Layout#gridLeft()} は、 2 列が収まる幅では
 *       <b>旧実装の {@code (screenWidth - gridWidth) / 2} と完全に同値</b>
 *       (= スクロールバー列を左右対称に予約しているため、 見た目の横位置は変わらない)。</li>
 *   <li>列数は {@link #COLS_MAX} を超えない (= 広い画面の見た目は従来どおり 2 列のまま)。
 *       狭いときだけ 1 列へ落ちる<b>片方向</b>の適応。</li>
 * </ul>
 *
 * <p>
 * <b>なぜ {@code MIN_SCREEN_HEIGHT} = 120 で十分か (= 全解像度 / 全 GUI スケール保証の根拠)</b>:
 * vanilla の {@code com.mojang.blaze3d.platform.Window#calculateScale} は
 * <pre>
 *   i = 1;
 *   while (i != guiScaleSetting &amp;&amp; i &lt; fbW &amp;&amp; i &lt; fbH
 *          &amp;&amp; fbW / (i + 1) &gt;= 320 &amp;&amp; fbH / (i + 1) &gt;= 240) i++;
 *   if (forceUnicode &amp;&amp; i % 2 != 0) i++;
 * </pre>
 * であり (MC 26.1.2 の実バイトコードで確認)、 「次の 1 段で 320x240 を割る」 直前で止まる。
 * よって<b>論理サイズは常に 320x240 以上</b>になる (ユーザが GUI スケール 8 を選んでも、
 * 小さいウィンドウでは vanilla 自身が実効スケールを下げる)。 唯一の例外が
 * {@code forceUnicode} の {@code i++} で、 これは 1 段だけ押し上げるため論理サイズは最悪
 * {@code 320/2 x 240/2 = 160x120} まで下がりうる。 本クラスの必要サイズはその最悪値以下に
 * 収めてあるので、 <b>MC が許すどのウィンドウ / どの GUI スケールでも破綻しない</b>。
 *
 * <p>
 * <b>外すと何が壊れるか</b>: 画面側がこの関数を通さず自前で座標計算を持つと、 単体テストが
 * 実挙動を保証しなくなる (= 前回の 「アドホック分析がリポジトリに残らず誤った前提が伝播した」
 * 再発)。 レイアウト算術は<b>すべてここに置き、 画面はフィールドを読むだけ</b>にすること。
 */
public final class ExistingCategoriesFit {

    private ExistingCategoriesFit() {
    }

    // ════════════════════════════════════════════════════════════════════
    // 寸法定数 (単一ソース)
    //   ※ UILayoutMetrics は client ソースセットにあり common からは参照できないため、
    //     同じ値をここに写している (SidePanelFitTest が GAP / MIN_W を写しているのと同流儀)。
    // ════════════════════════════════════════════════════════════════════

    /** 1 行の縦ピッチ (= 旧実装の {@code CELL_H})。 */
    public static final int CELL_H = 22;
    /** カテゴリ色チップの高さ (= 旧実装の {@code CHIP_H})。 */
    public static final int CHIP_H = 16;
    /**
     * カテゴリ色チップの標準幅 (= 旧実装の {@code CHIP_W})。
     * en_us の最長ラベル "Decoration Storage" が 95px なので 104 で全ラベルが収まる (実測)。
     */
    public static final int CHIP_W_MAX = 104;
    /**
     * チップ幅の下限。 標準幅が取れないほど狭いときだけここまで縮める。
     * 実際には {@link #MIN_SCREEN_WIDTH} 近辺でも標準幅が取れるため通常は発火しない
     * (= 防御的な下限。 これを割るくらいなら色チップとして識別できる最小サイズを保つ)。
     */
    public static final int CHIP_W_MIN = 60;
    /** 列間の隙間 (= 旧実装の {@code COL_GAP})。 */
    public static final int COL_GAP = 16;
    /** チップと {@code xN} の間の隙間 (= 旧実装の {@code COUNT_GAP})。 */
    public static final int COUNT_GAP = 6;
    /** 列数の上限。 <b>2 に据え置く</b> = 広い画面の見た目は従来と完全同一。 */
    public static final int COLS_MAX = 2;

    /** 画面左右の最小マージン。 右側はここにスクロールバーを置く。 */
    public static final int INSET_X = 8;
    /** タイトルの上端 (= {@code UILayoutMetrics.SCREEN_INSET_TOP})。 */
    public static final int INSET_TOP = 8;
    /** タイトル → 副題 の縦ピッチ (font.lineHeight 9 に対し 1px の余裕)。 */
    public static final int TITLE_PITCH = 10;
    /** 副題の下端 → リスト帯の上端。 */
    public static final int SUBTITLE_TO_LIST = 3;
    /** リスト帯の下端 → Back ボタンの上端。 */
    public static final int LIST_TO_BACK = 4;
    /** Back ボタンの下端 → フッターヒント帯の上端。 */
    public static final int BACK_TO_HINT = 4;

    /** Back ボタンの寸法 (= 旧実装と同じ 120 x {@code UILayoutMetrics.BUTTON_HEIGHT})。 */
    public static final int BACK_W = 120;
    public static final int BACK_H = 18;

    /** フッターヒントを底からどれだけ持ち上げるか (= {@code UILayoutMetrics.FOOTER_HINT_FROM_BOTTOM})。 */
    public static final int FOOTER_HINT_FROM_BOTTOM = 18;
    /** ヒント文字の backdrop 帯が文字の上下に取る余白 (= 旧実装と同じ 2px)。 */
    public static final int HINT_BAND_PAD = 2;

    /** スクロールバーの幅 (= {@code UILayoutMetrics.SCROLLBAR_WIDTH})。 */
    public static final int SCROLLBAR_W = 4;
    /** スクロールバーの当たり判定マージン (= {@code UILayoutMetrics.SCROLLBAR_HIT_MARGIN})。 */
    public static final int SCROLLBAR_HIT_MARGIN = 4;
    /** つまみの最小高さ (= {@code DistributionScreen#renderScrollbar} と同値)。 */
    public static final int THUMB_MIN_H = 20;

    /** ホイール 1 ノッチの送り量 (= 既存 2 画面と同じ 「行高 x 2」)。 */
    public static final int SCROLL_STEP = CELL_H * 2;

    /** リスト帯に最低限確保する行数 (= これを割ったら 「破綻」 とみなす)。 */
    public static final int MIN_VISIBLE_ROWS = 2;

    /**
     * 破綻せずに描ける最小の論理高さ。
     * {@code INSET_TOP(8) + TITLE_PITCH(10) + lineHeight(9) + SUBTITLE_TO_LIST(3)
     *  + MIN_VISIBLE_ROWS*CELL_H(44) + LIST_TO_BACK(4) + BACK_H(18) + BACK_TO_HINT(4)
     *  + HINT_BAND_PAD(2) + FOOTER_HINT_FROM_BOTTOM(18)} = 120。
     */
    public static final int MIN_SCREEN_HEIGHT = 120;
    /**
     * 破綻せずに描ける最小の論理幅。
     * {@code 2*(INSET_X + SCROLLBAR_W)(24) + CHIP_W_MIN(60) + COUNT_GAP(6) + countWidth(24)} = 114。
     * ({@code countWidth} は {@code font.width("x999")} = 24 を代表値とした場合)
     */
    public static final int MIN_SCREEN_WIDTH = 2 * (INSET_X + SCROLLBAR_W) + CHIP_W_MIN + COUNT_GAP + 24;

    // ════════════════════════════════════════════════════════════════════
    // レイアウト
    // ════════════════════════════════════════════════════════════════════

    /**
     * 画面 1 回ぶんの確定レイアウト。 画面側はここのフィールド / メソッドを読むだけで、
     * <b>自前の座標計算を一切持たない</b>。
     *
     * @param screenWidth   論理画面幅
     * @param screenHeight  論理画面高さ
     * @param itemCount     並べる項目数 (= concrete カテゴリ数)
     * @param titleY        タイトル文字の上端 y
     * @param subtitleY     副題文字の上端 y
     * @param listTop       リスト帯 (scissor 範囲) の上端 y
     * @param listBottom    リスト帯の下端 y
     * @param backX         Back ボタン左端 x
     * @param backY         Back ボタン上端 y
     * @param hintY         フッターヒント文字の上端 y
     * @param hintBandTop   フッターヒント backdrop 帯の上端 y
     * @param cols          実際の列数 (1 .. {@link #COLS_MAX})
     * @param rows          実際の行数 {@code ceil(itemCount / cols)}
     * @param chipW         カテゴリ色チップの実幅
     * @param colW          1 列ぶんの幅 ({@code chipW + COUNT_GAP + countWidth})
     * @param gridLeft      グリッド左端 x
     * @param scrollbarX    スクロールバー左端 x
     */
    public record Layout(
            int screenWidth, int screenHeight, int itemCount,
            int titleY, int subtitleY,
            int listTop, int listBottom,
            int backX, int backY,
            int hintY, int hintBandTop,
            int cols, int rows, int chipW, int colW, int gridLeft,
            int scrollbarX) {

        /** Back ボタンの幅 (定数だがレイアウトとして読めるように公開)。 */
        public int backW() {
            return BACK_W;
        }

        /** Back ボタンの高さ。 */
        public int backH() {
            return BACK_H;
        }

        /** スクロールバーの幅。 */
        public int scrollbarW() {
            return SCROLLBAR_W;
        }

        /** リスト帯の可視高さ。 */
        public int viewHeight() {
            return Math.max(0, listBottom - listTop);
        }

        /** 全項目を並べたときの総高さ。 */
        public int contentHeight() {
            return rows * CELL_H;
        }

        /** スクロールの最大値。 {@code 0} なら全項目が収まっている (= スクロール不要)。 */
        public int maxScroll() {
            return Math.max(0, contentHeight() - viewHeight());
        }

        /** グリッド全体の幅。 */
        public int gridWidth() {
            return cols * colW + (cols - 1) * COL_GAP;
        }

        /**
         * 全項目が収まるときに、 リスト帯の中で縦センタリングするためのオフセット。
         * スクロールが必要なときは 0 (= 先頭から詰める)。 旧実装の 「画面中央にグリッド」 という
         * 見た目を、 スクロール不要な通常ケースでは維持するためのもの。
         */
        public int contentOffsetY() {
            return maxScroll() > 0 ? 0 : Math.max(0, (viewHeight() - contentHeight()) / 2);
        }

        /** {@code index} 番目のセルの左端 x。 */
        public int cellX(int index) {
            return gridLeft + (index % cols) * (colW + COL_GAP);
        }

        /** {@code index} 番目のセルの上端 y (スクロール適用後)。 */
        public int cellY(int index, double scrollPx) {
            return listTop + contentOffsetY() - (int) Math.round(scrollPx)
                    + (index / cols) * CELL_H;
        }

        /** 空状態メッセージ (登録 0 件) を描く y。 リスト帯の縦中央。 */
        public int emptyTextY(int lineHeight) {
            return listTop + (viewHeight() - lineHeight) / 2;
        }

        /** スクロール量を {@code [0, maxScroll]} に丸める。 慣性やタッチで飛んでも破綻しない。 */
        public double clampScroll(double scrollPx) {
            if (!(scrollPx > 0)) {          // NaN もここで 0 に落ちる
                return 0.0;
            }
            return Math.min(scrollPx, maxScroll());
        }

        /** スクロールバーを描くか (= 収まっているときは描かない)。 */
        public boolean scrollbarVisible() {
            return maxScroll() > 0;
        }

        /** スクロールバーつまみの高さ。 */
        public int thumbHeight() {
            int viewH = viewHeight();
            int contentH = contentHeight();
            if (contentH <= viewH) {
                return viewH;
            }
            return Math.max(THUMB_MIN_H, (int) ((long) viewH * viewH / contentH));
        }

        /** スクロールバーつまみの上端 y。 */
        public int thumbY(double scrollPx) {
            int max = maxScroll();
            if (max <= 0) {
                return listTop;
            }
            return listTop + (int) ((clampScroll(scrollPx) / max) * (viewHeight() - thumbHeight()));
        }

        /** マウス座標がスクロールバー (当たり判定マージン込み) の上か。 */
        public boolean isOverScrollbar(double mouseX, double mouseY) {
            return scrollbarVisible()
                    && mouseX >= scrollbarX - SCROLLBAR_HIT_MARGIN
                    && mouseX <= scrollbarX + SCROLLBAR_W + SCROLLBAR_HIT_MARGIN
                    && mouseY >= listTop && mouseY <= listBottom;
        }

        /** つまみを掴んで {@code mouseY} まで動かしたときのスクロール量。 */
        public double scrollFromMouseY(double mouseY) {
            int max = maxScroll();
            int span = viewHeight() - thumbHeight();
            if (max <= 0 || span <= 0) {
                return 0.0;
            }
            double frac = (mouseY - listTop - thumbHeight() / 2.0) / span;
            if (frac < 0) {
                frac = 0;
            } else if (frac > 1) {
                frac = 1;
            }
            return frac * max;
        }
    }

    /**
     * レイアウトを確定する。
     *
     * @param screenWidth  論理画面幅 ({@code Screen#width})
     * @param screenHeight 論理画面高さ ({@code Screen#height})
     * @param itemCount    項目数 (= concrete カテゴリ数)。 0 でも安全 (空状態)。
     * @param countWidth   {@code font.width("xN")} の想定最大幅 (= 実行時に font で測った値)
     * @param lineHeight   {@code font.lineHeight}
     */
    public static Layout compute(int screenWidth, int screenHeight, int itemCount,
            int countWidth, int lineHeight) {
        // ── 縦: 上下のバンドを画面端に固定し、 残りを全部リスト帯にする ──
        int titleY = INSET_TOP;
        int subtitleY = titleY + TITLE_PITCH;
        int listTop = subtitleY + lineHeight + SUBTITLE_TO_LIST;

        int hintY = screenHeight - FOOTER_HINT_FROM_BOTTOM;
        int hintBandTop = hintY - HINT_BAND_PAD;
        int backY = hintBandTop - BACK_TO_HINT - BACK_H;
        int listBottom = backY - LIST_TO_BACK;

        // ── 横: スクロールバー列を左右対称に予約する ──
        //   こうすると grid の中央寄せが旧実装の (screenWidth - gridWidth) / 2 と同値になり、
        //   バーを足しても横位置が動かない。
        int sideReserve = INSET_X + SCROLLBAR_W;
        int contentLeft = sideReserve;
        int contentW = Math.max(0, screenWidth - 2 * sideReserve);

        int colWFull = CHIP_W_MAX + COUNT_GAP + countWidth;
        int fitCols = colWFull + COL_GAP <= 0 ? 1 : (contentW + COL_GAP) / (colWFull + COL_GAP);
        int cols = Math.max(1, Math.min(COLS_MAX, fitCols));

        int perCol = (contentW - (cols - 1) * COL_GAP) / cols;
        int chipW = clamp(perCol - COUNT_GAP - countWidth, CHIP_W_MIN, CHIP_W_MAX);
        int colW = chipW + COUNT_GAP + countWidth;

        int gridW = cols * colW + (cols - 1) * COL_GAP;
        int gridLeft = contentLeft + Math.max(0, (contentW - gridW) / 2);

        int rows = itemCount <= 0 ? 0 : (itemCount + cols - 1) / cols;

        int scrollbarX = screenWidth - INSET_X - SCROLLBAR_W;
        int backX = (screenWidth - BACK_W) / 2;

        return new Layout(screenWidth, screenHeight, itemCount,
                titleY, subtitleY, listTop, listBottom,
                backX, backY, hintY, hintBandTop,
                cols, rows, chipW, colW, gridLeft, scrollbarX);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
