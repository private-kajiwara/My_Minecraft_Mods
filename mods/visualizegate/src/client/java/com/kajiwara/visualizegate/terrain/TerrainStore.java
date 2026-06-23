package com.kajiwara.visualizegate.terrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.state.PointCloudViewState;
import com.kajiwara.visualizegate.tile.Tile;
import com.kajiwara.visualizegate.tile.TileIo;
import com.kajiwara.visualizegate.tile.TileKey;
import com.kajiwara.visualizegate.tile.TileStore;
import com.kajiwara.visualizegate.tile.VoxelKey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 地形カラム代表点の蓄積と永続化 (点群ポップアップの地形素材)。
 *
 * <p>㉗ で<b>タイル化</b>: 旧来のグローバル {@code CAP_PER_DIM} (1.5M・first-come-drop) を廃止し、
 * 共有の {@link TileStore} (common・<b>タイル別上限 × 無制限タイル数</b>) へ移行した。 これで探索面積に応じて
 * 在庫が伸び続け、 <b>歩いた所が全部残る</b> (CAP 頭打ちの解消)。 表示点数は GPU detail 予算で別途有界。
 *
 * <p>永続は worldId 別に {@code configDir/visualizegate/tiles/<worldId>/<dimId>/<tileX>_<tileZ>.bin}
 * (タイル群・バイナリ {@link TileIo})。 旧単一 {@code visualizegate-terrain.json} は<b>一度だけ破棄</b>して
 * タイルへ再構築する (㉒B の絶対座標化と同じ「今回一度だけ破棄」前例)。
 *
 * <p>サンプリングは side 非依存の {@link TerrainSampler} (src/main へ移動済) を呼ぶ。 ネザー帯はクライアント
 * では従来どおりプレイヤー Y を中心に取る。 {@code snapshotColumns} は全ロード済みタイルを集約し従来と同じ
 * flat {@code int[]} を返す (= 表示側 API 不変)。
 */
public final class TerrainStore {

    private static final String LEGACY_FILE_NAME = "visualizegate-terrain.json";
    private static final String TILES_DIR = "tiles";
    /** ネザー帯走査のプレイヤー Y からの上下幅。 */
    private static final int NETHER_BAND_UP = 24;
    private static final int NETHER_BAND_DOWN = 40;

    private static final TerrainStore INSTANCE = new TerrainStore();

    /** worldId → タイルストア (common・グローバル CAP 無し・タイル数無制限)。 */
    private final Map<String, TileStore> stores = new HashMap<>();
    /** ensureLoaded を worldId ごとに一度だけ行う印。 */
    private final Set<String> loadedWorlds = new HashSet<>();
    /** worldId → dimId → 2D キー(4ブロックセル) → 最大観測 Y (surfaceYAt 用・非永続・ロード/put で更新)。 */
    private final Map<String, Map<String, Map<Long, Integer>>> surf = new HashMap<>();
    private boolean legacyPurged = false;

    private TerrainStore() {
    }

