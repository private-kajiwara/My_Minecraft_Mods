package com.kajiwara.visualizegate.server;

import com.kajiwara.visualizegate.VisualizeGateMod;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * ㉗B サーバー側エントリポイント (Fabric {@code "server"} entrypoint = {@link DedicatedServerModInitializer}・
 * <b>専用サーバー限定</b>で走る)。 environment "*" 化により dedicated server でもロードされる。
 * <b>client 型を一切 import しない</b> (server/ パッケージ境界規約)。
 *
 * <p>役割: 読み込まれたチャンクを {@link ServerTerrainTiles} で捕捉し、 ワールドセーブ配下へタイル永続。
 * クライアントへの配信 (S2C) はフェーズ3。 SP (内部サーバー) の表示はクライアント側タイルストアを使うため、
 * フェーズ2 ではサーバー捕捉は専用サーバーの永続基盤に限ってよい (dedicated-only で十分)。
 *
 * <p><b>版差</b>: {@code ServerChunkEvents.Load#onChunkLoad} は ≥26.1 が 3 引数 {@code (level,chunk,newChunk)}、
 * ≤1.21.11 が 2 引数 {@code (level,chunk)} (javap 確認)。 Stonecutter {@code //?} でラムダ arity を出し分ける。
 */
public final class VisualizeGateServer implements DedicatedServerModInitializer {

    private static final ServerTerrainTiles TILES = new ServerTerrainTiles();

    @Override
    public void onInitializeServer() {
        //? if >=26.1 {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newChunk) -> TILES.onChunkLoad(level, chunk));
        //?} else {
        /*ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> TILES.onChunkLoad(level, chunk));*/
        //?}
        ServerLifecycleEvents.SERVER_STARTED.register(TILES::loadExisting);   // 既存タイルをロード (再起動後も累積)
        ServerTickEvents.END_SERVER_TICK.register(TILES::onServerTick);       // スロットル捕捉 (時間予算でドレイン)
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> TILES.save());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TILES.clear());
        VisualizeGateMod.LOGGER.info("VisualizeGate server initialized (terrain tile capture + per-world persist).");
    }
}
