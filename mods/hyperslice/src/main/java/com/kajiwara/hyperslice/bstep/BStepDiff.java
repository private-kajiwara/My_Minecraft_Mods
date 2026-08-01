package com.kajiwara.hyperslice.bstep;

import com.kajiwara.hyperslice.core.HyperTerrain;
import com.kajiwara.hyperslice.worldgen.HyperSliceChunkGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 1 チャンク分の「w を {@code fromW} → {@code toW} に動かしたときに変わるブロック」を求める。
 *
 * <p><b>MC の可変状態には一切触らない</b> ({@link LevelChunk} から読むのは
 * {@code getMinY} / {@code getHeight} / {@code getPos} / セクション本数という不変値だけ)。
 * したがってワーカースレッドで並列に走らせてよい。 サーバースレッドで行うのは適用のみ。
 *
 * <h2>全ブロック生成をしないこと (この最適化を省いてはいけない)</h2>
 * 1 チャンク 98,304 ブロックを両方の w で作って比べるのは無駄が大きすぎる。
 * 実測 ({@code :common:wDiff}) のとおり<b>変化は列ごとの地表高度の変化に由来する</b>ので、
 *
 * <ol>
 *   <li>列ごと (16x16 = 256 列) に旧 w / 新 w の {@code surfaceY} を求める</li>
 *   <li><b>高さが変わらない列はスキップ</b> (実測で約半数)</li>
 *   <li>変わった列だけ、 下記の y 範囲のみ走査する</li>
 * </ol>
 *
 * これで 1 チャンクあたりの {@link HyperTerrain} 呼び出しが
 * <b>98,304 → 512</b> (256 列 x 2 つの w) に落ちる。
 *
 * <h2>影響を受ける y 範囲の導出</h2>
 * {@link HyperSliceChunkGenerator#stateAt} は次の形をしている:
 * <pre>
 *   y == minY                  -&gt; BEDROCK
 *   y &gt;  surface               -&gt; y &lt;= SEA_LEVEL ? WATER : AIR
 *   y == surface               -&gt; underwater ? SAND : GRASS
 *   y &gt;  surface - SOIL_DEPTH  -&gt; underwater ? SAND : DIRT
 *   それ以外                    -&gt; STONE
 * </pre>
 * {@code lo = min(s0,s1)}, {@code hi = max(s0,s1)} として:
 * <ul>
 *   <li>{@code y > hi} … 両方とも「地表より上」の枝に入る。 値は {@code y} と
 *       {@code SEA_LEVEL} だけで決まり {@code surface} に依存しない → <b>必ず同一</b></li>
 *   <li>{@code y <= lo - SOIL_DEPTH} … {@code y <= surface - SOIL_DEPTH} は
 *       {@code surface = hi} でも成立するので両方とも STONE 枝 → <b>必ず同一</b></li>
 * </ul>
 * ∴ 変化しうるのは <b>{@code y ∈ [lo - (SOIL_DEPTH-1), hi]}</b> のみ ({@code [minY, maxY]} でクランプ)。
 *
 * <p>{@code underwater} は {@code surface < SEA_LEVEL} から導かれる従属変数なので、
 * <b>{@code s0 == s1} なら列全体が完全に同一</b>。 これが上記 (2) の根拠。
 *
 * <p>範囲内でも実際には一致する y があるため、 範囲を走査して {@code stateAt} の
 * <b>実値を比較</b>し、 異なる y だけを記録する。
 *
 * <p>この導出は「証明」であって実行時保証ではない。 {@code stateAt} の帯構造を将来
 * 変えたときに黙って壊れないよう、 {@code /bstep verify} が全 y 総当たりの
 * {@link #computeReference} と突き合わせる。
 */
public final class BStepDiff {

    /** 記録配列の初期容量。 実測 p95 が 1,297 なのでこの辺から始めれば伸長はほぼ起きない。 */
    private static final int INITIAL_CAPACITY = 1536;

    private BStepDiff() {
    }

    /**
     * 1 チャンク分の差分。
     *
     * <p>{@link BlockPos} は<b>ワーカースレッドで</b>確保する (サーバースレッドの
     * 適用フェーズに確保コストを持ち込まないため)。
     */
    public static final class ChunkDiff {

        private final LevelChunk chunk;
        private BlockPos[] positions;
        private BlockState[] states;
        private int size;
        private int changedColumns;
        /** セクション添字ごとの変化数 (0 なら未接触)。 パケット数の見積りに使う。 */
        private final int[] perSection;

        ChunkDiff(LevelChunk chunk, int sectionCount) {
            this.chunk = chunk;
            this.positions = new BlockPos[INITIAL_CAPACITY];
            this.states = new BlockState[INITIAL_CAPACITY];
            this.perSection = new int[sectionCount];
        }

        void add(BlockPos pos, BlockState state, int sectionIndex) {
            if (size == positions.length) {
                int next = size * 2;
                BlockPos[] p = new BlockPos[next];
                BlockState[] s = new BlockState[next];
                System.arraycopy(positions, 0, p, 0, size);
                System.arraycopy(states, 0, s, 0, size);
                positions = p;
                states = s;
            }
            positions[size] = pos;
            states[size] = state;
            size++;
            perSection[sectionIndex]++;
        }

        public LevelChunk chunk() {
            return chunk;
        }

        public int size() {
            return size;
        }

        public BlockPos position(int i) {
            return positions[i];
        }

        public BlockState state(int i) {
            return states[i];
        }

        public int changedColumns() {
            return changedColumns;
        }

        /**
         * 触れたセクション数。
         *
         * <p><b>これがそのままパケット数になる</b>。 26.1.2 の {@code ChunkHolder.broadcastChanges}
         * は「そのセクションの変化が 1 個なら {@code ClientboundBlockUpdatePacket}、
         * 2 個以上なら {@code ClientboundSectionBlocksUpdatePacket}」なので、
         * どちらにせよセクション 1 個につきパケットはちょうど 1 個。
         */
        public int touchedSections() {
            int n = 0;
            for (int count : perSection) {
                if (count > 0) {
                    n++;
                }
            }
            return n;
        }
    }

    // ── 本体 ────────────────────────────────────────────────────

    /**
     * {@code fromW} から {@code toW} への差分を求める (列スキップ + y 範囲限定)。
     *
     * <p>ブロックの選び方は {@link HyperSliceChunkGenerator#stateAt} に<b>委ねる</b>。
     * ここで独自に書き直すと「方式A のディメンションと一致するか」という検証手段
     * そのものが失われる (自分のコピー同士の比較に堕ちる)。
     */
    public static ChunkDiff compute(LevelChunk chunk, HyperTerrain terrain,
                                    double fromW, double toW) {
        return compute(chunk, terrain, fromW, toW, false);
    }

    /**
     * 全 y 総当たりの参照実装 ({@code /bstep verify} 用)。
     *
     * <p>列スキップも y 範囲限定も行わない。 <b>遅いが定義そのもの</b>なので、
     * 最適化版がこれと一致することが最適化の正しさの証明になる。
     */
    public static ChunkDiff computeReference(LevelChunk chunk, HyperTerrain terrain,
                                             double fromW, double toW) {
        return compute(chunk, terrain, fromW, toW, true);
    }

    private static ChunkDiff compute(LevelChunk chunk, HyperTerrain terrain,
                                     double fromW, double toW, boolean bruteForce) {
        int minY = chunk.getMinY();
        int maxY = minY + chunk.getHeight() - 1;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int sectionCount = chunk.getSections().length;

        ChunkDiff diff = new ChunkDiff(chunk, sectionCount);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx;
                int worldZ = baseZ + lz;

                // 生成側 (fillFromNoise) と同じクランプ。 ここを変えると地形が食い違う。
                int s0 = Math.min(terrain.surfaceY(worldX, worldZ, fromW), maxY);
                int s1 = Math.min(terrain.surfaceY(worldX, worldZ, toW), maxY);

                if (!bruteForce && s0 == s1) {
                    // underwater は surface の従属変数なので、 surface が同じなら列は完全同一。
                    continue;
                }

                boolean u0 = s0 < HyperTerrain.SEA_LEVEL;
                boolean u1 = s1 < HyperTerrain.SEA_LEVEL;

                int y0;
                int y1;
                if (bruteForce) {
                    y0 = minY;
                    y1 = maxY;
                } else {
                    int lo = Math.min(s0, s1);
                    int hi = Math.max(s0, s1);
                    y0 = Math.max(minY, lo - (HyperSliceChunkGenerator.SOIL_DEPTH - 1));
                    y1 = Math.min(maxY, hi);
                }

                boolean columnChanged = false;
                for (int y = y0; y <= y1; y++) {
                    BlockState before = HyperSliceChunkGenerator.stateAt(y, minY, s0, u0);
                    BlockState after = HyperSliceChunkGenerator.stateAt(y, minY, s1, u1);
                    if (before == after) {
                        // stateAt は defaultBlockState() の同一インスタンスを返すので参照比較でよい
                        // (生成側も `state != AIR` の参照比較を使っている)。
                        continue;
                    }
                    int sectionIndex = chunk.getSectionIndex(y);
                    if (sectionIndex < 0 || sectionIndex >= sectionCount) {
                        continue;
                    }
                    diff.add(new BlockPos(worldX, y, worldZ), after, sectionIndex);
                    columnChanged = true;
                }
                if (columnChanged) {
                    diff.changedColumns++;
                }
            }
        }
        return diff;
    }

    // ── 位相 ────────────────────────────────────────────────────

    /**
     * w が w 格子セルのどこにいるか {@code [0,1)}。 0 = 格子点上、 0.5 = セル中央。
     *
     * <p><b>負荷が位相で約 50 倍変動する</b> (実測: 格子点上 16 ブロック → セル中央 813)
     * ため、 計測値は必ずこれと一緒に読むこと。
     *
     * <h3>導出 (推測ではない)</h3>
     * {@code HyperTerrain.octave} は最も粗いオクターブで
     * {@code wi=floor(w)}, {@code f=w-wi}, {@code scaled=wi*K},
     * {@code rem=floorMod(scaled,N)}, {@code u=(rem+f*K)/N} を作り、
     * {@code fade(u-floor(u))} で w 方向を補間する。 一方
     * <pre>
     *   w*K/N = (wi+f)*K/N = (scaled + f*K)/N
     *         = floorDiv(scaled,N) + (rem + f*K)/N
     *         = 整数 + u
     * </pre>
     * なので {@code frac(w*K/N) == frac(u)}。 すなわち位相は
     * <b>{@code frac(w * K / N)} に厳密に等しい</b> (K = {@code wLatticeCount(N)})。
     * {@code :common:wDiff} が位相バケツを {@code baseUnits % cellUnits} で切っているのと同じ定義。
     */
    public static double phase(double w, int sliceCount) {
        int k = HyperTerrain.wLatticeCount(sliceCount);
        double u = w * k / (double) sliceCount;
        double p = u - Math.floor(u);
        // 数値誤差で 1.0 に達した場合を畳む (表示専用なので実害はないが 0.999.. と 0 が混ざると読みにくい)
        return p >= 1.0 ? 0.0 : p;
    }
}
