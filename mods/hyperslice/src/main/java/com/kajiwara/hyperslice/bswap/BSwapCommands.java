package com.kajiwara.hyperslice.bswap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import com.kajiwara.hyperslice.slice.SliceTeleporter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>【診断実験】</b> 開発用サーバーコマンド {@code /bswap}。
 *
 * <pre>
 *   /bswap &lt;w&gt; [radius]            差し替えて各フェーズを ms で報告 (radius 既定 0 = 1 チャンク)
 *   /bswap gen &lt;parallel|sequential&gt;   生成の並列化を切り替える
 *   /bswap light &lt;wait|nowait&gt;         光の完了を待つかを切り替える
 *   /bswap reset                       計測履歴 (中央値の母数) を捨てる
 * </pre>
 *
 * <p>登録は {@code HyperSliceCommands} の {@code CommandRegistrationCallback} に相乗りする
 * (イベント登録を増やさない)。 {@link BSwapExperiment#EXPERIMENT_ENABLED} が
 * {@code false} なら<b>登録もされない</b>。
 *
 * <h2>正解データとの比較</h2>
 * 差し替えが視覚的に正しいかは方式A が正解データを持っている。
 * {@code /bswap 3} (その場で地形だけ w=3 に) と {@code /hyperslice 3} (本当に w=3 へ行く) で
 * 同じ座標の地形が一致するはず。 一致しなければ差し替えロジックが誤っている。
 */
public final class BSwapCommands {

    /**
     * 直近の計測履歴。
     *
     * <p><b>初回はクラスロードと JIT で必ず遅くなる</b>ので、 1 回の値だけを見て
     * 判断してはならない。 毎回この履歴の中央値を併記する。
     */
    private static final Deque<BSwapRunner.Timings> HISTORY = new ArrayDeque<>();

    private BSwapCommands() {
    }

    /** {@code HyperSliceCommands} から呼ぶ。 実験が無効なら何も登録しない。 */
    public static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!BSwapExperiment.EXPERIMENT_ENABLED) {
            return;
        }

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bswap")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL));

        root.then(Commands.argument("w", IntegerArgumentType.integer())
                .executes(c -> swap(c, IntegerArgumentType.getInteger(c, "w"), 0))
                .then(Commands.argument("radius",
                                IntegerArgumentType.integer(0, BSwapExperiment.MAX_RADIUS))
                        .executes(c -> swap(c, IntegerArgumentType.getInteger(c, "w"),
                                IntegerArgumentType.getInteger(c, "radius")))));

        root.then(Commands.literal("gen")
                .then(Commands.literal("parallel").executes(c -> setGen(c, true)))
                .then(Commands.literal("sequential").executes(c -> setGen(c, false))));

        root.then(Commands.literal("light")
                .then(Commands.literal("wait").executes(c -> setLight(c, true)))
                .then(Commands.literal("nowait").executes(c -> setLight(c, false))));

        root.then(Commands.literal("reset").executes(BSwapCommands::reset));

        dispatcher.register(root);
    }

    // ── 差し替え ────────────────────────────────────────────────

    private static int swap(CommandContext<CommandSourceStack> c, int targetW, int radius) {
        CommandSourceStack src = c.getSource();

        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.translatable("hyperslice.command.player_only"));
            return 0;
        }

        if (SliceTeleporter.sliceWOf(player.level()) < 0) {
            src.sendFailure(Component.translatable("hyperslice.bswap.not_in_hyperslice"));
            return 0;
        }

        int chunks = BSwapRunner.run(player, targetW, radius, timings -> report(src, targetW, radius, timings));
        if (chunks == 0) {
            src.sendFailure(Component.translatable("hyperslice.bswap.no_chunks"));
            return 0;
        }
        return chunks;
    }

    /**
     * 計測結果をチャットへ流す。
     *
     * <p>{@code light wait} のときは非同期完了後に呼ばれるため、 コマンド実行から
     * 少し遅れてこの行が出る。 それ自体が「光の待ち時間」の体感でもある。
     */
    private static void report(CommandSourceStack src, int targetW, int radius,
                               BSwapRunner.Timings t) {
        HISTORY.addLast(t);
        while (HISTORY.size() > BSwapExperiment.HISTORY_SIZE) {
            HISTORY.removeFirst();
        }

        src.sendSuccess(() -> Component.translatable("hyperslice.bswap.header",
                targetW, radius, t.chunks(), t.sections(),
                genModeLabel(), lightModeLabel()), false);
        src.sendSuccess(() -> Component.translatable("hyperslice.bswap.timings",
                ms(t.generate()), ms(t.swap()), ms(t.light()), ms(t.send()), ms(t.total())), false);

        List<BSwapRunner.Timings> h = new ArrayList<>(HISTORY);
        src.sendSuccess(() -> Component.translatable("hyperslice.bswap.median",
                h.size(),
                ms(median(h, BSwapRunner.Timings::generate)),
                ms(median(h, BSwapRunner.Timings::swap)),
                ms(median(h, BSwapRunner.Timings::light)),
                ms(median(h, BSwapRunner.Timings::send)),
                ms(median(h, BSwapRunner.Timings::total))), false);

        if (t.blockEntitiesRemoved() > 0) {
            src.sendSuccess(() -> Component.translatable("hyperslice.bswap.block_entities",
                    t.blockEntitiesRemoved()), false);
        }
    }

    // ── モード切替 ──────────────────────────────────────────────

    private static int setGen(CommandContext<CommandSourceStack> c, boolean parallel) {
        BSwapExperiment.setParallelGeneration(parallel);
        HISTORY.clear();   // モードを変えたら母数を混ぜない
        c.getSource().sendSuccess(
                () -> Component.translatable("hyperslice.bswap.set_gen", genModeLabel()), false);
        return 1;
    }

    private static int setLight(CommandContext<CommandSourceStack> c, boolean wait) {
        BSwapExperiment.setWaitForLight(wait);
        HISTORY.clear();
        c.getSource().sendSuccess(
                () -> Component.translatable("hyperslice.bswap.set_light", lightModeLabel()), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> c) {
        int n = HISTORY.size();
        HISTORY.clear();
        c.getSource().sendSuccess(
                () -> Component.translatable("hyperslice.bswap.reset", n), false);
        return n;
    }

    // ── ヘルパ ──────────────────────────────────────────────────

    private static Component genModeLabel() {
        return Component.translatable(BSwapExperiment.parallelGeneration()
                ? "hyperslice.bswap.gen.parallel" : "hyperslice.bswap.gen.sequential");
    }

    private static Component lightModeLabel() {
        return Component.translatable(BSwapExperiment.waitForLight()
                ? "hyperslice.bswap.light.wait" : "hyperslice.bswap.light.nowait");
    }

    private interface Phase {
        long of(BSwapRunner.Timings t);
    }

    private static long median(List<BSwapRunner.Timings> history, Phase phase) {
        List<Long> values = new ArrayList<>(history.size());
        for (BSwapRunner.Timings t : history) {
            values.add(phase.of(t));
        }
        Collections.sort(values);
        int n = values.size();
        if (n == 0) {
            return 0L;
        }
        // 偶数個は中央 2 つの平均 (件数が少ないうちの跳ねを抑える)。
        return (n % 2 == 1) ? values.get(n / 2)
                : (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
    }

    /** ns → ms 表示 (翻訳引数は String で渡す)。 */
    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }
}
