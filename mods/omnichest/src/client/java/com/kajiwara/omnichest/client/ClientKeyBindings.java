package com.kajiwara.omnichest.client;

import com.kajiwara.omnichest.classify.AutoDepositManager;
import com.kajiwara.omnichest.client.gui.SearchScreen;
import com.kajiwara.omnichest.client.input.TextInputState;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.distribution.ui.DistributionScreen;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.slotlock.MenuSlotLockSession;
import com.kajiwara.omnichest.slotlock.SlotLockManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
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
     * デフォルトは <b>中マウスボタン</b> (= 従来の固定挙動をそのまま既定値にしたもの)。
     *
     * <p>
     * <b>これがスロットロック ホットキーの唯一の真実の源</b>。 マウス / キーボードのどちらにも
     * 割り当てられ、 「未割当」 にすれば機能ごと止まる。 判定は
     * {@link com.kajiwara.omnichest.mixin.SlotLockScreenMixin} が
     * {@link KeyMapping#matchesMouse} / {@link KeyMapping#matches} で行う
     * (= ボタン番号の直書き比較はしない)。
     */
    public static final String TOGGLE_SLOT_LOCK_KEY = "key.omnichest.toggle_slot_lock";

    /**
     * Favorite Slot Lock: 全ロックを一括解除するキー。
     * 誤爆防止のため <b>2 回連続押下</b> (1.5 秒以内) で確定する。
     * デフォルトは未バインド。
     */
    public static final String CLEAR_ALL_LOCKS_KEY = "key.omnichest.clear_all_slot_locks";

    /**
     * Selected Item HUD: 「選択アイテム情報 HUD」 の表示を ON/OFF 切替するキー。
     * デフォルトは <b>未バインド</b> (= 衝突を避けるため、 ユーザー任意設定)。
     * {@code /omnichest hud toggle} と同一の設定 ({@link com.kajiwara.omnichest.config.data.RenderConfig#showSelectedItemHud})
     * を切り替える。
     */
    public static final String TOGGLE_SELECTED_ITEM_HUD_KEY = "key.omnichest.toggle_selected_item_hud";

    /**
     * Dimension Menu: 「ハイライト中アイテムが どのディメンションにあるか」 の一覧メニュー
     * ({@link com.kajiwara.omnichest.client.gui.DimensionMenuScreen}) を開閉する<b>再割当可能</b>キー。
     * デフォルトは <b>未バインド</b>。 既定操作は Alt+C (下記グローバル ポール) で、 衝突時はこのキーへ
     * 好みの単一キーを割り当てられる (= 設定 {@code render.dimensionMenuAltC} で Alt+C を無効化可)。
     */
    public static final String TOGGLE_DIMENSION_MENU_KEY = "key.omnichest.toggle_dimension_menu";

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
    private static KeyMapping toggleSelectedItemHud;
    private static KeyMapping toggleDimensionMenu;

    /** Alt+C グローバル ポールのエッジ検出フラグ (Alt+D と同方式)。 */
    private static boolean lastAltCDown = false;

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

        // Slot Lock 切替キー。 既定は「中マウスボタン」 (= 従来の固定挙動を既定値として保存)。
        // KeyMapping なので Controls から再割当も未割当もできる (= 未割当ならロックは発動しない)。
        toggleSlotLock = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                TOGGLE_SLOT_LOCK_KEY,
                InputConstants.Type.MOUSE,
                GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
                CATEGORY));

        // Slot Lock 全解除キー (= 未バインド)。
        // 2 回連続押下 (1.5 秒以内) で全ロックを消去する double-tap 仕様。
        clearAllSlotLocks = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                CLEAR_ALL_LOCKS_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));

        // 選択アイテム HUD の表示トグル (= 未バインドで登録: ユーザーが好みのキーを割当可能)。
        toggleSelectedItemHud = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                TOGGLE_SELECTED_ITEM_HUD_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));

        // ディメンション別メニューの再割当キー (= 未バインドで登録: 既定は Alt+C ポール)。
        toggleDimensionMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                TOGGLE_DIMENSION_MENU_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(ClientKeyBindings::onTick);
    }

    /**
     * スロットロック切替キーの {@link KeyMapping} を返す (= GUI 内から入力判定するため)。
     *
     * <p>
     * <b>なぜ getter が要るか</b>: バニラは <b>Screen が開いている間 KeyMapping を tick しない</b>
     * ({@code KeyboardHandler#keyPress} が {@code KeyMapping.set/click} を
     * {@code minecraft.screen == null} のときだけ呼ぶ) ため、 インベントリ GUI の中では
     * {@link KeyMapping#consumeClick()} は永久に false を返す。 よって GUI 内では
     * {@link com.kajiwara.omnichest.mixin.SlotLockScreenMixin} が受け取った
     * イベントに対して {@code matchesMouse} / {@code matches} を直接評価する。
     *
     * <p>
     * 初期化前 (= {@link #register()} 前) は null を返しうるので、 呼び出し側で null を許容すること。
     */
    public static KeyMapping slotLockToggleMapping() {
        return toggleSlotLock;
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

        if (toggleSelectedItemHud != null) {
            while (toggleSelectedItemHud.consumeClick()) {
                // 選択アイテム HUD の表示を反転 (= /omnichest hud toggle と同一設定)。
                // Screen の有無に関わらず動く単純な表示トグル (= ロジックには触れない)。
                boolean next;
                try {
                    next = !ConfigManager.get().render.showSelectedItemHud;
                    ConfigManager.get().render.showSelectedItemHud = next;
                    ConfigManager.save();
                } catch (Throwable t) {
                    next = true;
                }
                if (mc.player != null) mc.player.sendSystemMessage(next
                        ? OmniChestLocale.get("omnichest.command.hud.on", "Selected item HUD: ON")
                        : OmniChestLocale.get("omnichest.command.hud.off", "Selected item HUD: OFF"));
            }
        }

        if (toggleDimensionMenu != null) {
            while (toggleDimensionMenu.consumeClick()) {
                // ディメンション別メニューを開閉 (= 再割当キー経路。 開いていれば閉じる)。
                com.kajiwara.omnichest.client.gui.DimensionMenuScreen.toggle();
            }
        }

        // 注: スロットロック切替キー (toggleSlotLock) をここで consumeClick しても意味が無い。
        // バニラは Screen が開いている間 KeyMapping を tick しない (= KeyboardHandler#keyPress が
        // KeyMapping.set/click を minecraft.screen == null のときだけ呼ぶ) ので、
        // 「インベントリ GUI の中でホバー中スロットを切り替える」 このキーは tick 経路では絶対に
        // 発火しない。 判定は SlotLockScreenMixin が mouseClicked / keyPressed で直接行う
        // (= 真実の源は KeyMapping 一本のまま)。

        // ─── グローバル Alt+C = ディメンション別メニューをトグル (= 既定操作) ───
        //
        // KeyMapping は修飾コンボ (Alt+C) を扱えないため、 Alt+D と同じ GLFW ポール + エッジ検出で
        // 実装する。 設定 {@code render.dimensionMenuAltC} が OFF なら無効化 (= 再割当キーのみ使う)。
        // {@link DimensionMenuScreen#toggle()} が screen 状態を見て「開く / 自画面を閉じる / 他画面中は無視」
        // を判断するため、 ここでは screen ガード不要 (= 自画面を開いたまま Alt+C で閉じられる)。
        // C はバニラの移動キー (WASD) ではないため、 旧 Alt+A で起きていたストレイフ一瞬混入が起きない。
        {
            boolean altCEnabled;
            try {
                altCEnabled = ConfigManager.get().render.dimensionMenuAltC;
            } catch (Throwable t) {
                altCEnabled = true;
            }
            var winC = mc.getWindow();
            boolean altDownC = InputConstants.isKeyDown(winC, InputConstants.KEY_LALT)
                    || InputConstants.isKeyDown(winC, InputConstants.KEY_RALT);
            boolean cDown = InputConstants.isKeyDown(winC, GLFW.GLFW_KEY_C);
            boolean nowDown = altDownC && cDown;
            // 文字入力中は抑止する。 GLFW 生ポーリングは Screen の有無に関係なく毎 tick 走るため、
            // テキスト欄に打っている最中でも条件が揃えば発火してしまう (= タイプ中に別メニューが開く)。
            // エッジ検出フラグ (lastAltCDown) は抑止中も毎 tick 更新する
            // (= 入力欄から抜けた瞬間に押しっぱなしが暴発しない)。
            if (altCEnabled && nowDown && !lastAltCDown && !TextInputState.isTextInputActive()) {
                com.kajiwara.omnichest.client.gui.DimensionMenuScreen.toggle();
            }
            lastAltCDown = nowDown;
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
            // screen == null ガードで既にテキスト入力中は除外されるが、 「生ポーリングの
            // ホットキーは TextInputState を必ず参照する」 という規則を明示的に守る。
            //? if >=26.2 {
            /*if (nowDown && !lastAltDDown && mc.gui.screen() == null && !TextInputState.isTextInputActive()) {*/
            //?} else {
            if (nowDown && !lastAltDDown && mc.screen == null && !TextInputState.isTextInputActive()) {
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
