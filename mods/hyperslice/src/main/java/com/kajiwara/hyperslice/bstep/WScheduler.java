package com.kajiwara.hyperslice.bstep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.kajiwara.hyperslice.core.WPhase;

/**
 * <b>【方式B 中核】</b> 更新スケジューラ — 毎ティック<b>どのチャンクをどれだけ</b>追い付かせるかを決める。
 *
 * <h2>「ステップ」という単位は適用側に存在しない</h2>
 * w は毎ティック連続に進む。 適用も<b>毎ティック</b>、 時間予算の範囲で行う。
 * かつては量子 (0.125) が溜まるたびに 1 回まとめて適用していたが、 これは
 * 「6.25 ティックに 1 回、 数百チャンクを一括で処理する」という構造で、
 * 実測でサーバースレッドを単発 81.97ms 占有し、 ティック実周期を 127.73ms へ伸ばしていた
 * (ティック予算は 50ms)。 <b>総仕事量ではなく、 その山が問題だった。</b>
 *
 * <p>実際、 平準化しても総量は変わらない。 半径 12 (625 チャンク)・0.02 w/tick での定常需要は
 * <pre>
 *   d 0..3    49 ch / 粒度 0.125 (= 6.25 tick に 1 回) →  7.84 ch/tick
 *   d 4..6   120 ch / 粒度 0.25  (= 12.5 tick に 1 回) →  9.60 ch/tick
 *   d 7..10  272 ch / 粒度 0.5   (= 25   tick に 1 回) → 10.88 ch/tick
 *   d 11..   184 ch / 粒度 1.0   (= 50   tick に 1 回) →  3.68 ch/tick
 *   ─────────────────────────────────────────────────────────────
 *                                                合計 約 32 ch/tick
 * </pre>
 * であり、 これは旧方式の「200 ch / 6.25 tick」と同じ値である。 <b>やっているのは平準化だけ。</b>
 *
 * <h2>機構は 2 つ — 距離から粒度、 遅れから優先度</h2>
 * <ol>
 *   <li>チャンクの距離帯から<b>許される w 粒度</b>を引く
 *       ({@link #BAND_MAX_DISTANCE} / {@link #BAND_QUANTA})</li>
 *   <li>毎ティック {@code lag / granularity} の<b>降順</b>に、 時間予算の範囲で取る。
 *       1 以上が「更新すべき」</li>
 * </ol>
 *
 * <p><b>優先度が遅れの比であることが要点。</b> 遅れているチャンクほど優先度が上がるので、
 * 一度落ちこぼれたチャンクが二度と選ばれない (餓死する) ことが構造的に起きない。
 * 半径外へ出ていた・アンロードされていた・{@code BLOCK_TICKING} を割っていた等の理由で
 * <b>候補集合から抜けていた</b>チャンクは大きな遅れを抱えて戻ってくるが、 まさにその遅れが
 * 最優先の根拠になり、 戻ってきたティックで拾われる。
 *
 * <h2>同着のタイブレークに座標ハッシュを使う</h2>
 * 平常時は各チャンクの遅れがばらけるので順序は自然に散る。 しかし<b>再同期する経路</b>がある:
 * {@code /bstep to <w>} は全チャンクを即時適用するので、 その瞬間すべての遅れが 0 に揃う
 * (大量ロード直後も同じ)。 同じ帯のチャンクは同じ速さで遅れを積むため、 一斉に閾値へ届く。
 * このとき同着をコレクション順 (= ラスタ走査順) で割ると、 毎ティック<b>空間的に連続した
 * チャンク</b>が選ばれ、 更新の走査線が地形を舐めていくように見える。
 * {@link WPhase#scatter} で割ればこれが消える (コストは乗算 2 回)。
 *
 * <h2>予算不足時の劣化のしかた</h2>
 * {@code :common:wDiff} の実測でセクション数は delta にほぼ非依存 (0.125 で 2〜4、
 * 1.0 でも 3〜4)、 ブロック数も delta 8 倍で 3.6 倍にしかならない。 つまり<b>遅れたチャンクの
 * 追い付きコストは遅れていないチャンクとほぼ同じ</b>なので、 予算が足りなくても仕事が
 * 雪だるま式に増えることはない。 系は「遠方の更新頻度が落ちて遅れが大きい所で釣り合う」
 * という形で静かに劣化する。
 */
