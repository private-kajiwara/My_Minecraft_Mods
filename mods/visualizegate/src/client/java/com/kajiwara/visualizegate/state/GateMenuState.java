package com.kajiwara.visualizegate.state;

/**
 * メニュー UI のトグル状態 (client・インメモリ)。
 *
 * <p>両トグル既定 ON ＝ 未操作なら既存スライス (枠表示) の挙動は不変。
 * v0 では永続化しない (後段で OmniChest の config 流儀に合わせて足せるよう、
 * アクセスを static getter/setter に集約しておく)。
 */
public final class GateMenuState {

    private static boolean boxOverlayEnabled = true;
    private static boolean hudIconEnabled = true;
    // UX 層 (純追加)。 advancedMode 既定 false=かんたん / legend 既定 ON / firstRunDone 既定 false。
    private static boolean advancedMode = false;
    private static boolean legendEnabled = true;
    private static boolean firstRunDone = false;
    // 機能1 ホログラム枠 (ズレ無し設置位置) 既定 ON。
    private static boolean hologramEnabled = true;
    // 機能3 探索ドーム (リンク検索範囲＋混線検出) 既定 ON。
    private static boolean domeEnabled = true;
    // ゲート名ラベル (各ポータル上の在世界テキスト・状態色・SEE_THROUGH) 既定 ON。
    private static boolean gateNamesEnabled = true;

    private GateMenuState() {
    }

    public static boolean isBoxOverlayEnabled() {
        return boxOverlayEnabled;
    }

    public static void setBoxOverlayEnabled(boolean v) {
        boxOverlayEnabled = v;
    }

    public static boolean toggleBoxOverlay() {
        boxOverlayEnabled = !boxOverlayEnabled;
        return boxOverlayEnabled;
    }

    public static boolean isHudIconEnabled() {
        return hudIconEnabled;
    }

    public static void setHudIconEnabled(boolean v) {
        hudIconEnabled = v;
    }

    public static boolean toggleHudIcon() {
        hudIconEnabled = !hudIconEnabled;
        return hudIconEnabled;
    }

    // ── かんたん/詳細 (card・将来オーバーレイが参照) ──
    public static boolean isAdvancedMode() {
        return advancedMode;
    }

    public static void setAdvancedMode(boolean v) {
        advancedMode = v;
    }

    public static boolean toggleAdvancedMode() {
        advancedMode = !advancedMode;
        return advancedMode;
    }

    // ── 常設凡例 (上級者向け on/off) ──
    public static boolean isLegendEnabled() {
        return legendEnabled;
    }

    public static void setLegendEnabled(boolean v) {
        legendEnabled = v;
    }

    public static boolean toggleLegend() {
        legendEnabled = !legendEnabled;
        return legendEnabled;
    }

    // ── 初回ガイド表示済みフラグ ──
    public static boolean isFirstRunDone() {
        return firstRunDone;
    }

    public static void setFirstRunDone(boolean v) {
        firstRunDone = v;
    }

    // ── 機能1 ホログラム枠 ──
    public static boolean isHologramEnabled() {
        return hologramEnabled;
    }

    public static void setHologramEnabled(boolean v) {
        hologramEnabled = v;
    }

    public static boolean toggleHologram() {
        hologramEnabled = !hologramEnabled;
        return hologramEnabled;
    }

    // ── 機能3 探索ドーム ──
    public static boolean isDomeEnabled() {
        return domeEnabled;
    }

    public static void setDomeEnabled(boolean v) {
        domeEnabled = v;
    }

    public static boolean toggleDome() {
        domeEnabled = !domeEnabled;
        return domeEnabled;
    }

    // ── ゲート名ラベル (在世界・両次元の現存ポータル上) ──
    public static boolean isGateNamesEnabled() {
        return gateNamesEnabled;
    }

    public static void setGateNamesEnabled(boolean v) {
        gateNamesEnabled = v;
    }

    public static boolean toggleGateNames() {
        gateNamesEnabled = !gateNamesEnabled;
        return gateNamesEnabled;
    }
}
