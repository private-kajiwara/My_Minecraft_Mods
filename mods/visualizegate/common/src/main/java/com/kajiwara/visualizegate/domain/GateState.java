package com.kajiwara.visualizegate.domain;

/**
 * ㉚ ゲート 1 つの状態分類 (MC 非依存)。 重大度の高い順に上書きされる (CONFLICT が最優先)。
 *
 * <ul>
 *   <li>{@link #OK}          — 活性＋整合リンク (緑)。</li>
 *   <li>{@link #OFFSET}      — リンクはあるがズレが大きい (黄)。</li>
 *   <li>{@link #WILL_CREATE} — 対応ゲートが無く、 通ると新規生成される見込み (橙)。</li>
 *   <li>{@link #CONFLICT}    — 交差 (複数 OW→同一ネザー) or 非対称 (往復先が食い違う) (赤)。</li>
 *   <li>{@link #ORPHAN}      — 対応の手掛かりが無い片側ゲート (灰)。</li>
 * </ul>
 */
public enum GateState {
    OK(0),
    ORPHAN(1),
    OFFSET(2),
    WILL_CREATE(3),
    CONFLICT(4);

    /** 重大度 (大きいほど要対処・一覧/上書きの優先度)。 */
    public final int severity;

    GateState(int severity) {
        this.severity = severity;
    }
}
