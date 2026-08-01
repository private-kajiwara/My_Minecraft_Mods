package com.kajiwara.hyperslice.bstep;

import net.minecraft.world.level.block.Block;

/**
 * <b>【方式B 中核】</b> 差分適用ループ ({@code /bstep}) のスイッチと摘み。
 *
 * <h2>これは使い捨てではない</h2>
 * {@code /bswap} (フルセクション差し替え) が「最も危ない仮定を 1 つ叩く」使い捨てコードだったのに対し、
 * こちらは <b>方式B の中核ループそのもの</b>である。 実測
 * ({@code :common:wDiff}) により「毎ステップ全セクション再構築」ではなく
 * <b>差分適用</b>が正しいと数字で確定したため、 本実装の原型として書いてある。
 * ただし {@link #EXPERIMENT_ENABLED} による可逆性は維持する。
 *
 * <h2>実測から来ている前提 (推測ではない)</h2>
 * delta=0.125 / 1 チャンク (98,304 ブロック) あたり:
 * <ul>
 *   <li>変化ブロック数 中央値 412 / p95 1,297 / 最大 2,157</li>
 *   <li>変化セクション 常に 2〜4 / 24 (delta にほぼ非依存)</li>
 *   <li>変化した列 中央値 138 / 256 (= <b>約半数の列は完全に不変</b>)</li>
 *   <li>負荷は基準 w の<b>位相で約 50 倍変動</b> (格子点上 16 → セル中央 813)</li>
 * </ul>
 * 最後の 1 点があるため、 計測値は必ず位相と一緒に読むこと。
 *
 * <h2>捨て方</h2>
 * {@link #EXPERIMENT_ENABLED} を {@code false} にすれば {@code /bstep} は登録されず、
 * 挙動は導入前と完全に一致する ({@code static final boolean} なので javac が定数畳み込みし、
 * {@code HyperSliceCommands} のコンパイル結果から {@code bstep} への参照が 0 件になる)。
 * {@code ObserverW} / {@code BSwapExperiment} とはフラグを共有していないので、
 * <b>三者は独立に捨てられる</b>。
 */
public final class BStepExperiment {

    // =================================================================
    // ── 実験フラグ (コンパイル時に消えるのはこれだけ) ──
    // =================================================================

    /**
     * <b>方式B 全体のスイッチ。</b>
     *
     * <p><b>意味が変わっている。</b> 導入当初は「{@code /bstep} コマンドを登録するか」
     * だけだったが、 観測面 w の統合によって以下すべてを一括で切り替えるものになった:
     * <ul>
     *   <li>{@code /bstep} コマンドの登録</li>
     *   <li>w のサーバー権威 (キー入力の受信 + 各プレイヤーへの配布)</li>
     *   <li>更新スケジューラ ({@link WScheduler})</li>
     *   <li>観測面の規約 ({@code LevelW.observationPlane} が地形 w を返すか
     *       方式A の {@code slice + 0.5} を返すか)</li>
     * </ul>
     *
     * <p>{@code false} にすると挙動は方式B 導入前 (= 方式A) と完全に一致する。
     * {@code static final boolean} なので javac が定数畳み込みし、
     * {@code HyperSliceCommands} / {@code LevelW} のコンパイル結果から
     * {@code bstep} 側への実行コード参照が 0 件になる。
     */
    public static final boolean EXPERIMENT_ENABLED = true;

    // =================================================================
    // ── 人間が触る定数 ──
    // =================================================================
    //   <b>1 ティックの時間予算・チャンク数上限・距離帯の表は {@link WScheduler} にある。</b>
    //   カクつきを触るならまずそちら (時間予算 TICK_BUDGET_MS が最重要の摘み)。
    //   ここにあるのは「w をどう動かすか」と「どう書き込むか」の定数。

