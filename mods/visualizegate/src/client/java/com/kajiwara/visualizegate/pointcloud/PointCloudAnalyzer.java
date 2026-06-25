package com.kajiwara.visualizegate.pointcloud;

import java.util.ArrayList;
import java.util.List;

import com.kajiwara.visualizegate.domain.GateConflictAnalyzer;
import com.kajiwara.visualizegate.domain.GateNode;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.ui.GateColors;

/**
 * {@link PointCloudInputs} → {@link PointCloudSnapshot} の<b>純粋ビルダ</b> (MC 非依存・副作用なし)。
 *
 * <p>ワーカースレッドから呼ぶ。 ライブ World には触れず、 入力の不変コピーだけを読む。 処理:
 * <ol>
 *   <li>⑥ 各層を<b>自分の重心</b>で水平センタリング (OW=OW 重心 / ネザー=ネザー重心)。 <b>ネザーは
 *       1:1 自然スケール</b> (×8 整列なし) ＝ OW のおよそ 1/8 サイズの塊 (ヘッダ「Nether 1:8」と一致)。
 *       各層の平均 Y を算出。</li>
 *   <li>各層を描画予算 {@link #POINT_BUDGET_PER_LAYER} までストライド間引き (= 決定的・乱数不使用)。</li>
 *   <li>各層 Y センタリング (平均) で<b>ビュー空間</b>へ。 配色は⑤の
 *       <b>実ブロック色 (MapColor)</b>＋高さ明暗 (色なしデータはディメンション色グラデへフォールバック)。</li>
 *   <li>㉙ リンク: 永続の<b>確定接続ペア</b> ({@link PointCloudInputs#confirmedLinks()}・開いて繋がった
 *       LINKED のみ) を線分化 (毎解析の再解決はしない＝PortalMemory が記録/剪定)。</li>
 * </ol>
 * <b>垂直分離 (spacing)</b> は織り込まない (描画時に Screen が加算＝ライブスライダ対応)。
 */
public final class PointCloudAnalyzer {

    /**
     * 1 層あたりの描画点上限 (超過はストライド間引き＝決定的)。 細かいドット (1〜3px) で地形面を
     * 表す。 描画は {@link com.kajiwara.visualizegate.ui.PointCloudScreen} のテクスチャバッチ
     * (DynamicTexture + 1 blit) なので<b>静止フレームは点数に依存しない</b> (idle=1 ドローコール)＝
     * 高解像度に上げられる。 回転時のみラスタライズが N に比例 (rebuild ms を HUD で確認しながら調整)。
     * ストライド ({@link com.kajiwara.visualizegate.terrain.TerrainSampler#STRIDE}=4) は据え置き
     * (細かくすると既存 TerrainStore 格子座標の
     * 意味が変わり蓄積済みデータの位置がずれる＋容量 4 倍＝互換破壊のため)。 在庫密度内で予算だけ上げる。
     */
    public static final int POINT_BUDGET_PER_LAYER = 1_000_000;
    /**
     * ㉒A ネザー層の<b>水平 1:8 縮尺</b>。 各層は自分の重心で中心化するため、 重心化だけでは縮尺は
     * 「探索した範囲の広さ」次第になり (＝territory 比が崩れると 1:1 に見える)。 ヘッダ "Nether 1:8" を
     * 確実にするため、 ネザーの (X,Z) を重心からこの倍率で縮める (Y は自然＝spacing で縦分離)。 OW は 1:1。
     */
    private static final float NETHER_XZ_SCALE = 1.0f / 8.0f;

    private PointCloudAnalyzer() {
    }

