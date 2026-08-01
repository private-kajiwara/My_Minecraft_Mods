package com.kajiwara.hyperslice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 4D 地形関数の性質を runClient 無しで検証する。
 *
 * <p>ここが守っているのは v0.1 の中核要件:
 * <ol>
 *   <li>w 方向が周期 N で <b>厳密に</b> 周期的 (w=N-1 → w=0 に壁が無い)</li>
 *   <li>スライス間に相関がある (別世界ではない)</li>
 *   <li>それでいて同一ではない (4 次元性が潰れていない)</li>
 * </ol>
 */
class HyperTerrainTest {

    private static final long SEED = 123456789L;

    // ── 1. 厳密な w 周期性 ──────────────────────────────────────

    @Test
    void heightIsExactlyPeriodicInW() {
        for (int n : new int[] { 2, 3, 4, 8 }) {
            HyperTerrain t = new HyperTerrain(SEED, n);
            for (int w = -2 * n; w <= 2 * n; w++) {
                for (int x = -40; x <= 40; x += 7) {
                    for (int z = -40; z <= 40; z += 11) {
                        assertEquals(t.noise(x, z, w), t.noise(x, z, w + n),
                                0.0, // ビット単位で一致すること (許容誤差ゼロ)
                                "noise must be exactly periodic in w (N=" + n
                                        + ", x=" + x + ", z=" + z + ", w=" + w + ")");
                    }
                }
            }
        }
    }

    @Test
    void wLatticeIndexWrapsExactly() {
        HyperTerrain t = new HyperTerrain(SEED, 8);
        int period = HyperTerrain.wLatticeCount(8);
        for (int w = -16; w <= 16; w++) {
            int[] a = t.wLattice(w, period);
            int[] b = t.wLattice(w + 8, period);
            assertEquals(a[0], b[0], "lattice index must wrap at w+N");
            assertEquals(a[1], b[1], "lattice fraction must be identical at w+N");
        }
    }

    /** w=N-1 から w=0 への段差が、 他の隣接ステップと同程度であること (= 壁が無い)。 */
    @Test
    void seamStepIsNotLargerThanOtherSteps() {
        int n = 8;
        HyperTerrain t = new HyperTerrain(SEED, n);

        double seam = meanAbsDelta(t, n - 1, 0);
        double worstInterior = 0.0;
        for (int w = 0; w < n - 1; w++) {
            worstInterior = Math.max(worstInterior, meanAbsDelta(t, w, w + 1));
        }
        assertTrue(seam <= worstInterior * 1.5,
                "w=N-1 -> w=0 must not be a discontinuity: seam=" + seam
                        + " worstInterior=" + worstInterior);
    }

    // ── 2. スライス間の相関 ────────────────────────────────────

    /** 隣接スライスは「別世界」ではなく、 無関係な 2 スライスよりずっと似ている。 */
    @Test
    void adjacentSlicesAreMoreCorrelatedThanDistantOnes() {
        int n = 8;
        HyperTerrain t = new HyperTerrain(SEED, n);

        double adjacent = meanAbsDelta(t, 0, 1);
        double opposite = meanAbsDelta(t, 0, n / 2);

        assertTrue(adjacent < opposite,
                "adjacent slices must differ less than opposite ones: adjacent="
                        + adjacent + " opposite=" + opposite);
    }

    /** 隣接スライスは同一ではない (w 方向が定数に潰れていない)。 */
    @Test
    void adjacentSlicesAreNotIdentical() {
        for (int n : new int[] { 2, 4, 8 }) {
            HyperTerrain t = new HyperTerrain(SEED, n);
            assertTrue(meanAbsDelta(t, 0, 1) > 1e-6,
                    "w must actually vary the terrain (N=" + n + ")");
        }
    }

    /** N=2 でも縮退しない (格子点数が 1 に落ちないこと)。 */
    @Test
    void smallSliceCountDoesNotDegenerate() {
        assertEquals(2, HyperTerrain.wLatticeCount(2));
        assertEquals(2, HyperTerrain.wLatticeCount(4));
        assertEquals(4, HyperTerrain.wLatticeCount(8));
        assertEquals(1, HyperTerrain.wLatticeCount(1));
    }

