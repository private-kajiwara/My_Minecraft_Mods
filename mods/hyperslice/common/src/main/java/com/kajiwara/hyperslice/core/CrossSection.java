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
 * {@code dw = entity.w - observationPlaneW}。 観測面の求め方は<b>方式A と方式B で違う</b>ので
 * 下記 2 つのメソッドを取り違えないこと。
 *
 * <ul>
 *   <li><b>方式A</b> ({@link #observationPlane(int)}) … スライスが別ディメンションで、
 *       ブロックは {@code w ∈ [n, n+1)} を占めると解釈する。 よって観測面は
 *       {@code w = n + 0.5} (ブロック層が観測面に対して対称になる)</li>
 *   <li><b>方式B</b> ({@link #planeForTerrainW(double)}) … 地形自体が連続な w で
 *       切り直されるので、 観測面は<b>地形 w そのもの</b>。 オフセットは付かない</li>
 * </ul>
 */
public final class CrossSection {

    private CrossSection() {
    }

    /**
     * <b>方式A</b>: スライス番号から観測超平面の w を返す ({@code slice + 0.5})。
     *
     * <p>スライスが別ディメンションであり、 そのディメンションのブロックが
     * {@code w ∈ [n, n+1)} を占めると解釈したときの観測面。
     *
     * <p><b>方式B では使わない</b> ({@link #planeForTerrainW(double)} を使う)。
     * 方式B へ移行してもこのメソッドを残すのは、 方式B を無効化したときに
     * 挙動が完全に元へ戻る (= 可逆性) ようにするため。
     */
    public static double observationPlane(int slice) {
        return slice + 0.5;
    }

    /**
     * <b>方式B</b>: 地形の w から観測超平面の w を返す。 <b>オフセットは付かない</b>。
     *
     * <h2>なぜ {@code +0.5} しないのか</h2>
     * 方式A の {@code +0.5} は「ブロックが {@code w ∈ [n, n+1)} という<b>厚み</b>を持つ」
     * という解釈から来ている。 しかし地形の実装はそうなっていない:
     * {@code slice_n} のディメンションは {@code HyperTerrain} を
     * <b>{@code w = n} で厳密に評価</b>している (生成器の Codec が {@code "w": n} を
     * そのまま {@code surfaceY(x, z, w)} へ渡す)。 つまりブロック層は
     * 「{@code w = n} で切った断面を w 方向に押し出したもの」であって、
     * {@code [n, n+1)} の体積平均ではない。
     *
     * <p>方式B では w が連続になり、 地形は「今の w で切り直した断面」そのものになる。
     * したがってエンティティと地形が同一の超平面を共有するには
     * <b>観測面 = 地形 w</b> でなければならない。 方式A で {@code +0.5} ずれていても
     * 無害だったのは、 その値をエンティティ層しか読んでいなかったため。
     *
     * <p>恒等関数だが<b>規約を書く場所を 1 箇所に決めるため</b>にメソッドにしてある
     * (呼び出し側に {@code +0.5} を書かせない・書かないことの理由が読める)。
     */
    public static double planeForTerrainW(double terrainW) {
        return terrainW;
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
