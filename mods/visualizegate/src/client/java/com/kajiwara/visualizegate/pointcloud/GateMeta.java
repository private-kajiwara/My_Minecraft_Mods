package com.kajiwara.visualizegate.pointcloud;

import java.util.List;

import com.kajiwara.visualizegate.domain.GateConflict;

/**
 * ㉚ スナップショットに付随するゲート<b>メタ情報</b> (不変・ワーカー算出)。 位置 (gateX/Y/Z) と<b>添字を揃える</b>:
 * {@code gateNumber[i]}/{@code gateState[i]} は {@code snapshot.gateX[i]} のゲートに対応する。
 *
 * @param gateNumber  ゲート添字 → 安定採番 (次元別連番)
 * @param gateState   ゲート添字 → {@link com.kajiwara.visualizegate.domain.GateState} の ordinal (色分け用)
 * @param gateWx      ゲート添字 → 絶対ワールド anchor X (一覧表示用)
 * @param gateWy      ゲート添字 → 絶対ワールド anchor Y
 * @param gateWz      ゲート添字 → 絶対ワールド anchor Z
 * @param linkOwNumber 接続ペア添字 (snapshot.linkAx と同順) → OW 側ゲート番号 (0=不明)
 * @param linkNNumber  接続ペア添字 → ネザー側ゲート番号 (0=不明)
 * @param conflicts   要対処項目 (重大度順・Conflicts タブ用)
 */
public record GateMeta(int[] gateNumber, int[] gateState,
        int[] gateWx, int[] gateWy, int[] gateWz,
        int[] linkOwNumber, int[] linkNNumber, List<GateConflict> conflicts) {

    public static final GateMeta EMPTY = new GateMeta(new int[0], new int[0],
            new int[0], new int[0], new int[0], new int[0], new int[0], List.of());
}
