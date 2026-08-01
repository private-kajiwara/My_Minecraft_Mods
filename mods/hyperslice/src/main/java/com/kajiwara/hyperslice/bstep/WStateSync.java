package com.kajiwara.hyperslice.bstep;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.kajiwara.hyperslice.net.WStatePayload;
import com.kajiwara.hyperslice.slice.LevelW;
import com.kajiwara.hyperslice.slice.SliceTeleporter;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * <b>【方式B 中核】</b> サーバの w を各プレイヤーへ配る。
 *
 * <p>クライアントはこれを受けて 4 次元エンティティの観測面と HUD に使う。 送るのは
 * <b>値が変わったときだけ</b> (ディメンション移動も変化として扱う) なので、
 * w を動かさない間は 1 パケットも飛ばない。
 *
 * <p>HyperSlice の外にいるプレイヤーには送らない。 クライアント側は
 * 「自分のレベルがスライスでなければ観測面は無い」と自力で判断できる
 * (ディメンション ID から分かる) ので、 わざわざ「無効」を送る必要がない。
 */
public final class WStateSync {

    /** 直近に送った値。 ディメンションが変われば同じ数値でも送り直す。 */
    private static final Map<UUID, Sent> LAST = new HashMap<>();

    private record Sent(ResourceKey<Level> dimension, double w) {
    }

    private WStateSync() {
    }

    /** {@link BStepSession} の END_SERVER_TICK から毎ティック呼ぶ。 */
    static void broadcast(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            UUID id = player.getUUID();

            if (!SliceTeleporter.isSlice(level)) {
                LAST.remove(id);
                continue;
            }

            double w = LevelW.terrainW(level);
            Sent last = LAST.get(id);
            if (last != null && last.dimension() == level.dimension()
                    && Double.compare(last.w(), w) == 0) {
                continue;
            }
            LAST.put(id, new Sent(level.dimension(), w));

            // チャンネルを持たないクライアント (バニラ等) には送らない。
            if (ServerPlayNetworking.canSend(player, WStatePayload.TYPE)) {
                ServerPlayNetworking.send(player, new WStatePayload(w));
            }
        }

        if (!LAST.isEmpty()) {
            LAST.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
    }

    /** サーバー停止時に捨てる。 */
    static void clear() {
        LAST.clear();
    }
}
