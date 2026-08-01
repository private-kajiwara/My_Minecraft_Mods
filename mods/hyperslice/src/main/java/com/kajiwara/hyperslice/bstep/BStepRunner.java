package com.kajiwara.hyperslice.bstep;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.ToDoubleFunction;

import com.kajiwara.hyperslice.core.HyperTerrain;
import com.kajiwara.hyperslice.worldgen.HyperSliceChunkGenerator;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 方式B の適用 1 回: 差分計算 (並列) → 適用 (サーバースレッド)。
 *
 * <h2>役割分担</h2>
 * <pre>
 *   diff   BStepDiff を対象チャンクに対して並列に走らせる ……… ワーカースレッド
 *   apply  Level.setBlock で差分だけ書き込む ………………………… サーバースレッド
 *   (send) 我々の経路には無い。 ChunkHolder が溜めて毎ティック 1 回まとめて送る
 * </pre>
 *
 * <p><b>「送信フェーズ」を我々が持っていない</b>のは実装漏れではなく 26.1.2 の構造。
 * {@code Level.setBlock} が同期的に呼ぶのは {@code sendBlockUpdated} →
 * {@code ChunkHolder.blockChanged} = <b>セクションごとの {@code ShortSet} への登録</b>までで、
 * 実際のパケット構築と送出は {@code ServerChunkCache.broadcastChangedChunks} が
 * 毎ティック 1 回行う。 したがって登録コストは apply に含まれ、 送出コストはそのあと
 * サーバーのティック本体で払われる。
 *
 * <p><b>合否指標は MSPT ではない</b> ({@link TickPeak} を参照)。 バニラのティック計測は
 * {@code END_SERVER_TICK} より前に締め切られるので、 ここで測っている占有時間を 1ns も
 * 含まない。 判定に使うのは自前の「mod 占有」と「ティック実周期」である。
 *
 * <h2>対象チャンク</h2>
 * ロード済み<b>かつ {@code BLOCK_TICKING} 以上</b>のものだけ。 後者が要るのは
 * {@code Level.setBlock} が {@code UPDATE_CLIENTS} を効かせる条件が
 * {@code chunk.getFullStatus().isOrAfter(BLOCK_TICKING)} であり、
 * {@code ChunkHolder.blockChanged} も {@code getTickingChunk() == null} なら即 return するため
 * (どちらも逆アセンブルで確認)。 これを満たさないチャンクに書くと
 * <b>サーバー側だけ変わってクライアントへ届かない</b>ので、 最初から除外して数を報告する。
 * 未ロードチャンクの新規生成は誘発しない ({@code /bswap} と同じ方針)。
 */
public final class BStepRunner {

    /**
     * 差分計算専用のスレッドプール。
     *
     * <p>MC の共通プールと取り合うと測定値がぶれるため分ける。 ここで走るのは
     * {@link HyperTerrain} の純粋計算と {@link BStepDiff} の組み立てだけで、
     * MC の可変状態には触らない。 したがってサーバースレッドから {@code join()} しても
     * デッドロックしない (光の future とは事情が違う)。
     */
    private static volatile ExecutorService diffPool;

    private BStepRunner() {
    }

    /**
     * 1 回の適用の計測結果。 時間は [ns]。
     *
     * <p><b>時間の意味を取り違えないこと</b>: {@link #diffNs} と {@link #applyNs} は
     * どちらも<b>1 ティックの中でサーバースレッドが実際に占有された実時間</b>である
     * (差分計算はワーカープールで走るが、 サーバースレッドは {@code join()} で
     * 待っているので占有時間に入る)。 一方 MSPT は
     * {@code MinecraftServer.getAverageTickTimeNanos()} = <b>直近 100 ティックの移動平均</b>
     * ({@code TICK_STATS_SPAN = 100}) なので、 1 ティックだけ 135ms かかっても
     * 平均には 1.35ms しか乗らない。 <b>両者を直に比べてはいけない。</b>
     *
     * @param chunks         実際に差分を当てたチャンク数
     * @param chunksSkipped  {@code BLOCK_TICKING} 未満などで<b>対象外</b>だった数
     * @param chunksDeferred 「更新すべき」だったが<b>予算に入らなかった</b>数
     *                       (次のティックでさらに優先度が上がる)
     * @param updatedPerBand 距離帯ごとの更新数 ({@link WScheduler} の帯と同じ並び)
     * @param residualLagW   <b>追い付けていない遅れ</b> [w]。 当てなかったチャンクの遅れの最大
     *                       ({@link WScheduler#residualLag} を参照)
     */
    public record StepResult(double fromW, double toW, double phase,
                             int chunks, int chunksSkipped, int chunksDeferred,
                             int blocks, int columns, int sections,
                             int[] updatedPerBand, double residualLagW,
                             long diffNs, long applyNs) {
        /** この適用がサーバースレッドを占有した実時間 [ns]。 */
        public long total() {
            return diffNs + applyNs;
        }
    }