    /**
     * <b>{@code setBlockState} に渡すフラグ。 方式B の成否を左右する最重要の選択。</b>
     *
     * <p>値は {@code UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS} = {@code 2 | 816} = <b>818</b>。
     * これは<b>バニラ {@code /setblock <...> strict} が使うのと完全に同一の値</b>である
     * (26.1.2 の {@code SetBlockCommand} を逆アセンブルして確認: {@code iconst_2} …
     * {@code sipush 816} … {@code ior})。
     *
     * <h3>起こすこと</h3>
     * <ul>
     *   <li>{@code UPDATE_CLIENTS} … {@code sendBlockUpdated} → {@code ChunkHolder} が
     *       セクション単位に溜め、 毎ティック 1 回まとめて送る (下記「送信」参照)</li>
     *   <li>ハイトマップ 4 種の更新 … <b>フラグに関係なく</b>
     *       {@code LevelChunk.setBlockState} が無条件に行う</li>
     *   <li>差分ライティング … 同じく無条件。 {@code LightEngine.hasDifferentLightProperties}
     *       が真のときだけ {@code skyLightSources.update} + {@code lightEngine.checkBlock}。
     *       <b>石↔土のような不透明同士の差し替えでは光は動かない</b>
     *       (比較対象は lightDampening / lightEmission / useShapeForLightOcclusion のみ)</li>
     *   <li>セクションの空判定が変わったときの光への通知 … 同じく無条件</li>
     * </ul>
     *
     * <h3>抑止すること (これを外すと連続ステップで即破綻する)</h3>
     * <ul>
     *   <li><b>{@code UPDATE_NEIGHBORS} を立てない</b> … 近傍更新の連鎖 (レッドストーン・観察者)
     *       と {@code affectNeighborsAfterRemoval} が起きない</li>
     *   <li><b>{@code UPDATE_SKIP_ON_PLACE} (512)</b> … {@code state.onPlace} を呼ばない。
     *       <b>これが無いと砂が落下し水が流れ出す</b> ({@code FallingBlock} /
     *       {@code LiquidBlock} は {@code onPlace} で tick を予約する)。 生成地形は砂と水を
     *       含むので、 近傍更新を切っただけでは不十分</li>
     *   <li>{@code UPDATE_KNOWN_SHAPE} (16) … 形状伝播 (唯一の再帰経路) を打ち切る</li>
     *   <li>{@code UPDATE_SUPPRESS_DROPS} (32) … ドロップを出さない。 1 ステップで数百〜数千
     *       ブロックが消えるため、 出すとアイテムエンティティでサーバーが死ぬ</li>
     *   <li>{@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS} (256) … チェスト等の中身を撒かない
     *       (<b>黙って消える</b>。 使い捨てワールド前提)</li>
     * </ul>
     *
     * <h3>送信について</h3>
     * まとめる手段を自作していないのは、 <b>26.1.2 が自動でまとめるから</b>。
     * {@code ChunkHolder} が {@code changedBlocksPerSection: ShortSet[]} に溜め、
     * {@code ServerChunkCache.broadcastChangedChunks} が毎ティック 1 回
     * {@code broadcastChanges} を呼ぶ。 切替は<b>閾値ではなく size==1 かどうか</b>
     * (逆アセンブル実測): 1 個なら {@code ClientboundBlockUpdatePacket}、
     * 2 個以上なら {@code ClientboundSectionBlocksUpdatePacket} 1 個でセクション丸ごと。
     * <b>いずれにせよ「触れたセクション 1 個につきパケット 1 個」</b>に収束する。
     */
    public static final int SET_BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /**
     * w 量子 [スライス] — <b>チャンクが遅れてよい量の単位</b>。
     *
     * <p><b>意味が変わっている。</b> かつては「1 ステップで進める w の量」で、 w は
     * これを単位に飛んでいた。 今は w は毎ティック連続に進み、 この値は
     * {@link WScheduler#BAND_QUANTA} と掛け合わされて<b>「その帯のチャンクが今の w から
     * どれだけ遅れてよいか」</b>を決める (最近傍が 1 個ぶん = 0.125 w)。
     * 更新の頻度としては同じ粗さになるので、 既定値も測定との対応もそのまま。
     *
     * <p>{@code :common:wDiff} の測定が delta=0.125 を基準に取ってあるので、 既定を
     * それに合わせてある。 変えると測定値との対応が崩れるので、 変えたら
     * {@code :common:wDiff} も同じ delta で取り直すこと。
     */
    public static final double STEP_QUANTUM = 0.125;

    /**
     * <b>キーを押している間の w の増減レート [w/tick]。 体験の一次判定を決める最重要の摘み。</b>
     *
     * <p>速すぎると地形とエンティティが点滅しているようにしか見えず、 遅すぎると静止して
     * 見える。 既定 {@code 0.02} = <b>0.4 w/秒</b>は、 診断実験 {@code ObserverW} で
     * 「膨らむ球が円周を一周する」ことを実機確認した値そのもの
     * ({@code HyperEntityType.DEFAULT_W_VELOCITY} と同値)。
     *
     * <p><b>クライアント側 ({@code ObserverW}) からサーバー側のここへ移設してある。</b>
     * 方式B では w は世界の状態でサーバーが権威を持つため、 速さをクライアントに
     * 持たせると (a) 改造クライアントが任意の速さで世界の w を動かせ、
     * (b) 設計値が 2 箇所に散る。 クライアントが送るのは向き ({@code -1/0/+1}) だけ。
     */
    public static final double W_RATE_PER_TICK = 0.02;

