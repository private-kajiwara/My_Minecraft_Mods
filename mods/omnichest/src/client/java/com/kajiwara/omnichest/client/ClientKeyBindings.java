package com.kajiwara.omnichest.client;

import com.kajiwara.omnichest.classify.AutoDepositManager;
import com.kajiwara.omnichest.client.gui.SearchScreen;
import com.kajiwara.omnichest.distribution.ui.DistributionScreen;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.mixin.AbstractContainerScreenAccessor;
import com.kajiwara.omnichest.slotlock.MenuSlotLockSession;
import com.kajiwara.omnichest.slotlock.SlotIndexMapper;
import com.kajiwara.omnichest.slotlock.SlotLockManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

/**
 * Chest Network Search 用キーバインド。
 *
 * <p>
 * デフォルトキー: <b>G</b> (=「Get / Grep / Go」)
 * カテゴリ: <b>OmniChest</b>
 *
 * <p>
 * 端末側で他バインドと衝突した場合は、 「コントロール設定」から再割当できる。
 *
 * <p>
 * 動作: 押すと、ゲーム画面 (= 何も Screen が開いていない時) からのみ
 * {@link SearchScreen} を開く。何かの Screen が既に開いている場合は無視する
 * (誤発火防止)。
 */
public final class ClientKeyBindings {

    public static final String OPEN_SEARCH_KEY = "key.omnichest.open_search";
    /** Storage Auto Distribution: 倉庫振り分けメニューを開くキー。 デフォルト <b>J</b>。 */
    public static final String OPEN_DISTRIBUTION_KEY = "key.omnichest.open_distribution";
    /** Smart Storage Classification: 自動投入プランをチャットに表示するキー。 */
    public static final String SMART_DEPOSIT_KEY = "key.omnichest.smart_deposit";
    /**
     * Favorite Slot Lock: GUI 内のホバー中スロットをロック切替するキー。
     * Alt+Click / Middle-Click 操作が使えない (= キーボード派) ユーザー向けの代替入口。
     * デフォルトは <b>未バインド</b> (= 衝突を避けるため、ユーザー任意設定)。
     */
    public static final String TOGGLE_SLOT_LOCK_KEY = "key.omnichest.toggle_slot_lock";

    /**
     * Favorite Slot Lock: 全ロックを一括解除するキー。
     * 誤爆防止のため <b>2 回連続押下</b> (1.5 秒以内) で確定する。
     * デフォルトは未バインド。
     */
    public static final String CLEAR_ALL_LOCKS_KEY = "key.omnichest.clear_all_slot_locks";