    /**
     * 対象チャンクと、 そのチャンクが現在どの w の地形になっているか。
     *
     * @param band {@link WScheduler} の距離帯の添字 (報告の内訳を出すために持ち回る)
     */
    public record Target(LevelChunk chunk, double currentW, int band) {
    }

    /**
     * 選抜前の候補。 {@link Target} に<b>中心からの Chebyshev 距離</b>を足したもの。
     *
     * <p>距離を {@link WScheduler} が使う。 ここで測っておくのは、 収集が正方形走査で
     * dx/dz を持っている場所だから (後から {@code ChunkPos} だけ渡されると
     * 中心を持ち回る必要が出る)。
     */
    public record Candidate(LevelChunk chunk, double currentW, int distance) {
    }

    // ── 対象の収集 ──────────────────────────────────────────────

    /**
     * 差分を当てられるチャンクを集める。
     *
     * <p>各候補には中心からの <b>Chebyshev 距離</b> ({@code max(|dx|,|dz|)}) を付ける。
     * 走査が正方形なのでこれが自然な距離で、 {@link WScheduler} の距離帯もこれで切る。
     *
     * @param radius   半径 [チャンク]。 負値ならシミュレーション距離を使う
     * @param currentWOf そのチャンクが今どの w の地形になっているかを引く関数
     * @param skipped  {@code BLOCK_TICKING} 未満などで除外した数を返す 1 要素配列
     */
    public static List<Candidate> collectCandidates(ServerLevel level, ChunkPos centre,
                                                    int radius,
                                                    ToDoubleFunction<LevelChunk> currentWOf,
                                                    int[] skipped) {
        ServerChunkCache chunkSource = level.getChunkSource();
        int r = radius >= 0 ? radius : level.getServer().getPlayerList().getSimulationDistance();

        List<Candidate> candidates = new ArrayList<>();
        int dropped = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LevelChunk chunk = chunkSource.getChunkNow(centre.x() + dx, centre.z() + dz);
                if (chunk == null) {
                    // 未ロード。 生成を誘発してはならない。
                    continue;
                }
                FullChunkStatus status = chunk.getFullStatus();
                if (status == null || !status.isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
                    dropped++;
                    continue;
                }
                int distance = Math.max(Math.abs(dx), Math.abs(dz));
                candidates.add(new Candidate(chunk, currentWOf.applyAsDouble(chunk), distance));
            }
        }
        skipped[0] = dropped;
        return candidates;
    }

    /** そのレベルの地形関数。 HyperSlice のディメンションでなければ {@code null}。 */
    public static HyperTerrain terrainOf(ServerLevel level) {
        ServerChunkCache chunkSource = level.getChunkSource();
        if (!(chunkSource.getGenerator() instanceof HyperSliceChunkGenerator generator)) {
            return null;
        }
        return generator.terrainFor(chunkSource.randomState());
    }

    /** そのレベルのスライス枚数 N。 HyperSlice のディメンションでなければ {@code -1}。 */
    public static int sliceCountOf(ServerLevel level) {
        ServerChunkCache chunkSource = level.getChunkSource();
        if (!(chunkSource.getGenerator() instanceof HyperSliceChunkGenerator generator)) {
            return -1;
        }
        return generator.sliceCount();
    }

    // ── 適用 ────────────────────────────────────────────────────

    /**
     * 適用の実績。 {@link StepResult} を組み立てるのは呼び出し側 ({@code BStepSession})。
     *
     * @param chunks 先頭から実際に当てた個数。 <b>常に先頭からの連続</b>なので、
     *               呼び出し側は {@code targets.subList(0, chunks)} に対してのみ
     *               {@link ChunkW} を進めればよい
     */
    public record Applied(int chunks, int blocks, int columns, int sections,
                          long diffNs, long applyNs) {
    }

    /**
     * 差分を計算して適用する。 <b>時間予算で途中打ち切りできる。</b>
     *
     * <p>各対象は<b>自分が今どの w になっているか</b> ({@link Target#currentW}) から
     * {@code toW} への差分を取る。 全チャンク一律に「前回の w」を使わないのは、
     * 途中でロードされたチャンクが<b>そのディメンション本来の整数 w</b>で生成されるため。
     * 一律にすると、 後から入ってきたチャンクだけ永久にずれた地形が残り、
     * 「{@code /hyperslice n} と一致するか」という検証が壊れる。
     *
     * <h2>打ち切りの構造</h2>
     * <ul>
     *   <li>対象は {@code batchSize} 個ずつ処理する。 バッチ内の差分計算は並列、 適用は逐次</li>
     *   <li>締め切りは<b>バッチを始める前</b>と<b>1 チャンク当てるごと</b>に見る。
     *       チャンクの途中では止めない (止めるとそのチャンクだけ半端な地形になり、
     *       {@link ChunkW} に何を書いても嘘になる)</li>
     *   <li>計算済みで当てなかった差分は捨てる。 <b>捨てる量の上限は 1 バッチぶん</b>
     *       ({@link WScheduler#DIFF_BATCH_CHUNKS} の javadoc を参照)</li>
     *   <li><b>必ず 1 チャンクは当てる</b>。 締め切りを既に過ぎていても最初の 1 個は通す
     *       (通さないと、 恒常的に重いサーバーで永久に何も適用されなくなる)</li>
     * </ul>
     *
     * @param deadlineNanos {@code System.nanoTime()} 基準の締め切り。
     *                      {@link Long#MAX_VALUE} なら無制限 (単発コマンド用)
     * @param maxChunks     当てる個数の上限 (暴走止め)
     * @param batchSize     差分計算をまとめる単位。 上限以上なら 1 バッチ = 全部
     */
    public static Applied apply(ServerLevel level, List<Target> targets, HyperTerrain terrain,
                                double toW, long deadlineNanos, int maxChunks, int batchSize) {
        int limit = Math.min(targets.size(), maxChunks);
        // 進み幅は必ず 1 以上・limit 以下に丸める。 素の batchSize を足すと
        // Integer.MAX_VALUE (単発コマンド) や 0 以下の設定ミスで start が壊れる。
        int stride = Math.max(1, Math.min(batchSize, Math.max(limit, 1)));
        int applied = 0;
        int blocks = 0;
        int columns = 0;
        int sections = 0;
        long diffNs = 0L;
        long applyNs = 0L;

        for (int start = 0; start < limit; start += stride) {
            if (applied > 0 && System.nanoTime() >= deadlineNanos) {
                break;
            }
            int end = Math.min(start + stride, limit);

            long t0 = System.nanoTime();
            List<BStepDiff.ChunkDiff> diffs =
                    computeAll(targets.subList(start, end), terrain, toW);
            long t1 = System.nanoTime();
            diffNs += t1 - t0;

            boolean expired = false;
            for (BStepDiff.ChunkDiff diff : diffs) {
                if (applied > 0 && System.nanoTime() >= deadlineNanos) {
                    expired = true;
                    break;
                }
                int n = diff.size();
                for (int i = 0; i < n; i++) {
                    // フラグの意味は BStepExperiment.SET_BLOCK_FLAGS の javadoc を参照。
                    level.setBlock(diff.position(i), diff.state(i),
                            BStepExperiment.SET_BLOCK_FLAGS);
                }
                blocks += n;
                columns += diff.changedColumns();
                sections += diff.touchedSections();
                applied++;
            }
            applyNs += System.nanoTime() - t1;

            if (expired) {
                break;
            }
        }

        return new Applied(applied, blocks, columns, sections, diffNs, applyNs);
    }

    private static List<BStepDiff.ChunkDiff> computeAll(List<Target> targets,
                                                        HyperTerrain terrain, double toW) {
        if (!BStepExperiment.parallelDiff() || targets.size() == 1) {
            List<BStepDiff.ChunkDiff> out = new ArrayList<>(targets.size());
            for (Target t : targets) {
                out.add(BStepDiff.compute(t.chunk(), terrain, t.currentW(), toW));
            }
            return out;
        }

        ExecutorService pool = pool();
        List<CompletableFuture<BStepDiff.ChunkDiff>> futures = new ArrayList<>(targets.size());
        for (Target t : targets) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> BStepDiff.compute(t.chunk(), terrain, t.currentW(), toW), pool));
        }
        List<BStepDiff.ChunkDiff> out = new ArrayList<>(targets.size());
        for (CompletableFuture<BStepDiff.ChunkDiff> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    // ── プール ──────────────────────────────────────────────────

    private static ExecutorService pool() {
        ExecutorService p = diffPool;
        if (p == null) {
            synchronized (BStepRunner.class) {
                p = diffPool;
                if (p == null) {
                    int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                    p = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "hyperslice-bstep-diff");
                        t.setDaemon(true);
                        return t;
                    });
                    diffPool = p;
                }
            }
        }
        return p;
    }
}
