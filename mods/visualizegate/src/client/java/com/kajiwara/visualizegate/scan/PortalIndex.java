package com.kajiwara.visualizegate.scan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.kajiwara.visualizegate.VisualizeGateMod;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/**
 * ディメンション別のポータルレコード保持と、 増分更新 (CHUNK_LOAD/UNLOAD) ＋ 定期 tick
 * (近傍再スキャン + 再検証) を担うクライアント側インデックス。
 *
 * <p><b>キー = 正規グローバル anchor</b>: 何度スキャンしても同一ポータルは 1 レコードに収束する。
 * insert 時に「包含 dedup」で stale な半分レコードを除去する (= 境界跨ぎの片側先行ロードで生じる
 * 一時的な分割枠を解消)。 近傍再スキャンが定期的に全成分を再 flood するため、 残存分も自己修復する。
 */
public final class PortalIndex {

    private static final PortalIndex INSTANCE = new PortalIndex();

    /** 近傍再スキャン半径 (チャンク)。 目の前で作ったポータルを数秒以内に拾うための範囲。 */
    private static final int NEAR_RADIUS_CHUNKS = 2;
    /** 定期 tick の間引き間隔 (tick)。 20 tick ≒ 1 秒。 */
    private static final int PERIODIC_INTERVAL = 20;

    private final Map<ResourceKey<Level>, Map<BlockPos, PortalRecord>> byDim = new HashMap<>();
    private long tickCounter = 0;

    private PortalIndex() {
    }

    public static PortalIndex get() {
        return INSTANCE;
    }

    /** イベント購読をまとめて登録する。 */
    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> INSTANCE.onChunkLoad(level, chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> INSTANCE.onChunkUnload(level, chunk));
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.clear());
    }

    /** レンダラ用: 指定ディメンションの現存レコードのスナップショット。 */
    public List<PortalRecord> recordsFor(ResourceKey<Level> dim) {
        Map<BlockPos, PortalRecord> dimMap = byDim.get(dim);
        if (dimMap == null || dimMap.isEmpty())
            return List.of();
        return new ArrayList<>(dimMap.values());
    }

    public void clear() {
        // ⑰ 診断: ディメンション往復で Links が消える件の切り分け用。 dim 移動でこれが出るなら DISCONNECT が
        // dim 変更でも発火している (ライブ index のみ消える＝点群 Links は PortalMemory から読むので本来不変)。
        VisualizeGateMod.LOGGER.info("[visualizegate] PortalIndex.clear() (DISCONNECT) — live index wiped");
        byDim.clear();
    }

    // ────────────────────────────────────────────────────────────────────
    // 増分更新
    // ────────────────────────────────────────────────────────────────────

    private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        try {
            for (PortalRecord rec : ClientPortalScanner.scanChunk(level, chunk,
                    PortalRecord.Provenance.CHUNK_LOAD, tickCounter)) {
                insert(rec);
            }
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] chunk-load scan failed (continuing): {}", t.toString());
        }
    }

    private void onChunkUnload(ClientLevel level, LevelChunk chunk) {
        Map<BlockPos, PortalRecord> dimMap = byDim.get(level.dimension());
        if (dimMap == null)
            return;
        ChunkPos cp = chunk.getPos();
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        int maxX = minX + 15, maxZ = minZ + 15;
        // anchor がアンロードされたチャンク列にあるレコードを失効させる (= 古い/半分の枠を残さない)。
        // 残った半分は次の近傍再スキャンでロード済み側から再構築される。
        dimMap.values().removeIf(r -> {
            BlockPos a = r.anchor();
            return a.getX() >= minX && a.getX() <= maxX && a.getZ() >= minZ && a.getZ() <= maxZ;
        });
    }

    private void onClientTick(Minecraft mc) {
        if (++tickCounter % PERIODIC_INTERVAL != 0)
            return;
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null)
            return;
        try {
            revalidateLoaded(level);
            nearRescan(level, player.blockPosition());
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] periodic tick failed (continuing): {}", t.toString());
        }
    }

    /** プレイヤー近傍 ±R チャンクをパレットスキップ付きで再スキャンし、 新規ポータルを拾う。 */
    private void nearRescan(ClientLevel level, BlockPos playerPos) {
        int pcx = playerPos.getX() >> 4;
        int pcz = playerPos.getZ() >> 4;
        for (int dx = -NEAR_RADIUS_CHUNKS; dx <= NEAR_RADIUS_CHUNKS; dx++) {
            for (int dz = -NEAR_RADIUS_CHUNKS; dz <= NEAR_RADIUS_CHUNKS; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (!level.hasChunk(cx, cz))
                    continue;
                ChunkAccess ca = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(ca instanceof LevelChunk lc))
                    continue;
                for (PortalRecord rec : ClientPortalScanner.scanChunk(level, lc,
                        PortalRecord.Provenance.NEAR_RESCAN, tickCounter)) {
                    insert(rec);
                }
            }
        }
    }

    /**
     * ロード済みチャンクにある既知レコードの anchor ブロックがまだ portal か確認し、 消えていれば失効させる。
     * anchor チャンクが未ロードのレコードは「分からない → 残す」 (ロード中で現存するものは expire させない)。
     */
    private void revalidateLoaded(ClientLevel level) {
        Map<BlockPos, PortalRecord> dimMap = byDim.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty())
            return;
        for (Iterator<Map.Entry<BlockPos, PortalRecord>> it = dimMap.entrySet().iterator(); it.hasNext();) {
            PortalRecord r = it.next().getValue();
            BlockPos a = r.anchor();
            if (!level.hasChunk(a.getX() >> 4, a.getZ() >> 4))
                continue; // 未ロード → 保持
            if (level.getBlockState(a).getBlock() != Blocks.NETHER_PORTAL)
                it.remove(); // ロード済みなのに消えている → 失効
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // insert + 包含 dedup
    // ────────────────────────────────────────────────────────────────────

    private void insert(PortalRecord rec) {
        Map<BlockPos, PortalRecord> dimMap = byDim.computeIfAbsent(rec.dimension(), k -> new HashMap<>());
        for (Iterator<Map.Entry<BlockPos, PortalRecord>> it = dimMap.entrySet().iterator(); it.hasNext();) {
            PortalRecord ex = it.next().getValue();
            if (ex.anchor().equals(rec.anchor()))
                continue; // 同一キー → 末尾で put 更新
            if (contains(rec.aabb(), ex.aabb())) {
                it.remove(); // rec が既存を内包 (= rec は完全版) → stale な半分を除去
            } else if (contains(ex.aabb(), rec.aabb())) {
                // rec が既存に内包される (= 既に完全版あり) → 既存の lastSeen のみ更新して終了
                dimMap.put(ex.anchor(), ex.withSeen(rec.provenance(), rec.lastSeenTick()));
                return;
            }
        }
        dimMap.put(rec.anchor(), rec);
    }

    /** big が small を完全に内包するか (境界一致を許容)。 */
    private static boolean contains(AABB big, AABB small) {
        return big.minX <= small.minX && big.minY <= small.minY && big.minZ <= small.minZ
                && big.maxX >= small.maxX && big.maxY >= small.maxY && big.maxZ >= small.maxZ;
    }
}
