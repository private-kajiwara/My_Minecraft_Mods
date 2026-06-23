package com.kajiwara.visualizegate.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PortalLinkResolverTest {

    private static final int NETHER_MIN = 0;
    private static final int NETHER_MAX = 127;
    private static final double NETHER_RADIUS = 16;

    private static DomainPortal nether(int x, int y, int z, boolean live, long tick) {
        return new DomainPortal(PortalDimension.NETHER, new GridPos(x, y, z), live, tick);
    }

    @Test
    void idealTargetIsScaledProjection() {
        // OW(800,70,160) -> Nether ideal (100,70,20)。
        LinkPrediction pred = PortalLinkResolver.predict(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(), NETHER_RADIUS, p -> false);
        assertEquals(new GridPos(100, 70, 20), pred.idealTarget());
    }

    @Test
    void linkedWhenKnownPortalWithinRadius() {
        // ideal=(100,70,20)、 半径16 内 (105,70,22) に既知ポータル → LINKED。
        DomainPortal near = nether(105, 70, 22, true, 1000L);
        LinkPrediction pred = PortalLinkResolver.predict(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(near), NETHER_RADIUS, p -> true);
        assertEquals(PredictedLinkState.LINKED, pred.state());
        assertTrue(pred.matched().isPresent());
        assertEquals(near, pred.matched().get());
        assertTrue(pred.offsetDistance() > 0 && pred.offsetDistance() <= NETHER_RADIUS);
    }

    @Test
    void willCreateWhenObservedButNoPortalInRadius() {
        // 半径外 (200,70,20) のみ → 観測済みなら WILL_CREATE (赤)。
        DomainPortal far = nether(200, 70, 20, true, 1000L);
        LinkPrediction pred = PortalLinkResolver.predict(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(far), NETHER_RADIUS, p -> true);
        assertEquals(PredictedLinkState.WILL_CREATE, pred.state());
        assertTrue(pred.matched().isEmpty());
    }

    @Test
    void unknownWhenTargetRegionNotObserved() {
        // 既知ポータル無し かつ 未観測 → UNKNOWN (灰)。 嘘の赤を出さない。
        LinkPrediction pred = PortalLinkResolver.predict(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(), NETHER_RADIUS, p -> false);
        assertEquals(PredictedLinkState.UNKNOWN, pred.state());
        assertTrue(pred.matched().isEmpty());
    }

    @Test
    void nearestPortalChosenAmongMultipleInRadius() {
        DomainPortal a = nether(110, 70, 20, true, 1L);   // dist 10
        DomainPortal b = nether(103, 70, 20, true, 2L);   // dist 3 (nearest)
        LinkPrediction pred = PortalLinkResolver.predict(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(a, b), NETHER_RADIUS, p -> true);
        assertEquals(PredictedLinkState.LINKED, pred.state());
        assertEquals(b, pred.matched().get());
    }

    @Test
    void portalsInWrongDimensionAreIgnored() {
        DomainPortal owGhost = new DomainPortal(PortalDimension.OVERWORLD, new GridPos(100, 70, 20), true, 1L);
        LinkPrediction pred = PortalLinkResolver.predict(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(owGhost), NETHER_RADIUS, p -> true);
        assertEquals(PredictedLinkState.WILL_CREATE, pred.state()); // OW のゴーストは Nether 探索で無視
    }

    @Test
    void ghostBuildPositionProjectsOtherDimPortalIntoCurrentDim() {
        // C-1: Nether の既知ポータル P_other(100,70,20) を OW へ射影 → (800,70,160)。
        // ここに OW で建てれば渡った先で P_other 近傍へリンクする (ズレ無し建設位置)。
        DomainPortal pOther = nether(100, 70, 20, true, 1L);
        GridPos ghost = PortalLinkResolver.ghostBuildPosition(pOther, PortalDimension.OVERWORLD, -64, 319);
        assertEquals(new GridPos(800, 70, 160), ghost);
    }

    @Test
    void observationPredicateReceivesIdealTarget() {
        // 述語には算出した理想ターゲットが渡る (= 領域単位の観測判定がその座標で行える)。
        GridPos[] seen = new GridPos[1];
        PortalLinkResolver.predict(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(), NETHER_RADIUS, p -> { seen[0] = p; return false; });
        assertEquals(new GridPos(100, 70, 20), seen[0]);
    }

    @Test
    void staleMemoryFlagIsCarried() {
        DomainPortal remembered = nether(105, 70, 22, false, 50L); // liveConfirmed=false (記憶のみ)
        LinkPrediction pred = PortalLinkResolver.predict(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(remembered), NETHER_RADIUS, p -> true);
        assertEquals(PredictedLinkState.LINKED, pred.state());
        assertFalse(pred.matched().get().liveConfirmed()); // 記憶（古い可能性）であることがデータ上残る
    }
}
