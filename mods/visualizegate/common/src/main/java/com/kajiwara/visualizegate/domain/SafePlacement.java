package com.kajiwara.visualizegate.domain;

import java.util.List;

/**
 * 競合解決の<b>安全建設位置</b>判定 (MC 非依存・純粋・<b>バニラ metric 厳密複製</b>)。
 *
 * <p>{@link GateConflictAnalyzer} (赤=競合の定義) は Euclidean <b>円</b>距離を使うが、 バニラの実探索は
 * {@code PortalForcer.findClosestPortalPosition} → {@code PoiManager.getInSquare} の <b>Chebyshev 正方形</b>
 * (|dx|≤r かつ |dz|≤r・XZ・全Y) ＋ 最近傍 <b>3D distSqr</b> (同距離は低Y優先) である (復号ソースで 26.1.2 / 1.21.11
 * 両世代を確認・両者同一)。 探索半径は<b>行き先次元</b>で {@code toNether ? 16 : 128}
 * ({@code NETHER_PORTAL_RADIUS=16 / OVERWORLD_PORTAL_RADIUS=128})。
 *
 * <p>緑 (安全) は<b>赤の厳密な否定</b>でなければならない。 円ベースのアナライザを緑に流用すると角 (Euclidean>r
 * だが Chebyshev≤r) で過小検出し「緑なのに吸い込まれる」が起きる。 そこで緑判定はここで<b>正方形 metric</b>で行う
 * (赤の出力は不変・アナライザは据え置き)。
 *
 * <p><b>判定 (候補 B を conflictDim に新設)</b>:
 * <ol>
 *   <li><b>forward 吸い込み</b>: B を行き先次元へ写像した {@code scale(B)} の正方形 {@code R(dest)} 内に既存ポータルが
 *       あれば、 バニラはそこへ吸い込む (新規でない) → {@link Verdict#PULL_IN}。</li>
 *   <li><b>新規交差</b>: 既存ポータルが無ければバニラは {@code scale(B)} に新ポータル {@code NP} を作る。 他の
 *       conflictDim ゲート {@code G} の写像 {@code scale(G)} が {@code NP} の正方形 {@code R(dest)} 内で、 かつ
 *       {@code G} の現最近傍より {@code NP} が近い (3D distSqr) なら、 {@code G} も {@code NP} を選び<b>取り合い</b>
 *       になる → {@link Verdict#CROSSING}。</li>
 * </ol>
 *
 * <p><b>吸い取り (帰り非対称) は別途不要</b>: OW↔ネザーは 8:1 写像なので「forward の吸い込み正方形」と「帰りの探索
 * 正方形」は<b>同一領域</b>に一致する (例 conflictDim=OW: {@code |B÷8−P|≤16 ⟺ |B−P×8|≤128})。 よって forward が
 * クリアなら帰りでも既存に奪われない (= 吸い取り/帰り非対称は forward クリア＋交差判定に内包される)。
 */
public final class SafePlacement {

    /** バニラ {@code PortalForcer.NETHER_PORTAL_RADIUS} (行き先=ネザーの探索半径・ネザーブロック)。 */
    public static final int NETHER_PORTAL_RADIUS = 16;
    /** バニラ {@code PortalForcer.OVERWORLD_PORTAL_RADIUS} (行き先=OW の探索半径・OW ブロック)。 */
    public static final int OVERWORLD_PORTAL_RADIUS = 128;

    /** 判定結果 (緑可否＋理由)。 */
    public enum Verdict {
        /** 安全 (新規・専有ペアになる)。 */
        SAFE,
        /** 写像先に既存ポータルがあり吸い込まれる。 */
        PULL_IN,
        /** 他の同次元ゲートと新ポータルを取り合う。 */
        CROSSING
    }

    private SafePlacement() {
    }

    /** 行き先次元の探索半径 (バニラ {@code toNether ? 16 : 128})。 */
    public static int searchRadius(PortalDimension destDim) {
        return destDim == PortalDimension.NETHER ? NETHER_PORTAL_RADIUS : OVERWORLD_PORTAL_RADIUS;
    }

