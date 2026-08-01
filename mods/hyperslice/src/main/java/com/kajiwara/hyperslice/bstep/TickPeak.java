package com.kajiwara.hyperslice.bstep;

import net.minecraft.server.MinecraftServer;

/**
 * <b>ピークティック時間の計測。</b> 平均では見えない<b>単発のスパイク</b>を人間に見せるためにある。
 *
 * <h2>なぜ MSPT では駄目なのか (ここが今回の要点)</h2>
 * {@code MinecraftServer.getAverageTickTimeNanos()} は<b>直近 100 ティックの移動平均</b>
 * ({@code TICK_STATS_SPAN = 100}) なので、 135ms のティックが 1 つ混じっても
 * 平均には 1.35ms しか乗らない。 「MSPT が 1.50 だから 135ms は実占有ではない」
 * という読み方は<b>誤り</b>である。 平均と単発は別の量である。
 *
 * <h2>さらに厄介なこと — バニラの計測窓は我々の仕事を含んでいない</h2>
 * 26.1.2 の {@code MinecraftServer.tickServer} を逆アセンブルすると、
 * <pre>
 *   0:   startNanos = Util.getNanos()            ← 計測開始
 *   103: tickCount++
 *   120: tickChildren(...)                        ← 世界のティック
 *   185: sample = Util.getNanos() - startNanos    ← 計測終了 (profiler "tallying")
 *   192: tickTimesNanos[tickCount % 100] = sample
 *   274: return                                   ← ここに Mixin の TAIL が入る
 * </pre>
 * であり、 Fabric の {@code ServerTickEvents.END_SERVER_TICK} は
 * {@code MinecraftServerMixin} が {@code @At("TAIL")} で刺している
 * (fabric-lifecycle-events-v1 のソースで確認)。 つまり<b>我々の差分適用は
 * 計測が締め切られたあとに走る</b>。 コマンド経路も同様で、
 * {@code processPacketsAndTick} が {@code packetProcessor.processQueuedPackets()} を
 * {@code tickServer()} の<b>外側</b>で呼ぶ。
 *
 * <p>したがって <b>{@code getTickTimesNanos()} の最大値をいくら見ても、
 * この mod が使った時間は 1ns も現れない</b>。 これは実装漏れではなくバニラの計測窓の位置である。
 * 我々の占有を知りたければ<b>自分で測る</b>しかない。
 *
 * <h2>3 つの数字を別々に出す (混同しないための唯一の手段)</h2>
 * <ol>
 *   <li><b>単発の実時間</b> {@link #peakOccupancyNanos()} …… この mod が 1 ティックの中で
 *       サーバースレッドを占有した実時間の、 ウィンドウ内最大。
 *       {@code BStepRunner.StepResult.total()} をティックごとに足したもの</li>
 *   <li><b>ティック間隔</b> {@link #peakSpacingNanos()} …… {@code END_SERVER_TICK} から
 *       次の {@code END_SERVER_TICK} までの実時間の、 ウィンドウ内最大。
 *       <b>これが唯一「サーバーが締め切りに間に合ったか」を答える数字</b>である。
 *       サーバーは目標間隔 (既定 50ms) までは寝るので、 余裕があるかぎりこの値は
 *       50ms に張り付く。 <b>50ms を超えたぶんがそのまま遅延</b>である</li>
 *   <li><b>バニラのティック計測</b> {@link #vanillaPeakNanos} …… 上記のとおり
 *       <b>この mod の仕事を含まない</b>。 チャンク生成やオートセーブなど
 *       「我々以外の」スパイクを見るための対照</li>
 * </ol>
 *
 * <p>1 と 2 の関係が判定の要である:
 * <ul>
 *   <li>1 が大きく 2 も 50ms を超える → サーバーが締め切りを落としている</li>
 *   <li>1 が大きいが 2 は 50ms のまま → サーバーは間に合っている。 それでもカクつくなら
 *       原因は<b>同時に届く大量のセクション更新パケット</b>によるクライアント側の再構築</li>
 *   <li>1 が小さい → サーバー側の適用は原因ではない</li>
 * </ul>
 *
 * <h2>ウィンドウ</h2>
 * {@link #WINDOW_TICKS} = 100 ティック = 約 5 秒。 バニラの {@code TICK_STATS_SPAN} に
 * 合わせてある。 <b>一度出たスパイクは 5 秒間ウィンドウに残る</b>ので、
 * 「直したか」を見るときは 5 秒静かにしてから読むこと。
 *
 * <p>状態はサーバー 1 つぶんの {@code static}。 {@code BStepSession} の他の {@code static}
 * と同じくサーバースレッドからのみ触り、 サーバー停止時に {@link #clear()} で捨てる。
 */
public final class TickPeak {

    /** 計測ウィンドウ [tick]。 バニラの {@code TICK_STATS_SPAN} と同じ 100 = 約 5 秒。 */
    public static final int WINDOW_TICKS = 100;

    /** ティック間隔の実時間 [ns] のリングバッファ。 */
    private static final long[] SPACING_NANOS = new long[WINDOW_TICKS];

    /** この mod が 1 ティックで占有した実時間 [ns] のリングバッファ。 */
    private static final long[] OCCUPANCY_NANOS = new long[WINDOW_TICKS];

    private static int cursor;
    private static int filled;

