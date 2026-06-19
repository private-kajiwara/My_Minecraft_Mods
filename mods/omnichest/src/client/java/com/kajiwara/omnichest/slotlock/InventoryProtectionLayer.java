package com.kajiwara.omnichest.slotlock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.BitSet;

/**
 * 「<b>Inventory Protection Layer</b>」 — Slot Lock の利害関係をひとまとめにした
 * 他機能から再利用可能な薄い API。
 *
 * <p>
 * 既存・将来の整理系コード ({@link com.kajiwara.omnichest.util.ContainerSorter},
 * {@link com.kajiwara.omnichest.util.StackCompactor},
 * {@link com.kajiwara.omnichest.util.DepositMatchingHelper},
 * {@link com.kajiwara.omnichest.template.apply.SlotPlanner},
 * {@link com.kajiwara.omnichest.classify.SmartRoutingManager}) は、
 * 自分の処理内で <em>直接 SlotLockManager を読まない</em>。
 * 必ずこのレイヤを介す。
 *
 * <p>
 * これにより:
 * <ul>
 * <li>「ロック対象判定」の <b>仕様</b> がここに集約される (= 巨大 if 文回避)。</li>
 * <li>SLOT モード / ITEM モード / Hotbar デフォルト保護 / Armor デフォルト保護 など、
 *     設定駆動の追加ポリシーを 1 箇所で表現できる。</li>
 * <li>テンプレ適用や検索整理など、 別系統の機能が増えても同じ API を呼ぶだけで
 *     ロック仕様を取り込める。</li>
 * </ul>
 *
 * <p>
 * <b>禁止</b> されるのは「<em>自動移動</em>」 のみ。プレイヤーの手動操作
 * (通常クリック / ドラッグ / shift-click) は別のフックで判定する (= mouseClicked 側)。
 */
public final class InventoryProtectionLayer {

    private InventoryProtectionLayer() {
    }

    // ────────────────────────────────────────────────────────────────────
    // 1) Player 座標 (= 永続化座標) ベースの判定
    // ────────────────────────────────────────────────────────────────────

    /**
     * Player 座標 (0..40) のスロットが「自動移動禁止」か。
     *
     * <p>
     * 判定:
     * <ol>
     * <li>明示的にロック登録されている → 禁止。</li>
     * <li>{@link SlotLockConfig#protectHotbarByDefault} が true で Hotbar (0..8) → 禁止。</li>
     * <li>{@link SlotLockConfig#protectOffhandByDefault} が true で Offhand (40) → 禁止。</li>
     * <li>{@link SlotLockConfig#protectArmorByDefault} が true で Armor (36..39) → 禁止。</li>
     * </ol>
     */
    public static boolean isProtectedByPlayerSlot(int playerSlotIndex) {
        if (playerSlotIndex < 0)
            return false;

        SlotLockManager mgr = SlotLockManager.get();
        if (mgr.isLocked(playerSlotIndex))
            return true;

        SlotLockConfig cfg = SlotLockConfig.get();
        if (cfg.protectHotbarByDefault && SlotIndexMapper.isHotbar(playerSlotIndex))
            return true;
        if (cfg.protectOffhandByDefault && SlotIndexMapper.isOffhand(playerSlotIndex))
            return true;
        if (cfg.protectArmorByDefault && SlotIndexMapper.isArmor(playerSlotIndex))
            return true;

        return false;
    }

    // ────────────────────────────────────────────────────────────────────
    // 2) Container Menu 連番ベースの判定 (= 整理処理から最頻使用)
    // ────────────────────────────────────────────────────────────────────

    /**
     * 現在開いている menu 連番のスロットが「自動移動禁止」か。
     *
     * <p>
     * チェスト本体側のスロット (= Player Inventory に紐付かない) は常に false。
     * ロック仕様はあくまで <em>プレイヤー側</em> を守ることに限定する。
     * チェスト本体スロットを守りたい場合は将来 ContainerProtectionLayer を別途追加する想定。
     */
    public static boolean isProtectedByMenuSlot(AbstractContainerMenu menu, int menuSlotIndex) {
        if (menu == null || menuSlotIndex < 0 || menuSlotIndex >= menu.slots.size())
            return false;
        Slot slot = menu.slots.get(menuSlotIndex);
        return isProtectedSlot(slot);
    }

