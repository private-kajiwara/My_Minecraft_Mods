package com.kajiwara.hyperslice.bswap;

/**
 * <b>【診断実験】</b> 方式B の最小実験 ({@code /bswap}) のスイッチと摘み。
 *
 * <h2>これは方式B の実装ではない</h2>
 * 方式B (単一ディメンションのままブロックを書き換え、 継ぎ目のない w 移動を実現する方式) の
 * <b>最も危ない仮定 1 つだけ</b>を叩いて実測値を得るための使い捨てコード。
 * 「B を全部作ってから性能問題に気づく」ことを避けるための投資であり、
 * <b>ここから B を育ててはならない</b>。
 *
 * <h2>叩く仮定</h2>
 * 「ロード済みチャンクのブロックを丸ごと別の w の地形に差し替え、 再ライティングして
 * クライアントへ再送する」ことが、 <b>現実的な時間で・光が正しく乗った状態で</b>できるか。
 *
 * <p>知りたいのは 2 点だけ:
 * <ol>
 *   <li>光が正しく乗るか (乗らない・遅延してちらつくなら B の設計を見直す必要がある)</li>
 *   <li>1 回あたり何 ms か、 範囲を広げたときにどうスケールするか</li>
 * </ol>
 *
 * <h2>要求水準 (実験から逆算した実数値)</h2>
 * 観測面の移動レートは実機で {@code 0.4 w/秒} が妥当と判定された
 * ({@code ObserverW.RATE_PER_TICK})。 w 量子化を 1/8 ブロックにすると
 * {@code 0.4 / 0.125 = 3.2 回/秒} → <b>約 300ms に 1 回</b>ロード済み範囲全体を
 * 差し替えることになる。 これが B の性能要件。
 *
 * <h2>捨て方</h2>
 * {@link #EXPERIMENT_ENABLED} を {@code false} にすれば、 コマンドは登録されず
 * 挙動は実験前と完全に一致する ({@code static final boolean} なので javac が定数畳み込みし、
 * {@code HyperSliceCommands} のコンパイル結果から {@code bswap} への参照が 0 件になる)。
 * 完全に消すなら {@code mods/hyperslice/README.md} の「実験を捨てる手順」を見ること。
 *
 * <h2>観測面 w の実験 ({@code ObserverW}) との関係</h2>
 * <b>独立</b>。 フラグを共有していないので片方だけ捨てられる。 これは意図的で、
 * {@code ObserverW} 側は「B が正しく動いているかを判定するときの唯一の既知の正解」として
 * B より長く残す必要があるため。
 */
public final class BSwapExperiment {

    // =================================================================
    // ── 実験フラグ (コンパイル時に消えるのはこれだけ) ──
    // =================================================================

    /**
     * この実験全体のスイッチ。
     *
     * <p>{@code false} にすると {@code /bswap} は登録されない (= 実験前と完全に一致)。
     */
    public static final boolean EXPERIMENT_ENABLED = true;

    // =================================================================
    // ── 測定モード (実行時に切り替える) ──
    // =================================================================
    //   ここを static final にしないのは意図的。 「両方を測って比べる」のが目的なので、
    //   モードを変えるたびにリビルドと再起動を強いると測定そのものが回らなくなる。
    //   コンパイル時の可逆性は EXPERIMENT_ENABLED 一本が担保する。

    /**
     * 生成 (ブロック配列の作成) をスレッドプールで並列化するか。
     *
     * <p>{@code HyperTerrain} は純粋関数で MC の状態に一切触らないため、
     * チャンク単位の生成は安全に並列化できる。 サーバースレッドで行うのは
     * 差し替えと送信のみ。
     *
     * <p>{@code /bswap gen <parallel|sequential>} で切り替える。
     */
    private static volatile boolean parallelGeneration = true;

    /**
     * ライティングの完了を待ってから送信するか。
     *
     * <p><b>26.1.2 ではサーバー側の光は同期実行できない</b>
     * ({@code ThreadedLevelLightEngine.runLightUpdates()} は
     * {@code UnsupportedOperationException("Ran automatically on a different thread!")} を
     * 無条件に投げる。 javap で確認済み)。 したがって選べるのは次の 2 つだけ:
     *
     * <ul>
     *   <li>{@code true}  … {@code lightChunk} → {@code waitForPendingTasks} の完了後に送る。
     *       <b>光は正しく乗るが、 その待ち時間が丸ごと遅延になる</b>。 その ms を測る。</li>
     *   <li>{@code false} … 待たずに即送る。 送信までは最短だが
     *       <b>クライアントには旧地形の光が乗った新地形が一瞬見える</b>。 ちらつきを観察する。</li>
     * </ul>
     *
     * <p>{@code /bswap light <wait|nowait>} で切り替える。
     */
    private static volatile boolean waitForLight = true;

    // =================================================================
    // ── 上限 ──
    // =================================================================

    /**
     * {@code /bswap <w> [radius]} の半径上限 [チャンク]。
     *
     * <p>スケーリングの測定に使う想定は 0 / 1 / 2 (= 1 / 9 / 25 チャンク)。
     * 3 (49 チャンク) まで許すが、 それ以上はサーバースレッドを長時間占有するため許さない。
     */
    public static final int MAX_RADIUS = 3;

    /** 中央値を出すための履歴の長さ [回]。 */
    public static final int HISTORY_SIZE = 16;

    private BSwapExperiment() {
    }

    public static boolean parallelGeneration() {
        return parallelGeneration;
    }

    public static void setParallelGeneration(boolean value) {
        parallelGeneration = value;
    }

    public static boolean waitForLight() {
        return waitForLight;
    }

    public static void setWaitForLight(boolean value) {
        waitForLight = value;
    }
}
