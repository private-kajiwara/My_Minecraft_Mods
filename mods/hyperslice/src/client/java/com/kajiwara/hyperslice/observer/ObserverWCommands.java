package com.kajiwara.hyperslice.observer;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@code /observerw} — 今の観測面 w の<b>表示</b>。
 *
 * <pre>
 *   /observerw           サーバーから届いている観測面 w / スライス本来の w / ズレ を表示
 * </pre>
 *
 * <h2>読み取り専用になった理由</h2>
 * 方式B の統合で w の権威が<b>サーバー</b>へ移った。 クライアントコマンドから値を
 * 書き換えると、 サーバーが次に配る値で即座に上書きされる (= 効かないコマンドが残る)。
 * 絶対値の指定はサーバー側の <b>{@code /bstep to <w>}</b> に移してある。
 * そちらなら地形も同時に追従するので、 特定の w で静止させて見比べる用途が本当に成立する。
 *
 * <p>クライアントコマンドのまま残しているのは、 これがクライアントが今どう見えているかを
 * 出すもの (= サーバーへ問い合わせる意味がない) だから。 WorldChange の
 * {@code /worldChange} と同じ {@link ClientCommandRegistrationCallback} 経路。
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
