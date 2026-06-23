package com.kajiwara.visualizegate.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class PortalCoordinateMapperTest {

    // Nether の建築境界 (写像 Y クランプ用)。
    private static final int NETHER_MIN = 0;
    private static final int NETHER_MAX = 127;
    private static final int OW_MIN = -64;
    private static final int OW_MAX = 319;

    @Test
    void overworldToNetherDividesXZByEight() {
        GridPos ow = new GridPos(80, 70, 160);
        GridPos nether = PortalCoordinateMapper.project(ow, PortalDimension.OVERWORLD, PortalDimension.NETHER,
                NETHER_MIN, NETHER_MAX);
        assertEquals(new GridPos(10, 70, 20), nether);
    }

    @Test
    void netherToOverworldMultipliesXZByEight() {
        GridPos nether = new GridPos(10, 70, 20);
        GridPos ow = PortalCoordinateMapper.project(nether, PortalDimension.NETHER, PortalDimension.OVERWORLD,
                OW_MIN, OW_MAX);
        assertEquals(new GridPos(80, 70, 160), ow);
    }

    @Test
    void roundTripOverworldNetherOverworldIsLossyByFloorDiv() {
        // OW(83) -> Nether(floor 83/8 = 10) -> OW(80): 8:1 は非可逆 (floorDiv の丸め)。
        GridPos ow = new GridPos(83, 70, 7);
        GridPos nether = PortalCoordinateMapper.project(ow, PortalDimension.OVERWORLD, PortalDimension.NETHER,
                NETHER_MIN, NETHER_MAX);
        assertEquals(new GridPos(10, 70, 0), nether);
        GridPos back = PortalCoordinateMapper.project(nether, PortalDimension.NETHER, PortalDimension.OVERWORLD,
                OW_MIN, OW_MAX);
        assertEquals(new GridPos(80, 70, 0), back); // 83 ではなく 80 に戻る
    }

    @Test
    void negativeCoordsUseFloorDivNotTruncation() {
        // floorDiv(-1, 8) = -1 (切り捨てなら 0)。
        GridPos ow = new GridPos(-1, 70, -9);
        GridPos nether = PortalCoordinateMapper.project(ow, PortalDimension.OVERWORLD, PortalDimension.NETHER,
                NETHER_MIN, NETHER_MAX);
        assertEquals(new GridPos(-1, 70, -2), nether);
    }

    @Test
    void yIsClampedToTargetBounds() {
        GridPos high = new GridPos(0, 250, 0);
        GridPos nether = PortalCoordinateMapper.project(high, PortalDimension.OVERWORLD, PortalDimension.NETHER,
                NETHER_MIN, NETHER_MAX);
        assertEquals(NETHER_MAX, nether.y()); // 250 -> 127 にクランプ

        GridPos low = new GridPos(0, -100, 0);
        GridPos ow = PortalCoordinateMapper.project(low, PortalDimension.NETHER, PortalDimension.OVERWORLD,
                OW_MIN, OW_MAX);
        assertEquals(OW_MIN, ow.y()); // -100 -> -64 にクランプ
    }

    @Test
    void sameDimensionOnlyClampsY() {
        GridPos p = new GridPos(123, 400, -456);
        GridPos out = PortalCoordinateMapper.project(p, PortalDimension.OVERWORLD, PortalDimension.OVERWORLD,
                OW_MIN, OW_MAX);
        assertEquals(new GridPos(123, OW_MAX, -456), out);
    }

    @Test
    void endAndOtherAreUnsupported() {
        GridPos p = new GridPos(0, 64, 0);
        assertThrows(IllegalArgumentException.class, () ->
                PortalCoordinateMapper.project(p, PortalDimension.OVERWORLD, PortalDimension.END, 0, 255));
        assertThrows(IllegalArgumentException.class, () ->
                PortalCoordinateMapper.project(p, PortalDimension.END, PortalDimension.NETHER, 0, 127));
        assertFalse(PortalCoordinateMapper.isSupported(PortalDimension.OVERWORLD, PortalDimension.END));
        assertTrue(PortalCoordinateMapper.isSupported(PortalDimension.OVERWORLD, PortalDimension.NETHER));
        assertTrue(PortalCoordinateMapper.isSupported(PortalDimension.NETHER, PortalDimension.OVERWORLD));
    }
}
