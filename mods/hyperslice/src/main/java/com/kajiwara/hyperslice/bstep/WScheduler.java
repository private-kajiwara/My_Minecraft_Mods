package com.kajiwara.hyperslice.bstep;

import java.util.ArrayList;
import java.util.List;

import com.kajiwara.hyperslice.core.WPhase;

import net.minecraft.world.level.ChunkPos;

/**
 * <b>【方式B 中核】</b> 更新スケジューラ — チャンクごとに「どのステップで更新するか」を決める。
 *
 * <h2>何のためにあるか (実測から)</h2>
 * 律速は<b>サーバーではなくクライアントのチャンクメッシュ再構築</b>である。
 * 実測 (描画距離 12 / 625 チャンク / 最悪位相):
 * <pre>
 *   サーバー  MSPT 1.50 / TPS 20.00   (ティック予算 50ms に対し 100 倍の余裕)
 *   クライアント  描画距離 4 (約 81ch) は快適、 12 (625ch) は紙芝居
 * </pre>
 * 必要な削減は <b>3〜4 倍</b>。 サーバー側は何も足りていないので、
 * ここでやることは「クライアントへ渡す仕事量を減らす」ことだけ。
 *
 * <h2>機構は 1 つだけ — 距離から周期、 座標ハッシュから位相オフセット</h2>
 * <ol>
 *   <li>チャンクの距離帯から<b>更新周期</b>を引く ({@link #BAND_MAX_DISTANCE} / {@link #BAND_PERIOD})</li>
 *   <li>座標ハッシュから<b>位相オフセット</b>を与える ({@code hash(chunkPos) % period})</li>
 * </ol>
 *
 * <p><b>位相オフセットが必須</b>である理由: 遠方を一斉に更新すると、 周期ごとに
 * 「同心円状の段差がまとめて動く」という目に付く破綻が起きる。 座標ハッシュで散らせば
 * 毎ステップ遠方の {@code 1/period} ずつが更新され、 負荷が平坦になり、
 * 地形の食い違いも輪ではなくノイズとして分散する。
 *
 * <h2>設計上の含意 — これは「遠方に粗い w 量子を与える」ことと同義</h2>
 * 遠方の更新頻度を落とすことは、 そのチャンクの w が階段状に飛ぶことを意味する。
 * つまり<b>局所的な w 量子の粗化</b>である。 したがってグローバルな量子
 * ({@link BStepExperiment#STEP_QUANTUM} = 1/8) は<b>変えない</b>。
 * 近傍の滑らかさはそのまま保たれ、 遠方だけが実質的に粗くなる。
 *
 * <h2>バーストは存在しない (先読み・償却機構は不要)</h2>
 * 実測でセクション数は位相にほぼ依存しない:
 * <pre>
 *   位相 0.000 →  16 ブロック / 1.80 セクション
 *   位相 0.125 → 107 ブロック / 2.25 セクション
 *   位相 0.500 → 816 ブロック / 2.13 セクション
 * </pre>
 * ブロック数は 51 倍変動するがセクション数は 18% しか動かない (変化が地表に沿って
 * 薄く広がるため、 量ではなく空間的な広がりがコストを決める)。 クライアントの仕事は
 * セクション単位なので、 <b>位相を先読みして重いステップを償却する機構は要らない</b>。
 */
public final class WScheduler {

    // =================================================================
    // ── 人間が触る定数 (この 2 本の配列が最重要) ──
    // =================================================================

    /**
     * 距離帯の上限 [チャンク]。 <b>Chebyshev 距離</b> ({@code max(|dx|, |dz|)}) で測る。
     *
     * <p>Chebyshev なのは {@code BStepRunner.collectTargets} が正方形に走査しているため
     * (ユークリッドにすると帯が対象範囲の角で切れて、 角のチャンクだけ最遠帯に落ちる)。
     *
     * <p>{@link #BAND_PERIOD} と<b>同じ長さ・同じ順序</b>で、 昇順であること。
     * 最後の要素は {@link Integer#MAX_VALUE} (= それ以上すべて) にすること。
     */
    public static final int[] BAND_MAX_DISTANCE = { 3, 6, 10, Integer.MAX_VALUE };

