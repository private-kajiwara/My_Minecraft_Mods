package com.kajiwara.hyperslice.bstep;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import com.kajiwara.hyperslice.core.HyperTerrain;
import com.kajiwara.hyperslice.slice.SliceTeleporter;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

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
 *   <li>レベルごとに 1 つの「今の w」。 初期値はそのスライス本来の整数 w</li>
 *   <li>さらに<b>チャンクごとに</b>「そのチャンクが今どの w の地形か」を持つ。
 *       {@link WeakHashMap} のキーは {@link LevelChunk} の<b>参照同一性</b>
 *       ({@code LevelChunk} は {@code equals}/{@code hashCode} を上書きしていない。 javap で確認)
 *       なので、 アンロード → 再ロードで別インスタンスになったチャンクは
 *       自動的に「本来の整数 w」へ戻る。 これが無いと、 途中でロードされたチャンクだけ
 *       永久にずれた地形が残り {@code /hyperslice n} との比較が壊れる</li>
 * </ul>
 *
 * <h2>スケジューラとの関係 — 追い付きは per-chunk w がそのまま担う</h2>
 * {@link WScheduler} が見送ったチャンクは w が遅れる。 次に順番が来たときに当てるのは
 * 「1 ステップぶん」ではなく<b>そのチャンクの w から今の w までの蓄積分</b>である。
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

    /** チャンクごとの「今の地形 w」。 未登録なら {@link #nominalW}。 */
    private final WeakHashMap<LevelChunk, Double> chunkW = new WeakHashMap<>();

    /**
     * このセッションが出した通算ステップ数。 {@link WScheduler} の位相の基準。
     *
     * <p>単発 ({@code /bstep <delta>}) では増やさない。 単発はスケジューラを通さず
     * 全チャンクを更新するので、 位相の基準を動かす意味がないうえ、
     * 動かすと連続駆動の位相がコマンドを撃つたびにずれる。
     */
    private long stepIndex;

    // ── auto の状態 ────────────────────────────────────────────

    private double rate;
    /** まだステップに変換していない w の残り。 */
    private double pending;
    /**
     * 出し切れずに捨てた w の合計。
     *
     * <p>1 ティックに出すステップは<b>最大 1 回</b>に固定してある (負荷を青天井にしないため)。
     * したがって {@code rate / STEP_QUANTUM} が tickrate を超えると出し切れない。
     * 既定 (量子 0.125・20 TPS) では <b>2.5 w/秒</b>が上限。 これを超えたぶんを黙って
     * 溜めると「w は進んでいるのに地形が追いつかない」状態が測定値に見えないまま進むので、
     * 捨てて<b>捨てた量を報告する</b>。
     */
    private double droppedW;
    private java.util.UUID driverId;
    private int hudCountdown;

    /**
     * このティックで既にキー入力によって駆動済みか。
     *
     * <p>キー入力と {@code /bstep auto} が同じレベルで同時に走っているとき、
     * 1 ティックに 2 回 {@link #tick} を呼ぶと量子が 2 回出てしまう。
     * キー入力側が先に走り、 その場合 auto 側の呼び出しを飛ばす
     * (レートはキー入力側の {@link #tick} が合算して扱っている)。
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
        this.currentW = nominalW;
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

    // ── 単発ステップ ────────────────────────────────────────────

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
     * <p><b>単発ではスケジューラを通さない。</b> 通すと遠方が更新されないまま残り、
     * 「{@code /bstep 3.0} してから {@code /hyperslice 3} と見比べる」という
     * 正しさの検証手段が壊れる。 スケジューラが効くのは連続駆動のときだけ。
     */
    public BStepRunner.StepResult stepTo(ServerPlayer player, double targetW) {
        return apply(player, targetW, false);
    }

    /**
     * 差分を適用する。
     *
     * @param scheduled {@code true} なら {@link WScheduler} で今回更新するチャンクを絞る
     */
    private BStepRunner.StepResult apply(ServerPlayer player, double targetW, boolean scheduled) {
        HyperTerrain terrain = BStepRunner.terrainOf(level);
        if (terrain == null) {
            return null;
        }

        int[] skipped = new int[1];
        ChunkPos centre = ChunkPos.containing(player.blockPosition());
        List<BStepRunner.Candidate> candidates = BStepRunner.collectCandidates(
                level, centre, BStepExperiment.radius(),
                chunk -> chunkW.getOrDefault(chunk, (double) nominalW), skipped);
        if (candidates.isEmpty()) {
            return null;
        }

        WScheduler.Selection selection = WScheduler.select(
                candidates, stepIndex, scheduled && BStepExperiment.scheduler(), targetW);

        double fromW = currentW;
        BStepRunner.StepResult result = BStepRunner.step(
                level, selection, terrain, sliceCount, fromW, targetW, skipped[0]);

        currentW = targetW;
        // 見送ったチャンクの w は<b>進めない</b>。 進めると蓄積分が失われ、
        // そのチャンクは永久にずれた地形のまま残る。
        for (BStepRunner.Target target : selection.targets()) {
            chunkW.put(target.chunk(), targetW);
        }
        if (scheduled) {
            stepIndex++;
        }

        record(result);
        probeLight(centre);
        return result;
    }

    // ── 駆動 (キー入力 / auto) ──────────────────────────────────

    /**
     * イベント登録。 {@code HyperSliceCommands} からフラグ判定つきで 1 回だけ呼ぶ。
     *
     * <p>誰も w を動かしていないときの {@code END_SERVER_TICK} は、
     * {@code WDriveInput.isIdle()} と {@code active == null} と
     * {@code WStateSync} のプレイヤー走査 (値が変わっていなければ何も送らない) だけで
     * 抜ける。 全レベル走査は入力があるときにしか起きない。
     */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BStepSession::onServerTick);
        // ディメンションキーはワールドを作り直しても同じなので、 サーバー停止時に必ず捨てる
        // (捨てないと ServerLevel の参照をスライス枚数ぶん握ったままになる)。
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    /** 連続ステップを開始する。 既に別セッションが走っていればそれを止める。 */
    public void startAuto(ServerPlayer player, double rate) {
        if (active != null && active != this) {
            active.stopAuto();
        }
        this.rate = rate;
        this.pending = 0.0;
        this.droppedW = 0.0;
        this.driverId = player.getUUID();
        this.hudCountdown = 0;
        active = this;
    }

    public void stopAuto() {
        this.rate = 0.0;
        this.pending = 0.0;
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
     * <p>順序に意味がある: <b>期限切れの入力を落とす → w を進める → 進んだ結果を配る</b>。
     * 配布を先にすると、 クライアントの観測面が常に 1 ティック古い w になる。
     */
    private static void onServerTick(MinecraftServer server) {
        WDriveInput.expire(server);
        driveFromInput(server);

        BStepSession auto = active;
        if (auto != null && auto.rate > 0.0 && !auto.drivenThisTick) {
            auto.tick(server, 0);
        }
        for (BStepSession session : SESSIONS.values()) {
            session.drivenThisTick = false;
        }

        WStateSync.broadcast(server);
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
            session.drivenThisTick = true;
        }
    }

    /**
     * このセッションの 1 ティック。
     *
     * @param inputDirection キー入力の向き ({@code -1} / {@code 0} / {@code +1})
     */
    private void tick(MinecraftServer server, int inputDirection) {
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
            pending += perTick;
            // 量子は符号つきで取り出す (キー入力は負方向にも進む)。
            if (Math.abs(pending) >= BStepExperiment.STEP_QUANTUM) {
                double quantum = Math.copySign(BStepExperiment.STEP_QUANTUM, pending);
                pending -= quantum;
                apply(centre, currentW + quantum, true);
                if (Math.abs(pending) >= BStepExperiment.STEP_QUANTUM) {
                    // 出し切れないぶんは溜めずに捨てる (droppedW の説明を参照)。
                    droppedW += Math.abs(pending);
                    pending = 0.0;
                }
            }
        }

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
        droppedW = 0.0;
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

    /** アクションバー 1 行 (連続ステップ中に人間が目で追うためのもの)。 */
    public Component hudLine(MinecraftServer server) {
        BStepRunner.StepResult last = history.peekLast();
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        return Component.translatable("hyperslice.bstep.hud",
                fmt(currentW), fmt(phase()),
                num(mspt), num(tps(server)),
                last == null ? 0 : last.blocks(),
                last == null ? 0 : last.chunks(),
                last == null ? 0 : last.chunksDeferred(),
                fmt(last == null ? 0.0 : last.maxLagW()),
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
                bandSummary(r.updatedPerBand()), r.chunksDeferred(), fmt(r.maxLagW())));
        lines.add(Component.translatable("hyperslice.bstep.timings",
                num(r.diffNs() / 1_000_000.0), num(r.applyNs() / 1_000_000.0),
                num(r.total() / 1_000_000.0)));
        lines.add(Component.translatable("hyperslice.bstep.median", history.size(),
                num(median(BStepRunner.StepResult::diffNs) / 1_000_000.0),
                num(max(BStepRunner.StepResult::diffNs) / 1_000_000.0),
                num(median(BStepRunner.StepResult::applyNs) / 1_000_000.0),
                num(max(BStepRunner.StepResult::applyNs) / 1_000_000.0)));
        lines.add(Component.translatable("hyperslice.bstep.server",
                num(server.getAverageTickTimeNanos() / 1_000_000.0), num(tps(server)),
                lightLatency.isEmpty()
                        ? Component.translatable("hyperslice.bstep.light.none")
                        : Component.translatable("hyperslice.bstep.light.value",
                                num(medianLight() / 1_000_000.0),
                                Component.translatable(lightFallingBehind()
                                        ? "hyperslice.bstep.light.behind"
                                        : "hyperslice.bstep.light.ok"))));
        if (droppedW > 0.0) {
            lines.add(Component.translatable("hyperslice.bstep.dropped", fmt(droppedW),
                    num(BStepExperiment.STEP_QUANTUM * server.tickRateManager().tickrate())));
        }
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
    }
}
