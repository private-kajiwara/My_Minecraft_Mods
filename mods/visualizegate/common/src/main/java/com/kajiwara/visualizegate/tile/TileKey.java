package com.kajiwara.visualizegate.tile;

/**
 * ㉗ 領域タイルの識別子 (純 Java・MC 非依存)。 1 タイル = {@link #TILE_CHUNKS}×{@link #TILE_CHUNKS} チャンク
 * (= {@value #TILE_BLOCKS}×{@value #TILE_BLOCKS} ブロック)。 ディメンション別・タイル座標 (チャンク座標を
 * {@code floorDiv(,TILE_CHUNKS)}) でキー化する。 タイル数は<b>無制限</b> (= 探索した所は全部残る)。
 */
public record TileKey(String dimId, int tileX, int tileZ) {

    /** 1 タイル辺のチャンク数 (ロック済み設計 N=8)。 */
    public static final int TILE_CHUNKS = 8;
    /** 1 タイル辺のブロック数 (= 128)。 */
    public static final int TILE_BLOCKS = TILE_CHUNKS * 16;

    /** チャンク座標 → タイル座標 (負も正しく floor)。 */
    public static int tileFromChunk(int chunkCoord) {
        return Math.floorDiv(chunkCoord, TILE_CHUNKS);
    }

    /** 絶対ブロック座標が属するタイルキー。 */
    public static TileKey forBlock(String dimId, int blockX, int blockZ) {
        return new TileKey(dimId, tileFromChunk(blockX >> 4), tileFromChunk(blockZ >> 4));
    }

    /** タイル X 座標 → 絶対 blockX 下端 (タイル原点)。 */
    public int originBlockX() {
        return tileX * TILE_BLOCKS;
    }

    public int originBlockZ() {
        return tileZ * TILE_BLOCKS;
    }
}
