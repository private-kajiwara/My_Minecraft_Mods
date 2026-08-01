package com.kajiwara.hyperslice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 更新スケジューラの位相オフセットの性質。
 *
 * <p>ここが壊れると実機では<b>「遠方に輪状の段差が周期的に走る」</b>あるいは
 * 「斜めの縞・市松模様が出る」という形でしか現れない。 目視でしか分からない壊れ方は
 * 起動せずに検査できるようにしておく。
 */
class WPhaseTest {

    /** 実際に使われうる周期 ({@code WScheduler.BAND_QUANTA} の想定域)。 */
    private static final int[] PERIODS = { 2, 3, 4, 8, 16 };

    private static final int SPAN = 64;   // -32..31 チャンク四方

    @Test
    @DisplayName("同じ座標は常に同じ位相 (ステップごとに抽選し直さない)")
    void deterministic() {
        for (int period : PERIODS) {
            for (int x = -SPAN / 2; x < SPAN / 2; x++) {
                for (int z = -SPAN / 2; z < SPAN / 2; z++) {
                    assertEquals(WPhase.offsetOf(x, z, period), WPhase.offsetOf(x, z, period));
                }
            }
        }
    }

    @Test
    @DisplayName("オフセットは必ず [0, period) に入る (負座標でも)")
    void offsetInRange() {
        for (int period : PERIODS) {
            for (int x = -SPAN / 2; x < SPAN / 2; x++) {
                for (int z = -SPAN / 2; z < SPAN / 2; z++) {
                    int offset = WPhase.offsetOf(x, z, period);
                    assertTrue(offset >= 0 && offset < period,
                            "offset " + offset + " out of [0," + period + ") at " + x + "," + z);
                }
            }
        }
    }

    @Test
    @DisplayName("周期 1 以下は毎ステップ更新 (近傍帯)")
    void periodOneIsAlwaysDue() {
        for (long step = 0; step < 16; step++) {
            assertTrue(WPhase.dueAt(3, -7, 1, step));
            assertTrue(WPhase.dueAt(3, -7, 0, step));
        }
    }

    @Test
    @DisplayName("各チャンクはちょうど period ステップに 1 回だけ更新される")
    void exactlyOncePerPeriod() {
        for (int period : PERIODS) {
            for (int x = -8; x < 8; x++) {
                for (int z = -8; z < 8; z++) {
                    int due = 0;
                    for (long step = 0; step < period; step++) {
                        if (WPhase.dueAt(x, z, period, step)) {
                            due++;
                        }
                    }
                    assertEquals(1, due,
                            "chunk " + x + "," + z + " period " + period + " fired " + due + " times");
                }
            }
        }
    }

    @Test
    @DisplayName("負のステップ番号でも破綻しない (floorMod であること)")
    void negativeStepIndex() {
        for (int period : PERIODS) {
            for (int x = -8; x < 8; x++) {
                int due = 0;
                for (long step = -period; step < 0; step++) {
                    if (WPhase.dueAt(x, 5, period, step)) {
                        due++;
                    }
                }
                assertEquals(1, due, "chunk " + x + ",5 period " + period);
            }
        }
    }

    /**
     * <b>負荷の平坦さ</b>: どのステップでも「今回更新される遠方チャンク」の数が
     * 全体の {@code 1/period} 付近に収まること。
     *
     * <p>これが崩れると、 特定のステップだけクライアントの仕事が跳ねる
     * (= 周期的なカクつき) という形で出る。 許容は理想値の ±25%。
     */
    @Test
    @DisplayName("どのステップでも更新数が 1/period 付近に収まる (負荷が平坦)")
    void loadIsFlatAcrossSteps() {
        int total = SPAN * SPAN;
        for (int period : PERIODS) {
            double ideal = total / (double) period;
            for (long step = 0; step < period; step++) {
                int due = 0;
                for (int x = -SPAN / 2; x < SPAN / 2; x++) {
                    for (int z = -SPAN / 2; z < SPAN / 2; z++) {
                        if (WPhase.dueAt(x, z, period, step)) {
                            due++;
                        }
                    }
                }
                assertTrue(due > ideal * 0.75 && due < ideal * 1.25,
                        "period " + period + " step " + step + ": " + due
                                + " chunks due, expected around " + ideal);
            }
        }
    }

    /**
     * <b>同心円にならないこと</b> — 実機で一番目立つ破綻を直接狙った検査。
     *
     * <p>位相が距離だけで決まってしまう (あるいは定数になる) と、 同じ Chebyshev
     * リング上のチャンクが全部同じステップで更新され、 <b>輪がまとめて動く</b>ように見える。
     * これを禁じる。
     *
     * <p>「全位相を使い切ること」は<b>要求しない</b>。 リングの周長が周期に対して短いとき
     * (例: r=4 の周 32 チャンクに対し周期 16)、 使われない位相が出るのは
     * まともなハッシュでも普通に起きることで、 品質の指標にならない。 負荷の平坦さは
     * {@link #loadIsFlatAcrossSteps} が広い母数で見ている。
     */
    @Test
    @DisplayName("同じ距離リングの位相が揃わない (輪状の段差にならない)")
    void ringsAreNotInPhase() {
        for (int period : PERIODS) {
            for (int radius = 4; radius <= 24; radius++) {
                int[] count = new int[period];
                int ringSize = 0;
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                            continue;   // Chebyshev リングの周だけ
                        }
                        count[WPhase.offsetOf(x, z, period)]++;
                        ringSize++;
                    }
                }

