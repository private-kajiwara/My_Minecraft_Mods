package com.kajiwara.hyperslice.core;

/**
 * 更新スケジューラの<b>位相オフセット</b> — チャンク座標から「周期のどこで更新するか」を決める。
 *
 * <h2>なぜ位相オフセットが要るのか</h2>
 * 遠方のチャンクを {@code period} ステップに 1 回だけ更新すると決めたとき、 全チャンクを
 * <b>同じタイミング</b>で更新すると、 周期ごとに「同心円状の段差がまとめて動く」という
 * 非常に目に付く破綻が起きる (負荷も {@code period} ティックに 1 回だけ跳ねる)。
 *
 * <p>座標から決まるオフセットで散らせば、 毎ステップ遠方の {@code 1/period} ずつが
 * 更新される。 負荷が平坦になり、 地形の食い違いも輪ではなく<b>ノイズとして分散</b>する。
 *
 * <h2>「乱数」ではなく「決定論的な写像」であること</h2>
 * ステップごとに抽選し直してはならない。 運悪く長く選ばれないチャンクが出て遅れが偏るし、
 * 同じチャンクの更新間隔が一定にならない。 同じ座標は<b>常に同じ位相</b>である必要がある。
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