    /**
     * キー入力の有効期限 [tick]。 これを超えて新しい入力が来なければ「離した」と扱う。
     *
     * <p>クライアントは押している間 (毎クライアントティック = 20/秒) 送り続けるので、
     * 数ティックの猶予で十分。 これが無いと、 離した通知が届かない状況
     * (画面遷移・切断・別 mod による入力奪取) で<b>世界の w が走り続ける</b>。
     */
    public static final int INPUT_EXPIRY_TICKS = 5;

    /** {@code /bstep auto <rate>} で指定できるレートの上限 [w/秒]。 */
    public static final double MAX_RATE = 4.0;

    /** {@code /bstep radius <n>} の上限 [チャンク]。 */
    public static final int MAX_RADIUS = 32;

    /**
     * 移動中央値・最大値を出すための履歴の長さ [適用回数]。
     *
     * <p>適用は<b>毎ティック</b>起きるので、 これはおおよそ「直近 N ティック」である。
     * 既定 100 は {@link TickPeak#WINDOW_TICKS} と同じ = 約 5 秒で、
     * ピーク欄と中央値欄が同じ時間窓を指すようにしてある
     * (かつては 1 ステップ = 6.25 ティックだったので 32 でも約 10 秒あった)。
     */
    public static final int HISTORY_SIZE = 100;

    /** アクションバーへの表示間隔 [tick]。 */
    public static final int HUD_INTERVAL_TICKS = 5;

    // =================================================================
    // ── 実行時モード ──
    // =================================================================
    //   ここを static final にしないのは意図的 ({@code BSwapExperiment} と同じ理由)。
    //   「モードを変えるたびにリビルドと再起動」では測定が回らない。
    //   コンパイル時の可逆性は EXPERIMENT_ENABLED 一本が担保する。

    /**
     * 差分の対象半径 [チャンク]。 負値は「シミュレーション距離いっぱい」を意味する。
     *
     * <p><b>なぜ半径という摘みが要るか</b>: 方式B の本来の対象は「ロード済み範囲全体」だが、
     * 実測 (1 チャンクあたり中央値 412 ブロック) から素直に掛け算すると
     * シミュレーション距離 8 (17x17=289 チャンク) で 1 ステップ 10 万ブロックを超える。
     * <b>どこで破綻するかを探す</b>のがこの計測の目的なので、 範囲は実行時に動かせないと
     * 意味がない。 既定は「シミュレーション距離いっぱい」= 正直な方式B の負荷。
     */
    private static volatile int radius = -1;

    /**
     * 差分計算をスレッドプールで並列化するか。
     *
     * <p>{@code HyperTerrain} は純粋関数で MC の可変状態に一切触らないため、
     * チャンク単位の差分計算は安全に並列化できる。 サーバースレッドで行うのは
     * <b>適用のみ</b>。
     */
    private static volatile boolean parallelDiff = true;

    /**
     * <b>距離帯の表</b> ({@link WScheduler#BAND_QUANTA}) を効かせるか。
     *
     * <p>実行時に切れるようにしてあるのは、 <b>効果を実測で比べるため</b>。
     * カクつきが消えたかはクライアント側の体感で判定するしかないので、
     * 同じ場所・同じレートで on/off を往復できないと判断できない。
     *
     * <p>{@code off} にすると全帯の粒度が {@link #STEP_QUANTUM} に揃い、 優先度が
     * 遅れそのものになる (近方と遠方が対等)。 <b>時間予算は効いたまま</b>なので
     * サーバーは止まらず、 「距離別に粗くすることに意味があるか」だけを比べられる。
     */
    private static volatile boolean scheduler = true;

    private BStepExperiment() {
    }

    public static int radius() {
        return radius;
    }

    public static void setRadius(int value) {
        radius = value;
    }

    public static boolean parallelDiff() {
        return parallelDiff;
    }

    public static void setParallelDiff(boolean value) {
        parallelDiff = value;
    }

    public static boolean scheduler() {
        return scheduler;
    }

    public static void setScheduler(boolean value) {
        scheduler = value;
    }
}
