package com.kajiwara.hyperslice.command;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.kajiwara.hyperslice.core.HyperEntityRecord;
import com.kajiwara.hyperslice.core.HyperEntityType;
import com.kajiwara.hyperslice.core.HyperVec;
import com.kajiwara.hyperslice.entity.HyperEntityService;
import com.kajiwara.hyperslice.slice.LevelW;
import com.kajiwara.hyperslice.slice.SliceTeleporter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 開発用サーバーコマンド {@code /hyperentity}。
 *
 * <p>権限は v0.1 の {@code /hyperslice} と同じ {@code LEVEL_ALL}。
 * 登録は {@link HyperSliceCommands} の {@code CommandRegistrationCallback} に相乗りする
 * (イベント登録を増やさない)。
 *
 * <pre>
 *   /hyperentity spawn &lt;type&gt; [w] [wVelocity]   実行者の位置に生成
 *   /hyperentity spread &lt;count&gt; &lt;spacing&gt; [radius]
 *                                                静止球を w 方向 + 水平円周上に散らす
 *   /hyperentity list                            近傍のレコードを w と dw 付きで一覧
 *   /hyperentity clear                           全削除 (試行の高速化に必須)
 * </pre>
 */
public final class HyperEntityCommands {

    /** {@code list} が拾う 3 次元距離 [ブロック]。 */
    private static final double LIST_RADIUS = 128.0;

    /** {@code spread} で一度に置ける上限 (暴発防止)。 */
    private static final int MAX_SPREAD = 64;

    /**
     * {@code spread} の水平散らし半径の既定値 [ブロック]。
     *
     * <p>アルファブレンドを使わない仕様 (原則2) では、 同じ位置に重ねた球は
     * 一番大きい 1 個しか見えない。 「別々の場所の球が別々の位相で膨らみ縮む」ことこそ
     * この実験の判定対象なので、 w だけでなく水平にも散らす必要がある。
     * 数値の調整はこの定数 1 箇所 (コマンド引数でも上書き可)。
     */
    private static final double DEFAULT_SPREAD_RADIUS = 6.0;

    private HyperEntityCommands() {
    }

