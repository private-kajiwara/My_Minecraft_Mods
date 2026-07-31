package com.kajiwara.hyperslice.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kajiwara.hyperslice.core.HyperTerrain;

/**
 * 測定ハーネス自身の検算。 <b>測定値の信頼性はここに乗っている。</b>
 *
 * <p>{@link WDiffProbe#compare} は「変化した列だけを、 土の底〜海面/地表の窓で」
 * 走査する高速版なので、 窓が狭すぎれば変化を取りこぼす。 全 y を走査する
 * 参照実装と一致することを実データで確認する。
 */
class WDiffProbeTest {

    @Test
    void windowedCompareMatchesFullScan() {
        for (long seed : new long[] { 123456789L, -987654321L, 4815162342L }) {
            HyperTerrain t = new HyperTerrain(seed, 8);
            for (int k = 0; k < 20; k++) {
                for (int du : new int[] { 1, 2, 4, 8 }) {
                    int[] a = WDiffProbe.surfaceGrid(t, k - 10, k * 3, k / 8.0);
                    int[] b = WDiffProbe.surfaceGrid(t, k - 10, k * 3, (k + du) / 8.0);
                    assertEquals(WDiffProbe.compareFullColumns(a, b), WDiffProbe.compare(a, b),
                            "窓走査が全走査と一致しない (seed=" + seed + " k=" + k + " du=" + du + ")");
                }
            }
        }
    }

    /** ミラーした「高さ→種別」が生成側の意図どおりであること (境界の目視できる固定点)。 */
    @Test
    void stateAtMatchesGeneratorContract() {
        int minY = HyperTerrain.MIN_Y;
        int surface = 80;   // 海面 (63) より上 = 陸
        assertEquals(WDiffProbe.BEDROCK, WDiffProbe.stateAt(minY, minY, surface, false));
        assertEquals(WDiffProbe.GRASS, WDiffProbe.stateAt(surface, minY, surface, false));
        assertEquals(WDiffProbe.DIRT, WDiffProbe.stateAt(surface - 1, minY, surface, false));
        assertEquals(WDiffProbe.DIRT, WDiffProbe.stateAt(surface - 3, minY, surface, false));
        assertEquals(WDiffProbe.STONE, WDiffProbe.stateAt(surface - 4, minY, surface, false));
        assertEquals(WDiffProbe.AIR, WDiffProbe.stateAt(surface + 1, minY, surface, false));

        int seabed = 40;    // 海面より下 = 水没
        assertEquals(WDiffProbe.SAND, WDiffProbe.stateAt(seabed, minY, seabed, true));
        assertEquals(WDiffProbe.SAND, WDiffProbe.stateAt(seabed - 1, minY, seabed, true));
        assertEquals(WDiffProbe.STONE, WDiffProbe.stateAt(seabed - 4, minY, seabed, true));
        assertEquals(WDiffProbe.WATER, WDiffProbe.stateAt(seabed + 1, minY, seabed, true));
        assertEquals(WDiffProbe.WATER, WDiffProbe.stateAt(HyperTerrain.SEA_LEVEL, minY, seabed, true));
        assertEquals(WDiffProbe.AIR, WDiffProbe.stateAt(HyperTerrain.SEA_LEVEL + 1, minY, seabed, true));
    }

    /** 同一の w どうしなら差分はゼロ (測定が「何かを見つけてしまう」誤りをしていない)。 */
    @Test
    void identicalWGivesZeroDiff() {
        HyperTerrain t = new HyperTerrain(123456789L, 8);
        int[] a = WDiffProbe.surfaceGrid(t, 5, -3, 1.25);
        int[] b = WDiffProbe.surfaceGrid(t, 5, -3, 1.25);
        WDiffProbe.ChunkDiff d = WDiffProbe.compare(a, b);
        assertEquals(0, d.changedBlocks());
        assertEquals(0, d.changedColumns());
        assertEquals(0, d.changedSections());
    }

    /** 1 周期ぶん動かすと差分ゼロ (小数 w でも周期が厳密であることの、 ブロック単位での確認)。 */
    @Test
    void fullPeriodShiftGivesZeroDiff() {
        HyperTerrain t = new HyperTerrain(123456789L, 8);
        int[] a = WDiffProbe.surfaceGrid(t, 5, -3, 1.375);
        int[] b = WDiffProbe.surfaceGrid(t, 5, -3, 1.375 + 8);
        assertEquals(0, WDiffProbe.compare(a, b).changedBlocks());
    }

    /** 分布の分位点が期待どおり (中央値 / p95 / 最大の読み取りを間違えていない)。 */
    @Test
    void quantilesAreCorrect() {
        WDiffProbe.Dist d = new WDiffProbe.Dist();
        for (int i = 1; i <= 100; i++) {
            d.add(i);
        }
        assertEquals(50, d.median());
        assertEquals(95, d.p95());
        assertEquals(100, d.max());
        assertTrue(Math.abs(d.mean() - 50.5) < 1e-9);
    }
}
