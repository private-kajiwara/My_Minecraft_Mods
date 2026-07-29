package com.kajiwara.hyperslice.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.kajiwara.hyperslice.core.CrossSection;
import com.kajiwara.hyperslice.core.HyperEntityManager;
import com.kajiwara.hyperslice.core.HyperEntityRecord;
import com.kajiwara.hyperslice.core.HyperTerrainQuery;
import com.kajiwara.hyperslice.net.HyperEntitySyncPayload;
import com.kajiwara.hyperslice.slice.SliceTeleporter;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 4 次元エンティティ層のサーバ側の器。
 *
 * <p><b>設計の要点</b>: 実体 ({@link HyperEntityManager}) は common 側の純粋 Java で、
 * {@code ServerLevel} を一切参照しない。 このクラスはその周りの薄い殻でしかなく、
 * 「tick を駆動する」「プレイヤーごとに絞って送る」だけを担う。
 * 方式B (単一ディメンションでブロックを書き換える) へ移行しても、
 * 変更が要るのはこの殻と観測面の求め方だけで、 エンティティ層本体は無変更で通る。
 *
 * <p>4 次元エンティティはどの {@code ServerLevel} にも属さない (4 次元世界に 1 つ存在する)
 * ため、 マネージャは<b>サーバ全体で 1 個</b>。 バニラ Entity の tick には載せない。
 */
public final class HyperEntityService {

    // ─── 同期の調整用定数 ───────────────────────────────────────

    /** 同期する 3 次元距離の上限 [ブロック]。 */
    public static final double SYNC_RADIUS = 96.0;

    /**
     * w 交差判定に持たせる余裕。
     *
     * <p>厳密に交差したものだけを送ると、 ネットワーク遅延で到着が遅れたときに
     * 「本来は小さく現れるはずの球」がいきなり大きい状態で出現して見える。
     * 先回りして送っておくことで、 断面が 0 から立ち上がる様子を欠かさない。
     */
    public static final double SYNC_W_MARGIN = 1.0;

    /**
     * <b>【診断実験】</b> w による同期の絞り込みを行わない。
     *
     * <p>観測面 w の連続移動実験では、 観測面はクライアント権威で
     * ({@code ObserverW}) サーバはその値を知らない。 そのまま絞り込むと、
     * プレイヤーが w をずらした先の球が「送られてきていない」ために見えない。
     *
     * <p>そこで有効時は<b>水平半径内の全レコードを送る</b>。 新しいパケット型を
     * 足さずに済ませるための措置で、 テスト用エンティティは数体なので帯域は問題にならない。
     *
     * <p>この定数を {@code false} にすれば元の絞り込みに完全に戻る。
     */
    public static final boolean EXPERIMENT_NO_W_FILTER = true;

    // ────────────────────────────────────────────────────────

    private static final HyperEntityService INSTANCE = new HyperEntityService();

    /**
     * 地形照会は {@link HyperTerrainQuery#EMPTY} を注入している。
     *
     * <p>マイルストーン1 は地形衝突なしなので実際には引かれない。
     * マイルストーン2 で「改変差分ストア → 無ければ {@code HyperTerrain}」へ差し替える。
     * 差し替えはこの 1 行で済み、 common 側は無変更 (それがこの層を切った理由)。
     */
    private final HyperEntityManager manager = new HyperEntityManager(HyperTerrainQuery.EMPTY);

    /** 直近に「空のスナップショット」を送り終えたプレイヤー (空の連投を避けるため)。 */
    private final Set<UUID> sentEmpty = new HashSet<>();

    private HyperEntityService() {
    }

    public static HyperEntityService get() {
        return INSTANCE;
    }

    public HyperEntityManager manager() {
        return manager;
    }

    /** {@code HyperSlice.onInitialize} から 1 回だけ呼ぶ。 */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(INSTANCE::onServerTick);
    }

    // ── tick + 同期 ────────────────────────────────────────────

    private void onServerTick(MinecraftServer server) {
        manager.tick();
        broadcast(server);
    }

    /**
     * プレイヤーごとに、 見えるものだけを送る。
     *
     * <p>絞り込み自体は common 側 ({@link HyperEntityManager#visibleFrom}) にある純粋な計算で、
     * ここは「プレイヤーの観測面 w を求めて渡す」だけ。
     */
    private void broadcast(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();

            int slice = SliceTeleporter.sliceWOf(player.level());
            List<HyperEntityRecord> visible;
            if (slice < 0) {
                // HyperSlice の外にいるプレイヤーには 4 次元エンティティは存在しない。
                visible = List.of();
            } else {
                // 実験時は w マージンを実質無限にして、 水平半径内の全レコードを送る。
                double wMargin = EXPERIMENT_NO_W_FILTER ? Double.POSITIVE_INFINITY : SYNC_W_MARGIN;
                visible = manager.visibleFrom(
                        player.getX(), player.getY(), player.getZ(),
                        CrossSection.observationPlane(slice), SYNC_RADIUS, wMargin);
            }

            if (visible.isEmpty()) {
                // 空を送り続けない。 「直前も空」なら送信を省く (1 回だけ空を送って
                // クライアント側を空にし、 以降は無音)。 これが無いと 4 次元エンティティを
                // 一度も使わないプレイヤーにも毎 tick パケットが飛び続ける。
                if (!sentEmpty.add(playerId)) {
                    continue;
                }
            } else {
                sentEmpty.remove(playerId);
            }

            List<HyperEntitySyncPayload.Entry> entries = new ArrayList<>(visible.size());
            for (HyperEntityRecord r : visible) {
                entries.add(HyperEntitySyncPayload.Entry.of(r));
            }
            ServerPlayNetworking.send(player, new HyperEntitySyncPayload(entries));
        }

        // 切断したプレイヤーの痕跡を残さない。
        if (!sentEmpty.isEmpty()) {
            sentEmpty.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
    }
}
