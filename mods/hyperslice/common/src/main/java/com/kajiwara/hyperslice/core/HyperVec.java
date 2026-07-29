package com.kajiwara.hyperslice.core;

/**
 * 連続 4 次元座標 {@code (x, y, z, w)}。
 *
 * <p>整数格子の {@link HyperCoord} とは別物。 こちらはエンティティの位置・速度など
 * <b>連続量</b>に使う。 w が {@code double} なのは意図的で、 方式B (単一ディメンションで
 * ブロックを書き換え、 継ぎ目のない w 移動) へ移行しても API を変えずに済むようにするため。
 *
 * <p>Minecraft の型を一切参照しない純粋な値型。
 */
public record HyperVec(double x, double y, double z, double w) {

    public static final HyperVec ZERO = new HyperVec(0, 0, 0, 0);

    public HyperVec add(HyperVec o) {
        return new HyperVec(x + o.x, y + o.y, z + o.z, w + o.w);
    }

    public HyperVec scale(double s) {
        return new HyperVec(x * s, y * s, z * s, w * s);
    }

    public HyperVec withW(double newW) {
        return new HyperVec(x, y, z, newW);
    }

    public HyperVec withY(double newY) {
        return new HyperVec(x, newY, z, w);
    }

    /** 3 次元部分 (x, y, z) のみのユークリッド距離の 2 乗。 w は含めない。 */
    public double distanceSq3(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** ブロック格子へ落とした整数座標 ({@code w} は floor で層番号になる)。 */
    public HyperCoord toBlock() {
        return new HyperCoord(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), (int) Math.floor(w));
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f, %.2f, w=%.3f)", x, y, z, w);
    }
}