    static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("hyperentity")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL));

        root.then(Commands.literal("spawn")
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests(HyperEntityCommands::suggestTypes)
                        .executes(c -> spawn(c, typeArg(c), null, null))
                        .then(Commands.argument("w", DoubleArgumentType.doubleArg())
                                .executes(c -> spawn(c, typeArg(c),
                                        DoubleArgumentType.getDouble(c, "w"), null))
                                .then(Commands.argument("wVelocity", DoubleArgumentType.doubleArg())
                                        .executes(c -> spawn(c, typeArg(c),
                                                DoubleArgumentType.getDouble(c, "w"),
                                                DoubleArgumentType.getDouble(c, "wVelocity")))))));

        root.then(Commands.literal("spread")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_SPREAD))
                        .then(Commands.argument("spacing", DoubleArgumentType.doubleArg(0.0))
                                .executes(c -> spread(c,
                                        IntegerArgumentType.getInteger(c, "count"),
                                        DoubleArgumentType.getDouble(c, "spacing"),
                                        DEFAULT_SPREAD_RADIUS))
                                // 半径の上限は同期半径。 これを超えると置いた球が
                                // そもそも送られてこず「消えた」ようにしか見えない。
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(
                                                0.0, HyperEntityService.SYNC_RADIUS))
                                        .executes(c -> spread(c,
                                                IntegerArgumentType.getInteger(c, "count"),
                                                DoubleArgumentType.getDouble(c, "spacing"),
                                                DoubleArgumentType.getDouble(c, "radius")))))));

        root.then(Commands.literal("list").executes(HyperEntityCommands::list));
        root.then(Commands.literal("clear").executes(HyperEntityCommands::clear));

        dispatcher.register(root);
    }

    private static String typeArg(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "type");
    }

    // ── spawn ───────────────────────────────────────────────────

    private static int spawn(CommandContext<CommandSourceStack> c, String typeId,
                             Double wOverride, Double wVelocityOverride) {
        CommandSourceStack src = c.getSource();

        ServerPlayer player = playerOrFail(src);
        if (player == null) {
            return 0;
        }

        HyperEntityType type = HyperEntityType.byId(typeId.toLowerCase(Locale.ROOT));
        if (type == null) {
            src.sendFailure(Component.translatable("hyperslice.entity.unknown_type", typeId));
            return 0;
        }

        int slice = SliceTeleporter.sliceWOf(player.level());
        if (slice < 0) {
            src.sendFailure(Component.translatable("hyperslice.entity.not_in_hyperslice"));
            return 0;
        }

        // w 未指定なら「観測面の手前ぎりぎり」から出発させ、 通過する様子を最初から見せる。
        double planeW = LevelW.observationPlane(player.level());
        double w = (wOverride != null) ? wOverride : planeW - type.wThickness() * 0.5;
        double wVel = (wVelocityOverride != null)
                ? wVelocityOverride : HyperEntityType.DEFAULT_W_VELOCITY;

        UUID id = HyperEntityService.get().manager().spawn(
                type,
                new HyperVec(player.getX(), player.getY(), player.getZ(), w),
                new HyperVec(0, 0, 0, wVel));

        src.sendSuccess(() -> Component.translatable("hyperslice.entity.spawned",
                Component.translatable(type.translationKey()),
                fmt(w), fmt(wVel), shortId(id)), false);
        return 1;
    }

    // ── spread ──────────────────────────────────────────────────

    /**
     * <b>【診断実験用】</b> 静止球を w 方向に等間隔で並べつつ、 水平にも円状に散らす。
     *
     * <p>観測面 w の連続移動を判定するには「w 方向に散らばった<b>静止</b>球を複数同時に見る」
     * 必要がある。 1 体ずつ spawn するのは手数が多すぎるのでまとめて置く。
     * 全て {@code wVelocity = 0}。
     *
     * <p><b>水平にも散らす理由</b>: 消滅をアルファブレンドではなく縮小だけで表現する
     * (原則2) ため、 同じ (x,y,z) に重ねると同心球になり<b>一番大きい 1 個しか見えない</b>。
     * それでは「別々の場所の球が別々の位相で同時に膨らみ縮む」という、
     * この実験の判定の核心が観察できない。 そこで実行者を中心とした水平円周上、
     * {@code i / count * 2π} の角度に並べる。
     *
     * <p>y は実行者と同じ。 地形にめり込むことはあるが診断用途では許容する。
     *
     * <p>w の中心は<b>今の観測面</b> ({@code LevelW.observationPlane})。 方式B 統合により
     * サーバーが w の権威を持つので、 プレイヤーが Page Up/Down でずらした先で撃っても
     * 「今見ている面」を中心に並ぶ。 統合前はサーバーがクライアント権威の
     * {@code ObserverW} を知らず、 常にスライス本来の面が中心になっていた。
     */
    private static int spread(CommandContext<CommandSourceStack> c, int count, double spacing,
                              double radius) {
        CommandSourceStack src = c.getSource();

        ServerPlayer player = playerOrFail(src);
        if (player == null) {
            return 0;
        }

        int slice = SliceTeleporter.sliceWOf(player.level());
        if (slice < 0) {
            src.sendFailure(Component.translatable("hyperslice.entity.not_in_hyperslice"));
            return 0;
        }
        double centreW = LevelW.observationPlane(player.level());

        // count 体を centreW を中心に等間隔で並べる (偶数個なら中心を挟む形になる)。
        double firstW = centreW - spacing * (count - 1) / 2.0;

        double centreX = player.getX();
        double centreY = player.getY();
        double centreZ = player.getZ();

        for (int i = 0; i < count; i++) {
            // 水平円周上に等角で配置する。 w のずれと角度が 1 対 1 に対応するので、
            // 「どの向きの球が今いちばん大きいか」で観測面の位置が読み取れる。
            double angle = (2.0 * Math.PI * i) / count;
            double x = centreX + radius * Math.cos(angle);
            double z = centreZ + radius * Math.sin(angle);

            HyperEntityService.get().manager().spawn(
                    HyperEntityType.DRIFTER,
                    new HyperVec(x, centreY, z, firstW + spacing * i),
                    HyperVec.ZERO);   // 静止
        }

        double lastW = firstW + spacing * (count - 1);
        src.sendSuccess(() -> Component.translatable("hyperslice.entity.spread",
                count, fmt(firstW), fmt(lastW), fmt(centreW), fmt(radius)), false);
        return count;
    }

    // ── list ────────────────────────────────────────────────────

    private static int list(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();

        ServerPlayer player = playerOrFail(src);
        if (player == null) {
            return 0;
        }

        int slice = SliceTeleporter.sliceWOf(player.level());
        if (slice < 0) {
            src.sendFailure(Component.translatable("hyperslice.entity.not_in_hyperslice"));
            return 0;
        }
        double planeW = LevelW.observationPlane(player.level());

        // 交差していないものも見たいので margin を大きく取る (list は診断用)。
        var records = HyperEntityService.get().manager().visibleFrom(
                player.getX(), player.getY(), player.getZ(),
                planeW, LIST_RADIUS, Double.MAX_VALUE / 4);

        int total = HyperEntityService.get().manager().count();
        src.sendSuccess(() -> Component.translatable("hyperslice.entity.list_header",
                records.size(), total, fmt(planeW)), false);

        for (HyperEntityRecord r : records) {
            double dw = r.dw(planeW);
            src.sendSuccess(() -> Component.translatable("hyperslice.entity.list_line",
                    shortId(r.id()),
                    Component.translatable(r.type().translationKey()),
                    fmt(r.position().w()), fmt(dw), fmt(r.crossSectionRadius(planeW))), false);
        }
        return records.size();
    }

    // ── clear ───────────────────────────────────────────────────

    private static int clear(CommandContext<CommandSourceStack> c) {
        int n = HyperEntityService.get().manager().clear();
        c.getSource().sendSuccess(
                () -> Component.translatable("hyperslice.entity.cleared", n), false);
        return n;
    }

    // ── ヘルパ ──────────────────────────────────────────────────

    private static ServerPlayer playerOrFail(CommandSourceStack src) {
        try {
            return src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.translatable("hyperslice.command.player_only"));
            return null;
        }
    }

    private static CompletableFuture<Suggestions> suggestTypes(
            CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);
        for (HyperEntityType t : HyperEntityType.values()) {
            if (t.id().startsWith(rem)) {
                b.suggest(t.id());
            }
        }
        return b.buildFuture();
    }

    /** 表示用の短い数値 (翻訳引数は String で渡す)。 */
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** UUID の先頭 8 文字 (ログを追うのに十分・全体は長すぎる)。 */
    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
