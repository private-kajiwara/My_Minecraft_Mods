package com.kajiwara.hyperslice.bstep;

import java.util.List;

import com.kajiwara.hyperslice.core.HyperTerrain;
import com.kajiwara.hyperslice.slice.SliceTeleporter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * <b>【方式B 中核】</b> 開発用サーバーコマンド {@code /bstep}。
 *
 * <pre>
 *   /bstep                          現在の w / 位相 / 設定を表示
 *   /bstep &lt;delta&gt;                  w を delta だけ進めて差分を適用 (単発・全チャンク)
 *   /bstep to &lt;w&gt;                   w を絶対値で指定して適用 (単発・全チャンク)
 *   /bstep auto &lt;rate&gt;              rate w/秒 で連続的に進め続ける (持続スループット測定)
 *   /bstep auto off                 停止
 *   /bstep reset                    本来の w に戻して再適用し、 計測履歴を捨てる
 *   /bstep radius &lt;n|all&gt;           対象半径 [チャンク] (既定 all = シミュレーション距離)
 *   /bstep budget &lt;ms&gt;              1 ティックの適用時間予算 [ms] (カクつきの主な摘み)
 *   /bstep diff &lt;parallel|sequential&gt;  差分計算の並列化を切り替える
 *   /bstep schedule &lt;on|off&gt;        距離帯の表の on/off (効果を実測で比べるため)
 *   /bstep verify                   y 範囲最適化が全 y 総当たりと一致するかを検査する
 * </pre>
 *
 * <p><b>普段の遊び方は Page Up / Down (キー入力) である。</b> このコマンド群は
 * 単発・計測・検証のために残してある。
 *
 * <p><b>単発 ({@code <delta>} / {@code to} / {@code reset}) は優先度も時間予算も通さない。</b>
 * 通すと遠方が更新されないまま残り、 「{@code /bstep 3.0} してから
 * {@code /hyperslice 3} と見比べる」という正しさの検証手段が壊れる。
 *
 * <p>登録は {@code HyperSliceCommands} に相乗りする (イベント登録を増やさない)。
 * {@link BStepExperiment#EXPERIMENT_ENABLED} が {@code false} なら<b>登録もされない</b>。
 */
public final class BStepCommands {

    /**
     * {@code /bstep budget <ms>} で指定できる上限 [ms]。
     *
     * <p>ティック予算 (既定 50ms) を超える値を許しても意味が無い — 予算いっぱいまで使えば
     * ティックの実周期は必ず延びる。 上限に張り付けても直らないなら、 摘みは予算ではなく
     * {@code WScheduler.BAND_QUANTA} の側である。
     */
    private static final double MAX_BUDGET_MS = 50.0;

    private BStepCommands() {
    }

    /** {@code HyperSliceCommands} から呼ぶ。 無効なら何も登録しない。 */
    public static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!BStepExperiment.EXPERIMENT_ENABLED) {
            return;
        }

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bstep")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL));

        root.executes(BStepCommands::status);

        root.then(Commands.argument("delta", DoubleArgumentType.doubleArg())
                .executes(c -> step(c, DoubleArgumentType.getDouble(c, "delta"))));

        root.then(Commands.literal("auto")
                .then(Commands.literal("off").executes(BStepCommands::autoOff))
                .then(Commands.argument("rate",
                                DoubleArgumentType.doubleArg(0.0, BStepExperiment.MAX_RATE))
                        .executes(c -> autoOn(c, DoubleArgumentType.getDouble(c, "rate")))));

        root.then(Commands.literal("to")
                .then(Commands.argument("w", DoubleArgumentType.doubleArg())
                        .executes(c -> stepTo(c, DoubleArgumentType.getDouble(c, "w")))));

        root.then(Commands.literal("reset").executes(BStepCommands::reset));

        root.then(Commands.literal("schedule")
                .then(Commands.literal("on").executes(c -> setScheduler(c, true)))
                .then(Commands.literal("off").executes(c -> setScheduler(c, false))));

        root.then(Commands.literal("radius")
                .then(Commands.literal("all").executes(c -> setRadius(c, -1)))
                .then(Commands.argument("chunks",
                                IntegerArgumentType.integer(0, BStepExperiment.MAX_RADIUS))
                        .executes(c -> setRadius(c, IntegerArgumentType.getInteger(c, "chunks")))));

        root.then(Commands.literal("budget")
                .then(Commands.argument("ms",
                                DoubleArgumentType.doubleArg(0.1, MAX_BUDGET_MS))
                        .executes(c -> setBudget(c, DoubleArgumentType.getDouble(c, "ms")))));

        root.then(Commands.literal("diff")
                .then(Commands.literal("parallel").executes(c -> setDiff(c, true)))
                .then(Commands.literal("sequential").executes(c -> setDiff(c, false))));

        root.then(Commands.literal("verify").executes(BStepCommands::verify));

        dispatcher.register(root);
    }

    // ── 実行者とセッションの取得 ────────────────────────────────

    private static ServerPlayer playerOf(CommandSourceStack src) {
        try {
            return src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.translatable("hyperslice.command.player_only"));
            return null;
        }
    }

    private static BStepSession sessionOf(CommandSourceStack src, ServerPlayer player) {
        if (SliceTeleporter.sliceWOf(player.level()) < 0) {
            src.sendFailure(Component.translatable("hyperslice.bstep.not_in_hyperslice"));
            return null;
        }
        BStepSession session = BStepSession.of(player.level());
        if (session == null) {
            src.sendFailure(Component.translatable("hyperslice.bstep.not_in_hyperslice"));
        }
        return session;
    }

    // ── 状態表示 ────────────────────────────────────────────────

    private static int status(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = playerOf(src);
        if (player == null) {
            return 0;
        }
        BStepSession session = sessionOf(src, player);
        if (session == null) {
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("hyperslice.bstep.status",
                String.format(java.util.Locale.ROOT, "%.3f", session.currentW()),
                String.format(java.util.Locale.ROOT, "%.3f", session.phase()),
                session.nominalW(), session.sliceCount(),
                radiusLabel(),
                String.format(java.util.Locale.ROOT, "%.2f", WScheduler.budgetMs()),
                modeLabel(), scheduleLabel(),
                session.isAuto()
                        ? Component.translatable("hyperslice.bstep.auto.on",
                                String.format(java.util.Locale.ROOT, "%.2f", session.rate()))
                        : Component.translatable("hyperslice.bstep.auto.off")), false);
        return 1;
    }

    // ── 単発 ────────────────────────────────────────────────────

    private static int step(CommandContext<CommandSourceStack> c, double delta) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = playerOf(src);
        if (player == null) {
            return 0;
        }
        BStepSession session = sessionOf(src, player);
        if (session == null) {
            return 0;
        }
        BStepRunner.StepResult result = session.step(player, delta);
        if (result == null) {
            src.sendFailure(Component.translatable("hyperslice.bstep.no_chunks"));
            return 0;
        }
        report(src, session, result);
        return result.chunks();
    }

    /**
     * {@code /bstep to <w>}: w を絶対値で指定する。
     *
     * <p>権威がサーバーへ移ったので、 クライアントコマンドだった
     * {@code /observerw <value>} の代わりがこれ (新しいパケット型を増やさずに済む)。
     * 特定の w で静止させて見比べたいときに使う。
     */
    private static int stepTo(CommandContext<CommandSourceStack> c, double targetW) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = playerOf(src);
        if (player == null) {
            return 0;
        }
        BStepSession session = sessionOf(src, player);
        if (session == null) {
            return 0;
        }
        session.stopAuto();
        BStepRunner.StepResult result = session.stepTo(player, targetW);
        if (result == null) {
            src.sendFailure(Component.translatable("hyperslice.bstep.no_chunks"));
            return 0;
        }
        report(src, session, result);
        return result.chunks();
    }

    private static int reset(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = playerOf(src);
        if (player == null) {
            return 0;
        }
        BStepSession session = sessionOf(src, player);
        if (session == null) {
            return 0;
        }
        session.stopAuto();
        int discarded = session.clearHistory();
        BStepRunner.StepResult result = session.stepTo(player, session.nominalW());
        if (result == null) {
            src.sendFailure(Component.translatable("hyperslice.bstep.no_chunks"));
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("hyperslice.bstep.reset",
                session.nominalW(), discarded), false);
        report(src, session, result);
        return result.chunks();
    }

    private static void report(CommandSourceStack src, BStepSession session,
                               BStepRunner.StepResult result) {
        for (Component line : session.reportLines(src.getServer(), result)) {
            src.sendSuccess(() -> line, false);
        }
    }

    // ── 連続 ────────────────────────────────────────────────────

    private static int autoOn(CommandContext<CommandSourceStack> c, double rate) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = playerOf(src);
        if (player == null) {
            return 0;
        }
        BStepSession session = sessionOf(src, player);
        if (session == null) {
            return 0;
        }
        if (rate <= 0.0) {
            session.stopAuto();
            src.sendSuccess(() -> Component.translatable("hyperslice.bstep.auto_stopped"), false);
            return 0;
        }
        session.startAuto(player, rate);
        // 量子ではなく「許される遅れ」を見せる。 w は連続に進むので「毎秒何ステップ」は
        // もう存在しない (最近傍と最遠帯が何 w まで遅れてよいか、 が実際の粗さ)。
        src.sendSuccess(() -> Component.translatable("hyperslice.bstep.auto_started",
                String.format(java.util.Locale.ROOT, "%.2f", rate),
                String.format(java.util.Locale.ROOT, "%.3f",
                        WScheduler.granularityOf(0, BStepExperiment.scheduler())),
                String.format(java.util.Locale.ROOT, "%.3f",
                        WScheduler.granularityOf(Integer.MAX_VALUE, BStepExperiment.scheduler()))),
                false);
        return 1;
    }

    private static int autoOff(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        BStepSession session = BStepSession.activeSession();
        if (session == null) {
            src.sendFailure(Component.translatable("hyperslice.bstep.auto_not_running"));
            return 0;
        }
        session.stopAuto();
        src.sendSuccess(() -> Component.translatable("hyperslice.bstep.auto_stopped"), false);
        return 1;
    }

    // ── 設定 ────────────────────────────────────────────────────

    private static int setRadius(CommandContext<CommandSourceStack> c, int radius) {
        BStepExperiment.setRadius(radius);
        c.getSource().sendSuccess(
                () -> Component.translatable("hyperslice.bstep.set_radius", radiusLabel()), false);
        return 1;
    }

    /**
     * {@code /bstep budget <ms>}: 1 ティックの適用時間予算。
     *
     * <p><b>実機での調整レバーその 1</b>。 「追い付けていない遅れ」が最遠帯の粒度
     * (既定 1.0) より大きいまま安定するなら、 ここが足りていない。
     * レバーその 2 は {@code WScheduler.BAND_QUANTA} (こちらはリビルドが要る)。
     */
    private static int setBudget(CommandContext<CommandSourceStack> c, double ms) {
        WScheduler.setBudgetMs(ms);
        c.getSource().sendSuccess(
                () -> Component.translatable("hyperslice.bstep.set_budget",
                        String.format(java.util.Locale.ROOT, "%.2f", ms),
                        String.format(java.util.Locale.ROOT, "%.2f", WScheduler.TICK_BUDGET_MS),
                        WScheduler.MAX_CHUNKS_PER_TICK), false);
        return 1;
    }

    private static int setDiff(CommandContext<CommandSourceStack> c, boolean parallel) {
        BStepExperiment.setParallelDiff(parallel);
        c.getSource().sendSuccess(
                () -> Component.translatable("hyperslice.bstep.set_diff", modeLabel()), false);
        return 1;
    }

    private static int setScheduler(CommandContext<CommandSourceStack> c, boolean enabled) {
        BStepExperiment.setScheduler(enabled);
        c.getSource().sendSuccess(
                () -> Component.translatable("hyperslice.bstep.set_schedule", scheduleLabel()), false);
        return 1;
    }

    private static Component radiusLabel() {
        int r = BStepExperiment.radius();
        return r < 0 ? Component.translatable("hyperslice.bstep.radius.all")
                : Component.literal(Integer.toString(r));
    }

    private static Component modeLabel() {
        return Component.translatable(BStepExperiment.parallelDiff()
                ? "hyperslice.bstep.diff.parallel" : "hyperslice.bstep.diff.sequential");
    }

    private static Component scheduleLabel() {
        return Component.translatable(BStepExperiment.scheduler()
                ? "hyperslice.bstep.schedule.on" : "hyperslice.bstep.schedule.off");
    }

    // ── 自己検査 ────────────────────────────────────────────────

    /**
     * y 範囲最適化の検査。
     *
     * <p>実行者のチャンク 1 個について、 「列スキップ + y 範囲限定」の差分と
     * <b>全 y 総当たり</b>の差分を突き合わせる。 一致しなければ
     * {@link BStepDiff} の範囲導出が {@code stateAt} の実装とずれている。
     *
     * <p>{@code :common} の JUnit にできないのは、 {@code stateAt} が {@code BlockState}
     * (= MC 依存) を返すため。 だから実コード経路上の自己検査にしてある。
     * <b>何も適用しない</b> (読み取りのみ)。
     */
    private static int verify(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerPlayer player = playerOf(src);
        if (player == null) {
            return 0;
        }
        BStepSession session = sessionOf(src, player);
        if (session == null) {
            return 0;
        }
        ServerLevel level = player.level();
        HyperTerrain terrain = BStepRunner.terrainOf(level);
        if (terrain == null) {
            src.sendFailure(Component.translatable("hyperslice.bstep.not_in_hyperslice"));
            return 0;
        }

        int[] skipped = new int[1];
        ChunkPos centre = ChunkPos.containing(player.blockPosition());
        List<BStepRunner.Candidate> candidates = BStepRunner.collectCandidates(
                level, centre, 0, c2 -> session.currentW(), skipped);
        if (candidates.isEmpty()) {
            src.sendFailure(Component.translatable("hyperslice.bstep.no_chunks"));
            return 0;
        }
        LevelChunk chunk = candidates.get(0).chunk();

        // 位相を変えながら複数の delta で試す (1 点だけ合っても保証にならない)。
        double from = session.currentW();
        double[] deltas = { 0.125, 0.25, 0.5, 1.0, -0.125, -0.75, 3.0 };
        int checked = 0;
        int mismatched = 0;
        long optimisedTotal = 0;
        long referenceTotal = 0;

        for (double delta : deltas) {
            double to = from + delta;
            BStepDiff.ChunkDiff fast = BStepDiff.compute(chunk, terrain, from, to);
            BStepDiff.ChunkDiff slow = BStepDiff.computeReference(chunk, terrain, from, to);
            optimisedTotal += fast.size();
            referenceTotal += slow.size();
            checked++;
            if (!sameChanges(fast, slow)) {
                mismatched++;
                final double d = delta;
                src.sendFailure(Component.translatable("hyperslice.bstep.verify_mismatch",
                        String.format(java.util.Locale.ROOT, "%.3f", d),
                        fast.size(), slow.size()));
            }
        }

        final int checkedCount = checked;
        final int mismatchCount = mismatched;
        final long opt = optimisedTotal;
        final long ref = referenceTotal;
        if (mismatchCount == 0) {
            src.sendSuccess(() -> Component.translatable("hyperslice.bstep.verify_ok",
                    checkedCount, opt, ref), false);
        }
        return mismatchCount == 0 ? 1 : 0;
    }

    /** 2 つの差分が「同じ位置に同じブロックを置く」かどうか (順序は問わない)。 */
    private static boolean sameChanges(BStepDiff.ChunkDiff a, BStepDiff.ChunkDiff b) {
        if (a.size() != b.size()) {
            return false;
        }
        java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>
                map = new java.util.HashMap<>(a.size() * 2);
        for (int i = 0; i < a.size(); i++) {
            map.put(a.position(i), a.state(i));
        }
        for (int i = 0; i < b.size(); i++) {
            if (map.remove(b.position(i)) != b.state(i)) {
                return false;
            }
        }
        return map.isEmpty();
    }
}
