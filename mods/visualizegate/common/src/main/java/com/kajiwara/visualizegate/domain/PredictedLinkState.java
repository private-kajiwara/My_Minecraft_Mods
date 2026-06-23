package com.kajiwara.visualizegate.domain;

/**
 * ポータルリンク予測の三値状態 (正直な不確実性表現)。
 *
 * <ul>
 *   <li>{@link #LINKED}      — 理想ターゲットの探索半径内に既知ポータルがある (緑)。</li>
 *   <li>{@link #WILL_CREATE} — 対象領域は観測済みだが既知ポータルが無い → 新規生成される見込み (赤)。</li>
 *   <li>{@link #UNKNOWN}     — 対象領域を未観測 → 判定不能 (灰/破線)。</li>
 * </ul>
 */
public enum PredictedLinkState {
    LINKED,
    WILL_CREATE,
    UNKNOWN
}
