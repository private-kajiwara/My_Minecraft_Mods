package com.kajiwara.visualizegate.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.terrain.TerrainSampler;
import com.kajiwara.visualizegate.tile.Tile;
import com.kajiwara.visualizegate.tile.TileIo;
import com.kajiwara.visualizegate.tile.TileKey;
import com.kajiwara.visualizegate.tile.TileStore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/**
 * ㉗B サーバー側の地形タイル捕捉＋per-world 永続 (server-only・client 型を一切 import しない)。
 *
 * <p><b>スロットル捕捉</b>: {@code ServerChunkEvents.CHUNK_LOAD} はチャンクを<b>キューに積むだけ</b>で、
 * 実サンプリング ({@link TerrainSampler}・重い) は {@code END_SERVER_TICK} で<b>1 tick あたり時間予算
 * {@link #DRAIN_BUDGET_NANOS} 以内</b>に少しずつ処理する。 これで forceload や大量チャンク同時ロード時に
 * 1 tick がブロックして<b>サーバー Watchdog (60s) に落とされるのを防ぐ</b> (= 在世界の同期サンプリングは
 * バースト時にティックを食い尽くす)。 蓄積は共有 {@link TileStore} (common・グローバル CAP 無し・タイル別上限)。
 *
 * <p>保存先=<b>ワールドセーブ配下</b> {@code getWorldPath(ROOT)/visualizegate/tiles/<dimId>/<tileX>_<tileZ>.bin}
 * (worldId 不要＝per-world)。 クライアントへの S2C 配信はフェーズ3。
 */
public final class ServerTerrainTiles {

    private static final String NETHER_DIM = "minecraft:the_nether";
    /** 1 tick あたりのサンプリング時間予算 (ナノ秒)。 5ms＝Watchdog/TPS に十分余裕 (残りは次 tick へ繰越)。 */
    private static final long DRAIN_BUDGET_NANOS = 5_000_000L;
    /** キュー上限 (バースト保護)。 超過分の新規 enqueue はスキップ (再ロード時に再度載る)。 */
    private static final int MAX_QUEUE = 50_000;

    private record Pending(ServerLevel level, int cx, int cz) {
    }

    private final TileStore store = new TileStore();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private Path baseDir; // <worldSave>/visualizegate/tiles (初回チャンクロードで確定)

    /** CHUNK_LOAD: キューに積むだけ (server スレッド・軽量)。 */
    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        try {
            ensureBase(level.getServer());
            if (queue.size() >= MAX_QUEUE) {
                return; // バースト保護 (再ロードで再度載る)
            }
            ChunkPos cp = chunk.getPos();
            int cx = cp.getMinBlockX() >> 4; // ㉒ ChunkPos.x/z は 26.1 で private → getMinBlockX()>>4
            int cz = cp.getMinBlockZ() >> 4;
            String key = level.dimension().identifier().toString() + '|' + cx + '|' + cz;
            if (queued.add(key)) {
                queue.add(new Pending(level, cx, cz));
            }
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] server enqueue failed (continuing): {}", t.toString());
        }
    }

    /** END_SERVER_TICK: 時間予算内でキューを処理 (重いサンプリングをティック跨ぎに分散)。 */
    public void onServerTick(MinecraftServer server) {
        if (queue.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        do {
            Pending p = queue.poll();
            if (p == null) {
                break;
            }
            queued.remove(p.level().dimension().identifier().toString() + '|' + p.cx() + '|' + p.cz());
            sampleOne(p);
        } while (!queue.isEmpty() && System.nanoTime() - start < DRAIN_BUDGET_NANOS);
    }

    private void sampleOne(Pending p) {
        try {
            ServerLevel level = p.level();
            if (!level.hasChunk(p.cx(), p.cz())) {
                return; // もうアンロードされた
            }
            ChunkAccess ca = level.getChunk(p.cx(), p.cz(), ChunkStatus.FULL, false);
            if (!(ca instanceof LevelChunk lc)) {
                return;
            }
            String dimId = level.dimension().identifier().toString();
            boolean hasCeiling = dimId.equals(NETHER_DIM);
            TerrainSampler.sampleChunk(level, lc, hasCeiling,
                    TerrainSampler.NETHER_MAX_Y, TerrainSampler.NETHER_MIN_Y,
                    (wx, wz, y, color) -> store.put(dimId, wx, wz, y, color));
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] server terrain sample failed (continuing): {}",
                    t.toString());
        }
    }

    private void ensureBase(MinecraftServer server) {
        if (baseDir == null && server != null) {
            baseDir = server.getWorldPath(LevelResource.ROOT).resolve("visualizegate").resolve("tiles");
        }
    }

    /** 変更タイルだけを保存 (SERVER_STOPPING 等)。 残キューは破棄 (次回起動の再ロードで再採取)。 */
    public void save() {
        if (baseDir == null) {
            return;
        }
        try {
            // 未処理キューは保存しない (蓄積済み dirty タイルのみ)。 次回起動時に該当チャンクが再ロードされれば再採取。
            TileIo.writeDirty(baseDir, store);
            VisualizeGateMod.LOGGER.info("[visualizegate] server terrain saved (pending queue {})", queue.size());
        } catch (IOException ex) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] server tile save failed: {}", ex.toString());
        }
    }

    public void clear() {
        store.clear();
        queue.clear();
        queued.clear();
        baseDir = null;
    }

    // ── 起動時に既存タイルをロード (再起動後も累積) ──
    public void loadExisting(MinecraftServer server) {
        ensureBase(server);
        if (baseDir != null) {
            int n = TileIo.loadAll(baseDir, store);
            if (n > 0) {
                VisualizeGateMod.LOGGER.info("[visualizegate] server loaded {} existing tiles", n);
            }
        }
    }
}