    // ── 3. 決定論性・値域 ──────────────────────────────────────

    @Test
    void isDeterministic() {
        HyperTerrain a = new HyperTerrain(SEED, 8);
        HyperTerrain b = new HyperTerrain(SEED, 8);
        for (int x = -20; x <= 20; x += 3) {
            for (int z = -20; z <= 20; z += 3) {
                assertEquals(a.surfaceY(x, z, 3), b.surfaceY(x, z, 3));
            }
        }
    }

    @Test
    void differentSeedsGiveDifferentTerrain() {
        HyperTerrain a = new HyperTerrain(SEED, 8);
        HyperTerrain b = new HyperTerrain(SEED + 1, 8);
        boolean anyDifferent = false;
        for (int x = -20; x <= 20 && !anyDifferent; x += 3) {
            for (int z = -20; z <= 20; z += 3) {
                if (a.surfaceY(x, z, 0) != b.surfaceY(x, z, 0)) {
                    anyDifferent = true;
                    break;
                }
            }
        }
        assertTrue(anyDifferent, "different seeds must produce different terrain");
    }

    @Test
    void noiseStaysInRange() {
        HyperTerrain t = new HyperTerrain(SEED, 8);
        for (int w = 0; w < 8; w++) {
            for (int x = -100; x <= 100; x += 5) {
                for (int z = -100; z <= 100; z += 5) {
                    double v = t.noise(x, z, w);
                    assertTrue(v >= -1.0001 && v <= 1.0001, "noise out of range: " + v);
                }
            }
        }
    }

    @Test
    void surfaceStaysInsideWorld() {
        HyperTerrain t = new HyperTerrain(SEED, 8);
        for (int w = 0; w < 8; w++) {
            for (int x = -100; x <= 100; x += 5) {
                for (int z = -100; z <= 100; z += 5) {
                    int y = t.surfaceY(x, z, w);
                    assertTrue(y > HyperTerrain.MIN_Y
                                    && y < HyperTerrain.MIN_Y + HyperTerrain.WORLD_HEIGHT,
                            "surface outside world bounds: " + y);
                }
            }
        }
    }

    @Test
    void isSolidAgreesWithSurface() {
        HyperTerrain t = new HyperTerrain(SEED, 4);
        int y = t.surfaceY(10, 10, 1);
        assertTrue(t.isSolid(10, y, 10, 1));
        assertTrue(!t.isSolid(10, y + 1, 10, 1));
        assertTrue(t.isSolid(new HyperCoord(10, y, 10, 1)));
    }

    @Test
    void rejectsInvalidSliceCount() {
        try {
            new HyperTerrain(SEED, 0);
            assertTrue(false, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ── ヘルパ ────────────────────────────────────────────────

    /** 2 スライス間の平均絶対差 (地形の「違い」の指標)。 */
    private static double meanAbsDelta(HyperTerrain t, int wa, int wb) {
        double sum = 0.0;
        int count = 0;
        for (int x = -64; x <= 64; x += 4) {
            for (int z = -64; z <= 64; z += 4) {
                sum += Math.abs(t.noise(x, z, wa) - t.noise(x, z, wb));
                count++;
            }
        }
        return sum / count;
    }

    // ── SliceRegistry ────────────────────────────────────────

    @Test
    void sliceIdRoundTrips() {
        for (int w = 0; w < 8; w++) {
            assertEquals(w, SliceRegistry.wFromPath(SliceRegistry.slicePath(w)));
        }
        assertEquals("hyperslice:slice_3", SliceRegistry.sliceId(3));
        assertEquals(-1, SliceRegistry.wFromPath("overworld"));
        assertEquals(-1, SliceRegistry.wFromPath("slice_abc"));
        assertEquals(-1, SliceRegistry.wFromPath(null));
        assertNotEquals(-1, SliceRegistry.wFromPath("slice_0"));
    }

    @Test
    void wrapCyclesBothDirections() {
        assertEquals(0, SliceRegistry.wrap(8, 8));
        assertEquals(7, SliceRegistry.wrap(-1, 8));
        assertEquals(1, SliceRegistry.wrap(9, 8));
        assertEquals(0, SliceRegistry.wrap(0, 1));
    }
}
