package com.kajiwara.omnichest.mixin;

import com.kajiwara.omnichest.client.ClientKeyBindings;
import com.kajiwara.omnichest.client.input.TextInputState;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.slotlock.InventoryProtectionLayer;
import com.kajiwara.omnichest.slotlock.LockedSlotData;
import com.kajiwara.omnichest.slotlock.MenuSlotLockSession;
import com.kajiwara.omnichest.slotlock.SlotLockConfig;
import com.kajiwara.omnichest.slotlock.SlotLockManager;
import com.kajiwara.omnichest.slotlock.SlotOverlayRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Favorite Slot Lock System を {@link AbstractContainerScreen} に組み込む Mixin。
 *
 * <p>
 * 役割:
 * <ol>
 * <li><b>Overlay 描画</b> — {@code renderSlot} の TAIL で
 *     {@link SlotOverlayRenderer} に委譲し、ロック中スロットに Tint / Glow / マーカーを乗せる。</li>
 * <li><b>Tooltip 行追加</b> — {@code renderTooltip} の HEAD でロック中スロットを検出し、
 *     [LOCKED SLOT] / [LOCKED ITEM] 行を末尾に足した tooltip を自分で描いて元処理をキャンセル。</li>
 * <li><b>クリック介入</b> — {@code mouseClicked} の HEAD で Alt+Click /
 *     再割当可能ホットキー (既定 = 中クリック) / Shift+Alt+Click を検出し、
 *     ロック切替を発火して元処理をキャンセル。</li>
 * <li><b>キー介入</b> — {@code keyPressed} の HEAD で、ホットキーがキーボードに
 *     割り当てられている場合の発火を担う。</li>
 * </ol>
 *
 * <p>
 * 設計メモ:
 * <ul>
 * <li>このクラスは {@link com.kajiwara.omnichest.mixin.GenericContainerScreenMixin}
 *     と <b>独立</b>。両 Mixin が同じ class (AbstractContainerScreen) を狙うが、注入対象メソッドが
 *     重ならないので競合しない。</li>
 * <li>クリック介入は <em>手動操作</em> に該当する。 SlotLockManager 側の API を呼ぶだけで、
 *     {@link com.kajiwara.omnichest.slotlock.SlotLockConfig#blockManualOverride}
 *     のような「手動も止める」設定はここでは関与しない (= 整理処理側で参照する)。</li>
 * </ul>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotLockScreenMixin extends Screen {

    @Shadow
    protected Slot hoveredSlot;

    /**
     * @Shadow されたメソッドは Mixin の内側ではこの abstract 宣言で参照する。
     * これにより本物の signature と vararg/return 型を一致させる。
     */
    @Shadow
    protected abstract List<Component> getTooltipFromContainerItem(ItemStack stack);

    /** menu フィールド (= generic T) を Mixin 内から触れるよう getMenu() を経由する。 */
    @Shadow
    public abstract AbstractContainerMenu getMenu();

    protected SlotLockScreenMixin(Component title) {
        super(title);
    }

    // ────────────────────────────────────────────────────────────────────
    // (1) Overlay 描画 — renderSlot の TAIL に乗せる
    // ────────────────────────────────────────────────────────────────────

    /**
     * 1 スロット描画の末尾で overlay を描く。
     *
     * <p>
     * renderSlot の呼び出し中は GuiGraphicsExtractor の matrix が既に
     * {@code (leftPos, topPos)} まで translate されているので、
     * {@link Slot#x}, {@link Slot#y} (= GUI 内ローカル座標) で fill すれば実画面座標に乗る。
     */
    @Inject(method = "extractSlot",
            at = @At("TAIL"))
    //? if >=1.21.11 {
    private void cits_slotLock$overlay(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
    //?} else {
    /*private void cits_slotLock$overlay(GuiGraphics g, Slot slot, CallbackInfo ci) {*/
    //?}
        // Renderer 側で「overlay 無効」「ロックでも保護でもない」を全部チェックするので、ここは委譲だけ。
        // 1.21.11 では renderSlot の signature が (GuiGraphicsExtractor, Slot, int mouseX, int mouseY) に
        // 拡張されている。 mouseX/Y は使わないが、引数列を完全一致させないと
        // Mixin が InvalidInjectionException を投げる。
        SlotOverlayRenderer.renderSlot(g, slot);
    }

    // ────────────────────────────────────────────────────────────────────
    // (2) Tooltip 行追加 — renderTooltip を差し替えで描画
    // ────────────────────────────────────────────────────────────────────

    /**
     * ホバー中スロットがロック対象の場合に Tooltip の末尾に [LOCKED ...] 行を追加する。
     *
     * <p>
     * バニラ {@code renderTooltip} は:
     * <ul>
     * <li>カーソルに持っているアイテム (= getCarried) が空 かつ</li>
     * <li>hoveredSlot に item があるとき、その item の tooltip を描く</li>
     * </ul>
     * という最小実装。これを丸ごと差し替え (= cancel) して、自分で「tooltip + 追加行」を描く。
     */
    @Inject(method = "extractTooltip",
            at = @At("HEAD"),
            cancellable = true)
    private void cits_slotLock$tooltip(GuiGraphicsExtractor g, int x, int y, CallbackInfo ci) {
        if (!SlotLockConfig.get().showTooltipLine)
            return;
        Slot hovered = this.hoveredSlot;
        if (hovered == null)
            return;
        if (!InventoryProtectionLayer.isProtectedSlot(hovered))
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (!carried.isEmpty())
            return; // バニラ実装も carried 非空時は何もしないのでそれに合わせる

        // プレイヤースロットならその LockedSlotData を、 そうでない (= チェスト本体側で
        // セッションロック中) なら汎用の SESSION 行を出す。
        Component lockLine;
        if (hovered.container instanceof Inventory) {
            int playerSlot = hovered.getContainerSlot();
            LockedSlotData data = SlotLockManager.get().get(playerSlot);
            lockLine = (data != null)
                    ? SlotOverlayRenderer.buildTooltipLine(data)
                    : SlotOverlayRenderer.buildDefaultProtectionTooltipLine(playerSlot);
        } else {
            lockLine = OmniChestLocale.get(Keys.SLOT_LOCK_TOOLTIP_SESSION,
                    "§b§l[SESSION LOCK] §r§7Chest body slot (until close)");
        }
        if (lockLine == null)
            return;

        Font font = this.font;
        if (hovered.hasItem()) {
            ItemStack item = hovered.getItem();
            // バニラの tooltip 行を取得し、末尾に LOCKED 行を追加して自前で「次フレーム描画」予約する。
            List<Component> lines = new ArrayList<>(this.getTooltipFromContainerItem(item));
            lines.add(lockLine);
            Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image = item.getTooltipImage();
            // 1.21.11: 即時描画の renderTooltip ではなく、 setTooltipForNextFrame を使う。
            g.setTooltipForNextFrame(font, lines, image, x, y);
        } else {
            // 空スロットでもロック中なら LOCKED 行を表示する。
            List<Component> lines = new ArrayList<>(1);
            lines.add(lockLine);
            g.setComponentTooltipForNextFrame(font, lines, x, y);
        }
        ci.cancel();
    }

    // ────────────────────────────────────────────────────────────────────
    // (3) クリック介入 — mouseClicked HEAD で「最上流」から横取り
    // ────────────────────────────────────────────────────────────────────

    /**
     * GUI 上のクリックを <b>vanilla の QUICK_CRAFT / slotClicked 解釈の前</b> に横取りして
     * ロック切替を発火する。
     *
     * <p>
     * <b>過去の失敗</b>: 当初は {@code slotClicked(Slot,int,int,ContainerInput)} HEAD に inject
     * したが、 1.21.11 では vanilla {@code mouseClicked} 内で先に
     * {@code quickCraftSlots} の初期化や {@code isSplittingStack} のフラグ操作が走っており、
     * slotClicked HEAD で cancel しても drag 関連の副作用 (= 1 アイテムずつ配布される
     * 「アイテム分割」ジェスチャ) が残った。
     *
     * <p>
     * <b>解決</b>: 1.21.11 の AbstractContainerScreen は
     * {@code mouseClicked(MouseButtonEvent, boolean)} を override している
     * (intermediary は {@code method_25402} のため tiny mappings 上は親 interface 側にしか
     * 列挙されないが、 bytecode 上は確かに存在する)。 その HEAD を狙うことで、
     * QUICK_CRAFT 開始判定よりも前にロック処理を確定できる。
     *
     * <p>
     * 取り扱う組合せ (デフォルト config):
     * <ul>
     * <li><b>Alt + 左クリック</b> (button=0, ALT 修飾)</li>
     * <li><b>スロットロック ホットキー</b> — {@link ClientKeyBindings#slotLockToggleMapping()}
     *     との {@link KeyMapping#matchesMouse} 一致。 既定は中マウスボタンだが Controls から
     *     再割当・未割当にできる (= 未割当なら発火せず cancel もしない)。</li>
     * <li><b>Shift + Alt + 左クリック</b> → サイクル切替</li>
     * </ul>
     *
     * <p>
     * 介入できなかった場合は何もせず元処理に流す。
     */
    @Inject(method = "mouseClicked",
            at = @At("HEAD"),
            cancellable = true)
    private void cits_slotLock$onMouseClicked(MouseButtonEvent event, boolean doubleClick,
                                              CallbackInfoReturnable<Boolean> cir) {
        Slot hovered = this.hoveredSlot;
        if (hovered == null)
            return;
        // Creative インベントリの「アイテムブラウザ (タブの中身)」は無限供給スロットであり、
        // ロックする意味が無い (= ハイライト誤爆を防ぐためここで一律スキップ)。
        // プレイヤーインベントリ部 (= 下段) はロックしたいので、 hovered.container が
        // Inventory のときだけ通常パスへ。
        if (cits_slotLock$isCreativeNonPlayerSlot(hovered))
            return;

        int button = event.button();
        boolean isAlt = event.hasAltDown();
        boolean isShift = event.hasShiftDown();

        SlotLockConfig cfg = SlotLockConfig.get();
        boolean cycle = (button == 0 && cfg.cycleWithShiftAltClick && isAlt && isShift);
        // Alt + 左クリック ジェスチャ。 修飾キー込みのコンボは KeyMapping で表現できないので、
        // 従来どおり SlotLockConfig のトグルで制御する。
        boolean altToggle = !cycle && (button == 0 && cfg.toggleWithAltClick && isAlt && !isShift);
        // 再割当可能ホットキー (既定 = 中マウスボタン)。 ボタン番号の直書きはせず KeyMapping に問う。
        // 未割当なら matchesMouse は必ず false = 発火しない & 後段の cancel にも到達しない。
        boolean hotkeyToggle = !cycle && !altToggle && cits_slotLock$hotkeyMatchesMouse(event);
        boolean simpleToggle = altToggle || hotkeyToggle;

        if (!cycle && !simpleToggle)
            return;

        AbstractContainerMenu menu = this.getMenu();
        boolean isPlayerSlot = (hovered.container instanceof Inventory);

        boolean fired;
        boolean wasLocked;
        int dragKey; // drag 二重防止集合に詰めるキー (player なら playerSlot, chest なら menu.index + offset)

        if (isPlayerSlot) {
            // ─── プレイヤースロット: 永続 SlotLockManager に書き込む ───
            int playerSlot = hovered.getContainerSlot();
            wasLocked = SlotLockManager.get().isLocked(playerSlot);
            fired = cycle
                    ? SlotLockManager.get().cycleByMenuSlot(menu, hovered)
                    : SlotLockManager.get().toggleByMenuSlot(menu, hovered);
            dragKey = playerSlot;
        } else {
            // ─── チェスト本体スロット: セッション限定ロック (containerId と紐づく) ───
            // cycle (Shift+Alt) は ITEM モード昇格を意味するが、チェスト本体側は ITEM モード未対応のため
            // simple toggle と同じ動作 (= 単純切替) にフォールバックする。
            int containerId = menu.containerId;
            wasLocked = MenuSlotLockSession.get().isLocked(containerId, hovered.index);
            MenuSlotLockSession.get().toggle(containerId, hovered.index);
            fired = true;
            // drag 集合のキーは「menu index に 1000 オフセット」を加えてプレイヤースロットと衝突させない
            // (= drag 中に player ↔ chest を横断しても安全な ID 空間にする)。
            dragKey = 1000 + hovered.index;
        }

        if (fired) {
            // ─── Alt+ドラッグ連続選択モードを開始 ───
            // SLOT モードの simple toggle (= Alt+左クリック) のときだけ drag を起動。
            // ホットキー経路 (中クリック等) は drag 対象外 = 従来と同じ (mouseDragged が Alt 必須のため)。
            if (altToggle) {
                this.cits_slotLock$dragMode = wasLocked ? DRAG_UNLOCK : DRAG_LOCK;
                this.cits_slotLock$dragTouched.clear();
                this.cits_slotLock$dragTouched.add(dragKey);
            }

            // バニラの mouseClicked 全体 (drag 開始判定含む) を完全に抑止する。
            cir.setReturnValue(true);
        }
    }

    /**
     * スロットロック ホットキーが「このマウスイベント」に割り当てられているかを問う。
     *
     * <p>
     * 判定は {@link KeyMapping#matchesMouse} 一本 (= {@code button == 2} のような直書き比較はしない)。
     * これにより Controls での再割当がそのまま効き、 <b>未割当なら常に false</b> になる
     * (= クリエイティブの中クリック複製 / 通常の中クリックがバニラ経路へ素通りする)。
     */
    @Unique
    private boolean cits_slotLock$hotkeyMatchesMouse(MouseButtonEvent event) {
        KeyMapping mapping = ClientKeyBindings.slotLockToggleMapping();
        return mapping != null && mapping.matchesMouse(event);
    }

    // ────────────────────────────────────────────────────────────────────
    // (3b) キーボード割当時の発火 — keyPressed HEAD
    // ────────────────────────────────────────────────────────────────────

    /**
     * スロットロック ホットキーが <b>キーボード</b> に割り当てられている場合の発火経路。
     *
     * <p>
     * <b>なぜ GUI 側で判定するのか</b>: バニラは Screen が開いている間 {@link KeyMapping} を
     * tick しない ({@code KeyboardHandler#keyPress} が {@code KeyMapping.set/click} を
     * {@code minecraft.screen == null} のときだけ呼ぶ) ため、 {@code consumeClick()} は
     * インベントリ GUI の中では永久に false。 よってここで {@link KeyMapping#matches} を直接評価する。
     *
     * <p>
     * 文字入力中 ({@link TextInputState#isTextInputActive()}) は発火しない
     * (= チェスト GUI の検索欄に打っている最中にロックが暴発しない)。
     * マッチしない場合は何も cancel せず、 バニラの keyInventory / hotbar / drop 処理に素通りさせる。
     */
    @Inject(method = "keyPressed",
            at = @At("HEAD"),
            cancellable = true)
    private void cits_slotLock$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (TextInputState.isTextInputActive())
            return;
        KeyMapping mapping = ClientKeyBindings.slotLockToggleMapping();
        if (mapping == null || !mapping.matches(event))
            return;
        Slot hovered = this.hoveredSlot;
        if (hovered == null)
            return;
        if (cits_slotLock$isCreativeNonPlayerSlot(hovered))
            return;

        AbstractContainerMenu menu = this.getMenu();
        if (hovered.container instanceof Inventory) {
            // プレイヤースロット: 永続 SlotLockManager (= マウス経路の simple toggle と同一 API)。
            SlotLockManager.get().toggleByMenuSlot(menu, hovered);
        } else {
            // チェスト本体スロット: セッション限定ロック。
            MenuSlotLockSession.get().toggle(menu.containerId, hovered.index);
        }
        cir.setReturnValue(true);
    }

    // ────────────────────────────────────────────────────────────────────
    // (4) Alt+ドラッグ連続選択 — mouseDragged で hovered スロットを順次トグル
    // ────────────────────────────────────────────────────────────────────

    /** ドラッグモードの状態。 */
    @Unique
    private static final int DRAG_NONE = 0;
    @Unique
    private static final int DRAG_LOCK = 1;
    @Unique
    private static final int DRAG_UNLOCK = 2;

    /** 現在の drag モード。 click で起動、 release でリセット。 */
    @Unique
    private int cits_slotLock$dragMode = DRAG_NONE;

    /** drag 中に既にトグル済みの Player スロット番号集合 (= 二重 toggle 防止)。 */
    @Unique
    private final Set<Integer> cits_slotLock$dragTouched = new HashSet<>();

    /**
     * Alt+左ボタンを押下したままカーソルが他スロットへ移動した瞬間に、
     * そのスロットへも同じ操作 (lock or unlock) を適用する。
     *
     * <p>
     * 「方向」は最初のクリックの結果から決まる:
     * <ul>
     * <li>初回が「未ロック → ロックされた」 → 以降のスロットは <b>必ずロックする</b>
     *     (= 既にロック中ならスキップ)</li>
     * <li>初回が「ロック中 → 解除された」 → 以降のスロットは <b>必ず解除する</b>
     *     (= 既に未ロックならスキップ)</li>
     * </ul>
     */
    @Inject(method = "mouseDragged",
            at = @At("HEAD"),
            cancellable = true)
    private void cits_slotLock$onMouseDragged(MouseButtonEvent event, double dx, double dy,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (this.cits_slotLock$dragMode == DRAG_NONE)
            return;
        // Alt キーを離したら drag 終了。
        if (!event.hasAltDown()) {
            this.cits_slotLock$dragMode = DRAG_NONE;
            this.cits_slotLock$dragTouched.clear();
            return;
        }
        Slot hovered = this.hoveredSlot;
        if (hovered == null) {
            // hovered が無い (= 空白上) ときは drag を継続するが何もしない。
            // バニラ drag (= quickCraft) には流したくないので cancel する。
            cir.setReturnValue(true);
            return;
        }
        // Creative ブラウザのスロットは drag でも触らない (= 下段プレイヤー側だけ通す)。
        if (cits_slotLock$isCreativeNonPlayerSlot(hovered)) {
            cir.setReturnValue(true);
            return;
        }

        AbstractContainerMenu menu = this.getMenu();
        boolean isPlayerSlot = (hovered.container instanceof Inventory);
        int dragKey = isPlayerSlot ? hovered.getContainerSlot() : (1000 + hovered.index);

        if (this.cits_slotLock$dragTouched.contains(dragKey)) {
            cir.setReturnValue(true);
            return;
        }
        this.cits_slotLock$dragTouched.add(dragKey);

        if (isPlayerSlot) {
            int playerSlot = hovered.getContainerSlot();
            boolean currentlyLocked = SlotLockManager.get().isLocked(playerSlot);
            if (this.cits_slotLock$dragMode == DRAG_LOCK && !currentlyLocked) {
                SlotLockManager.get().put(LockedSlotData.slot(playerSlot));
            } else if (this.cits_slotLock$dragMode == DRAG_UNLOCK && currentlyLocked) {
                SlotLockManager.get().remove(playerSlot);
            }
        } else {
            // チェスト本体スロット: セッションロックに直接書き込む。
            int containerId = menu.containerId;
            if (this.cits_slotLock$dragMode == DRAG_LOCK) {
                MenuSlotLockSession.get().put(containerId, hovered.index);
            } else if (this.cits_slotLock$dragMode == DRAG_UNLOCK) {
                MenuSlotLockSession.get().remove(containerId, hovered.index);
            }
        }
        cir.setReturnValue(true);
    }

    /**
     * マウスボタンが離されたら drag 状態をクリアする。
     * vanilla の処理は通常通り走らせる (= cancel しない)。
     */
    @Inject(method = "mouseReleased",
            at = @At("HEAD"))
    private void cits_slotLock$onMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (this.cits_slotLock$dragMode != DRAG_NONE) {
            this.cits_slotLock$dragMode = DRAG_NONE;
            this.cits_slotLock$dragTouched.clear();
        }
    }

    /**
     * 「Creative インベントリの非プレイヤースロット」判定。
     * 該当する場合は click / drag / overlay 描画を全て無視したい。
     *
     * <p>
     * Creative インベントリのアイテムブラウザは無限供給スロットで、 menu の Slot Wrapper を介して
     * ItemPickerMenu の特殊コンテナを参照する。プレイヤーインベントリ部分 (= 下段)
     * は通常通り Inventory なので除外しない。
     */
    @Unique
    private boolean cits_slotLock$isCreativeNonPlayerSlot(Slot slot) {
        if (!(((Object) this) instanceof CreativeModeInventoryScreen))
            return false;
        return !(slot.container instanceof Inventory);
    }

}
