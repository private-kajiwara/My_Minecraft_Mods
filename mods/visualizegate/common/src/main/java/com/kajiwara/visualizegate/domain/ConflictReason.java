package com.kajiwara.visualizegate.domain;

/**
 * ㉚ コンフリクト理由の<b>種別</b> (MC 非依存・表示文字列を持たない)。
 *
 * <p>ドメインは「どの種類の競合か」だけを返し、 表示文面 (ローカライズ) は UI 側が
 * {@code visualizegate.conflict.*} の {@code Component.translatable} で解決する。
 * 旧 {@code GateConflict.reasonJa} の日本語ハードコードを廃した結果の責務分離。
 */
public enum ConflictReason {

    /** 交差: 複数 OW が同一ネザーを最近傍とする。 */
    CROSSING,
    /** 非対称: OW→N→OW の往復で別ゲートに出る。 */
    ASYMMETRIC,
    /** ズレ: リンクはあるが理想ターゲットから離れている。 */
    OFFSET,
    /** 未接続: 対応ネザーが半径内に無い (通ると新規生成)。 */
    WILL_CREATE,
    /** 片側: どの OW からもリンクされないネザーゲート。 */
    ORPHAN
}
