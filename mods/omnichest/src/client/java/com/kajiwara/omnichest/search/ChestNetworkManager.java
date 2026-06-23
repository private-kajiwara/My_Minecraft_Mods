package com.kajiwara.omnichest.search;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * クライアントが「これまでに開いて中身を観測したコンテナ」を保持する中央レジストリ。
 *
 * <p>
 * 設計方針:
 * <ul>
 * <li>シングルトン (process 内に 1 個)。スレッドセーフな {@link ConcurrentHashMap} を採用。</li>
 * <li>キーは {@link ContainerSnapshot.Key} = (dimension, normalizedPos)。
 * ラージチェストでも左右どちらから引いても同じエントリにたどり着く。</li>
 * <li>「保存」「更新」「削除」「listener 通知」のシンプルな pub/sub のみを担い、
 * 「どこから情報を得るか」 (= イベント駆動の購読)、
 * 「どう検索するか」 (= インデックス)、
 * 「どう描画するか」 (= ハイライト) は、別クラスへ完全に分離する。</li>
 * <li>将来 {@code CacheStorage} 経由で永続化したい場合は、本クラスを変更せず
 * リスナとして自身を登録する設計でよい (今は in-memory のみ)。</li>
 * </ul>
 *
 * <p>
 * 仕様上の制約:
 * <ul>
 * <li>本クラスは「実際にプレイヤーが開いた」コンテナだけを保存する想定。
 * 未発見ブロックを勝手にスキャンしない (チート防止)。
 * 呼び出し側がそのルールを守ること。</li>
 * </ul>
 */
public final class ChestNetworkManager {

    private static final ChestNetworkManager INSTANCE = new ChestNetworkManager();

    /** Snapshot 保存用。 Key の dim & normalizedPos でユニーク。 */
    private final Map<ContainerSnapshot.Key, ContainerSnapshot> snapshots = new ConcurrentHashMap<>();

    /** スナップショット変更時に呼び出される listener (副インデックスの更新等)。 */
    private final List<Consumer<ChangeEvent>> listeners = new CopyOnWriteArrayList<>();

    private ChestNetworkManager() {
    }

    public static ChestNetworkManager get() {
        return INSTANCE;
    }

    // ────────────────────────────────────────────────────────────────────
    // 書き込み
    // ────────────────────────────────────────────────────────────────────

    /**
     * 新しいスナップショットを (もしあれば) 既存エントリへ上書きで保存する。
     * 同じキーの内容が「実質変化なし」と判定された場合でも、
     * lastSeenMillis は更新する。
     */
    public void put(ContainerSnapshot snapshot) {
        if (snapshot == null)
            return;
        ContainerSnapshot.Key key = snapshot.key();
        ContainerSnapshot prev = snapshots.put(key, snapshot);
        fire(new ChangeEvent(prev == null ? ChangeKind.ADDED : ChangeKind.UPDATED, key, prev, snapshot));
    }

    /** 指定キーのスナップショットを削除する。 */
    public void remove(ContainerSnapshot.Key key) {
        ContainerSnapshot prev = snapshots.remove(key);
        if (prev != null) {
            fire(new ChangeEvent(ChangeKind.REMOVED, key, prev, null));
        }
    }

    /** 全削除。 ClientPlayConnectionEvents.DISCONNECT 時等に呼ぶ。 */
    public void clear() {
        if (snapshots.isEmpty())
            return;
        snapshots.clear();
        fire(new ChangeEvent(ChangeKind.CLEARED, null, null, null));
    }

    /**
     * ItemStack のリストから新しいスナップショットを構築して put する便宜メソッド。
     * 呼び出し側 (ContainerScanner) が冗長コードを書かなくて済むようにラップしている。
     */
    public void capture(ResourceKey<Level> dimension,
            BlockPos pos,
            @Nullable BlockPos secondaryPos,
            ContainerType type,
            List<ItemStack> items,
            long lastSeenMillis) {
        put(new ContainerSnapshot(dimension, pos, secondaryPos, type, items, lastSeenMillis));
    }

    /**
     * コンテナを持つエンティティ (= トロッコ / ボート / モブ) のスナップショットを構築して put する。
     * ブロック版 {@link #capture} と同じ経路 (= {@link #put}) を通すため、 インデックス /
     * 検索 / ネスト / リスナ は<b>区別なく</b> エンティティ snapshot も扱える。
     *
     * @param pos    捕捉時のエンティティ {@code blockPosition()} (= 未解決時のフォールバック位置)
     * @param entity 同一性 (UUID) と毎フレーム位置解決 (networkId) を担うロケータ
     */
    public void captureEntity(ResourceKey<Level> dimension,
            BlockPos pos,
            ContainerType type,
            List<ItemStack> items,
            long lastSeenMillis,
            EntityLocator entity) {
        put(new ContainerSnapshot(dimension, pos, null, type, items, lastSeenMillis, entity));
    }

    // ────────────────────────────────────────────────────────────────────
    // 読み出し
    // ────────────────────────────────────────────────────────────────────

    /** 全てのスナップショットを (スナップショット時点のコピーで) 返す。 */
    public Collection<ContainerSnapshot> snapshots() {
        return new ArrayList<>(snapshots.values());
    }

    @Nullable
    public ContainerSnapshot get(ContainerSnapshot.Key key) {
        return snapshots.get(key);
    }

    public int size() {
        return snapshots.size();
    }

    // ────────────────────────────────────────────────────────────────────
    // listener
    // ────────────────────────────────────────────────────────────────────

    public void addListener(Consumer<ChangeEvent> listener) {
        listeners.add(listener);
    }

    private void fire(ChangeEvent event) {
        for (Consumer<ChangeEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (Throwable t) {
                // listener の例外で manager 側を巻き込まない。
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 通知 DTO
    // ────────────────────────────────────────────────────────────────────

    public enum ChangeKind {
        ADDED, UPDATED, REMOVED, CLEARED
    }

    public static record ChangeEvent(ChangeKind kind,
            @Nullable ContainerSnapshot.Key key,
            @Nullable ContainerSnapshot before,
            @Nullable ContainerSnapshot after) {
    }
}
