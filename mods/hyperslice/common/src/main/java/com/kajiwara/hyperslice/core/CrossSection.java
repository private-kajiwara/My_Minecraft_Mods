package com.kajiwara.hyperslice.core;

/**
 * 4 次元物体を 3 次元超平面で切った<b>断面</b>の計算。
 *
 * <h2>なぜフェードではなく断面なのか</h2>
 * 4 次元球を 3 次元超平面で切った断面は 3 次元球であり、 その半径は
 * <pre>
 *   R = wThickness / 2                       (4次元球の w 方向半径)
 *   r_visible = R * sqrt(max(0, 1 - (dw/R)^2))
 * </pre>
 * となる。 {@code dw} が端に近づくと半径が 0 に<b>収束する</b>ので、
 * アルファフェードは一切要らない。 半透明を混ぜると「透けた物体」に見えて
 * 「断面」という読みが壊れるため、 <b>純粋にスケールのみで表現する</b>。
 *
 * <p>系として、 観測面と交差していない物体は描画対象が存在しない (半径 0)。
 * 「隣スライスのゴースト表示」のような特別扱いは不要で、
 * 正しい 4 次元物理がそのまま最小実装になる。
 *
 * <h2>観測面の規約</h2>
 * ブロックは {@code w ∈ [n, n+1)} を占める。 よってスライス {@code n} にいる
 * プレイヤーの観測超平面は {@code w = n + 0.5} (ブロック層が観測面に対して対称になる)。
 * {@code dw = entity.w - observationPlaneW}。
 */
public final class CrossSection {

    private CrossSection() {
    }

    /**
     * スライス番号から観測超平面の w を返す。
     *
     * <p>方式A では {@code slice + 0.5}。 方式B で小数 w になっても
     * 呼び出し側の API は変わらない。
     */
    public static double observationPlane(int slice) {
        return slice + 0.5;
    }

    /**
     * 断面の半径。
     *
     * @param wThickness 4 次元物体の w 方向の厚み (直径)。 0 以下なら常に 0 を返す
     * @param dw         観測超平面からの w 方向のずれ ({@code entity.w - planeW})
     * @return 断面半径 (交差していなければ {@code 0.0}・常に非負で NaN を返さない)
     */
    public static double radius(double wThickness, double dw) {
        if (!(wThickness > 0.0) || Double.isNaN(dw)) {
            // 厚みゼロ/負、 または dw が NaN のときは「交差なし」に倒す (例外は投げない)。
            return 0.0;
        }
        double r = wThickness * 0.5;
        double t = dw / r;
        double inside = 1.0 - t * t;
        if (inside <= 0.0) {
            return 0.0;
        }
        return r * Math.sqrt(inside);
    }

    /**
     * 観測超平面と交差しているか (= 描画対象が存在するか)。
     *
     * @param margin 交差判定に持たせる余裕。 ネットワーク遅延で到着が遅れると
     *               突然出現して見えるため、 <b>同期の絞り込みでは正のマージン</b>を
     *               与える。 描画側は {@code 0} で判定してよい
     */
    public static boolean intersects(double wThickness, double dw, double margin) {
        if (!(wThickness > 0.0) || Double.isNaN(dw)) {
            return false;
        }
        return Math.abs(dw) < wThickness * 0.5 + margin;
    }
}