    /**
     * {@code conflictDim} の探索半径を<b>相手次元の探索半径を conflictDim ブロックへ換算</b>した値 (排他ゾーンの
     * 半幅)。 OW 在: ネザー半径16 ×8 = 128。 ネザー在: OW 半径128 ÷8 = 16 (「OWは大きく/ネザーは小さく」)。
     */
    public static int exclusionHalfWidth(PortalDimension conflictDim) {
        PortalDimension other = opposite(conflictDim);
        int r = searchRadius(other);
        return (conflictDim == PortalDimension.OVERWORLD)
                ? r * PortalCoordinateMapper.OVERWORLD_TO_NETHER_DIVISOR
                : r / PortalCoordinateMapper.OVERWORLD_TO_NETHER_DIVISOR;
    }

    public static boolean isSafe(GridPos b, PortalDimension conflictDim, List<GateNode> gates,
            int owMinY, int owMaxY, int netMinY, int netMaxY) {
        return classify(b, conflictDim, gates, owMinY, owMaxY, netMinY, netMaxY) == Verdict.SAFE;
    }

    /**
     * 候補 B (conflictDim に新設) の安全性をバニラ metric で判定する。
     *
     * @param gates 既存全ゲート (両次元・{@code PortalMemory.gateNodes()} 由来)
     */
    public static Verdict classify(GridPos b, PortalDimension conflictDim, List<GateNode> gates,
            int owMinY, int owMaxY, int netMinY, int netMaxY) {
        PortalDimension otherDim = opposite(conflictDim);
        int rDest = searchRadius(otherDim);
        int[] destB = bounds(otherDim, owMinY, owMaxY, netMinY, netMaxY);

        // B の行き先 (scale) 座標。
        GridPos exitB = PortalCoordinateMapper.project(b, conflictDim, otherDim, destB[0], destB[1]);

        // (1) forward 吸い込み: 既存の otherDim ポータルが exitB の正方形 R(dest) 内なら吸い込み。
        if (findClosestSquare(exitB, rDest, gates, otherDim) != null) {
            return Verdict.PULL_IN;
        }

        // 新ポータル NP は exitB に出来る。 (2) 他の conflictDim ゲートが NP を選び取り合うか。
        for (GateNode g : gates) {
            if (g.dim() != conflictDim) {
                continue;
            }
            if (g.x() == b.x() && g.y() == b.y() && g.z() == b.z()) {
                continue; // 同一座標は自分扱い
            }
            GridPos exitG = PortalCoordinateMapper.project(g.pos(), conflictDim, otherDim, destB[0], destB[1]);
            if (chebyshevXZ(exitG, exitB) <= rDest) {
                // G の現最近傍 (既存 otherDim) と、 新 NP(=exitB) を比べ、 NP が近ければ G は NP を選ぶ＝取り合い。
                GateNode gTarget = findClosestSquare(exitG, rDest, gates, otherDim);
                long dNp = distSq3D(exitG, exitB);
                long dCur = (gTarget != null) ? distSq3D(exitG, gTarget.pos()) : Long.MAX_VALUE;
                if (dNp <= dCur) {
                    return Verdict.CROSSING;
                }
            }
        }
        return Verdict.SAFE;
    }

    /**
     * {@code dim} のゲートのうち {@code center} の Chebyshev 正方形 (半径 {@code radius}・XZ) 内にある最近傍
     * (3D distSqr・同距離は低Y優先)。 無ければ null。 バニラ {@code findClosestPortalPosition} の複製。
     */
    public static GateNode findClosestSquare(GridPos center, int radius, List<GateNode> gates, PortalDimension dim) {
        GateNode best = null;
        long bestD = Long.MAX_VALUE;
        int bestY = Integer.MAX_VALUE;
        for (GateNode g : gates) {
            if (g.dim() != dim) {
                continue;
            }
            if (Math.abs(g.x() - center.x()) <= radius && Math.abs(g.z() - center.z()) <= radius) {
                long d = distSq3D(center, g.pos());
                if (d < bestD || (d == bestD && g.y() < bestY)) {
                    bestD = d;
                    bestY = g.y();
                    best = g;
                }
            }
        }
        return best;
    }

    static int chebyshevXZ(GridPos a, GridPos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
    }

    static long distSq3D(GridPos a, GridPos b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    static PortalDimension opposite(PortalDimension d) {
        return d == PortalDimension.OVERWORLD ? PortalDimension.NETHER : PortalDimension.OVERWORLD;
    }

    private static int[] bounds(PortalDimension dim, int owMinY, int owMaxY, int netMinY, int netMaxY) {
        return dim == PortalDimension.NETHER ? new int[] { netMinY, netMaxY } : new int[] { owMinY, owMaxY };
    }
}
