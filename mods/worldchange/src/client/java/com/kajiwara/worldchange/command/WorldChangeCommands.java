package com.kajiwara.worldchange.command;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.kajiwara.worldchange.core.WorldEntry;
import com.kajiwara.worldchange.core.WorldMatch;
import com.kajiwara.worldchange.core.WorldMatcher;
import com.kajiwara.worldchange.core.WorldQuery;
import com.kajiwara.worldchange.world.WorldCatalog;
import com.kajiwara.worldchange.world.WorldSwitcher;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;*/
//?}
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * クライアント専用 {@code /worldChange <名前:シード値>} コマンド (Brigadier・サーバー非依存)。
 *
 * <p>マルチ接続中でも client command として動き、 ローカル SP の既存ワールドへ切り替わる (決定 C)。
 * 引数は単一の greedy 文字列で受け、 {@link WorldQuery} が {@code 名前:シード} を解析する。
 * 照合 ({@link WorldMatcher}) は名前主・シードは曖昧回避/緩い検証 (決定 A)。 不在/曖昧/ロック/非互換は
 * 翻訳キー経由でフィードバックし、 クラッシュさせない。
 *
 * <p>登録ビルダのみ版差 (26.1+={@code ClientCommands} / legacy={@code ClientCommandManager})。
 */
public final class WorldChangeCommands {

    private WorldChangeCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> build(dispatcher));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("worldChange");
        root.executes(WorldChangeCommands::usage);
        root.then(argument("query", StringArgumentType.greedyString())
                .suggests(WorldChangeCommands::suggestWorlds)
                .executes(c -> run(c, StringArgumentType.getString(c, "query"))));
        dispatcher.register(root);
    }

    private static int usage(CommandContext<FabricClientCommandSource> c) {
        c.getSource().sendFeedback(Component.translatable("worldchange.usage"));
        return 0;
    }

    private static int run(CommandContext<FabricClientCommandSource> c, String raw) {
        FabricClientCommandSource src = c.getSource();
        WorldQuery query = WorldQuery.parse(raw);
        if (query.isBlank()) {
            return usage(c);
        }

        List<WorldEntry> entries = WorldCatalog.listEntries();
        List<WorldEntry> candidates = WorldMatcher.nameCandidates(entries, query.name());

        // シード指定があるときだけ、 候補のシードを遅延読込する (緩い検証 / 曖昧回避に必要な分だけ)。
        if (query.seed().isPresent() && !candidates.isEmpty()) {
            candidates = candidates.stream()
                    .map(e -> e.withSeed(WorldCatalog.readSeed(e.folderId())))
                    .toList();
        }
        WorldMatch match = WorldMatcher.resolve(query, candidates);

        switch (match.status()) {
            case NOT_FOUND -> {
                src.sendError(Component.translatable("worldchange.not_found", query.name()));
                return 0;
            }
            case AMBIGUOUS_NEED_SEED -> {
                src.sendError(Component.translatable("worldchange.ambiguous.need_seed",
                        query.name(), match.candidates().size()));
                sendCandidates(src, match.candidates());
                return 0;
            }
            case AMBIGUOUS_SEED_UNRESOLVED -> {
                src.sendError(Component.translatable("worldchange.ambiguous.seed_unresolved", query.name()));
                sendCandidates(src, match.candidates());
                return 0;
            }
            case SEED_MISMATCH -> {
                // 緩い検証: 警告しつつ採用して切り替える。
                src.sendFeedback(Component.translatable("worldchange.seed_mismatch",
                        match.selected().displayName()));
                return startSwitch(src, match.selected());
            }
            case MATCHED -> {
                return startSwitch(src, match.selected());
            }
            default -> {
                return 0;
            }
        }
    }

    private static int startSwitch(FabricClientCommandSource src, WorldEntry target) {
        WorldSwitcher.Outcome outcome = WorldSwitcher.begin(target);
        switch (outcome) {
            case STARTED -> {
                src.sendFeedback(Component.translatable("worldchange.switching", target.displayName()));
                return 1;
            }
            case LOCKED -> {
                src.sendError(Component.translatable("worldchange.locked", target.displayName()));
                return 0;
            }
            case INCOMPATIBLE -> {
                src.sendError(Component.translatable("worldchange.incompatible", target.displayName()));
                return 0;
            }
            case ALREADY_THERE -> {
                src.sendFeedback(Component.translatable("worldchange.already_there", target.displayName()));
                return 0;
            }
            default -> {
                return 0;
            }
        }
    }

    private static void sendCandidates(FabricClientCommandSource src, List<WorldEntry> candidates) {
        for (WorldEntry e : candidates) {
            src.sendFeedback(Component.translatable("worldchange.candidate_line",
                    e.folderId(), e.displayName()));
        }
    }

    /** フォルダ名/表示名を前方一致で提案 (空白入りも greedy 引数なのでそのまま補完可)。 */
    private static CompletableFuture<Suggestions> suggestWorlds(
            CommandContext<FabricClientCommandSource> c, SuggestionsBuilder b) {
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);
        for (WorldEntry e : WorldCatalog.listEntries()) {
            if (e.folderId().toLowerCase(Locale.ROOT).startsWith(rem)) {
                b.suggest(e.folderId());
            } else if (e.displayName().toLowerCase(Locale.ROOT).startsWith(rem)) {
                b.suggest(e.displayName());
            }
        }
        return b.buildFuture();
    }

    // ── 登録ビルダ入口 (唯一の版差: 26.1+=ClientCommands / legacy=ClientCommandManager) ──

    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        //? if >=26.1 {
        return ClientCommands.literal(name);
        //?} else {
        /*return ClientCommandManager.literal(name);*/
        //?}
    }

    private static <T> com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, T> argument(
            String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
        //? if >=26.1 {
        return ClientCommands.argument(name, type);
        //?} else {
        /*return ClientCommandManager.argument(name, type);*/
        //?}
    }
}
