package com.kajiwara.hyperslice.bstep;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.kajiwara.hyperslice.core.HyperTerrain;
import com.kajiwara.hyperslice.slice.SliceTeleporter;
import com.kajiwara.hyperslice.worldgen.HyperSliceChunkGenerator;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * <b>【方式B 中核】</b> レベルの w の<b>権威</b>と、 それを進める駆動。
 *
 * <h2>ここが w の唯一の権威である</h2>
 * 方式B では w は<b>世界の状態</b>なので、 レベル (= ディメンション) ごとに 1 つだけ持つ。
 * 地形もエンティティの観測面も HUD も、 この値を {@code LevelW} 経由で読む。
 * クライアントは値を<b>持たず</b>、 {@code WStateSync} が配ったものを表示に使うだけ。
 *
 * <h2>w を進める経路は 2 つ (どちらもここへ集まる)</h2>
 * <ul>
 *   <li><b>キー入力</b> ({@code WDriveInput}) … Page Up / Down。 本来の遊び方。
 *       毎ティック {@code BStepExperiment.W_RATE_PER_TICK} ずつ動かす</li>
 *   <li><b>{@code /bstep}</b> … 単発・{@code auto}・{@code verify}。 計測と検証のために残す</li>
 * </ul>
 *
 * <h2>w の持ち方</h2>
 * <ul>
 *   <li>レベルごとに 1 つの「今の w」。 初期値は<b>保存値</b>、 無ければそのスライス本来の
 *       整数 w ({@link WSavedState})。 保存しないと、 地形だけがセーブに焼かれた状態で
 *       開き直すことになり、 次の 1 ステップで巨大な差分が出る</li>
 *   <li>さらに<b>チャンクごとに</b>「そのチャンクが今どの w の地形か」を持つ。
 *       実体はチャンク自身に貼る永続 attachment ({@link ChunkW})。 これが無いと、
 *       途中でロードされたチャンクや再ロードされたチャンクだけ永久にずれた地形が残り
 *       {@code /hyperslice n} との比較が壊れる</li>
 *   <li>生成器にも同じ値を渡す ({@link LiveW})。 新規チャンクが<b>生成時点で今の w</b>で
 *       作られるので、 「一瞬だけ違う w の地形が見えてから直る」ちらつきが起きない</li>
 * </ul>
 *
 * <h2>w は毎ティック連続に進む — 「ステップ」という単位は存在しない</h2>
 * かつては量子 ({@link BStepExperiment#STEP_QUANTUM}) が溜まるたびに w を飛ばし、
 * その瞬間に全対象へ一括で差分を当てていた。 これが実測でサーバースレッドを単発 81.97ms
 * 占有し、 ティック実周期を 127.73ms へ伸ばしていた (ティック予算 50ms)。
 *
 * <p>今は w を毎ティック {@link BStepExperiment#W_RATE_PER_TICK} ずつ連続に進め、
 * 適用は<b>毎ティック時間予算の範囲だけ</b>行う ({@link #catchUp})。 量子は
 * 「w をどう進めるか」ではなく<b>「どれだけ遅れてよいか」の単位</b>になった
 * ({@link WScheduler#BAND_QUANTA})。
 *
 * <h2>追い付きは per-chunk w がそのまま担う</h2>
 * 予算に入らなかったチャンクは w が遅れる。 次に順番が来たときに当てるのは
 * 「1 ティックぶん」ではなく<b>そのチャンクの w から今の w までの蓄積分</b>である。
 * これは新しい機構ではなく、 上の per-chunk w がそのまま効く:
 * {@code BStepDiff.compute(chunk, terrain, そのチャンクの w, 今の w)} は
 * delta の大きさに一切依存しない (y 範囲の導出が {@code lo=min(s0,s1)} /
 * {@code hi=max(s0,s1)} で書かれており、 {@code /bstep verify} が delta 3.0 =
 * 量子 24 個ぶんまで総当たりと突き合わせている)。
 *
 * <p>ただし<b>更新したチャンクの w だけ</b>を進めること。 見送ったチャンクまで
 * 進めてしまうと蓄積分が失われ、 そのチャンクは永久にずれたまま残る。
 */
public final class BStepSession {

    /** レベルごとのセッション。 サーバースレッドからのみ触る。 */
    private static final Map<net.minecraft.resources.ResourceKey<Level>, BStepSession> SESSIONS =
            new HashMap<>();

    /** {@code auto} が走っているセッション (同時に 1 つだけ)。 */
    private static BStepSession active;

    // ── セッション状態 ──────────────────────────────────────────

    private final ServerLevel level;
    private final int nominalW;
    private final int sliceCount;

    /** このレベルの「今の観測 w」。 */
    private double currentW;

    /** セーブに焼く先。 {@link #currentW} が変わるたびに書き写す。 */
    private final WSavedState saved;

    /** 生成器へ渡す「今の w」。 ワーカースレッドから読まれる。 */
    private final LiveW liveW;

    /**
     * 前回の追い付き走査で<b>予算に入りきらなかった対象が居たか</b>。
     *
     * <p>w が動いておらずこれも偽なら、 毎ティック 625 回の {@code getChunkNow} を
     * 回す意味は無い ({@link WScheduler#IDLE_RESCAN_TICKS} を参照)。
     */
    private boolean catchUpPending;

    /** 直近の適用の実績。 空振りのティックでも上書きするので、 表示は常に「今」を指す。 */
    private BStepRunner.StepResult last;

    // ── auto の状態 ────────────────────────────────────────────

    private double rate;
    private java.util.UUID driverId;
    private int hudCountdown;

    /**
     * このティックで既にキー入力によって駆動済みか。
     *
     * <p>キー入力と {@code /bstep auto} が同じレベルで同時に走っているとき、
     * 1 ティックに 2 回 {@link #tick} を呼ぶと w が 2 回進んでしまう。
     * キー入力側が先に走り、 その場合 auto 側の呼び出しを飛ばす
     * (レートはキー入力側の {@link #tick} が合算して扱っている)。
     *
     * <p>駆動していないレベルの追い付き ({@link #catchUp}) を回すかどうかの判定にも使う。
     */
    private boolean drivenThisTick;

    // ── 計測履歴 ────────────────────────────────────────────────

    private final Deque<BStepRunner.StepResult> history = new ArrayDeque<>();
    /** 光が追いつくまでの遅延 [ns]。 <b>単調増加していたらライトエンジンのキューが飽和している</b>。 */
    private final Deque<Long> lightLatency = new ArrayDeque<>();
    /** 光の計測は 1 本ずつ。 前回が終わっていないのに次を投げると測っているものが変わる。 */
    private boolean lightProbeInFlight;

    private BStepSession(ServerLevel level, int nominalW, int sliceCount) {
        this.level = level;
        this.nominalW = nominalW;
        this.sliceCount = sliceCount;
        this.saved = WSavedState.of(level, nominalW);
        this.currentW = saved.currentW();
        this.liveW = new LiveW(currentW);
    }

    /**
     * 生成器に「今の w」を差し込む (方式B)。
     *
     * <p>セッションを作るたびに ({@link #of} の中で) 1 回呼ぶ。 これ以降、 そのディメンションで
     * 新規生成されるチャンクは<b>整数 w ではなく今の連続 w</b>で作られ、 使った w が
     * {@link ChunkW} に記録される。 方式A ではセッションそのものが作られない
     * ({@code BStepExperiment.EXPERIMENT_ENABLED} が偽なら {@link #of} の呼び出し側が
     * すべて定数畳み込みで消える) ので、 生成器は Codec の整数 w のまま。
     */
    private void installLiveW() {
        if (level.getChunkSource().getGenerator() instanceof HyperSliceChunkGenerator generator) {
            generator.setWSource(liveW);
        }
    }

    // ── 取得 ────────────────────────────────────────────────────

    /**
     * そのレベルのセッション。 HyperSlice のディメンションでなければ {@code null}。
     *
     * <p>ディメンションキーはワールドを作り直しても同じなので、 <b>{@link ServerLevel} の
     * 参照同一性で作り直す</b>。 これをしないと、 ワールドを抜けて別のワールドへ入ったときに
     * 古いレベルを掴んだセッションが残る (w も per-chunk 表も前のワールドのもの)。
     */
    public static BStepSession of(ServerLevel level) {
        int w = SliceTeleporter.sliceWOf(level);
        if (w < 0) {
            return null;
        }
        int n = BStepRunner.sliceCountOf(level);
        if (n < 1) {
            return null;
        }
        BStepSession existing = SESSIONS.get(level.dimension());
        if (existing != null && existing.level == level) {
            return existing;
        }
        if (existing != null && active == existing) {
            active = null;
        }
        BStepSession fresh = new BStepSession(level, w, n);
        SESSIONS.put(level.dimension(), fresh);
        // 生成器への差し込みはセッションの生成と<b>必ず対</b>にする。 レベル読み込みイベント側
        // だけで行うと、 何らかの理由でそこを通らずに作られたセッションの生成器が
        // 整数 w のまま取り残される (= そのディメンションだけ新規チャンクがちらつく)。
        fresh.installLiveW();
        return fresh;
    }

    /**
     * 既にあるセッションだけを引く (無ければ {@code null}・<b>作らない</b>)。
     *
     * <p>{@code LevelW} が毎ティック・全プレイヤーぶん呼ぶので、 副作用で
     * セッションを量産しないための入口。 セッションが無いということは
     * まだ一度も w を動かしていないということなので、 呼び出し側は
     * そのディメンション本来の整数 w を使えばよい。
     */
    public static BStepSession peek(ServerLevel level) {
        BStepSession existing = SESSIONS.get(level.dimension());
        return (existing != null && existing.level == level) ? existing : null;
    }

    public double currentW() {
        return currentW;
    }

    public int nominalW() {
        return nominalW;
    }

    public int sliceCount() {
        return sliceCount;
    }

    public double phase() {
        return BStepDiff.phase(currentW, sliceCount);
    }

    public boolean isAuto() {
        return active == this && rate > 0.0;
    }

    public double rate() {
        return rate;
    }

    // ── 単発適用 (コマンド専用・分散しない) ────────────────────

    /**
     * w を {@code delta} だけ進めて<b>全チャンク</b>に差分を適用する (単発)。
     *
     * @return 計測結果。 対象チャンクが 0 なら {@code null}
     */
    public BStepRunner.StepResult step(ServerPlayer player, double delta) {
        return stepTo(player, currentW + delta);
    }

    /**
     * w を絶対値で指定して<b>全チャンク</b>に差分を適用する (単発。 {@code reset} も使う)。
     *
     * <p><b>単発では優先度も時間予算も通さない。</b> 通すと遠方が更新されないまま残り、
     * 「{@code /bstep 3.0} してから {@code /hyperslice 3} と見比べる」という
     * 正しさの検証手段が壊れる。 分散が効くのは連続駆動のときだけ。
     *
     * <p>差分計算も 1 バッチ (= 全対象を一度に並列化) で行う。 連続駆動と同じ
     * バッチ分割にすると並列度が変わり、 過去の実測値と比較できなくなる。
     */
    public BStepRunner.StepResult stepTo(ServerPlayer player, double targetW) {
        HyperTerrain terrain = BStepRunner.terrainOf(level);
        if (terrain == null) {
            return null;
        }

        int[] skipped = new int[1];
        ChunkPos centre = ChunkPos.containing(player.blockPosition());
        List<BStepRunner.Candidate> candidates = BStepRunner.collectCandidates(
                level, centre, BStepExperiment.radius(),
                chunk -> ChunkW.of(chunk, nominalW), skipped);
        if (candidates.isEmpty()) {
            return null;
        }

        List<BStepRunner.Target> targets = new ArrayList<>(candidates.size());
        for (BStepRunner.Candidate candidate : candidates) {
            targets.add(new BStepRunner.Target(candidate.chunk(), candidate.currentW(),
                    WScheduler.bandOf(candidate.distance())));
        }

        double fromW = currentW;
        setCurrentW(targetW);

        // 締め切り無し・個数無制限・1 バッチ (= 全対象を一度に並列化)。
        BStepRunner.Applied applied = BStepRunner.apply(
                level, targets, terrain, targetW,
                Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        for (int i = 0; i < applied.chunks(); i++) {
            ChunkW.set(targets.get(i).chunk(), targetW);
        }

        BStepRunner.StepResult result = new BStepRunner.StepResult(
                fromW, targetW, BStepDiff.phase(targetW, sliceCount),
                applied.chunks(), skipped[0], targets.size() - applied.chunks(),
                applied.blocks(), applied.columns(), applied.sections(),
                WScheduler.bandCounts(targets, applied.chunks()), 0.0,
                applied.diffNs(), applied.applyNs());

        // 全チャンクを今の w に揃えたので、 追い付き待ちは無い。
        catchUpPending = false;
        last = result;
        record(result);
        // この適用がサーバースレッドを占有した実時間をティック単位で積む。
        // バニラの計測窓は我々の仕事を含まないので、 自分で測るしかない ({@link TickPeak})。
        TickPeak.recordOccupancy(result.total());
        probeLight(centre);
        return result;
    }

    /**
     * このレベルの w を動かす。 <b>currentW を書き換える唯一の場所。</b>
     *
     * <p>セーブと生成器へ必ず同時に書き写す。 生成器へ渡すのは volatile 1 個への代入で、
     * 以後に生成されるチャンクはこの w で作られる ({@link LiveW})。
     */
    private void setCurrentW(double w) {
        currentW = w;
        saved.set(w);
        liveW.set(w);
    }

    // ── 追い付き (毎ティック・時間予算つき) ──────────────────

    /**
     * <b>【方式B 中核】</b> このティックぶんの追い付き。
     *
     * <p>やることは 3 つだけ:
     * <ol>
     *   <li>ロード済み対象を集め、 それぞれの遅れ ({@code |今の w - そのチャンクの w|}) を測る</li>
     *   <li>{@code 遅れ / その帯が許す粒度} の降順に並べる ({@link WScheduler#plan})</li>
     *   <li><b>時間予算の範囲だけ</b>当てる。 残りは次のティックで優先度が上がって戻ってくる</li>
     * </ol>
     *
     * <p>持ち越しのキューは持たない。 当てたチャンクの w だけを進めるので、
     * 残りは次のティックの候補収集で「遅れが大きいまま」自動的に現れる。 キューを持つと
     * 「アンロードされた・半径外へ出た対象をいつ捨てるか」という答えの無い問いが増える。
     *
     * @param wMoved このティックで w が動いたか (動いていなければ間引いてよい)
     */
    private void catchUp(MinecraftServer server, ServerPlayer centrePlayer, boolean wMoved) {
        if (!wMoved && !catchUpPending
                && server.getTickCount() % WScheduler.IDLE_RESCAN_TICKS != 0) {
            // 誰も w を触っておらず追い付き待ちも無い。 それでも完全には止めない:
            // アンロードされていたチャンクは古い w を焼いたまま戻ってくる。
            return;
        }

        HyperTerrain terrain = BStepRunner.terrainOf(level);
        if (terrain == null) {
            return;
        }

        int[] skipped = new int[1];
        ChunkPos centre = ChunkPos.containing(centrePlayer.blockPosition());
        List<BStepRunner.Candidate> candidates = BStepRunner.collectCandidates(
                level, centre, BStepExperiment.radius(),
                chunk -> ChunkW.of(chunk, nominalW), skipped);
        if (candidates.isEmpty()) {
            catchUpPending = false;
            return;
        }

        WScheduler.Plan plan = WScheduler.plan(candidates, currentW, BStepExperiment.scheduler());
        if (plan.due().isEmpty()) {
            // 全員が粒度の範囲内 = 設計上追い付いている。
            catchUpPending = false;
            last = idleResult(plan, skipped[0]);
            return;
        }

        BStepRunner.Applied applied = BStepRunner.apply(
                level, plan.due(), terrain, currentW,
                System.nanoTime() + WScheduler.budgetNanos(),
                WScheduler.MAX_CHUNKS_PER_TICK, WScheduler.DIFF_BATCH_CHUNKS);

        for (int i = 0; i < applied.chunks(); i++) {
            ChunkW.set(plan.due().get(i).chunk(), currentW);
        }
        catchUpPending = applied.chunks() < plan.due().size();

        BStepRunner.StepResult result = new BStepRunner.StepResult(
                currentW, currentW, BStepDiff.phase(currentW, sliceCount),
                applied.chunks(), skipped[0], plan.due().size() - applied.chunks(),
                applied.blocks(), applied.columns(), applied.sections(),
                WScheduler.bandCounts(plan.due(), applied.chunks()),
                WScheduler.residualLag(plan, applied.chunks(), currentW),
                applied.diffNs(), applied.applyNs());

        last = result;
        if (applied.chunks() > 0) {
            record(result);
            TickPeak.recordOccupancy(result.total());
            probeLight(centre);
        }
    }

    /** 当てるものが無かったティックの表示用 (遅れの数字だけは正しく出す)。 */
    private BStepRunner.StepResult idleResult(WScheduler.Plan plan, int skipped) {
        return new BStepRunner.StepResult(
                currentW, currentW, BStepDiff.phase(currentW, sliceCount),
                0, skipped, 0, 0, 0, 0,
                new int[WScheduler.bandCount()], plan.maxLagNotDue(), 0L, 0L);
    }

    // ── 駆動 (キー入力 / auto) ──────────────────────────────────

    /**
     * イベント登録。 {@code HyperSliceCommands} からフラグ判定つきで 1 回だけ呼ぶ。
     *
     * <p>誰も w を動かしていないときの {@code END_SERVER_TICK} は、
     * {@code WDriveInput.isIdle()} と {@code active == null} と、 各セッションの
     * {@link #catchUp} の頭にある間引き ({@link WScheduler#IDLE_RESCAN_TICKS}) と、
     * {@code WStateSync} のプレイヤー走査 (値が変わっていなければ何も送らない) だけで抜ける。
     * <b>625 回の {@code getChunkNow} が回るのは、 w が動いているか追い付き待ちがあるか、
     * さもなくば 1 秒に 1 回だけ</b>である。
     */
    public static void register() {
        // レベル読み込み時にセッションを<b>先出しで</b>作る。 遅延生成のままだと、
        // ワールドを開き直したあと誰かが w を動かすまで LevelW が本来の整数 w を返し、
        // 「地形は保存された w なのに観測面と HUD は整数 w」という不整合が残る。
        // ここで作れば保存値の復元と生成器への差し込みが同時に済む。
        ServerLevelEvents.LOAD.register((server, level) -> {
            if (SliceTeleporter.isSlice(level)) {
                // 生成 = 保存値の復元 + 生成器への差し込み (of の中で対にしてある)。
                of(level);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(BStepSession::onServerTick);
        // ディメンションキーはワールドを作り直しても同じなので、 サーバー停止時に必ず捨てる
        // (捨てないと ServerLevel の参照をスライス枚数ぶん握ったままになる)。
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    /** 連続駆動を開始する。 既に別セッションが走っていればそれを止める。 */
    public void startAuto(ServerPlayer player, double rate) {
        if (active != null && active != this) {
            active.stopAuto();
        }
        this.rate = rate;
        this.driverId = player.getUUID();
        this.hudCountdown = 0;
        active = this;
    }

    public void stopAuto() {
        this.rate = 0.0;
        this.driverId = null;
        if (active == this) {
            active = null;
        }
    }

    /** 現在 auto が走っているセッション ({@code /bstep auto off} 用)。 */
    public static BStepSession activeSession() {
        return active;
    }

    /**
     * 毎ティックの駆動。
     *
     * <p>順序に意味がある: <b>期限切れの入力を落とす → w を進める → プレイヤーを地形に乗せる
     * → 進んだ結果を配る</b>。 配布を先にすると、 クライアントの観測面が常に 1 ティック古い w に
     * なる。 地形に乗せるのを w より先にすると、 1 ティック前の地表へ載せてしまう。
     */
    private static void onServerTick(MinecraftServer server) {
        WDriveInput.expire(server);
        driveFromInput(server);

        BStepSession auto = active;
        if (auto != null && auto.rate > 0.0 && !auto.drivenThisTick) {
            auto.tick(server, 0);
        }
        // 駆動していないレベルも追い付きだけは回す。 w を誰も触っていなくても、
        // アンロードされていたチャンクは古い w を焼いたまま戻ってくるため
        // (走査そのものの間引きは catchUp が判断する)。
        for (BStepSession session : SESSIONS.values()) {
            if (!session.drivenThisTick) {
                ServerPlayer centre = session.centrePlayer(server);
                if (centre != null) {
                    session.catchUp(server, centre, false);
                }
            }
            session.drivenThisTick = false;
        }

        // 地形に乗る。 適用が出なかったティックでも呼ぶ (SMOOTH の追従と、
        // 遅れていたチャンクが追い付いた場合のため)。
        WRide.tick(server);

        WStateSync.broadcast(server);

        // ティックの締め。 <b>この handler の最後</b>でなければならない。 ここまでの経過が
        // そのままティックの実周期になり、 それが「サーバーが締め切りに間に合ったか」を
        // 答える唯一の数字である ({@link TickPeak} の javadoc を参照)。
        TickPeak.endTick();
    }

    /**
     * キー入力による駆動 (本来の遊び方)。
     *
     * <p>w は世界の状態なので、 入力があったプレイヤーの<b>いるレベル</b>の w を動かす。
     * 同一レベルに複数人いれば向きの符号の和を採る ({@code WDriveInput})。
     */
    private static void driveFromInput(MinecraftServer server) {
        if (WDriveInput.isIdle()) {
            return;
        }
        for (ServerLevel candidate : server.getAllLevels()) {
            if (!SliceTeleporter.isSlice(candidate)) {
                continue;
            }
            int direction = WDriveInput.directionOf(candidate);
            if (direction == 0) {
                continue;
            }
            BStepSession session = of(candidate);
            if (session == null) {
                continue;
            }
            session.tick(server, direction);
        }
    }

    /**
     * このセッションの 1 ティック: <b>w を進める → 予算ぶんだけ追い付かせる</b>。
     *
     * <p>w は量子に丸めずそのまま進める。 「量子ぶん溜まったら一括で当てる」構造こそが
     * バーストの発生源だったので、 適用側から「ステップ」という単位を無くしてある
     * (クラス javadoc を参照)。 量子は {@link WScheduler} で「どれだけ遅れてよいか」の
     * 単位として生きている。
     *
     * @param inputDirection キー入力の向き ({@code -1} / {@code 0} / {@code +1})
     */
    private void tick(MinecraftServer server, int inputDirection) {
        drivenThisTick = true;
        if (rate > 0.0) {
            ServerPlayer driver = driverId == null ? null
                    : server.getPlayerList().getPlayer(driverId);
            if (driver == null || driver.level() != level) {
                // auto の実行者が居なくなった / 別ディメンションへ移った → 止める。
                stopAuto();
            }
        }

        ServerPlayer centre = centrePlayer(server);
        if (centre == null) {
            return;
        }

        double perTick = 0.0;
        if (rate > 0.0) {
            perTick += rate / ticksPerSecond(server);
        }
        if (inputDirection != 0) {
            perTick += inputDirection * BStepExperiment.W_RATE_PER_TICK;
        }

        if (perTick != 0.0) {
            setCurrentW(currentW + perTick);
        }
        catchUp(server, centre, perTick != 0.0);

        if (--hudCountdown <= 0) {
            hudCountdown = BStepExperiment.HUD_INTERVAL_TICKS;
            Component line = hudLine(server);
            // 動いているのは世界の w なので、 そのレベルにいる全員に見せる。
            for (ServerPlayer viewer : level.players()) {
                viewer.sendSystemMessage(line, true);
            }
        }
    }

    /**
     * 差分の中心にするプレイヤー。
     *
     * <p>{@code auto} の実行者を優先し、 居なければそのレベルの先頭のプレイヤー。
     * <b>1 人だけを中心に採る</b>のは、 対象範囲を人数ぶん広げると負荷が
     * 人数に比例して跳ね、 計測の意味が変わるため (v0.1 はシングルプレイ前提)。
     */
    private ServerPlayer centrePlayer(MinecraftServer server) {
        if (driverId != null) {
            ServerPlayer driver = server.getPlayerList().getPlayer(driverId);
            if (driver != null && driver.level() == level) {
                return driver;
            }
        }
        List<ServerPlayer> players = level.players();
        return players.isEmpty() ? null : players.get(0);
    }

    /** 1 秒あたりのティック数 (tickrate はコマンドで変えられるので固定 20 にしない)。 */
    private static double ticksPerSecond(MinecraftServer server) {
        float tickrate = server.tickRateManager().tickrate();
        return tickrate > 0.0f ? tickrate : 20.0;
    }

    // ── 光の追随 ────────────────────────────────────────────────

    /**
     * 適用から光が落ち着くまでの遅延を測る。
     *
     * <p>全チャンクに future を張ると 1 ステップで数百本になるので、
     * <b>実行者のチャンク 1 本だけ</b>を代表として測る。
     *
     * <p>サーバースレッドを {@code join()} で塞いではならない (光タスクの駆動が
     * サーバースレッドのチャンクタスクループに依存しており、 自分の待っている相手を
     * 止めてデッドロックする)。 継続を {@code server.execute} へ渡す。
     */
    private void probeLight(ChunkPos centre) {
        if (lightProbeInFlight) {
            return;
        }
        lightProbeInFlight = true;
        long start = System.nanoTime();
        MinecraftServer server = level.getServer();
        level.getChunkSource().getLightEngine()
                .waitForPendingTasks(centre.x(), centre.z())
                .thenRun(() -> server.execute(() -> {
                    lightProbeInFlight = false;
                    lightLatency.addLast(System.nanoTime() - start);
                    while (lightLatency.size() > BStepExperiment.HISTORY_SIZE) {
                        lightLatency.removeFirst();
                    }
                }));
    }

    // ── 計測履歴 ────────────────────────────────────────────────

    private void record(BStepRunner.StepResult result) {
        history.addLast(result);
        while (history.size() > BStepExperiment.HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    public int historySize() {
        return history.size();
    }

    public int clearHistory() {
        int n = history.size();
        history.clear();
        lightLatency.clear();
        return n;
    }

    private interface Phase {
        long of(BStepRunner.StepResult r);
    }

    private long median(Phase phase) {
        if (history.isEmpty()) {
            return 0L;
        }
        List<Long> values = new ArrayList<>(history.size());
        for (BStepRunner.StepResult r : history) {
            values.add(phase.of(r));
        }
        Collections.sort(values);
        int n = values.size();
        return (n % 2 == 1) ? values.get(n / 2)
                : (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
    }

    private long max(Phase phase) {
        long best = 0L;
        for (BStepRunner.StepResult r : history) {
            best = Math.max(best, phase.of(r));
        }
        return best;
    }

    private long medianOf(List<Long> sorted) {
        int n = sorted.size();
        if (n == 0) {
            return 0L;
        }
        return (n % 2 == 1) ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /**
     * 光の遅延が<b>単調増加</b>していないか。
     *
     * <p>増加し続けているならライトエンジンのキューが飽和している = 破綻。
     * 履歴を前半 / 後半に割って中央値を比べる (単発の跳ねに反応しないように)。
     */
    private boolean lightFallingBehind() {
        int n = lightLatency.size();
        if (n < 8) {
            return false;
        }
        List<Long> all = new ArrayList<>(lightLatency);
        List<Long> first = new ArrayList<>(all.subList(0, n / 2));
        List<Long> second = new ArrayList<>(all.subList(n / 2, n));
        Collections.sort(first);
        Collections.sort(second);
        long a = medianOf(first);
        long b = medianOf(second);
        // 「2 倍以上に伸びている」を飽和の目安にする (絶対値ではなく傾向で見る)。
        return a > 0 && b > a * 2;
    }

    // ── 報告 ────────────────────────────────────────────────────

    /**
     * アクションバー 1 行 (連続駆動中に人間が目で追うためのもの)。
     *
     * <p><b>時間の意味を必ず併記すること。</b> ここには性質の違う 3 つの時間が並んでいる:
     * {@code avg100} = 100 ティック平均 (バニラ・我々の仕事を含まない)、
     * {@code 占有} = この mod の単発の実時間のウィンドウ最大、
     * {@code 周期} = ティック間隔の実時間のウィンドウ最大。 混同すると
     * 「平均が小さいから単発も小さい」という誤った読みに戻る。
     */
    public Component hudLine(MinecraftServer server) {
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        double targetMs = server.tickRateManager().millisecondsPerTick();
        return Component.translatable("hyperslice.bstep.hud",
                fmt(currentW), fmt(phase()),
                num(mspt), num(tps(server)),
                num(TickPeak.peakOccupancyNanos() / 1_000_000.0),
                num(TickPeak.peakSpacingNanos() / 1_000_000.0),
                TickPeak.overrunTicks(targetMs),
                last == null ? 0 : last.blocks(),
                last == null ? 0 : last.chunks(),
                last == null ? 0 : last.chunksDeferred(),
                fmt(last == null ? 0.0 : last.residualLagW()),
                num(median(BStepRunner.StepResult::diffNs) / 1_000_000.0),
                num(median(BStepRunner.StepResult::applyNs) / 1_000_000.0),
                num(medianLight() / 1_000_000.0),
                Component.translatable(lightFallingBehind()
                        ? "hyperslice.bstep.light.behind" : "hyperslice.bstep.light.ok"));
    }

    /** チャットへ出す詳細行 (単発 / 停止時)。 */
    public List<Component> reportLines(MinecraftServer server, BStepRunner.StepResult r) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("hyperslice.bstep.header",
                fmt(r.fromW()), fmt(r.toW()), fmt(r.phase()),
                r.chunks(), r.chunksSkipped()));
        lines.add(Component.translatable("hyperslice.bstep.changed",
                r.blocks(), r.columns(), r.sections()));
        lines.add(Component.translatable("hyperslice.bstep.schedule",
                bandSummary(r.updatedPerBand()), r.chunksDeferred(), fmt(r.residualLagW()),
                num(WScheduler.budgetMs())));
        lines.add(Component.translatable("hyperslice.bstep.timings",
                num(r.diffNs() / 1_000_000.0), num(r.applyNs() / 1_000_000.0),
                num(r.total() / 1_000_000.0)));
        lines.add(Component.translatable("hyperslice.bstep.median", history.size(),
                num(median(BStepRunner.StepResult::diffNs) / 1_000_000.0),
                num(max(BStepRunner.StepResult::diffNs) / 1_000_000.0),
                num(median(BStepRunner.StepResult::applyNs) / 1_000_000.0),
                num(max(BStepRunner.StepResult::applyNs) / 1_000_000.0)));
        // ピークは平均と<b>別の行</b>に出す。 同じ行に混ぜると、 かつてのように
        // 「MSPT が小さいから単発も小さい」と読まれる。
        double targetMs = server.tickRateManager().millisecondsPerTick();
        lines.add(Component.translatable("hyperslice.bstep.peak",
                TickPeak.samples(),
                num(TickPeak.peakOccupancyNanos() / 1_000_000.0),
                num(TickPeak.peakSpacingNanos() / 1_000_000.0),
                TickPeak.overrunTicks(targetMs), num(targetMs),
                num(TickPeak.vanillaPeakNanos(server) / 1_000_000.0),
                TickPeak.vanillaOverrunTicks(server, targetMs)));
        lines.add(Component.translatable("hyperslice.bstep.server",
                num(server.getAverageTickTimeNanos() / 1_000_000.0), num(tps(server)),
                lightLatency.isEmpty()
                        ? Component.translatable("hyperslice.bstep.light.none")
                        : Component.translatable("hyperslice.bstep.light.value",
                                num(medianLight() / 1_000_000.0),
                                Component.translatable(lightFallingBehind()
                                        ? "hyperslice.bstep.light.behind"
                                        : "hyperslice.bstep.light.ok"))));
        return lines;
    }

    /**
     * 距離帯ごとの更新数を 1 つの部品にまとめる。
     *
     * <p>帯の本数を lang のプレースホルダ数に焼き付けないため、 ここで組み立てる
     * ({@code WScheduler} の表を人間が 3 帯や 5 帯に変えても文字列側は無改修で通る)。
     */
    private static Component bandSummary(int[] updatedPerBand) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < updatedPerBand.length; i++) {
            if (i > 0) {
                out.append(Component.literal("  "));
            }
            int max = WScheduler.bandMaxDistance(i);
            out.append(max == Integer.MAX_VALUE
                    ? Component.translatable("hyperslice.bstep.band.far", updatedPerBand[i])
                    : Component.translatable("hyperslice.bstep.band", max, updatedPerBand[i]));
        }
        return out;
    }

    private long medianLight() {
        List<Long> values = new ArrayList<>(lightLatency);
        Collections.sort(values);
        return medianOf(values);
    }

    /**
     * TPS。 バニラは TPS を直接持っていないので MSPT から導く。
     *
     * <p>{@code getAverageTickTimeNanos()} は直近 100 ティックの移動平均
     * ({@code TICK_STATS_SPAN = 100}。 javap で確認)。 目標レートは
     * {@code tickRateManager().tickrate()} (コマンドで変えられるので 20 固定にしない)。
     */
    private static double tps(MinecraftServer server) {
        float target = server.tickRateManager().tickrate();
        double msptTarget = server.tickRateManager().millisecondsPerTick();
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        return Math.min(target, 1000.0 / Math.max(mspt, msptTarget));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** 全セッションを捨てる (サーバー停止時)。 */
    private static void clear() {
        SESSIONS.clear();
        active = null;
        WDriveInput.clear();
        WStateSync.clear();
        TickPeak.clear();
    }
}
