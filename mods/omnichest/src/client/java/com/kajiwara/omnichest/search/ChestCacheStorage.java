package com.kajiwara.omnichest.search;

import com.kajiwara.omnichest.OmniChest;
import com.mojang.serialization.DynamicOps;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * チェストネットワーク (= 開いたことのあるコンテナ群) を NBT で永続化する。
 *
 * <p>
 * 保存先:
 * <ul>
 * <li>シングルプレイ: {@code <config>/omnichest/chests/world_<level>.nbt}</li>
 * <li>マルチプレイ : {@code <config>/omnichest/chests/server_<ip-sanitized>.nbt}</li>
 * </ul>
 *
 * <p>
 * 仕様:
 * <ul>
 * <li>{@link ClientPlayConnectionEvents#JOIN} で load (= 過去スナップショットを復元)</li>
 * <li>{@link ChestNetworkManager} に listener を装着し、変更検知で throttled save</li>
 * <li>{@link ClientPlayConnectionEvents#DISCONNECT} で最終 save</li>
 * <li>同一サーバ / ワールドへ再ログインしたとき、前回開けたチェストを覚えている</li>
 * </ul>
 *
 * <p>
 * ItemStack のシリアライズは {@link ItemStack#CODEC} を使用し、 1.21+ の Data Components
 * (エンチャント / カスタム名 / ポーション 等) を漏れなく保存する。
 */
public final class ChestCacheStorage {

    /**
     * schema 形式 version。互換性破壊する変更時にインクリメント。
     * <ul>
     * <li>1: ブロックコンテナのみ保存 (エンティティは非永続)。</li>
     * <li>2: コンテナ持ちエンティティ (= トロッコ / ボート / モブ) も UUID 付きで保存。
     * v1 ファイルは entity タグ無しの block 群として従来どおりロードできる (= 後方互換)。</li>
     * </ul>
     */
    private static final int VERSION = 2;
    /** save の連打を抑制する最小間隔 (ms)。 */
    private static final long SAVE_THROTTLE_MS = 2000L;

    private static volatile long lastSaveMs = 0L;
    /** load 完了前の save を抑制するためのフラグ。 */
    private static volatile boolean loaded = false;

    private ChestCacheStorage() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // load は client thread (= レンダースレッド) で実行する。 registry/level の参照安全性を担保。
            client.execute(ChestCacheStorage::tryLoad);
        });

        // スナップショット変更 listener: throttled save。
        ChestNetworkManager.get().addListener(event -> {
            // CLEARED (= disconnect 時の全クリア) では save しない (= 直前データを残す)。
            if (event.kind() == ChestNetworkManager.ChangeKind.CLEARED)
                return;
            // load 前の listener fire (= load 自体の put イベント) も save しない。
            if (!loaded)
                return;
            trySaveThrottled();
        });

        // 切断時に最終 save (load 完了済み & 変化あり)。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (loaded)
                trySave("disconnect");
            loaded = false;
        });

        // ゲーム完全終了時 (= ウィンドウ× / Alt+F4 / Quit) にも最終 save。
        // CLIENT_STOPPING は Minecraft.close() 冒頭で発火し、 この時点では level / 統合サーバが
        // まだ生きているため currentCacheFile() / trySave() が正しく書き込める。
        // ワールド内に居たまま直接終了する経路では DISCONNECT の発火が保証されない (発火しても
        // level 破棄後だと trySave() が mc.level==null で no-op になる) ため、 ここで取りこぼしを防ぐ。
        // loaded ガードにより、 タイトル画面からの Quit (退出済み = loaded false) では発火しても
        // 既存キャッシュを空保存で上書きしない (加えて currentCacheFile() も null を返し二重に安全)。
        // throttle の直近変更トレーリング取りこぼしも、 この無条件 flush が拾う。
        // StorageMemory / SlotLockStorage と同じ DISCONNECT + CLIENT_STOPPING の二重フックに揃える。
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (loaded)
                trySave("client_stopping");
        });
    }

    private static void trySaveThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastSaveMs < SAVE_THROTTLE_MS)
            return;
        lastSaveMs = now;
        trySave("throttled");
    }

    public static void trySave() {
        trySave("manual");
    }

    /**
     * @param reason 発火経路 (= "throttled" / "disconnect" / "client_stopping" / "manual")。
     *               診断ログ用。 保存挙動には影響しない。
     */
    public static void trySave(String reason) {
        Path file = currentCacheFile();
        if (file == null) {
            // 診断: ワールド/サーバ未接続でファイルが決まらない (= 保存対象なし)。
            OmniChest.LOGGER.info("[omnichest] ChestCache save skip [{}]: no cache file (not in world/server)", reason);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            // 診断: level 破棄後の発火 (= この経路では書き込めない)。
            OmniChest.LOGGER.info("[omnichest] ChestCache save skip [{}]: level==null, file={}", reason, file);
            return;
        }
        RegistryAccess registries = level.registryAccess();
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);

        int written = 0;
        int entityWritten = 0;
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.putInt("version", VERSION);
            ListTag listTag = new ListTag();
            for (ContainerSnapshot snap : ChestNetworkManager.get().snapshots()) {
                // ブロック / エンティティ 双方を保存する (= v2)。 エンティティは UUID で同一性を持ち、
                // ロード時は最終既知座標で一覧へ復元、 実体再ロード時に networkId へ再アンカーされる
                // ({@link snapshotToTag} / {@link tagToSnapshot} / ContainerScanner の ENTITY_LOAD)。
                CompoundTag t = snapshotToTag(snap, ops);
                if (t != null) {
                    listTag.add(t);
                    written++;
                    if (snap.isEntity())
                        entityWritten++;
                }
            }
            root.put("snapshots", listTag);

            try (OutputStream os = Files.newOutputStream(file)) {
                NbtIo.writeCompressed(root, os);
            }
            // 診断: 何件を どのファイルへ どの経路で 書いたか (managerSize と突き合わせ可能)。
            OmniChest.LOGGER.info(
                    "[omnichest] Saved {} chest snapshots to {} [{}] (managerSize={}, entityWritten={})",
                    written, file, reason, ChestNetworkManager.get().size(), entityWritten);
        } catch (Exception e) {
            OmniChest.LOGGER.warn("Failed to save chest cache to {}", file, e);
        }
    }

    public static void tryLoad() {
        Path file = currentCacheFile();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            OmniChest.LOGGER.info("[omnichest] ChestCache load skip: level==null");
            loaded = true; // level 不在では load しないが、以後の save は許可。
            return;
        }
        if (file == null || !Files.exists(file)) {
            // 診断: ロード対象ファイル不在 (= 初回 / パス不一致の切り分け用)。
            OmniChest.LOGGER.info("[omnichest] ChestCache load: no file to load (file={})", file);
            loaded = true;
            return;
        }
        RegistryAccess registries = level.registryAccess();
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);

        try {
            CompoundTag root;
            try (InputStream is = Files.newInputStream(file)) {
                root = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
            }
            ListTag listTag = root.getList("snapshots").orElse(new ListTag());
            int count = 0;
            for (int i = 0; i < listTag.size(); i++) {
                CompoundTag tag = listTag.getCompoundOrEmpty(i);
                if (tag.isEmpty())
                    continue;
                ContainerSnapshot snap = tagToSnapshot(tag, ops);
                if (snap != null) {
                    ChestNetworkManager.get().put(snap);
                    count++;
                }
            }
            OmniChest.LOGGER.info("Loaded {} chest snapshots from {}", count, file);
        } catch (Exception e) {
            OmniChest.LOGGER.warn("Failed to load chest cache from {}", file, e);
        } finally {
            loaded = true;
        }
    }

    /**
     * 現在のセッション用のキャッシュファイルパスを決定する。
     * シングルプレイ: ワールド名で分離。マルチプレイ: サーバ IP で分離。
     */
    private static Path currentCacheFile() {
        Minecraft mc = Minecraft.getInstance();
        Path base = FabricLoader.getInstance().getConfigDir()
                .resolve(OmniChest.MOD_ID).resolve("chests");
        IntegratedServer ss = mc.getSingleplayerServer();
        if (ss != null) {
            String levelName = ss.getWorldData().getLevelName();
            return base.resolve("world_" + sanitize(levelName) + ".nbt");
        }
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            return base.resolve("server_" + sanitize(server.ip) + ".nbt");
        }
        return null;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ════════════════════════════════════════════════════════════════════
    // シリアライズ
    // ════════════════════════════════════════════════════════════════════

    private static CompoundTag snapshotToTag(ContainerSnapshot snap, DynamicOps<Tag> ops) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", snap.dimension().identifier().toString());
        t.putInt("x", snap.pos().getX());
        t.putInt("y", snap.pos().getY());
        t.putInt("z", snap.pos().getZ());
        if (snap.secondaryPos() != null) {
            t.putInt("sx", snap.secondaryPos().getX());
            t.putInt("sy", snap.secondaryPos().getY());
            t.putInt("sz", snap.secondaryPos().getZ());
        }
        t.putString("type", snap.type().name());
        t.putLong("lastSeen", snap.lastSeenMillis());
        // エンティティコンテナ: 同一性は UUID。 networkId はセッション内ハンドルなので保存しない
        // (= ロード時はセンチネルで未解決状態にし、 実体再ロードで再アンカーする)。
        // pos は捕捉時 (= 最終既知) 座標で、 上の x/y/z にそのまま入る (= 一覧復元の表示位置)。
        if (snap.isEntity() && snap.entity() != null) {
            t.putString("entityUuid", snap.entity().uuid().toString());
        }

        ListTag items = new ListTag();
        for (int i = 0; i < snap.items().size(); i++) {
            ItemStack stack = snap.items().get(i);
            if (stack.isEmpty())
                continue;
            Optional<Tag> stackTagOpt = ItemStack.CODEC.encodeStart(ops, stack).result();
            if (stackTagOpt.isEmpty())
                continue;
            CompoundTag itemTag = new CompoundTag();
            itemTag.putInt("slot", i);
            itemTag.put("stack", stackTagOpt.get());
            items.add(itemTag);
        }
        t.put("items", items);
        return t;
    }

    private static ContainerSnapshot tagToSnapshot(CompoundTag t, DynamicOps<Tag> ops) {
        String dimStr = t.getString("dim").orElse(null);
        if (dimStr == null || dimStr.isEmpty())
            return null;
        Identifier dimId = Identifier.tryParse(dimStr);
        if (dimId == null)
            return null;
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);

        BlockPos pos = new BlockPos(
                t.getInt("x").orElse(0),
                t.getInt("y").orElse(0),
                t.getInt("z").orElse(0));
        BlockPos secondaryPos = null;
        if (t.getInt("sx").isPresent()) {
            secondaryPos = new BlockPos(
                    t.getInt("sx").orElse(0),
                    t.getInt("sy").orElse(0),
                    t.getInt("sz").orElse(0));
        }

        String typeStr = t.getString("type").orElse("OTHER");
        ContainerType type;
        try {
            type = ContainerType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            type = ContainerType.OTHER;
        }

        long lastSeen = t.getLong("lastSeen").orElse(System.currentTimeMillis());

        ListTag itemsList = t.getList("items").orElse(new ListTag());
        int maxSlot = -1;
        for (int i = 0; i < itemsList.size(); i++) {
            CompoundTag c = itemsList.getCompoundOrEmpty(i);
            if (c.isEmpty())
                continue;
            int slot = c.getInt("slot").orElse(-1);
            if (slot > maxSlot)
                maxSlot = slot;
        }

        int defaultSize = switch (type) {
            case DOUBLE_CHEST, DOUBLE_TRAPPED_CHEST -> 54;
            default -> 27;
        };
        int size = Math.max(defaultSize, maxSlot + 1);

        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            items.add(ItemStack.EMPTY);

        for (int i = 0; i < itemsList.size(); i++) {
            CompoundTag itemTag = itemsList.getCompoundOrEmpty(i);
            if (itemTag.isEmpty())
                continue;
            int slot = itemTag.getInt("slot").orElse(-1);
            Tag stackTag = itemTag.get("stack");
            if (stackTag == null || slot < 0 || slot >= size)
                continue;
            ItemStack stack = ItemStack.CODEC.parse(ops, stackTag).result().orElse(ItemStack.EMPTY);
            items.set(slot, stack);
        }

        // エンティティコンテナ (v2): UUID を持つ場合は EntityLocator 付きで復元する。
        // networkId はまだ不明なのでセンチネル (= 未解決)。 実体が再ロードされた時に
        // ContainerScanner の ENTITY_LOAD が UUID 一致で networkId へ再アンカーする。
        // UUID が壊れていれば そのエントリは安全にスキップ (= クラッシュさせない)。
        String entityUuidStr = t.getString("entityUuid").orElse(null);
        if (entityUuidStr != null && !entityUuidStr.isEmpty()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(entityUuidStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
            return new ContainerSnapshot(dim, pos, null, type, items, lastSeen,
                    EntityLocator.unresolved(uuid));
        }

        return new ContainerSnapshot(dim, pos, secondaryPos, type, items, lastSeen);
    }
}