    public static PointCloudSnapshot analyze(PointCloudInputs in) {
        int owN = in.owTerrain().length / 4;   // flat 4 つ組 (wx, wz, y, color)
        int nN = in.netherTerrain().length / 4;

        // ── 1. 水平重心・各層平均 Y (OW スケール) ──
        double owSumX = 0;   // OW 重心 (= OW 層の視野中心)
        double owSumZ = 0;
        double nSumX = 0;    // ⑥ ネザー重心 (1:1・自然スケールで自分の中心へ置く)
        double nSumZ = 0;
        long hCount = 0;
        long owYSum = 0;
        long nYSum = 0;
        int owYMin = Integer.MAX_VALUE;
        int owYMax = Integer.MIN_VALUE;
        int nYMin = Integer.MAX_VALUE;
        int nYMax = Integer.MIN_VALUE;
        for (int i = 0; i < owN; i++) {
            int x = in.owTerrain()[i * 4];
            int z = in.owTerrain()[i * 4 + 1];
            int y = in.owTerrain()[i * 4 + 2];
            owSumX += x;
            owSumZ += z;
            hCount++;
            owYSum += y;
            owYMin = Math.min(owYMin, y);
            owYMax = Math.max(owYMax, y);
        }
        for (int i = 0; i < nN; i++) {
            int x = in.netherTerrain()[i * 4];       // ⑥ 1:1 (×8 を外す)
            int z = in.netherTerrain()[i * 4 + 1];
            int y = in.netherTerrain()[i * 4 + 2];
            nSumX += x;
            nSumZ += z;
            hCount++;
            nYSum += y;
            nYMin = Math.min(nYMin, y);
            nYMax = Math.max(nYMax, y);
        }
        if (hCount == 0) {
            // 地形が無くてもポータル/リンクだけは描けるよう重心はポータルから取る。
            return analyzePortalsOnly(in);
        }
        // ⑥ 各層を<b>自分の重心</b>で中心化する: OW=OW 重心、 ネザー=ネザー重心 (1:1・自然スケール)。
        // ネザーの ×8 整列と R_ow クリップは廃止 → ネザーは OW のおよそ 1/8 サイズのコンパクトな塊に
        // なる (ヘッダ「Nether 1:8」と一致)。 リンク線は各端をそれぞれの層変換で置く＝実位置どうしを結び
        // 扇状に開く (terrain と端点は同一変換なのでズレない)。
        float owCenterX = (owN > 0) ? (float) (owSumX / owN) : 0f;
        float owCenterZ = (owN > 0) ? (float) (owSumZ / owN) : 0f;
        float nCenterX = (nN > 0) ? (float) (nSumX / nN) : 0f;
        float nCenterZ = (nN > 0) ? (float) (nSumZ / nN) : 0f;
        float owMeanY = (owN > 0) ? (float) ((double) owYSum / owN) : 0f;
        float nMeanY = (nN > 0) ? (float) ((double) nYSum / nN) : 0f;

        // ── 2-3. 各層を間引き＋センタリング＋配色 ──
        int owStride = stride(owN, POINT_BUDGET_PER_LAYER);
        int nStride = stride(nN, POINT_BUDGET_PER_LAYER);
        int owDrawn = countKept(owN, owStride);
        int nDrawn = countKept(nN, nStride);

        float[] owX = new float[owDrawn];
        float[] owY = new float[owDrawn];
        float[] owZ = new float[owDrawn];
        int[] owColor = new int[owDrawn];
        int k = 0;
        for (int i = 0; i < owN; i += owStride) {
            int x = in.owTerrain()[i * 4];
            int z = in.owTerrain()[i * 4 + 1];
            int y = in.owTerrain()[i * 4 + 2];
            int color = in.owTerrain()[i * 4 + 3];
            owX[k] = x - owCenterX;
            owY[k] = y - owMeanY;
            owZ[k] = z - owCenterZ;
            owColor[k] = blockOrDimColor(color, GateColors.PC_OW_LOW, GateColors.PC_OW_HIGH,
                    norm(y, owYMin, owYMax));
            k++;
        }

        // ネザーは上限 nDrawn 個で確保し、 R_ow クリップを通った点だけ詰める (nk = 実描画数)。
        float[] nXt = new float[nDrawn];
        float[] nYt = new float[nDrawn];
        float[] nZt = new float[nDrawn];
        int[] nColort = new int[nDrawn];
        int nk = 0;
        for (int i = 0; i < nN; i += nStride) {
            int x = in.netherTerrain()[i * 4];       // ⑥ 1:1 (×8 を外す)
            int z = in.netherTerrain()[i * 4 + 1];
            int y = in.netherTerrain()[i * 4 + 2];
            int color = in.netherTerrain()[i * 4 + 3];
            nXt[nk] = (x - nCenterX) * NETHER_XZ_SCALE; // ㉒A 1:8 水平縮尺
            nYt[nk] = y - nMeanY;
            nZt[nk] = (z - nCenterZ) * NETHER_XZ_SCALE;
            nColort[nk] = blockOrDimColor(color, GateColors.PC_NETHER_LOW, GateColors.PC_NETHER_HIGH,
                    norm(y, nYMin, nYMax));
            nk++;
        }
        float[] nX = (nk == nDrawn) ? nXt : java.util.Arrays.copyOf(nXt, nk);
        float[] nY = (nk == nDrawn) ? nYt : java.util.Arrays.copyOf(nYt, nk);
        float[] nZ = (nk == nDrawn) ? nZt : java.util.Arrays.copyOf(nZt, nk);
        int[] nColor = (nk == nDrawn) ? nColort : java.util.Arrays.copyOf(nColort, nk);

        // ── 4. リンク (OW→ネザー LINKED のみ) ＋ ⑪ 既知ゲート位置 (OW/ネザー全部) ──
        Links links = buildLinks(in, owCenterX, owCenterZ, nCenterX, nCenterZ, owMeanY, nMeanY);
        Marker mk = marker(in, owCenterX, owCenterZ, nCenterX, nCenterZ, owMeanY, nMeanY);
        Gates gates = buildGates(in, owCenterX, owCenterZ, nCenterX, nCenterZ, owMeanY, nMeanY);
        GateMeta gateMeta = buildGateMeta(in);

        float radius = horizontalRadius(owX, owZ, nX, nZ);
        return new PointCloudSnapshot(owX, owY, owZ, owColor, nX, nY, nZ, nColor,
                links.ax, links.ay, links.az, links.bx, links.by, links.bz,
                radius, owN, nN, owDrawn, nk,
                mk.present(), mk.x(), mk.y(), mk.z(), mk.nether(),
                gates.x, gates.y, gates.z, gates.nether,
                owCenterX, owCenterZ, owMeanY, nCenterX, nCenterZ, nMeanY, gateMeta);
    }

