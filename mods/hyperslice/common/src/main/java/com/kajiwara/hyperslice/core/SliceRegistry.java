package com.kajiwara.hyperslice.core;

/**
 * スライス番号 {@code w} と ディメンション ID の対応、 および w の巡回規則。
 *
 * <h2>N (スライス枚数) はここに定数として持たない</h2>
 * N は {@code mods/hyperslice/gradle.properties} の {@code slice_count} を唯一の摘みとし、
 * ビルド時に生成される dimension JSON の {@code generator.slice_count} を経由して
 * {@code HyperSliceChunkGenerator} の Codec に入る。 つまり <b>N はデータ側の値</b>であり、
 * Java 側は生成器インスタンスから受け取るだけ。 定数の二重管理が起きない。
 *
 * <p>コマンドや HUD が N を必要とする場面では、 サーバーに実在する
 * {@code hyperslice:slice_*} ディメンションを数えて得る (実行時の実態が正)。
 */
public final class SliceRegistry {

    /** この Mod の namespace。 */
    public static final String NAMESPACE = "hyperslice";

    /** スライスディメンションの ID 接頭辞。 実 ID は {@code hyperslice:slice_<w>}。 */
    public static final String SLICE_PREFIX = "slice_";

    private SliceRegistry() {
    }

    /** スライス {@code w} のディメンション path 部 (例: {@code "slice_3"})。 */
    public static String slicePath(int w) {
        return SLICE_PREFIX + w;
    }

    /** スライス {@code w} の完全なディメンション ID 文字列 (例: {@code "hyperslice:slice_3"})。 */
    public static String sliceId(int w) {
        return NAMESPACE + ":" + slicePath(w);
    }

    /**
     * ディメンション path から w を逆引きする。
     *
     * @return スライスでなければ {@code -1}
     */
    public static int wFromPath(String path) {
        if (path == null || !path.startsWith(SLICE_PREFIX)) {
            return -1;
        }
        try {
            int w = Integer.parseInt(path.substring(SLICE_PREFIX.length()));
            return w >= 0 ? w : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * w を {@code 0 .. sliceCount-1} に巡回させる。
     *
     * <p>負の値も正しく巻き戻る ({@code -1} → {@code sliceCount-1})。
     * w=N-1 と w=0 は地形上も連続している ({@link HyperTerrain} の周期性) ので、
     * この巡回はワープではなく単なる隣接移動として一貫している。
     */
    public static int wrap(int w, int sliceCount) {
        if (sliceCount < 1) {
            throw new IllegalArgumentException("sliceCount must be >= 1, got " + sliceCount);
        }
        return Math.floorMod(w, sliceCount);
    }
}
