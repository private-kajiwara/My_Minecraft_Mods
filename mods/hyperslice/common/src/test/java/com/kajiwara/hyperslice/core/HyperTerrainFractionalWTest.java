package com.kajiwara.hyperslice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 連続 (小数) w の評価経路が、 <b>整数 w において従来と完全に同一</b>であることの回帰テスト。
 *
 * <p>方式B は w を 1/8 のような小数で刻む前提なので、 {@link HyperTerrain} が小数 w を
 * 受け付ける必要がある。 その追加によって <b>方式A の生成結果が 1 ビットでも変わっては
 * ならない</b> (既存ワールドの地形が変わる = 既に生成したチャンクと食い違う)。
 *
 * <p>{@link #integerSweepChecksumIsUnchanged()} が要になる。 このチェックサムは
 * <b>小数 w 対応を入れる前の実装から採取した値</b>で、 それと一致することが
 * 「整数 w の挙動が変わっていない」ことの実測の証拠になる。
 */
class HyperTerrainFractionalWTest {

    private static final long SEED = 123456789L;

    /**
     * 小数 w 対応を入れる <b>前</b> の実装 (commit 5b3fa72 時点) から採取した
     * 掃引チェックサム。 N を 6 通り・シードを 3 通り・w を ±2 周期・
     * x/z を 31x25 点で回し、 {@code noise} のビット列と {@code surfaceY} /
     * {@code isSolid} の値を畳み込んだもの。 <b>この値は更新してはならない</b> —
     * 落ちたということは整数 w の地形が変わったということ。
     */
    private static final long GOLDEN_INTEGER_SWEEP = 503660030094200896L;

    @Test
    void integerSweepChecksumIsUnchanged() {
        long h = 0xCBF29CE484222325L;
        for (int n : new int[] { 1, 2, 3, 4, 8, 16 }) {
            for (long seed : new long[] { 0L, 123456789L, -987654321L }) {
                HyperTerrain t = new HyperTerrain(seed, n);
                for (int w = -2 * n; w <= 2 * n; w++) {
                    for (int x = -200; x <= 200; x += 13) {
                        for (int z = -200; z <= 200; z += 17) {
                            h = fold(h, Double.doubleToRawLongBits(t.noise(x, z, w)));
                            h = fold(h, t.surfaceY(x, z, w));
                            h = fold(h, t.isSolid(x, 70, z, w) ? 1 : 0);
                        }
                    }
                }
            }
        }
        assertEquals(GOLDEN_INTEGER_SWEEP, h,
                "整数 w の地形が変化した。 小数 w 対応は整数 w に対して"
                        + " ビット単位で無変更でなければならない");
    }

    private static long fold(long h, long v) {
        h ^= v;
        h *= 0x100000001B3L;
        return h ^ (h >>> 29);
    }

    /** int 版と double 版が整数 w でビット単位に一致すること (実装が 1 本である証明の補強)。 */
    @Test
    void doubleOverloadMatchesIntegerOverloadExactly() {
        for (int n : new int[] { 1, 2, 3, 4, 8 }) {
            HyperTerrain t = new HyperTerrain(SEED, n);
            for (int w = -2 * n; w <= 2 * n; w++) {
                for (int x = -60; x <= 60; x += 7) {
                    for (int z = -60; z <= 60; z += 11) {
                        assertEquals(Double.doubleToRawLongBits(t.noise(x, z, w)),
                                Double.doubleToRawLongBits(t.noise(x, z, (double) w)),
                                "noise(int w) と noise(double w) はビット単位で一致すること"
                                        + " (N=" + n + ", w=" + w + ")");
                        assertEquals(t.surfaceY(x, z, w), t.surfaceY(x, z, (double) w));
                        assertEquals(t.isSolid(x, 70, z, w), t.isSolid(x, 70, z, (double) w));
                    }
                }
            }
        }
    }

    /** 小数 w でも周期 N が厳密に成立すること (w=N-ε → w=0 に壁が無い)。 */
    @Test
    void fractionalWIsExactlyPeriodic() {
        for (int n : new int[] { 2, 4, 8 }) {
            HyperTerrain t = new HyperTerrain(SEED, n);
            for (int k = -2 * n * 8; k <= 2 * n * 8; k++) {
                double w = k / 8.0;
                for (int x = -40; x <= 40; x += 13) {
                    for (int z = -40; z <= 40; z += 17) {
                        assertEquals(t.noise(x, z, w), t.noise(x, z, w + n), 0.0,
                                "小数 w でも周期は厳密であること (N=" + n + ", w=" + w + ")");
                    }
                }
            }
        }
    }

    /** 小数 w が実際に地形を動かすこと (整数格子に量子化されて潰れていない)。 */
    @Test
    void fractionalWActuallyMovesTerrain() {
        HyperTerrain t = new HyperTerrain(SEED, 8);
        boolean anyDifference = false;
        for (int x = -60; x <= 60 && !anyDifference; x += 3) {
            for (int z = -60; z <= 60; z += 3) {
                if (t.noise(x, z, 1.0) != t.noise(x, z, 1.125)) {
                    anyDifference = true;
                    break;
                }
            }
        }
        assertTrue(anyDifference, "w+0.125 は地形を変えなければならない (方式B の前提)");
    }

    /**
     * w を 1/8 ずつ進めたとき、 地表高度が「ほとんど動かない」こと。
     *
     * <p>実測 (3 シード x 全位相 x 約 140 万列): 1 ステップあたりの平均は
     * <b>0.63〜0.70 ブロック</b>、 約半数の列は <b>変化ゼロ</b>、 最悪でも
     * <b>6 ブロック</b>。 方式B が差分適用で成立するかはこの性質に懸かっているので、
     * 定数を触ってここが崩れたら気付けるようにしておく。
     */
    @Test
    void fractionalStepsAreSmall() {
        HyperTerrain t = new HyperTerrain(SEED, 8);
        int worst = 0;
        long sum = 0;
        long unchanged = 0;
        long n = 0;
        for (int k = 0; k < 64; k++) {
            for (int x = -64; x <= 64; x += 3) {
                for (int z = -64; z <= 64; z += 3) {
                    int d = Math.abs(t.surfaceY(x, z, k / 8.0) - t.surfaceY(x, z, (k + 1) / 8.0));
                    worst = Math.max(worst, d);
                    sum += d;
                    if (d == 0) {
                        unchanged++;
                    }
                    n++;
                }
            }
        }
        double mean = sum / (double) n;
        assertTrue(worst <= 8, "1/8 ステップの最悪変化が大きすぎる (worst=" + worst + ")");
        assertTrue(mean < 1.0, "1/8 ステップの平均変化が大きすぎる (mean=" + mean + ")");
        assertTrue(unchanged * 2 >= n,
                "1/8 ステップで変化しない列が半数を割った (unchanged=" + unchanged + "/" + n + ")");
    }
}