    /** 地形ゼロ時: ポータルのみで各層の重心を取って組む (⑥ ネザーも 1:1・自分の重心)。 */
    private static PointCloudSnapshot analyzePortalsOnly(PointCloudInputs in) {
        double owSumX = 0;
        double owSumZ = 0;
        double nSumX = 0;
        double nSumZ = 0;
        long owYSum = 0;
        long nYSum = 0;
        int owc = 0;
        int nc = 0;
        for (GateNode g : in.gates()) {
            if (g.dim() == PortalDimension.NETHER) {
                nSumX += g.x();
                nSumZ += g.z();
                nYSum += g.y();
                nc++;
            } else if (g.dim() == PortalDimension.OVERWORLD) {
                owSumX += g.x();
                owSumZ += g.z();
                owYSum += g.y();
                owc++;
            }
        }
        if (owc + nc == 0) {
            return PointCloudSnapshot.EMPTY;
        }
        float owCenterX = (owc > 0) ? (float) (owSumX / owc) : 0f;
        float owCenterZ = (owc > 0) ? (float) (owSumZ / owc) : 0f;
        float nCenterX = (nc > 0) ? (float) (nSumX / nc) : 0f;
        float nCenterZ = (nc > 0) ? (float) (nSumZ / nc) : 0f;
        float owMeanY = (owc == 0) ? 0f : (float) ((double) owYSum / owc);
        float nMeanY = (nc == 0) ? 0f : (float) ((double) nYSum / nc);
        Links links = buildLinks(in, owCenterX, owCenterZ, nCenterX, nCenterZ, owMeanY, nMeanY);
        Marker mk = marker(in, owCenterX, owCenterZ, nCenterX, nCenterZ, owMeanY, nMeanY);
        Gates gates = buildGates(in, owCenterX, owCenterZ, nCenterX, nCenterZ, owMeanY, nMeanY);
        GateMeta gateMeta = buildGateMeta(in);
        float radius = horizontalRadius(links.ax, links.az, links.bx, links.bz);
        return new PointCloudSnapshot(new float[0], new float[0], new float[0], new int[0],
                new float[0], new float[0], new float[0], new int[0],
                links.ax, links.ay, links.az, links.bx, links.by, links.bz,
                radius, 0, 0, 0, 0,
                mk.present(), mk.x(), mk.y(), mk.z(), mk.nether(),
                gates.x, gates.y, gates.z, gates.nether,
                owCenterX, owCenterZ, owMeanY, nCenterX, nCenterZ, nMeanY, gateMeta);
    }

