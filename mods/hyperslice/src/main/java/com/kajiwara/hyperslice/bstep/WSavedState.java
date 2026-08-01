package com.kajiwara.hyperslice.bstep;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * <b>【方式B 中核】</b> そのレベルの w をセーブに焼く。
 *
 * <h2>なぜ必要か</h2>
 * w はメモリ上にしか無かったため、 ワールドを開き直すと本来の整数 w に戻っていた。
 * ところが<b>地形はその w で切り直した状態のままセーブされている</b>ので、
 * 開き直した瞬間に「地形の w」と「セッションの w」が食い違い、 次の 1 ステップで
 * 巨大な差分が出る (= 世界が一気に作り直される)。 保存すればこれが起きない。
 *
 * <h2>置き場所</h2>
 * {@link ServerLevel#getDataStorage()} は<b>レベルごと</b>の {@link SavedDataStorage} なので、
 * ディメンションごとの {@code data/} に自動的に分かれる。 w がレベルごとの値である以上
 * これがそのまま正しい置き場所で、 スライス番号を鍵に混ぜる必要はない。
 *
 * <h2>26.1 の形</h2>
 * {@code DimensionDataStorage} ではなく {@link SavedDataStorage}、 NBT 手書きではなく
 * {@link SavedDataType}{@code (Identifier, Supplier<T>, Codec<T>, DataFixTypes)} の
 * <b>Codec ベース</b> (javap 実測)。 {@code dataFixType()} は読み込み時に無条件に
 * 参照されるため {@code null} を渡せない。 我々のデータに当たる vanilla の datafixer は
 * 存在しないが、 書き込み時のデータバージョンは常に現行なので
 * {@code DataFixTypes.update} は実質 no-op になる。
 *
 * <h2>チャンクごとの w は<b>ここに入れない</b></h2>
 * それは {@link ChunkW} が<b>チャンク自身の attachment</b>として持つ。 ここに
 * {@code ChunkPos -> w} の表を置くと、 訪れたチャンクぶんだけ無制限に育ち、
 * かつ「アンロードされたチャンクの分をいつ捨てるか」という答えの無い問いが残る。
 */
public final class WSavedState extends SavedData {

    private static final Codec<WSavedState> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.DOUBLE.fieldOf("current_w").forGetter(s -> s.currentW)
            ).apply(instance, WSavedState::new));

    /**
     * 保存型。
     *
     * <p>第 2 引数の「既定を作る supplier」は {@code computeIfAbsent} 専用で、
     * 本 mod は使わない (既定値はレベルごとに違う = そのスライス本来の整数 w なので、
     * レベルを知らない supplier では正しく作れない)。 {@link #of} が
     * {@code get} → 無ければ {@code set} で明示的に作る。
     */
    private static final SavedDataType<WSavedState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("hyperslice", "w"),
            () -> new WSavedState(0.0),
            CODEC,
            DataFixTypes.LEVEL);

    private double currentW;

    private WSavedState(double currentW) {
        this.currentW = currentW;
    }

    /**
     * このレベルの保存済み w を読む。 無ければ {@code nominalW} で作る。
     *
     * <p>{@code SavedDataStorage.get} はディスクを 1 回だけ読んでキャッシュし、
     * ファイルが無ければ {@code null} を返す (javap 実測)。 {@code set} は
     * キャッシュへの登録と {@code setDirty()} を両方行う。
     */
    static WSavedState of(ServerLevel level, double nominalW) {
        SavedDataStorage storage = level.getDataStorage();
        WSavedState existing = storage.get(TYPE);
        if (existing != null) {
            return existing;
        }
        WSavedState fresh = new WSavedState(nominalW);
        storage.set(TYPE, fresh);
        return fresh;
    }

    double currentW() {
        return currentW;
    }

    /** 値が実際に変わったときだけ dirty にする (w を動かしていない間の保存を誘発しない)。 */
    void set(double w) {
        if (Double.compare(currentW, w) != 0) {
            currentW = w;
            setDirty();
        }
    }
}
