package com.kajiwara.hyperslice.bswap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.kajiwara.hyperslice.core.HyperTerrain;
import com.kajiwara.hyperslice.worldgen.HyperSliceChunkGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * <b>【診断実験】</b> ロード済みチャンクを別の w の地形へ丸ごと差し替えて再送する。
 *
 * <h2>測定の意味を壊さないための必須事項</h2>
 * <b>{@code Level.setBlock} / {@code setBlockState} をブロック単位で使わない。</b>
 * 1 ブロックずつ置くと近傍更新・ライティング・パケットが 1 個ずつ発火し、
 * 測定値が「B が本来出せる性能」から完全に乖離する。 ここでは
 * {@link PalettedContainer} を丸ごと新造し、
 * {@link LevelChunkSection} ごと差し替える (= B 本実装で使うのと同じ手段)。
 *
 * <p>{@code new LevelChunkSection(states, biomes)} は<b>コンストラクタ内で
 * {@code recalcBlockCounts()} を呼ぶ</b>ので (26.1.2 の逆アセンブルで確認)、
 * ブロック数の整合はこちらで面倒を見る必要がない。
 *
 * <h2>フェーズ</h2>
 * <pre>
 *   generate  HyperTerrain から新しいブロック配列 (PalettedContainer) を作る … 並列化可
 *   swap      セクション差し替え + ハイトマップ + スカイライト源 …… サーバースレッド
 *   light     ライトエンジンへの通知 (と、 モードにより完了待ち) … 非同期
 *   send      フルチャンク+光パケットの構築と送出 ………………… サーバースレッド
 * </pre>
 */
public final class BSwapRunner {

    /**
     * 生成専用のスレッドプール。
     *
     * <p>MC の共通プール (ForkJoinPool.commonPool) と取り合うと測定値がぶれるため分ける。
     * ここで走るのは {@link HyperTerrain} の純粋計算と {@link PalettedContainer} の
     * 組み立てだけで、 MC の可変状態には一切触れない。 したがってサーバースレッドから
     * {@code join()} してもデッドロックしない (光の future とは事情が違う)。
     */
    private static volatile ExecutorService generatorPool;

    private BSwapRunner() {
    }

    /**
     * 差し替えたセクションと、 それが空気だけかどうか。
     *
     * <p>空判定を差し替え時に控えておくのは<b>計測の正確さのため</b>。
     * 光へ通知する段で判定し直すと、 その走査コストが light フェーズの数値に混入する。
     */
    private record DirtySection(SectionPos pos, boolean empty) {
    }

    /** 1 回分の計測結果 [ns]。 */
    public record Timings(long generate, long swap, long light, long send,
                          int chunks, int sections, int blockEntitiesRemoved) {
        public long total() {
            return generate + swap + light + send;
        }
    }

    /**
     * 差し替えを実行する。
     *
     * <p>{@code waitForLight} が有効なときは light / send が非同期に完了するため、
     * 結果は戻り値ではなく {@code onDone} で返す (呼び出し側はチャットへ流す)。
     * 無効なときも同じ経路を通す (報告の形を揃えるため)。
     *
     * @return 対象になったロード済みチャンク数。 0 なら何もしていない
     */
    public static int run(ServerPlayer player, int targetW, int radius, Consumer<Timings> onDone) {
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        ServerChunkCache chunkSource = level.getChunkSource();

        if (!(chunkSource.getGenerator() instanceof HyperSliceChunkGenerator generator)) {
            return 0;
        }
        HyperTerrain terrain = generator.terrainFor(chunkSource.randomState());

        // ── 対象チャンクの収集 (ロード済みのみ) ──
        // getChunkNow は未ロードなら null を返す。 ここで新規生成を誘発してはならない
        // (生成コストが測定値に混入するし、 実験の趣旨から外れる)。
        ChunkPos centre = ChunkPos.containing(player.blockPosition());
        List<LevelChunk> targets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = chunkSource.getChunkNow(centre.x() + dx, centre.z() + dz);
                if (chunk != null) {
                    targets.add(chunk);
                }
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }

        // パレット生成に必要なのは Strategy と既定値だけ。 どちらも不変なのでワーカーへ渡せる。
        // ファクトリはレベルが持っているものをそのまま使う (毎回 create すると余計な確保になる。
        // バニラの SerializableChunkData も level.palettedContainerFactory() を引いている)。
        Strategy<BlockState> strategy = level.palettedContainerFactory().blockStatesStrategy();
        BlockState air = Blocks.AIR.defaultBlockState();

        // ── フェーズ1: 生成 ──
        long t0 = System.nanoTime();
        List<PalettedContainer<BlockState>[]> generated =
                generateAll(targets, terrain, targetW, strategy, air);
        long t1 = System.nanoTime();

