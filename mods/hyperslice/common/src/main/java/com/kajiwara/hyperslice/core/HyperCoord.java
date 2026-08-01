package com.kajiwara.hyperslice.core;

/**
 * 4 次元ボクセル座標 {@code (x, y, z, w)}。
 *
 * <p>{@code x, y, z} は通常の Minecraft ブロック座標と同じ意味を持つ。
 * {@code w} は「どのスライス (= どのディメンション) か」を表す整数で、
 * {@code 0 .. N-1} を巡回する ({@link SliceRegistry} 参照)。
 *
 * <p>この型は純粋な値であり、 Minecraft の型を一切参照しない。
 * 地形関数 {@link HyperTerrain} の入力としてのみ使う。
 */
public record HyperCoord(int x, int y, int z, int w) {

    /** {@code w} だけ差し替えた同一 (x,y,z) の座標を返す (スライス間の対応点)。 */
    public HyperCoord withW(int newW) {
        return new HyperCoord(x, y, z, newW);
    }

    /** {@code (x,y,z)} だけ差し替えた同一 w の座標を返す。 */
    public HyperCoord withXyz(int newX, int newY, int newZ) {
        return new HyperCoord(newX, newY, newZ, w);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ", w=" + w + ")";
    }
}
