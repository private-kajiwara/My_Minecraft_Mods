package com.kajiwara.visualizegate.tile;

/**
 * ㉗ ボクセル (露出サーフェス代表点) の<b>絶対ブロック座標</b>キー/値パック (純 Java・MC 非依存)。
 *
 * <p>キー = {@code x:26 | z:26 | (y+64):9} (㉒B 由来)。 x,z=26bit 符号付き (±33.5M &gt; 境界±30M)、
 * y=(y+64) を 9bit (OW -64..320→0..384、 ネザー 0..127)。 値 = {@code color<<32 | (y & 0xFFFFFFFF)}。
 * color は 0xRRGGBB / {@link #NO_COLOR}(-1)。 タイル化後もボクセルキーは<b>絶対座標のまま流用</b>する
 * (タイルは単に空間分割するだけ＝既存スナップショット出力 {@code (wx,wz,y,color)} と完全一致)。
 */
public final class VoxelKey {

    /** ブロック色が取れないときの番兵 (= ディメンション色フォールバック)。 {@code TerrainSampler.NO_COLOR} と一致。 */
    public static final int NO_COLOR = -1;

    private VoxelKey() {
    }

    public static long of(int blockX, int blockZ, int y) {
        return ((long) (blockX & 0x3FFFFFF) << 38) | ((long) (blockZ & 0x3FFFFFF) << 12)
                | (((y + 64) & 0x1FFL));
    }

    /** キーから絶対 blockX (26bit 符号付き)。 */
    public static int x(long key) {
        return (int) (key >> 38);
    }

    /** キーから絶対 blockZ (26bit 符号付き)。 */
    public static int z(long key) {
        return (int) ((key << 26) >> 38);
    }

    public static long packVal(int color, int y) {
        return ((long) color << 32) | (y & 0xFFFFFFFFL);
    }

    public static int valY(long v) {
        return (int) v;
    }

    public static int valColor(long v) {
        return (int) (v >> 32);
    }
}
