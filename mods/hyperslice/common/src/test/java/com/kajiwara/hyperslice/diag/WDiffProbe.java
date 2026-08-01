package com.kajiwara.hyperslice.diag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.kajiwara.hyperslice.core.HyperTerrain;

/**
 * 【診断・使い捨て】 w を微小に動かしたとき、 実際に何ブロックが変化するかの実測。
 *
 * <p><b>これは出荷コードではない。</b> {@code common/src/test} に置いてあるので
 * 出荷 jar には一切入らない (test ソースセットは jar に含まれない)。 捨てるときは
 * このパッケージと {@code common/build.gradle} の {@code wDiff} タスクを消すだけでよい。
 *
 * <h2>何を測るのか</h2>
 * 方式B (単一ディメンションのままブロックを書き換えて w を連続移動させる) は
 * 「毎ステップ、 セクションを丸ごと作り直す」前提で設計されている。 しかし w の
 * 量子化を 1/8 ブロックにすると、 1 ステップの w 移動 (0.125) に対して
 * {@code W_LATTICE_SPACING = 2.0} なので、 実際に変化するブロックは
 * <b>地形の高さが整数境界をまたいだ列だけ</b>のはずである。 その実数を出す。
 *
 * <h2>地形は複製していない</h2>
 * 地形の値は {@link HyperTerrain#surfaceY(int, int, double)} を <b>そのまま呼ぶ</b>。
 * 唯一ここに写しているのは「地表高度 → ブロック種別」の対応
 * ({@link #stateAt}) で、 これは {@code HyperSliceChunkGenerator.stateAt} の
 * ミラーである (あちらは {@code BlockState} を返すため MC 依存で、 MC 非依存の
 * common からは呼べない)。 <b>写したのは分類だけで、 地形そのものではない。</b>
 * 分岐と定数は 1:1 に対応させてあり、 元は
 * {@code src/main/java/com/kajiwara/hyperslice/worldgen/HyperSliceChunkGenerator.java}。
 *
 * <h2>2 つの指標</h2>
 * <ul>
 *   <li><b>固体差分</b> — {@code isSolid} が変わったブロック数。 ミラーを一切通さない
 *       ({@link HyperTerrain} だけで決まる) ので、 写し間違いの影響を受けない基準値</li>
 *   <li><b>ブロック差分</b> — 上のミラーで種別が変わったブロック数。 方式B が実際に
 *       書き換える必要のある数。 水没判定の反転や表土の材質変化を含むぶん固体差分より多い</li>
 * </ul>
 */
public final class WDiffProbe {

    private WDiffProbe() {
    }

    // ── HyperSliceChunkGenerator のミラー (地形ではなく「高さ→種別」だけ) ──

    /** {@code HyperSliceChunkGenerator.SOIL_DEPTH} と同じ値。 */
    public static final int SOIL_DEPTH = 4;

    public static final int BEDROCK = 0;
    public static final int STONE = 1;
    public static final int DIRT = 2;
    public static final int GRASS = 3;
    public static final int SAND = 4;
    public static final int WATER = 5;
    public static final int AIR = 6;

    /** {@code HyperSliceChunkGenerator.stateAt} と同じ分岐 (BlockState を int に置換しただけ)。 */
    public static int stateAt(int y, int minY, int surface, boolean underwater) {
        if (y == minY) {
            return BEDROCK;
        }
        if (y > surface) {
            return y <= HyperTerrain.SEA_LEVEL ? WATER : AIR;
        }
        if (y == surface) {
            return underwater ? SAND : GRASS;
        }
        if (y > surface - SOIL_DEPTH) {
            return underwater ? SAND : DIRT;
        }
        return STONE;
    }

    // ── 世界の寸法 (HyperTerrain の定数から導出。 ここに数値を書かない) ──

    public static final int MIN_Y = HyperTerrain.MIN_Y;
    public static final int MAX_Y = HyperTerrain.MIN_Y + HyperTerrain.WORLD_HEIGHT - 1;
    public static final int SECTION_COUNT = HyperTerrain.WORLD_HEIGHT / 16;
    public static final int MIN_SECTION = Math.floorDiv(MIN_Y, 16);
    public static final int COLUMNS_PER_CHUNK = 16 * 16;
    public static final long BLOCKS_PER_CHUNK = (long) COLUMNS_PER_CHUNK * HyperTerrain.WORLD_HEIGHT;