    /**
     * ㉙ 接続線は<b>永続の確定ペア</b> {@link PointCloudInputs#confirmedLinks()} (開いて繋がった LINKED のみ・
     * セッション跨ぎ) から引く。 もう毎解析の再解決はしない (PortalMemory が tick/capture で確定ペアを記録/剪定)。
     * 各端 anchor を点群と同一のビュー変換 (OW=OW 重心 / ネザー=ネザー重心 ×{@link #NETHER_XZ_SCALE}) へ写す＝
     * 地形/ゲートと整合 (spacing は描画時加算)。
     */
    private static Links buildLinks(PointCloudInputs in, float owCenterX, float owCenterZ,
            float nCenterX, float nCenterZ, float owMeanY, float nMeanY) {
        List<int[]> pairs = in.confirmedLinks();
        Links out = new Links(pairs.size());
        int i = 0;
        for (int[] p : pairs) { // p = {owX,owY,owZ, nX,nY,nZ}
            out.ax[i] = p[0] - owCenterX;     // A 端 (OW 層・OW 重心センタリング)
            out.ay[i] = p[1] - owMeanY;
            out.az[i] = p[2] - owCenterZ;
            out.bx[i] = (p[3] - nCenterX) * NETHER_XZ_SCALE; // B 端 (ネザー層・1:8・terrain と同変換)
            out.by[i] = p[4] - nMeanY;
            out.bz[i] = (p[5] - nCenterZ) * NETHER_XZ_SCALE;
            i++;
        }
        return out;
    }

    /**
     * ⑪/㉚ 既知ゲート ({@link GateNode}・OW 先ネザー後) を点群と同じビュー空間へ写す (OW=OW 変換 / ネザー=1:1×1/8)。
     * 配列添字は {@code in.gates()} の順と一致＝採番/状態 (GateMeta) と添字対応。
     */
    private static Gates buildGates(PointCloudInputs in, float owCenterX, float owCenterZ,
            float nCenterX, float nCenterZ, float owMeanY, float nMeanY) {
        List<GateNode> nodes = in.gates();
        int n = nodes.size();
        float[] gx = new float[n];
        float[] gy = new float[n];
        float[] gz = new float[n];
        boolean[] gn = new boolean[n];
        int[] num = new int[n];
        for (int k = 0; k < n; k++) {
            GateNode g = nodes.get(k);
            boolean nether = g.dim() == PortalDimension.NETHER;
            if (nether) {
                gx[k] = (g.x() - nCenterX) * NETHER_XZ_SCALE;   // ㉒A 1:8 水平縮尺
                gy[k] = g.y() - nMeanY;
                gz[k] = (g.z() - nCenterZ) * NETHER_XZ_SCALE;
            } else {
                gx[k] = g.x() - owCenterX;
                gy[k] = g.y() - owMeanY;
                gz[k] = g.z() - owCenterZ;
            }
            gn[k] = nether;
            num[k] = g.number();
        }
        return new Gates(gx, gy, gz, gn, num);
    }

    private record Gates(float[] x, float[] y, float[] z, boolean[] nether, int[] number) {
    }

    /**
     * ㉚ ゲートメタ (採番/状態/コンフリクト/リンク番号) を組む。 状態は {@link GateConflictAnalyzer} (純) で算出し、
     * {@code in.gates()} の順＝ゲート配列の添字と一致させる。 リンク番号は確定ペアの両端 anchor を採番へ照合。
     */
    private static GateMeta buildGateMeta(PointCloudInputs in) {
        List<GateNode> nodes = in.gates();
        int n = nodes.size();
        GateConflictAnalyzer.Result r = GateConflictAnalyzer.analyze(nodes,
                in.netherMinY(), in.netherMaxY(), in.owMinY(), in.owMaxY());
        int[] num = new int[n];
        int[] stateOrd = new int[n];
        int[] wx = new int[n];
        int[] wy = new int[n];
        int[] wz = new int[n];
        for (int i = 0; i < n; i++) {
            GateNode g = nodes.get(i);
            num[i] = g.number();
            stateOrd[i] = r.states()[i].ordinal();
            wx[i] = g.x();
            wy[i] = g.y();
            wz[i] = g.z();
        }
        List<int[]> pairs = in.confirmedLinks();
        int[] linkOw = new int[pairs.size()];
        int[] linkN = new int[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            int[] p = pairs.get(i);
            linkOw[i] = numberAtAnchor(nodes, PortalDimension.OVERWORLD, p[0], p[1], p[2]);
            linkN[i] = numberAtAnchor(nodes, PortalDimension.NETHER, p[3], p[4], p[5]);
        }
        return new GateMeta(num, stateOrd, wx, wy, wz, linkOw, linkN, r.conflicts());
    }