public final class WScheduler {

    // =================================================================
    // ── 人間が触る定数 (ここが調整レバーの全て) ──
    // =================================================================

    /**
     * <b>1 ティックに適用へ使ってよい時間 [ms]。 最重要の摘み。</b>
     *
     * <p>実機での合格条件は「ティック実周期の最大が 50ms 前後 (締め切り超過 0)」かつ
     * 「最大遅れが最遠帯の粒度 (既定 1.0) 前後で安定」。 <b>後者が満たされないなら
     * ここが足りていない</b>ので、 この値を上げるか {@link #BAND_QUANTA} の最遠を粗くする。
     * この 2 つが調整レバーである。
     *
     * <p>上限の目安: ティック予算 50ms からバニラのティック本体を引いた残りが天井。
     * 8.0 は「予算の 16%」という控えめな出発点で、 <b>足りるかどうかは実機で測るまで
     * 分からない</b> (需要 32 ch/tick に対し何 ch/tick 出せるかは 1 チャンクの単価次第で、
     * 単価は遅れと地形の位相で数倍変動する)。 実機で追い込めるよう
     * {@code /bstep budget <ms>} で実行時にも変えられる。
     */
    public static final double TICK_BUDGET_MS = 8.0;

    /**
     * 1 ティックに適用するチャンク数の上限。 <b>時間予算の副次的な保険</b>。
     *
     * <p>時間計測が何らかの理由で機能しなくなったとき (計時の粒度が粗い環境など) に
     * 青天井になるのを止めるためだけにある。 既定 64 は上記の定常需要 32 ch/tick の 2 倍で、
     * 遅れを取り戻す余地を残しつつ暴走は止まる値。 <b>通常はここが効いてはいけない</b>
     * (効いているなら時間予算より先にこちらが当たっているということなので、
     * {@code /bstep} の報告で適用数がちょうど 64 に張り付く)。
     */
    public static final int MAX_CHUNKS_PER_TICK = 64;

    /**
     * 差分計算をまとめる単位 [チャンク]。
     *
     * <p>予算で打ち切るとき、 <b>計算済みで適用しなかった差分は捨てる</b>ことになる。
     * 全対象の差分を先にまとめて計算すると捨てる量が大きくなり、 1 チャンクずつ計算すると
     * 並列化 ({@code BStepRunner} のワーカープール) が効かない。 その折り合いがこの値で、
     * <b>無駄になりうる計算の上限が 1 バッチぶん</b>になる。
     */
    public static final int DIFF_BATCH_CHUNKS = 16;

    /**
     * 何もしていないときに候補を数え直す間隔 [tick]。
     *
     * <p>w が動いておらず、 前回の走査で追い付いていない対象も無ければ、 毎ティック
     * 625 回の {@code getChunkNow} を回す意味は無い。 ただし完全に止めてはいけない:
     * <b>アンロードされていたチャンクは古い w を焼いたまま戻ってくる</b> ({@link ChunkW})
     * ので、 誰も w を触っていなくても拾いに行く必要がある。 既定 20 = 1 秒。
     */
    public static final int IDLE_RESCAN_TICKS = 20;

    /**
     * 距離帯の上限 [チャンク]。 <b>Chebyshev 距離</b> ({@code max(|dx|, |dz|)}) で測る。
     *
     * <p>Chebyshev なのは {@code BStepRunner.collectCandidates} が正方形に走査しているため
     * (ユークリッドにすると帯が対象範囲の角で切れて、 角のチャンクだけ最遠帯に落ちる)。
     *
     * <p>{@link #BAND_QUANTA} と<b>同じ長さ・同じ順序</b>で、 昇順であること。
     * 最後の要素は {@link Integer#MAX_VALUE} (= それ以上すべて) にすること。
     */
    public static final int[] BAND_MAX_DISTANCE = { 3, 6, 10, Integer.MAX_VALUE };