    // ── 測定パラメータ ────────────────────────────────────────────

    /** w の量子化単位。 全ての基準 w / delta はこの整数倍で表す (誤差ゼロで扱える)。 */
    private static final int UNITS_PER_SLICE = 8;

    /** 振る delta [1/8 スライス単位] = 0.125 / 0.25 / 0.5 / 1.0。 */
    private static final int[] DELTA_UNITS = { 1, 2, 4, 8 };

    /** 1 領域あたりのチャンク数の一辺 (5 → 25 チャンク)。 */
    private static final int REGION_CHUNKS = 5;

    private static final long[] SEEDS = { 123456789L, -987654321L, 4815162342L };

    // ── 1 チャンク分の比較結果 ──────────────────────────────────

    /** 1 チャンクを w と w+delta で比べた結果。 */
    public record ChunkDiff(long changedBlocks, long changedSolid, int changedColumns,
                            int changedSections) {
    }

    /**
     * 同一チャンクを 2 つの地表高度配列 (= 2 つの w) で比べる。
     *
     * <p>{@code surfaceA[i] == surfaceB[i]} の列は、 水没判定も表土も完全に同一に
     * なる (どちらも地表高度だけから決まる) ので寄与しない。 変化した列だけを
     * 「土の底〜海面/地表の上端」の窓で走査する。 窓の外が本当に同一であることは
     * {@link #compareFullColumns} との突き合わせで検証している
     * ({@code WDiffProbeTest} と、 本 main の起動時セルフチェック)。
     */
    public static ChunkDiff compare(int[] surfaceA, int[] surfaceB) {
        long blocks = 0;
        long solid = 0;
        int columns = 0;
        boolean[] sections = new boolean[SECTION_COUNT];

        for (int i = 0; i < COLUMNS_PER_CHUNK; i++) {
            int a = surfaceA[i];
            int b = surfaceB[i];
            if (a == b) {
                continue;
            }
            columns++;

            // 固体差分: isSolid(y) は y <= surface と同値なので、 差は高さの差そのもの。
            solid += Math.abs(clampToWorld(a) - clampToWorld(b));

            boolean ua = a < HyperTerrain.SEA_LEVEL;
            boolean ub = b < HyperTerrain.SEA_LEVEL;
            int yLo = Math.max(MIN_Y, Math.min(a, b) - SOIL_DEPTH);
            int yHi = Math.min(MAX_Y, Math.max(Math.max(a, b), HyperTerrain.SEA_LEVEL));
            for (int y = yLo; y <= yHi; y++) {
                if (stateAt(y, MIN_Y, a, ua) != stateAt(y, MIN_Y, b, ub)) {
                    blocks++;
                    sections[Math.floorDiv(y, 16) - MIN_SECTION] = true;
                }
            }
        }

        int sectionCount = 0;
        for (boolean s : sections) {
            if (s) {
                sectionCount++;
            }
        }
        return new ChunkDiff(blocks, solid, columns, sectionCount);
    }

    /** 窓を使わず全 y を走査する参照実装 (検証専用・遅い)。 */
    public static ChunkDiff compareFullColumns(int[] surfaceA, int[] surfaceB) {
        long blocks = 0;
        long solid = 0;
        int columns = 0;
        boolean[] sections = new boolean[SECTION_COUNT];

        for (int i = 0; i < COLUMNS_PER_CHUNK; i++) {
            int a = surfaceA[i];
            int b = surfaceB[i];
            boolean ua = a < HyperTerrain.SEA_LEVEL;
            boolean ub = b < HyperTerrain.SEA_LEVEL;
            boolean columnChanged = false;
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                if (stateAt(y, MIN_Y, a, ua) != stateAt(y, MIN_Y, b, ub)) {
                    blocks++;
                    columnChanged = true;
                    sections[Math.floorDiv(y, 16) - MIN_SECTION] = true;
                }
                boolean sa = y >= MIN_Y && y <= a;
                boolean sb = y >= MIN_Y && y <= b;
                if (sa != sb) {
                    solid++;
                }
            }
            if (columnChanged) {
                columns++;
            }
        }

