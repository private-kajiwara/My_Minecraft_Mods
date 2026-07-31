package com.kajiwara.hyperslice.worldgen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.kajiwara.hyperslice.core.HyperTerrain;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * 4 次元世界の 1 スライスを生成するチャンクジェネレータ。
 *
 * <p><b>このクラスは 1 つだけ</b>で、 スライスの違いは Codec のフィールド {@code w} に
 * 入る。 したがってコードは 1 本のまま、 ディメンション定義は w 違いの JSON N 枚で済む
 * (JSON はビルド時に {@code generateSliceData} が {@code slice_count} から生成する)。
 *
 * <p>地形は {@link HyperTerrain} 唯一に委ねる。 ここでブロックを積む以外の
 * 「スライス固有の地形ロジック」を足してはならない。
 */
public class HyperSliceChunkGenerator extends ChunkGenerator {

    /**
     * dimension JSON の {@code generator} ブロックに対応する Codec。
     *
     * <pre>
     * "generator": {
     *   "type": "hyperslice:slice",
     *   "w": 3,
     *   "slice_count": 8,
     *   "biome_source": { "type": "minecraft:fixed", "biome": "hyperslice:slice_3" }
     * }
     * </pre>
     *
     * <p>{@code slice_count} をデータ側に持たせているのが要点で、 これにより
     * Java 側に N の定数が一切要らなくなる (N はビルド時に JSON へ焼かれる)。
     */
    public static final MapCodec<HyperSliceChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(g -> g.biomeSource),
                    com.mojang.serialization.Codec.INT.fieldOf("w")
                            .forGetter(g -> g.w),
                    com.mojang.serialization.Codec.INT.fieldOf("slice_count")
                            .forGetter(g -> g.sliceCount)
            ).apply(instance, HyperSliceChunkGenerator::new));

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * 地表からこの深さまでを土に、 最上段を草にする。
     *
     * <p>【方式B 中核】{@code /bstep} の差分計算が「影響を受ける y 範囲」を
     * {@code [min(s0,s1) - (SOIL_DEPTH-1), max(s0,s1)]} と導くのに要るため public。
     * <b>値とロジックは一切変えていない</b> ({@link #stateAt} と同じく可視性のみ)。
     * 複製しなかったのは、 複製するとこの定数を変えたときに差分の範囲が黙って
     * 足りなくなる (壊れても例外が出ず、 地形が中途半端に残るだけ) ため。
     */
    public static final int SOIL_DEPTH = 4;

    private final int w;
    private final int sliceCount;

    /**
     * 地形シードを導出するための固定キー。
     *
     * <p>26.1.2 の {@link RandomState} はワールドシードを直接公開していない
     * (javap で確認済み: seed アクセサが無い)。 一方
     * {@link net.minecraft.world.level.levelgen.PositionalRandomFactory} は
     * ワールドシードから決定論的に導出されるので、 固定キーで 1 つ引けば
     * 「ワールドシードの関数である安定した long」が得られる。
     *
     * <p>{@code createState} のフックでシードを捕まえる手もあるが、 呼び出し順序に
     * 依存するため採らない。 こちらは呼ばれた時点に依存しない。
     */
    private static final String TERRAIN_SEED_KEY = "hyperslice:terrain";

    /** 導出済みの地形関数 (RandomState が同一なら使い回す)。 */
    private volatile HyperTerrain cachedTerrain;
    private volatile RandomState cachedFor;

    public HyperSliceChunkGenerator(BiomeSource biomeSource, int w, int sliceCount) {
        super(biomeSource);
        if (sliceCount < 1) {
            throw new IllegalArgumentException("slice_count must be >= 1, got " + sliceCount);
        }
        this.w = w;
        this.sliceCount = sliceCount;
    }

    /** このスライスの w。 */
    public int w() {
        return w;
    }

    /** 4 次元世界のスライス枚数 N。 */
    public int sliceCount() {
        return sliceCount;
    }

    /**
     * ワールドシードに紐づく地形関数を返す。
     *
     * <p>スライス番号 {@code w} をシードに混ぜてはならない。 混ぜると
     * スライスごとに別世界になり 4 次元性が壊れる。 w は
     * {@link HyperTerrain} の <em>引数</em> としてのみ効く。
     */
    public HyperTerrain terrainFor(RandomState randomState) {
        HyperTerrain t = cachedTerrain;
        if (t == null || cachedFor != randomState) {
            long seed = randomState.getOrCreateRandomFactory(
                    Identifier.parse(TERRAIN_SEED_KEY)).at(0, 0, 0).nextLong();
            t = new HyperTerrain(seed, sliceCount);
            cachedTerrain = t;
            cachedFor = randomState;
        }
        return t;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ── 地形本体 ────────────────────────────────────────────────

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager,
                                                        ChunkAccess chunk) {
        HyperTerrain terrain = terrainFor(randomState);

        int minY = chunk.getMinY();
        int maxY = minY + chunk.getHeight() - 1;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx;
                int worldZ = baseZ + lz;

                int surface = Math.min(terrain.surfaceY(worldX, worldZ, w), maxY);
                boolean underwater = surface < HyperTerrain.SEA_LEVEL;

                for (int y = minY; y <= maxY; y++) {
                    BlockState state = stateAt(y, minY, surface, underwater);
                    if (state == AIR) {
                        continue;
                    }
                    pos.set(worldX, y, worldZ);
                    chunk.setBlockState(pos, state);
                    oceanFloor.update(lx, y, lz, state);
                    worldSurface.update(lx, y, lz, state);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * 高さと地表からブロックを決める (地形の唯一の真実は surface 値そのもの)。
     *
     * <p>【診断実験】方式B の最小実験 ({@code /bswap}) が同じ判定を使うため public。
     * <b>ロジックと定数は一切変えていない</b> (可視性のみ)。 実験を捨てるときは
     * {@code private} へ戻すだけでよい。 実験側で複製しないのは、 複製すると
     * 「{@code /bswap 3} と {@code /hyperslice 3} の地形が一致するか」という検証が
     * 自分のコピー同士の比較に堕ちて意味を失うため。
     */
    public static BlockState stateAt(int y, int minY, int surface, boolean underwater) {
        if (y == minY) {
            return BEDROCK;
        }
        if (y > surface) {
            // 地表より上: 海面以下なら水、 それより上は空気
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

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
                             RandomState randomState) {
        int surface = terrainFor(randomState).surfaceY(x, z, w);
        // 「最初の空きブロック」を返す契約なので地表の 1 つ上。
        // 水面下は水柱の上端 (= 海面の 1 つ上) を地表扱いにする。
        int top = Math.max(surface, HyperTerrain.SEA_LEVEL);
        return Math.min(top + 1, level.getMinY() + level.getHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level,
                                     RandomState randomState) {
        int minY = level.getMinY();
        int height = level.getHeight();
        int surface = terrainFor(randomState).surfaceY(x, z, w);
        boolean underwater = surface < HyperTerrain.SEA_LEVEL;

        BlockState[] column = new BlockState[height];
        for (int i = 0; i < height; i++) {
            column[i] = stateAt(minY + i, minY, surface, underwater);
        }
        return new NoiseColumn(minY, column);
    }

    // ── v0.1 では使わない生成段 ────────────────────────────────
    //   洞窟・地表装飾・構造物・湧きは v0.1 のスコープ外 (別タスク)。
    //   地形が 4 次元として連続していることを確認できる最小構成に絞る。

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {
        // fillFromNoise で表層まで決めているので何もしない。
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk) {
        // 洞窟なし (v0.1)。
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // 敵はスコープ外 (v0.1)。
    }

    // ── 世界の寸法 ──────────────────────────────────────────────
    //   dimension_type (data/hyperslice/dimension_type/hyper.json) と
    //   HyperTerrain の定数に揃えること。

    @Override
    public int getGenDepth() {
        return HyperTerrain.WORLD_HEIGHT;
    }

    @Override
    public int getMinY() {
        return HyperTerrain.MIN_Y;
    }

    @Override
    public int getSeaLevel() {
        return HyperTerrain.SEA_LEVEL;
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos) {
        lines.add("HyperSlice w=" + w + " / N=" + sliceCount);
    }
}