    /**
     * 距離帯ごとに許す w 粒度 — <b>{@link BStepExperiment#STEP_QUANTUM} の何個ぶんまで
     * 遅れてよいか</b>。 {@code 1} = 量子 1 個ぶん (= 最も細かい)。
     *
     * <p>既定値では
     * <pre>
     *   d 0..3    粒度 1 x 0.125 = 0.125 w
     *   d 4..6    粒度 2 x 0.125 = 0.25  w
     *   d 7..10   粒度 4 x 0.125 = 0.5   w
     *   d 11..    粒度 8 x 0.125 = 1.0   w
     * </pre>
     * となり、 <b>遠方ほど w が階段状に飛ぶ</b> = 局所的な w 量子の粗化である。
     * グローバルな量子 ({@link BStepExperiment#STEP_QUANTUM}) は変えない。
     * 近傍の滑らかさはそのまま保たれ、 遠方だけが実質的に粗くなる。
     *
     * <p><b>予算が足りないときのもう一方の調整レバー</b>がここ。 最遠を 8 → 16 にすれば
     * 最遠帯の需要が半分になる (代わりに最遠帯の地形は最大 2.0 w 古くなる)。
     *
     * <p>この表は<b>旧「更新周期 [ステップ]」の表と数値が同一</b>である。 周期 8 ステップに
     * 1 回 = 遅れの上限 8 量子であり、 意味の読み替えだけで済んでいる。
     */
    public static final int[] BAND_QUANTA = { 1, 2, 4, 8 };

    // =================================================================

    static {
        if (BAND_MAX_DISTANCE.length != BAND_QUANTA.length || BAND_QUANTA.length == 0) {
            throw new IllegalStateException(
                    "BAND_MAX_DISTANCE and BAND_QUANTA must be the same non-zero length");
        }
    }

    /**
     * 実行時の時間予算 [ms]。 既定は {@link #TICK_BUDGET_MS}。
     *
     * <p>{@code static final} にしないのは意図的 ({@code BStepExperiment} の実行時モードと
     * 同じ理由)。 合格判定は「予算を変えて最大遅れを読む」の往復なので、
     * リビルドと再起動を挟むと測定が回らない。
     */
    private static volatile double budgetMs = TICK_BUDGET_MS;

    private WScheduler() {
    }

    // ── 予算 ────────────────────────────────────────────────────

    public static double budgetMs() {
        return budgetMs;
    }

    public static void setBudgetMs(double value) {
        budgetMs = value;
    }

    /** 予算 [ns]。 {@code System.nanoTime()} に足して締め切りにする。 */
    public static long budgetNanos() {
        return (long) (budgetMs * 1_000_000.0);
    }

    // ── 帯 ──────────────────────────────────────────────────────

    /** 帯の本数 (報告の桁を帯の数に追従させるため公開している)。 */
    public static int bandCount() {
        return BAND_QUANTA.length;
    }

    /** 帯 {@code i} の上限距離。 最遠帯は {@link Integer#MAX_VALUE}。 */
    public static int bandMaxDistance(int band) {
        return BAND_MAX_DISTANCE[band];
    }

    /** Chebyshev 距離 {@code d} が属する帯の添字。 */
    public static int bandOf(int chebyshevDistance) {
        for (int i = 0; i < BAND_MAX_DISTANCE.length; i++) {
            if (chebyshevDistance <= BAND_MAX_DISTANCE[i]) {
                return i;
            }
        }
        return BAND_MAX_DISTANCE.length - 1;
    }

    /**
     * その帯が許す w 粒度 [w]。 これを超えて遅れたチャンクが「更新すべき」になる。
     *
     * @param bands {@code false} なら帯の表を無視して全帯を {@link BStepExperiment#STEP_QUANTUM}
     *              にする ({@code /bstep schedule off} = 距離別の粗化に意味があるかの A/B)
     */
    public static double granularityOf(int chebyshevDistance, boolean bands) {
        int quanta = bands ? BAND_QUANTA[bandOf(chebyshevDistance)] : 1;
        return BStepExperiment.STEP_QUANTUM * (quanta < 1 ? 1 : quanta);
    }

