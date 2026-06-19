package com.kajiwara.visualizegate.config;

import java.util.ArrayList;
import java.util.List;

import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.ui.GateColors;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * ModMenu 設定画面 (独自 Screen・OmniChest のサイドバー型レイアウトを最小再現・Mixin 不使用)。
 *
 * <p>左タブ (Display / About) ／ 右詳細。 トグルは in-game メニュー (V) と同じ {@link GateMenuState} を
 * 読み書きするので双方向に反映される。 変更は即 {@link GateConfigManager#save()}。 配色は {@link GateColors}。
 *
 * <p><b>背景の扱い</b> (OmniChest 現物の知見): MC 1.21.5+ は {@code Screen#render} から
 * {@code renderBackground} 呼び出しが消え、 GameRenderer が外側で 1 回描く。 ここで {@code renderBackground}
 * を呼ぶと blur 二重起動でクラッシュするため<b>呼ばない</b>。 パネルは super(=widget 描画) の前に塗る。
 */
public class GateConfigScreen extends Screen {

    private enum Tab {
        DISPLAY, ABOUT
    }

    private static final int SIDEBAR_W = 110;
    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 34;

    private final Screen parent;
    private Tab activeTab = Tab.DISPLAY;
    private final List<AbstractWidget> displayWidgets = new ArrayList<>();

    public GateConfigScreen(Screen parent) {
        super(Component.literal("VisualizeGate Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        displayWidgets.clear();

        // ─── 左サイドバー: タブボタン ───
        int tabY = HEADER_H + 6;
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.config.tab.display"),
                b -> selectTab(Tab.DISPLAY)).bounds(8, tabY, SIDEBAR_W - 14, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.config.tab.about"),
                b -> selectTab(Tab.ABOUT)).bounds(8, tabY + 24, SIDEBAR_W - 14, 20).build());

        // ─── 右詳細: Display タブのトグル群 ───
        int detailX = SIDEBAR_W + 16;
        int btnW = Math.min(this.width - detailX - 16, 260);
        int dy = HEADER_H + 10;
        Button boxBtn = Button.builder(boxLabel(), b -> {
            GateMenuState.toggleBoxOverlay();
            b.setMessage(boxLabel());
            GateConfigManager.save();
        }).bounds(detailX, dy, btnW, 20).build();
        Button hudBtn = Button.builder(hudLabel(), b -> {
            GateMenuState.toggleHudIcon();
            b.setMessage(hudLabel());
            GateConfigManager.save();
        }).bounds(detailX, dy + 24, btnW, 20).build();
        // 機能1 ホログラム枠 (ズレ無し設置位置の金枠) on/off。
        Button holoBtn = Button.builder(hologramLabel(), b -> {
            GateMenuState.toggleHologram();
            b.setMessage(hologramLabel());
            GateConfigManager.save();
        }).bounds(detailX, dy + 48, btnW, 20).build();
        // 機能3 探索ドーム (リンク検索範囲＋混線検出) on/off。
        Button domeBtn = Button.builder(domeLabel(), b -> {
            GateMenuState.toggleDome();
            b.setMessage(domeLabel());
            GateConfigManager.save();
        }).bounds(detailX, dy + 72, btnW, 20).build();
        // かんたん⇄詳細 (in-game メニューと同じ GateMenuState を共有＝双方向反映)。
        Button modeBtn = Button.builder(modeLabel(), b -> {
            GateMenuState.toggleAdvancedMode();
            b.setMessage(modeLabel());
            GateConfigManager.save();
        }).bounds(detailX, dy + 96, btnW, 20).build();
        // 常設凡例 on/off (上級者向け)。
        Button legendBtn = Button.builder(legendLabel(), b -> {
            GateMenuState.toggleLegend();
            b.setMessage(legendLabel());
            GateConfigManager.save();
        }).bounds(detailX, dy + 120, btnW, 20).build();
        addRenderableWidget(boxBtn);
        addRenderableWidget(hudBtn);
        addRenderableWidget(holoBtn);
        addRenderableWidget(domeBtn);
        addRenderableWidget(modeBtn);
        addRenderableWidget(legendBtn);
        displayWidgets.add(boxBtn);
        displayWidgets.add(hudBtn);
        displayWidgets.add(holoBtn);
        displayWidgets.add(domeBtn);
        displayWidgets.add(modeBtn);
        displayWidgets.add(legendBtn);

        // ─── フッタ: Done ───
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.menu.done"), b -> this.onClose())
                .bounds(this.width / 2 - 60, this.height - FOOTER_H + 7, 120, 20).build());

        applyTabVisibility();
    }

    private void selectTab(Tab t) {
        this.activeTab = t;
        applyTabVisibility();
    }

    private void applyTabVisibility() {
        boolean disp = activeTab == Tab.DISPLAY;
        for (AbstractWidget w : displayWidgets) {
            w.visible = disp;
            w.active = disp;
        }
    }

    private static Component boxLabel() {
        return Component.translatable("visualizegate.menu.box", onOff(GateMenuState.isBoxOverlayEnabled()));
    }

    private static Component hudLabel() {
        return Component.translatable("visualizegate.menu.hud", onOff(GateMenuState.isHudIconEnabled()));
    }

    private static Component modeLabel() {
        return Component.translatable("visualizegate.menu.mode", Component.translatable(
                GateMenuState.isAdvancedMode() ? "visualizegate.mode.advanced" : "visualizegate.mode.simple"));
    }

    private static Component hologramLabel() {
        return Component.translatable("visualizegate.menu.hologram", onOff(GateMenuState.isHologramEnabled()));
    }

    private static Component domeLabel() {
        return Component.translatable("visualizegate.menu.dome", onOff(GateMenuState.isDomeEnabled()));
    }

    private static Component legendLabel() {
        return Component.translatable("visualizegate.menu.legend", onOff(GateMenuState.isLegendEnabled()));
    }

    private static Component onOff(boolean b) {
        return Component.translatable(b ? "visualizegate.state.on" : "visualizegate.state.off");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // backdrop は GameRenderer が外側で描画済 → renderBackground は呼ばない。
        // パネルを widget の前に塗る (Mod カラー)。
        g.fill(0, 0, this.width, this.height, GateColors.BASE);
        g.fill(0, HEADER_H, SIDEBAR_W, this.height - FOOTER_H, GateColors.PANEL);
        g.fill(0, HEADER_H, this.width, HEADER_H + 1, GateColors.MAIN);
        g.fill(SIDEBAR_W, HEADER_H, SIDEBAR_W + 1, this.height - FOOTER_H, GateColors.MAIN_DIM);
        g.fill(0, this.height - FOOTER_H, this.width, this.height - FOOTER_H + 1, GateColors.MAIN);

        super.extractRenderState(g, mouseX, mouseY, partialTick); // widgets

        // 前景テキスト (Mod カラー)。
        g.text(this.font, this.title, 10, 11, GateColors.ACCENT);
        if (activeTab == Tab.ABOUT) {
            int x = SIDEBAR_W + 16;
            int y = HEADER_H + 12;
            g.text(this.font, Component.literal("VisualizeGate"), x, y, GateColors.TEXT);
            g.text(this.font, Component.translatable("visualizegate.config.about.tagline"), x, y + 14, GateColors.MAIN);
        }
    }

    @Override
    public void onClose() {
        GateConfigManager.save();
        //? if >=26.2 {
        /*this.minecraft.setScreenAndShow(this.parent);*/
        //?} else {
        this.minecraft.setScreen(this.parent);
        //?}
    }
}
