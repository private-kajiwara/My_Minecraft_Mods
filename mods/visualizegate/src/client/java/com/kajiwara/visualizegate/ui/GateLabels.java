package com.kajiwara.visualizegate.ui;

/**
 * ゲート既定名の接頭辞 (識別子・<b>非ローカライズ</b>)。
 *
 * <p>{@code OW-<n>} / {@code N-<n>} はユーザー命名が無いゲートの既定 ID で、 言語に依らず同一表記
 * (座標系の "OW"/"N" と同じ識別子)。 従来 4 箇所 (GateRenameScreen / GateNameLabelRenderer /
 * PointCloudScreen の gateLabel・link 行) に散っていた literal を<b>ここ 1 箇所へ集約</b>した
 * (訳語化はしない＝B-P1 の「ゲートID接頭辞は非localize・定数集約のみ」方針)。
 */
public final class GateLabels {

    /** オーバーワールド側ゲートの既定名接頭辞。 */
    public static final String OW_PREFIX = "OW-";
    /** ネザー側ゲートの既定名接頭辞。 */
    public static final String N_PREFIX = "N-";

    private GateLabels() {
    }

    /** 既定名 {@code OW-<number>} / {@code N-<number>} を組み立てる (ユーザー命名が無いときの表示名)。 */
    public static String defaultName(boolean nether, int number) {
        return (nether ? N_PREFIX : OW_PREFIX) + number;
    }
}