        // ── フェーズ2: 差し替え ──
        int sections = 0;
        int blockEntitiesRemoved = 0;
        List<DirtySection> dirty = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            LevelChunk chunk = targets.get(i);
            blockEntitiesRemoved += swapChunk(chunk, generated.get(i), dirty);
            sections += chunk.getSections().length;
        }
        long t2 = System.nanoTime();

        // ── フェーズ3/4: ライティング → 送信 ──
        final int chunkCount = targets.size();
        final int sectionCount = sections;
        final int removed = blockEntitiesRemoved;
        final long genNs = t1 - t0;
        final long swapNs = t2 - t1;

        ThreadedLevelLightEngine light = chunkSource.getLightEngine();
        for (DirtySection section : dirty) {
            // セクションの中身が丸ごと変わったことを光へ知らせる。
            light.updateSectionStatus(section.pos(), section.empty());
        }

        if (!BSwapExperiment.waitForLight()) {
            // 待たない: 光の再計算は投げっぱなしで即送る。
            // クライアントには「旧地形の光が乗った新地形」が一瞬見える (= ちらつきの観察)。
            for (LevelChunk chunk : targets) {
                light.lightChunk(chunk, false);
            }
            long t3 = System.nanoTime();
            sendAll(targets, level, player);
            long t4 = System.nanoTime();
            onDone.accept(new Timings(genNs, swapNs, t3 - t2, t4 - t3,
                    chunkCount, sectionCount, removed));
            return chunkCount;
        }

        // 待つ: 光の完了後に、 サーバースレッドへ戻ってから送る。
        //
        // 【重要】ここでサーバースレッドを block してはならない。 光タスクは
        // ChunkTaskDispatcher 経由で走り、 その駆動はサーバースレッドのチャンクタスクループが
        // 担うため、 素の join() は自分の待っている相手を止めてデッドロックする。
        // 継続を server.execute へ渡すことで待たずに済ませる。
        CompletableFuture<?>[] futures = new CompletableFuture<?>[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            LevelChunk chunk = targets.get(i);
            ChunkPos pos = chunk.getPos();
            futures[i] = light.lightChunk(chunk, false)
                    .thenCompose(ignored -> light.waitForPendingTasks(pos.x(), pos.z()));
        }
        CompletableFuture.allOf(futures).thenRun(() -> server.execute(() -> {
            long t3 = System.nanoTime();
            sendAll(targets, level, player);
            long t4 = System.nanoTime();
            onDone.accept(new Timings(genNs, swapNs, t3 - t2, t4 - t3,
                    chunkCount, sectionCount, removed));
        }));
        return chunkCount;
    }

    // ── フェーズ1: 生成 ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<PalettedContainer<BlockState>[]> generateAll(
            List<LevelChunk> targets, HyperTerrain terrain, int targetW,
            Strategy<BlockState> strategy, BlockState air) {

        if (!BSwapExperiment.parallelGeneration()) {
            List<PalettedContainer<BlockState>[]> out = new ArrayList<>(targets.size());
            for (LevelChunk chunk : targets) {
                out.add(generateChunk(chunk, terrain, targetW, strategy, air));
            }
            return out;
        }

        ExecutorService pool = pool();
        List<CompletableFuture<PalettedContainer<BlockState>[]>> futures =
                new ArrayList<>(targets.size());
        for (LevelChunk chunk : targets) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> generateChunk(chunk, terrain, targetW, strategy, air), pool));
        }
        List<PalettedContainer<BlockState>[]> out = new ArrayList<>(targets.size());
        for (CompletableFuture<PalettedContainer<BlockState>[]> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    /**
     * 1 チャンク分のセクション中身を作る。
     *
     * <p>ブロックの選び方は {@link HyperSliceChunkGenerator#stateAt} に<b>委ねる</b>。
     * ここで独自に書き直すと {@code /bswap 3} と {@code /hyperslice 3} が一致しなくなり、
     * 「方式A を正解データとして比べる」という検証手段そのものが失われる。
     *
     * <p>空気を書かないのは {@code fillFromNoise} と同じ扱い
     * (新造した容器の既定値が空気なので、 書かない = 空気)。
     */
    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] generateChunk(
            LevelChunk chunk, HyperTerrain terrain, int targetW,
            Strategy<BlockState> strategy, BlockState air) {

        int minY = chunk.getMinY();
        int sectionCount = chunk.getSections().length;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int maxY = minY + chunk.getHeight() - 1;

        // 地表高はカラムごとに 1 回だけ引く (セクションごとに引き直すと 24 倍無駄)。
        int[] surface = new int[256];
        boolean[] underwater = new boolean[256];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int s = Math.min(terrain.surfaceY(baseX + lx, baseZ + lz, targetW), maxY);
                surface[lx * 16 + lz] = s;
                underwater[lx * 16 + lz] = s < HyperTerrain.SEA_LEVEL;
            }
        }

        PalettedContainer<BlockState>[] out = new PalettedContainer[sectionCount];
        for (int si = 0; si < sectionCount; si++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(air, strategy);
            int sectionMinY = minY + si * 16;
            for (int ly = 0; ly < 16; ly++) {
                int y = sectionMinY + ly;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int c = lx * 16 + lz;
                        BlockState state = HyperSliceChunkGenerator.stateAt(
                                y, minY, surface[c], underwater[c]);
                        if (state != air) {
                            states.set(lx, ly, lz, state);
                        }
                    }
                }
            }
            out[si] = states;
        }
        return out;
    }

    // ── フェーズ2: 差し替え ───────────────────────────────────────

    /**
     * セクションを丸ごと差し替え、 ハイトマップとスカイライト源を作り直す。
     *
     * <p>順序が重要: セクション → ハイトマップ → スカイライト源。
     * スカイライト源は現在のブロックとハイトマップから引かれるので、 先にやると旧地形基準になる。
     *
     * @return 取り除いたブロックエンティティの数
     */
    private static int swapChunk(LevelChunk chunk, PalettedContainer<BlockState>[] states,
                                 List<DirtySection> dirty) {
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos pos = chunk.getPos();
        int minSectionY = SectionPos.blockToSectionCoord(chunk.getMinY());

        // 生成地形にブロックエンティティは無い (石・土・草・砂・水・岩盤・空気のみ) が、
        // プレイヤーが置いたものが残っていると「中身の無いブロックエンティティ」になる。
        // 差し替え範囲はチャンク全体なので全部落とす。
        int removed = 0;
        for (BlockPos bePos : new HashSet<>(chunk.getBlockEntitiesPos())) {
            chunk.removeBlockEntity(bePos);
            removed++;
        }

        for (int si = 0; si < sections.length; si++) {
            // 生物群系は据え置く (地形だけを差し替える実験なので、 変える理由がない)。
            LevelChunkSection replaced = new LevelChunkSection(states[si], sections[si].getBiomes());
            sections[si] = replaced;
            dirty.add(new DirtySection(
                    SectionPos.of(pos.x(), minSectionY + si, pos.z()), replaced.hasOnlyAir()));
        }

        // 作り直すハイトマップの種類は自分で決めず、 チャンクの生成段階に聞く。
        // バニラがセーブから LevelChunk を組み立てる経路 (SerializableChunkData) も
        // ChunkStatus.heightmapsAfter() を使っている (逆アセンブルで確認)。
        Heightmap.primeHeightmaps(chunk, chunk.getPersistedStatus().heightmapsAfter());
        chunk.getSkyLightSources().fillFrom(chunk);

        chunk.markUnsaved();
        return removed;
    }

    // ── フェーズ4: 送信 ───────────────────────────────────────────

    /**
     * フルチャンク + 光パケットを、 そのチャンクを見ている全プレイヤーへ送る。
     *
     * <p>構築の形はバニラ {@code PlayerChunkSender.sendChunk} と同一
     * ({@code new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)}。
     * 逆アセンブルで確認。 null / null は「全セクション」の意味)。
     */
    private static void sendAll(List<LevelChunk> chunks, ServerLevel level, ServerPlayer fallback) {
        ServerChunkCache chunkSource = level.getChunkSource();
        for (LevelChunk chunk : chunks) {
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                    chunk, level.getLightEngine(), null, null);
            ChunkPos pos = chunk.getPos();
            int sent = 0;
            for (ServerPlayer p : level.players()) {
                if (chunkSource.chunkMap.isChunkTracked(p, pos.x(), pos.z())) {
                    p.connection.send(packet);
                    sent++;
                }
            }
            if (sent == 0) {
                // 追跡判定から漏れても、 実験の実行者にだけは必ず届ける。
                fallback.connection.send(packet);
            }
        }
    }

    // ── プール ──────────────────────────────────────────────────

    private static ExecutorService pool() {
        ExecutorService p = generatorPool;
        if (p == null) {
            synchronized (BSwapRunner.class) {
                p = generatorPool;
                if (p == null) {
                    int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                    p = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "hyperslice-bswap-gen");
                        t.setDaemon(true);
                        return t;
                    });
                    generatorPool = p;
                }
            }
        }
        return p;
    }
}
