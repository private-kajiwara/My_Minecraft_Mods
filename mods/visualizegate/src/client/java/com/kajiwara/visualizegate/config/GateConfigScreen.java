package com.kajiwara.visualizegate.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.kajiwara.visualizegate.client.keybind.GateKeyBindings;
import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.state.PointCloudViewState;
import com.kajiwara.visualizegate.ui.GateColors;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * ModMenu 設定画面 (独自 Screen・OmniChest 風フラット・<b>Mixin 不使用</b>・全コントロール自前 immediate 描画)。
 *
 * <p><b>レイアウト</b>: 上中央タイトルバー (1px 金枠) / 左サイドバー (枠なし・セクション見出し＋2 段タブ・選択帯) /
 * 右詳細 (枠なし・スクロール・説明→ラベル→コントロール→補足) / 下 3 ボタン (Reset/Save/Cancel)。 金は<b>点
 * アクセント</b>のみ (タイトル文字・ボタン枠/文字・選択タブラベル・数値)。 暗スクリムでゲーム内 HUD を沈める。
 *
 * <p><b>ステージング</b>: 編集は {@link #draft} 上で行い、 <b>Save</b> で {@link GateConfigManager#apply} (live
 * state へ反映＋GSON 保存)、 <b>Cancel</b>/Esc で破棄、 <b>Reset</b> で draft を既定へ (まだ未適用＝Cancel で戻せる)。
 * in-game 側 (レンダラ/ドック/keybind/ /vg) と同じ state を共有するので Save で即反映される。
 *
 * <p><b>背景</b> (OmniChest 知見): MC 1.21.5+ は GameRenderer が外側で backdrop を描く。 ここで
 * {@code renderBackground} を呼ぶと blur 二重起動でクラッシュするため<b>呼ばない</b>。 自前スクリムを塗る。
 */
public class GateConfigScreen extends Screen {

    // ── タブ (セクション 2 系統)。 ──
    private enum Tab {
        CPU, GPU, GATE_VISUALS, POINT_CLOUD, DOCK_HUD, KEYBINDS
    }

    // 寸法。
    private static final int MARGIN = 16;
    private static final int SIDEBAR_W = 150;
    private static final int HEADER_H = 38;
    private static final int FOOTER_H = 36;
    private static final int DETAIL_PAD = 18;
    private static final int LINE = 11;     // テキスト行高
    private static final int CTRL_H = 14;    // コントロール高
    private static final int LABEL_LINE = 16; // ラベル＋コントロールの行高
    private static final int ROW_GAP = 9;
    private static final int OPT_H = 14;     // ドロップダウン展開の 1 行高

    private final Screen parent;
    private GateConfig draft;
    private Tab activeTab = Tab.GPU; // 添付モックの初期選択 (GPU / Render)
    private int scroll = 0;
    private int contentHeight = 0;

    private final List<SideEntry> sidebar = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private DropdownRow openDropdown; // 展開中ドロップダウン (null=なし)

    // フッタボタン矩形 (init で算出)。
    private int resetX;
    private int saveX;
    private int cancelX;
    private int btnW;
    private int btnY;

    public GateConfigScreen(Screen parent) {
        super(Component.translatable("visualizegate.config.title"));
        this.parent = parent;
        this.draft = GateConfigManager.snapshot();
    }

    private int detailX() {
        return SIDEBAR_W + DETAIL_PAD;
    }

    private int detailRight() {
        return this.width - MARGIN;
    }

    private int detailTop() {
        return HEADER_H + 6;
    }

    private int footerTop() {
        return this.height - FOOTER_H;
    }

    @Override
    protected void init() {
        buildSidebar();
        buildRows();
        // フッタ 3 ボタン (等幅・中央寄せ)。
        btnW = Math.min(150, (this.width - MARGIN * 2 - 16) / 3);
        btnY = this.height - FOOTER_H + (FOOTER_H - 20) / 2;
        int totalW = btnW * 3 + 16;
        int startX = (this.width - totalW) / 2;
        resetX = startX;
        saveX = startX + btnW + 8;
        cancelX = startX + (btnW + 8) * 2;
    }

    // ════════════════════════════════════════════════════════════════════
    // サイドバー
    // ════════════════════════════════════════════════════════════════════

