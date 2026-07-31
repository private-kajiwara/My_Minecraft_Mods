package com.kajiwara.hyperslice.bstep;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.kajiwara.hyperslice.net.WInputPayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>【方式B 中核】</b> w を動かすキー入力の受け取り (サーバ側・権威)。
 *
 * <h2>なぜサーバ権威か</h2>
 * 方式B では w は<b>世界の状態</b>である (地形がその w で切り直される)。 クライアントが
 * 権威を持つと、 地形の w とエンティティの観測面が別々に動いて不整合になる。
 * 診断実験 {@code ObserverW} が「クライアント権威で良い」としていたのは、 地形が整数
 * スライスに固定されたままエンティティだけが連続 w に反応する矛盾を承知で許容していた
 * ためで、 本実装ではその前提が消える。
 *
 * <h2>w は世界のものなのでプレイヤー単位では持たない</h2>
 * 保持するのは<b>プレイヤーの意思表示 (向き)</b> だけで、 w 自体は
 * {@link BStepSession} がレベルごとに 1 つ持つ。 同一ディメンションに複数人いれば
 * 向きの<b>符号の和</b>が採られる (逆向きに押し合えば止まる)。 速さを人数倍にしないのは、
 * 「押している人数」が w 移動速度という設計値に混ざらないようにするため。
 *
 * <h2>入力に期限がある理由</h2>
 * {@code direction != 0} は「押され続けている」と解釈して毎ティック w を進めるので、
 * 離した通知が届かない状況で w が走り続ける危険がある。 受信ティックを記録し
 * {@link BStepExperiment#INPUT_EXPIRY_TICKS} 経過した入力は<b>無効</b>として扱う
 * (クライアントは押している間ずっと送ってくる)。
 */
public final class WDriveInput {

    /** 直近の入力。 サーバースレッドからのみ触る (ハンドラもサーバースレッドで走る)。 */
    private static final Map<UUID, Input> INPUTS = new HashMap<>();

    /** 1 プレイヤーの意思表示と、 それが届いたティック。 */
    private record Input(int direction, int tick) {
    }

    private WDriveInput() {
    }

    /**
     * 受信ハンドラの登録。 {@code HyperSlice.onInitialize} からフラグ判定つきで 1 回だけ呼ぶ。
     *
     * <p>ハンドラが<b>サーバースレッドで走る</b>ことは実測済み:
     * {@code AbstractChanneledNetworkAddon.handle} が {@code isOnReceiveThread()}
     * ({@code MinecraftServer.packetProcessor().isSameThread()}) を見て偽なら
     * {@code ServerPlayNetworkAddon.schedule} = {@code MinecraftServer.execute(Runnable)}
     * へ回す (fabric-networking-api-v1 6.3.1 を逆アセンブルして確認)。
     * したがってここから {@link #INPUTS} を触ってよい。
     */
    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(WInputPayload.TYPE,
                (payload, context) -> receive(context.server(), context.player(), payload));
    }

    private static void receive(MinecraftServer server, ServerPlayer player,
                                WInputPayload payload) {
        int direction = payload.clampedDirection();
        UUID id = player.getUUID();
        if (direction == 0) {
            // 離した速報。 期限切れを待たずに即止める。
            INPUTS.remove(id);
            return;
        }
        INPUTS.put(id, new Input(direction, server.getTickCount()));
    }

    /** 誰も w を動かそうとしていないか (毎ティックの全レベル走査を省くための早期判定)。 */
    public static boolean isIdle() {
        return INPUTS.isEmpty();
    }

    /**
     * そのレベルの今の向き ({@code -1} / {@code 0} / {@code +1})。
     *
     * <p>そのレベルに<b>実際にいるプレイヤー</b>の入力だけを見る。 ディメンションを
     * 移った直後の入力が前のディメンションの w を動かし続けることはない。
     */
    public static int directionOf(ServerLevel level) {
        if (INPUTS.isEmpty()) {
            return 0;
        }
        int now = level.getServer().getTickCount();
        int sum = 0;
        for (ServerPlayer player : level.players()) {
            Input input = INPUTS.get(player.getUUID());
            if (input == null) {
                continue;
            }
            if (now - input.tick() > BStepExperiment.INPUT_EXPIRY_TICKS) {
                continue;
            }
            sum += input.direction();
        }
        // 人数で速くならないように符号だけを採る。
        return Integer.signum(sum);
    }

    /** 期限切れの入力を捨てる (毎ティック 1 回・{@link BStepSession} から呼ぶ)。 */
    public static void expire(MinecraftServer server) {
        if (INPUTS.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        INPUTS.entrySet().removeIf(e ->
                now - e.getValue().tick() > BStepExperiment.INPUT_EXPIRY_TICKS
                        || server.getPlayerList().getPlayer(e.getKey()) == null);
    }

    /** サーバー停止時に捨てる。 */
    static void clear() {
        INPUTS.clear();
    }
}
