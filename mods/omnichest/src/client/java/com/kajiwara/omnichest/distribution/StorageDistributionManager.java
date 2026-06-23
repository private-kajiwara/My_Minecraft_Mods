package com.kajiwara.omnichest.distribution;

import com.kajiwara.omnichest.classify.StorageCategory;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.config.data.DistributionConfig;
import com.kajiwara.omnichest.distribution.DistributionOpenTracker.OpenContext;
import com.kajiwara.omnichest.distribution.ui.DistributePreviewScreen;
import com.kajiwara.omnichest.distribution.ui.SetCategoryScreen;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.search.ContainerType;
import com.kajiwara.omnichest.slotlock.InventoryProtectionLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Storage Auto Distribution の中枢オーケストレータ。
 *
 * <p>
 * 仕様の 「基本動作」 (= [Auto Distribute] → 既知チェスト解析 → 保存先決定 → 順番に整理) を実装する。
 * 個々の責務 (分類 / 優先順位 / 安全移動 / 永続化 / GUI) は専用クラスに分離し、 ここは
 * <b>意思決定と接続</b> だけを行う。
 *
 * <p>
 * <b>遠隔チェスト対応</b>:
 * <ul>
 *   <li>行き先が <b>現在開いているチェスト</b> なら → {@link DistributionQueue} で今すぐ移動 (QUICK_MOVE)。</li>
 *   <li>行き先が <b>離れたチェスト</b> なら → {@link VirtualTransferRegistry} に予約 (= Virtual Transfer)。
 *       そのチェストを開いた瞬間 {@link #onContainerOpened} → {@link #applyPendingFor} で適用される。</li>
 * </ul>
 * これにより 「未ロードチェストを直接編集しない」 という Minecraft 制約を守りつつ、
 * 「遠隔整理されているように見える」 UX を実現する。
 */
public final class StorageDistributionManager {

    private StorageDistributionManager() {
    }

    // ════════════════════════════════════════════════════════════════════
    // Auto Distribute (= ボタン押下のメイン処理)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 現在開いているチェストを起点に Auto Distribute を実行する。
     * チェスト GUI 内の {@code [Auto Distribute]} ボタンから呼ばれる。
     */
    public static void distributeFromOpen() {
        OpenContext ctx = DistributionOpenTracker.get().active();
        if (ctx == null) {
            postChat(OmniChestLocale.get("omnichest.distribution.chat.no_open",
                    "§7[Auto Distribute] Open a chest first."));
            return;
        }
        DistributionResult result = distribute(ctx);
        postChat(result.toComponent());
    }

    /**
     * 振り分け本体。 プレイヤーインベントリ + 開いているチェストのアイテムを解析し、
     * カテゴリごとの保存先を決めて、 即時移動 or 予約に振り分ける。
     */
    public static DistributionResult distribute(OpenContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        DistributionConfig cfg = ConfigManager.get().distribution;
        if (!cfg.enableAutoDistribution) {
            return DistributionResult.disabled();
        }
        if (mc.player == null || mc.gameMode == null || ctx == null) {
            return DistributionResult.empty();
        }
        AbstractContainerMenu menu = ctx.menu();
        // menu がすり替わっていないか (= 別 GUI に遷移していないか) を検証。
        if (menu == null || mc.player.containerMenu != menu) {
            return DistributionResult.empty();
        }
        int slotCount = ctx.slotCount();
        if (slotCount <= 0 || slotCount >= menu.slots.size()) {
            return DistributionResult.empty();
        }
        // ─── 連打耐性 ───
        // 直前の振り分けがまだキュー処理中なら、 二重発火を抑止する (= ゴーストクリック / 履歴二重計上を防ぐ)。
        if (DistributionQueue.get().isBusy()) {
            return DistributionResult.busy();
        }
        // 予約 (Pending) は 「今インベントリにある、 他所行きのアイテム」 から毎回作り直す。
        // こうすることで Auto Distribute を連打しても予約数が二重計上されない (= 物理量は常に保存)。
        VirtualTransferRegistry.get().clear();

        StorageKey openKey = ctx.key();
        StorageAssignment openAssignment = StorageAssignmentManager.get().get(openKey);
        ResourceKey<Level> dim = mc.player.level().dimension();
        Vec3 playerPos = mc.player.position();
        DistributionPriorityMode mode = cfg.priorityMode;
        boolean historyOn = cfg.enableTransferHistory;
        String openLabel = openChestLabel(ctx, openAssignment);

        List<DistributionQueue.MoveOp> ops = new ArrayList<>();
        int movedToOpen = 0;
        int pendingCount = 0;
        int pulledOut = 0;

        // ─── (A) プレイヤーインベントリのアイテムを振り分け ───
        for (int i = slotCount; i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            // Slot Lock 連携: 保護スロットは触らない (= 既存の保護レイヤを尊重)。
            if (InventoryProtectionLayer.isProtectedByMenuSlot(menu, i)) {
                continue;
            }
            StorageCategory cat = CategoryMapper.toStorageCategory(stack);

            // 開いているチェストがこのカテゴリ用なら、 そこへ今すぐ入れる (= 最も自然)。
            if (openAssignment != null && openAssignment.category() == cat) {
                ops.add(new DistributionQueue.MoveOp(menu.containerId, i, stack.copy()));
                movedToOpen += stack.getCount();
                if (historyOn) {
                    TransferHistoryManager.get().record(stack, stack.getCount(),
                            playerLabel(), openLabel, cat, true);
                }
                continue;
            }

            StorageAssignment target = StoragePriorityResolver.resolve(cat, mode, dim, playerPos);
            if (target == null) {
                continue; // 行き先となる登録倉庫が無い → そのまま。
            }
            if (openKey != null && target.key().equals(openKey)) {
                // 行き先が開いているチェスト → 今すぐ移動。
                ops.add(new DistributionQueue.MoveOp(menu.containerId, i, stack.copy()));
                movedToOpen += stack.getCount();
                if (historyOn) {
                    TransferHistoryManager.get().record(stack, stack.getCount(),
                            playerLabel(), target.name(), cat, true);
                }
            } else {
                // 行き先が遠隔チェスト → 予約 (Virtual Transfer)。
                VirtualTransferRegistry.get().add(target.key(), cat, stack, stack.getCount(), playerLabel());
                pendingCount += stack.getCount();
            }
        }

        // ─── (B) 開いているチェスト内の 「場違いなアイテム」 を、 本来の倉庫へ送る ───
        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            StorageCategory cat = CategoryMapper.toStorageCategory(stack);
            // この開いているチェストが既にこのカテゴリ用なら 「定位置」 なので動かさない。
            if (openAssignment != null && openAssignment.category() == cat) {
                continue;
            }
            StorageAssignment target = StoragePriorityResolver.resolve(cat, mode, dim, playerPos);
            if (target == null || (openKey != null && target.key().equals(openKey))) {
                continue;
            }
            // チェストからプレイヤーインベントリへ取り出し (QUICK_MOVE) → 行き先へ予約。
            ops.add(new DistributionQueue.MoveOp(menu.containerId, i, stack.copy()));
            VirtualTransferRegistry.get().add(target.key(), cat, stack, stack.getCount(), openLabel);
            pulledOut += stack.getCount();
        }

        DistributionQueue.get().enqueue(menu.containerId, ops, null);
        return new DistributionResult(true, movedToOpen, pendingCount, pulledOut);
    }

    // ════════════════════════════════════════════════════════════════════
    // チェストを開いた瞬間の処理 (= fill 更新 + pending 自動適用)
    // ════════════════════════════════════════════════════════════════════

    /** {@link DistributionOpenTracker} から、 チェストを開いた瞬間に呼ばれる。 */
    public static void onContainerOpened(OpenContext ctx) {
        if (ctx == null) {
            return;
        }
        // (1) 登録倉庫なら、 開いた時点の使用状況を記録 (= emptiest first 判定の近似データ更新)。
        StorageAssignment a = StorageAssignmentManager.get().get(ctx.key());
        if (a != null) {
            int used = countUsedSlots(ctx.menu(), ctx.slotCount());
            StorageAssignmentManager.get().put(
                    a.withObservedFill(used, ctx.slotCount(), System.currentTimeMillis()));
        }

        // (2) Auto Apply: このチェスト宛ての予約があれば自動適用する。
        DistributionConfig cfg = ConfigManager.get().distribution;
        if (cfg.enableAutoDistribution && cfg.autoApplyPendingTransfers) {
            applyPendingFor(ctx);
        }
    }

    /**
     * 開いたチェスト宛ての {@link PendingTransfer} を適用する (= インベントリ内の一致アイテムを投入)。
     * 「Auto Apply」 OFF のときでも GUI の手動適用などから呼べるよう public にしている。
     */
    public static int applyPendingFor(OpenContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || ctx == null) {
            return 0;
        }
        AbstractContainerMenu menu = ctx.menu();
        if (menu == null || mc.player.containerMenu != menu) {
            return 0;
        }
        List<PendingTransfer> list = VirtualTransferRegistry.get().forTarget(ctx.key());
        if (list.isEmpty()) {
            return 0;
        }
        int slotCount = ctx.slotCount();
        boolean historyOn = ConfigManager.get().distribution.enableTransferHistory;
        StorageAssignment a = StorageAssignmentManager.get().get(ctx.key());
        String targetLabel = openChestLabel(ctx, a);

        List<DistributionQueue.MoveOp> ops = new ArrayList<>();
        int appliedTransfers = 0;
        // 同一行き先に同種アイテムの予約が複数件ある場合 (= addRaw による永続化ロード等) でも、
        // 1 つの在庫スロットを複数 pending へ二重計上しないよう、 消費済みスロットを追跡する。
        Set<Integer> consumedSlots = new HashSet<>();

        for (PendingTransfer p : list) {
            int found = 0;
            for (int i = slotCount; i < menu.slots.size(); i++) {
                if (consumedSlots.contains(i)) {
                    continue;
                }
                ItemStack stack = menu.slots.get(i).getItem();
                if (stack.isEmpty() || !p.matches(stack)) {
                    continue;
                }
                if (InventoryProtectionLayer.isProtectedByMenuSlot(menu, i)) {
                    continue;
                }
                ops.add(new DistributionQueue.MoveOp(menu.containerId, i, stack.copy()));
                consumedSlots.add(i);
                found += stack.getCount();
            }
            if (found > 0) {
                appliedTransfers++;
                if (historyOn) {
                    TransferHistoryManager.get().record(p.representative(), Math.min(found, p.count()),
                            p.sourceLabel(), targetLabel, p.category(), true);
                }
            } else if (historyOn) {
                // インベントリに該当アイテムが無い = 予約は失効。 Failed として残す。
                TransferHistoryManager.get().record(p.representative(), p.count(),
                        p.sourceLabel(), targetLabel, p.category(), false);
            }
        }

        // 予約は適用試行で消費する (= 残すと二重適用になる)。
        VirtualTransferRegistry.get().clearTarget(ctx.key());
        DistributionQueue.get().enqueue(menu.containerId, ops, null);
        return appliedTransfers;
    }

    // ════════════════════════════════════════════════════════════════════
    // Set Category 画面起動
    // ════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════
    // Auto Distribute プレビュー (= 実行前に行き先を確認させる)
    // ════════════════════════════════════════════════════════════════════

    /**
     * チェスト GUI 内の {@code [Auto Distribute]} ボタンから、 <b>実行前の確認画面</b> を開く。
     *
     * <p>
     * 仕様 (= 自動整理の行き先プレビュー): 何がどこへ送られるのかを {@link DistributePreviewScreen}
     * で提示し、 プレイヤーが Confirm したときだけ実際の {@link #distributeFromOpen()} を走らせる。
     * これにより 「押したら何が起きるか」 を事前に理解でき、 自動化への信頼を高める。
     *
     * <p>
     * <b>ロジック非変更</b>: 振り分け計算/実行は {@link #distribute(OpenContext)} のまま。 ここは
     * 確認ステップを <b>前段に挿入する</b> だけで、 プレビューは完全にクライアント側の読み取り専用処理。
     */
    public static void openDistributePreview(@Nullable Screen parent) {
        OpenContext ctx = DistributionOpenTracker.get().active();
        Minecraft mc = Minecraft.getInstance();
        if (ctx == null) {
            postChat(OmniChestLocale.get("omnichest.distribution.chat.no_open",
                    "§7[Auto Distribute] Open a chest first."));
            return;
        }
        if (!ConfigManager.get().distribution.enableAutoDistribution) {
            postChat(DistributionResult.disabled().toComponent());
            return;
        }
        //? if >=26.2 {
        /*mc.setScreenAndShow(new DistributePreviewScreen(parent, computePreview(ctx)));*/
        //?} else {
        mc.setScreen(new DistributePreviewScreen(parent, computePreview(ctx)));
        //?}
    }

    /**
     * 振り分けの<b>プレビュー</b>を、 実際の {@link #distribute} と同じ判定ロジック
     * ({@link CategoryMapper#toStorageCategory} + {@link StoragePriorityResolver#resolve} +
     * Slot Lock 尊重) で<b>副作用なし</b>に算出する。 キュー登録/予約/履歴記録はしない。
     *
     * <p>
     * 戻り値は 「送り元チェストの中身」 と 「送り先チェストごとに振り分けられるアイテム」 を
     * インベントリ風に可視化するための {@link DistributionPreview}。 行き先は StorageKey 単位で
     * まとめ、 同一アイテムは型単位で個数を合算する。
     */
    public static DistributionPreview computePreview(OpenContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        DistributionConfig cfg = ConfigManager.get().distribution;

        StorageKey openKey = ctx == null ? null : ctx.key();
        StorageAssignment openAssignment = openKey == null
                ? null : StorageAssignmentManager.get().get(openKey);
        String openLabel = ctx == null ? "" : openChestLabel(ctx, openAssignment);
        StorageCategory sourceCat = openAssignment != null ? openAssignment.category() : null;
        ContainerType sourceType = ctx != null ? ctx.type() : ContainerType.OTHER;

        List<ItemStack> sourceItems = new ArrayList<>();
        java.util.LinkedHashMap<StorageKey, GroupBuilder> groups = new java.util.LinkedHashMap<>();
        // 「行き先となる倉庫が無い」 ために動かせなかった具体カテゴリ (= 何が足りないかの可視化用)。
        // 副作用なし: distribute() の判定には一切関与せず、 プレビュー表示専用の読み取り情報。
        java.util.LinkedHashSet<StorageCategory> required = new java.util.LinkedHashSet<>();

        if (cfg.enableAutoDistribution && mc.player != null && ctx != null) {
            AbstractContainerMenu menu = ctx.menu();
            int slotCount = ctx.slotCount();
            if (menu != null && mc.player.containerMenu == menu
                    && slotCount > 0 && slotCount < menu.slots.size()) {

                ResourceKey<Level> dim = mc.player.level().dimension();
                Vec3 playerPos = mc.player.position();
                DistributionPriorityMode mode = cfg.priorityMode;

                // 送り元パネル用: 開いているチェストの現在の中身 (= 実スロット順)。
                for (int i = 0; i < slotCount; i++) {
                    ItemStack stack = menu.slots.get(i).getItem();
                    if (!stack.isEmpty()) {
                        sourceItems.add(stack.copy());
                    }
                }

                // (A) プレイヤーインベントリ → 行き先 (= distribute() の (A) と同じ判定)。
                for (int i = slotCount; i < menu.slots.size(); i++) {
                    ItemStack stack = menu.slots.get(i).getItem();
                    if (stack.isEmpty() || InventoryProtectionLayer.isProtectedByMenuSlot(menu, i)) {
                        continue;
                    }
                    StorageCategory cat = CategoryMapper.toStorageCategory(stack);
                    if (openAssignment != null && openAssignment.category() == cat) {
                        addToGroup(groups, openKey, openLabel, sourceType, cat, stack); // 開チェストへ投入。
                    } else {
                        StorageAssignment target = StoragePriorityResolver.resolve(cat, mode, dim, playerPos);
                        if (target != null) {
                            addToGroup(groups, target.key(), target.name(), target.type(), cat, stack);
                        } else if (cat.isConcrete()) {
                            required.add(cat); // 行き先倉庫が未登録のカテゴリ。
                        }
                    }
                }

                // (B) 開いているチェスト内の場違いなアイテム → 本来の倉庫 (= distribute() の (B) と同じ)。
                for (int i = 0; i < slotCount; i++) {
                    ItemStack stack = menu.slots.get(i).getItem();
                    if (stack.isEmpty()) {
                        continue;
                    }
                    StorageCategory cat = CategoryMapper.toStorageCategory(stack);
                    if (openAssignment != null && openAssignment.category() == cat) {
                        continue;
                    }
                    StorageAssignment target = StoragePriorityResolver.resolve(cat, mode, dim, playerPos);
                    if (target == null || (openKey != null && target.key().equals(openKey))) {
                        if (target == null && cat.isConcrete()) {
                            required.add(cat); // 行き先倉庫が未登録のカテゴリ。
                        }
                        continue;
                    }
                    addToGroup(groups, target.key(), target.name(), target.type(), cat, stack);
                }
            }
        }

        List<DestinationGroup> dests = new ArrayList<>();
        for (GroupBuilder b : groups.values()) {
            // 実際に 1 個以上受け取る行き先だけを残す (= 何も移動しない行き先はプレビューに出さない)。
            if (b.total > 0 && !b.items.isEmpty()) {
                dests.add(new DestinationGroup(b.name, b.type, b.category, b.items, b.total));
            }
        }
        // 行き先のあるカテゴリは 「足りないもの」 ではないので required から除く
        // (= 一部が遠隔倉庫へ送れる場合に矛盾した表示を避ける)。
        for (DestinationGroup d : dests) {
            required.remove(d.category());
        }
        return new DistributionPreview(openLabel, sourceCat, sourceType, sourceItems, dests,
                new ArrayList<>(required));
    }

    /** {@link #computePreview} 用: 行き先 (StorageKey) ごとにアイテムを型単位で合算する。 */
    private static void addToGroup(java.util.LinkedHashMap<StorageKey, GroupBuilder> groups,
            StorageKey key, String name, ContainerType type, StorageCategory cat, ItemStack stack) {
        GroupBuilder b = groups.computeIfAbsent(key, k -> new GroupBuilder(name, type, cat));
        b.total += stack.getCount();
        for (ItemStack s : b.items) {
            if (ItemStack.isSameItemSameComponents(s, stack)) {
                s.grow(stack.getCount());
                return;
            }
        }
        b.items.add(stack.copy());
    }

    /** {@link #computePreview} の集計用 可変ビルダ (= 内部限定)。 */
    private static final class GroupBuilder {
        final String name;
        final ContainerType type;
        final StorageCategory category;
        final List<ItemStack> items = new ArrayList<>();
        int total;

        GroupBuilder(String name, ContainerType type, StorageCategory category) {
            this.name = name;
            this.type = type;
            this.category = category;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Set Category 画面起動
    // ════════════════════════════════════════════════════════════════════

    /** チェスト GUI 内の {@code [Set Category]} ボタンから、 現在のチェストの登録画面を開く。 */
    public static void openSetCategoryForCurrent(@Nullable Screen parent) {
        OpenContext ctx = DistributionOpenTracker.get().active();
        Minecraft mc = Minecraft.getInstance();
        if (ctx == null) {
            postChat(OmniChestLocale.get("omnichest.distribution.chat.no_open",
                    "§7[Auto Distribute] Open a chest first."));
            return;
        }
        //? if >=26.2 {
        /*mc.setScreenAndShow(new SetCategoryScreen(parent, ctx));*/
        //?} else {
        mc.setScreen(new SetCategoryScreen(parent, ctx));
        //?}
    }

    // ════════════════════════════════════════════════════════════════════
    // ヘルパ
    // ════════════════════════════════════════════════════════════════════

    private static int countUsedSlots(AbstractContainerMenu menu, int slotCount) {
        int used = 0;
        int n = Math.min(slotCount, menu.slots.size());
        for (int i = 0; i < n; i++) {
            if (!menu.slots.get(i).getItem().isEmpty()) {
                used++;
            }
        }
        return used;
    }

    private static String playerLabel() {
        return OmniChestLocale.getString("omnichest.distribution.source.player", "Player Inventory");
    }

    private static String openChestLabel(OpenContext ctx, @Nullable StorageAssignment a) {
        if (a != null) {
            return a.name();
        }
        if (ctx.title() != null && !ctx.title().isBlank()) {
            return ctx.title();
        }
        return ctx.type().displayString();
    }

    private static void postChat(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            if (mc.player != null) mc.player.sendSystemMessage(msg);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 結果 DTO
    // ════════════════════════════════════════════════════════════════════

    /**
     * Auto Distribute の結果サマリ。 チャットへのフィードバック生成に使う。
     *
     * @param ran          実行されたか (= 機能 OFF なら false)
     * @param movedToOpen  開いているチェストへ今すぐ入れた個数
     * @param pending      遠隔チェストへ予約した個数
     * @param pulledOut    開いているチェストから取り出した個数
     */
    public record DistributionResult(boolean ran, int movedToOpen, int pending, int pulledOut) {

        static DistributionResult empty() {
            return new DistributionResult(true, 0, 0, 0);
        }

        static DistributionResult disabled() {
            return new DistributionResult(false, 0, 0, 0);
        }

        /** 直前の振り分けがまだ処理中 (= 連打抑止)。 movedToOpen に -1 を入れて区別する。 */
        static DistributionResult busy() {
            return new DistributionResult(true, -1, 0, 0);
        }

        public Component toComponent() {
            if (!ran) {
                return OmniChestLocale.get("omnichest.distribution.chat.disabled",
                        "§7[Auto Distribute] Feature is disabled in config.");
            }
            if (movedToOpen < 0) {
                return OmniChestLocale.get("omnichest.distribution.chat.busy",
                        "§7[Auto Distribute] Still working… try again in a moment.");
            }
            return OmniChestLocale.get("omnichest.distribution.chat.summary",
                    "§a[Auto Distribute] §rDeposited %1$d, queued %2$d for remote chests, pulled %3$d.",
                    movedToOpen, pending, pulledOut);
        }
    }

    /**
     * Auto Distribute プレビュー全体のデータ。 送り元チェスト (= 開いているチェスト) の中身と、
     * 送り先チェストごとに振り分けられるアイテム群を、 インベントリ風に並べて見せるための表現。
     *
     * @param sourceLabel    送り元チェスト名
     * @param sourceCategory 送り元チェストの<b>割り当て</b>カテゴリ (= 無ければ null)。 ヘッダ色に使う。
     * @param sourceItems    送り元チェストの現在の中身 (= 実スロット順のコピー)
     * @param destinations   送り先チェストごとのグループ (= 受け取るアイテム群)
     * @param requiredCategories 振り分けたいのに行き先倉庫が未登録だった具体カテゴリ
     *                           (= empty-state で 「何の倉庫を用意すれば良いか」 を示す。 表示専用)
     */
    public record DistributionPreview(String sourceLabel, @Nullable StorageCategory sourceCategory,
            ContainerType sourceType, List<ItemStack> sourceItems, List<DestinationGroup> destinations,
            List<StorageCategory> requiredCategories) {

        /** 振り分け先が 1 件も無い (= 動かすものが無い) か。 */
        public boolean isEmpty() {
            return destinations.isEmpty();
        }
    }

    /**
     * 1 つの送り先チェストに振り分けられるアイテム群。
     *
     * @param name       送り先チェスト名
     * @param category   送り先のカテゴリ (= 色とカテゴリ名の元)
     * @param items      送られるアイテム (= 型単位で合算済み)
     * @param totalCount 送られる総個数
     */
    public record DestinationGroup(String name, ContainerType type, StorageCategory category,
            List<ItemStack> items, int totalCount) {
    }
}