    private void buildSidebar() {
        sidebar.clear();
        int y = detailTop();
        y = addSection(y, "visualizegate.config.section.performance");
        y = addTab(y, Tab.CPU, "visualizegate.config.tab2.cpu", "visualizegate.config.tab2.cpu.sub");
        y = addTab(y, Tab.GPU, "visualizegate.config.tab2.gpu", "visualizegate.config.tab2.gpu.sub");
        y += 8;
        y = addSection(y, "visualizegate.config.section.display");
        y = addTab(y, Tab.GATE_VISUALS, "visualizegate.config.tab2.gatevis", "visualizegate.config.tab2.gatevis.sub");
        y = addTab(y, Tab.POINT_CLOUD, "visualizegate.config.tab2.pointcloud", "visualizegate.config.tab2.pointcloud.sub");
        y = addTab(y, Tab.DOCK_HUD, "visualizegate.config.tab2.dockhud", "visualizegate.config.tab2.dockhud.sub");
        y = addTab(y, Tab.KEYBINDS, "visualizegate.config.tab2.keybinds", "visualizegate.config.tab2.keybinds.sub");
    }

    private int addSection(int y, String key) {
        SideEntry e = new SideEntry();
        e.section = key;
        e.y = y;
        e.h = 16;
        sidebar.add(e);
        return y + e.h;
    }

    private int addTab(int y, Tab tab, String enKey, String jaKey) {
        SideEntry e = new SideEntry();
        e.tab = tab;
        e.enKey = enKey;
        e.jaKey = jaKey;
        e.y = y;
        e.h = 22;
        sidebar.add(e);
        return y + e.h;
    }

    private void selectTab(Tab t) {
        this.activeTab = t;
        this.scroll = 0;
        this.openDropdown = null;
        buildRows();
    }

    // ════════════════════════════════════════════════════════════════════
    // 詳細 (タブごとのコントロール列)
    // ════════════════════════════════════════════════════════════════════

