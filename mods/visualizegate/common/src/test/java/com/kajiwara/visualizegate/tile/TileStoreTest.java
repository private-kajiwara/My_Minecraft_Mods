package com.kajiwara.visualizegate.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** ㉗ common タイルストアの純ロジック検証 (MC 非依存)。 */
class TileStoreTest {

    private static final String OW = "minecraft:overworld";

    @Test
    void voxelKeyRoundTripIncludingNegatives() {
        long k = VoxelKey.of(-2000, 3000, -40);
        assertEquals(-2000, VoxelKey.x(k));
        assertEquals(3000, VoxelKey.z(k));
        long v = VoxelKey.packVal(0xAABBCC, -40);
        assertEquals(-40, VoxelKey.valY(v));
        assertEquals(0xAABBCC, VoxelKey.valColor(v));
    }

    @Test
    void tileAssignmentFloorsNegativeChunks() {
        // block -1 → chunk -1 → tile floorDiv(-1,8) = -1
        TileKey k = TileKey.forBlock(OW, -1, -1);
        assertEquals(-1, k.tileX());
        assertEquals(-1, k.tileZ());
        // block 0 → tile 0 ; block 128 (=8 chunks=1 tile) → tile 1 ; block 1280 (=80 chunks) → tile 10
        assertEquals(0, TileKey.forBlock(OW, 0, 0).tileX());
        assertEquals(1, TileKey.forBlock(OW, 128, 0).tileX());
        assertEquals(10, TileKey.forBlock(OW, 1280, 0).tileX());
    }

    @Test
    void snapshotAggregatesAcrossTilesUnbounded() {
        TileStore s = new TileStore();
        // 2 つの遠く離れたタイルへ書く (= 無制限カバー)。
        s.put(OW, 10, 10, 70, 0x112233);
        s.put(OW, 5000, 5000, 64, 0x445566);
        int[] flat = s.snapshot(OW);
        assertEquals(8, flat.length); // 2 ボクセル × 4
        assertEquals(0, s.snapshot("minecraft:the_nether").length);
    }

    @Test
    void perTileCapTriggersLodDemotionAndStaysBounded() {
        Tile t = new Tile();
        // 上限を超えて密に詰める (128×128 の格子上で stride-1 を全部) → LOD 降格で有界に。
        int put = 0;
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                for (int y = 60; y < 64; y++) { // 1 カラム 4 ボクセル → 65536 > MAX
                    t.put(x, z, y, 0x010203);
                }
            }
        }
        assertTrue(t.size() <= Tile.MAX_PTS_PER_TILE, "tile must stay bounded, was " + t.size());
        assertTrue(t.lodStride() > 1, "LOD must have demoted, stride=" + t.lodStride());
    }

    @Test
    void codecRoundTrip() throws IOException {
        Tile t = new Tile();
        t.put(100, -200, 72, 0xABCDEF);
        t.put(101, -200, 30, VoxelKey.NO_COLOR);
        byte[] bytes = TileCodec.encode(t);
        Tile back = TileCodec.decode(bytes);
        assertNotNull(back);
        assertEquals(t.size(), back.size());
        assertEquals(t.lodStride(), back.lodStride());
    }
}
