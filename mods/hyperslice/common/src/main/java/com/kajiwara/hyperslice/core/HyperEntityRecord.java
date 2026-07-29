package com.kajiwara.hyperslice.core;

import java.util.UUID;

/**
 * 4 次元エンティティ 1 体のレコード。
 *
 * <p><b>バニラ {@code Entity} ではない。</b> 方式A ではスライスが別ディメンションなので、
 * バニラ Entity として実装すると w 方向の移動がディメンション間テレポートになり、
 * 非プレイヤーの {@code changeDimension} は実体を作り直すため AI 状態が飛ぶ。
 * よって滑らかな w 移動は原理的に作れない。 そこで mod 側が自前でレコードを保持し、
 * 自前で tick する ({@link HyperEntityManager})。
 *
 * <p>この型は不変。 tick は新しいレコードを作って置き換える (純粋関数的に扱う)。
 * バニラ Mob は一切変更しないので、 ゾンビ等は 3D のままそのスライスに固定で競合しない。
 */
public record HyperEntityRecord(
        UUID id,
        HyperEntityType type,
        HyperVec position,
        HyperVec velocity) {

    /** w 方向の厚み (型から引く)。 */
    public double wThickness() {
        return type.wThickness();
    }

    /** 観測超平面 {@code planeW} から見た w 方向のずれ。 */
    public double dw(double planeW) {
        return position.w() - planeW;
    }

    /** 観測超平面 {@code planeW} における断面半径。 交差していなければ 0。 */
    public double crossSectionRadius(double planeW) {
        return CrossSection.radius(wThickness(), dw(planeW));
    }

    public HyperEntityRecord withPosition(HyperVec p) {
        return new HyperEntityRecord(id, type, p, velocity);
    }

    public HyperEntityRecord withVelocity(HyperVec v) {
        return new HyperEntityRecord(id, type, position, v);
    }
}
