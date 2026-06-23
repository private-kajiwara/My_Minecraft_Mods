package com.kajiwara.omnichest.search;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 「コンテナを持つエンティティ」 (= チェスト付きトロッコ / チェスト付きボート / チェストを積んだモブ)
 * を、 ブロックの {@link BlockPos} に相当する <b>同一性 + 位置の解決手段</b> として表す不変ハンドル。
 *
 * <p>
 * <b>設計意図</b> (= ブロックコンテナとの差分を最小化する):
 * <ul>
 * <li>ブロックコンテナは {@code (dimension, BlockPos)} で一意かつ位置固定だが、 エンティティは
 *     移動する。 そこで「同一性 = {@link #uuid}」 と「毎フレームの現在位置解決 = {@link #networkId}
 *     による {@code level.getEntity(int)}」 を分離して保持する。</li>
 * <li>{@link ContainerSnapshot.Key} の等価判定はエンティティ snapshot では {@code uuid} を採用する
 *     (= 移動して再キャプチャされても重複エントリにならない)。</li>
 * <li>{@code networkId} はクライアントが現在ロード中のエンティティを引くための高速ハンドル。
 *     再ログインで変わるためセッション内専用 (= 永続化しない)。</li>
 * </ul>
 *
 * <p>
 * <b>クライアント完結</b>: 解決は {@link ClientLevel#getEntity(int)} の読み取りのみで、 サーバへの
 * パケット送信やワールド変更は一切しない (= 既存のコンテナ収集方針と同じ「正規に読める情報のみ」)。
 */
public final class EntityLocator {

    /**
     * 未解決を表すセンチネル networkId。 永続化からの復元直後など、 まだ実体の networkId が
     * 分からない状態に使う。 {@link net.minecraft.client.multiplayer.ClientLevel#getEntity(int)} は
     * この値で常に {@code null} を返すため、 {@link #resolve} は安全に「未解決」となる。
     * 実体が再ロードされた時 (= ContainerScanner の ENTITY_LOAD) に実 networkId へ再アンカーする。
     */
    public static final int UNRESOLVED_NETWORK_ID = Integer.MIN_VALUE;

    private final UUID uuid;
    private final int networkId;

    public EntityLocator(UUID uuid, int networkId) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.networkId = networkId;
    }

    /** このロケータを作るユーティリティ (= {@link Entity#getUUID()} / {@link Entity#getId()} から)。 */
    public static EntityLocator of(Entity entity) {
        return new EntityLocator(entity.getUUID(), entity.getId());
    }

    /**
     * UUID のみ既知で networkId 未確定 (= 永続化からの復元直後) のロケータを作る。
     * {@link #resolve} は実体が再ロードされ再アンカーされるまで {@code null} を返す。
     */
    public static EntityLocator unresolved(UUID uuid) {
        return new EntityLocator(uuid, UNRESOLVED_NETWORK_ID);
    }

    public UUID uuid() {
        return uuid;
    }

    public int networkId() {
        return networkId;
    }

    /**
     * 現在ロード中の実エンティティを解決する。 未ロード / 消滅で引けない場合は {@code null}。
     *
     * <p>
     * {@code networkId} 経由 ({@link ClientLevel#getEntity(int)}) で引き、 取れた場合のみ UUID 一致を
     * 念のため確認する (= ローカルで networkId が別エンティティに再利用された稀なケースを弾く)。
     */
    @Nullable
    public Entity resolve(@Nullable ClientLevel level) {
        if (level == null) {
            return null;
        }
        Entity e = level.getEntity(networkId);
        if (e == null) {
            return null;
        }
        if (!uuid.equals(e.getUUID())) {
            return null;
        }
        return e;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EntityLocator other)) {
            return false;
        }
        // 同一性は UUID のみ (= networkId はセッション内ハンドルなので等価判定に含めない)。
        return uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return "EntityLocator[uuid=" + uuid + ", networkId=" + networkId + "]";
    }
}
