package com.kajiwara.worldchange.ui;

import java.util.List;

import com.kajiwara.worldchange.core.WorldEntry;
import com.kajiwara.worldchange.world.WorldCatalog;
import com.kajiwara.worldchange.world.WorldSwitcher;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * ModMenu の設定ボタンから開く「ワールドピッカー」。 vanilla ウィジェット (Button) のみで構成し、
 * 独自テクスチャ/装飾は使わない (要件: vanilla UI 基準)。
 *
 * <p>各行 = 1 ワールドのボタン (表示名 + フォルダ名)。 押すとそのワールドへ切り替える ({@link WorldSwitcher#begin})。
 * 切替できないワールド (非互換 / ロック中 / 現在いるワールド) はボタンを無効化 (グレー) して誤操作を防ぐ。
 * ワールドが多い場合は前/次ページで送る。 描画メソッド名のみ版差 (GateRenameScreen と同名規則・stonecutter 一方向)。
 */
public class WorldPickerScreen extends Screen {

    private static final int ROW_W = 300;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 2;
    private static final int TOP = 40;        // 一覧開始 y
    private static final int BOTTOM_RESERVE = 64; // 下部 (ページ送り + Done) の確保高

    private final Screen parent;
    private final List<WorldEntry> worlds;
    private int page;

    public WorldPickerScreen(Screen parent) {
        super(Component.translatable("worldchange.screen.title"));
        this.parent = parent;
        this.worlds = WorldCatalog.listEntries();
    }

    private int rowsPerPage() {
        int avail = this.height - TOP - BOTTOM_RESERVE;
        return Math.max(1, avail / (ROW_H + ROW_GAP));
    }

    private int pageCount() {
        if (worlds.isEmpty()) {
            return 1;
        }
        int per = rowsPerPage();
        return (worlds.size() + per - 1) / per;
    }

    @Override
    protected void init() {
        int per = rowsPerPage();
        page = Math.max(0, Math.min(page, pageCount() - 1));
        int centerX = this.width / 2;
        int x = centerX - ROW_W / 2;
        int y = TOP;

        int start = page * per;
        int end = Math.min(worlds.size(), start + per);
        java.util.Optional<String> current = WorldSwitcher.currentWorldId();
        for (int i = start; i < end; i++) {
            WorldEntry e = worlds.get(i);
            boolean isCurrent = current.isPresent() && current.get().equalsIgnoreCase(e.folderId());
            boolean switchable = e.compatible() && !e.locked() && !isCurrent;
            Button row = Button.builder(rowLabel(e, isCurrent), b -> WorldSwitcher.begin(e))
                    .bounds(x, y, ROW_W, ROW_H).build();
            row.active = switchable;
            addRenderableWidget(row);
            y += ROW_H + ROW_GAP;
        }

        // ページ送り (2 ページ以上のときのみ)。
        int navY = this.height - 52;
        if (pageCount() > 1) {
            addRenderableWidget(Button.builder(Component.translatable("worldchange.screen.prev"), b -> {
                if (page > 0) {
                    page--;
                    rebuild();
                }
            }).bounds(centerX - ROW_W / 2, navY, 98, ROW_H).build());
            addRenderableWidget(Button.builder(Component.literal((page + 1) + " / " + pageCount()), b -> {
            }).bounds(centerX - 49, navY, 98, ROW_H).build());
            addRenderableWidget(Button.builder(Component.translatable("worldchange.screen.next"), b -> {
                if (page < pageCount() - 1) {
                    page++;
                    rebuild();
                }
            }).bounds(centerX + ROW_W / 2 - 98, navY, 98, ROW_H).build());
        }

        // Done (親へ戻る)。
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(centerX - 100, this.height - 28, 200, ROW_H).build());
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private Component rowLabel(WorldEntry e, boolean isCurrent) {
        String label = e.displayName();
        if (!e.folderId().equals(e.displayName())) {
            label = label + "  (" + e.folderId() + ")";
        }
        if (isCurrent) {
            return Component.translatable("worldchange.screen.row_current", label);
        }
        if (e.locked()) {
            return Component.translatable("worldchange.screen.row_locked", label);
        }
        if (!e.compatible()) {
            return Component.translatable("worldchange.screen.row_incompatible", label);
        }
        return Component.literal(label);
    }

    @Override
    public void onClose() {
        //? if >=26.2 {
        /*this.minecraft.setScreenAndShow(parent);*/
        //?} else {
        this.minecraft.setScreen(parent);
        //?}
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick); // 背景 + ウィジェット
        // タイトル (中央上)。
        int tw = this.font.width(this.title);
        g.text(this.font, this.title, (this.width - tw) / 2, 16, 0xFFFFFFFF);
        if (worlds.isEmpty()) {
            Component empty = Component.translatable("worldchange.screen.empty");
            int ew = this.font.width(empty);
            g.text(this.font, empty, (this.width - ew) / 2, TOP + 10, 0xFFA0A0A0);
        }
    }
}
