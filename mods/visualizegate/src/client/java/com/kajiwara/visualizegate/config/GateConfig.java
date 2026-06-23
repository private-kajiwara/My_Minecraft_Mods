package com.kajiwara.visualizegate.config;

/**
 * 永続化される設定の POJO (GSON シリアライズ対象)。
 *
 * <p>{@link com.kajiwara.visualizegate.state.GateMenuState} が単一の真実 (live state) で、
 * この POJO はディスク入出力の器。 欠落フィールドは GSON が既定値のまま残す (前方互換)。
 */
public final class GateConfig {

    public int schemaVersion = 1;
    public boolean boxOverlayEnabled = true;
    public boolean hudIconEnabled = true;

    // UX 層 (純追加・前方互換: 旧 JSON に欠落していても GSON が既定値を残す)。
    public boolean advancedMode = false;
    public boolean legendEnabled = true;
    public boolean firstRunDone = false;
    public boolean hologramEnabled = true;
    public boolean domeEnabled = true;
    public boolean gateNamesEnabled = true; // ゲート名ラベル (在世界・両次元) 既定 ON

    // ゲート可視化 (枠/線/ドーム) の最大表示距離 (m・水平)。 実効描画距離で render 時にクランプ。
    // 既定 128m＝従来は無制限だったが、 全ポータル枠の遠距離描画を抑える穏当な既定 (推奨 96–160)。
    public float gateRenderDistanceM = 128f;

    // ── パフォーマンス: CPU サンプラ制御 (dock 展開連動から分離) ──
    // v1: 既定 OFF。 CPU 計測は開発者向けで、 dock 展開中に JMX スレッドを起こすため、 一般ユーザーには既定で走らせない。
    public boolean cpuSamplingEnabled = false;
    public float cpuSamplingHz = 1.0f;        // サンプリング頻度 (0.5 / 1 / 2 Hz)
    public boolean cpuGraphEnabled = true;    // dock 展開時の CPU スパークライン表示

    // ── 点群データ収集 gate (v1: 既定 OFF) ──
    // 地形カラムの蓄積 (config/visualizegate/tiles/ への書き出し) は、 この opt-in フラグの裏に gate する。
    // 既定 OFF＝点群機能を使わない大多数のユーザーはディスク蓄積ゼロ。 ON にした「その時点から」蓄積が始まる。
    // 既存タイルのロード/表示は本フラグに関係なく常に有効 (過去データは失わない)。
    public boolean pcCaptureEnabled = false;

    // 点群ポップアップの表示オプション (PointCloudViewState の器)。
    public boolean pcShowOverworld = true;
    public boolean pcShowNether = true;
    public boolean pcShowLinks = true;
    public boolean pcDimTint = false; // ⑤ 淡いディメンション色ティント (既定 OFF=純ブロック色)
    public int pcDimensionSpacing = 100;
    public int pcGpuDetail = 20000; // ⑭/⑯ GPU3D 1 層あたり最大描画点数 (品質・中位 GPU 安全既定)
    public int pcPointSize = 2;     // ⑯ GL 点サイズ (px)
    public float pcOwDisplayScale = 1.0f;     // ㉓ OW 層の表示スケール (基準 1:1 × これ・既定=現状一致)
    public float pcNetherDisplayScale = 1.0f; // ㉓ ネザー層の表示スケール (基準 1/8 × これ・既定=現状一致)
    public int pcSidebarW = 200;              // ㉞ サイドバー幅 (スプリッターで可変・ロード時にウィンドウクランプ)
    public Boolean pcOverlayDetail = null;    // ⑤④/⑤⑤B 点群パネルのオーバーレイ詳細度 (null=未設定→実効 詳細・初回既定)
    public boolean pcCloudOnly = false;       // ⑤⑤ 点群ソロ表示 (cloud-only・既定 OFF)
    public boolean pcPanelVisible = false;    // ⑤⑥ 右下点群パネルの可視 (永続ミラー・既定 false=従来どおり非表示)

    public static GateConfig defaults() {
        return new GateConfig();
    }
}
