package com.kajiwara.visualizegate.state;

/**
 * 点群ポップアップの表示オプション (client・インメモリ・{@link GateMenuState} と同流儀)。
 *
 * <p>レイヤートグル 3 種と「ディメンション間隔」(垂直分離量) を保持する。 これらは描画時のみに効く
 * <b>ビュー設定</b>で、 解析スナップショットには織り込まれない (= スライダ変更で再解析不要)。
 * 永続化は {@link com.kajiwara.visualizegate.config.GateConfigManager} 経由 ({@code visualizegate.json})。
 */
public final class PointCloudViewState {

    /** 間隔スライダの範囲 (OW スケールのビュー単位)。 */
    public static final int SPACING_MIN = 0;
    public static final int SPACING_MAX = 400;
    public static final int SPACING_DEFAULT = 100;

    /**
     * ⑭ GPU3D 描画品質 = 1 層あたりの最大描画点数 (スライダ範囲)。 解析スナップショットは不変のまま、
     * 描画時に stride 間引きで上限化する (= 再解析不要・ライブ調整可)。 <b>既定は中位 GPU
     * (GTX1660/RX580 級・1080p) で滑らかな安全値</b>。 上限は高性能勢向け (= スナップショット上限)。
     * GPU3D は Screen 表示中のみ・SP は停止中＝通常プレイ FPS/サーバーに影響しない。
     */
    public static final int DETAIL_MIN = 500;
    public static final int DETAIL_MAX = 1_500_000; // ㉒ ハード上限 (=store CAP)。 実効上限はスライダが現 stock に追従。
    public static final int DETAIL_DEFAULT = 20_000; // ⑯ 中位 GPU 安全値 (低スペックはスライダで下げる)

    /** ⑯ GL 点サイズ (ピクセル・スライダ範囲)。 小さく＝密で滑らかな高密度クラウド (参照寄せ)。 */
    public static final int POINT_SIZE_MIN = 1;
    public static final int POINT_SIZE_MAX = 6;
    public static final int POINT_SIZE_DEFAULT = 2;

    /**
     * ㉓ 層ごとの<b>表示スケール</b> (基準形 OW1:1・ネザー1/8 の<b>上に重ねる</b> XZ 倍率)。 基準形は不変で、
     * 各層を<b>自分の重心基準</b>で「基準スケール × 表示スケール」へ拡縮する (= スナップショットは触らず描画時に
     * 合成＝再解析不要・ライブ)。 既定 1.0/1.0 では現状の 1:8 と完全に同じ見た目 (回帰ゼロ)。 範囲は対数で
     * 1.0 が中央 (min×max=1 の対称範囲)。 Y/spacing には一切効かない (XZ のみ)。
     *
     * <p>㉘ <b>層ごとに範囲を分離</b>: OW は ×0.25〜×4 (従来通り)、 ネザーは ×{@code 1/NETHER_SCALE_MAX}〜
     * ×{@code NETHER_SCALE_MAX} (=×16) へ拡張。 ネザー ×8 で基準÷8 を相殺＝OW と同じ footprint、 ×16 で OW の 2 倍。
     * 後微調整は {@link #NETHER_SCALE_MAX} 1 定数で (min は 1/max ＝対数中央 1.0 維持)。
     */
    public static final float DISPLAY_SCALE_DEFAULT = 1.0f;
    /** OW 表示スケール範囲 (従来通り・1.0 中央)。 */
    public static final float OW_SCALE_MAX = 4.0f;
    public static final float OW_SCALE_MIN = 1.0f / OW_SCALE_MAX;
    /** ㉘ ネザー表示スケール範囲 (拡張・1.0 中央)。 max を上げるだけで両端が対称に広がる。 */
    public static final float NETHER_SCALE_MAX = 16.0f;
    public static final float NETHER_SCALE_MIN = 1.0f / NETHER_SCALE_MAX;

