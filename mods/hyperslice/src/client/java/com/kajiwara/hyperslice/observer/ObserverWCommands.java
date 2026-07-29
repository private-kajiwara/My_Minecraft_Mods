package com.kajiwara.hyperslice.observer;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * <b>【診断実験】</b> {@code /observerw} — 観測面 w の直接指定とリセット。
 *
 * <p><b>クライアントコマンド</b>である点が要点。 {@link ObserverW} はクライアント権威なので、
 * サーバコマンドにするとパケットが必要になる (新しいパケット型を足さない方針に反する)。
 * WorldChange の {@code /worldChange} と同じ {@link ClientCommandRegistrationCallback} 経路。
 *
 * <pre>
 *   /observerw           現在値を表示
 *   /observerw &lt;value&gt;   直接指定 (特定値での静止確認用)
 *   /observerw reset     所属スライス本来の観測面 (slice + 0.5) へ戻す
 * </pre>
 *
 * <p>{@link ObserverW#EXPERIMENT_ENABLED} が {@code false} なら<b>登録もされない</b>。
 */
public final class ObserverWCommands {

    private ObserverWCommands() {
    }

    /** {@code HyperSliceClient} から 1 回だけ呼ぶ。 実験が無効なら何も登録しない。 */
    public static void register() {
        if (!ObserverW.EXPERIMENT_ENABLED) {
            return;
        }
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> build(dispatcher));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("observerw");

        root.executes(ObserverWCommands::show);
        root.then(ClientCommands.literal("reset").executes(c -> {
            ObserverW.reset();
            return show(c);
        }));
        root.then(ClientCommands.argument("value", DoubleArgumentType.doubleArg())
                .executes(c -> {
                    ObserverW.set(DoubleArgumentType.getDouble(c, "value"));
                    return show(c);
                }));

        dispatcher.register(root);
    }

    private static int show(CommandContext<FabricClientCommandSource> c) {
        double w = ObserverW.get();
        if (Double.isNaN(w)) {
            c.getSource().sendError(Component.translatable("hyperslice.observer.not_in_hyperslice"));
            return 0;
        }
        c.getSource().sendFeedback(Component.translatable("hyperslice.observer.value",
                fmt(w), fmt(ObserverW.nominalPlane())));
        return 1;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
