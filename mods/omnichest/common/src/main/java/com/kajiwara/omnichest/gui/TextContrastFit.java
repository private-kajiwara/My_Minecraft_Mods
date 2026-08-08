package com.kajiwara.omnichest.gui;

/**
 * 「その背景の上で確実に読める文字色」 を求める純粋関数。 Minecraft 型に一切依存しないため
 * {@code common} 側に置き、 単体テスト可能にしている ({@link GuiScaleFit} / {@link SidePanelFit} /
 * {@link ExistingCategoriesFit} と同じ流儀)。
 *
 * <p>
 * <b>背景 (= なぜ必要か)</b>: カテゴリ色チップ ({@code CategoryBadgeRenderer#renderCategoryChip}) は
 * 「カテゴリ色を暗くした背景 + カテゴリ色そのものの文字」 で描かれていた。 {@code darken} は sRGB の
 * 符号値を線形に 0.40 倍する演算だが、 コントラスト比はガンマ復号後の<b>輝度</b>で決まるため、
 * 元の色が暗いほど背景と文字の輝度差が縮む。 実測では concrete 27 カテゴリ中 <b>22 件</b>が
 * WCAG AA (4.5:1) を下回り、 最悪は NETHER / BUILDING_DEEPSLATE の <b>1.91:1</b> だった
 * (シェーダー環境・雪原など高輝度の場面で 「文字が潰れる」 と実機報告あり)。
 *
 * <p>
 * <b>方式</b>: <b>カテゴリの識別色 ({@code StorageCategory#rgb()}) は一切変更しない</b>。
 * チップの背景・枠・在庫バッジは従来どおりのカテゴリ色のまま、 <b>文字を描くときの色だけ</b>を
 * 目標比に届くまで白へ寄せる ({@link #readableTextColor})。
 *
 * <p>
 * <b>不変条件 (invariant)</b>:
 * <ul>
 *   <li><b>色相 (hue) は厳密に保たれる</b>。 白との混合は各チャンネルを
 *       {@code c + (255 - c) * t} に写す。 任意の 2 チャンネル差は {@code (1 - t)} 倍に等しく
 *       スケールし、 大小関係も変わらないため、 HSV の色相
 *       ({@code (差) / (max - min)} で定まる) は数学的に不変になる (彩度だけが下がる)。
 *       → 「赤系のカテゴリは赤系の文字のまま」 が保証される。</li>
 *   <li>既に目標比を満たしている色は<b>1 ビットも変えない</b> ({@code t = 0} を返す)。
 *       = 従来から読めていたカテゴリは完全に非回帰。</li>
 *   <li>返り値は必ず目標比を満たす。 チップの背景式 {@code darken(rgb, 0.40)} に対しては、
 *       最悪ケース (元色が純白 → 背景 {@code #666666}) でも白の比が 5.74:1 あるため、
 *       <b>任意の入力色で到達可能</b> ({@code TextContrastFitTest} が全域サンプルで検証)。</li>
 * </ul>
 *
 * <p>
 * <b>外すと何が壊れるか</b>: 呼び出し側が自前で色を計算し始めると、 単体テストが実挙動を
 * 保証しなくなる。 文字色の決定は<b>必ずここを通す</b>こと。
 */
public final class TextContrastFit {

    private TextContrastFit() {
    }

    /** 目標コントラスト比 (= WCAG 2.1 AA の通常文字)。 */
    public static final double TARGET_RATIO = 4.5;

    /**
     * カテゴリ色チップの背景に使う減光係数 (= {@code CategoryBadgeRenderer} の通常時と同値)。
     * <b>この値は変更しない</b> (見た目の非回帰。 変えるのは文字色だけ)。
     */
    public static final float CHIP_BG_FACTOR = 0.40f;
    /** ホバー時の減光係数 (= 同上)。 */
    public static final float CHIP_BG_FACTOR_HOVER = 0.55f;
    /**
     * カテゴリタグ ({@code CategoryBadgeRenderer#renderTag}) の背景に使う減光係数。
     * 旧実装は {@code 0x80 | rgb} の<b>半透明</b>だったため実効背景が背後依存になり、
     * コントラストを保証できなかった。 黒地に 50% で載せたのと同じ見えになる 0.50 を
     * <b>不透明</b>で使うことで、 暗い背景の上での見た目をほぼ保ったまま比を確定させる。
     */
    public static final float TAG_BG_FACTOR = 0.50f;

    /** 白へ寄せる混合率の刻み (= {@code 1/STEPS})。 決定論のため整数刻みに固定する。 */
    private static final int STEPS = 100;

