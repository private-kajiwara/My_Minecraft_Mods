package com.kajiwara.omnichest.client.command;

import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.i18n.OmniChestLocale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;*/
//?}
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * クライアント専用コマンド {@code /omnichest hud <on|off|toggle>} (Brigadier・サーバー非依存)。
 *
 * <p>
 * 「選択アイテム情報 HUD」 ({@link com.kajiwara.omnichest.client.render.SelectedItemHudRenderer}) の
 * 表示を即座に出し入れする手段。 VG の {@code /vg clean} に相当する「即消し」 = {@code /omnichest hud off}。
 * OmniChest には既存コマンドが無いため、 この名前空間 ({@code /omnichest}) を最小新設する
 * (= WorldChange と同じ {@link ClientCommandRegistrationCallback} 経路・Mixin レス)。
 *
 * <p>
 * 唯一の版差は登録ビルダ (26.1+={@code ClientCommands} / legacy={@code ClientCommandManager})。
 */
public final class OmniChestCommands {

    private OmniChestCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> build(dispatcher));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("omnichest");
        LiteralArgumentBuilder<FabricClientCommandSource> hud = literal("hud");
        hud.executes(OmniChestCommands::usage);
        hud.then(literal("on").executes(c -> set(c, true)));
        hud.then(literal("off").executes(c -> set(c, false)));
        hud.then(literal("toggle").executes(OmniChestCommands::toggle));
        root.then(hud);
        dispatcher.register(root);
    }

    private static int usage(CommandContext<FabricClientCommandSource> c) {
        c.getSource().sendFeedback(OmniChestLocale.get(
                "omnichest.command.hud.usage", "/omnichest hud <on|off|toggle>"));
        return 0;
    }

    private static int toggle(CommandContext<FabricClientCommandSource> c) {
        boolean next;
        try {
            next = !ConfigManager.get().render.showSelectedItemHud;
        } catch (Throwable t) {
            next = true;
        }
        return set(c, next);
    }

    private static int set(CommandContext<FabricClientCommandSource> c, boolean value) {
        try {
            ConfigManager.get().render.showSelectedItemHud = value;
            ConfigManager.save();
        } catch (Throwable ignored) {
            // 設定保存に失敗しても実行時の表示切替は反映されている (= クラッシュさせない)。
        }
        c.getSource().sendFeedback(value
                ? OmniChestLocale.get("omnichest.command.hud.on", "Selected item HUD: ON")
                : OmniChestLocale.get("omnichest.command.hud.off", "Selected item HUD: OFF"));
        return 1;
    }

    // ── 登録ビルダ入口 (唯一の版差: 26.1+=ClientCommands / legacy=ClientCommandManager) ──

    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        //? if >=26.1 {
        return ClientCommands.literal(name);
        //?} else {
        /*return ClientCommandManager.literal(name);*/
        //?}
    }
}