    private void buildRows() {
        rows.clear();
        switch (activeTab) {
            case CPU -> {
                rows.add(new ToggleRow("visualizegate.config.cpu.sampling.desc",
                        "visualizegate.config.cpu.sampling.label", "visualizegate.config.cpu.sampling.sub",
                        () -> draft.cpuSamplingEnabled, v -> draft.cpuSamplingEnabled = v));
                rows.add(new DropdownRow("visualizegate.config.cpu.rate.desc",
                        "visualizegate.config.cpu.rate.label", "visualizegate.config.cpu.rate.sub", null,
                        new Component[] {
                                Component.translatable("visualizegate.config.cpu.rate.05"),
                                Component.translatable("visualizegate.config.cpu.rate.1"),
                                Component.translatable("visualizegate.config.cpu.rate.2") },
                        () -> hzIndex(draft.cpuSamplingHz), null,
                        i -> draft.cpuSamplingHz = HZ_VALUES[i]));
                rows.add(new ToggleRow("visualizegate.config.cpu.graph.desc",
                        "visualizegate.config.cpu.graph.label", "visualizegate.config.cpu.graph.sub",
                        () -> draft.cpuGraphEnabled, v -> draft.cpuGraphEnabled = v));
            }
            case GPU -> {
                rows.add(new SliderRow("visualizegate.config.gpu.dist.desc",
                        "visualizegate.config.gpu.dist.label", "visualizegate.config.gpu.dist.sub",
                        "visualizegate.config.gpu.dist.note",
                        GateMenuState.GATE_RENDER_DIST_MIN, GateMenuState.GATE_RENDER_DIST_MAX, true,
                        () -> draft.gateRenderDistanceM, v -> draft.gateRenderDistanceM = (float) v,
                        v -> String.format("%d m", Math.round(v))));
                rows.add(new DropdownRow("visualizegate.config.gpu.quality.desc",
                        "visualizegate.config.gpu.quality.label", "visualizegate.config.gpu.quality.sub",
                        gpu3dNoteKey(),
                        new Component[] {
                                Component.translatable("visualizegate.config.gpu.quality.low"),
                                Component.translatable("visualizegate.config.gpu.quality.medium"),
                                Component.translatable("visualizegate.config.gpu.quality.high") },
                        () -> qualityIndex(draft), this::qualityCurrentLabel,
                        this::applyQualityPreset));
            }
            case GATE_VISUALS -> {
                rows.add(new ToggleRow("visualizegate.config.gv.frame.desc",
                        "visualizegate.config.gv.frame.label", "visualizegate.config.gv.frame.sub",
                        () -> draft.boxOverlayEnabled, v -> draft.boxOverlayEnabled = v));
                rows.add(new ToggleRow("visualizegate.config.gv.dome.desc",
                        "visualizegate.config.gv.dome.label", "visualizegate.config.gv.dome.sub",
                        () -> draft.domeEnabled, v -> draft.domeEnabled = v));
                rows.add(new ToggleRow("visualizegate.config.gv.holo.desc",
                        "visualizegate.config.gv.holo.label", "visualizegate.config.gv.holo.sub",
                        () -> draft.hologramEnabled, v -> draft.hologramEnabled = v));
                rows.add(new ToggleRow("visualizegate.config.gv.names.desc",
                        "visualizegate.config.gv.names.label", "visualizegate.config.gv.names.sub",
                        () -> draft.gateNamesEnabled, v -> draft.gateNamesEnabled = v));
                rows.add(new DropdownRow("visualizegate.config.gv.mode.desc",
                        "visualizegate.config.gv.mode.label", "visualizegate.config.gv.mode.sub", null,
                        new Component[] {
                                Component.translatable("visualizegate.mode.simple"),
                                Component.translatable("visualizegate.mode.advanced") },
                        () -> draft.advancedMode ? 1 : 0, null,
                        i -> draft.advancedMode = (i == 1)));
            }
            case POINT_CLOUD -> {
                rows.add(new HeaderRow("visualizegate.config.pc.group.visibility"));
                rows.add(new ToggleRow(null,
                        "visualizegate.config.pc.panel.label", "visualizegate.config.pc.panel.sub",
                        () -> draft.pcPanelVisible, v -> draft.pcPanelVisible = v));
                rows.add(new ToggleRow(null,
                        "visualizegate.config.pc.ow.label", "visualizegate.config.pc.ow.sub",
                        () -> draft.pcShowOverworld, v -> draft.pcShowOverworld = v));
                rows.add(new ToggleRow(null,
                        "visualizegate.config.pc.nether.label", "visualizegate.config.pc.nether.sub",
                        () -> draft.pcShowNether, v -> draft.pcShowNether = v));
                rows.add(new ToggleRow(null,
                        "visualizegate.config.pc.links.label", "visualizegate.config.pc.links.sub",
                        () -> draft.pcShowLinks, v -> draft.pcShowLinks = v));
                rows.add(new ToggleRow(null,
                        "visualizegate.config.pc.solo.label", "visualizegate.config.pc.solo.sub",
                        () -> draft.pcCloudOnly, v -> draft.pcCloudOnly = v));
                rows.add(new HeaderRow("visualizegate.config.pc.group.appearance"));
                rows.add(new SliderRow(null,
                        "visualizegate.config.pc.size.label", "visualizegate.config.pc.size.sub", null,
                        PointCloudViewState.POINT_SIZE_MIN, PointCloudViewState.POINT_SIZE_MAX, true,
                        () -> draft.pcPointSize, v -> draft.pcPointSize = (int) Math.round(v),
                        v -> String.format("%d px", Math.round(v))));
                rows.add(new ToggleRow(null,
                        "visualizegate.config.pc.tint.label", "visualizegate.config.pc.tint.sub",
                        () -> draft.pcDimTint, v -> draft.pcDimTint = v));
                rows.add(new DropdownRow(null,
                        "visualizegate.config.pc.detail.label", "visualizegate.config.pc.detail.sub", null,
                        new Component[] {
                                Component.translatable("visualizegate.config.pc.detail.full"),
                                Component.translatable("visualizegate.config.pc.detail.brief") },
                        () -> effectiveDetail(draft) ? 0 : 1, null,
                        i -> draft.pcOverlayDetail = (i == 0)));
                rows.add(new HeaderRow("visualizegate.config.pc.group.layout"));
                rows.add(new SliderRow("visualizegate.config.pc.spacing.desc",
                        "visualizegate.config.pc.spacing.label", "visualizegate.config.pc.spacing.sub", null,
                        PointCloudViewState.SPACING_MIN, PointCloudViewState.SPACING_MAX, true,
                        () -> draft.pcDimensionSpacing, v -> draft.pcDimensionSpacing = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
            }
            case DOCK_HUD -> {
                rows.add(new ToggleRow("visualizegate.config.dh.icon.desc",
                        "visualizegate.config.dh.icon.label", "visualizegate.config.dh.icon.sub",
                        () -> draft.hudIconEnabled, v -> draft.hudIconEnabled = v));
                rows.add(new ToggleRow("visualizegate.config.dh.legend.desc",
                        "visualizegate.config.dh.legend.label", "visualizegate.config.dh.legend.sub",
                        () -> draft.legendEnabled, v -> draft.legendEnabled = v));
                rows.add(new InfoRow("visualizegate.config.dh.dockkey.label", "visualizegate.config.dh.dockkey.sub",
                        GateKeyBindings::dockKeyDisplay, "visualizegate.config.keys.note"));
            }
            case KEYBINDS -> {
                rows.add(new InfoRow("visualizegate.config.kb.openmenu.label", "visualizegate.config.kb.openmenu.sub",
                        GateKeyBindings::boundKeyDisplay, null));
                rows.add(new InfoRow("visualizegate.config.kb.dock.label", "visualizegate.config.kb.dock.sub",
                        GateKeyBindings::dockKeyDisplay, "visualizegate.config.keys.note"));
            }
        }
    }

    // ── CPU サンプリング頻度 (0.5 / 1 / 2 Hz) ──
    private static final float[] HZ_VALUES = { 0.5f, 1.0f, 2.0f };

    private static int hzIndex(float hz) {
        int best = 1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < HZ_VALUES.length; i++) {
            float d = Math.abs(HZ_VALUES[i] - hz);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ── 点群品質プリセット (Low/Medium/High → 既存ノブ pcGpuDetail + pcPointSize へマップ) ──
    private static final int[] Q_DETAIL = { 8_000, 20_000, 80_000 };
    private static final int[] Q_SIZE = { 1, 2, 3 };

    /** 現在のノブ値がプリセットと一致するなら index、 しなければ -1 (=Custom)。 */
    private static int qualityIndex(GateConfig c) {
        for (int i = 0; i < Q_DETAIL.length; i++) {
            if (c.pcGpuDetail == Q_DETAIL[i] && c.pcPointSize == Q_SIZE[i]) {
                return i;
            }
        }
        return -1;
    }

    private Component qualityCurrentLabel() {
        int i = qualityIndex(draft);
        if (i < 0) {
            return Component.translatable("visualizegate.config.gpu.quality.custom");
        }
        return Component.translatable(new String[] {
                "visualizegate.config.gpu.quality.low",
                "visualizegate.config.gpu.quality.medium",
                "visualizegate.config.gpu.quality.high" }[i]);
    }

    private void applyQualityPreset(int i) {
        draft.pcGpuDetail = Q_DETAIL[i];
        draft.pcPointSize = Q_SIZE[i];
    }

    /** legacy (<26.1) は GPU3D 不在＝点群は texbatch。 品質の注記キー (新版は null)。 */
    private static String gpu3dNoteKey() {
        //? if <26.1 {
        /*return "visualizegate.config.gpu.quality.legacy";*/
        //?} else {
        return null;
        //?}
    }

    private static boolean effectiveDetail(GateConfig c) {
        return c.pcOverlayDetail != null ? c.pcOverlayDetail : true;
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 暗スクリム (背後の HUD/世界を沈める)。 renderBackground は呼ばない。
        g.fill(0, 0, this.width, this.height, GateColors.SCRIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick); // 基底 (子 widget 無し・PointCloudScreen と同流儀)

        drawTitleBar(g);
        drawSidebar(g, mouseX, mouseY);
        drawDetail(g, mouseX, mouseY);
        drawFooter(g, mouseX, mouseY);
    }

    private void drawTitleBar(GuiGraphicsExtractor g) {
        int tw = this.font.width(this.title);
        int boxW = tw + 28;
        int x = (this.width - boxW) / 2;
        int y = 8;
        int h = 20;
        g.fill(x, y, x + boxW, y + h, GateColors.BASE);
        thinBorder(g, x, y, boxW, h, GateColors.ACCENT);
        g.text(this.font, this.title, (this.width - tw) / 2, y + 6, GateColors.ACCENT);
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        for (SideEntry e : sidebar) {
            if (e.section != null) {
                g.text(this.font, Component.translatable(e.section), 10, e.y + 3, GateColors.SECTION);
                g.fill(10, e.y + 14, SIDEBAR_W - 10, e.y + 15, GateColors.MAIN_DIM);
            } else {
                boolean sel = e.tab == activeTab;
                if (sel) {
                    g.fill(0, e.y, SIDEBAR_W, e.y + e.h, GateColors.SELECT_BAND);
                    g.fill(0, e.y, 2, e.y + e.h, GateColors.MAIN);
                }
                g.text(this.font, Component.translatable(e.enKey), 12, e.y + 3,
                        sel ? GateColors.ACCENT : GateColors.TEXT);
                g.text(this.font, Component.translatable(e.jaKey), 12, e.y + 12, GateColors.SUBTEXT);
            }
        }
    }

    private void drawDetail(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = detailTop();
        int bottom = footerTop();
        g.enableScissor(SIDEBAR_W, top, this.width, bottom);
        int sx = detailX();
        int y = top - scroll;
        int total = 0;
        DropdownRow toOverlay = null;
        int overlayY = 0;
        for (Row r : rows) {
            r.draw(g, sx, y, mouseX, mouseY);
            if (r == openDropdown) {
                toOverlay = openDropdown;
                overlayY = y;
            }
            int h = r.height();
            y += h;
            total += h;
        }
        contentHeight = total;
        // 展開中ドロップダウンのリストは最後に (他行の上へ) 描く。
        if (toOverlay != null) {
            toOverlay.drawOptions(g, sx, overlayY, mouseX, mouseY);
        }
        g.disableScissor();
        // スクロール上限を更新 (clamp)。
        int maxScroll = Math.max(0, contentHeight - (bottom - top));
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
    }

    private void drawFooter(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, footerTop(), this.width, footerTop() + 1, GateColors.MAIN_DIM);
        drawButton(g, resetX, "visualizegate.config.btn.reset", mouseX, mouseY);
        drawButton(g, saveX, "visualizegate.config.btn.save", mouseX, mouseY);
        drawButton(g, cancelX, "visualizegate.config.btn.cancel", mouseX, mouseY);
    }

    private void drawButton(GuiGraphicsExtractor g, int x, String key, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + btnW && mouseY >= btnY && mouseY <= btnY + 20;
        g.fill(x, btnY, x + btnW, btnY + 20, hover ? GateColors.PANEL : GateColors.BASE);
        thinBorder(g, x, btnY, btnW, 20, GateColors.ACCENT);
        Component c = Component.translatable(key);
        g.text(this.font, c, x + (btnW - this.font.width(c)) / 2, btnY + 6, GateColors.ACCENT);
    }

    /** 1px 枠 (4 辺・色 1 色)。 */
    private void thinBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力 (MouseButtonEvent: 全ノード同一・PointCloudScreen で javap 確認済)
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        // 展開中ドロップダウンが最優先 (オプション選択 / 外側クリックで閉じる)。
        if (openDropdown != null) {
            if (openDropdown.clickOption(mx, my)) {
                openDropdown = null;
                return true;
            }
            openDropdown = null;
            return true;
        }
        // フッタボタン。
        if (my >= btnY && my <= btnY + 20) {
            if (mx >= resetX && mx <= resetX + btnW) {
                doReset();
                return true;
            }
            if (mx >= saveX && mx <= saveX + btnW) {
                doSave();
                return true;
            }
            if (mx >= cancelX && mx <= cancelX + btnW) {
                onClose();
                return true;
            }
        }
        // サイドバータブ。
        if (mx >= 0 && mx <= SIDEBAR_W) {
            for (SideEntry e : sidebar) {
                if (e.tab != null && my >= e.y && my < e.y + e.h) {
                    selectTab(e.tab);
                    return true;
                }
            }
        }
        // 詳細コントロール (スクロール考慮・ビューポート内のみ)。
        if (mx >= SIDEBAR_W && my >= detailTop() && my <= footerTop()) {
            int y = detailTop() - scroll;
            for (Row r : rows) {
                if (r.click(mx, my, y)) {
                    return true;
                }
                y += r.height();
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        int y = detailTop() - scroll;
        for (Row r : rows) {
            if (r.drag(event.x(), event.y(), y)) {
                return true;
            }
            y += r.height();
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        for (Row r : rows) {
            r.release();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= SIDEBAR_W && mouseY >= detailTop() && mouseY <= footerTop()) {
            openDropdown = null; // 座標がずれるので閉じる
            int max = Math.max(0, contentHeight - (footerTop() - detailTop()));
            scroll = Math.max(0, Math.min(max, scroll - (int) (scrollY * 18)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void doReset() {
        // draft のみ既定へ (未適用＝Cancel で元に戻せる)。 schemaVersion 等の内部値は既定で問題ない。
        this.draft = GateConfig.defaults();
        this.openDropdown = null;
        buildRows();
    }

    private void doSave() {
        GateConfigManager.apply(draft);
        closeToParent();
    }

    @Override
    public void onClose() {
        // Cancel / Esc = 破棄 (apply しない)。
        closeToParent();
    }

    private void closeToParent() {
        //? if >=26.2 {
        /*this.minecraft.setScreenAndShow(this.parent);*/
        //?} else {
        this.minecraft.setScreen(this.parent);
        //?}
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // サイドバーエントリ
    // ════════════════════════════════════════════════════════════════════

    private static final class SideEntry {
        String section; // null なら tab エントリ
        Tab tab;
        String enKey;
        String jaKey;
        int y;
        int h;
    }

    // ════════════════════════════════════════════════════════════════════
    // コントロール (immediate・draft を直接読み書き)
    // ════════════════════════════════════════════════════════════════════

    private abstract class Row {
        abstract int height();

        abstract void draw(GuiGraphicsExtractor g, int sx, int sy, int mouseX, int mouseY);

        boolean click(double mx, double my, int sy) {
            return false;
        }

        boolean drag(double mx, double my, int sy) {
            return false;
        }

        void release() {
        }
    }

    /** 点群タブのサブ見出し (淡い藤色＋下線)。 */
    private final class HeaderRow extends Row {
        private final String key;

        HeaderRow(String key) {
            this.key = key;
        }

        @Override
        int height() {
            return LINE + 8;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int sx, int sy, int mouseX, int mouseY) {
            g.text(font, Component.translatable(key), sx, sy + 3, GateColors.SECTION);
            g.fill(sx, sy + 13, detailRight(), sy + 14, GateColors.MAIN_DIM);
        }
    }

    /** 説明→ラベル(EN/JA)→コントロール→補足 の共通枠 (Toggle/Slider/Dropdown/Info の基底)。 */
    private abstract class ItemRow extends Row {
        final String descKey;
        final String enKey;
        final String jaKey;
        final String noteKey;

        ItemRow(String descKey, String enKey, String jaKey, String noteKey) {
            this.descKey = descKey;
            this.enKey = enKey;
            this.jaKey = jaKey;
            this.noteKey = noteKey;
        }

        int descH() {
            return descKey != null ? LINE : 0;
        }

        int noteH() {
            return noteKey != null ? LINE : 0;
        }

        @Override
        int height() {
            return descH() + LABEL_LINE + noteH() + ROW_GAP;
        }

        /** ラベル＋コントロールが乗る行の screen Y。 */
        int lineY(int sy) {
            return sy + descH();
        }

        @Override
        void draw(GuiGraphicsExtractor g, int sx, int sy, int mouseX, int mouseY) {
            int y = sy;
            if (descKey != null) {
                g.text(font, Component.translatable(descKey), sx, y, GateColors.SUBTEXT);
                y += LINE;
            }
            // ラベル (EN 主・JA 副)。
            Component en = Component.translatable(enKey);
            g.text(font, en, sx, y + 4, GateColors.TEXT);
            if (jaKey != null) {
                g.text(font, Component.translatable(jaKey), sx + font.width(en) + 6, y + 4, GateColors.SUBTEXT);
            }
            drawControl(g, y, mouseX, mouseY);
            y += LABEL_LINE;
            if (noteKey != null) {
                g.text(font, Component.translatable(noteKey), sx, y, GateColors.SUBTEXT);
            }
        }

        /** コントロールを行 {@code lineY} に右寄せで描く。 */
        abstract void drawControl(GuiGraphicsExtractor g, int lineY, int mouseX, int mouseY);

        /** ラベル行に当たっているか (右側のコントロール帯)。 */
        boolean onLine(double my, int sy) {
            int ly = lineY(sy);
            return my >= ly && my <= ly + LABEL_LINE;
        }
    }

    /** フラットトグル (track + knob + ON/OFF テキスト)。 */
    private final class ToggleRow extends ItemRow {
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;
        private static final int TRACK_W = 26;
        private static final int TRACK_H = 12;
        private static final int HIT_W = 74;

        ToggleRow(String descKey, String enKey, String jaKey, BooleanSupplier get, Consumer<Boolean> set) {
            super(descKey, enKey, jaKey, null);
            this.get = get;
            this.set = set;
        }

        @Override
        void drawControl(GuiGraphicsExtractor g, int lineY, int mouseX, int mouseY) {
            boolean on = get.getAsBoolean();
            Component st = Component.translatable(on ? "visualizegate.state.on" : "visualizegate.state.off");
            int stW = font.width(st);
            int tx = detailRight() - TRACK_W - 6 - stW;
            int ty = lineY + (LABEL_LINE - TRACK_H) / 2;
            g.fill(tx, ty, tx + TRACK_W, ty + TRACK_H, on ? GateColors.MAIN_DIM : GateColors.PANEL);
            int kx = on ? tx + TRACK_W - TRACK_H : tx;
            g.fill(kx, ty, kx + TRACK_H, ty + TRACK_H, on ? GateColors.MAIN : GateColors.SUBTEXT);
            g.text(font, st, tx + TRACK_W + 6, lineY + 4, on ? GateColors.STATE_OK : GateColors.SUBTEXT);
        }

        @Override
        boolean click(double mx, double my, int sy) {
            if (onLine(my, sy) && mx >= detailRight() - HIT_W && mx <= detailRight()) {
                set.accept(!get.getAsBoolean());
                return true;
            }
            return false;
        }
    }

    /** 細トラック・紫フィル・右端に金の数値。 */
    private final class SliderRow extends ItemRow {
        private final double min;
        private final double max;
        private final boolean intStep;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final Function<Double, String> fmt;
        private static final int TRACK_W = 130;
        private boolean dragging;
        private int trackX; // 直近 draw の screen X (drag/click 用)

        SliderRow(String descKey, String enKey, String jaKey, String noteKey, double min, double max,
                boolean intStep, DoubleSupplier get, DoubleConsumer set, Function<Double, String> fmt) {
            super(descKey, enKey, jaKey, noteKey);
            this.min = min;
            this.max = max;
            this.intStep = intStep;
            this.get = get;
            this.set = set;
            this.fmt = fmt;
        }

        @Override
        void drawControl(GuiGraphicsExtractor g, int lineY, int mouseX, int mouseY) {
            String valStr = fmt.apply(get.getAsDouble());
            int valW = font.width(Component.literal(valStr));
            int tx = detailRight() - valW - 10 - TRACK_W;
            trackX = tx;
            int ty = lineY + LABEL_LINE / 2 - 2;
            double frac = clampFrac((get.getAsDouble() - min) / (max - min));
            g.fill(tx, ty, tx + TRACK_W, ty + 4, GateColors.PANEL);
            g.fill(tx, ty, tx + (int) (TRACK_W * frac), ty + 4, GateColors.MAIN);
            int hx = tx + (int) (TRACK_W * frac);
            g.fill(hx - 1, ty - 3, hx + 2, ty + 7, GateColors.MAIN);
            g.text(font, Component.literal(valStr), detailRight() - valW, lineY + 4, GateColors.ACCENT);
        }

        private void setFromMouse(double mx) {
            double frac = clampFrac((mx - trackX) / TRACK_W);
            double v = min + frac * (max - min);
            if (intStep) {
                v = Math.round(v);
            }
            set.accept(v);
        }

        @Override
        boolean click(double mx, double my, int sy) {
            if (onLine(my, sy) && mx >= trackX - 4 && mx <= trackX + TRACK_W + 4) {
                dragging = true;
                setFromMouse(mx);
                return true;
            }
            return false;
        }

        @Override
        boolean drag(double mx, double my, int sy) {
            if (dragging) {
                setFromMouse(mx);
                return true;
            }
            return false;
        }

        @Override
        void release() {
            dragging = false;
        }
    }

    /** ほぼ黒矩形＋細枠。 展開リストは不透明・選択行のみ控えめ塗り。 */
    private final class DropdownRow extends ItemRow {
        private final Component[] options;
        private final IntSupplier selIdx;
        private final Supplier<Component> currentText; // null なら options[clamp(selIdx)]
        private final IntConsumer choose;
        private static final int BOX_W = 124;
        private int boxX;
        private int boxY; // 直近 draw の screen 座標 (展開/hit-test 用)

        DropdownRow(String descKey, String enKey, String jaKey, String noteKey, Component[] options,
                IntSupplier selIdx, Supplier<Component> currentText, IntConsumer choose) {
            super(descKey, enKey, jaKey, noteKey);
            this.options = options;
            this.selIdx = selIdx;
            this.currentText = currentText;
            this.choose = choose;
        }

        private Component current() {
            if (currentText != null) {
                return currentText.get();
            }
            int i = Math.max(0, Math.min(options.length - 1, selIdx.getAsInt()));
            return options[i];
        }

        @Override
        void drawControl(GuiGraphicsExtractor g, int lineY, int mouseX, int mouseY) {
            int x = detailRight() - BOX_W;
            int ty = lineY + (LABEL_LINE - CTRL_H) / 2;
            boxX = x;
            boxY = ty;
            g.fill(x, ty, x + BOX_W, ty + CTRL_H, GateColors.DROPDOWN_BG);
            thinBorder(g, x, ty, BOX_W, CTRL_H, GateColors.MAIN_DIM);
            g.text(font, current(), x + 5, ty + 3, GateColors.TEXT);
            // ▾ 三角 (グリフ非依存)。
            int ax = x + BOX_W - 10;
            int ay = ty + 5;
            g.fill(ax, ay, ax + 5, ay + 1, GateColors.TEXT);
            g.fill(ax + 1, ay + 1, ax + 4, ay + 2, GateColors.TEXT);
            g.fill(ax + 2, ay + 2, ax + 3, ay + 3, GateColors.TEXT);
        }

        /** 展開リスト (draw の最後に screen が呼ぶ＝他行の上へ)。 */
        void drawOptions(GuiGraphicsExtractor g, int sx, int sy, int mouseX, int mouseY) {
            int x = boxX;
            int y = boxY + CTRL_H;
            int sel = selIdx.getAsInt();
            for (int i = 0; i < options.length; i++) {
                int oy = y + i * OPT_H;
                boolean hover = mouseX >= x && mouseX <= x + BOX_W && mouseY >= oy && mouseY <= oy + OPT_H;
                g.fill(x, oy, x + BOX_W, oy + OPT_H, GateColors.DROPDOWN_BG);
                if (i == sel || hover) {
                    g.fill(x, oy, x + BOX_W, oy + OPT_H, GateColors.SELECT_BAND);
                }
                g.text(font, options[i], x + 5, oy + 3, i == sel ? GateColors.ACCENT : GateColors.TEXT);
            }
            thinBorder(g, x, y, BOX_W, OPT_H * options.length, GateColors.MAIN_DIM);
        }

        /** 展開中の click: オプション命中で choose して true。 ボックス自体は折り畳み扱いで呼ばれない。 */
        boolean clickOption(double mx, double my) {
            int x = boxX;
            int y = boxY + CTRL_H;
            for (int i = 0; i < options.length; i++) {
                int oy = y + i * OPT_H;
                if (mx >= x && mx <= x + BOX_W && my >= oy && my <= oy + OPT_H) {
                    choose.accept(i);
                    return true;
                }
            }
            return false;
        }

        @Override
        boolean click(double mx, double my, int sy) {
            // ボックス命中で開閉トグル。
            if (mx >= boxX && mx <= boxX + BOX_W && my >= boxY && my <= boxY + CTRL_H) {
                openDropdown = (openDropdown == this) ? null : this;
                return true;
            }
            return false;
        }
    }

    /** 読み取り専用の情報行 (キーバインド表示＝再割当はバニラ Controls)。 */
    private final class InfoRow extends ItemRow {
        private final Supplier<String> value;

        InfoRow(String enKey, String jaKey, Supplier<String> value, String noteKey) {
            super(null, enKey, jaKey, noteKey);
            this.value = value;
        }

        @Override
        void drawControl(GuiGraphicsExtractor g, int lineY, int mouseX, int mouseY) {
            String v;
            try {
                v = value.get();
            } catch (Throwable t) {
                v = "?";
            }
            Component c = Component.literal(v);
            g.text(font, c, detailRight() - font.width(c), lineY + 4, GateColors.ACCENT);
        }
    }

    private static double clampFrac(double f) {
        return Math.max(0.0, Math.min(1.0, f));
    }
}
