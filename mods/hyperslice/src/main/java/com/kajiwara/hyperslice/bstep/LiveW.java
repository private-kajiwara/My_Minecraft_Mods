package com.kajiwara.hyperslice.bstep;

import com.kajiwara.hyperslice.worldgen.HyperSliceChunkGenerator;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * <b>【方式B 中核】</b> ChunkGenerator に「今の w」を渡す差し込み口の実装。
 *
 * <h2>これが解いている問題</h2>
 * 生成器はディメンションに焼かれた<b>整数</b> w でチャンクを作っていた。 そのため
 * 歩いて新しいチャンクが読み込まれるたびに「一瞬だけ違う w の地形が見えて、 次の更新で直る」
 * = <b>描画距離の縁が常時ちらつく</b>状態だった。 生成時点で今の w を読めば、
 * その追い付き自体が起きない。
 *
 * <h2>スレッド安全性</h2>
 * ワールド生成は<b>ワーカースレッド</b>で走る。 したがって:
 * <ul>
 *   <li>書き手は<b>サーバースレッドだけ</b> ({@link BStepSession} が w を進めたときに {@link #set})</li>
 *   <li>読み手はワーカー多数。 読むのは {@code volatile double} 1 個だけ</li>
 * </ul>
 * ロックもキューも要らない。 <b>生成中に w が進んだ場合</b>は、 そのチャンクは
 * 「読んだ瞬間の w」で作られ、 その値が {@link ChunkW} に記録されるので、
 * 次の更新で既存の差分機構が蓄積分をそのまま当てて整合する
 * ({@code BStepDiff.compute} は delta の大きさに依存しない)。
 *
 * <h2>方式A との関係</h2>
 * この差し込み口は {@code BStepExperiment.EXPERIMENT_ENABLED} が {@code true} のときしか
 * 生成器へ渡されない。 {@code false} なら生成器の {@code wSource} は永久に {@code null} で、
 * 生成は Codec の整数 w のまま = <b>方式A の正解データが保たれる</b>。
 */
final class LiveW implements HyperSliceChunkGenerator.WSource {

    /** 書き手はサーバースレッドのみ、 読み手は生成ワーカー多数。 */
    private volatile double w;

    LiveW(double initial) {
        this.w = initial;
    }

    /** サーバースレッドから。 {@link BStepSession} が w を進めるたびに呼ぶ。 */
    void set(double value) {
        this.w = value;
    }

    @Override
    public double w() {
        return w;
    }

    @Override
    public void record(ChunkAccess chunk, double generatedW) {
        ChunkW.set(chunk, generatedW);
    }
}
