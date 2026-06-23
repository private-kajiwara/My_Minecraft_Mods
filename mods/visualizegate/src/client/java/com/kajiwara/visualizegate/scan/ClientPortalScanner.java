package com.kajiwara.visualizegate.scan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * ロード済みチャンクから NETHER_PORTAL の連結成分を検出する純粋スキャナ (状態を持たない static)。
 *
 * <p><b>パレット早期スキップ</b>: {@link LevelChunkSection#hasOnlyAir()} と
 * {@link LevelChunkSection#maybeHas(java.util.function.Predicate)} で portal を含み得ないセクションは
 * 実ブロック走査せずスキップする (= 走査コストを実在セクションのみに限定)。
 *
 * <p><b>連結成分</b>: seed から 6 近傍 flood-fill する。 セクション境界をまたいで<b>チャンク内 Y 全域</b>を
 * 走るので、 21 ブロック高のポータルが「半分だけ枠」になることはない。 隣接チャンクへも<b>ロード済みに限り</b>
 * 踏み込む (= 境界跨ぎポータルを両側ロード時に 1 成分へ結合)。 未ロードチャンクは読みに行かない
 * ({@link Level#hasChunk(int, int)} ガード = force-load / NPE 回避)。
 */
public final class ClientPortalScanner {

    /** flood-fill の安全上限 (暴走ガード)。 実ポータルは最大でも数百ブロック。 */
    private static final int FLOOD_CAP = 8192;

    private ClientPortalScanner() {
    }

    private static boolean isPortal(BlockState state) {
        return state.getBlock() == Blocks.NETHER_PORTAL;
    }

    /**
     * 1 チャンクをスキャンし、 そこを起点に到達できる (ロード済み範囲の) ポータル連結成分を返す。
     * 戻り値の anchor はグローバル最低コーナーなので、 境界跨ぎでも一意。
     */
    public static List<PortalRecord> scanChunk(ClientLevel level, LevelChunk chunk,
            PortalRecord.Provenance prov, long tick) {
        ResourceKey<Level> dim = level.dimension();
        List<PortalRecord> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        ChunkPos cp = chunk.getPos();
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir())
                continue;
            if (!sec.maybeHas(ClientPortalScanner::isPortal))
                continue;

            int baseY = chunk.getSectionYFromSectionIndex(i) * 16;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int wx = baseX + x, wy = baseY + y, wz = baseZ + z;
                        long key = BlockPos.asLong(wx, wy, wz);
                        if (visited.contains(key))
                            continue;
                        BlockPos p = new BlockPos(wx, wy, wz);
                        if (!isPortal(level.getBlockState(p)))
                            continue;
                        PortalRecord rec = floodComponent(level, dim, p, visited, prov, tick);
                        if (rec != null)
                            out.add(rec);
                    }
                }
            }
        }
        return out;
    }

    /** seed から連結成分を BFS して 1 レコードを構築する (ロード済みブロックのみ辿る)。 */
    private static PortalRecord floodComponent(ClientLevel level, ResourceKey<Level> dim, BlockPos seed,
            Set<Long> visited, PortalRecord.Provenance prov, long tick) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed.asLong());

        int minX = seed.getX(), minY = seed.getY(), minZ = seed.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        int count = 0;

        while (!queue.isEmpty() && count < FLOOD_CAP) {
            BlockPos p = queue.poll();
            count++;
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() > maxZ) maxZ = p.getZ();

            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                long nk = n.asLong();
                if (visited.contains(nk))
                    continue;
                // 未ロードの隣接チャンクは読みに行かない (force-load / NPE 回避)。境界で止める。
                if (!level.hasChunk(n.getX() >> 4, n.getZ() >> 4))
                    continue;
                if (!isPortal(level.getBlockState(n)))
                    continue;
                visited.add(nk);
                queue.add(n);
            }
        }

        AABB aabb = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        // 軸は連結成分の広がりから幾何的に導出 (ブロックプロパティ API に依存しない)。
        Direction.Axis axis = (maxX - minX) >= (maxZ - minZ) ? Direction.Axis.X : Direction.Axis.Z;
        BlockPos anchor = new BlockPos(minX, minY, minZ);
        return new PortalRecord(dim, anchor, aabb, axis, prov, tick);
    }
}
