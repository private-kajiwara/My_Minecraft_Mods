package com.kajiwara.visualizegate.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.state.PointCloudViewState;
import com.kajiwara.visualizegate.state.VgOverlayState;

import net.fabricmc.loader.api.FabricLoader;

/**
 * {@link GateConfig} の JSON 永続化 (OmniChest ConfigManager の軽量踏襲)。
 *
 * <p>保存先 <code>&lt;config&gt;/visualizegate.json</code>。 atomic 書き込み (tmp → ATOMIC_MOVE) で
 * 書込中クラッシュでも喪失しない。 破損/欠落は既定値でフォールバック (= 起動を止めない)。
 * {@link GateMenuState} を単一の真実とし、 load で state へ反映 / save で state から書出す。
 */
public final class GateConfigManager {

    private static final String FILE_NAME = VisualizeGateMod.MOD_ID + ".json";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private GateConfigManager() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 起動時に 1 回。 ディスクから読み GateMenuState へ反映。 無ければ雛形を書く。 失敗は既定値維持。 */
    public static synchronized void load() {
        try {
            Path f = file();
            GateConfig cfg;
            if (Files.exists(f)) {
                try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    cfg = GSON.fromJson(r, GateConfig.class);
                }
                if (cfg == null) {
                    cfg = GateConfig.defaults();
                }
            } else {
                cfg = GateConfig.defaults();
                writeAtomic(f, GSON.toJson(cfg)); // 雛形を作る
            }
            applyToState(cfg);
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn(
                    "[visualizegate] config load failed (defaults kept): {}", t.toString());
        }
    }

    /** GateMenuState の現在値をディスクへ書き出す。 失敗してもログのみ (UI を巻き込まない)。 */
    public static synchronized void save() {
        try {
            writeAtomic(file(), GSON.toJson(currentConfig()));
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] config save failed: {}", t.toString());
        }
    }

    /**
     * 現在の live state からスナップショット POJO を作る (設定画面のステージング初期値用・副作用なし)。
     * 設定画面は編集をこの draft 上で行い、 Save で {@link #apply(GateConfig)}、 Cancel で破棄する。
     */
    public static synchronized GateConfig snapshot() {
        return currentConfig();
    }

    /**
     * draft を live state へ反映しつつディスクへ書き出す (設定画面の Save)。 in-game 側 (レンダラ/ドック/
     * /vg) と同じ state を共有するため、 反映は即座にゲームへ効く。 失敗はログのみ。
     */
    public static synchronized void apply(GateConfig cfg) {
        try {
            applyToState(cfg);
            writeAtomic(file(), GSON.toJson(cfg));
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] config apply failed: {}", t.toString());
        }
    }

    /** live state → POJO (save / snapshot の共通器)。 */
    private static GateConfig currentConfig() {
        GateConfig cfg = new GateConfig();
        cfg.boxOverlayEnabled = GateMenuState.isBoxOverlayEnabled();
        cfg.hudIconEnabled = GateMenuState.isHudIconEnabled();
        cfg.advancedMode = GateMenuState.isAdvancedMode();
        cfg.legendEnabled = GateMenuState.isLegendEnabled();
        cfg.firstRunDone = GateMenuState.isFirstRunDone();
        cfg.hologramEnabled = GateMenuState.isHologramEnabled();
        cfg.domeEnabled = GateMenuState.isDomeEnabled();
        cfg.gateNamesEnabled = GateMenuState.isGateNamesEnabled();
        cfg.gateRenderDistanceM = GateMenuState.getGateRenderDistanceM();
        cfg.cpuSamplingEnabled = VgOverlayState.isCpuSamplingEnabled();
        cfg.cpuSamplingHz = VgOverlayState.getCpuSamplingHz();
        cfg.cpuGraphEnabled = VgOverlayState.isCpuGraphEnabled();
        cfg.pcCaptureEnabled = PointCloudViewState.isCaptureEnabled(); // v1 点群データ収集 gate (既定 OFF)
        cfg.pcShowOverworld = PointCloudViewState.isShowOverworld();
        cfg.pcShowNether = PointCloudViewState.isShowNether();
        cfg.pcShowLinks = PointCloudViewState.isShowLinks();
        cfg.pcDimTint = PointCloudViewState.isDimTint();
        cfg.pcDimensionSpacing = PointCloudViewState.getDimensionSpacing();
        cfg.pcGpuDetail = PointCloudViewState.getGpuDetail();
        cfg.pcPointSize = PointCloudViewState.getPointSize();
        cfg.pcOwDisplayScale = PointCloudViewState.getOwDisplayScale();
        cfg.pcNetherDisplayScale = PointCloudViewState.getNetherDisplayScale();
        cfg.pcSidebarW = PointCloudViewState.getSidebarWidth(); // ㉞ サイドバー幅
        cfg.pcOverlayDetail = PointCloudViewState.getOverlayDetailRaw(); // ⑤④/⑤⑤B 生値 (null=未設定→GSON 省略)
        cfg.pcCloudOnly = PointCloudViewState.isCloudOnly(); // ⑤⑤ 点群ソロ表示 (cloud-only)
        cfg.pcPanelVisible = PointCloudViewState.isPanelVisible(); // ⑤⑥ パネル可視の永続ミラー (deliberate 値)
        return cfg;
    }

    /** POJO → live state (load / apply の共通器)。 */
    private static void applyToState(GateConfig cfg) {
        GateMenuState.setBoxOverlayEnabled(cfg.boxOverlayEnabled);
        GateMenuState.setHudIconEnabled(cfg.hudIconEnabled);
        GateMenuState.setAdvancedMode(cfg.advancedMode);
        GateMenuState.setLegendEnabled(cfg.legendEnabled);
        GateMenuState.setFirstRunDone(cfg.firstRunDone);
        GateMenuState.setHologramEnabled(cfg.hologramEnabled);
        GateMenuState.setDomeEnabled(cfg.domeEnabled);
        GateMenuState.setGateNamesEnabled(cfg.gateNamesEnabled);
        GateMenuState.setGateRenderDistanceM(cfg.gateRenderDistanceM);
        VgOverlayState.setCpuSamplingHz(cfg.cpuSamplingHz);
        VgOverlayState.setCpuGraphEnabled(cfg.cpuGraphEnabled);
        VgOverlayState.setCpuSamplingEnabled(cfg.cpuSamplingEnabled); // 末尾＝最新 Hz/状態で sampler を収束
        PointCloudViewState.setCaptureEnabled(cfg.pcCaptureEnabled); // v1 点群データ収集 gate (既定 OFF)
        PointCloudViewState.setShowOverworld(cfg.pcShowOverworld);
        PointCloudViewState.setShowNether(cfg.pcShowNether);
        PointCloudViewState.setShowLinks(cfg.pcShowLinks);
        PointCloudViewState.setDimTint(cfg.pcDimTint);
        PointCloudViewState.setDimensionSpacing(cfg.pcDimensionSpacing);
        PointCloudViewState.setGpuDetail(cfg.pcGpuDetail);
        PointCloudViewState.setPointSize(cfg.pcPointSize);
        PointCloudViewState.setOwDisplayScale(cfg.pcOwDisplayScale);
        PointCloudViewState.setNetherDisplayScale(cfg.pcNetherDisplayScale);
        PointCloudViewState.setSidebarWidth(cfg.pcSidebarW); // ㉞ 生値 (画面 init で現ウィンドウへ再クランプ)
        PointCloudViewState.setOverlayDetailRaw(cfg.pcOverlayDetail); // ⑤④/⑤⑤B 詳細度 (null=未設定→実効 詳細)
        PointCloudViewState.setCloudOnly(cfg.pcCloudOnly); // ⑤⑤ 点群ソロ表示 (cloud-only)
        // ⑤⑥ パネル可視の永続ミラーを反映し、 セッションの実描画ゲート (VgOverlayState.pointCloud) を seed。
        PointCloudViewState.setPanelVisible(cfg.pcPanelVisible);
        VgOverlayState.setPointCloud(cfg.pcPanelVisible);
    }

    private static void writeAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(content);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
