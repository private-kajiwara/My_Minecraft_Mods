package com.kajiwara.hyperslice.net;

import com.kajiwara.hyperslice.core.SliceRegistry;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバ → クライアント: <b>そのディメンションの今の w</b>。
 *
 * <h2>なぜ新しい型が要るか</h2>
 * 方式B では w は<b>世界の状態</b>であり、 サーバが権威を持つ。 クライアントは
 * 地形が今どの w で切られているかを知らないと、 4 次元エンティティの断面 (観測面) を
 * 地形と揃えられない。
 *
 * <p>既存の {@link HyperEntitySyncPayload} に相乗りさせていないのは、 あちらが
 * <b>「空のときは送信を止める」</b>設計 ({@code HyperEntityService.sentEmpty}) のため。
 * 相乗りさせると 4 次元エンティティが 0 体のときに w の配布も止まる。
 *
 * <p>送るのは<b>値が変わったときだけ</b> (ディメンションを移ったときも変化として扱う)。
 * 静止していれば無音なので、 w を動かさないプレイヤーには 1 パケットも飛ばない。
 */
public record WStatePayload(double w) implements CustomPacketPayload {

    public static final Type<WStatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SliceRegistry.NAMESPACE, "w_state"));

    public static final StreamCodec<ByteBuf, WStatePayload> STREAM_CODEC =
            ByteBufCodecs.DOUBLE.map(WStatePayload::new, WStatePayload::w);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
