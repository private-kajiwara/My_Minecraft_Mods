package com.kajiwara.visualizegate.tile;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * ㉗ 1 タイル分のボクセル集合 (純 Java・MC 非依存)。 絶対座標ボクセルキー ({@link VoxelKey}) → 値
 * ({@code color<<32 | y}) の Map。
 *
 * <p><b>per-tile 上限 + LOD 降格</b>: 1 タイルは {@link #MAX_PTS_PER_TILE} ボクセルまで。 超過時は
 * {@link #demoteLod()} で<b>横ストライドを 2 倍</b>に粗くし、 新ストライド格子に乗らないボクセルを捨てて
 * 容量を半減させる (= 1 タイルあたりのメモリ/点数を有界化)。 タイル数自体は無制限なので<b>全体カバーは無制限</b>
 * (歩いた所は全部残る)。 LOD は power-of-2 ストライド (1,2,4,8…) で、 整列判定はビット AND (負座標でも正しい)。
 */
public final class Tile {

    /**
     * 1 タイル (128×128 ブロック) のボクセル上限。 stride-1 表層 ≒ 16384 カラム + ⑳ 3D 露出面で増える分の
     * 余裕。 超過で LOD 降格 (stride×2 で約半減)。 メモリ/在庫はタイル数×この値で伸びる (探索面積に比例)＝
     * 表示は GPU detail 予算で別途有界なので在庫は大きくてよい。 後で微調整可能な単一定数。
     */
    public static final int MAX_PTS_PER_TILE = 16_000;

    private final Map<Long, Long> voxels;
    /** 現在の LOD 横ストライド (1=最密)。 power-of-2。 */
    private int lodStride;

    public Tile() {
        this.voxels = new HashMap<>();
        this.lodStride = 1;
    }

    /** codec ロード用 (既存ボクセル群 + LOD を復元)。 */
    public Tile(Map<Long, Long> voxels, int lodStride) {
        this.voxels = voxels;
        this.lodStride = Math.max(1, lodStride);
    }

    public int lodStride() {
        return lodStride;
    }

    public int size() {
        return voxels.size();
    }

    public Map<Long, Long> voxels() {
        return voxels;
    }

    /** power-of-2 ストライド整列 (ビット AND・負座標でも下位ビットで正しく判定)。 */
    private boolean alignedToLod(int wx, int wz) {
        int m = lodStride - 1;
        return (wx & m) == 0 && (wz & m) == 0;
    }

    /**
     * 1 ボクセルを格納。 既知キー=最新観測で更新 (色も)。 新規は容量内なら追加、 超過時は LOD 降格してから
     * 整列するもののみ追加。 返り値=実際に保持したか (false=現 LOD で間引かれた)。
     */
    public boolean put(int wx, int wz, int y, int color) {
        if (lodStride > 1 && !alignedToLod(wx, wz)) {
            return false; // 現 LOD 格子外 → 間引き
        }
        long key = VoxelKey.of(wx, wz, y);
        long val = VoxelKey.packVal(color, y);
        if (voxels.containsKey(key)) {
            voxels.put(key, val);
            return true;
        }
        if (voxels.size() < MAX_PTS_PER_TILE) {
            voxels.put(key, val);
            return true;
        }
        demoteLod(); // 満杯 → 粗くして容量を空ける
        if (alignedToLod(wx, wz) && voxels.size() < MAX_PTS_PER_TILE) {
            voxels.put(key, val);
            return true;
        }
        return false;
    }

    /** LOD を 1 段粗く (ストライド×2) し、 新格子に乗らないボクセルを除去 (容量を約半減)。 */
    private void demoteLod() {
        lodStride <<= 1;
        int m = lodStride - 1;
        for (Iterator<Map.Entry<Long, Long>> it = voxels.entrySet().iterator(); it.hasNext();) {
            long key = it.next().getKey();
            int wx = VoxelKey.x(key);
            int wz = VoxelKey.z(key);
            if ((wx & m) != 0 || (wz & m) != 0) {
                it.remove();
            }
        }
    }

    public boolean isEmpty() {
        return voxels.isEmpty();
    }
}
