package com.kajiwara.visualizegate.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** ㉕ 逆算 ({@link BackCalc}) のユニットテスト (向き・×8/÷8・既存/新規判定)。 */
class BackCalcTest {

    private static final int OW_MIN = -64;
    private static final int OW_MAX = 319;
    private static final int NETHER_MIN = 0;
    private static final int NETHER_MAX = 127;
    private static final double OW_RADIUS = 128;
    private static final double NETHER_RADIUS = 16;

    private static DomainPortal nether(int x, int y, int z) {
        return new DomainPortal(PortalDimension.NETHER, new GridPos(x, y, z), true, 1L);
    }

    @Test
    void overworldPlayer_targetNether_buildsInOverworldByMultiply8() {
        // OW にいて Nether(100,70,20) を狙う → 建設先は OW = ×8 = (800,70,160)。
        BackCalc.Result r = BackCalc.compute(new GridPos(100, 70, 20),
                PortalDimension.NETHER, PortalDimension.OVERWORLD, OW_MIN, OW_MAX,
                List.of(), NETHER_RADIUS, true);
        assertEquals(BackCalc.Kind.NEW_IN_CURRENT, r.kind());
        assertEquals(new GridPos(800, 70, 160), r.buildPos());
    }

    @Test
    void netherPlayer_targetOverworld_buildsInNetherByDivide8() {
        // Nether にいて OW(800,70,160) を狙う → 建設先は Nether = ÷8 = (100,70,20)。
        BackCalc.Result r = BackCalc.compute(new GridPos(800, 70, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(), OW_RADIUS, true);
        assertEquals(BackCalc.Kind.NEW_IN_CURRENT, r.kind());
        assertEquals(new GridPos(100, 70, 20), r.buildPos());
    }

    @Test
    void existingGateWithinTargetRadius_givesExistingKind() {
        // 対象 Nether(100,70,20) の半径16内 (105,70,22) に既存 → EXISTING (赤・吸い込み警告)。
        BackCalc.Result r = BackCalc.compute(new GridPos(100, 70, 20),
                PortalDimension.NETHER, PortalDimension.OVERWORLD, OW_MIN, OW_MAX,
                List.of(nether(105, 70, 22)), NETHER_RADIUS, true);
        assertEquals(BackCalc.Kind.EXISTING_IN_TARGET, r.kind());
        assertTrue(r.existing().isPresent());
        assertTrue(r.existingDistance() > 0 && r.existingDistance() <= NETHER_RADIUS);
    }

    @Test
    void existingGateOutsideRadius_isNew() {
        // 半径外 (200,70,20) のみ → 新規見込み (緑)。
        BackCalc.Result r = BackCalc.compute(new GridPos(100, 70, 20),
                PortalDimension.NETHER, PortalDimension.OVERWORLD, OW_MIN, OW_MAX,
                List.of(nether(200, 70, 20)), NETHER_RADIUS, true);
        assertEquals(BackCalc.Kind.NEW_IN_CURRENT, r.kind());
    }

    @Test
    void yIsClampedToCurrentDimBounds() {
        // OW Y=500 を Nether から逆算 → 現在次元 (Nether) の上限 127 にクランプ。
        BackCalc.Result r = BackCalc.compute(new GridPos(800, 500, 160),
                PortalDimension.OVERWORLD, PortalDimension.NETHER, NETHER_MIN, NETHER_MAX,
                List.of(), OW_RADIUS, true);
        assertEquals(NETHER_MAX, r.buildPos().y());
    }

    @Test
    void unobservedRegionStillNewButFlagged() {
        // 既存無し・未観測 → 新規 (緑) だが targetRegionObserved=false (断定不可の注記用)。
        BackCalc.Result r = BackCalc.compute(new GridPos(100, 70, 20),
                PortalDimension.NETHER, PortalDimension.OVERWORLD, OW_MIN, OW_MAX,
                List.of(), NETHER_RADIUS, false);
        assertEquals(BackCalc.Kind.NEW_IN_CURRENT, r.kind());
        assertTrue(!r.targetRegionObserved());
    }
}
