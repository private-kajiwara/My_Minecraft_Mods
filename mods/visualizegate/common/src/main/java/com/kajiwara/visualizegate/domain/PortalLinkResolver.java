package com.kajiwara.visualizegate.domain;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * ポータルリンクの<b>予測</b> (MC 非依存・純粋・ヒューリスティック)。
 *
 * <p><b>重要</b>: これはバニラの実ポータル探索 (直方体走査・POI・最近傍規則) の完全再現ではなく、
 * 「source を対象次元へ写像した理想ターゲットの近くに既知ポータルがあるか」を XZ 水平距離で判定する
 * <b>予測</b>である。 探索半径 (OW=128 / Nether=16 等) と次元境界は呼び出し側が引数で渡す
 * (= domain は MC 依存値を持たない)。
 */
public final class PortalLinkResolver {

    private PortalLinkResolver() {
    }

    /**
     * source ポータル/位置から対象次元へのリンクを予測する。
     *
     * @param source               現在次元の source 座標
     * @param from                 source の次元
     * @param to                   対象次元
     * @param toMinY               対象次元の最低 Y (Y クランプ用)
     * @param toMaxY               対象次元の最高 Y
     * @param knownInTo            対象次元の既知ポータル集合 (PortalMemory 由来)
     * @param searchRadius         探索半径 (OW=128 / Nether=16)
     * @param targetRegionObserved 「理想ターゲット周辺のリージョンが観測済みか」 を返す述語。
     *                             一致ポータルが無いとき: true→WILL_CREATE(赤) / false→UNKNOWN(灰)。
     *                             述語には算出した理想ターゲット座標が渡される (= 領域単位の正直な被覆判定)。
     */
    public static LinkPrediction predict(GridPos source, PortalDimension from, PortalDimension to,
            int toMinY, int toMaxY, Collection<DomainPortal> knownInTo,
            double searchRadius, Predicate<GridPos> targetRegionObserved) {

        GridPos ideal = PortalCoordinateMapper.project(source, from, to, toMinY, toMaxY);

        DomainPortal nearest = null;
        double best = Double.MAX_VALUE;
        for (DomainPortal p : knownInTo) {
            if (p.dimension() != to) {
                continue;
            }
            double d = ideal.horizontalDistanceTo(p.anchor());
            if (d <= searchRadius && d < best) {
                best = d;
                nearest = p;
            }
        }

        if (nearest != null) {
            return new LinkPrediction(PredictedLinkState.LINKED, ideal,
                    Optional.of(nearest), best, searchRadius);
        }
        PredictedLinkState state = targetRegionObserved.test(ideal)
                ? PredictedLinkState.WILL_CREATE
                : PredictedLinkState.UNKNOWN;
        return new LinkPrediction(state, ideal, Optional.empty(), Double.NaN, searchRadius);
    }

    /**
     * 機能1 (ホログラム) 用: 別次元の既知ポータル {@code other} を現在次元へ写像した
     * 「ズレ無し建設位置」 を返す。 = {@code project(other.anchor, other.dimension, currentDim)}。
     * これに現在次元でポータルを建てれば、 渡った先で {@code other} 近傍へリンクする (理論値)。
     *
     * @throws IllegalArgumentException OW↔Nether 以外の組み合わせ
     */
    public static GridPos ghostBuildPosition(DomainPortal other, PortalDimension currentDim,
            int currentMinY, int currentMaxY) {
        return PortalCoordinateMapper.project(other.anchor(), other.dimension(), currentDim,
                currentMinY, currentMaxY);
    }
}
