package com.kajiwara.hyperslice.command;

import com.kajiwara.hyperslice.bstep.BStepCommands;
import com.kajiwara.hyperslice.bstep.BStepExperiment;
import com.kajiwara.hyperslice.bstep.BStepSession;
import com.kajiwara.hyperslice.bswap.BSwapCommands;
import com.kajiwara.hyperslice.bswap.BSwapExperiment;
import com.kajiwara.hyperslice.core.SliceRegistry;
import com.kajiwara.hyperslice.slice.SliceTeleporter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 開発用サーバーコマンド {@code /hyperslice <n>} (Brigadier)。
 *
 * <p>WorldChange の {@code /worldChange} は <b>クライアント</b>コマンド
 * ({@code ClientCommandRegistrationCallback} + {@code FabricClientCommandSource}) だが、
 * こちらはディメンションを跨ぐ実際のテレポートを行うため <b>サーバー</b>コマンド
 * ({@link CommandRegistrationCallback} + {@link CommandSourceStack}) である。
 * 両者は別系統なので混同しないこと。
 *
 * <p>権限は {@code LEVEL_ALL} (誰でも実行可)。 v0.1 は開発用途で、 かつ
 * シングルプレイ前提のため。 マルチで運用するなら
 * {@code Commands.LEVEL_GAMEMASTERS} 等へ引き上げる。
 */
public final class HyperSliceCommands {

    private HyperSliceCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            build(dispatcher);
            // 4 次元エンティティの開発用コマンドを相乗りで登録する (イベント登録を増やさない)。
            HyperEntityCommands.build(dispatcher);
            // 【診断実験】方式B の最小実験。 定数畳み込みで消えるよう呼び出し側で判定する
            // (false ならこのクラスのバイトコードから bswap への参照が 0 件になる)。
            if (BSwapExperiment.EXPERIMENT_ENABLED) {
                BSwapCommands.build(dispatcher);
            }
            // 【方式B 中核】差分適用ループ。 同じく呼び出し側で定数判定する。
            if (BStepExperiment.EXPERIMENT_ENABLED) {
                BStepCommands.build(dispatcher);
            }
        });

        // 【方式B 中核】連続ステップの駆動とセッションの後始末。 コマンド登録コールバックの
        // 中でやると /reload のたびに二重登録されるのでここ (init 時 1 回)。
        if (BStepExperiment.EXPERIMENT_ENABLED) {
            BStepSession.register();
        }
    }

    private static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("hyperslice")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL));

        root.executes(HyperSliceCommands::status);
        root.then(Commands.argument("w", IntegerArgumentType.integer())
                .executes(c -> move(c, IntegerArgumentType.getInteger(c, "w"))));

        dispatcher.register(root);
    }

    /** 引数なし: 現在の w と N を表示する。 */
    private static int status(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        MinecraftServer server = src.getServer();

        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.translatable("hyperslice.command.player_only"));
            return 0;
        }

        int w = SliceTeleporter.sliceWOf(player.level());
        int n = SliceTeleporter.sliceCount(server);
        if (w < 0) {
            src.sendFailure(Component.translatable("hyperslice.command.not_in_hyperslice", n));
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("hyperslice.command.status", w, n), false);
        return 1;
    }

    /** {@code /hyperslice <n>}: スライス n へ移動する。 */
    private static int move(CommandContext<CommandSourceStack> c, int requested) {
        CommandSourceStack src = c.getSource();
        MinecraftServer server = src.getServer();

        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.translatable("hyperslice.command.player_only"));
            return 0;
        }

        int n = SliceTeleporter.sliceCount(server);
        if (n < 1) {
            src.sendFailure(Component.translatable("hyperslice.command.no_slices"));
            return 0;
        }

        // 0..N-1 を巡回させる。 w=N-1 と w=0 は地形上も連続しているので、
        // これはワープではなく単なる隣接移動。
        int targetW = SliceRegistry.wrap(requested, n);

        SliceTeleporter.Outcome outcome = SliceTeleporter.moveTo(player, targetW);
        switch (outcome.result()) {
            case MOVED -> {
                src.sendSuccess(() -> Component.translatable("hyperslice.command.moved", targetW, n), false);
                return 1;
            }
            case ALREADY_THERE -> {
                src.sendSuccess(() -> Component.translatable("hyperslice.command.already_there", targetW), false);
                return 0;
            }
            case BLOCKED -> {
                src.sendFailure(Component.translatable("hyperslice.command.blocked",
                        targetW, SliceTeleporter.SAFE_SEARCH_RADIUS));
                return 0;
            }
            case NO_SUCH_SLICE -> {
                src.sendFailure(Component.translatable("hyperslice.command.no_such_slice", targetW, n));
                return 0;
            }
            default -> {
                src.sendFailure(Component.translatable("hyperslice.command.no_slices"));
                return 0;
            }
        }
    }
}