    /**
     * 独自カテゴリを 1.21.11+ の新 API ({@link KeyMapping.Category#register}) で登録する。
     * String 版は package-private に変わったため、 Identifier 版を経由する。
     * 同名カテゴリが既に存在する場合は同じインスタンスが返る。
     */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("omnichest", "search"));

    private static KeyMapping openSearch;
    private static KeyMapping openDistribution;
    private static KeyMapping smartDeposit;
    private static KeyMapping toggleSlotLock;
    private static KeyMapping clearAllSlotLocks;

    /** 一括解除キー押下時刻 (ms)。 1.5 秒以内の連続押下で確定。 */
    private static long lastClearAllPressMs = 0L;

    /**
     * 「直近の tick で Alt+D が両方とも押されていたか」 のエッジ検出用フラグ。
     *
     * <p>
     * MC の {@link KeyMapping} は単一キーしか扱えず、 修飾キー (Alt) 込みのコンボには対応していない。
     * そこで、 Alt+D は <b>毎 tick で GLFW から直接ポーリング</b> し、 「前 tick = OFF, 今 tick = ON」 の
     * エッジを検出して 1 押下につき 1 回だけ発火させる。 押しっぱなしの 0.5 秒で連続発火する旧仕様
     * (= keyPressed の OS リピート任せ) は採用しない (= ユーザの「1 押下 = 1 アクション」 期待に合わせる)。
     */
    private static boolean lastAltDDown = false;

    private ClientKeyBindings() {
    }

    /**
     * KeyMapping の登録と tick リスナの装着を一括で行う。
     * ClientModInitializer から 1 回だけ呼ぶこと。
     */
    public static void register() {
        openSearch = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                OPEN_SEARCH_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                CATEGORY));

        // 倉庫振り分けメニュー。デフォルト「J」 (= 検索 G の隣)。
        openDistribution = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                OPEN_DISTRIBUTION_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CATEGORY));

        // 自動投入プランの一括表示。デフォルト「H」 (= "Home for items")。
        smartDeposit = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                SMART_DEPOSIT_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                CATEGORY));

        // Slot Lock 切替キー (= 未バインドで登録: ユーザーが好みのキーを割当可能)。
        toggleSlotLock = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                TOGGLE_SLOT_LOCK_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));

        // Slot Lock 全解除キー (= 未バインド)。
        // 2 回連続押下 (1.5 秒以内) で全ロックを消去する double-tap 仕様。
        clearAllSlotLocks = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                CLEAR_ALL_LOCKS_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(ClientKeyBindings::onTick);
    }

    private static void onTick(Minecraft mc) {
        if (openSearch == null)
            return;
        // 連打防止のため consumeClick で 1 押下につき 1 回だけ取り出す。
        while (openSearch.consumeClick()) {
            // 別の Screen が開いている時はオープンを抑止する (誤発火防止)。
            //? if >=26.2 {
            /*if (mc.gui.screen() == null) {*/
            //?} else {
            if (mc.screen == null) {
            //?}
                SearchScreen.open();
            }
        }

        if (openDistribution != null) {
            while (openDistribution.consumeClick()) {
                // 倉庫振り分けメニューもゲーム画面 (Screen 無し) からのみ開く。
                //? if >=26.2 {
                /*if (mc.gui.screen() == null) {*/
                //?} else {
                if (mc.screen == null) {
                //?}
                    DistributionScreen.open();
                }
            }
        }

        if (smartDeposit != null) {
            while (smartDeposit.consumeClick()) {
                // Smart Deposit はゲーム画面 (Screen 無し) のときだけ発火。
                // GUI を開いた状態だと既存の Deposit ボタンが提供する機能と被るため抑止。
                //? if >=26.2 {
                /*if (mc.gui.screen() == null && mc.player != null) {*/
                //?} else {
                if (mc.screen == null && mc.player != null) {
                //?}
                    AutoDepositManager.announceSummary(mc.player);
                }
            }
        }

        if (toggleSlotLock != null) {
            while (toggleSlotLock.consumeClick()) {
                // インベントリ系の Screen で、ホバー中のスロットを toggle する。
                // toggleSlotLock は ContainerScreen 外では何もしない (= 暴発防止)。
                //? if >=26.2 {
                /*if (mc.gui.screen() instanceof AbstractContainerScreen<?> acs) {*/
                //?} else {
                if (mc.screen instanceof AbstractContainerScreen<?> acs) {
                //?}
                    Slot hovered = ((AbstractContainerScreenAccessor) acs).cits$getHoveredSlot();
                    if (hovered != null && hovered.container instanceof Inventory) {
                        int playerSlot = hovered.getContainerSlot();
                        if (playerSlot >= SlotIndexMapper.PLAYER_INV_SLOT_MIN
                                && playerSlot <= SlotIndexMapper.PLAYER_INV_SLOT_MAX) {
                            SlotLockManager.get().toggleSlotLock(playerSlot);
                            if (mc.gui != null) {
                                Component msg = OmniChestLocale.get(
                                        Keys.SLOT_LOCK_CHAT_TOGGLED,
                                        "[Slot Lock] toggled slot %1$d", playerSlot);
                                if (mc.player != null) mc.player.sendSystemMessage(msg);
                            }
                        }
                    }
                }
            }
        }

        // ─── グローバル Alt+D = ワールド上の全ピンを一括解除 ───
        //
        // SearchScreen の中では keyPressed が ALT+D を 「カーソル下の 1 行を解除」 として処理し、
        // ここの onTick は <b>mc.screen == null</b> (= 何の GUI も開いていない) のときのみ動作する。
        // つまり「ゲーム画面でプレイ中、 ピンが世界に残っているのを 1 キーで掃除する」 のが目的。
        //
        // <b>エッジ検出</b>: GLFW のキーは「押されているか / 離されているか」 しか持たないので、
        // 「前 tick = OFF, 今 tick = ON」 の遷移を捉えて 1 押下 = 1 アクションに揃える。
        // {@link #lastAltDDown} 自体は <em>screen の有無に関わらず</em> 毎 tick 更新する
        // (= 「SearchScreen を開いたまま Alt+D を押し続け、 閉じた瞬間に発火」 等の意図しない暴発を防ぐ)。
        {
            // InputConstants.isKeyDown は Window オブジェクトを直接受け取る (= 既存パターンと一致。
            // SortButtonWidget.java 等で同じ呼び方が成立済み)。
            var win = mc.getWindow();
            boolean altDown = InputConstants.isKeyDown(win, InputConstants.KEY_LALT)
                    || InputConstants.isKeyDown(win, InputConstants.KEY_RALT);
            boolean dDown = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_D);
            boolean nowDown = altDown && dDown;
            //? if >=26.2 {
            /*if (nowDown && !lastAltDDown && mc.gui.screen() == null) {*/
            //?} else {
            if (nowDown && !lastAltDDown && mc.screen == null) {
            //?}
                // 全ピン解除。 ChestHighlighter.clear() は active map を空にするだけで、
                // 配下の ChestNetworkManager スナップショットや SearchIndex には触れない (= 検索状態は保持)。
                com.kajiwara.omnichest.client.render.ChestHighlighter.get().clear();
            }
            lastAltDDown = nowDown;
        }

        if (clearAllSlotLocks != null) {
            while (clearAllSlotLocks.consumeClick()) {
                // 全解除: double-tap (1.5 秒以内) で確定。
                long now = System.currentTimeMillis();
                int totalPlayer = SlotLockManager.get().size();
                int totalSession = MenuSlotLockSession.get().size();
                int total = totalPlayer + totalSession;
                if (total == 0) {
                    if (mc.gui != null) {
                        if (mc.player != null) mc.player.sendSystemMessage(OmniChestLocale.get(
                                Keys.SLOT_LOCK_CHAT_NOTHING_TO_CLEAR,
                                "§7[Slot Lock] §oNo locks to clear."));
                    }
                    lastClearAllPressMs = 0L;
                    continue;
                }
                if (now - lastClearAllPressMs <= 1500L) {
                    // 2 回目の押下 → 永続 + セッション 両方を全削除。
                    SlotLockManager.get().clearAll();
                    MenuSlotLockSession.get().clearAll();
                    if (mc.gui != null) {
                        if (mc.player != null) mc.player.sendSystemMessage(OmniChestLocale.get(
                                Keys.SLOT_LOCK_CHAT_CLEARED,
                                "§a[Slot Lock] §rCleared %1$d persistent + %2$d session locks.",
                                totalPlayer, totalSession));
                    }
                    lastClearAllPressMs = 0L;
                } else {
                    // 1 回目の押下 → 警告のみ。
                    lastClearAllPressMs = now;
                    if (mc.gui != null) {
                        if (mc.player != null) mc.player.sendSystemMessage(OmniChestLocale.get(
                                Keys.SLOT_LOCK_CHAT_CONFIRM_CLEAR,
                                "§e[Slot Lock] §rPress again within 1.5s to clear %1$d locks.",
                                total));
                    }
                }
            }
        }
    }
}
