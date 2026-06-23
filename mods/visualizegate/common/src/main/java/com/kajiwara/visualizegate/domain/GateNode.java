package com.kajiwara.visualizegate.domain;

/**
 * ㉚ コンフリクト解析の入力となる 1 ゲート (MC 非依存・不変)。 採番済みポータル。
 *
 * @param number 安定採番 (PortalMemory が永続・OW/ネザー別の連番)
 * @param dim    次元 (OVERWORLD / NETHER)
 * @param x      anchor (グローバル最低コーナー) 絶対ブロック座標
 * @param y      anchor Y
 * @param z      anchor Z
 */
public record GateNode(int number, PortalDimension dim, int x, int y, int z) {

    public GridPos pos() {
        return new GridPos(x, y, z);
    }
}
