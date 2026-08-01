package com.kajiwara.hyperslice;

import com.kajiwara.hyperslice.bstep.BStepExperiment;
import com.kajiwara.hyperslice.bstep.ChunkW;
import com.kajiwara.hyperslice.bstep.WDriveInput;
import com.kajiwara.hyperslice.command.HyperSliceCommands;
import com.kajiwara.hyperslice.core.SliceRegistry;
import com.kajiwara.hyperslice.entity.HyperEntityService;
import com.kajiwara.hyperslice.net.HyperEntitySyncPayload;
import com.kajiwara.hyperslice.net.WInputPayload;
import com.kajiwara.hyperslice.net.WStatePayload;
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

        // 4 次元エンティティ層のパケット型。
        // バニラ Entity には一切載せない (エンティティ層を ServerLevel 非依存に保つため)。
        PayloadTypeRegistry.clientboundPlay().register(
                HyperEntitySyncPayload.TYPE, HyperEntitySyncPayload.STREAM_CODEC);

        // 【方式B 中核】w のサーバー権威。 型の登録はフラグに関係なく行う:
        // 登録はチャンネル一覧の広告に出るだけで挙動を変えないのに対し、 片側だけ
        // 登録されていない状態で送ると例外になるため、 条件付きにすると
        // 「クライアントは有効・サーバーは無効」の組み合わせで壊れる。
        PayloadTypeRegistry.clientboundPlay().register(
                WStatePayload.TYPE, WStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                WInputPayload.TYPE, WInputPayload.STREAM_CODEC);
        if (BStepExperiment.EXPERIMENT_ENABLED) {
            WDriveInput.register();
            // チャンクごとの w を持つ attachment 型。 ワールド (= チャンク) を読む前に
            // 登録しておく必要があるのでここ。 フラグが false なら登録自体が起きない
            // (= 既存ワールドのチャンク NBT に何も足さない)。
            ChunkW.register();
        }

        // ── tick の登録順に意味がある ──────────────────────────────
        //   Fabric の END_SERVER_TICK は登録順に呼ばれる。 w を進める側
        //   (HyperSliceCommands 経由で BStepSession) を先に登録することで、
        //   同じティックの中で「w が進む → その w で観測面を決めて配る」順になる。
        //   逆にすると 4 次元エンティティの断面が常に 1 ティックぶん古い w で
        //   計算され、 地形と観測面が僅かにずれ続ける。
        HyperSliceCommands.register();
        HyperEntityService.register();

        LOGGER.info("[{}] chunk generator registered as {}", MOD_ID, CHUNK_GENERATOR_ID);
    }
}
