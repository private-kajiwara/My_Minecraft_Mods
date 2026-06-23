package com.kajiwara.visualizegate.domain;

/**
 * ㉚ 解析されたコンフリクト/注意項目の 1 件 (MC 非依存・不変)。 Links/Conflicts タブが素の日本語で表示する。
 *
 * @param state        分類 (色分けに使う)
 * @param gateNumbers  関係するゲート番号 (行クリックで 3D ハイライトする対象)
 * @param dims         {@code gateNumbers} と並びを揃えた次元 (OW/ネザーの区別・採番は次元別連番のため必須)
 * @param reasonJa     素の日本語の理由 (初見向け)
 */
public record GateConflict(GateState state, int[] gateNumbers, PortalDimension[] dims, String reasonJa) {

    public int severity() {
        return state.severity;
    }
}
