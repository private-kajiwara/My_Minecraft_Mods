package com.kajiwara.hyperslice.slice;

import java.util.Optional;

import com.kajiwara.hyperslice.core.SliceRegistry;
import com.kajiwara.hyperslice.worldgen.HyperSliceChunkGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * スライス間移動。 <b>同一プレイヤーエンティティのまま</b> レベルだけを差し替える。
 *
 * <p>x, y, z / 視点 / 速度は保持する ({@link TeleportTransition} が位置・
 * deltaMovement・yRot/xRot をまとめて運ぶ)。 インベントリは同一エンティティなので自動。
 *
 * <p>移動先チャンクを先に force-load してから飛ばす (ヒッチ対策)。
 * また移動先でプレイヤー占有セルが固体なら上下を探索し、 空きが無ければ
 * <b>移動を拒否</b>する (地形を掘って通すことはしない)。
 */
public final class SliceTeleporter {

    /** 安全な着地点を探す縦方向の探索範囲 [ブロック]。 */
    public static final int SAFE_SEARCH_RADIUS = 5;

    /** プレイヤーが占有する縦セル数 (足元 + 頭)。 */
    private static final int PLAYER_HEIGHT_CELLS = 2;

    /** 移動先チャンクを先読みする半径 [チャンク]。 */
    private static final int PRELOAD_RADIUS = 1;

    private SliceTeleporter() {
    }

    /** 移動の結果。 */
    public enum Result {
        /** 移動した。 */
        MOVED,
        /** すでにそのスライスにいる。 */
        ALREADY_THERE,
        /** そのスライスのディメンションがサーバーに存在しない。 */
        NO_SUCH_SLICE,
        /** 移動先が固体で埋まっており、 安全な着地点が見つからなかった。 */
        BLOCKED,
        /** HyperSlice のスライス内にいないため w の概念が無い。 */
        NOT_IN_HYPERSLICE
    }

    /** 移動結果と、 表示に使う付随情報。 */
    public record Outcome(Result result, int targetW) {
    }

    // ── スライスの同定 ──────────────────────────────────────────

    /** そのレベルが HyperSlice のスライスなら w を、 違えば {@code -1} を返す。 */
    public static int sliceWOf(Level level) {
        Identifier id = level.dimension().identifier();
        if (!SliceRegistry.NAMESPACE.equals(id.getNamespace())) {
            return -1;
        }
        return SliceRegistry.wFromPath(id.getPath());
    }

    /** そのレベルが HyperSlice のスライスか。 */
    public static boolean isSlice(Level level) {
        return sliceWOf(level) >= 0;
    }