    // ── 選抜 ────────────────────────────────────────────────────

    /**
     * このティックの<b>優先度順の対象一覧</b>を作る。 実際に何個まで当てるかは予算が決める。
     *
     * @param candidates 対象になりうるチャンク (距離つき)
     * @param targetW    今の w。 各チャンクの遅れはここからの差
     * @param bands      距離帯の表を効かせるか ({@code /bstep schedule})
     */
    public static Plan plan(List<BStepRunner.Candidate> candidates, double targetW, boolean bands) {
        List<Ranked> ranked = new ArrayList<>();
        double maxLagNotDue = 0.0;

        for (BStepRunner.Candidate candidate : candidates) {
            double lag = Math.abs(targetW - candidate.currentW());
            double priority = lag / granularityOf(candidate.distance(), bands);
            if (priority < 1.0) {
                // 粒度の範囲内 = 設計上「今は合っている」。 遅れの最大には数える
                // (最遠帯は常に粒度いっぱいまで遅れうるので、 健全な値がそのまま出る)。
                maxLagNotDue = Math.max(maxLagNotDue, lag);
                continue;
            }
            ranked.add(new Ranked(
                    new BStepRunner.Target(candidate.chunk(), candidate.currentW(),
                            bandOf(candidate.distance())),
                    priority,
                    WPhase.scatter(candidate.chunk().getPos().x(), candidate.chunk().getPos().z())));
        }

        // 遅れの比の降順。 同着 (/bstep to 直後など) は座標ハッシュで割る。
        ranked.sort(Comparator.comparingDouble(Ranked::priority).reversed()
                .thenComparingInt(Ranked::scatter));

        List<BStepRunner.Target> due = new ArrayList<>(ranked.size());
        for (Ranked r : ranked) {
            due.add(r.target());
        }
        return new Plan(due, maxLagNotDue);
    }

    /** 優先度つきの対象 (整列のためだけの内部形)。 */
    private record Ranked(BStepRunner.Target target, double priority, int scatter) {
    }

    /**
     * このティックの対象一覧と、 対象外だったチャンクの最大の遅れ。
     *
     * @param due          優先度の降順に並んだ「更新すべき」チャンク
     * @param maxLagNotDue 粒度の範囲内だったチャンクの最大の遅れ [w]
     */
    public record Plan(List<BStepRunner.Target> due, double maxLagNotDue) {
    }

    /**
     * <b>追い付けていない遅れ</b> [w] — 実機での合格判定に使う数字。
     *
     * <p>「このティックで<b>当てなかった</b>チャンクの遅れの最大」である。 全候補の最大では
     * ないことが要点で、 半径外から大きな遅れを抱えて戻ってきたチャンクは最優先で拾われて
     * その場で 0 になるため、 ここには出ない。 <b>本当に予算が足りていないときだけ伸びる。</b>
     *
     * <p>健全な値は<b>最遠帯の粒度 ({@link #BAND_QUANTA} の最後 x 量子・既定 1.0)</b> 前後。
     * 粒度の範囲内のチャンクは当てないので、 遅れが 0 に張り付くことはない (それが正常)。
     *
     * @param applied 先頭から実際に当てた個数
     */
    public static double residualLag(Plan plan, int applied, double targetW) {
        double worst = plan.maxLagNotDue();
        List<BStepRunner.Target> due = plan.due();
        for (int i = applied; i < due.size(); i++) {
            worst = Math.max(worst, Math.abs(targetW - due.get(i).currentW()));
        }
        return worst;
    }

    /** 距離帯ごとの<b>実際に当てた</b>数 (スケジューラが効いているかを人間が読むための数字)。 */
    public static int[] bandCounts(List<BStepRunner.Target> due, int applied) {
        int[] counts = new int[BAND_QUANTA.length];
        for (int i = 0; i < applied; i++) {
            counts[due.get(i).band()]++;
        }
        return counts;
    }
}
