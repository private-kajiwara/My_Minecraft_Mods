package com.kajiwara.omnichest.search;

import com.kajiwara.omnichest.OmniChest;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「プレイヤーが実際に開いた」コンテナを観測し、スナップショットを
 * {@link ChestNetworkManager} に登録するイベント駆動の収集器。
 *
 * <p>
 * 重要な仕様 (クライアントから取得可能な情報のみを使用):
 * <ul>
 * <li>未発見ブロックの中身を勝手にスキャンする実装は禁止。
 * 本クラスは「右クリックで実際に開いた瞬間」と
 * 「コンテナ GUI を閉じる瞬間」だけを記録タイミングとする。</li>
 * <li>UseBlockCallback で「次に開かれるコンテナの BlockPos」候補を記録し、
 * 直後の {@link ScreenEvents#AFTER_INIT} で
 * その screen が {@link AbstractContainerScreen} なら確定させる。</li>
 * <li>screen 表示中はスロット内容が逐次同期されるので、
 * GUI を閉じる直前のタイミングで最終スナップショットを取る。
 * 加えて、 client tick ごとに「内容変化があれば」差分スナップショットを更新する。</li>
 * </ul>
 *
 * <p>
 * 拡張ポイント:
 * <ul>
 * <li>{@link #isSupportedMenu(AbstractContainerMenu)} と
 * {@link #containerSlotCountOf(AbstractContainerMenu)} を拡張すれば、
 * MOD コンテナを後付けで対応できる。</li>
 * </ul>
 */
public final class ContainerScanner {

    private ContainerScanner() {
    }

    /**
     * 次に AbstractContainerScreen が開いたとき、それがこの BlockPos のものだと仮定する。
     * UseBlockCallback で更新され、 ScreenEvents.AFTER_INIT で消費 (もしくは破棄) される。
     */
    @Nullable
    private static PendingOpen pendingOpen;

    /**
     * 現在開いているコンテナ GUI の追跡情報。 null = 何も開いてない (or 非対応 GUI)。
     */
    @Nullable
    private static ActiveTracker active;

    /**
     * Fabric イベントへの登録を一括で行う。 ClientModInitializer から 1 度だけ呼ぶこと。
     */
    public static void register() {
        // ────────────────────────────────────────────────────────────
        // (1) 右クリックでブロックを叩いた瞬間: コンテナだったら pending に保存
        // ────────────────────────────────────────────────────────────
        UseBlockCallback.EVENT.register(ContainerScanner::onUseBlock);

        // ────────────────────────────────────────────────────────────
        // (1') 右クリックでエンティティに触れた瞬間: コンテナを持つエンティティ
        //      (= チェスト付きトロッコ / チェスト付きボート / チェストを積んだモブ) なら pending に保存。
        //      以後の処理 (= AFTER_INIT での確定, tick での再キャプチャ, 閉じる瞬間の最終取得) は
        //      ブロック経路と完全に共通 (= 中身は開いた menu の slots から取得)。
        // ────────────────────────────────────────────────────────────
        UseEntityCallback.EVENT.register(ContainerScanner::onUseEntity);

        // ────────────────────────────────────────────────────────────
        // (2) Screen がセットされた直後: AbstractContainerScreen なら追跡開始
        // ────────────────────────────────────────────────────────────
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> onScreenInit(screen));

        // ────────────────────────────────────────────────────────────
        // (3) client tick: 内容変化なら更新 / 退場時にクリーンアップ
        // ────────────────────────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(ContainerScanner::onClientTick);

        // ────────────────────────────────────────────────────────────
        // (4) 切断時: 全スナップショットを破棄 (将来は永続化に置換可能)
        // ────────────────────────────────────────────────────────────
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            pendingOpen = null;
            active = null;
            entityUnresolvedSince.clear();
            ChestNetworkManager.get().clear();
        });

        // ────────────────────────────────────────────────────────────
        // (4') エンティティ再ロード時の再アンカー (= 永続復元したエンティティコンテナの追従復帰)。
        //      永続化から復元した snapshot は networkId 未確定 (= センチネル) で一覧には出るが
        //      ワールド追従ができない。 実体がクライアントに再ロードされた瞬間に UUID 一致を見て
        //      実 networkId へ差し替える (= ピン/ハイライトが再び追従)。 Fabric API のイベントなので
        //      MC マッピング名に依存せず全版で同一に動く。
        // ────────────────────────────────────────────────────────────
        ClientEntityEvents.ENTITY_LOAD.register(ContainerScanner::onEntityLoad);

        // ────────────────────────────────────────────────────────────
        // (5) クライアントが見ているプレイヤーがチェスト/シュルカー等を壊した瞬間に、
        //     対応スナップショットを即座に取り除く (= 1 秒の periodic sweep を待たない)。
        //
        // 周期 sweep ({@link #sweepBrokenContainers}) は爆発 / 他 MOD / 採掘ボット等
        // 「自プレイヤー以外の経路で消えたコンテナ」 もカバーする保険として残す。
        // ここで AFTER をハンドルする目的は、 ユーザ自身の操作に対して「壊した瞬間に
        // 検索一覧から消える」 体感を出すこと (= UX 要件)。
        //
        // ChestHighlighter のピン側もまとめて掃除して、 取り残しを防ぐ。
        // ────────────────────────────────────────────────────────────
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            if (world == null || pos == null || state == null) return;
            // 壊された state がコンテナでなければ何もしない (= 速い早期 return)。
            if (ContainerType.fromBlockState(state) == null) return;

            ResourceKey<Level> dim = world.dimension();
            // 単体 + ラージチェスト両半 (もし state から相棒が分かるなら) を 1 回でクリアする。
            BlockPos other = ContainerType.otherHalfOrNull(world, pos, state);
            invalidateBrokenContainerAt(dim, pos);
            if (other != null) {
                invalidateBrokenContainerAt(dim, other);
            }
        });
    }

    /**
     * 指定位置のコンテナスナップショットと、 そこに対応する {@link ChestHighlighter} のピン /
     * ワイヤーを即座に取り除く。
     *
     * <p>
     * スナップショットの {@code Key} はラージチェストの「normalize 済み座標」 を含むため、
     * 単純な {@code (dim, pos)} 一致だけでなく、 ペア相手 {@code secondaryPos()} の一致でも
     * ヒットする必要がある。 ここでは {@link ChestNetworkManager#snapshots()} を 1 度走査し、
     * 「primary or secondary が指定 pos と一致する」 ものをすべて消す。
     */
    private static void invalidateBrokenContainerAt(ResourceKey<Level> dim, BlockPos pos) {
        java.util.List<ContainerSnapshot.Key> toRemove = new ArrayList<>();
        for (ContainerSnapshot snap : ChestNetworkManager.get().snapshots()) {
            if (!dim.equals(snap.dimension())) continue;
            // エンティティ snapshot はブロック破壊と無関係 (= 捕捉位置が偶然一致しても消さない)。
            if (snap.isEntity()) continue;
            boolean match = pos.equals(snap.pos())
                    || (snap.secondaryPos() != null && pos.equals(snap.secondaryPos()));
            if (match) {
                toRemove.add(snap.key());
            }
        }
        for (ContainerSnapshot.Key key : toRemove) {
            ChestNetworkManager.get().remove(key);
            com.kajiwara.omnichest.client.render.ChestHighlighter.get().clearForKey(key);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // イベントハンドラ
    // ════════════════════════════════════════════════════════════════════

    private static InteractionResult onUseBlock(Player player, Level level, net.minecraft.world.InteractionHand hand,
            BlockHitResult hit) {
        // クライアント側で発火する場合のみ処理する。サーバ側は無視する。
        if (level.isClientSide()) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            ContainerType ct = ContainerType.fromBlockState(state);
            // エンダーチェストは設定で OFF のとき収集しない (= 既存検索に混ぜない)。
            // 他コンテナは EnderChestStorageBridge.shouldTrack が常に true を返すため影響なし。
            if (ct != null && EnderChestStorageBridge.shouldTrack(ct)) {
                BlockPos other = ContainerType.otherHalfOrNull(level, pos, state);
                pendingOpen = new PendingOpen(level.dimension(), pos.immutable(),
                        other == null ? null : other.immutable(), ct, System.currentTimeMillis());
            }
        }
        // イベントは PASS で渡し、バニラ挙動を妨げない。
        return InteractionResult.PASS;
    }

    /**
     * エンティティ右クリック時のハンドラ。 コンテナを持つエンティティ (= トロッコ / ボート / モブ)
     * なら「次に開かれる menu の所有者」 として {@code pendingOpen} に記録する。
     * <p>
     * ブロック経路と同様に <b>PASS</b> で返してバニラ挙動を妨げない。 中身は一切ここで読まず、
     * 実際に開いた {@link #onScreenInit} 以降で menu から取得する (= 開封時キャッシュ方針を踏襲)。
     */
    private static InteractionResult onUseEntity(Player player, Level level,
            net.minecraft.world.InteractionHand hand, Entity entity, @Nullable EntityHitResult hit) {
        // クライアント側のみ。 サーバ側は無視する。
        if (level.isClientSide() && entity != null) {
            ContainerType ct = entityContainerType(entity);
            if (ct != null) {
                pendingOpen = PendingOpen.forEntity(level.dimension(), entity, ct);
            }
        }
        return InteractionResult.PASS;
    }

    /**
     * エンティティが「中身を観測できるコンテナを持つ対象」 なら {@link ContainerType} を返す。
     * 非対象 (= 通常モブ / チェスト非搭載のボート・モブ / ホッパー付きトロッコ等) は {@code null}。
     *
     * <p>
     * <b>対象</b> (= 右クリックで GUI を開いて中身を観測できるバニラエンティティ):
     * <ul>
     *   <li>{@link MinecartChest} → {@link ContainerType#CHEST_MINECART} (= {@code ChestMenu} 27)</li>
     *   <li>{@link AbstractChestBoat} (= チェスト付きボート / イカダ) → {@link ContainerType#CHEST_BOAT}</li>
     *   <li>{@link AbstractChestedHorse} かつ {@code hasChest()} (= ロバ / ラバ / ラマ / 行商ラマ)
     *       → {@link ContainerType#MOB_CHEST} (= {@code HorseInventoryMenu})</li>
     * </ul>
     * <b>非対象</b>: 通常の馬 (= {@code AbstractHorse} だが {@code AbstractChestedHorse} ではない) と
     * チェスト未装着のロバ等は {@code hasChest()==false} で弾く。 ホッパー付きトロッコは
     * バニラで GUI を開けない (= 中身を観測できない) ため、 そもそも対象外 (instanceof で拾わない)。
     */
    @Nullable
    private static ContainerType entityContainerType(Entity entity) {
        if (entity instanceof MinecartChest) {
            return ContainerType.CHEST_MINECART;
        }
        if (entity instanceof AbstractChestBoat) {
            return ContainerType.CHEST_BOAT;
        }
        if (entity instanceof AbstractChestedHorse horse && horse.hasChest()) {
            return ContainerType.MOB_CHEST;
        }
        return null;
    }

    private static void onScreenInit(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) {
            // コンテナ系 Screen でなければ pending を破棄する (= 誤適用防止)。
            // ただし active は<b>消さない</b>: Set Category / 振り分けプレビュー等の自前サブ画面を
            // chest GUI の上に重ねている間もコンテナ自体は開いたままで、 復帰時にバッジ等が即座に
            // カテゴリを引けるよう追跡を維持する。 実際にチェストが閉じた判定は onClientTick が
            // player.containerMenu で行う (= 重ねただけでは消えない)。
            pendingOpen = null;
            return;
        }
        AbstractContainerMenu menu = cs.getMenu();
        if (!isSupportedMenu(menu)) {
            // ChestMenu / ShulkerBoxMenu でなければ追跡対象外。
            pendingOpen = null;
            active = null;
            return;
        }

        // pending が設定されていれば、それを今開いた screen に紐付ける。
        // (pending が無い場合: e.g. /open コマンド経由でいきなり開かれたケース等。
        // 位置情報が無いので追跡しない = 安全側に倒す)
        if (pendingOpen == null) {
            // pending 無し。 ただし、 追跡中のコンテナへそのまま復帰した場合
            // (= Set Category 等のサブ画面から chest GUI に戻った) は既存 active を維持する。
            // これをしないと復帰直後にバッジのキー (currentActiveKey) が null になり、
            // 手動カテゴリが即時に表示されない (= 再オープンが必要に見える) 問題が起きる。
            if (active != null && active.menu == menu) {
                return;
            }
            active = null;
            return;
        }

        // 捕捉対象スロットの範囲 [start, start+count) を決める。
        // ブロック / トロッコ / ボートは先頭から (start=0)、 馬系 (HorseInventoryMenu) は
        // 鞍 / 防具スロットを除いた「チェスト収納スロット」 のみ (= 容器側末尾の columns*3)。
        int[] range = computeSlotRange(menu, pendingOpen);
        if (range == null) {
            active = null;
            pendingOpen = null;
            return;
        }

        active = new ActiveTracker(pendingOpen, menu, range[0], range[1]);
        pendingOpen = null;

        // 開いた瞬間に即 1 回スナップショットを取る (初回の内容を確実に登録するため)。
        captureNow("open");
    }

    /**
     * この menu / pendingOpen に対して、 スナップショットを取るべきコンテナ側スロットの
     * 範囲 {@code [start, start+count)} を返す。 対象外なら {@code null}。
     *
     * <ul>
     *   <li>ブロック / チェスト付きトロッコ / チェスト付きボート: {@code start=0}、
     *       {@code count=}{@link #containerSlotCountOf}。</li>
     *   <li>チェストを積んだモブ ({@link HorseInventoryMenu}): 鞍 / 防具スロットを除外し、
     *       <b>チェスト収納スロットのみ</b> を取る。 チェストスロットは容器側 (= 非プレイヤー) 領域の
     *       末尾 {@code columns*3} 個に並ぶ ({@code AbstractMountInventoryMenu} の slot 追加順) ため、
     *       {@code start = (menu.slots.size() - 36) - columns*3}、 {@code count = columns*3}。</li>
     * </ul>
     */
    @Nullable
    private static int[] computeSlotRange(AbstractContainerMenu menu, PendingOpen p) {
        if (menu instanceof HorseInventoryMenu) {
            // HorseInventoryMenu はチェストを積んだモブの pendingOpen 経由でのみ追跡する。
            if (p == null || !(p.entity instanceof AbstractChestedHorse horse) || !horse.hasChest()) {
                return null;
            }
            int chestCount = Math.max(0, horse.getInventoryColumns()) * 3;
            if (chestCount <= 0) {
                return null;
            }
            // 容器側 = プレイヤーインベントリ (27+9=36) を除いた先頭領域。 チェストはその末尾。
            int containerSide = menu.slots.size() - 36;
            int start = containerSide - chestCount;
            if (start < 0 || containerSide <= 0) {
                return null;
            }
            return new int[] { start, chestCount };
        }
        int count = containerSlotCountOf(menu);
        if (count <= 0) {
            return null;
        }
        return new int[] { 0, count };
    }

    private static void onClientTick(Minecraft mc) {
        // 開いていた screen が閉じられた (= mc.screen が AbstractContainerScreen でなくなった) 検出。
        if (active != null) {
            // 「チェストを閉じたか」 は mc.screen ではなく、 サーバと同期した実際の開コンテナ
            // (player.containerMenu) で判定する。 自前のサブ画面 (Set Category / 振り分けプレビュー)
            // を重ねている間もコンテナは開いたままなので追跡を維持し、 実際に閉じた
            // (= 別コンテナ / インベントリへ復帰) ときだけクリアする。
            if (mc.player == null || mc.player.containerMenu != active.menu) {
                // 閉じる瞬間に「最後の内容」をスナップショットしておく。
                captureNow("close");
                active = null;
                return;
            }

            // 開いている間も、定期的に再キャプチャを試みる (差分更新)。
            // 毎 tick やると重いので、 ActiveTracker のカウンタで間引く。
            if (active.tickAndShouldRecapture()) {
                captureNow("tick");
            }
        }

        // ─── 破壊検出: 周期的に「ブロックが消えた」 チェストを取り除く ───
        // チェストを壊して中身が world にドロップ → ドロップ品をプレイヤーが拾うと、
        // ChestNetworkManager にスナップショットが残ったまま 「存在しないチェスト」
        // として検索に引っかかる現象が起きる。 これを防ぐため、 ロード済みチャンクに
        // 居るスナップショットだけを periodic にブロックチェックで検証する。
        if (++validityCheckCounter >= VALIDITY_CHECK_INTERVAL_TICKS) {
            validityCheckCounter = 0;
            sweepBrokenContainers(mc);
        }
    }

    /** ブロック検証の間隔 (= 1 秒)。 検証はロード済みチャンクのみ。 */
    private static final int VALIDITY_CHECK_INTERVAL_TICKS = 20;
    private static int validityCheckCounter = 0;

    /**
     * エンティティ剪定の grace (ms)。 ロード済みチャンクで解決不能なエンティティ snapshot を
     * 「消滅」と確定するまでの猶予。 永続復元直後 (= センチネル networkId) や、 実体の
     * ENTITY_LOAD が発火する前のタイミングでの誤剪定を防ぐ。 grace 経過後もなお
     * 「ロード済みチャンク かつ 解決不能」なら確実に消えたとみなして剪定する。
     */
    private static final long ENTITY_PRUNE_GRACE_MS = 5000L;
    /** エンティティ snapshot が「ロード済みなのに解決不能」になった最初の時刻 (= grace 計測)。 */
    private static final Map<ContainerSnapshot.Key, Long> entityUnresolvedSince = new HashMap<>();

    /**
     * 「ロード済みチャンクにあるはずなのに、 もうコンテナでないブロック」 のスナップショットを
     * {@link ChestNetworkManager} から取り除く。
     * <ul>
     *   <li>未ロードチャンクのスナップショットは <b>触らない</b> (= isLoaded で除外、 false positive 防止)。</li>
     *   <li>取り除いたついでに、 対応するハイライト
     *       ({@link com.kajiwara.omnichest.client.render.ChestHighlighter})
     *       にも消去依頼を出して、 ピン/ワイヤーフレームの取り残しも回避。</li>
     * </ul>
     */
    private static void sweepBrokenContainers(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) return;
        final long nowMs = System.currentTimeMillis();
        java.util.List<ContainerSnapshot.Key> toRemove = new ArrayList<>();
        ResourceKey<Level> dim = level.dimension();
        for (ContainerSnapshot snap : ChestNetworkManager.get().snapshots()) {
            if (!dim.equals(snap.dimension())) continue;
            // ─── エンティティコンテナ (= トロッコ / ボート / モブ) の生存検証 ───
            // ブロック検証 (fromBlockState) には掛けない (= snap.pos() に対応ブロックは無く誤除去になる)。
            // 再アンカーは ENTITY_LOAD (onEntityLoad) が担うので、 ここでは「解決可否」だけを見る:
            //   - 解決可 → 生存・アンカー済み。 grace タイマ解除して残す。
            //   - 未ロードチャンク → 「分からない → 残す」 (= ブロック側 isLoaded と同方針)。
            //   - ロード済みなのに解決不能 → 消滅の可能性。 ただし ENTITY_LOAD 前/復元直後の
            //     誤剪定を避けるため grace 経過後にのみ剪定 (= 「確実に消えた」 のみ)。
            if (snap.isEntity()) {
                EntityLocator loc = snap.entity();
                if (loc == null) {
                    continue;
                }
                // まだ一度も再アンカーされていない (= 永続復元直後のセンチネル) エントリは剪定しない。
                // 「今セッションで実体を確認できていない」 = 不在を確証できないため (例: tryLoad より前に
                // 既にロード済みで ENTITY_LOAD が再発火しないケース)。 一覧表示は維持され、 実体に
                // 再遭遇すれば onEntityLoad / 再オープンで再アンカーされる。
                if (loc.networkId() == EntityLocator.UNRESOLVED_NETWORK_ID) {
                    continue;
                }
                if (loc.resolve(level) != null || !level.isLoaded(snap.pos())) {
                    entityUnresolvedSince.remove(snap.key());
                    continue;
                }
                // アンカー済み (= 今セッションで実体を確認した) のに、 ロード済みチャンクで解決不能。
                // grace 経過後にのみ「確実に消えた」 として剪定する。
                long since = entityUnresolvedSince.computeIfAbsent(snap.key(), k -> nowMs);
                if (nowMs - since >= ENTITY_PRUNE_GRACE_MS) {
                    toRemove.add(snap.key());
                    entityUnresolvedSince.remove(snap.key());
                }
                continue;
            }
            BlockPos pos = snap.pos();
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (ContainerType.fromBlockState(state) == null) {
                toRemove.add(snap.key());
            }
        }
        if (toRemove.isEmpty()) return;
        for (ContainerSnapshot.Key key : toRemove) {
            ChestNetworkManager.get().remove(key);
            com.kajiwara.omnichest.client.render.ChestHighlighter.get().clearForKey(key);
        }
        // 診断: ロード済みエントリを唯一サイレントに削除し得る経路。 ここが効くと「load されたのに消えた」。
        OmniChest.LOGGER.info(
                "[omnichest] Swept {} broken/removed containers (managerSize={})",
                toRemove.size(), ChestNetworkManager.get().size());
    }

    /**
     * エンティティがクライアントに再ロードされた時の再アンカー。
     *
     * <p>
     * 永続化から復元したエンティティ snapshot は networkId 未確定 (= センチネル) で
     * {@link EntityLocator#resolve} できない。 実体が UUID 一致でロードされたら、 その snapshot を
     * 実 networkId と現在位置で差し替える (= items / lastSeenMillis は維持) ことで、 ピン/ハイライトの
     * ワールド追従を復帰させる。 一覧表示は再アンカー前から出ている (= 解決可否に依存しない)。
     *
     * <p>
     * 大多数の mob ロードを早期に弾くため、 コンテナ持ち候補の型でのみ manager を走査する。
     */
    private static void onEntityLoad(Entity entity, ClientLevel world) {
        if (entity == null || world == null) return;
        if (!isContainerEntity(entity)) return;
        UUID uuid = entity.getUUID();
        ResourceKey<Level> dim = world.dimension();
        for (ContainerSnapshot snap : ChestNetworkManager.get().snapshots()) {
            if (!snap.isEntity()) continue;
            EntityLocator loc = snap.entity();
            if (loc == null || !loc.uuid().equals(uuid)) continue;
            if (!dim.equals(snap.dimension())) continue;
            // 既に同じ networkId なら何もしない (= 再 put のスパムと不要なセーブ通知を避ける)。
            if (loc.networkId() == entity.getId()) return;
            // 再アンカー: 実 networkId と現在位置で snapshot を差し替える (= 同一 UUID キーへ上書き)。
            ChestNetworkManager.get().put(new ContainerSnapshot(
                    snap.dimension(), entity.blockPosition(), null, snap.type(),
                    snap.items(), snap.lastSeenMillis(),
                    new EntityLocator(uuid, entity.getId())));
            entityUnresolvedSince.remove(snap.key());
            return;
        }
    }

    /**
     * コンテナを持ち得るエンティティ型か (= トロッコ / ボート / 荷運びモブ)。
     * onEntityLoad の早期フィルタ用 (= 厳密な「チェスト積載中か」 ではなく、 候補型かどうか)。
     */
    private static boolean isContainerEntity(Entity entity) {
        return entity instanceof MinecartChest
                || entity instanceof AbstractChestBoat
                || entity instanceof AbstractChestedHorse;
    }

    // ════════════════════════════════════════════════════════════════════
    // スナップショット
    // ════════════════════════════════════════════════════════════════════

    /**
     * 現在 active なコンテナの内容をスナップショットして {@link ChestNetworkManager} に登録する。
     * 「変化なし」と判定された場合は登録をスキップする (毎 tick のスパム書き込みを避けるため)。
     */
    private static void captureNow(String reason) {
        ActiveTracker a = active;
        if (a == null)
            return;
        AbstractContainerMenu menu = a.menu;
        // 捕捉範囲 [slotStart, slotStart+slotCount)。 ブロックは slotStart=0 (= 従来と同一)、
        // 馬系のみ鞍 / 防具を除いたチェスト収納スロットへオフセットする。
        int start = a.slotStart;
        int end = Math.min(start + a.containerSlotCount, menu.slots.size());

        List<ItemStack> snap = new ArrayList<>(Math.max(0, end - start));
        for (int i = start; i < end; i++) {
            Slot s = menu.slots.get(i);
            snap.add(s.getItem().copy());
        }

        if (!a.hasContentChanged(snap)) {
            // ただし lastSeen の更新はしておきたい場合があるが、
            // 「open / close」のみ強制更新し、「tick」では差分なしならスキップする。
            if ("tick".equals(reason)) {
                return;
            }
        }
        a.lastSnapshot = snap;

        if (a.locator != null) {
            // エンティティコンテナ: 同一性は UUID、 フォールバック位置は現在の blockPosition()。
            // entity が消えている (= null) 場合は捕捉時 pos を使う (= 直前まで開いていた位置)。
            BlockPos entPos = (a.entity != null) ? a.entity.blockPosition() : a.pos;
            ChestNetworkManager.get().captureEntity(
                    a.dimension,
                    entPos,
                    a.type,
                    snap,
                    System.currentTimeMillis(),
                    a.locator);
            return;
        }

        ChestNetworkManager.get().capture(
                a.dimension,
                a.pos,
                a.secondaryPos,
                a.type,
                snap,
                System.currentTimeMillis());
        // 診断: ブロックコンテナ登録の瞬間 (= 何が / どこで / managerSize がいくつになったか)。
        OmniChest.LOGGER.info(
                "[omnichest] Captured block container {} at {} [{}] (managerSize={})",
                a.type, a.pos, reason, ChestNetworkManager.get().size());
    }

    // ════════════════════════════════════════════════════════════════════
    // 拡張ポイント (対応コンテナ判定)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 対象とする {@link AbstractContainerMenu} かどうか。
     * 将来 MOD コンテナ対応するときは、ここに分岐を増やせばよい。
     *
     * <p>
     * <b>対応 vanilla メニュー</b> (= プレイヤーが開いて中身を観測できるストレージ系):
     * <ul>
     *   <li>{@link ChestMenu} (= 小型 / ラージチェスト / トラップチェスト / バレル / エンダーチェスト)</li>
     *   <li>{@link ShulkerBoxMenu} (= シュルカーボックス)</li>
     *   <li>{@link HopperMenu} (= ホッパー, 5 スロット)</li>
     *   <li>{@link DispenserMenu} (= ディスペンサー / ドロッパー, 9 スロット)</li>
     *   <li>{@link CrafterMenu} (= クラフター, 9 入力スロット)</li>
     *   <li>{@link HorseInventoryMenu} (= チェストを積んだモブ。 チェスト収納スロットのみ捕捉する。
     *       追跡対象になるのは {@code MOB_CHEST} の pendingOpen 経由のみで、 通常の馬は
     *       {@code pendingOpen} が立たないため追跡されない)</li>
     * </ul>
     */
    public static boolean isSupportedMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu
                || menu instanceof ShulkerBoxMenu
                || menu instanceof HopperMenu
                || menu instanceof DispenserMenu
                || menu instanceof CrafterMenu
                || menu instanceof HorseInventoryMenu;
    }

    /**
     * 対象 menu のチェスト側スロット数 (= プレイヤーインベントリを除く先頭スロット数)。
     *
     * <p>
     * <b>各 vanilla メニューのスロット数</b>:
     * <ul>
     *   <li>Chest: rows * 9 (3 or 6 行 → 27 / 54)</li>
     *   <li>Shulker: 27 固定</li>
     *   <li>Hopper: 5 固定 ({@code HopperMenu.CONTAINER_SIZE})</li>
     *   <li>Dispenser / Dropper: 9 固定 (3x3 グリッド, 両者とも {@link DispenserMenu})</li>
     *   <li>Crafter: 9 固定 (3x3 入力グリッド)</li>
     * </ul>
     * 不明な menu は -1 を返す (= 未対応扱い)。
     */
    public static int containerSlotCountOf(AbstractContainerMenu menu) {
        if (menu instanceof ChestMenu chest)
            return chest.getRowCount() * 9;
        if (menu instanceof ShulkerBoxMenu)
            return 27;
        if (menu instanceof HopperMenu)
            return 5;
        if (menu instanceof DispenserMenu)
            return 9;
        if (menu instanceof CrafterMenu)
            return 9;
        return -1;
    }

    /**
     * 外部から強制再キャプチャを促すための入口。
     * 例: 「いまもう一度スキャンしたい」ボタンなど。
     */
    public static void forceRecapture() {
        captureNow("force");
    }

    /**
     * 現在追跡中のコンテナの {@link ContainerSnapshot.Key} を返す。
     * チェストを開いていない、もしくは pendingOpen が無いまま開いた (= 追跡対象外) 場合は null。
     *
     * <p>
     * GUI 側 (チェスト画面のバッジ描画など) から「今開いてるチェストの分類を引きたい」ときに使う。
     */
    @Nullable
    public static ContainerSnapshot.Key currentActiveKey() {
        ActiveTracker a = active;
        if (a == null)
            return null;
        // エンティティコンテナは UUID キー (= ブロックの normalizedPos キーと同じ位置付け)。
        if (a.locator != null) {
            return new ContainerSnapshot.Key(a.dimension, a.pos, a.locator.uuid());
        }
        // ラージチェストでも (dim, normalizedPos) の Key を返したい。
        BlockPos normalized = a.secondaryPos == null ? a.pos
                : ContainerSnapshot.normalize(a.pos, a.secondaryPos);
        return new ContainerSnapshot.Key(a.dimension, normalized);
    }

    // ════════════════════════════════════════════════════════════════════
    // ローカル DTO
    // ════════════════════════════════════════════════════════════════════

    private static final class PendingOpen {
        final ResourceKey<Level> dimension;
        final BlockPos pos;
        @Nullable
        final BlockPos secondaryPos;
        final ContainerType type;
        @SuppressWarnings("unused")
        final long timestamp;
        /** エンティティコンテナの場合の live 参照 (= 馬の columns / 現在位置算出用)。 ブロックは null。 */
        @Nullable
        final Entity entity;
        /** エンティティコンテナの場合の同一性 + 位置解決ロケータ。 ブロックは null。 */
        @Nullable
        final EntityLocator locator;

        PendingOpen(ResourceKey<Level> dimension, BlockPos pos, @Nullable BlockPos secondaryPos,
                ContainerType type, long timestamp) {
            this(dimension, pos, secondaryPos, type, timestamp, null, null);
        }

        PendingOpen(ResourceKey<Level> dimension, BlockPos pos, @Nullable BlockPos secondaryPos,
                ContainerType type, long timestamp, @Nullable Entity entity, @Nullable EntityLocator locator) {
            this.dimension = dimension;
            this.pos = pos;
            this.secondaryPos = secondaryPos;
            this.type = type;
            this.timestamp = timestamp;
            this.entity = entity;
            this.locator = locator;
        }

        /** コンテナを持つエンティティ用の pendingOpen を作る。 */
        static PendingOpen forEntity(ResourceKey<Level> dimension, Entity entity, ContainerType type) {
            return new PendingOpen(dimension, entity.blockPosition().immutable(), null, type,
                    System.currentTimeMillis(), entity, EntityLocator.of(entity));
        }
    }

    /** 開いている間中保持する追跡情報。 */
    private static final class ActiveTracker {
        final ResourceKey<Level> dimension;
        final BlockPos pos;
        @Nullable
        final BlockPos secondaryPos;
        final ContainerType type;
        final AbstractContainerMenu menu;
        /** 捕捉対象スロットの開始 index (= ブロックは 0、 馬系はチェスト収納の先頭)。 */
        final int slotStart;
        final int containerSlotCount;
        /** エンティティコンテナの live 参照 (= 現在 blockPosition 算出用)。 ブロックは null。 */
        @Nullable
        final Entity entity;
        /** エンティティコンテナのロケータ (= 非 null なら captureEntity 経路へ)。 ブロックは null。 */
        @Nullable
        final EntityLocator locator;

        /** 直近スナップショット (== 直近 manager に登録した内容)。 */
        @Nullable
        List<ItemStack> lastSnapshot;

        /** tick ごとの再キャプチャ判定用のカウンタ。 */
        private int tickCounter = 0;
        /** 何 tick に 1 回再キャプチャ判定するか (= 0.5 秒程度)。 */
        private static final int RECAPTURE_INTERVAL_TICKS = 10;

        ActiveTracker(PendingOpen p, AbstractContainerMenu menu, int slotStart, int slotCount) {
            this.dimension = p.dimension;
            this.pos = p.pos;
            this.secondaryPos = p.secondaryPos;
            this.type = p.type;
            this.menu = menu;
            this.slotStart = slotStart;
            this.containerSlotCount = slotCount;
            this.entity = p.entity;
            this.locator = p.locator;
        }

        boolean tickAndShouldRecapture() {
            tickCounter++;
            if (tickCounter >= RECAPTURE_INTERVAL_TICKS) {
                tickCounter = 0;
                return true;
            }
            return false;
        }

        /**
         * 直近スナップショットと比較して内容が変わったかを判定する。
         * 「同 ItemStack &amp; 同 count &amp; 同 components」なら変化なしとみなす。
         */
        boolean hasContentChanged(List<ItemStack> nextSnapshot) {
            if (lastSnapshot == null)
                return true;
            if (lastSnapshot.size() != nextSnapshot.size())
                return true;
            for (int i = 0; i < lastSnapshot.size(); i++) {
                ItemStack a = lastSnapshot.get(i);
                ItemStack b = nextSnapshot.get(i);
                if (a.isEmpty() && b.isEmpty())
                    continue;
                if (a.isEmpty() != b.isEmpty())
                    return true;
                if (a.getCount() != b.getCount())
                    return true;
                if (!ItemStack.isSameItemSameComponents(a, b))
                    return true;
            }
            return false;
        }
    }

}