    /**
     * {@link Slot} オブジェクトから直接判定する版。
     *
     * <p>
     * 2 系統の保護を統合する:
     * <ul>
     * <li><b>プレイヤースロット</b> (slot.container instanceof Inventory) →
     *     {@link SlotLockManager} 経由の永続ロック + デフォルト保護を見る</li>
     * <li><b>チェスト本体スロット</b> (それ以外) →
     *     {@link MenuSlotLockSession} 経由のセッションロックを見る
     *     (= 現在開いている menu の containerId に紐づく一時ロック)</li>
     * </ul>
     */
    public static boolean isProtectedSlot(Slot slot) {
        if (slot == null)
            return false;
        if (slot.container instanceof Inventory) {
            return isProtectedByPlayerSlot(slot.getContainerSlot());
        }
        // チェスト本体スロット: セッションロックを見る。
        // 現在開いている menu の containerId を取れない (= player が null) ときは保護無しと判定。
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.containerMenu == null)
            return false;
        // Creative インベントリのアイテムブラウザスロットはロック対象外
        // (= 無限供給スロットなので保護する意味が無く、誤って overlay が乗ると邪魔)。
        //? if >=26.2 {
        /*if (mc.gui.screen() instanceof CreativeModeInventoryScreen)*/
        //?} else {
        if (mc.screen instanceof CreativeModeInventoryScreen)
        //?}
            return false;
        return MenuSlotLockSession.get().isLocked(player.containerMenu.containerId, slot.index);
    }

    // ────────────────────────────────────────────────────────────────────
    // 3) 1 メニュー分まとめてビットマップ化 (= sort/compact 内ループの O(1) 判定)
    // ────────────────────────────────────────────────────────────────────

    /**
     * menu の各スロットについて「保護フラグ」を立てた {@link BitSet} を返す。
     *
     * <p>
     * 戻り値: <code>bit[i] == true</code> ⇔ menu.slots[i] は自動移動禁止。
     *
     * <p>
     * 整理処理は 1 回 GUI を開いてから複数回ループを回すので、
     * 毎ループで Manager を引きに行くと O(N · K) になる。
     * 事前に BitSet 化して O(1) 判定に落とすのが推奨パターン。
     */
    public static BitSet buildProtectionMask(AbstractContainerMenu menu) {
        BitSet mask = new BitSet(menu == null ? 0 : menu.slots.size());
        if (menu == null)
            return mask;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (isProtectedSlot(menu.slots.get(i)))
                mask.set(i);
        }
        return mask;
    }

    /**
     * 範囲 [from, to) に対する保護マスク。 {@link #buildProtectionMask(AbstractContainerMenu)}
     * と異なり「範囲基準」のビット位置 (= from を 0 とする) で詰めて返す。
     * StackCompactor のような相対インデックスを使うコードと相性が良い。
     */
    public static BitSet buildProtectionMaskRange(AbstractContainerMenu menu, int from, int to) {
        int size = Math.max(0, to - from);
        BitSet mask = new BitSet(size);
        if (menu == null || size == 0)
            return mask;
        int end = Math.min(to, menu.slots.size());
        for (int i = from; i < end; i++) {
            if (isProtectedSlot(menu.slots.get(i)))
                mask.set(i - from);
        }
        return mask;
    }

    // ────────────────────────────────────────────────────────────────────
    // 4) ITEM ロック (= 「Diamond Pickaxe をどこへ動かしても守る」) 判定
    // ────────────────────────────────────────────────────────────────────

    /**
     * 「<em>このアイテム種</em> が ITEM モードでロックされているか」を判定する。
     *
     * <p>
     * 整理処理が「source スロットそのものは未ロックだが、運ぼうとしている stack が
     * ITEM ロック対象だ」というケースを検出するために使う。
     * (これを無視すると、整理側が一度ピックアップ → 別スロットへ落として、結果として
     * ITEM ロックが追跡再計算されるまでの 1 フレーム間でも整理予測が壊れる。)
     */
    public static boolean isItemLockedStack(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;
        SlotLockManager mgr = SlotLockManager.get();
        if (mgr.size() == 0)
            return false;
        for (LockedSlotData d : mgr.snapshot()) {
            if (d.matchesItem(stack))
                return true;
        }
        return false;
    }

    /**
     * 「source / dest どちらかが保護されている」を一発で判定する。
     * 整理コードの「swap 候補ペア」検証で使うショートカット。
     */
    public static boolean isMoveBlocked(AbstractContainerMenu menu, int srcMenuSlot, int dstMenuSlot) {
        return isProtectedByMenuSlot(menu, srcMenuSlot)
                || isProtectedByMenuSlot(menu, dstMenuSlot);
    }

    /**
     * 「ItemStack 自体が ITEM ロック対象」 or 「行き先 dst がスロット保護中」をまとめて判定。
     * quickMove (shift-click 整理) のように「source 側はプレイヤーが触ったので例外的に許可されている」
     * シナリオで、 dst だけ守る or stack だけ守るときに使う。
     */
    public static boolean isAutoDepositBlocked(AbstractContainerMenu menu, int dstMenuSlot, ItemStack stack) {
        if (isProtectedByMenuSlot(menu, dstMenuSlot))
            return true;
        return isItemLockedStack(stack);
    }
}