    /**
     * 距離帯ごとの更新周期 [ステップ]。 {@code 1} = 毎ステップ更新。
     *
     * <p>既定値の根拠 (描画距離 12 = 半径 12 の正方形 625 チャンクでの見積り。
     * Chebyshev 距離 d のリングは d≥1 で 8d チャンク):
     * <pre>
     *   d 0..3    49 チャンク / 周期 1 → 毎ステップ 49
     *   d 4..6   120 チャンク / 周期 2 → 毎ステップ 60
     *   d 7..10  272 チャンク / 周期 4 → 毎ステップ 68
     *   d 11..12 184 チャンク / 周期 8 → 毎ステップ 23
     *   ───────────────────────────────────────────
     *   合計 625 → 毎ステップ 約 200 チャンク = 3.1 倍の削減
     * </pre>
     * 実測から必要とされた 3〜4 倍の下端。 <b>足りなければここを上げる</b>
     * (実機で確かめるのは「描画距離 12 で紙芝居が解消するか」と
     * 「遠方に輪状の段差が出ないか」の 2 点)。
     */
    public static final int[] BAND_PERIOD = { 1, 2, 4, 8 };

    // =================================================================

    static {
        if (BAND_MAX_DISTANCE.length != BAND_PERIOD.length || BAND_PERIOD.length == 0) {
            throw new IllegalStateException(
                    "BAND_MAX_DISTANCE and BAND_PERIOD must be the same non-zero length");
        }
    }

    private WScheduler() {
    }

    /** 帯の本数 (報告の桁を帯の数に追従させるため公開している)。 */
    public static int bandCount() {
        return BAND_PERIOD.length;
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

    /** その距離の更新周期 [ステップ]。 */
    public static int periodOf(int chebyshevDistance) {
        int p = BAND_PERIOD[bandOf(chebyshevDistance)];
        return p < 1 ? 1 : p;
    }

    /**
     * このチャンクが {@code stepIndex} 回目のステップで更新される順番か。
     *
     * <p>位相オフセットの算出は common 側の {@link WPhase} にある。 撹拌が甘いと
     * 「斜めの縞」「市松模様」という<b>目視でしか分からない壊れ方</b>をするので、
     * MC を起動せずに検査できる場所に置いてある ({@code WPhaseTest})。
     */
    public static boolean dueAt(ChunkPos pos, int chebyshevDistance, long stepIndex) {
        return WPhase.dueAt(pos.x(), pos.z(), periodOf(chebyshevDistance), stepIndex);
    }

    // ── 選抜 ────────────────────────────────────────────────────

    /**
     * このステップで実際に差分を当てるチャンクを選ぶ。
     *
     * @param candidates 対象になりうるチャンク (距離つき)
     * @param stepIndex  このセッションが出した通算ステップ数
     * @param enabled    {@code false} なら全チャンクを選ぶ (スケジューラの効果を実測で比べるため)
     * @param targetW    今回の目標 w。 遅れ量の算出に使う
     */
    public static Selection select(List<BStepRunner.Candidate> candidates,
                                   long stepIndex, boolean enabled, double targetW) {
        List<BStepRunner.Target> targets = new ArrayList<>(candidates.size());
        int[] updatedPerBand = new int[BAND_PERIOD.length];
        int deferred = 0;
        double maxLag = 0.0;

        for (BStepRunner.Candidate candidate : candidates) {
            // 遅れ量は「選ばれなかったもの」も含めた全候補で測る
            // (最も遅れているチャンクを人間に見せるのがこの数字の目的)。
            maxLag = Math.max(maxLag, Math.abs(targetW - candidate.currentW()));

            if (enabled && !dueAt(candidate.chunk().getPos(), candidate.distance(), stepIndex)) {
                deferred++;
                continue;
            }
            targets.add(new BStepRunner.Target(candidate.chunk(), candidate.currentW()));
            updatedPerBand[bandOf(candidate.distance())]++;
        }

        return new Selection(targets, deferred, updatedPerBand, maxLag);
    }

    /**
     * 選抜の結果。
     *
     * <p>{@code updatedPerBand} は距離帯ごとの更新数で、 <b>スケジューラが効いているか</b>を
     * 人間が読むための数字。 {@code maxLagW} は最も w が遅れているチャンクの遅れ量
     * ({@code |targetW - chunkW|}) で、 これが単調に増えていくならどこかで
     * 追いつけていないということ。
     */
    public record Selection(List<BStepRunner.Target> targets, int deferred,
                            int[] updatedPerBand, double maxLagW) {
    }
}
