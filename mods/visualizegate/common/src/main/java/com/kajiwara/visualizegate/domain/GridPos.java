package com.kajiwara.visualizegate.domain;

/**
 * MC 非依存の整数 3D 座標 (BlockPos の純粋版)。 domain 層のユニットテスト用。
 */
public record GridPos(int x, int y, int z) {

    /** 水平 (XZ) ユークリッド距離。 ポータルリンク探索の近さ判定に使う (Y は無視＝バニラ探索の近似)。 */
    public double horizontalDistanceTo(GridPos other) {
        double dx = (double) this.x - other.x;
        double dz = (double) this.z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
