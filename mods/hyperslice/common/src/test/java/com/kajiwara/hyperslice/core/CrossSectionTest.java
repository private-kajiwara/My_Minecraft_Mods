package com.kajiwara.hyperslice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 断面半径の数学を検証する。
 *
 * <p>ここが守るのは v0.2 の中核: 4 次元球の 3 次元断面が
 * <b>dw=0 で最大 / 端で 0 に収束 / 途中は連続</b>であること。
 * この性質があるからこそアルファフェードが要らない。
 */
class CrossSectionTest {

    private static final double EPS = 1e-9;
    private static final double THICKNESS = 2.0;   // → w 方向半径 R = 1.0

    // ── 基本形 ──────────────────────────────────────────────────

    @Test
    void radiusIsMaximalAtPlane() {
        // dw=0 では断面が最大 = 4次元球の半径そのもの
        assertEquals(THICKNESS / 2.0, CrossSection.radius(THICKNESS, 0.0), EPS);
    }

    @Test
    void radiusIsZeroAtAndBeyondEdge() {
        double r = THICKNESS / 2.0;
        assertEquals(0.0, CrossSection.radius(THICKNESS, r), EPS);
        assertEquals(0.0, CrossSection.radius(THICKNESS, -r), EPS);
        assertEquals(0.0, CrossSection.radius(THICKNESS, r + 0.5), EPS);
        assertEquals(0.0, CrossSection.radius(THICKNESS, -r - 0.5), EPS);
        assertEquals(0.0, CrossSection.radius(THICKNESS, 1000.0), EPS);
    }

    /** 断面は球なので、 半径は円の方程式 r^2 + dw^2 = R^2 を満たす。 */
    @Test
    void radiusSatisfiesSphereEquation() {
        double R = THICKNESS / 2.0;
        for (double dw = -R; dw <= R; dw += R / 64.0) {
            double r = CrossSection.radius(THICKNESS, dw);
            assertEquals(R * R, r * r + dw * dw, 1e-9,
                    "cross-section must lie on the 4D sphere (dw=" + dw + ")");
        }
    }

    @Test
    void radiusIsSymmetricInDw() {
        for (double dw = 0.0; dw <= 1.0; dw += 0.05) {
            assertEquals(CrossSection.radius(THICKNESS, dw),
                    CrossSection.radius(THICKNESS, -dw), EPS,
                    "radius must be symmetric about the observation plane");
        }
    }

    @Test
    void radiusDecreasesMonotonicallyAwayFromPlane() {
        double prev = CrossSection.radius(THICKNESS, 0.0);
        for (double dw = 0.02; dw <= 1.2; dw += 0.02) {
            double r = CrossSection.radius(THICKNESS, dw);
            assertTrue(r <= prev + EPS,
                    "radius must not increase as |dw| grows (dw=" + dw + ")");
            prev = r;
        }
    }

    /** 連続性: 微小な dw 変化で半径が飛ばないこと (= 突然消えたりしない)。 */
    @Test
    void radiusIsContinuous() {
        double step = 1e-4;
        double prev = CrossSection.radius(THICKNESS, -1.5);
        for (double dw = -1.5 + step; dw <= 1.5; dw += step) {
            double r = CrossSection.radius(THICKNESS, dw);
            // 端 (dw = ±R) では傾きが無限大になるので、 そこだけ緩い上限にする。
            double tolerance = Math.abs(Math.abs(dw) - 1.0) < 0.01 ? 0.05 : 0.01;
            assertTrue(Math.abs(r - prev) < tolerance,
                    "radius jumped at dw=" + dw + " (" + prev + " -> " + r + ")");
            prev = r;
        }
    }

    // ── 退化ケース (例外を投げないこと) ──────────────────────────

    @Test
    void degenerateThicknessGivesZeroAndDoesNotThrow() {
        assertEquals(0.0, CrossSection.radius(0.0, 0.0), EPS);
        assertEquals(0.0, CrossSection.radius(-1.0, 0.0), EPS);
        assertEquals(0.0, CrossSection.radius(Double.NaN, 0.0), EPS);
    }

    @Test
    void nanDwGivesZeroAndDoesNotThrow() {
        assertEquals(0.0, CrossSection.radius(THICKNESS, Double.NaN), EPS);
        assertFalse(CrossSection.intersects(THICKNESS, Double.NaN, 1.0));
    }

    @Test
    void radiusIsNeverNegativeOrNaN() {
        for (double dw = -5.0; dw <= 5.0; dw += 0.01) {
            double r = CrossSection.radius(THICKNESS, dw);
            assertFalse(Double.isNaN(r), "radius must never be NaN (dw=" + dw + ")");
            assertTrue(r >= 0.0, "radius must never be negative (dw=" + dw + ")");
        }
    }

    // ── 交差判定 ────────────────────────────────────────────────

    @Test
    void intersectsMatchesRadiusWithoutMargin() {
        for (double dw = -2.0; dw <= 2.0; dw += 0.01) {
            boolean hit = CrossSection.intersects(THICKNESS, dw, 0.0);
            boolean visible = CrossSection.radius(THICKNESS, dw) > 0.0;
            assertEquals(visible, hit,
                    "intersects(margin=0) must agree with radius>0 (dw=" + dw + ")");
        }
    }

    @Test
    void marginWidensIntersection() {
        // 厚み 2.0 → 半径 1.0。 dw=1.5 は交差しないが、 マージン 1.0 なら送信対象に入る。
        assertFalse(CrossSection.intersects(THICKNESS, 1.5, 0.0));
        assertTrue(CrossSection.intersects(THICKNESS, 1.5, 1.0));
        assertFalse(CrossSection.intersects(THICKNESS, 2.5, 1.0));
    }

    // ── 観測面の規約 ────────────────────────────────────────────

    @Test
    void observationPlaneIsSliceCentre() {
        // ブロックは w ∈ [n, n+1) を占めるので、 観測面は層の中心 n+0.5
        assertEquals(0.5, CrossSection.observationPlane(0), EPS);
        assertEquals(3.5, CrossSection.observationPlane(3), EPS);
        assertEquals(-0.5, CrossSection.observationPlane(-1), EPS);
    }

    /** スライス n の観測者から見て、 w=n+0.5 にいる物体の断面が最大になる。 */
    @Test
    void entityAtPlaneCentreIsFullSize() {
        int slice = 3;
        double plane = CrossSection.observationPlane(slice);
        HyperEntityRecord r = new HyperEntityRecord(
                java.util.UUID.randomUUID(), HyperEntityType.DRIFTER,
                new HyperVec(0, 64, 0, plane), HyperVec.ZERO);
        assertEquals(HyperEntityType.DRIFTER.wThickness() / 2.0,
                r.crossSectionRadius(plane), EPS);
        assertEquals(0.0, r.dw(plane), EPS);
    }
}
