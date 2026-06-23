package com.kajiwara.visualizegate.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 安全建設位置のバニラ metric 判定検証 (MC 非依存)。 特に「Euclidean 円なら安全だが Chebyshev 正方形では
 * 吸い込まれる角ケース」(真因①) と「新規交差」(真因②) を回帰として固定する。
 */
class SafePlacementTest {

    private static GateNode ow(int num, int x, int z) {
        return new GateNode(num, PortalDimension.OVERWORLD, x, 64, z);
    }

    private static GateNode nether(int num, int x, int z) {
        return new GateNode(num, PortalDimension.NETHER, x, 32, z);
    }

    private static SafePlacement.Verdict classifyOw(GridPos b, List<GateNode> gates) {
        return SafePlacement.classify(b, PortalDimension.OVERWORLD, gates, -64, 319, 0, 127);
    }

    @Test
    void cornerCaseThatCircleMissedIsPullIn() {
        // 既存ネザー(0,0)。 候補 B=(128,*,128)→÷8→(16,16)。 Chebyshev=16≤16 ⇒ バニラは吸い込む。
        // Euclidean=22.6>16 で旧・円判定は「安全」と誤っていた角ケース。 正しくは PULL_IN。
        var gates = List.of(ow(1, 0, 0), nether(1, 0, 0));
        assertEquals(SafePlacement.Verdict.PULL_IN, classifyOw(new GridPos(128, 64, 128), gates));
    }

    @Test
    void newCrossingWithAnotherOwGateIsDetected() {
        // 既存 OW(0,0) は対応ネザー無し。 候補 B=(8,*,0)→÷8→(1,0)。 新ポータル NP=(1,0)。
        // OW(0,0)→÷8→(0,0) の正方形16内に NP があり、 既存対応が無い(=NP が最近) ⇒ 取り合い CROSSING。
        var gates = List.of(ow(1, 0, 0));
        assertEquals(SafePlacement.Verdict.CROSSING, classifyOw(new GridPos(8, 64, 0), gates));
    }

    @Test
    void farExclusiveSpotIsSafe() {
        // 既存ペア OW(0,0)+N(0,0)。 候補 B=(8000,*,8000)→÷8→(1000,1000)。 全ポータルから遠く専有 ⇒ SAFE。
        var gates = List.of(ow(1, 0, 0), nether(1, 0, 0));
        assertEquals(SafePlacement.Verdict.SAFE, classifyOw(new GridPos(8000, 64, 8000), gates));
    }

    @Test
    void crossingResolvedByMovingFarEnough() {
        // 取り合いシナリオ: OW(0,0) と OW(40,0) が同一ネザー N(0,0) を取り合い (両 ÷8 が16内)。
        // OW-1 を解決する想定。 N(0,0) を専有する新規位置を探す。
        var gates = List.of(ow(1, 0, 0), ow(2, 40, 0), nether(1, 0, 0));
        // B=(8,0): ÷8=(1,0) は N(0,0) の正方形16内 ⇒ 吸い込み (専有でない)。
        assertEquals(SafePlacement.Verdict.PULL_IN, classifyOw(new GridPos(8, 64, 0), gates));
        // B=(200,0): ÷8=(25,0)。 N(0,0)(dist25>16)外でクリア。 だが OW(40,0)÷8=(5,0) とは dist20>16 で交差せず…
        //   実際 (25,0) と (5,0) は 20>16 ⇒ 交差なし ⇒ SAFE。
        assertEquals(SafePlacement.Verdict.SAFE, classifyOw(new GridPos(200, 64, 0), gates));
        // B=(160,0): ÷8=(20,0)。 N(0,0)(20>16)外。 OW(40,0)÷8=(5,0) と dist15≤16 ⇒ NP を OW-2 が取り合う ⇒ CROSSING。
        assertEquals(SafePlacement.Verdict.CROSSING, classifyOw(new GridPos(160, 64, 0), gates));
    }

    @Test
    void netherSideExclusionIsSmall() {
        // 在ネザーで OW を行き先にする場合、 排他半幅は 16 (= OW半径128 ÷8)。
        assertEquals(16, SafePlacement.exclusionHalfWidth(PortalDimension.NETHER));
        assertEquals(128, SafePlacement.exclusionHalfWidth(PortalDimension.OVERWORLD));
    }
}