    public static TerrainStore get() {
        return INSTANCE;
    }

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> INSTANCE.onChunkLoad(level, chunk));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.onLeave());
    }

    // ── 蓄積 ────────────────────────────────────────────────────────────

    private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        try {
            // v1 gate: 点群データ収集が OFF の間は新規キャプチャを一切行わない (= ディスク蓄積ゼロ)。
            // 既存タイルのロード/表示はクエリ側 (snapshot*/surfaceYAt) が独立に ensureLoaded するので影響しない。
            if (!PointCloudViewState.isCaptureEnabled()) {
                return;
            }
            String worldId = PortalMemory.get().currentWorldId();
            if (worldId == null) {
                return; // world-id 未確定 (PortalMemory が JOIN/CHUNK_LOAD で先に確定する)
            }
            ensureLoaded(worldId);
            sampleAndStore(level, chunk, worldId);
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] terrain sample failed (continuing): {}", t.toString());
        }
    }

    /** 1 チャンクを現 stride でサンプルしストアへ反映 (chunk-load と ㉑ Re-analyze 再採取で共用)。 */
    private void sampleAndStore(ClientLevel level, LevelChunk chunk, String worldId) {
        String dimId = level.dimension().identifier().toString();
        PortalDimension dim = PortalMemory.dimOf(dimId);
        boolean hasCeiling = dim == PortalDimension.NETHER;

        int bandTop = TerrainSampler.NETHER_MAX_Y;
        int bandBot = TerrainSampler.NETHER_MIN_Y;
        if (hasCeiling) {
            LocalPlayer player = Minecraft.getInstance().player;
            int py = (player != null) ? (int) Math.floor(player.getY()) : 64;
            bandTop = Math.min(TerrainSampler.NETHER_MAX_Y, py + NETHER_BAND_UP);
            bandBot = Math.max(TerrainSampler.NETHER_MIN_Y, py - NETHER_BAND_DOWN);
        }

        TileStore store = store(worldId);
        Map<Long, Integer> surfGrid = surfGrid(worldId, dimId);
        TerrainSampler.sampleChunk(level, chunk, hasCeiling, bandTop, bandBot, (wx, wz, y, color) -> {
            store.put(dimId, wx, wz, y, color); // ㉗ タイル別上限のみ・グローバル CAP 無し
            long s2 = surfKey(wx, wz);
            Integer cur = surfGrid.get(s2);
            if (cur == null || y > cur) {
                surfGrid.put(s2, y);
            }
        });
    }

    /**
     * ㉑ 現在ロード中のチャンク (中心 ±{@code radiusChunks}) を現 stride で再採取＝Re-analyze 直後に見ている
     * 範囲がすぐ濃くなる。 メインスレッドで呼ぶこと。 render distance 外は再ロード時に更新 (正直な挙動)。
     */
    public void resampleLoaded(ClientLevel level, int centerBlockX, int centerBlockZ, int radiusChunks) {
        try {
            // v1 gate: 収集 OFF の間は Re-analyze の再採取 (新規キャプチャ) もしない。 既存タイルの表示は
            // snapshotColumns 側 (ungated) が担うので、 OFF でも過去に集めた地形は点群に出る。
            if (!PointCloudViewState.isCaptureEnabled()) {
                return;
            }
            String worldId = PortalMemory.get().currentWorldId();
            if (worldId == null) {
                return;
            }
            ensureLoaded(worldId);
            int ccx = centerBlockX >> 4;
            int ccz = centerBlockZ >> 4;
            int done = 0;
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    int cx = ccx + dx;
                    int cz = ccz + dz;
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }
                    net.minecraft.world.level.chunk.ChunkAccess ca = level.getChunk(cx, cz,
                            net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                    if (ca instanceof LevelChunk lc) {
                        sampleAndStore(level, lc, worldId);
                        done++;
                    }
                }
            }
            VisualizeGateMod.LOGGER.info("[visualizegate] terrain resampled {} loaded chunks (stride {})",
                    done, TerrainSampler.STRIDE);
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] terrain resample failed (continuing): {}", t.toString());
        }
    }

    private void onLeave() {
        try {
            saveAllDirty();
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] terrain save-on-leave failed: {}", t.toString());
        }
    }

    // ── クエリ (スナップショット用・不変コピー) ───────────────────────────

    /**
     * 現 world の指定ディメンションの全タイルを集約し flat int[] (wx, wz, y, color の 4 つ組連結) で返す。
     * 解析押下時にメインスレッドで呼び、 ワーカーへ渡す不変コピー。 無ければ空配列。
     */
    public int[] snapshotColumns(PortalDimension dim) {
        String worldId = PortalMemory.get().currentWorldId();
        if (worldId == null) {
            return new int[0];
        }
        ensureLoaded(worldId);
        String dimId = PortalMemory.canonicalDimId(dim);
        if (dimId == null) {
            return new int[0];
        }
        return store(worldId).snapshot(dimId);
    }

    /**
     * ㊽ 中心 (px,pz) ±{@code radius} ブロックの局所カラムだけを flat int[] で返す (ドックのライブ局所レーダー用)。
     * 半径を覆うタイルのみ走査＝有界・低コスト (~3Hz で呼んでよい)。 メイン (レンダー) スレッドで呼ぶこと。
     */
    public int[] snapshotColumnsLocal(PortalDimension dim, int px, int pz, int radius) {
        String worldId = PortalMemory.get().currentWorldId();
        if (worldId == null) {
            return new int[0];
        }
        ensureLoaded(worldId);
        String dimId = PortalMemory.canonicalDimId(dim);
        if (dimId == null) {
            return new int[0];
        }
        return store(worldId).snapshotLocal(dimId, px, pz, radius);
    }

    /**
     * 指定ディメンションの blockX/blockZ が属する 4 ブロックセルの観測サーフェス代表 Y を返す (現 world)。
     * 未観測なら空 (=「向こう未探索」判定に使う)。 HUD/カード用の軽量クエリ。
     */
    public java.util.OptionalInt surfaceYAt(PortalDimension dim, int blockX, int blockZ) {
        String worldId = PortalMemory.get().currentWorldId();
        if (worldId == null) {
            return java.util.OptionalInt.empty();
        }
        ensureLoaded(worldId);
        String dimId = PortalMemory.canonicalDimId(dim);
        if (dimId == null) {
            return java.util.OptionalInt.empty();
        }
        Map<String, Map<Long, Integer>> byDim = surf.get(worldId);
        if (byDim == null) {
            return java.util.OptionalInt.empty();
        }
        Map<Long, Integer> sGrid = byDim.get(dimId);
        if (sGrid == null) {
            return java.util.OptionalInt.empty();
        }
        Integer y = sGrid.get(surfKey(blockX, blockZ));
        return (y == null) ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(y);
    }

    private TileStore store(String worldId) {
        return stores.computeIfAbsent(worldId, k -> new TileStore());
    }

    private Map<Long, Integer> surfGrid(String worldId, String dimId) {
        return surf.computeIfAbsent(worldId, k -> new HashMap<>())
                .computeIfAbsent(dimId, k -> new HashMap<>());
    }

    // ── 永続化 (タイル群・バイナリ) ───────────────────────────────────────

    /** worldId のタイル群を初回のみ全ロード (= 旧 json の全ロードと等価＝過去探索が全部見える) ＋ surf 再構築。 */
    private void ensureLoaded(String worldId) {
        purgeLegacyOnce();
        if (loadedWorlds.contains(worldId)) {
            return;
        }
        loadedWorlds.add(worldId);
        TileStore store = store(worldId);
        int n = TileIo.loadAll(baseDir(worldId), store);
        rebuildSurf(worldId, store);
        if (n > 0) {
            VisualizeGateMod.LOGGER.info("[visualizegate] terrain loaded {} tiles for world {}", n, worldId);
        }
    }

    /** ロード済みタイル群から surf (2D 最大 Y) を再構築。 */
    private void rebuildSurf(String worldId, TileStore store) {
        for (TileKey k : store.allTileKeys()) {
            Tile t = store.getTile(k);
            if (t == null) {
                continue;
            }
            Map<Long, Integer> sGrid = surfGrid(worldId, k.dimId());
            for (Map.Entry<Long, Long> e : t.voxels().entrySet()) {
                long key = e.getKey();
                int wx = VoxelKey.x(key);
                int wz = VoxelKey.z(key);
                int y = VoxelKey.valY(e.getValue());
                long s2 = surfKey(wx, wz);
                Integer cur = sGrid.get(s2);
                if (cur == null || y > cur) {
                    sGrid.put(s2, y);
                }
            }
        }
    }

    private void saveAllDirty() {
        for (Map.Entry<String, TileStore> e : stores.entrySet()) {
            try {
                TileIo.writeDirty(baseDir(e.getKey()), e.getValue());
            } catch (IOException ex) {
                VisualizeGateMod.LOGGER.warn("[visualizegate] terrain tile save failed for {}: {}",
                        e.getKey(), ex.toString());
            }
        }
    }

    /** 旧単一 json を一度だけ破棄 (タイルへ再構築する前例どおり・座標系/容量方針が変わったため)。 */
    private void purgeLegacyOnce() {
        if (legacyPurged) {
            return;
        }
        legacyPurged = true;
        try {
            Path legacy = FabricLoader.getInstance().getConfigDir().resolve(LEGACY_FILE_NAME);
            if (Files.exists(legacy)) {
                Files.deleteIfExists(legacy);
                Files.deleteIfExists(legacy.resolveSibling(LEGACY_FILE_NAME + ".tmp"));
                VisualizeGateMod.LOGGER.info(
                        "[visualizegate] legacy terrain json discarded once — rebuilding as tiles");
            }
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] legacy terrain purge failed (ignored): {}", t.toString());
        }
    }

    private static Path baseDir(String worldId) {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(VisualizeGateMod.MOD_ID).resolve(TILES_DIR).resolve(TileIo.sanitizeDim(worldId));
    }

    // ── 2D サーフェスキー (4 ブロックセル・surfaceYAt 用) ──────────────────

    private static long surfKey(int blockX, int blockZ) {
        long sx = blockX >> 2;
        long sz = blockZ >> 2;
        return (sx & 0xFFFFFFFFL) << 32 | (sz & 0xFFFFFFFFL);
    }
}
