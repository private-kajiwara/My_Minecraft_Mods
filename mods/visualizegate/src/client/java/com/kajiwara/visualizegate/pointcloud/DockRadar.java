package com.kajiwara.visualizegate.pointcloud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.GateNode;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.terrain.TerrainStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * ㊽ ドックのサムネ点群<b>専用</b>の<b>ライブ局所レーダー</b> (フル画面 {@link PointCloudScreen} /
 * {@link PointCloudAnalysis} とは完全分離・whole-world/一時停止のフル画面は不変)。
 *
 * <p>プレイヤー周辺 {@link #LOCAL_RADIUS} ブロックの<b>現在次元</b>の地形を、 既に蓄積されている {@link TerrainStore}
 * から<b>局所読み</b>し (歩くほど鮮度が上がる・新規走査なし)、 ~3Hz にスロットルして<b>専用デーモンワーカー</b>で
 * 小さなスナップショットを組み volatile に publish する。 <b>capture はレンダースレッド</b>で軽量 (有界タイル走査)、
 * 重い組み立てだけオフスレッド＝メイン停滞なし。
 *
 * <p>capture 時のプレイヤー世界座標 ({@link #capX()}/{@link #capY()}/{@link #capZ()}) も公開する＝ドックは
 * 「現在プレイヤー位置 − capture 位置」の差分でカメラ中心を<b>毎フレーム追従</b>できる (geometry 再構築は 3Hz・
 * カメラ中心は毎フレーム＝滑らかに寄る・㊽A)。 局所データなので雲は常にプレイヤー周辺＝㊺A の正しいやり直し。
 */
public final class DockRadar {

    /** バニラ標準の次元境界 (PointCloudAnalysis と同一前提)。 */
    private static final int OW_MIN_Y = -64;
    private static final int OW_MAX_Y = 319;
    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;

    /** 局所レーダー半径 (ブロック・承認値 R≈64)。 */
    public static final int LOCAL_RADIUS = 64;
    /** 再生成スロットル (~3Hz)。 カメラ中心追従はドック側で毎フレーム。 */
    private static final long PERIOD_NANOS = 333_000_000L;

    private static final DockRadar INSTANCE = new DockRadar();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "visualizegate-dockradar");
        t.setDaemon(true);
        return t;
    });

    private volatile PointCloudSnapshot snapshot = PointCloudSnapshot.EMPTY;
    private volatile long generation = 0;
    private long lastCaptureNanos = 0;
    // capture 時プレイヤー世界座標 (毎フレーム追従の差分基準)。 volatile=ドック (レンダースレッド) が読む。
    private volatile double capX;
    private volatile double capY;
    private volatile double capZ;

    private DockRadar() {
    }

    public static DockRadar get() {
        return INSTANCE;
    }

    /**
     * レンダースレッドから (ドック点群描画時に) 呼ぶ。 ~3Hz にスロットルして局所 capture → ワーカー build。
     * 連打/毎フレーム呼びは内部スロットルで安全。
     */
    public void maybeCapture(long now) {
        if (lastCaptureNanos != 0 && (now - lastCaptureNanos) < PERIOD_NANOS) {
            return;
        }
        lastCaptureNanos = now;
        try {
            capture();
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] dock radar capture failed (continuing): {}", t.toString());
        }
    }

    /** メイン (レンダー) スレッドで現在次元の局所入力を不変コピー＝ワーカーへ渡す (ライブ World 非漏洩)。 */
    private void capture() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        PortalDimension dim = PortalMemory.dimOf(mc.level.dimension().identifier().toString());
        if (dim != PortalDimension.OVERWORLD && dim != PortalDimension.NETHER) {
            snapshot = PointCloudSnapshot.EMPTY; // 他次元は局所点群なし (ドックは "empty" 注記)
            return;
        }
        boolean inNether = dim == PortalDimension.NETHER;
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        // 現在次元の局所地形のみ (他層は空＝ "プレイヤー周辺中心" の局所ビュー・#4)。
        int[] ow = inNether ? new int[0]
                : TerrainStore.get().snapshotColumnsLocal(PortalDimension.OVERWORLD, px, pz, LOCAL_RADIUS);
        int[] neth = inNether
                ? TerrainStore.get().snapshotColumnsLocal(PortalDimension.NETHER, px, pz, LOCAL_RADIUS)
                : new int[0];
        // 採番済み全ゲート (範囲外は dock 側で縁クランプ＋方向)。 不変コピーでワーカーへ。
        List<GateNode> gates = new ArrayList<>(PortalMemory.get().gateNodes());

        capX = player.getX();
        capY = player.getY();
        capZ = player.getZ();

        // リンク線はドックでは描かない＝confirmedLinks は空 (capture を軽く保つ)。
        final PointCloudInputs in = new PointCloudInputs(
                ow, neth, gates, Collections.emptyList(),
                OW_MIN_Y, OW_MAX_Y, NETHER_MIN_Y, NETHER_MAX_Y,
                true, player.getX(), player.getY(), player.getZ(), inNether);

        final long myGen = ++generation;
        worker.submit(() -> {
            try {
                PointCloudSnapshot s = PointCloudAnalyzer.analyze(in);
                if (myGen == generation) {
                    snapshot = s;
                }
            } catch (Throwable t) {
                VisualizeGateMod.LOGGER.warn("[visualizegate] dock radar build failed: {}", t.toString());
            }
        });
    }

    public PointCloudSnapshot snapshot() {
        return snapshot;
    }

    public double capX() {
        return capX;
    }

    public double capY() {
        return capY;
    }

    public double capZ() {
        return capZ;
    }
}