    // ════════════════════════════════════════════════════════════════════
    // WCAG 2.1 の相対輝度 / コントラスト比
    // ════════════════════════════════════════════════════════════════════

    /** sRGB の 1 チャンネル (0..255) を線形値へ復号する。 */
    private static double toLinear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG 2.1 の相対輝度 (0..1)。 引数は {@code 0x00RRGGBB} (alpha は無視)。 */
    public static double relativeLuminance(int rgb) {
        return 0.2126 * toLinear((rgb >> 16) & 0xFF)
                + 0.7152 * toLinear((rgb >> 8) & 0xFF)
                + 0.0722 * toLinear(rgb & 0xFF);
    }

    /** WCAG 2.1 のコントラスト比 (1.0 .. 21.0)。 引数の順序は結果に影響しない。 */
    public static double contrastRatio(int rgbA, int rgbB) {
        double la = relativeLuminance(rgbA);
        double lb = relativeLuminance(rgbB);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    // ════════════════════════════════════════════════════════════════════
    // 色の操作
    // ════════════════════════════════════════════════════════════════════

    /**
     * 各チャンネルを {@code f} 倍して暗くする。
     * {@code CategoryBadgeRenderer#darken} と<b>ビット単位で同一の式</b>
     * (切り捨て {@code (int)} キャストまで含めて一致させてある)。
     */
    public static int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    /** {@code rgb} を白へ {@code t} (0..1) の割合で寄せる。 {@code t == 0} なら入力そのまま。 */
    public static int mixWithWhite(int rgb, double t) {
        int r = mixChannel((rgb >> 16) & 0xFF, t);
        int g = mixChannel((rgb >> 8) & 0xFF, t);
        int b = mixChannel(rgb & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int mixChannel(int c, double t) {
        return (int) Math.round(c + (255 - c) * t);
    }

    // ════════════════════════════════════════════════════════════════════
    // 本体
    // ════════════════════════════════════════════════════════════════════

    /**
     * {@code bgRgb} の上で {@link #TARGET_RATIO} を満たす文字色を返す。
     * 既に満たしていれば {@code textRgb} を<b>そのまま</b>返す。
     */
    public static int readableTextColor(int textRgb, int bgRgb) {
        return readableTextColor(textRgb, bgRgb, TARGET_RATIO);
    }

    /**
     * {@code bgRgb} の上で {@code target} を満たすまで {@code textRgb} を白へ寄せた色を返す。
     *
     * <p>
     * 色相は厳密に保たれる (クラス javadoc の不変条件を参照)。 刻みは {@code 1/100} 固定で、
     * 目標を満たす<b>最小</b>の混合率を選ぶ (= 必要以上に色を薄めない)。 万一 100% (= 純白) でも
     * 届かない背景なら純白を返す (これ以上できることが無いため)。
     */
    public static int readableTextColor(int textRgb, int bgRgb, double target) {
        int rgb = textRgb & 0xFFFFFF;
        int bg = bgRgb & 0xFFFFFF;
        for (int i = 0; i <= STEPS; i++) {
            int candidate = mixWithWhite(rgb, i / (double) STEPS);
            if (contrastRatio(candidate, bg) >= target) {
                return candidate;
            }
        }
        return 0xFFFFFF;
    }

    /**
     * 目標を満たすのに要した白の混合率 (0.0 .. 1.0)。 診断 / テスト用。
     * {@code 0.0} なら元の色のままで足りている。
     */
    public static double whiteMixRatio(int textRgb, int bgRgb, double target) {
        int rgb = textRgb & 0xFFFFFF;
        int bg = bgRgb & 0xFFFFFF;
        for (int i = 0; i <= STEPS; i++) {
            if (contrastRatio(mixWithWhite(rgb, i / (double) STEPS), bg) >= target) {
                return i / (double) STEPS;
            }
        }
        return 1.0;
    }

    /**
     * HSV の色相 (0..360)。 無彩色 ({@code max == min}) は {@code -1} を返す。
     * 「補正しても色相が変わらない」 ことをテストで機械検証するためのもの。
     */
    public static double hue(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int d = max - min;
        if (d == 0) {
            return -1.0;
        }
        double h;
        if (max == r) {
            h = 60.0 * (((g - b) / (double) d) % 6.0);
        } else if (max == g) {
            h = 60.0 * ((b - r) / (double) d + 2.0);
        } else {
            h = 60.0 * ((r - g) / (double) d + 4.0);
        }
        return h < 0 ? h + 360.0 : h;
    }
}
