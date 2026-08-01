package com.kajiwara.hyperslice.bstep;

import java.util.Set;

import com.kajiwara.hyperslice.core.HyperTerrain;
import com.kajiwara.hyperslice.slice.LevelW;
import com.kajiwara.hyperslice.slice.SliceTeleporter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>【方式B 中核】</b> 地形に乗る — w が動いて地面が上がってきたプレイヤーを新しい地表へ載せる。
 *
 * <h2>これは埋没回避であると同時に演出である</h2>
 * w を動かすと地表高度が変わる。 地面が上がればプレイヤーは地形に埋まり窒息する。
 * ({@code BStepExperiment.SET_BLOCK_FLAGS} = 818 が抑止しているのは近傍更新であって、
 * プレイヤーの埋没とは無関係。)
 *
 * <p>同時に、 地形が波打つのに合わせて上下に揺られること自体が
 * 「4 次元を移動している」という体験の中身でもある。 だから単に埋没を避けるのではなく
 * <b>「地形に乗る」挙動</b>にしてあり、 方式と量を下の定数で調整できる。
 *
 * <h2>規約</h2>
 * <ul>
 *   <li><b>上がるときだけ動かす。</b> 地面が下がったときは何もしない (自然落下に任せる)</li>
 *   <li><b>本当に埋まっているときだけ動かす。</b> 「地表より下にいる」で判定してはならない。
 *       それだと坑道を掘って潜っている人が毎ティック地上へ引き上げられる</li>
 *   <li>持ち上げ先は<b>地形関数から直に</b>引く ({@link HyperTerrain#surfaceY})。 地形は
 *       heightfield (洞窟・オーバーハングが無い) なので、 これが厳密に「新しい地表の上」</li>
 * </ul>
 *
 * <h2>差し込み位置</h2>
 * 差分適用 ({@code BStepSession.apply}) の中ではなく<b>サーバーティック</b>から呼ぶ。
 * <ol>
 *   <li>{@link Mode#SMOOTH} は毎ティックの追従が要る (ステップの無いティックでも動く)</li>
 *   <li>他プレイヤーが動かした w や、 遅れていたチャンクが追い付いた場合も拾える</li>
 *   <li>{@code applyNs} の計測値に別物のコストを混ぜない (過去の実測値と比較可能なまま保つ)</li>
 * </ol>
 *
 * <h2>プレイヤーを動かす手段</h2>
 * {@code player.connection.teleport(PositionMoveRotation, Set<Relative>)}。 これは
 * サーバー側の位置更新 ({@code teleportSetPosition}) とクライアントへの
 * {@code ClientboundPlayerPositionPacket} 送出を<b>両方</b>行う唯一の入口
 * (26.1.2 を逆アセンブルして確認)。 サーバー側で {@code setPos} するだけでは、
 * クライアントが次に送ってくる位置で元に戻される。
 *
 * <p>位置・回転・速度をすべて<b>相対</b>指定にして dy だけを足すので、 視点も水平移動も
 * 慣性も保たれる。 絶対座標で送ると、 クライアントが 1 ティックぶん先に進んでいる水平位置が
 * 巻き戻って引っかかる。
 *
 * <p><b>既知の制約</b>: teleport はクライアントの ack を待つ間 (
 * {@code awaitingPositionFromClient}) 移動パケットを捨てる。 往復遅延の大きいマルチでは
 * 持ち上げのたびに水平移動が一瞬詰まりうる。 v0.1 はシングルプレイ前提なので許容している。
 */
public final class WRide {

    // =================================================================
    // ── 人間が触る定数 (体験を決めるのはここ) ──
    // =================================================================

    /** 持ち上げ方 (下記 {@link Mode})。 */
    public static final Mode MODE = Mode.SMOOTH;

    /**
     * {@link Mode#SMOOTH} で 1 ティックに持ち上げる量 [ブロック/tick]。
     *
     * <p>既定 {@code 0.5} = 1 ブロックの上昇を <b>2 ティック</b>で消化する。
     *
     * <p><b>大きくするほど {@link Mode#INSTANT} に近づき、 小さくするほど引っかかる。</b>
     * 持ち上げ切るまでの間プレイヤーは足元が地形に埋まったままで、 その間は
     * クライアント側の衝突判定で<b>水平移動できない</b> (1 ブロックの段差は
     * バニラの自動踏み上がり高 0.6 を超えるため)。 4 ティック掛ける ({@code 0.25}) と
     * ステップ間隔の大半を埋まった状態で過ごすことになる。
     */
    public static final double LIFT_PER_TICK = 0.5;

    /**
     * この量以上の持ち上げが要るときは {@link Mode#SMOOTH} でも<b>即座に</b>出す [ブロック]。
     *
     * <p>既定 {@code 1.6} は<b>窒息の閾値そのもの</b>である。 プレイヤーの目線高は 1.62 なので、
     * 必要な持ち上げが 1.6 未満なら埋まっているのは足元だけで、 目線のブロックは空いている
     * = {@code isInWall} にならず窒息ダメージが入らない。 1.6 以上 (頭まで埋まる) を
     * 数ティック掛けて出すと、 その間ダメージが入り続ける。
     *
     * <p>つまりこの定数は「小さな上昇だけを滑らかにする」ためのもので、
     * <b>演出と無傷を両立させている</b>。
     */
    public static final double INSTANT_ABOVE = 1.6;

    /**
     * 1 回の持ち上げ量の上限 [ブロック]。
     *
     * <p>異常時 (遅れていたチャンクが巨大な蓄積分で一気に追い付いた等) に
     * プレイヤーが天井まで飛ばされるのを防ぐ。 上限に当たっても埋まったままなら
     * 次のティックで続きが出るので、 「上がりきらない」ことはない。
     */
    public static final double MAX_LIFT = 8.0;

    // =================================================================

    /** 持ち上げ方。 */
    public enum Mode {
        /** その場で新しい地表の上へ出す。 埋まる時間はゼロ。 段差はそのまま体感に出る。 */
        INSTANT,
        /** {@link #LIFT_PER_TICK} ずつ追従する。 揺られる感触が出る。 */
        SMOOTH
    }

    /**
     * 位置・回転・速度をすべて相対にする指定。
     *
     * <p>{@code ROTATE_DELTA} は入れていない (回転差が 0 なので効果は無いが、
     * 「何を相対にしているか」を読める形に留めるため)。
     */
    private static final Set<Relative> RELATIVE_LIFT = Set.of(
            Relative.X, Relative.Y, Relative.Z,
            Relative.Y_ROT, Relative.X_ROT,
            Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);

    /**
     * 境界がブロック面にぴったり乗っているときに、 隣のブロックを誤って
     * 「めり込んでいる」と数えないための収縮量。
     */
    private static final double EDGE_EPSILON = 1.0e-7;

    /** これ以下の持ち上げは出さない (数値誤差でパケットを毎ティック撃たないため)。 */
    private static final double LIFT_EPSILON = 1.0e-3;

    private WRide() {
    }

    /**
     * 毎ティック 1 回。 {@link BStepSession} の END_SERVER_TICK から呼ぶ。
     *
     * <p>対象は HyperSlice のディメンションにいる全プレイヤー。 除くのは 2 つだけ:
     * <ul>
     *   <li>スペクテイター … 地形をすり抜けるので埋まりようがない</li>
     *   <li>何かに乗っている人 … 乗り物の位置が正なので、 乗員だけ持ち上げると分離する</li>
     * </ul>
     * クリエイティブ飛行は特別扱いしない (飛んでいれば埋まらないので判定で自然に外れ、
     * 埋まったなら同じ処理でよい)。
     */
    static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            if (player.isSpectator() || player.isPassenger() || !SliceTeleporter.isSlice(level)) {
                continue;
            }
            double w = LevelW.terrainW(level);
            if (Double.isNaN(w)) {
                continue;
            }
            HyperTerrain terrain = BStepRunner.terrainOf(level);
            if (terrain == null) {
                continue;
            }
            ride(level, player, terrain, w);
        }
    }

    private static void ride(ServerLevel level, ServerPlayer player,
                             HyperTerrain terrain, double w) {
        AABB box = player.getBoundingBox().deflate(EDGE_EPSILON);
        if (!embedded(level, box)) {
            return;
        }

        double need = groundTop(level, terrain, w, box) - player.getY();
        if (need <= LIFT_EPSILON) {
            // 地面が下がった / もう地表の上にいる → 何もしない (落下は自然に任せる)。
            return;
        }
        need = Math.min(need, MAX_LIFT);

        double lift = (MODE == Mode.INSTANT || need >= INSTANT_ABOVE)
                ? need
                : Math.min(need, LIFT_PER_TICK);

        player.connection.teleport(
                new PositionMoveRotation(new Vec3(0.0, lift, 0.0), Vec3.ZERO, 0.0f, 0.0f),
                RELATIVE_LIFT);
    }

    /**
     * プレイヤーが地形に埋まっているか。
     *
     * <p>占有 AABB に重なるブロックのどれかが衝突形状を持てば埋まっている。
     * {@code SliceTeleporter.isFree} と同じ判定の書き方 (生成地形は全て
     * 完全立方体か空気か水なので、 形状の厳密な交差判定までは要らない)。
     */
    private static boolean embedded(ServerLevel level, AABB box) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x1 = Mth.floor(box.maxX);
        int y1 = Mth.floor(box.maxY);
        int z1 = Mth.floor(box.maxZ);
        for (int x = Mth.floor(box.minX); x <= x1; x++) {
            for (int y = Mth.floor(box.minY); y <= y1; y++) {
                for (int z = Mth.floor(box.minZ); z <= z1; z++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 新しい地表の上の y (= 立てる高さ)。
     *
     * <p>AABB が跨ぐ列すべての地表高度の<b>最大</b>を採る (斜面では高い側に乗る)。
     * {@code min(surfaceY, maxY)} のクランプは生成側 ({@code fillFromNoise}) と同一で、
     * ここを揃えないと世界の上端付近で地形と食い違う。
     */
    private static double groundTop(ServerLevel level, HyperTerrain terrain, double w, AABB box) {
        int maxY = level.getMaxY();
        int top = level.getMinY();
        int x1 = Mth.floor(box.maxX);
        int z1 = Mth.floor(box.maxZ);
        for (int x = Mth.floor(box.minX); x <= x1; x++) {
            for (int z = Mth.floor(box.minZ); z <= z1; z++) {
                top = Math.max(top, Math.min(terrain.surfaceY(x, z, w), maxY));
            }
        }
        return top + 1;
    }
}
