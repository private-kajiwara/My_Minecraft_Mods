package com.kajiwara.visualizegate.pointcloud;

import com.kajiwara.visualizegate.domain.PortalDimension;

/**
 * 解析結果の<b>不変スナップショット</b> (ワーカーが組み、 ポップアップ Screen が描画する)。
 *
 * <p>座標は「OW スケール整列・各層 Y センタリング・水平センタリング」済の<b>ビュー空間</b>:
 * <ul>
 *   <li>OW 点: {@code X=wx-hCenterX, Y=wy-owMeanY, Z=wz-hCenterZ}</li>
 *   <li>ネザー点: {@code X=wx-nCenterX, Y=wy-netherMeanY, Z=wz-nCenterZ} (⑥ 1:1 自然スケール・ネザー重心)</li>
 * </ul>
 * <b>垂直分離 (ディメンション間隔)</b> はライブのスライダ値なので<b>ここには織り込まない</b>。 描画時に
 * Screen がネザー層 (点・リンク B 端) の Y へ {@code +spacing} を足す (= スライダ変更でスナップショット再構築不要)。
 *
 * <p>リンクは OW ポータル (A 端) ↔ ネザー partner (B 端) の線分。 A 端は OW 層、 B 端はネザー層
 * (描画時に B 端 Y へ spacing を加算)。 色は層+高さで解析時に確定済 (描画は塗るだけ)。
 *
 * <p>{@code *Sampled} は間引き前の母数、 {@code *Drawn} は描画予算後の点数 (= 正直な「N/M 表示」用)。
 */
public final class PointCloudSnapshot {

    /** ㉒A ネザー層の水平 1:8 縮尺 (解析時に焼込み済・{@link #toViewSpace} の絶対座標写像にも使う)。 */
    public static final float NETHER_XZ_SCALE = 1.0f / 8.0f;

    public static final PointCloudSnapshot EMPTY = new PointCloudSnapshot(
            new float[0], new float[0], new float[0], new int[0],
            new float[0], new float[0], new float[0], new int[0],
            new float[0], new float[0], new float[0],
            new float[0], new float[0], new float[0],
            0f, 0, 0, 0, 0,
            false, 0f, 0f, 0f, false,
            new float[0], new float[0], new float[0], new boolean[0],
            0f, 0f, 0f, 0f, 0f, 0f, GateMeta.EMPTY);

    // OW 層 (青→青緑)。
    public final float[] owX;
    public final float[] owY;
    public final float[] owZ;
    public final int[] owColor;

    // ネザー層 (橙)。
    public final float[] nX;
    public final float[] nY;
    public final float[] nZ;
    public final int[] nColor;

    // リンク線分: A=OW 端 / B=ネザー端 (描画時に B.y += spacing)。
    public final float[] linkAx;
    public final float[] linkAy;
    public final float[] linkAz;
    public final float[] linkBx;
    public final float[] linkBy;
    public final float[] linkBz;

    /** 水平方向の最大広がり (= 既定カメラ距離の決定に使う)。 */
    public final float radius;

    public final int owSampled;
    public final int netherSampled;
    public final int owDrawn;
    public final int netherDrawn;

    // 解析時点のプレイヤー現在地マーカー (ビュー空間・各層 Y センタリング済、 spacing は描画時加算)。
    public final boolean hasMarker;
    public final float markerX;
    public final float markerY;
    public final float markerZ;
    /** マーカーがネザー層か (描画時 +spacing/2、 OW なら -spacing/2)。 */
    public final boolean markerNether;

    // ⑪ 既知ゲート位置 (OW/ネザー両方・ビュー空間・各層 Y センタリング済、 spacing は描画時加算)。
    // gateNether[i]=true ならネザー層 (描画時 -spacing/2)、 false なら OW 層 (+spacing/2)。
    public final float[] gateX;
    public final float[] gateY;
    public final float[] gateZ;
    public final boolean[] gateNether;