                int distinct = 0;
                int biggest = 0;
                for (int c : count) {
                    if (c > 0) {
                        distinct++;
                    }
                    biggest = Math.max(biggest, c);
                }

                assertTrue(distinct >= 2,
                        "ring r=" + radius + " period=" + period
                                + " is single-phase: the whole ring would update at once");
                assertTrue(biggest < ringSize * 0.75,
                        "ring r=" + radius + " period=" + period + ": one phase holds "
                                + biggest + " of " + ringSize + " chunks");
            }
        }
    }

    /**
     * <b>市松模様にならないこと</b>: 隣接チャンクが必ず違う位相 / 必ず同じ位相に
     * なるような規則性が無いこと。 素の {@code x + z} や {@code x ^ z} を使うと
     * ここで落ちる。
     */
    @Test
    @DisplayName("隣接チャンクの位相に規則性が無い (縞・市松にならない)")
    void neighboursAreNotCorrelated() {
        for (int period : PERIODS) {
            int same = 0;
            int pairs = 0;
            for (int x = -SPAN / 2; x < SPAN / 2 - 1; x++) {
                for (int z = -SPAN / 2; z < SPAN / 2; z++) {
                    if (WPhase.offsetOf(x, z, period) == WPhase.offsetOf(x + 1, z, period)) {
                        same++;
                    }
                    pairs++;
                }
            }
            double ratio = same / (double) pairs;
            double ideal = 1.0 / period;
            assertTrue(ratio > ideal * 0.6 && ratio < ideal * 1.4,
                    "period " + period + ": adjacent chunks share a phase " + ratio
                            + " of the time, expected around " + ideal);
        }
    }

    // ── タイブレーク (優先度方式の中核で使う役割) ──────────────

    @Test
    @DisplayName("タイブレーク値は同じ座標なら常に同じ (ティックごとに抽選し直さない)")
    void scatterIsDeterministic() {
        for (int x = -SPAN / 2; x < SPAN / 2; x++) {
            for (int z = -SPAN / 2; z < SPAN / 2; z++) {
                assertEquals(WPhase.scatter(x, z), WPhase.scatter(x, z));
            }
        }
    }

    /**
     * <b>これがタイブレークに求める唯一の性質</b>: 大小関係が空間的に散っていること。
     *
     * <p>{@code /bstep to <w>} の直後や大量ロード直後は、 同じ帯のチャンクの遅れが
     * <b>厳密に同着</b>になる。 このときの順序が収集順 (ラスタ走査) だと、 予算で切った
     * 上位 K 個が<b>空間的に連続した帯</b>になり、 更新の走査線が地形を舐めていくように見える。
     *
     * <p>ここでは「全体の 1/8 を取る」という現実的な予算比で切り、 選ばれた集合が
     * 四分割のどこにも偏らないこと、 および隣接チャンクが一緒に選ばれる率が
     * 偶然 (1/8) の範囲に収まる (= 塊にならない) ことを検査する。
     */
    @Test
    @DisplayName("同着を割った上位集合が空間的に散る (走査線にならない)")
    void tieBreakIsSpatiallyScattered() {
        int half = SPAN / 2;
        int total = SPAN * SPAN;
        int take = total / 8;

        // scatter の昇順で上位 take 個を選ぶ = 実装のタイブレークと同じ順序。
        int[] keys = new int[total];
        int n = 0;
        for (int x = -half; x < half; x++) {
            for (int z = -half; z < half; z++) {
                keys[n++] = WPhase.scatter(x, z);
            }
        }
        int[] sorted = keys.clone();
        java.util.Arrays.sort(sorted);
        int threshold = sorted[take - 1];

        boolean[][] chosen = new boolean[SPAN][SPAN];
        int[] quadrant = new int[4];
        int chosenCount = 0;
        for (int x = -half; x < half; x++) {
            for (int z = -half; z < half; z++) {
                if (WPhase.scatter(x, z) > threshold) {
                    continue;
                }
                chosen[x + half][z + half] = true;
                chosenCount++;
                quadrant[(x < 0 ? 0 : 1) + (z < 0 ? 0 : 2)]++;
            }
        }

        double perQuadrant = chosenCount / 4.0;
        for (int q = 0; q < 4; q++) {
            assertTrue(quadrant[q] > perQuadrant * 0.75 && quadrant[q] < perQuadrant * 1.25,
                    "quadrant " + q + " holds " + quadrant[q] + " of " + chosenCount
                            + " selected chunks, expected around " + perQuadrant);
        }

        int adjacentPairs = 0;
        int pairs = 0;
        for (int x = 0; x < SPAN - 1; x++) {
            for (int z = 0; z < SPAN; z++) {
                if (chosen[x][z]) {
                    pairs++;
                    if (chosen[x + 1][z]) {
                        adjacentPairs++;
                    }
                }
            }
        }
        double ratio = adjacentPairs / (double) pairs;
        assertTrue(ratio < 0.25,
                "selected chunks clump together: " + ratio
                        + " of them have a selected east neighbour (chance would be 0.125)");
    }
}
