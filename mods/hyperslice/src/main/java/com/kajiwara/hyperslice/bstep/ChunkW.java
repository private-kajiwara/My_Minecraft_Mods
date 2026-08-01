package com.kajiwara.hyperslice.bstep;

import com.mojang.serialization.Codec;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * <b>【方式B 中核】</b> 「このチャンクの地形は今どの w か」— <b>チャンク自身に貼り付けて持つ</b>。
 *
 * <h2>なぜチャンクに持たせるのか (サーバー側の表ではなく)</h2>
 * チャンクごとの w がずれる原因は 3 つあり、 <b>どれも保存を跨ぐ</b>:
 * <ol>
 *   <li>{@link WScheduler} が遠方を見送る → 最大で「周期 x 量子」ぶん遅れる</li>
 *   <li>途中で生成されたチャンクは<b>生成時の w</b> ({@code HyperSliceChunkGenerator.WSource}) で作られる</li>
 *   <li>アンロード → 再ロード。 差し替え済みの地形は {@code markUnsaved} 経由でディスクに焼かれるので、
 *       再ロードしたチャンクは<b>そのとき焼かれた w のまま</b>である</li>
 * </ol>
 * かつてはサーバー側の {@code WeakHashMap<LevelChunk, Double>} で持っていたが、 それだと (3) で
 * 表だけが消えて「本来の整数 w」に戻ってしまい、 実際の地形と食い違った差分が当たる
 * (古い地形が永久に残る)。 チャンクに貼れば<b>チャンクと同じ寿命・同じ保存単位</b>になり、
 * アンロードでもワールド再開でも自動的に正しい。 表の肥大や退避方針も要らない。
 *
 * <h2>実体</h2>
 * Fabric の永続 attachment (チャンク NBT に同居する)。 26.1 系の
 * {@code fabric-data-attachment-api-v1} は {@code ChunkAccess} を対象に含み、
 * <b>{@code ProtoChunk} に付けた値を {@code LevelChunk} へ移送する</b>
 * ({@code LevelChunkMixin.transferProtoChunkAttachment}) ので、 <b>生成中に記録した w が
 * そのまま実チャンクとディスクへ引き継がれる</b> (jar の mixin 構成を実測確認)。
 * クライアントへは同期しない ({@code syncWith} を宣言していない)。
 *
 * <h2>既定値の規約</h2>
 * <b>値が無いチャンク = そのディメンション本来の整数 w</b>。 これは
 * <ul>
 *   <li>方式B を入れる前に生成された既存ワールドのチャンク</li>
 *   <li>方式B が無効 ({@code BStepExperiment.EXPERIMENT_ENABLED == false}) のとき</li>
 * </ul>
 * の両方で正しい (どちらも本来の整数 w で作られている)。 方式B が有効なら
 * 生成器が必ず記録するので、 以後は値が付く。
 */
public final class ChunkW {

    /**
     * チャンクの現在 w。
     *
     * <p>{@code static final} の初期化子で登録が走るため、 <b>ワールドを読む前に</b>
     * このクラスを触っておく必要がある ({@link #register()} を mod 初期化で呼ぶ)。
     * 遅れて登録するとチャンク NBT 側の値が解決できない。
     */
    private static final AttachmentType<Double> TYPE = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath("hyperslice", "chunk_w"), Codec.DOUBLE);

    private ChunkW() {
    }

    /**
     * attachment 型を登録する (mod 初期化で 1 回)。
     *
     * <p>実体は上の {@code static final} の初期化なので、 このメソッドは
     * 「クラス初期化を今ここで起こす」ためだけにある。 方式B が無効なら
     * 呼び出し側で定数畳み込みにより消え、 登録自体が起きない。
     */
    public static void register() {
        // TYPE の解決 = このクラスの初期化。 何もしないのが正しい。
    }

    /**
     * このチャンクの地形が今どの w か。 記録が無ければ {@code nominalW}。
     *
     * @param nominalW そのディメンション本来の整数 w
     */
    public static double of(ChunkAccess chunk, double nominalW) {
        Double value = chunk.getAttached(TYPE);
        return value == null ? nominalW : value;
    }

    /**
     * このチャンクの地形を w にしたことを記録する。
     *
     * <p><b>差分を実際に当てたチャンクにだけ</b>呼ぶこと。 見送ったチャンクにも呼ぶと
     * 蓄積分が失われ、 そのチャンクは永久にずれたまま残る
     * ({@code BStepSession} の同じ注意書きを参照)。
     */
    public static void set(ChunkAccess chunk, double w) {
        chunk.setAttached(TYPE, w);
    }
}