    /**
     * サーバーに実在するスライス枚数 N。
     *
     * <p>N は Java 側の定数ではなくデータ側の値なので、 実在するレベルの
     * ChunkGenerator から読む (実行時の実態が正)。 見つからなければ
     * {@code hyperslice:slice_*} レベルの数を数える。
     */
    public static int sliceCount(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (isSlice(level)
                    && level.getChunkSource().getGenerator() instanceof HyperSliceChunkGenerator gen) {
                return gen.sliceCount();
            }
        }
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (isSlice(level)) {
                count++;
            }
        }
        return count;
    }

    /** スライス {@code w} のレベルを取得する。 */
    public static Optional<ServerLevel> sliceLevel(MinecraftServer server, int w) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath(SliceRegistry.NAMESPACE, SliceRegistry.slicePath(w)));
        return Optional.ofNullable(server.getLevel(key));
    }

    // ── 移動 ────────────────────────────────────────────────────

    /**
     * プレイヤーをスライス {@code targetW} へ移す。
     *
     * <p>失敗時は何も起きない (クラッシュさせない)。 呼び出し側が
     * {@link Outcome} を見て翻訳キー経由のメッセージを出す。
     */
    public static Outcome moveTo(ServerPlayer player, int targetW) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return new Outcome(Result.NO_SUCH_SLICE, targetW);
        }

        Optional<ServerLevel> maybeTarget = sliceLevel(server, targetW);
        if (maybeTarget.isEmpty()) {
            return new Outcome(Result.NO_SUCH_SLICE, targetW);
        }
        ServerLevel target = maybeTarget.get();

        if (player.level() == target) {
            return new Outcome(Result.ALREADY_THERE, targetW);
        }

        double x = player.getX();
        double z = player.getZ();
        int y = (int) Math.floor(player.getY());

        // ヒッチ対策: 移動先チャンクを先に読み込んでから飛ばす。
        //
        //   ここで addTicketAndLoadWithRadius(...).join() としてはならない。
        //   本メソッドはサーバースレッドで走るが、 チャンク生成タスクを実際に
        //   進めるのもサーバースレッドなので、 素の join() は「自分が処理すべき
        //   仕事」を待って固まる = デッドロックする。
        //
        //   ServerChunkCache.getChunk(...) は待機中に MainThreadExecutor.managedBlock で
        //   タスクキューを回してから join する (javap で確認済み) ため安全。
        //   よって「チケットを張る (非ブロッキング)」→「managed な同期ロード」の順にする。
        ChunkPos chunkPos = ChunkPos.containing(BlockPos.containing(x, y, z));
        target.getChunkSource().addTicketWithRadius(TicketType.PORTAL, chunkPos, PRELOAD_RADIUS);
        target.getChunk(chunkPos.x(), chunkPos.z());

        // 安全判定: 占有セルが固体なら上下 ±SAFE_SEARCH_RADIUS を探す。
        OptionalIntLike safeY = findSafeY(target, x, y, z);
        if (!safeY.present()) {
            return new Outcome(Result.BLOCKED, targetW);
        }

        Vec3 pos = new Vec3(x, safeY.value(), z);
        TeleportTransition transition = new TeleportTransition(
                target,
                pos,
                player.getDeltaMovement(),   // 速度を保持
                player.getYRot(),            // 視点 (水平) を保持
                player.getXRot(),            // 視点 (垂直) を保持
                TeleportTransition.DO_NOTHING);
        player.teleport(transition);

        return new Outcome(Result.MOVED, targetW);
    }

    // ── 安全判定 ────────────────────────────────────────────────

    /** {@code OptionalInt} が record パターンに使いにくいので最小の代替。 */
    private record OptionalIntLike(boolean present, int value) {
        static OptionalIntLike of(int v) {
            return new OptionalIntLike(true, v);
        }

        static OptionalIntLike empty() {
            return new OptionalIntLike(false, 0);
        }
    }

    /**
     * 着地可能な y を探す。
     *
     * <p>まず元の高さをそのまま試し、 塞がっていれば {@code ±SAFE_SEARCH_RADIUS} を
     * 近い順に探す。 全て塞がっていれば空を返す (= 移動を拒否する)。
     * 地形を掘って通すことはしない。
     */
    private static OptionalIntLike findSafeY(ServerLevel level, double x, int y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        if (isFree(level, bx, y, bz)) {
            return OptionalIntLike.of(y);
        }
        for (int d = 1; d <= SAFE_SEARCH_RADIUS; d++) {
            int up = y + d;
            if (isFree(level, bx, up, bz)) {
                return OptionalIntLike.of(up);
            }
            int down = y - d;
            if (isFree(level, bx, down, bz)) {
                return OptionalIntLike.of(down);
            }
        }
        return OptionalIntLike.empty();
    }

    /** {@code (bx, by, bz)} にプレイヤーが立てるか (足元と頭が両方空いているか)。 */
    private static boolean isFree(ServerLevel level, int bx, int by, int bz) {
        if (by < level.getMinY() || by + PLAYER_HEIGHT_CELLS - 1 > level.getMaxY()) {
            return false;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < PLAYER_HEIGHT_CELLS; i++) {
            pos.set(bx, by + i, bz);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
