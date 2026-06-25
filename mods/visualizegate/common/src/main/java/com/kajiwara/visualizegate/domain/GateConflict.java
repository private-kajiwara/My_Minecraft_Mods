package com.kajiwara.visualizegate.domain;

/**
 * ㉚ 解析されたコンフリクト/注意項目の 1 件 (MC 非依存・不変)。 Links/Conflicts タブが表示する。
 *
 * <p>表示文面は持たない (旧 {@code reasonJa} の日本語ハードコードを廃止)。 {@link #reason} の種別と
 * {@link #gateNumbers}/{@link #dims}/{@link #offsetBlocks} の素データだけを返し、 ローカライズは UI が
 * {@code visualizegate.conflict.*} で解決する ({@link ConflictReason} 参照)。
 *
 * @param state        分類 (色分けに使う)
 * @param reason       理由の種別 (UI が翻訳キーへマップ)
 * @param gateNumbers  関係するゲート番号 (行クリックで 3D ハイライトする対象)
 * @param dims         {@code gateNumbers} と並びを揃えた次元 (OW/ネザーの区別・採番は次元別連番のため必須)
 * @param offsetBlocks {@link ConflictReason#OFFSET} のときのズレ量 (ブロック・四捨五入)。 他種別では 0。
 */
public record GateConflict(GateState state, ConflictReason reason, int[] gateNumbers,
        PortalDimension[] dims, int offsetBlocks) {

    public int severity() {
        return state.severity;
    }
}
