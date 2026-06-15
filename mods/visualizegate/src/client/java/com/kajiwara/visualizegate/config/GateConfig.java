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