    /**
     * ㉞ サイドバー (右パネル) 幅。 スプリッターのドラッグで可変・config 永続。 ここでは生値を保持し、
     * ウィンドウ幅依存のクランプ (= MIN_VP を潰さない) は {@link com.kajiwara.visualizegate.ui.PointCloudScreen}
     * 側で現在の {@code width} に対して行う (state はウィンドウ寸を知らないため)。
     */
    public static final int SIDEBAR_W_DEFAULT = 200;
    /** ㉞ サイドバー最小幅 (タブ「View/Gates/Links」と主要操作が収まる下限)。 */
    public static final int SIDEBAR_W_MIN = 150;

    private static boolean showOverworld = true;
    private static boolean showNether = true;
    private static boolean showLinks = true;
    /** ⑤ 淡いディメンション色ティント (ブロック色へ dim 色を 15% 混ぜる)。 既定 OFF＝純ブロック色。 */
    private static boolean dimTint = false;
    private static int dimensionSpacing = SPACING_DEFAULT;
    private static int gpuDetail = DETAIL_DEFAULT;
    private static int pointSize = POINT_SIZE_DEFAULT;
    private static float owDisplayScale = DISPLAY_SCALE_DEFAULT;     // ㉓ OW 層の表示スケール (基準 1:1 × これ)
    private static float netherDisplayScale = DISPLAY_SCALE_DEFAULT; // ㉓ ネザー層の表示スケール (基準 1/8 × これ)
    private static int sidebarWidth = SIDEBAR_W_DEFAULT;             // ㉞ サイドバー幅 (生値・画面側でウィンドウクランプ)
    /**
     * ⑤④/⑤⑤ 右下点群パネルのオーバーレイ詳細度 (false=簡略 / true=詳細)。 config 永続。
     * <b>⑤⑤B 三値</b>: {@code null}=未設定＝<b>実効=詳細</b> (初回既定)。 ユーザが {@code /vg detail} か
     * {@code only detail|compact} で明示すると非 null 値が入り永続 (= 一度選んだらその選択を保持)。 既存 config に
     * 明示 {@code false} が書かれていればそのまま簡略 (⑤④ は毎回 false を書いていたので移行不要)。
     */
    private static Boolean overlayDetail = null;
    /** ⑤⑤ 点群ソロ表示 (cloud-only): ON で点群パネル以外の VG HUD を抑止。 config 永続・既定 OFF。 */
    private static boolean cloudOnly = false;
    /**
     * ⑤⑥ 右下点群パネルの可視 (永続ミラー・既定 false=従来どおり非表示)。 セッションの実描画ゲートは
     * {@code VgOverlayState.pointCloud} で、 起動時に load がこの永続値から seed する。 deliberate コマンド
     * (only/show/clean) だけがこれを set＆save する (切断のセッションリセットでは書かない＝設定を消さない)。
     */
    private static boolean panelVisible = false;

    private PointCloudViewState() {
    }

    /** ⑤⑥ 点群パネル可視の永続ミラー (実描画ゲートは VgOverlayState.isPointCloud)。 */
    public static boolean isPanelVisible() {
        return panelVisible;
    }

    public static void setPanelVisible(boolean v) {
        panelVisible = v;
    }

    /** ⑤⑤ 点群ソロ表示 (cloud-only) の生フラグ。 実効 solo は {@code VgOverlayState.isCloudSolo()} (パネルON時のみ)。 */
    public static boolean isCloudOnly() {
        return cloudOnly;
    }

    public static void setCloudOnly(boolean v) {
        cloudOnly = v;
    }

    /** ⑤④/⑤⑤B 点群パネルのオーバーレイ詳細度の<b>実効値</b> (未設定 null → 詳細＝初回既定)。 */
    public static boolean isOverlayDetail() {
        return overlayDetail != null ? overlayDetail : true;
    }

    /** 明示設定 (非 null 値が入り永続される)。 */
    public static void setOverlayDetail(boolean v) {
        overlayDetail = v;
    }

