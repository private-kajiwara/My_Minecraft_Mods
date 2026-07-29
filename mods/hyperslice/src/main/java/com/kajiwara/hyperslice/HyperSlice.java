package com.kajiwara.hyperslice;

import com.kajiwara.hyperslice.command.HyperSliceCommands;
import com.kajiwara.hyperslice.core.SliceRegistry;
import com.kajiwara.hyperslice.entity.HyperEntityService;
import com.kajiwara.hyperslice.net.HyperEntitySyncPayload;
import com.kajiwara.hyperslice.worldgen.HyperSliceChunkGenerator;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 共通エントリポイント (Fabric {@code "main"} entrypoint)。
 *
 * <p>ディメンション生成はサーバー側の仕事なので、 チャンクジェネレータの Codec 登録と
 * コマンド登録はここで行う (クライアント単独起動でも内蔵サーバーが使うため
 * {@code "main"} が正しく、 {@code "server"} ではない)。
 *
 * <p>この Mod は既存 3 mod と違い {@code "environment": "*"} である
 * (worldgen とサーバーコマンドを持つため)。
 */
public class HyperSlice implements ModInitializer {

    public static final String MOD_ID = SliceRegistry.NAMESPACE;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** dimension JSON の {@code generator.type} に書く ID。 */
    public static final Identifier CHUNK_GENERATOR_ID =
            Identifier.fromNamespaceAndPath(MOD_ID, "slice");

    @Override
    public void onInitialize() {
        // ChunkGenerator の Codec をレジストリへ。 これが無いと dimension JSON の
        // "type": "hyperslice:slice" が解決できずワールド生成が失敗する。
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR,
                CHUNK_GENERATOR_ID, HyperSliceChunkGenerator.CODEC);

        // 4 次元エンティティ層: パケット型の登録と、 自前 tick の駆動。
        // バニラ Entity には一切載せない (エンティティ層を ServerLevel 非依存に保つため)。
        PayloadTypeRegistry.clientboundPlay().register(
                HyperEntitySyncPayload.TYPE, HyperEntitySyncPayload.STREAM_CODEC);
        HyperEntityService.register();

        HyperSliceCommands.register();

        LOGGER.info("[{}] chunk generator registered as {}", MOD_ID, CHUNK_GENERATOR_ID);
    }
}
