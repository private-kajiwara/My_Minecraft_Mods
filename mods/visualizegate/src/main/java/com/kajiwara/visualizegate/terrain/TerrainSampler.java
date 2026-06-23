package com.kajiwara.visualizegate.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * ロード済みチャンクを<b>カラム代表点</b>に点群化する純粋スキャナ (状態を持たない static)。
 *
 * <p>㉗ で {@code src/client} から <b>{@code src/main}</b> (両 side) へ移動し、 引数を {@code ClientLevel}→
 * <b>{@code Level}</b> に一般化した (= クライアントの {@code ClientLevel} とサーバーの {@code ServerLevel} の両方を
 * 受ける・client 型非依存)。 {@code getHeight}/{@code getBlockState}/{@code getMapColor} は {@code Level} に在り
 * 両世代同名 (javap 確認)。 これで server entrypoint からも同一サンプリングを呼べる。
 *
 * <p><b>粒度</b>: ストライド {@link #STRIDE} ブロック格子の 1 カラムにつき、 上から下へ「空気の直下の固体」
 * (= 露出した上面: 地表・洞窟床・オーバーハング上面…) を採る (⑳ 3D 露出サーフェス)。
 *
 * <p><b>表面 Y の決め方</b>:
 * <ul>
 *   <li>オーバーワールド系 (空が開く次元): {@code getHeight(WORLD_SURFACE,x,z)} から下へ {@link #OW_SCAN_DEPTH}。</li>
 *   <li>ネザー (天井がある次元): 与えられた Y 帯を上から下へ走査。</li>
 * </ul>
 */
public final class TerrainSampler {

    /**
     * 格納/サンプリングの横ストライド (ブロック)。 ㉒ <b>1</b> (横最大解像度)。 タイル化 (㉗) 後はタイル内 LOD が
     * 容量を有界化するので、 サンプリングは最密のまま採れる (在庫はタイル数で伸び、 表示は GPU 予算で有界)。
     */
    public static final int STRIDE = 1;
    /** ⑳ OW 系の 3D 露出サーフェス採取の縦走査深さ (ブロック)。 これより深い洞窟は採らない (読取り有界)。 */
    private static final int OW_SCAN_DEPTH = 96;

    /** ㉗ ネザー帯走査の既定全域 (server はプレイヤー非依存でこの全域を走査)。 */
    public static final int NETHER_MIN_Y = 1;
    public static final int NETHER_MAX_Y = 126;

    private TerrainSampler() {
    }

    /** カラムを受け取るシンク。 y=world 絶対高さ、 color=表面ブロックの MapColor 由来 RGB / {@link #NO_COLOR}。 */
    @FunctionalInterface
    public interface ColumnSink {
        void accept(int blockX, int blockZ, int y, int color);
    }

    /** ブロック色が取れない (MapColor.NONE 等) ときの番兵。 */
    public static final int NO_COLOR = -1;

    /**
     * 1 チャンクを {@link #STRIDE} 格子でサンプルし、 各カラムの露出面を {@code sink} へ渡す。
     *
     * @param hasCeiling 天井のある次元 (ネザー) なら true ＝ 帯走査、 false ＝ WORLD_SURFACE ハイトマップ
     * @param bandTopY   ネザー帯走査の上端 (含む・クランプ済を渡すこと)
     * @param bandBotY   ネザー帯走査の下端 (含む)
     */
    public static void sampleChunk(Level level, LevelChunk chunk, boolean hasCeiling,
            int bandTopY, int bandBotY, ColumnSink sink) {
        ChunkPos cp = chunk.getPos();
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx += STRIDE) {
            for (int dz = 0; dz < 16; dz += STRIDE) {
                int wx = baseX + dx;
                int wz = baseZ + dz;
                int scanTop;
                int scanBot;
                if (hasCeiling) {
                    scanTop = bandTopY;
                    scanBot = bandBotY;
                } else {
                    int surfY = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                    scanTop = surfY;
                    scanBot = surfY - OW_SCAN_DEPTH;
                }
                boolean prevAir = true;
                for (int y = scanTop; y >= scanBot; y--) {
                    boolean air = level.getBlockState(pos.set(wx, y, wz)).isAir();
                    if (!air && prevAir) {
                        sink.accept(wx, wz, y, mapColorAt(level, pos));
                    }
                    prevAir = air;
                }
            }
        }
    }

    /**
     * 表面ブロックの MapColor 由来 RGB (0xRRGGBB)。 {@code MapColor.NONE}/取得不能は {@link #NO_COLOR}。
     * API 名は 26.1.2/1.21.11/1.21.10 同一 (javap 確認・置換不要)。 メインスレッド/server スレッドで level 有のみ。
     */
    private static int mapColorAt(Level level, BlockPos pos) {
        try {
            net.minecraft.world.level.material.MapColor mc = level.getBlockState(pos).getMapColor(level, pos);
            if (mc == null || mc.col == 0) {
                return NO_COLOR;
            }
            int argb = mc.calculateARGBColor(net.minecraft.world.level.material.MapColor.Brightness.NORMAL);
            return argb & 0xFFFFFF;
        } catch (Throwable t) {
            return NO_COLOR;
        }
    }
}
