package com.kajiwara.hyperslice.slice;

import com.kajiwara.hyperslice.bstep.BStepExperiment;
import com.kajiwara.hyperslice.bstep.BStepSession;
import com.kajiwara.hyperslice.core.CrossSection;

import net.minecraft.server.level.ServerLevel;

/**
 * <b>そのレベルの今の w</b> — 地形とエンティティが共有する唯一の入口。
 *
 * <h2>なぜ 1 箇所に集めるのか</h2>
 * 方式B の要点は「地形の w とエンティティの観測面が<b>同一の値</b>であること」である。
 * 呼び出し側がそれぞれ w を求めると、 片方だけ更新し忘れた瞬間に
 * 「地形は動いたのにエンティティの断面が動かない」という、 実機では
 * 原因が非常に見えにくい壊れ方をする。 だから求め方はここにしか書かない。
 *
 * <h2>権威の実体は {@code BStepSession}</h2>
 * w は {@link BStepSession} が {@code ServerLevel} ごとに 1 つ持つ (per-chunk の
 * 追い付き表と対になっているため、 そこが唯一の自然な置き場所)。 このクラスは
 * その参照を隠す薄い窓でしかない。
 *
 * <h2>可逆性</h2>
 * {@link BStepExperiment#EXPERIMENT_ENABLED} が {@code false} のときは
 * <b>方式A の挙動へ完全に戻る</b>: w はそのディメンション本来の整数、 観測面は
 * {@code slice + 0.5}。 {@code static final boolean} なので javac が定数畳み込みし、
 * その場合このクラスから {@code BStepSession} への実行コード参照は 0 件になる。
 */
public final class LevelW {

    private LevelW() {
    }

    /**
     * 今の地形 w。 HyperSlice のスライスでなければ {@link Double#NaN}。
     *
     * <p>まだ一度も w を動かしていないレベルには {@link BStepSession} が無いので、
     * そのディメンション本来の整数 w を返す (セッションを作る副作用は起こさない)。
     */
    public static double terrainW(ServerLevel level) {
        int slice = SliceTeleporter.sliceWOf(level);
        if (slice < 0) {
            return Double.NaN;
        }
        if (BStepExperiment.EXPERIMENT_ENABLED) {
            BStepSession session = BStepSession.peek(level);
            if (session != null) {
                return session.currentW();
            }
        }
        return slice;
    }

    /**
     * 今の観測超平面 w。 HyperSlice のスライスでなければ {@link Double#NaN}。
     *
     * <p>方式B では地形 w と一致する ({@link CrossSection#planeForTerrainW} の javadoc に
     * なぜ {@code +0.5} が付かないかを書いてある)。 方式B を無効にすると方式A の
     * {@code slice + 0.5} に戻る。
     */
    public static double observationPlane(ServerLevel level) {
        int slice = SliceTeleporter.sliceWOf(level);
        if (slice < 0) {
            return Double.NaN;
        }
        if (BStepExperiment.EXPERIMENT_ENABLED) {
            return CrossSection.planeForTerrainW(terrainW(level));
        }
        return CrossSection.observationPlane(slice);
    }
}