    /** 前回 {@link #endTick} を呼んだ時刻 [ns]。 {@code 0} なら未初期化。 */
    private static long lastEndNanos;

    /** このティックでまだ書き出していない占有時間 [ns]。 */
    private static long occupancyThisTick;

    private TickPeak() {
    }

    // ── 記録 ────────────────────────────────────────────────────

    /**
     * この mod がサーバースレッドを占有した時間を積む。
     *
     * <p>1 ティックに複数回呼ばれても足し合わせる (単発コマンドと連続駆動が
     * 同じティックに重なる場合がある)。 {@link #endTick} で書き出される。
     */
    public static void recordOccupancy(long nanos) {
        occupancyThisTick += nanos;
    }

    /**
     * 1 ティックの締め。 {@code END_SERVER_TICK} の<b>最後</b>に 1 回だけ呼ぶ。
     *
     * <p>ここまでにこのティックの仕事は済んでいるので、 前回の呼び出しからの経過時間が
     * そのまま<b>ティックの実周期</b>になる。
     */
    public static void endTick() {
        long now = System.nanoTime();
        if (lastEndNanos != 0L) {
            SPACING_NANOS[cursor] = now - lastEndNanos;
            OCCUPANCY_NANOS[cursor] = occupancyThisTick;
            cursor = (cursor + 1) % WINDOW_TICKS;
            if (filled < WINDOW_TICKS) {
                filled++;
            }
        }
        lastEndNanos = now;
        occupancyThisTick = 0L;
    }

    /** サーバー停止時に捨てる。 */
    public static void clear() {
        java.util.Arrays.fill(SPACING_NANOS, 0L);
        java.util.Arrays.fill(OCCUPANCY_NANOS, 0L);
        cursor = 0;
        filled = 0;
        lastEndNanos = 0L;
        occupancyThisTick = 0L;
    }

    // ── 読み出し (自前の計測) ──────────────────────────────────

    /** 有効なサンプル数 [tick]。 */
    public static int samples() {
        return filled;
    }

    /** ウィンドウ内で、 この mod が 1 ティックで占有した実時間の最大 [ns]。 */
    public static long peakOccupancyNanos() {
        return max(OCCUPANCY_NANOS);
    }

    /** ウィンドウ内のティック実周期の最大 [ns]。 */
    public static long peakSpacingNanos() {
        return max(SPACING_NANOS);
    }

    /**
     * ウィンドウ内で目標間隔を超えたティックの数。
     *
     * <p>ここが 0 でないティックは<b>サーバーが締め切りを落としている</b>。
     * 閾値は 50ms 固定ではなく {@code tickRateManager().millisecondsPerTick()} を渡すこと
     * ({@code /tick rate} で変えられるため)。
     *
     * <p>実周期は目標間隔ちょうどに張り付くので、 わずかな計測ゆらぎで数えすぎないよう
     * {@link #OVERRUN_MARGIN_MS} の余裕を持たせている。
     */
    public static int overrunTicks(double targetMs) {
        long threshold = (long) ((targetMs + OVERRUN_MARGIN_MS) * 1_000_000.0);
        int n = 0;
        for (int i = 0; i < filled; i++) {
            if (SPACING_NANOS[i] > threshold) {
                n++;
            }
        }
        return n;
    }

    /**
     * 「超えた」と数えるまでの余裕 [ms]。
     *
     * <p>サーバーの待ちは目標間隔ちょうどを狙うが、 OS のスケジューリングで
     * 常に 1ms 程度は上振れする。 この余裕が無いと全ティックが超過に数えられて
     * 数字の意味が消える。
     */
    public static final double OVERRUN_MARGIN_MS = 2.0;

    private static long max(long[] ring) {
        long best = 0L;
        for (int i = 0; i < filled; i++) {
            best = Math.max(best, ring[i]);
        }
        return best;
    }

    // ── 読み出し (バニラの計測。 我々の仕事を含まない) ────────

    /**
     * バニラのティック計測のウィンドウ内最大 [ns]。
     *
     * <p><b>この mod の仕事は含まれない</b> (クラス javadoc の逆アセンブル結果を参照)。
     * {@code getTickTimesNanos()} は内部配列の参照をそのまま返すので (26.1.2 で確認)、
     * ここでは読むだけで書き換えない。 未書き込みのスロットは 0 なので、
     * {@code getTickCount()} で有効長を絞る。
     */
    public static long vanillaPeakNanos(MinecraftServer server) {
        long[] ring = server.getTickTimesNanos();
        int n = validVanillaSamples(server, ring.length);
        long best = 0L;
        for (int i = 0; i < n; i++) {
            best = Math.max(best, ring[i]);
        }
        return best;
    }

    /** バニラのティック計測で目標間隔を超えたティックの数 (我々の仕事を含まない)。 */
    public static int vanillaOverrunTicks(MinecraftServer server, double targetMs) {
        long[] ring = server.getTickTimesNanos();
        int n = validVanillaSamples(server, ring.length);
        long threshold = (long) (targetMs * 1_000_000.0);
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (ring[i] > threshold) {
                count++;
            }
        }
        return count;
    }

    private static int validVanillaSamples(MinecraftServer server, int span) {
        return Math.min(span, Math.max(server.getTickCount(), 0));
    }
}
