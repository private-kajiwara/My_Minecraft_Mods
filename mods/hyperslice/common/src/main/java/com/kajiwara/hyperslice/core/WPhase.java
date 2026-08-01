package com.kajiwara.hyperslice.core;

/**
 * 更新スケジューラの<b>空間的な散らし</b> — チャンク座標から決定論的に撹拌された値を作る。
 *
 * <h2>用途は 2 つある (現在の主役は 1 番目)</h2>
 * <ol>
 *   <li><b>{@link #scatter} — 同着のタイブレーク。</b> 更新スケジューラは
 *       {@code lag / granularity} の降順にチャンクを取るが、 この値が<b>厳密に同着</b>に
 *       なる経路がある ({@code /bstep to <w>} は全チャンクを即時適用するので、 その瞬間
 *       すべての遅れが 0 に揃う。 大量ロード直後も同じ)。 同着をコレクション順
 *       (= ラスタ走査順) で割ると、 毎ティック<b>空間的に連続したチャンク</b>が選ばれ、
 *       更新の走査線が地形を舐めていくように見える。 ハッシュ順で割ればこれが消える</li>
 *   <li>{@link #offsetOf} / {@link #dueAt} — <b>周期方式</b>の位相オフセット。
 *       「遠方は period ステップに 1 回」という形で更新を間引いていた頃の中核。
 *       現在の中核ループは時間予算つきの優先度方式に変わったので<b>ホットパスには居ない</b>が、
 *       撹拌の質を検査する {@code WPhaseTest} の対象であり、 周期方式の参照実装として残す</li>
 * </ol>
 *
 * <h2>「乱数」ではなく「決定論的な写像」であること</h2>
 * ティックごとに抽選し直してはならない。 運悪く長く選ばれないチャンクが出て遅れが偏るし、
 * 同じチャンクの更新間隔が一定にならない。 同じ座標は<b>常に同じ値</b>である必要がある。
 *
 * <h2>MC 非依存にしてある理由</h2>
 * ここは純粋な整数演算で、 撹拌が甘いと「斜めの縞」「市松模様」として実機で見える。
 * 目視でしか分からない壊れ方をするものは、 起動せずに検査できる場所に置く
 * ({@code HyperTerrain} を common に隔離したのと同じ理由)。 呼び出し側の
 * {@code WScheduler} は距離帯の表と {@code ChunkPos} との橋渡しだけを持つ。
 */
public final class WPhase {

    private WPhase() {
    }

    /**
     * そのチャンクの<b>撹拌された順序値</b>。 優先度が同着のときのタイブレークに使う。
     *
     * <p>値そのものに意味は無く、 <b>大小関係が空間的に散っていること</b>だけが要件
     * ({@code WPhaseTest.tieBreakIsSpatiallyScattered})。 コストは乗算 2 回と
     * シフト XOR で、 毎ティック数百回呼んでも無視できる。
     */
    public static int scatter(int chunkX, int chunkZ) {
        return mix(chunkX, chunkZ);
    }

    /**
     * そのチャンクの位相オフセット {@code [0, period)}。
     *
     * @param period 更新周期 [ステップ]。 1 以下なら常に 0 (毎ステップ更新なので意味を持たない)
     */
    public static int offsetOf(int chunkX, int chunkZ, int period) {
        if (period <= 1) {
            return 0;
        }
        return Math.floorMod(mix(chunkX, chunkZ), period);
    }

    /** そのチャンクが {@code stepIndex} 回目のステップで更新される順番か。 */
    public static boolean dueAt(int chunkX, int chunkZ, int period, long stepIndex) {
        if (period <= 1) {
            return true;
        }
        return Math.floorMod(stepIndex - offsetOf(chunkX, chunkZ, period), period) == 0;
    }

    /**
     * 座標 → 撹拌された整数。
     *
     * <p>素の {@code x + z} や {@code x ^ z} では、 周期の約数と座標の周期が噛み合って
     * 斜めの縞や市松模様として見えてしまう。 奇数の大きな乗数と右シフト XOR で
     * 下位ビットまで散らす (well-known な整数ファイナライザ形)。
     */
    static int mix(int x, int z) {
        int h = x * 0x9E3779B9 ^ z * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2545F491;
        h ^= h >>> 13;
        return h;
    }
}