        int sectionCount = 0;
        for (boolean s : sections) {
            if (s) {
                sectionCount++;
            }
        }
        return new ChunkDiff(blocks, solid, columns, sectionCount);
    }

    private static int clampToWorld(int surface) {
        return Math.max(MIN_Y - 1, Math.min(MAX_Y, surface));
    }

    /**
     * 1 チャンク (16x16) の地表高度を w で 1 枚作る。 地形の値は
     * {@link HyperTerrain} だけが決める。
     */
    public static int[] surfaceGrid(HyperTerrain terrain, int chunkX, int chunkZ, double w) {
        int[] out = new int[COLUMNS_PER_CHUNK];
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // 生成側と同じく maxY で頭を打つ (現在の定数では実際には効かない)。
                out[lx * 16 + lz] = Math.min(terrain.surfaceY(baseX + lx, baseZ + lz, w), MAX_Y);
            }
        }
        return out;
    }

    // ── 領域選び ────────────────────────────────────────────────

    /** 測定に使う 25 チャンクの領域と、 その地形の素性。 */
    public record Region(String label, int chunkX, int chunkZ,
                         double meanSurface, double stdSurface, int minSurface, int maxSurface) {
    }

    /**
     * 「山がち」「平坦」「海面付近」の領域を、 決め打ちではなく地形を走査して選ぶ。
     * 選ばれた座標と素性は報告に出すので、 何を測ったのかが後から分かる。
     */
    public static List<Region> pickRegions(HyperTerrain terrain) {
        int span = 12;      // 候補格子の広がり (±span 個)
        int step = 320;     // 候補間隔 [ブロック]
        double bestStd = -1;
        double worstStd = Double.MAX_VALUE;
        double bestSeaGap = Double.MAX_VALUE;
        int[] mountain = null;
        int[] flat = null;
        int[] sea = null;

        for (int cx = -span; cx <= span; cx++) {
            for (int cz = -span; cz <= span; cz++) {
                int ox = cx * step;
                int oz = cz * step;
                double[] stats = probeArea(terrain, ox, oz);
                double mean = stats[0];
                double std = stats[1];
                if (std > bestStd) {
                    bestStd = std;
                    mountain = new int[] { ox, oz };
                }
                if (std < worstStd) {
                    worstStd = std;
                    flat = new int[] { ox, oz };
                }
                double gap = Math.abs(mean - HyperTerrain.SEA_LEVEL);
                if (gap < bestSeaGap) {
                    bestSeaGap = gap;
                    sea = new int[] { ox, oz };
                }
            }
        }

        List<Region> out = new ArrayList<>();
        out.add(describe(terrain, "mountainous", mountain));
        out.add(describe(terrain, "flat", flat));
        out.add(describe(terrain, "near-sea-level", sea));
        return out;
    }

    /** 候補領域を粗く (8 ブロック間隔で) 走査して [平均, 標準偏差] を返す。 */
    private static double[] probeArea(HyperTerrain terrain, int originX, int originZ) {
        int side = REGION_CHUNKS * 16;
        double sum = 0;
        double sumSq = 0;
        int n = 0;
        for (int dx = 0; dx < side; dx += 8) {
            for (int dz = 0; dz < side; dz += 8) {
                int s = terrain.surfaceY(originX + dx, originZ + dz, 0);
                sum += s;
                sumSq += (double) s * s;
                n++;
            }
        }
        double mean = sum / n;
        return new double[] { mean, Math.sqrt(Math.max(0, sumSq / n - mean * mean)) };
    }

    private static Region describe(HyperTerrain terrain, String label, int[] origin) {
        int side = REGION_CHUNKS * 16;
        double sum = 0;
        double sumSq = 0;
        int n = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                int s = terrain.surfaceY(origin[0] + dx, origin[1] + dz, 0);
                sum += s;
                sumSq += (double) s * s;
                min = Math.min(min, s);
                max = Math.max(max, s);
                n++;
            }
        }
        double mean = sum / n;
        double std = Math.sqrt(Math.max(0, sumSq / n - mean * mean));
        return new Region(label, Math.floorDiv(origin[0], 16), Math.floorDiv(origin[1], 16),
                mean, std, min, max);
    }

    // ── 統計 ────────────────────────────────────────────────────

    /** 分布をそのまま持ち、 中央値 / p95 / 最大を出す (平均だけでは判断できないため)。 */
    public static final class Dist {
        private long[] values = new long[1024];
        private int size;
        private boolean sorted;

        public void add(long v) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = v;
            sorted = false;
        }

        public int size() {
            return size;
        }

        private void sort() {
            if (!sorted) {
                Arrays.sort(values, 0, size);
                sorted = true;
            }
        }

        public long quantile(double q) {
            if (size == 0) {
                return 0;
            }
            sort();
            int idx = (int) Math.ceil(q * size) - 1;
            return values[Math.max(0, Math.min(size - 1, idx))];
        }

        public long median() {
            return quantile(0.5);
        }

        public long p95() {
            return quantile(0.95);
        }

        public long max() {
            if (size == 0) {
                return 0;
            }
            sort();
            return values[size - 1];
        }

        public double mean() {
            if (size == 0) {
                return 0;
            }
            double sum = 0;
            for (int i = 0; i < size; i++) {
                sum += values[i];
            }
            return sum / size;
        }
    }

    /** delta 1 つ分の集計。 */
    private static final class Bucket {
        final Dist blocks = new Dist();
        final Dist solid = new Dist();
        final Dist columns = new Dist();
        final Dist sections = new Dist();

        void add(ChunkDiff d) {
            blocks.add(d.changedBlocks());
            solid.add(d.changedSolid());
            columns.add(d.changedColumns());
            sections.add(d.changedSections());
        }
    }

    // ── 出力 ────────────────────────────────────────────────────

    /**
     * 報告を貯めておく。 Windows のコンソールは既定コードページ (cp932 等) で
     * 解釈するため、 端末では日本語が化けることがある。 そこで同じ内容を必ず
     * <b>UTF-8 のファイル</b>にも書き出し、 そちらを正本にする。
     */
    private static final StringBuilder REPORT = new StringBuilder();

    private static void line(String format, Object... args) {
        REPORT.append(String.format(Locale.ROOT, format, args)).append(System.lineSeparator());
    }

    private static void line() {
        REPORT.append(System.lineSeparator());
    }

    // ── main ────────────────────────────────────────────────────

    public static void main(String[] args) {
        int sliceCount = args.length > 0 ? Integer.parseInt(args[0]) : 8;

        selfCheck(sliceCount);

        int wUnits = sliceCount * UNITS_PER_SLICE;                 // 1 周期 = 何単位か
        int maxDelta = DELTA_UNITS[DELTA_UNITS.length - 1];
        int cacheSize = wUnits + maxDelta + 1;

        line("%s", "=========================================================");
        line("%s", " HyperSlice w-差分 実測 (WDiffProbe)");
        line("%s", "=========================================================");
        line("N (slice_count)        : %d", sliceCount);
        line("w 格子点数 K            : %d  (W_LATTICE_SPACING=%.1f)",
                HyperTerrain.wLatticeCount(sliceCount), HyperTerrain.W_LATTICE_SPACING);
        line("world                  : minY=%d height=%d (y %d..%d)",
                HyperTerrain.MIN_Y, HyperTerrain.WORLD_HEIGHT, MIN_Y, MAX_Y);
        line("1 チャンクの総ブロック数 : %,d  (16x16x%d)",
                BLOCKS_PER_CHUNK, HyperTerrain.WORLD_HEIGHT);
        line("1 チャンクのセクション数 : %d", SECTION_COUNT);
        line("基準 w                  : 0, 1/8, 2/8, ... %d/8 (1 周期を 1/8 刻みで %d 点)",
                wUnits - 1, wUnits);
        line("領域                    : 3 種 x %d チャンク",
                REGION_CHUNKS * REGION_CHUNKS);
        line("シード                  : %s", Arrays.toString(SEEDS));
        line();

        // delta -> 集計 (全シード / 全領域 / 全基準 w をプール)
        Bucket[] pooled = new Bucket[DELTA_UNITS.length];
        for (int i = 0; i < pooled.length; i++) {
            pooled[i] = new Bucket();
        }
        // 領域別 (delta x region)
        Bucket[][] perRegion = new Bucket[DELTA_UNITS.length][3];
        for (Bucket[] row : perRegion) {
            for (int i = 0; i < row.length; i++) {
                row[i] = new Bucket();
            }
        }
        // 基準 w の位相依存 (delta=0.125 のみ・格子セル内の位置ごと)
        // 格子セル 1 個の幅 = N/K スライス = N*8/K 単位。
        int cellUnits = Math.max(1, wUnits / Math.max(1, HyperTerrain.wLatticeCount(sliceCount)));
        Bucket[] perPhase = new Bucket[cellUnits];
        for (int i = 0; i < perPhase.length; i++) {
            perPhase[i] = new Bucket();
        }

        long t0 = System.nanoTime();
        for (long seed : SEEDS) {
            HyperTerrain terrain = new HyperTerrain(seed, sliceCount);
            List<Region> regions = pickRegions(terrain);
            line("seed %d の領域:", seed);
            for (Region r : regions) {
                line("  %-15s chunk(%d,%d) block(%d,%d)  surface mean=%.1f sd=%.1f range=%d..%d",
                        r.label(), r.chunkX(), r.chunkZ(), r.chunkX() * 16, r.chunkZ() * 16,
                        r.meanSurface(), r.stdSurface(), r.minSurface(), r.maxSurface());
            }

            for (int ri = 0; ri < regions.size(); ri++) {
                Region region = regions.get(ri);
                for (int cx = 0; cx < REGION_CHUNKS; cx++) {
                    for (int cz = 0; cz < REGION_CHUNKS; cz++) {
                        int chunkX = region.chunkX() + cx;
                        int chunkZ = region.chunkZ() + cz;

                        // この 1 チャンクについて、 使う全ての w の地表を先に作る。
                        int[][] cache = new int[cacheSize][];
                        for (int k = 0; k < cacheSize; k++) {
                            cache[k] = surfaceGrid(terrain, chunkX, chunkZ, k / (double) UNITS_PER_SLICE);
                        }

                        for (int di = 0; di < DELTA_UNITS.length; di++) {
                            int du = DELTA_UNITS[di];
                            for (int k = 0; k < wUnits; k++) {
                                ChunkDiff d = compare(cache[k], cache[k + du]);
                                pooled[di].add(d);
                                perRegion[di][ri].add(d);
                                if (du == 1) {
                                    perPhase[k % perPhase.length].add(d);
                                }
                            }
                        }
                    }
                }
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        line();
        printPooled(pooled);
        line();
        printPerRegion(perRegion);
        line();
        printPerPhase(perPhase, sliceCount);
        line();
        line("サンプル数 (delta ごと): %,d チャンク比較   所要 %d ms",
                pooled[0].blocks.size(), elapsedMs);

        emit();
    }

    /**
     * 報告を標準出力と UTF-8 ファイルの両方へ出す。
     *
     * <p>Windows のコンソールは既定コードページで解釈するため端末では日本語が
     * 化けることがある。 <b>ファイルの方が正本</b>。
     */
    private static void emit() {
        String text = REPORT.toString();
        System.out.print(text);
        Path outFile = Path.of("build", "reports", "wdiff", "w-diff.txt");
        try {
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println();
        System.out.println("report (UTF-8): " + outFile.toAbsolutePath());
    }

    private static void printPooled(Bucket[] pooled) {
        line("%s", "--- 全体 (3 シード x 3 領域 x 25 チャンク x 全基準 w をプール) ---");
        line();
        line("%s", "[変化ブロック数 / チャンク]   総ブロック数 " + String.format(Locale.ROOT, "%,d", BLOCKS_PER_CHUNK));
        line("  %-8s %10s %10s %10s %10s | %9s %9s",
                "delta", "中央値", "p95", "最大", "平均", "p95 の%", "最大の%");
        for (int i = 0; i < DELTA_UNITS.length; i++) {
            Dist d = pooled[i].blocks;
            line("  %-8s %10d %10d %10d %10.1f | %8.4f%% %8.4f%%",
                    deltaLabel(DELTA_UNITS[i]), d.median(), d.p95(), d.max(), d.mean(),
                    100.0 * d.p95() / BLOCKS_PER_CHUNK, 100.0 * d.max() / BLOCKS_PER_CHUNK);
        }

        line();
        line("%s", "[固体差分 (isSolid が変わったブロック数) / チャンク]  ※ミラー非経由の基準値");
        line("  %-8s %10s %10s %10s %10s", "delta", "中央値", "p95", "最大", "平均");
        for (int i = 0; i < DELTA_UNITS.length; i++) {
            Dist d = pooled[i].solid;
            line("  %-8s %10d %10d %10d %10.1f",
                    deltaLabel(DELTA_UNITS[i]), d.median(), d.p95(), d.max(), d.mean());
        }

        line();
        line("%s", "[変化した列数 / チャンク]   全 256 列");
        line("  %-8s %10s %10s %10s %10s | %9s",
                "delta", "中央値", "p95", "最大", "平均", "p95 の%");
        for (int i = 0; i < DELTA_UNITS.length; i++) {
            Dist d = pooled[i].columns;
            line("  %-8s %10d %10d %10d %10.1f | %8.1f%%",
                    deltaLabel(DELTA_UNITS[i]), d.median(), d.p95(), d.max(), d.mean(),
                    100.0 * d.p95() / COLUMNS_PER_CHUNK);
        }

        line();
        line("%s", "[変化したセクション数 / チャンク]   全 " + SECTION_COUNT + " セクション");
        line("  %-8s %10s %10s %10s %10s", "delta", "中央値", "p95", "最大", "平均");
        for (int i = 0; i < DELTA_UNITS.length; i++) {
            Dist d = pooled[i].sections;
            line("  %-8s %10d %10d %10d %10.1f",
                    deltaLabel(DELTA_UNITS[i]), d.median(), d.p95(), d.max(), d.mean());
        }
    }

    private static void printPerRegion(Bucket[][] perRegion) {
        String[] labels = { "mountainous", "flat", "near-sea-level" };
        line("%s", "--- 領域別 変化ブロック数 / チャンク ---");
        line("  %-16s %-8s %10s %10s %10s", "領域", "delta", "中央値", "p95", "最大");
        for (int ri = 0; ri < labels.length; ri++) {
            for (int di = 0; di < DELTA_UNITS.length; di++) {
                Dist d = perRegion[di][ri].blocks;
                line("  %-16s %-8s %10d %10d %10d",
                        di == 0 ? labels[ri] : "", deltaLabel(DELTA_UNITS[di]),
                        d.median(), d.p95(), d.max());
            }
        }
    }

    private static void printPerPhase(Bucket[] perPhase, int sliceCount) {
        line("%s", "--- delta=0.125 の 基準 w 依存 (w 格子セル内の位相ごと) ---");
        line("  w 格子セル = %.1f スライス幅 = %d ステップ",
                sliceCount / (double) HyperTerrain.wLatticeCount(sliceCount), perPhase.length);
        line("  %-10s %10s %10s %10s", "位相", "中央値", "p95", "最大");
        for (int i = 0; i < perPhase.length; i++) {
            Dist d = perPhase[i].blocks;
            line("  %-10s %10d %10d %10d",
                    i + "/" + perPhase.length, d.median(), d.p95(), d.max());
        }
    }

    private static String deltaLabel(int units) {
        return String.format(Locale.ROOT, "%.3f", units / (double) UNITS_PER_SLICE);
    }

    /**
     * 起動時セルフチェック。 窓走査が全走査と一致すること (= 窓が狭すぎないこと) を
     * 実データで確認する。 ここが落ちたら測定値は信用できない。
     */
    private static void selfCheck(int sliceCount) {
        HyperTerrain t = new HyperTerrain(SEEDS[0], sliceCount);
        for (int k = 0; k < 24; k++) {
            int[] a = surfaceGrid(t, 3 + k, -7, k / 8.0);
            int[] b = surfaceGrid(t, 3 + k, -7, (k + 1) / 8.0);
            ChunkDiff fast = compare(a, b);
            ChunkDiff slow = compareFullColumns(a, b);
            if (!fast.equals(slow)) {
                throw new IllegalStateException("self-check failed at k=" + k
                        + ": windowed=" + fast + " full=" + slow);
            }
        }
        line("%s", "[self-check] 窓走査 == 全走査 : OK (24 チャンク)");
    }
}
