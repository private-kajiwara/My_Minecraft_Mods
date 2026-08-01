package com.kajiwara.hyperslice.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.kajiwara.hyperslice.core.HyperEntityRecord;
import com.kajiwara.hyperslice.core.SliceRegistry;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバ → クライアントの 4 次元エンティティ同期パケット。
 *
 * <p>v0.2 は<b>差分なしの全送信</b> (該当プレイヤーに見えるものを毎 tick まるごと)。
 * レコードは 1 件あたり 40 バイト程度で、 M1 は 1 体・実用時も数十体なので帯域は問題にならない。
 * 単純さを優先し、 必要になってから差分化する。
 *
 * <p>送るのは観測面と<b>交差しているものだけ</b>。 交差していないものは断面半径が 0 =
 * 描画対象が存在しないので、 そもそも送らない (「隣スライスのゴースト表示」は不要)。
 *
 * <p>w 方向の厚みは型から一意に決まるのでパケットには含めない (型序数だけ送る)。
 */
public record HyperEntitySyncPayload(List<Entry> entries) implements CustomPacketPayload {

    /** 1 体分。 dw と断面半径はクライアント側が自分の観測面から算出する。 */
    public record Entry(UUID id, int typeOrdinal, double x, double y, double z, double w) {

        public static Entry of(HyperEntityRecord r) {
            return new Entry(r.id(), r.type().ordinal(),
                    r.position().x(), r.position().y(), r.position().z(), r.position().w());
        }
    }

    public static final Type<HyperEntitySyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SliceRegistry.NAMESPACE, "entity_sync"));

    /**
     * 1 件分の codec。
     *
     * <p>{@code StreamCodec.composite} は引数が多いと読みにくいので、
     * プリミティブ codec に委譲する明示的な encode/decode で書く。
     */
    private static final StreamCodec<ByteBuf, Entry> ENTRY_CODEC = StreamCodec.of(
            (buf, e) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, e.id());
                ByteBufCodecs.VAR_INT.encode(buf, e.typeOrdinal());
                ByteBufCodecs.DOUBLE.encode(buf, e.x());
                ByteBufCodecs.DOUBLE.encode(buf, e.y());
                ByteBufCodecs.DOUBLE.encode(buf, e.z());
                ByteBufCodecs.DOUBLE.encode(buf, e.w());
            },
            buf -> new Entry(
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf)));

    /**
     * リスト部分の codec。
     *
     * <p>型を {@code List<Entry>} と明示しているのは、 {@code ArrayList::new} から
     * 要素型を {@code ArrayList<Entry>} と推論されると、 下の {@code map} に渡す
     * {@code entries()} ({@code List<Entry>} を返す) と食い違うため。
     */
    private static final StreamCodec<ByteBuf, List<Entry>> LIST_CODEC =
            ByteBufCodecs.collection(ArrayList::new, ENTRY_CODEC);

    /** ペイロード全体の codec。 {@code RegistryFriendlyByteBuf} は {@code ByteBuf} の派生。 */
    public static final StreamCodec<ByteBuf, HyperEntitySyncPayload> STREAM_CODEC =
            LIST_CODEC.map(HyperEntitySyncPayload::new, HyperEntitySyncPayload::entries);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
