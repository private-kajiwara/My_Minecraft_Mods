package com.kajiwara.visualizegate.domain;

import java.util.Optional;

/**
 * {@link PortalLinkResolver} の予測結果 (MC 非依存・ヒューリスティック)。
 *
 * @param state          三値状態 (LINKED/WILL_CREATE/UNKNOWN)
 * @param idealTarget    source を対象次元へ写像した理想ターゲット座標
 * @param matched        LINKED 時の一致ポータル (それ以外は空)
 * @param offsetDistance LINKED 時の理想ターゲット↔一致ポータルの水平距離 (それ以外は NaN)
 * @param searchRadius   使用した探索半径 (OW=128 / Nether=16 等)
 */
public record LinkPrediction(
        PredictedLinkState state,
        GridPos idealTarget,
        Optional<DomainPortal> matched,
        double offsetDistance,
        double searchRadius) {
}