    // ㉕ 各層の重心/平均 Y (絶対ブロック座標→ビュー空間写像に必要・{@link #toViewSpace})。
    // 地形点/リンク/ゲート/現在地マーカーと<b>同一アンカー</b>なので、 ここに通せば back-calculate 要素が
    // 既存要素とズレずにスタック表示される。
    public final float owCenterX;
    public final float owCenterZ;
    public final float owMeanY;
    public final float nCenterX;
    public final float nCenterZ;
    public final float nMeanY;

    /** ㉚ ゲートメタ情報 (採番/状態/コンフリクト・gateX 等と添字一致)。 */
    public final GateMeta gateMeta;

    public PointCloudSnapshot(float[] owX, float[] owY, float[] owZ, int[] owColor,
            float[] nX, float[] nY, float[] nZ, int[] nColor,
            float[] linkAx, float[] linkAy, float[] linkAz,
            float[] linkBx, float[] linkBy, float[] linkBz,
            float radius, int owSampled, int netherSampled, int owDrawn, int netherDrawn,
            boolean hasMarker, float markerX, float markerY, float markerZ, boolean markerNether,
            float[] gateX, float[] gateY, float[] gateZ, boolean[] gateNether,
            float owCenterX, float owCenterZ, float owMeanY,
            float nCenterX, float nCenterZ, float nMeanY, GateMeta gateMeta) {
        this.owX = owX;
        this.owY = owY;
        this.owZ = owZ;
        this.owColor = owColor;
        this.nX = nX;
        this.nY = nY;
        this.nZ = nZ;
        this.nColor = nColor;
        this.linkAx = linkAx;
        this.linkAy = linkAy;
        this.linkAz = linkAz;
        this.linkBx = linkBx;
        this.linkBy = linkBy;
        this.linkBz = linkBz;
        this.radius = radius;
        this.owSampled = owSampled;
        this.netherSampled = netherSampled;
        this.owDrawn = owDrawn;
        this.netherDrawn = netherDrawn;
        this.hasMarker = hasMarker;
        this.markerX = markerX;
        this.markerY = markerY;
        this.markerZ = markerZ;
        this.markerNether = markerNether;
        this.gateX = gateX;
        this.gateY = gateY;
        this.gateZ = gateZ;
        this.gateNether = gateNether;
        this.owCenterX = owCenterX;
        this.owCenterZ = owCenterZ;
        this.owMeanY = owMeanY;
        this.nCenterX = nCenterX;
        this.nCenterZ = nCenterZ;
        this.nMeanY = nMeanY;
        this.gateMeta = gateMeta;
    }

    /**
     * ㉕ <b>絶対ブロック座標</b> (dim 内) を、 地形点/ゲートと同一の<b>ビュー空間</b> (重心センタリング＋
     * ネザー ÷8 焼込み済) へ写す。 戻り値の XZ には<b>表示スケール未適用</b>・Y には<b>spacing 未加算</b>
     * (描画側が gate と同じく XZ へ owScale/nScale を乗算し Y へ ±pivotY を足す＝完全に同経路)。
     *
     * @return {@code [vx, vy, vz]} (ビュー空間・表示スケール/spacing 適用前)
     */
    public float[] toViewSpace(PortalDimension dim, double wx, double wy, double wz) {
        if (dim == PortalDimension.NETHER) {
            return new float[] {
                    (float) ((wx - nCenterX) * NETHER_XZ_SCALE),
                    (float) (wy - nMeanY),
                    (float) ((wz - nCenterZ) * NETHER_XZ_SCALE) };
        }
        return new float[] {
                (float) (wx - owCenterX),
                (float) (wy - owMeanY),
                (float) (wz - owCenterZ) };
    }

    public int gateCount() {
        return gateX.length;
    }

    public int linkCount() {
        return linkAx.length;
    }

    public boolean isEmpty() {
        return owX.length == 0 && nX.length == 0 && linkAx.length == 0;
    }
}
