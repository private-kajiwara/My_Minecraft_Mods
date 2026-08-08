package com.kajiwara.omnichest.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TextContrastFit} の単体テスト (= カテゴリ色チップ / タグの文字が読めることの回帰テスト)。
 *
 * <p>
 * <b>不具合</b>: チップは 「カテゴリ色を暗くした背景 + カテゴリ色そのものの文字」 で描かれていた。
 * {@code darken} は sRGB 符号値の線形スケールだが、 コントラスト比はガンマ復号後の輝度で決まるため
 * <b>元の色が暗いほど比が下がる</b>。 実測で concrete 27 カテゴリ中 <b>22 件</b>が 4.5:1 を割り、
 * 最悪 1.91:1 (NETHER / BUILDING_DEEPSLATE) だった。 シェーダー環境の雪原など高輝度の場面で
 * 「文字が潰れる」 と実機報告があった 6 件は、 いずれもこの下位グループに属する。
 *
 * <p>
 * <b>{@link #PALETTE} について</b>: {@code StorageCategory} は client ソースセットにあり
 * {@code common} からは参照できないため、 enum の定義値をここに写している
 * ({@code SidePanelFitTest} がラベル実測幅を写しているのと同流儀)。 <b>カテゴリを追加したら
 * この表も更新すること</b>。 ただし更新を忘れても安全側に倒れる:
 * {@link #anyColorWhatsoeverReachesTheTarget()} が RGB 空間を格子サンプルして
 * 「どんな色でも補正後は目標比を満たす」 ことを検証しているため、 未知の新色でも実行時には必ず読める。
 */
class TextContrastFitTest {

    /** {@code StorageCategory} の定義値 (name, rgb, isConcrete)。 */
    private static final Object[][] PALETTE = {
            { "BUILDING", 0xA0A0A0, true },
            { "BUILDING_STONE", 0x9A9A9A, true },
            { "BUILDING_GRANITE", 0xB0705A, true },
            { "BUILDING_DIORITE", 0xD8D8D0, true },
            { "BUILDING_ANDESITE", 0x8A8C8A, true },
            { "BUILDING_DEEPSLATE", 0x4A4A50, true },
            { "BUILDING_TUFF", 0x7C7D6E, true },
            { "BUILDING_SANDSTONE", 0xDCCFA0, true },
            { "BUILDING_PRISMARINE", 0x5FA8A0, true },
            { "BUILDING_MUD_BRICK", 0x9C7B5E, true },
            { "BUILDING_STONE_MIXED", 0x8F8B80, false },
            { "WOOD", 0x9B6B3F, true },
            { "ORE", 0xC0C0C0, true },
            { "REDSTONE", 0xD63A3A, true },
            { "REDSTONE_CIRCUIT", 0xD64545, true },
            { "REDSTONE_TRANSPORT", 0xC85A2E, true },
            { "REDSTONE_MOVEMENT", 0xB0603A, true },
            { "REDSTONE_TRAP", 0xA83A5A, true },
            { "FOOD", 0xF4B860, true },
            { "FARM", 0x6FBF3A, true },
            { "COMBAT", 0xB23B3B, true },
            { "TOOL", 0x6FA0D8, true },
            { "POTION", 0xB05DF5, true },
            { "NETHER", 0x842A2A, true },
            { "END", 0x6E59B0, true },
            { "MAGIC", 0xD862E0, true },
            { "MOB_DROP", 0x88AA66, true },
            { "DECORATION", 0xE0C97F, true },
            { "MIXED", 0x8E8E8E, false },
            { "UNKNOWN", 0x606060, false },
    };

    /**
     * 改修<b>前</b>から既に 4.5:1 を満たしていた 5 件。 これらは補正が働かず
     * 文字色が 1 ビットも変わらないことを固定する (= 見た目の非回帰)。
     */
    private static final String[] ALREADY_PASSING = {
            "BUILDING_DIORITE", "BUILDING_SANDSTONE", "ORE", "FOOD", "DECORATION",
    };

    private static int concreteCount() {
        int n = 0;
        for (Object[] row : PALETTE) {
            if ((Boolean) row[2]) {
                n++;
            }
        }
        return n;
    }

    private static int chipBg(int rgb) {
        return TextContrastFit.darken(rgb, TextContrastFit.CHIP_BG_FACTOR);
    }

    // ════════════════════════════════════════════════════════════════════
    // 中核: 27 concrete カテゴリ全件が目標比を満たす
    // ════════════════════════════════════════════════════════════════════

    @Test
    void paletteMatchesTheShippedCategorySet() {
        assertEquals(30, PALETTE.length, "StorageCategory の enum 総数");
        assertEquals(27, concreteCount(), "concrete カテゴリ数 (1.3.0 以降)");
    }

    @Test
    void everyConcreteCategoryReachesTheTargetAfterCorrection() {
        for (Object[] row : PALETTE) {
            if (!(Boolean) row[2]) {
                continue;
            }
            String name = (String) row[0];
            int rgb = (Integer) row[1];
            int bg = chipBg(rgb);
            int fixed = TextContrastFit.readableTextColor(rgb, bg);
            double ratio = TextContrastFit.contrastRatio(fixed, bg);
            assertTrue(ratio >= TextContrastFit.TARGET_RATIO,
                    name + ": 補正後も " + TextContrastFit.TARGET_RATIO + ":1 に届かない (" + ratio + ")");
        }
    }

    @Test
    void beforeCorrectionOnlyFiveCategoriesPassed() {
        // 「直った」 ことの基準点。 この数字が動いたらパレットか darken 係数が変わっている。
        int passed = 0;
        int failed = 0;
        double worst = Double.MAX_VALUE;
        String worstName = null;
        for (Object[] row : PALETTE) {
            if (!(Boolean) row[2]) {
                continue;
            }
            int rgb = (Integer) row[1];
            double ratio = TextContrastFit.contrastRatio(rgb, chipBg(rgb));
            if (ratio >= TextContrastFit.TARGET_RATIO) {
                passed++;
            } else {
                failed++;
            }
            if (ratio < worst) {
                worst = ratio;
                worstName = (String) row[0];
            }
        }
        assertEquals(5, passed, "改修前に 4.5:1 を満たしていたのは 5 件");
        assertEquals(22, failed, "改修前に 4.5:1 を割っていたのは 22 件");
        assertTrue(worst < 2.0, "最悪ケースは 2.0:1 未満だった (実測 1.91)");
        assertTrue("NETHER".equals(worstName) || "BUILDING_DEEPSLATE".equals(worstName),
                "最悪は NETHER / BUILDING_DEEPSLATE のいずれか (実測はどちらも 1.91)");
    }

    @Test
    void alreadyPassingCategoriesAreNotTouchedAtAll() {
        for (String name : ALREADY_PASSING) {
            int rgb = colorOf(name);
            int bg = chipBg(rgb);
            assertEquals(rgb, TextContrastFit.readableTextColor(rgb, bg),
                    name + ": 既に基準を満たしているのに文字色が変わった (非回帰違反)");
            assertEquals(0.0, TextContrastFit.whiteMixRatio(rgb, bg, TextContrastFit.TARGET_RATIO),
                    name + ": 白の混合が発生している");
        }
    }

    @Test
    void reportedCategoriesActuallyImprove() {
        // 実機で 「潰れる」 と報告された 6 件が、 補正で確実に基準へ乗ること。
        String[] reported = { "BUILDING_DEEPSLATE", "NETHER", "END", "MOB_DROP",
                "BUILDING_MUD_BRICK", "BUILDING_TUFF" };
        for (String name : reported) {
            int rgb = colorOf(name);
            int bg = chipBg(rgb);
            double before = TextContrastFit.contrastRatio(rgb, bg);
            double after = TextContrastFit.contrastRatio(
                    TextContrastFit.readableTextColor(rgb, bg), bg);
            assertTrue(before < TextContrastFit.TARGET_RATIO, name + ": 改修前は基準未満のはず");
            assertTrue(after >= TextContrastFit.TARGET_RATIO, name + ": 改修後は基準以上のはず");
            assertNotEquals(rgb, TextContrastFit.readableTextColor(rgb, bg),
                    name + ": 補正が働いていない");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 色相が保たれる (= カテゴリの見分けがつかなくならない)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 色相のずれの許容値。 白との混合は連続値では色相を厳密に保つが、 結果を 8bit 整数へ丸める
     * ため 1LSB 未満の誤差が乗る。 彩度が低い色ほど {@code max - min} が小さく、 同じ丸め誤差が
     * 大きな角度差に見える (そしてその領域では色相自体が知覚的にほぼ無意味)。
     * 実測の最大ずれは <b>彩度 32 以上で 0.447 度 / 32 未満で 1.000 度</b>。
     */
    private static final double HUE_TOLERANCE_CHROMATIC = 1.0;
    private static final double HUE_TOLERANCE_NEAR_GRAY = 2.0;

    @Test
    void correctionPreservesHue() {
        for (Object[] row : PALETTE) {
            String name = (String) row[0];
            int rgb = (Integer) row[1];
            int bg = chipBg(rgb);
            int fixed = TextContrastFit.readableTextColor(rgb, bg);
            double h0 = TextContrastFit.hue(rgb);
            double h1 = TextContrastFit.hue(fixed);
            if (h0 < 0) {
                assertTrue(h1 < 0, name + ": 無彩色が有彩色になった");
                continue;
            }
            assertTrue(h1 >= 0, name + ": 有彩色が無彩色になった");
            double d = Math.abs(h0 - h1);
            d = Math.min(d, 360.0 - d);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            double tol = chroma >= 32 ? HUE_TOLERANCE_CHROMATIC : HUE_TOLERANCE_NEAR_GRAY;
            assertTrue(d <= tol,
                    name + ": 色相が " + d + " 度ずれた (chroma=" + chroma + ", h " + h0 + " -> " + h1 + ")");
        }
    }

    @Test
    void mixingWithWhiteScalesChannelDifferencesUniformly() {
        // 色相が保たれる理由そのものを固定する:
        // 任意の 2 チャンネル差は (1 - t) 倍に等しくスケールし、 大小関係も変わらない。
        int rgb = 0x842A2A;                                  // NETHER (最悪ケース)
        for (int i = 0; i <= 100; i += 5) {
            double t = i / 100.0;
            int m = TextContrastFit.mixWithWhite(rgb, t);
            int r0 = (rgb >> 16) & 0xFF, g0 = (rgb >> 8) & 0xFF, b0 = rgb & 0xFF;
            int r1 = (m >> 16) & 0xFF, g1 = (m >> 8) & 0xFF, b1 = m & 0xFF;
            assertTrue(r1 >= g1 && r1 >= b1, "t=" + t + " で最大チャンネルが入れ替わった");
            assertEquals((r0 - g0) * (1 - t), r1 - g1, 1.0, "r-g のスケールが崩れた t=" + t);
            assertEquals((r0 - b0) * (1 - t), r1 - b1, 1.0, "r-b のスケールが崩れた t=" + t);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 将来のカテゴリ追加に対する保証 (= パレット表の更新を忘れても安全)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void anyColorWhatsoeverReachesTheTarget() {
        // RGB 空間を格子サンプル (17^3 = 4913 色) して、 チップ背景式に対し必ず届くことを確認。
        int checked = 0;
        for (int r = 0; r <= 255; r += 16) {
            for (int g = 0; g <= 255; g += 16) {
                for (int b = 0; b <= 255; b += 16) {
                    int rgb = (r << 16) | (g << 8) | b;
                    int bg = chipBg(rgb);
                    double ratio = TextContrastFit.contrastRatio(
                            TextContrastFit.readableTextColor(rgb, bg), bg);
                    assertTrue(ratio >= TextContrastFit.TARGET_RATIO,
                            String.format("#%06X で届かない (%.2f)", rgb, ratio));
                    checked++;
                }
            }
        }
        assertTrue(checked > 4000, "サンプル数が少なすぎる: " + checked);
    }

    @Test
    void tagBackgroundFactorAlsoAlwaysReachesTheTarget() {
        // カテゴリタグ (renderTag) は不透明化した TAG_BG_FACTOR で描く。 こちらも全域で到達可能。
        for (int r = 0; r <= 255; r += 32) {
            for (int g = 0; g <= 255; g += 32) {
                for (int b = 0; b <= 255; b += 32) {
                    int rgb = (r << 16) | (g << 8) | b;
                    int bg = TextContrastFit.darken(rgb, TextContrastFit.TAG_BG_FACTOR);
                    double ratio = TextContrastFit.contrastRatio(
                            TextContrastFit.readableTextColor(rgb, bg), bg);
                    assertTrue(ratio >= TextContrastFit.TARGET_RATIO,
                            String.format("tag #%06X で届かない (%.2f)", rgb, ratio));
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 基礎関数の健全性
    // ════════════════════════════════════════════════════════════════════

    @Test
    void contrastRatioMatchesKnownValues() {
        // WCAG の既知値で校正する。
        assertEquals(21.0, TextContrastFit.contrastRatio(0xFFFFFF, 0x000000), 0.001);
        assertEquals(1.0, TextContrastFit.contrastRatio(0x808080, 0x808080), 0.001);
        assertEquals(TextContrastFit.contrastRatio(0xFF0000, 0x000000),
                TextContrastFit.contrastRatio(0x000000, 0xFF0000), 1e-12, "順序に依存しない");
        assertEquals(0.0, TextContrastFit.relativeLuminance(0x000000), 1e-12);
        assertEquals(1.0, TextContrastFit.relativeLuminance(0xFFFFFF), 1e-9);
    }

    @Test
    void darkenMatchesTheRendererFormulaBitForBit() {
        // CategoryBadgeRenderer#darken と同一であること (切り捨てまで含めて)。
        for (Object[] row : PALETTE) {
            int rgb = (Integer) row[1];
            int r = (int) (((rgb >> 16) & 0xFF) * TextContrastFit.CHIP_BG_FACTOR);
            int g = (int) (((rgb >> 8) & 0xFF) * TextContrastFit.CHIP_BG_FACTOR);
            int b = (int) ((rgb & 0xFF) * TextContrastFit.CHIP_BG_FACTOR);
            assertEquals((r << 16) | (g << 8) | b, chipBg(rgb), (String) row[0]);
        }
        assertEquals(0.40f, TextContrastFit.CHIP_BG_FACTOR, 0.0f, "減光係数は変えていない");
        assertEquals(0.55f, TextContrastFit.CHIP_BG_FACTOR_HOVER, 0.0f);
    }

    @Test
    void mixWithWhiteEndpointsAreExact() {
        assertEquals(0x842A2A, TextContrastFit.mixWithWhite(0x842A2A, 0.0));
        assertEquals(0xFFFFFF, TextContrastFit.mixWithWhite(0x842A2A, 1.0));
        assertEquals(0xFFFFFF, TextContrastFit.mixWithWhite(0xFFFFFF, 0.0));
    }

    @Test
    void correctionIsIdempotent() {
        // 一度補正した色をもう一度通しても動かない (= 二重適用しても壊れない)。
        for (Object[] row : PALETTE) {
            int rgb = (Integer) row[1];
            int bg = chipBg(rgb);
            int once = TextContrastFit.readableTextColor(rgb, bg);
            assertEquals(once, TextContrastFit.readableTextColor(once, bg), (String) row[0]);
        }
    }

    @Test
    void correctionPicksTheSmallestSufficientMix() {
        // 「必要以上に薄めない」 = 1 段手前では基準を割ること。
        for (Object[] row : PALETTE) {
            String name = (String) row[0];
            int rgb = (Integer) row[1];
            int bg = chipBg(rgb);
            double t = TextContrastFit.whiteMixRatio(rgb, bg, TextContrastFit.TARGET_RATIO);
            if (t <= 0.0) {
                continue;
            }
            double prev = TextContrastFit.contrastRatio(
                    TextContrastFit.mixWithWhite(rgb, t - 0.01), bg);
            assertTrue(prev < TextContrastFit.TARGET_RATIO,
                    name + ": 1 段手前でも基準を満たしている (薄めすぎ)");
        }
    }

    private static int colorOf(String name) {
        for (Object[] row : PALETTE) {
            if (row[0].equals(name)) {
                return (Integer) row[1];
            }
        }
        throw new IllegalArgumentException(name);
    }
}
