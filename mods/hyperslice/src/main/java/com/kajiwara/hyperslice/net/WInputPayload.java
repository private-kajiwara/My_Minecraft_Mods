package com.kajiwara.hyperslice.net;

import com.kajiwara.hyperslice.core.SliceRegistry;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * クライアント → サーバ: <b>w を動かす向き</b> ({@code -1} / {@code 0} / {@code +1})。
 *
 * <h2>なぜ「向き」であって「量」ではないのか</h2>
 * 速さ ({@code BStepExperiment.W_RATE_PER_TICK}) はサーバが持つ。 クライアントに量を
 * 決めさせると、 改造クライアントが任意の速さで世界の w を動かせてしまうし、
 * 「w 移動速度の設計値」という最重要の摘みが 2 箇所に散る。 送るのは意思表示だけ。
 *
 * <h2>送り方 (押している間ずっと送る)</h2>
 * 押した瞬間 / 離した瞬間の<b>エッジだけ</b>を送る方式は採らない。 サーバは
 * {@code direction != 0} を「押され続けている」と解釈して毎ティック w を進めるので、
 * 離した通知が届かない状況 (画面遷移・切断・別 mod による入力奪取) で
 * <b>w が走り続ける</b>危険がある。
 *
 * <p>そこで押している間は毎クライアントティック送り、 サーバ側は受信から
 * {@code BStepExperiment.INPUT_EXPIRY_TICKS} 経過した入力を<b>自動的に無効</b>にする。
 * 離した瞬間に {@code 0} を 1 回送るのは、 期限切れを待たずに即止めるための速報。
 * ペイロードは向き 1 個だけなので 20 パケット/秒でも実質無視できる大きさ。
 */
public record WInputPayload(int direction) implements CustomPacketPayload {

    public static final Type<WInputPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SliceRegistry.NAMESPACE, "w_input"));

    public static final StreamCodec<ByteBuf, WInputPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(WInputPayload::new, WInputPayload::direction);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 受信値を {@code -1 / 0 / +1} に丸める (クライアントを信用しない)。 */
    public int clampedDirection() {
        return Integer.signum(direction);
    }
}
