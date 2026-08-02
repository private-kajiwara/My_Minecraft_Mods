package com.kajiwara.omnichest.gui;

/**
 * コンテナ内サイドパネルの幅を <b>ラベルの実測幅から</b> 決める純粋関数群。
 *
 * <p>
 * <b>背景 (この不具合の RCA)</b>: 旧実装はパネル幅を定数 146px に固定し、 2 列グリッドの
 * セル幅を {@code (146 - gap) / 2 = 72px} として使っていた。 バニラの
 * {@code AbstractButton} はラベルを {@code TEXT_MARGIN = 2} だけ内側に描くため実際に使える
 * 文字幅は <b>68px</b> しかなく、 これを超えるラベルは
 * {@code ActiveTextCollector#acceptScrollingWithDefaultCenter} によって
 * <b>スクロール表示 (= 左右が切れる)</b> になっていた。 en_us の "Sort by Category" は 88px
 * あるため常時見切れる。 ラベル幅を一切測っていなかったのが根本原因。
 *
 * <p>
 * <b>方針</b>: 幅は「収まる最小」から積み上げる。
 * <ol>
 * <li>ラベル実測幅からパネル幅を決める ({@link #panelWidth})。 下限は従来値なので、
 *     収まっていたロケールの見た目は<b>従来と完全に同一</b>。</li>
 * <li>画面の余地が足りず 2 列に収まらない行は 1 列 (縦積み) へ落とす ({@link #shouldStackRow})。</li>
 * <li>1 列でも収まらないラベルだけ省略記号付きで切り詰める (呼び出し側でツールチップを付ける)。</li>
 * </ol>
 *
 * <p>
 * MC の型に一切依存しないので、 全ロケールのラベル幅を流し込んだ単体テストで
 * 「はみ出しゼロ」 を機械的に検証できる ({@code SidePanelFitTest})。
 */
public final class SidePanelFit {

    /**
     * バニラ {@code AbstractButton.TEXT_MARGIN}。 ラベルは左右それぞれこの余白の内側に描かれる
     * (javap 実測: {@code extractDefaultLabel} が {@code extractScrollingStringOverContents(.., 2)}
     * を呼ぶ)。
     */
    public static final int TEXT_MARGIN = 2;

    private SidePanelFit() {
    }

    /** ラベル幅 {@code labelWidth} がちょうど収まるボタン幅。 */
    public static int minButtonWidth(int labelWidth) {
        return labelWidth + 2 * TEXT_MARGIN;
    }

    /** ボタン幅 {@code buttonWidth} のうち、 実際に文字を描ける幅。 */
    public static int usableTextWidth(int buttonWidth) {
        return buttonWidth - 2 * TEXT_MARGIN;
    }

    /** ラベルがそのボタン幅に収まるか (= スクロール/見切れが起きないか)。 */
    public static boolean fits(int labelWidth, int buttonWidth) {
        return labelWidth <= usableTextWidth(buttonWidth);
    }

    /** パネル幅 {@code panelWidth} を 2 列に割ったときの左セル幅 (右セルはこれ以上)。 */
    public static int cellWidth(int panelWidth, int gap) {
        return (panelWidth - gap) / 2;
    }

    /**
     * 2 列グリッドと全幅行が収まるのに必要なパネル幅を {@code [min, max]} にクランプして返す。
     *
     * @param maxGridLabelWidth 2 列セルに入る全ラベルの最大実測幅 (0 なら 2 列は無視)
     * @param maxFullLabelWidth 全幅行に入る全ラベルの最大実測幅 (0 なら全幅行は無視)
     * @param gap               2 列の間隔
     * @param min               下限 (= 従来のパネル幅。 これを下回らないので既存の見た目を壊さない)
     * @param max               上限 (= パネルが画面を占有しすぎないための頭打ち)
     */
    public static int panelWidth(int maxGridLabelWidth, int maxFullLabelWidth,
            int gap, int min, int max) {
        int forGrid = (maxGridLabelWidth > 0)
                ? 2 * minButtonWidth(maxGridLabelWidth) + gap
                : 0;
        int forFull = (maxFullLabelWidth > 0) ? minButtonWidth(maxFullLabelWidth) : 0;
        int desired = Math.max(forGrid, forFull);
        if (desired < min) {
            return min;
        }
        return Math.min(desired, Math.max(min, max));
    }

    /**
     * 2 列の行を 1 列 (縦積み) へ落とすべきか。
     *
     * <p>
     * 行内の 2 ボタンは同じ幅なので、 <b>広い方のラベル</b>がセルに収まらなければその行は縦積みにする。
     * 縦積み後は各ボタンがパネル全幅になるため、 収まる可能性が大きく上がる。
     *
     * @param leftLabelWidth  左ボタンのラベル実測幅
     * @param rightLabelWidth 右ボタンのラベル実測幅 (右が無い行は 0)
     */
    public static boolean shouldStackRow(int leftLabelWidth, int rightLabelWidth,
            int panelWidth, int gap) {
        int cell = cellWidth(panelWidth, gap);
        return !fits(Math.max(leftLabelWidth, rightLabelWidth), cell);
    }
}
