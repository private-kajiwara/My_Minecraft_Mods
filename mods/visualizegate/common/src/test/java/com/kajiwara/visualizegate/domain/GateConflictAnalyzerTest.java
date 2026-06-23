package com.kajiwara.visualizegate.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** ㉚ コンフリクト分類の純ロジック検証 (MC 非依存)。 */
class GateConflictAnalyzerTest {

    private static GateNode ow(int num, int x, int z) {
        return new GateNode(num, PortalDimension.OVERWORLD, x, 64, z);
    }

    private static GateNode nether(int num, int x, int z) {
        return new GateNode(num, PortalDimension.NETHER, x, 64, z);
    }

    private static GateConflictAnalyzer.Result run(List<GateNode> gates) {
        return GateConflictAnalyzer.analyze(gates, 0, 127, -64, 319);
    }

    @Test
    void cleanPairIsOk() {
        // OW(0,0)→÷8→(0,0)、 nether(0,0) 距離0＝OK。 往復対称。
        var r = run(List.of(ow(1, 0, 0), nether(1, 0, 0)));
        assertEquals(GateState.OK, r.states()[0]);
        assertEquals(GateState.OK, r.states()[1]);
        assertTrue(r.conflicts().isEmpty(), "clean pair has no conflicts");
    }

    @Test
    void twoOwToOneNetherIsCrossing() {
        // OW(0,0)→(0,0)、 OW(8,0)→(1,0)。 nether(0,0) が両者の最近傍 (dist 0/1 ≤16)＝交差。
        var r = run(List.of(ow(1, 0, 0), ow(2, 8, 0), nether(1, 0, 0)));
        assertEquals(GateState.CONFLICT, r.states()[0]);
        assertEquals(GateState.CONFLICT, r.states()[1]);
        assertEquals(GateState.CONFLICT, r.states()[2]);
        assertTrue(r.conflicts().stream().anyMatch(c -> c.reasonJa().startsWith("交差")));
    }

    @Test
    void largeOffsetIsOffset() {
        // OW(0,0)→(0,0)、 nether(10,0): dist 10 (>8, ≤16)＝OFFSET。
        var r = run(List.of(ow(1, 0, 0), nether(1, 10, 0)));
        assertEquals(GateState.OFFSET, r.states()[0]);
    }

    @Test
    void unlinkedOwIsWillCreate() {
        // OW(0,0)→(0,0)、 nether は遠方(1000,1000) 半径外＝対応なし＝WILL_CREATE。
        var r = run(List.of(ow(1, 0, 0), nether(1, 1000, 1000)));
        assertEquals(GateState.WILL_CREATE, r.states()[0]);
    }

    @Test
    void unTargetedNetherIsOrphan() {
        // nether(1000,1000) はどの OW からも狙われない＝ORPHAN。 (OW は別の所で繋がる)
        var r = run(List.of(ow(1, 0, 0), nether(1, 0, 0), nether(2, 1000, 1000)));
        // gates: idx0=OW1, idx1=N1 (OK pair), idx2=N2 orphan
        assertEquals(GateState.ORPHAN, r.states()[2]);
    }
}
