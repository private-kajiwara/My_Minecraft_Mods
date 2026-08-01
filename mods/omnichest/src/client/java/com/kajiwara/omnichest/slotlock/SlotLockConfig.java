package com.kajiwara.omnichest.slotlock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kajiwara.omnichest.OmniChest;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Favorite Slot Lock System 用の設定。
 *
 * <p>
 * 保存先: <code>&lt;config&gt;/omnichest/slot_lock_config.json</code>
 *
 * <p>
 * フィールドは public mutable とし、 GUI から直接編集する。
 * 永続化は {@link #save()} を呼ぶ。
 */
public final class SlotLockConfig {

    private static final String FILE_NAME = "slot_lock_config.json";

    // ────────────────────────────────────────────────────────────────────
    // UI / Visual
    // ────────────────────────────────────────────────────────────────────

    /** スロットに鍵 / 星オーバーレイを描画するか。 */
    public boolean showOverlay = true;

    /**
     * ロックスロットの上に半透明色帯を被せるか。
     * デフォルト false (= アイテムアイコンが見づらくなるので無効)。
     * 視認性を最大化したいユーザーは true に切替。
     */
    public boolean showTint = false;

    /** ロックスロットの周囲に淡い枠 (Glow) を出すか。 */
    public boolean showGlow = true;

    /**
     * ロックスロットの周囲に「太い白枠 (vanilla hover ライク)」を出すか。
     * Glow 色 (青/橙) より目立つので、デフォルト ON。
     */
    public boolean showStrongOutline = true;

    /**
     * Tint / Glow / Outline をマウスホバーに合わせて脈動 (pulse) させるか。
     * デフォルト ON: 静止画でも 1Hz 程度で alpha が変動するためロック状態が一目で分かる。
     */
    public boolean pulseAnimation = true;

    /** Tooltip に [LOCKED SLOT] / [LOCKED ITEM] 行を追記するか。 */
    public boolean showTooltipLine = true;

    // ────────────────────────────────────────────────────────────────────
    // 操作系
    // ────────────────────────────────────────────────────────────────────

    /** Alt + Click でロック切替を有効にするか。 */
    public boolean toggleWithAltClick = true;

    // 注: 「中クリックでロック切替」 は <b>ここには無い</b>。 中クリックは
    // {@link com.kajiwara.omnichest.client.ClientKeyBindings#TOGGLE_SLOT_LOCK_KEY} の
    // <b>既定バインド</b> であり、 有効 / 無効 / 再割当は vanilla の Controls 画面が唯一の源。
    // (旧 toggleWithMiddleClick は、 Controls で未割当にしても中クリックが効き続ける原因だったため廃止。
    //  既存の slot_lock_config.json に残っている同名プロパティは読み飛ばされるだけで無害。)

    /** Shift + Alt + Click で SLOT → ITEM → 解除の 3 状態サイクルにするか。 */
    public boolean cycleWithShiftAltClick = true;

    // ────────────────────────────────────────────────────────────────────
    // 保護ポリシー (= デフォルト保護)
    // ────────────────────────────────────────────────────────────────────

    /** Hotbar (0..8) を「明示ロック無くてもデフォルトで自動整理対象外」にするか。 */
    public boolean protectHotbarByDefault = false;

    /** オフハンド (40) を「明示ロック無くてもデフォルトで自動整理対象外」にするか。 */
    public boolean protectOffhandByDefault = false;

    /**
     * アーマー (36..39) を「明示ロック無くてもデフォルトで自動整理対象外」にするか。
     * デフォルト false (= ユーザーが明示的にロックしない限り装備スロットに UI を出さない)。
     * 装備スロットを保護したいユーザーは true に切替 or 各スロットを Alt+Click でロックする。
     */
    public boolean protectArmorByDefault = false;

    /**
     * 手動操作 (= プレイヤー自身のクリック / shift-click / drag) もブロックするか。
     * デフォルトは false (= 自動整理処理のみ制限する)。
     * 本当に「絶対動かしたくない」場合に true にする上級設定。
     */
    public boolean blockManualOverride = false;

    // ────────────────────────────────────────────────────────────────────
    // 高度な ITEM-mode 設定
    // ────────────────────────────────────────────────────────────────────

    /**
     * ITEM モードの「Diamond Pickaxe を追跡」で、移動先を見失わなかったときに
     * Manager の中身を JSON へ反映するか。
     * デフォルト true (= 追跡したら即セーブ)。
     */
    public boolean persistItemModeReassignment = true;

    /**
     * 自動投入 (Smart Deposit) が ITEM ロック対象のスタックを箱へ送るのを
     * 防止するか。
     * デフォルト true (= プレイヤーが手で動かさない限り、ロック対象は手元に残る)。
     */
    public boolean blockSmartDepositOfItemLocked = true;

    // ────────────────────────────────────────────────────────────────────
    // load / save (= 他 Config と同パターン)
    // ────────────────────────────────────────────────────────────────────

    private static SlotLockConfig instance;

    public static synchronized SlotLockConfig get() {
        if (instance == null) {
            instance = new SlotLockConfig();
            try {
                instance.load();
            } catch (Exception ex) {
                OmniChest.LOGGER.warn(
                        "[omnichest] SlotLockConfig.load 失敗: {}. デフォルトで起動します。",
                        ex.toString());
            }
        }
        return instance;
    }

    private static Path resolveFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(OmniChest.MOD_ID).resolve(FILE_NAME);
    }

    public synchronized void load() {
        Path file = resolveFile();
        if (!Files.exists(file))
            return;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            if (o.has("showOverlay")) this.showOverlay = o.get("showOverlay").getAsBoolean();
            if (o.has("showTint")) this.showTint = o.get("showTint").getAsBoolean();
            if (o.has("showGlow")) this.showGlow = o.get("showGlow").getAsBoolean();
            if (o.has("showStrongOutline")) this.showStrongOutline = o.get("showStrongOutline").getAsBoolean();
            if (o.has("pulseAnimation")) this.pulseAnimation = o.get("pulseAnimation").getAsBoolean();
            if (o.has("showTooltipLine")) this.showTooltipLine = o.get("showTooltipLine").getAsBoolean();
            if (o.has("toggleWithAltClick")) this.toggleWithAltClick = o.get("toggleWithAltClick").getAsBoolean();
            if (o.has("cycleWithShiftAltClick")) this.cycleWithShiftAltClick = o.get("cycleWithShiftAltClick").getAsBoolean();
            if (o.has("protectHotbarByDefault")) this.protectHotbarByDefault = o.get("protectHotbarByDefault").getAsBoolean();
            if (o.has("protectOffhandByDefault")) this.protectOffhandByDefault = o.get("protectOffhandByDefault").getAsBoolean();
            if (o.has("protectArmorByDefault")) this.protectArmorByDefault = o.get("protectArmorByDefault").getAsBoolean();
            if (o.has("blockManualOverride")) this.blockManualOverride = o.get("blockManualOverride").getAsBoolean();
            if (o.has("persistItemModeReassignment")) this.persistItemModeReassignment = o.get("persistItemModeReassignment").getAsBoolean();
            if (o.has("blockSmartDepositOfItemLocked")) this.blockSmartDepositOfItemLocked = o.get("blockSmartDepositOfItemLocked").getAsBoolean();
        } catch (Exception ioe) {
            OmniChest.LOGGER.warn(
                    "[omnichest] SlotLockConfig: 読み込みエラー {} (デフォルト設定で続行)",
                    ioe.toString());
        }
    }

    public synchronized void save() {
        try {
            Path file = resolveFile();
            Files.createDirectories(file.getParent());

            JsonObject o = new JsonObject();
            o.addProperty("showOverlay", showOverlay);
            o.addProperty("showTint", showTint);
            o.addProperty("showGlow", showGlow);
            o.addProperty("showStrongOutline", showStrongOutline);
            o.addProperty("pulseAnimation", pulseAnimation);
            o.addProperty("showTooltipLine", showTooltipLine);
            o.addProperty("toggleWithAltClick", toggleWithAltClick);
            o.addProperty("cycleWithShiftAltClick", cycleWithShiftAltClick);
            o.addProperty("protectHotbarByDefault", protectHotbarByDefault);
            o.addProperty("protectOffhandByDefault", protectOffhandByDefault);
            o.addProperty("protectArmorByDefault", protectArmorByDefault);
            o.addProperty("blockManualOverride", blockManualOverride);
            o.addProperty("persistItemModeReassignment", persistItemModeReassignment);
            o.addProperty("blockSmartDepositOfItemLocked", blockSmartDepositOfItemLocked);

            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(o.toString());
            }
        } catch (Exception ex) {
            OmniChest.LOGGER.warn("[omnichest] SlotLockConfig.save 失敗: {}", ex.toString());
        }
    }
}