    private static int numberAtAnchor(List<GateNode> nodes, PortalDimension dim, int x, int y, int z) {
        for (GateNode g : nodes) {
            if (g.dim() == dim && g.x() == x && g.y() == y && g.z() == z) {
                return g.number();
            }
        }
        return 0;
    }

    /** プレイヤー現在地を点群と同じビュー空間 (⑥ ネザーは 1:1・各層は自分の重心と Y 平均) へ写す。 */
    private static Marker marker(PointCloudInputs in, float owCenterX, float owCenterZ,
            float nCenterX, float nCenterZ, float owMeanY, float nMeanY) {
        if (!in.playerPresent()) {
            return Marker.NONE;
        }
        if (in.playerInNether()) {
            return new Marker(true,
                    (float) ((in.playerX() - nCenterX) * NETHER_XZ_SCALE), // ㉒A 1:8 水平縮尺
                    (float) (in.playerY() - nMeanY),
                    (float) ((in.playerZ() - nCenterZ) * NETHER_XZ_SCALE),
                    true);
        }
        return new Marker(true,
                (float) (in.playerX() - owCenterX),
                (float) (in.playerY() - owMeanY),
                (float) (in.playerZ() - owCenterZ),
                false);
    }

    private record Marker(boolean present, float x, float y, float z, boolean nether) {
        static final Marker NONE = new Marker(false, 0f, 0f, 0f, false);
    }

    private static final class Links {
        final float[] ax;
        final float[] ay;
        final float[] az;
        final float[] bx;
        final float[] by;
        final float[] bz;

        Links(int n) {
            ax = new float[n];
            ay = new float[n];
            az = new float[n];
            bx = new float[n];
            by = new float[n];
            bz = new float[n];
        }
    }

    // ── ヘルパ ──────────────────────────────────────────────────────────

    private static int stride(int count, int budget) {
        if (count <= budget || count == 0) {
            return 1;
        }
        return (count + budget - 1) / budget;
    }

    private static int countKept(int count, int stride) {
        if (count == 0) {
            return 0;
        }
        return (count + stride - 1) / stride;
    }

    private static float norm(int v, int min, int max) {
        if (max <= min) {
            return 0.5f;
        }
        return (float) (v - min) / (float) (max - min);
    }

    /** 水平 (X,Z) の最大半径 (2 層分)。 */
    private static float horizontalRadius(float[] x1, float[] z1, float[] x2, float[] z2) {
        float max = 0f;
        for (int i = 0; i < x1.length; i++) {
            max = Math.max(max, x1[i] * x1[i] + z1[i] * z1[i]);
        }
        for (int i = 0; i < x2.length; i++) {
            max = Math.max(max, x2[i] * x2[i] + z2[i] * z2[i]);
        }
        return (float) Math.sqrt(max);
    }

    /**
     * ⑤ 点の基本色: ブロック色 (MapColor 由来 0xRRGGBB) があれば高さ明暗を<b>軽く</b>乗せて使い、
     * 無ければ (色なしデータ＝再訪前) 従来のディメンション色グラデへフォールバック。 距離フェードは
     * 描画側 ({@code PointCloudScreen}) で別途乗る。 戻り値はアルファ 0xFF 付き ARGB。
     */
    private static int blockOrDimColor(int storedColor, int dimLow, int dimHigh, float heightNorm) {
        if (storedColor == com.kajiwara.visualizegate.terrain.TerrainSampler.NO_COLOR) {
            return lerp(dimLow, dimHigh, heightNorm); // 色なし → ディメンション色 (正直なフォールバック)
        }
        return shadeByHeight(storedColor, heightNorm);
    }

    /** ブロック色 (0xRRGGBB) に高さ明暗を軽く乗せる (0.8〜1.2 倍・縮小)。 アルファ 0xFF。 */
    private static int shadeByHeight(int rgb, float t) {
        float f = 0.8f + 0.4f * Math.max(0f, Math.min(1f, t));
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((rgb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** ARGB 線形補間 (t=0→a / t=1→b)。 */
    private static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int ba = (b >> 24) & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int rr = Math.round(ar + (br - ar) * t);
        int rg = Math.round(ag + (bg - ag) * t);
        int rb = Math.round(ab + (bb - ab) * t);
        int ra = Math.round(aa + (ba - aa) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}
