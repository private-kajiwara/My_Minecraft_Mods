package com.kajiwara.hyperslice.core;

/**
 * 4 次元空間の地形照会。 {@link HyperEntityManager} はこれ<b>だけ</b>を通して地形を見る。
 *
 * <p>これがある理由は {@link HyperTerrain} を純粋関数として隔離したのと同じ:
 * エンティティ層を {@code ServerLevel} に依存させると、 方式A → 方式B
 * (単一ディメンションでブロックを書き換える) の移行で全面書き直しになる。
 * 物理・当たり判定は「地形関数 + 改変差分」に対して計算し、 Minecraft の世界オブジェクトを
 * 参照しない。
 *
 * <p>既存の {@link HyperTerrain#isSolid(int, int, int, int)} が偶然ではなく
 * <b>意図的に</b>このシグネチャと一致しているので、 素の地形だけを見るなら
 * {@code terrain::isSolid} のメソッド参照をそのまま渡せる。 プレイヤーが掘った/置いた
 * 差分を反映する段階では、 MC 側で「差分ストアを先に引き、 無ければ地形関数へ委譲する」
 * 実装を注入すればよい (common 側は無変更)。
 */
@FunctionalInterface
public interface HyperTerrainQuery {

    /** {@code (x, y, z, w)} が固体か。 {@code w} はブロック層番号 (= {@code floor(w)})。 */
    boolean isSolid(int x, int y, int z, int w);

    /** 何も無い空間 (テスト用 / マイルストーン1 の衝突なし構成用)。 */
    HyperTerrainQuery EMPTY = (x, y, z, w) -> false;
}