    public static boolean toggleOverlayDetail() {
        setOverlayDetail(!isOverlayDetail());
        return overlayDetail;
    }

    /** ⑤⑤B 永続用の生値 (null=未設定)。 config 保存時に書き出す (GSON は null フィールドを省略＝未設定を保つ)。 */
    public static Boolean getOverlayDetailRaw() {
        return overlayDetail;
    }

    /** ⑤⑤B config ロード時に生値を反映 (null=未設定のまま＝実効 詳細)。 */
    public static void setOverlayDetailRaw(Boolean v) {
        overlayDetail = v;
    }

    /** ㉞ サイドバー幅 (生値)。 画面側がウィンドウ幅で再クランプして使う。 */
    public static int getSidebarWidth() {
        return sidebarWidth;
    }

    /** ㉞ サイドバー幅を設定 (下限のみ適用・上限はウィンドウ依存で画面側がクランプ)。 */
    public static void setSidebarWidth(int v) {
        sidebarWidth = Math.max(SIDEBAR_W_MIN, v);
    }

    public static boolean isShowOverworld() {
        return showOverworld;
    }

    public static void setShowOverworld(boolean v) {
        showOverworld = v;
    }

    public static boolean toggleOverworld() {
        showOverworld = !showOverworld;
        return showOverworld;
    }

    public static boolean isShowNether() {
        return showNether;
    }

    public static void setShowNether(boolean v) {
        showNether = v;
    }

    public static boolean toggleNether() {
        showNether = !showNether;
        return showNether;
    }

    public static boolean isShowLinks() {
        return showLinks;
    }

    public static void setShowLinks(boolean v) {
        showLinks = v;
    }

    public static boolean toggleLinks() {
        showLinks = !showLinks;
        return showLinks;
    }

    public static boolean isDimTint() {
        return dimTint;
    }

    public static void setDimTint(boolean v) {
        dimTint = v;
    }

    public static boolean toggleDimTint() {
        dimTint = !dimTint;
        return dimTint;
    }

    public static int getDimensionSpacing() {
        return dimensionSpacing;
    }

    public static void setDimensionSpacing(int v) {
        dimensionSpacing = Math.max(SPACING_MIN, Math.min(SPACING_MAX, v));
    }

    /** ⑭ GPU3D の 1 層あたり最大描画点数 (品質設定)。 */
    public static int getGpuDetail() {
        return gpuDetail;
    }

    public static void setGpuDetail(int v) {
        gpuDetail = Math.max(DETAIL_MIN, Math.min(DETAIL_MAX, v));
    }

    /** ⑯ GL 点サイズ (px)。 */
    public static int getPointSize() {
        return pointSize;
    }

    public static void setPointSize(int v) {
        pointSize = Math.max(POINT_SIZE_MIN, Math.min(POINT_SIZE_MAX, v));
    }

    /** ㉓ OW 層の表示スケール (基準 1:1 に乗算)。 */
    public static float getOwDisplayScale() {
        return owDisplayScale;
    }

    public static void setOwDisplayScale(float v) {
        owDisplayScale = clampScale(v, OW_SCALE_MIN, OW_SCALE_MAX);
    }

    /** ㉓ ネザー層の表示スケール (基準 1/8 に乗算)。 */
    public static float getNetherDisplayScale() {
        return netherDisplayScale;
    }

    public static void setNetherDisplayScale(float v) {
        netherDisplayScale = clampScale(v, NETHER_SCALE_MIN, NETHER_SCALE_MAX);
    }

    /** ㉘ 層別範囲でクランプ (旧保存値 ≤4 は新範囲内＝移行不要)。 NaN は既定 1.0。 */
    private static float clampScale(float v, float min, float max) {
        if (Float.isNaN(v)) {
            return DISPLAY_SCALE_DEFAULT;
        }
        return Math.max(min, Math.min(max, v));
    }
}
