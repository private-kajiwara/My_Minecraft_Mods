package com.kajiwara.visualizegate.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import com.kajiwara.visualizegate.client.keybind.GateKeyBindings;
import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.state.PointCloudViewState;
import com.kajiwara.visualizegate.ui.GateColors;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * ModMenu 設定画面 (独自 Screen・OmniChest 風フラット・<b>Mixin 不使用</b>・全コントロール自前 immediate 描画)。
 *
 * <p><b>レイアウト</b>: 上中央タイトルバー (1px 金枠) / 左サイドバー (枠なし・セクション見出し＋2 段タブ・選択帯) /
 * 右詳細 (枠なし・スクロール・説明→ラベル→コントロール→補足) / 下 3 ボタン (Reset/Save/Cancel)。 金は<b>点
 * アクセント</b>のみ (タイトル文字・ボタン枠/文字・選択タブラベル・数値)。 暗スクリムでゲーム内 HUD を沈める。
 *
 * <p><b>ステージング</b>: 編集は {@link #draft} 上で行い、 <b>Save</b> で {@link GateConfigManager#apply} (live
 * state へ反映＋GSON 保存)、 <b>Cancel</b>/Esc で破棄、 <b>Reset</b> で確認ポップアップ→「はい」で draft の
 * <b>画面表示フィールドのみ</b>を既定へ (まだ未適用＝Cancel で戻せる・非UIのライブ調整フィールドは温存)。
 *
 * <p><b>Reset 確認</b> ({@link #confirmOpen}): 画面内オーバーレイ層 (別 Screen にしない)。 既定と異なる<b>変更
 * フィールドのみ</b>を「現在 → 既定」1 行/項目・タブ別に表示し、 はい/いいえで確定/取消。 変更ゼロなら Reset
 * ボタンを非活性化 (= ポップアップを出さない)。 modal 中は下層コントロールへ入力を通さない。
 *
 * <p><b>背景</b> (OmniChest 知見): MC 1.21.5+ は GameRenderer が外側で backdrop を描く。 ここで
 * {@code renderBackground} を呼ぶと blur 二重起動でクラッシュするため<b>呼ばない</b>。 自前スクリムを塗る。
 */
public class GateConfigScreen extends Screen {

    // ── タブ (セクション 2 系統)。 ──
    private enum Tab {
        CPU, GPU, GATE_VISUALS, POINT_CLOUD, DOCK_HUD, KEYBINDS
    }

    // タブ → サイドバー見出しキー (diff のセクション見出しにも流用)。
    private static String tabLabelKey(Tab t) {
        return switch (t) {
            case CPU -> "visualizegate.config.tab2.cpu";
            case GPU -> "visualizegate.config.tab2.gpu";
            case GATE_VISUALS -> "visualizegate.config.tab2.gatevis";
            case POINT_CLOUD -> "visualizegate.config.tab2.pointcloud";
            case DOCK_HUD -> "visualizegate.config.tab2.dockhud";
            case KEYBINDS -> "visualizegate.config.tab2.keybinds";
        };
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
    private final List<Row> rows = new ArrayList<>(); // 全タブの master list (Row.tab で表示を filter)
    private DropdownRow openDropdown; // 展開中ドロップダウン (null=なし)

    // ホバーツールチップ (drawDetail で収集し extractRenderState の最後に自前ボックス描画)。
    private Component pendingTip;
    private int tipMx;
    private int tipMy;

    // ── Reset 確認ポップアップ (modal overlay) ──
    private boolean confirmOpen = false;
    private final List<DiffEntry> diff = new ArrayList<>();
    private int diffScroll = 0;
    // 直近 draw で算出した panel/ボタン/リスト矩形 (hit-test 用)。
    private int cpX;
    private int cpY;
    private int cpW;
    private int cpH;
    private int cpListTop;
    private int cpListBottom;
    private int cpYesX;
    private int cpNoX;
    private int cpBtnY;
    private int cpBtnW;

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
        y = addTab(y, Tab.CPU, "visualizegate.config.tab2.cpu");
        y = addTab(y, Tab.GPU, "visualizegate.config.tab2.gpu");
        y += 8;
        y = addSection(y, "visualizegate.config.section.display");
        y = addTab(y, Tab.GATE_VISUALS, "visualizegate.config.tab2.gatevis");
        y = addTab(y, Tab.POINT_CLOUD, "visualizegate.config.tab2.pointcloud");
        y = addTab(y, Tab.DOCK_HUD, "visualizegate.config.tab2.dockhud");
        y = addTab(y, Tab.KEYBINDS, "visualizegate.config.tab2.keybinds");
    }

    private int addSection(int y, String key) {
        SideEntry e = new SideEntry();
        e.section = key;
        e.y = y;
        e.h = 16;
        sidebar.add(e);
        return y + e.h;
    }

    private int addTab(int y, Tab tab, String labelKey) {
        SideEntry e = new SideEntry();
        e.tab = tab;
        e.labelKey = labelKey;
        e.y = y;
        e.h = 18; // 単一ラベル化で 1 行分に圧縮
        sidebar.add(e);
        return y + e.h;
    }

    private void selectTab(Tab t) {
        this.activeTab = t;
        this.scroll = 0;
        this.openDropdown = null;
    }

    // ════════════════════════════════════════════════════════════════════
    // 詳細 — 全タブの master list を 1 度だけ構築 (Row.tab で表示/ヒットテストを filter)
    // ════════════════════════════════════════════════════════════════════

    private void add(Tab tab, Row r) {
        r.tab = tab;
        rows.add(r);
    }

    private void buildRows() {
        rows.clear();
        // ── CPU ──
        add(Tab.CPU, new ToggleRow(null,
                "visualizegate.config.cpu.sampling.label", "visualizegate.config.cpu.sampling.tip",
                c -> c.cpuSamplingEnabled, v -> draft.cpuSamplingEnabled = v));
        add(Tab.CPU, new DropdownRow(null,
                "visualizegate.config.cpu.rate.label", "visualizegate.config.cpu.rate.tip",
                new Component[] {
                        Component.translatable("visualizegate.config.cpu.rate.05"),
                        Component.translatable("visualizegate.config.cpu.rate.1"),
                        Component.translatable("visualizegate.config.cpu.rate.2") },
                c -> hzIndex(c.cpuSamplingHz), null,
                i -> draft.cpuSamplingHz = HZ_VALUES[i]));
        add(Tab.CPU, new ToggleRow(null,
                "visualizegate.config.cpu.graph.label", "visualizegate.config.cpu.graph.tip",
                c -> c.cpuGraphEnabled, v -> draft.cpuGraphEnabled = v));
        // ── GPU / Render ── (Render Distance のみインライン 1 行を残す)
        add(Tab.GPU, new SliderRow("visualizegate.config.gpu.dist.inline",
                "visualizegate.config.gpu.dist.label", "visualizegate.config.gpu.dist.tip",
                GateMenuState.GATE_RENDER_DIST_MIN, GateMenuState.GATE_RENDER_DIST_MAX, true,
                c -> c.gateRenderDistanceM, v -> draft.gateRenderDistanceM = (float) v,
                v -> String.format("%d m", Math.round(v))));
        add(Tab.GPU, new DropdownRow(null,
                "visualizegate.config.gpu.quality.label", "visualizegate.config.gpu.quality.tip",
                new Component[] {
                        Component.translatable("visualizegate.config.gpu.quality.low"),
                        Component.translatable("visualizegate.config.gpu.quality.medium"),
                        Component.translatable("visualizegate.config.gpu.quality.high") },
                GateConfigScreen::qualityIndex, GateConfigScreen::qualityCurrentLabel,
                this::applyQualityPreset));
        // ── Gate Visuals ──
        add(Tab.GATE_VISUALS, new ToggleRow(null,
                "visualizegate.config.gv.frame.label", "visualizegate.config.gv.frame.tip",
                c -> c.boxOverlayEnabled, v -> draft.boxOverlayEnabled = v));
        add(Tab.GATE_VISUALS, new ToggleRow(null,
                "visualizegate.config.gv.dome.label", "visualizegate.config.gv.dome.tip",
                c -> c.domeEnabled, v -> draft.domeEnabled = v));
        add(Tab.GATE_VISUALS, new ToggleRow(null,
                "visualizegate.config.gv.holo.label", "visualizegate.config.gv.holo.tip",
                c -> c.hologramEnabled, v -> draft.hologramEnabled = v));
        add(Tab.GATE_VISUALS, new ToggleRow(null,
                "visualizegate.config.gv.names.label", "visualizegate.config.gv.names.tip",
                c -> c.gateNamesEnabled, v -> draft.gateNamesEnabled = v));
        add(Tab.GATE_VISUALS, new DropdownRow(null,
                "visualizegate.config.gv.mode.label", "visualizegate.config.gv.mode.tip",
                new Component[] {
                        Component.translatable("visualizegate.mode.simple"),
                        Component.translatable("visualizegate.mode.advanced") },
                c -> c.advancedMode ? 1 : 0, null,
                i -> draft.advancedMode = (i == 1)));
        // ── Point Cloud ──
        add(Tab.POINT_CLOUD, new HeaderRow("visualizegate.config.pc.group.visibility"));
        add(Tab.POINT_CLOUD, new ToggleRow(null,
                "visualizegate.config.pc.panel.label", "visualizegate.config.pc.panel.tip",
                c -> c.pcPanelVisible, v -> draft.pcPanelVisible = v));
        add(Tab.POINT_CLOUD, new ToggleRow(null,
                "visualizegate.config.pc.ow.label", "visualizegate.config.pc.ow.tip",
                c -> c.pcShowOverworld, v -> draft.pcShowOverworld = v));
        add(Tab.POINT_CLOUD, new ToggleRow(null,
                "visualizegate.config.pc.nether.label", "visualizegate.config.pc.nether.tip",
                c -> c.pcShowNether, v -> draft.pcShowNether = v));
        add(Tab.POINT_CLOUD, new ToggleRow(null,
                "visualizegate.config.pc.links.label", "visualizegate.config.pc.links.tip",
                c -> c.pcShowLinks, v -> draft.pcShowLinks = v));
        add(Tab.POINT_CLOUD, new ToggleRow(null,
                "visualizegate.config.pc.solo.label", "visualizegate.config.pc.solo.tip",
                c -> c.pcCloudOnly, v -> draft.pcCloudOnly = v));
        add(Tab.POINT_CLOUD, new HeaderRow("visualizegate.config.pc.group.appearance"));
        add(Tab.POINT_CLOUD, new SliderRow(null,
                "visualizegate.config.pc.size.label", "visualizegate.config.pc.size.tip",
                PointCloudViewState.POINT_SIZE_MIN, PointCloudViewState.POINT_SIZE_MAX, true,
                c -> c.pcPointSize, v -> draft.pcPointSize = (int) Math.round(v),
                v -> String.format("%d px", Math.round(v))));
        add(Tab.POINT_CLOUD, new ToggleRow(null,
                "visualizegate.config.pc.tint.label", "visualizegate.config.pc.tint.tip",
                c -> c.pcDimTint, v -> draft.pcDimTint = v));
        add(Tab.POINT_CLOUD, new DropdownRow(null,
                "visualizegate.config.pc.detail.label", "visualizegate.config.pc.detail.tip",
                new Component[] {
                        Component.translatable("visualizegate.config.pc.detail.full"),
                        Component.translatable("visualizegate.config.pc.detail.brief") },
                c -> effectiveDetail(c) ? 0 : 1, null,
                i -> draft.pcOverlayDetail = (i == 0)));
        add(Tab.POINT_CLOUD, new HeaderRow("visualizegate.config.pc.group.layout"));
        add(Tab.POINT_CLOUD, new SliderRow(null,
                "visualizegate.config.pc.spacing.label", "visualizegate.config.pc.spacing.tip",
                PointCloudViewState.SPACING_MIN, PointCloudViewState.SPACING_MAX, true,
                c -> c.pcDimensionSpacing, v -> draft.pcDimensionSpacing = (int) Math.round(v),
                v -> String.valueOf(Math.round(v))));
        // ── Dock / HUD ──
        add(Tab.DOCK_HUD, new ToggleRow(null,
                "visualizegate.config.dh.icon.label", "visualizegate.config.dh.icon.tip",
                c -> c.hudIconEnabled, v -> draft.hudIconEnabled = v));
        add(Tab.DOCK_HUD, new ToggleRow(null,
                "visualizegate.config.dh.legend.label", "visualizegate.config.dh.legend.tip",
                c -> c.legendEnabled, v -> draft.legendEnabled = v));
        add(Tab.DOCK_HUD, new InfoRow("visualizegate.config.dh.dockkey.label", "visualizegate.config.keys.note",
                GateKeyBindings::dockKeyDisplay));
        // ── Keybinds (読み取り専用・diff 対象外) ──
        add(Tab.KEYBINDS, new InfoRow("visualizegate.config.kb.openmenu.label", "visualizegate.config.keys.note",
                GateKeyBindings::boundKeyDisplay));
        add(Tab.KEYBINDS, new InfoRow("visualizegate.config.kb.dock.label", "visualizegate.config.keys.note",
                GateKeyBindings::dockKeyDisplay));
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

    private static Component qualityCurrentLabel(GateConfig c) {
        int i = qualityIndex(c);
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

        // ホバーツールチップ (ドロップダウン展開中/ポップアップ中は抑止)。
        if (pendingTip != null && openDropdown == null && !confirmOpen) {
            drawTooltipBox(g, pendingTip, tipMx, tipMy);
        }
        if (confirmOpen) {
            drawConfirm(g, mouseX, mouseY);
        }
    }

    /** 自前ツールチップ (immediate・細枠ボックス・1 行・画面内へクランプ)。 */
    private void drawTooltipBox(GuiGraphicsExtractor g, Component text, int mx, int my) {
        int tw = this.font.width(text);
        int pad = 4;
        int boxW = tw + pad * 2;
        int boxH = 9 + pad * 2;
        int bx = mx + 12;
        int by = my - 6;
        if (bx + boxW > this.width - 2) {
            bx = mx - 12 - boxW; // 右に入らなければ左へ
        }
        if (bx < 2) {
            bx = 2;
        }
        if (by + boxH > this.height - 2) {
            by = this.height - 2 - boxH;
        }
        if (by < 2) {
            by = 2;
        }
        g.fill(bx, by, bx + boxW, by + boxH, GateColors.BASE);
        thinBorder(g, bx, by, boxW, boxH, GateColors.MAIN_DIM);
        g.text(this.font, text, bx + pad, by + pad, GateColors.TEXT);
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
                g.text(this.font, Component.translatable(e.labelKey), 12, e.y + 5,
                        sel ? GateColors.ACCENT : GateColors.TEXT);
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
        pendingTip = null;
        for (Row r : rows) {
            if (r.tab != activeTab) {
                continue;
            }
            r.draw(g, sx, y, mouseX, mouseY);
            if (r == openDropdown) {
                toOverlay = openDropdown;
                overlayY = y;
            }
            // ホバーツールチップ収集 (ビューポート内・ラベル行のみ)。
            if (r instanceof ItemRow ir && mouseY >= top && mouseY <= bottom
                    && ir.hoveredForTip(mouseX, mouseY, y)) {
                pendingTip = Component.translatable(ir.tipKey);
                tipMx = mouseX;
                tipMy = mouseY;
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
        boolean resetEnabled = anyChangedFromDefault();
        drawButton(g, resetX, "visualizegate.config.btn.reset", mouseX, mouseY, resetEnabled, GateColors.ACCENT);
        drawButton(g, saveX, "visualizegate.config.btn.save", mouseX, mouseY, true, GateColors.ACCENT);
        drawButton(g, cancelX, "visualizegate.config.btn.cancel", mouseX, mouseY, true, GateColors.ACCENT);
    }

    private void drawButton(GuiGraphicsExtractor g, int x, String key, int mouseX, int mouseY,
            boolean enabled, int color) {
        boolean hover = enabled && mouseX >= x && mouseX <= x + btnW && mouseY >= btnY && mouseY <= btnY + 20;
        int c = enabled ? color : GateColors.SUBTEXT;
        g.fill(x, btnY, x + btnW, btnY + 20, hover ? GateColors.PANEL : GateColors.BASE);
        thinBorder(g, x, btnY, btnW, 20, c);
        Component label = Component.translatable(key);
        g.text(this.font, label, x + (btnW - this.font.width(label)) / 2, btnY + 6, c);
    }

    /** 1px 枠 (4 辺・色 1 色)。 */
    private void thinBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ════════════════════════════════════════════════════════════════════
    // Reset 確認ポップアップ (modal overlay)
    // ════════════════════════════════════════════════════════════════════

    /** 既定と異なる表示フィールドがあるか (Reset ボタンの活性判定)。 */
    private boolean anyChangedFromDefault() {
        GateConfig def = GateConfig.defaults();
        for (Row r : rows) {
            if (r instanceof ItemRow ir && ir.resettable() && ir.changedFromDefault(draft, def)) {
                return true;
            }
        }
        return false;
    }

    /** 変更フィールドの diff を構築してポップアップを開く。 */
    private void openConfirm() {
        GateConfig def = GateConfig.defaults();
        diff.clear();
        for (Row r : rows) {
            if (r instanceof ItemRow ir && ir.resettable() && ir.changedFromDefault(draft, def)) {
                diff.add(new DiffEntry(ir.tab, Component.translatable(ir.labelKey),
                        ir.value(draft), ir.value(def)));
            }
        }
        if (diff.isEmpty()) {
            return; // 念のため (ボタン非活性のはずだが二重ガード)
        }
        diffScroll = 0;
        openDropdown = null;
        confirmOpen = true;
    }

    /** 「はい」: 表示フィールドのみ既定へ (非UIフィールドは温存)。 永続化しない (staging 維持)。 */
    private void applyReset() {
        GateConfig def = GateConfig.defaults();
        for (Row r : rows) {
            if (r instanceof ItemRow ir && ir.resettable()) {
                ir.applyDefault(draft, def);
            }
        }
        confirmOpen = false;
    }

    private static final int CP_LINE = 11;
    private static final int CP_SECTION_H = 13;

    private void drawConfirm(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // 追加スクリム (下層を更に沈める＝modal を強調)。
        g.fill(0, 0, this.width, this.height, 0x99000000);

        // 内容高を見積もる (セクション見出し + 行)。
        int rowsH = 0;
        Tab prev = null;
        for (DiffEntry e : diff) {
            if (e.tab != prev) {
                rowsH += CP_SECTION_H;
                prev = e.tab;
            }
            rowsH += CP_LINE;
        }
        int pw = Math.min(380, this.width - 40);
        int titleH = 24;
        int btnAreaH = 30;
        int listMax = Math.max(CP_LINE * 3, this.height - 80 - titleH - btnAreaH);
        int listH = Math.min(rowsH, listMax);
        int ph = titleH + listH + btnAreaH + 8;
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;
        cpX = px;
        cpY = py;
        cpW = pw;
        cpH = ph;

        g.fill(px, py, px + pw, py + ph, GateColors.BASE);
        thinBorder(g, px, py, pw, ph, GateColors.ACCENT);

        // タイトル (金・中央)。
        Component title = Component.translatable("visualizegate.config.reset.title");
        g.text(this.font, title, px + (pw - this.font.width(title)) / 2, py + 7, GateColors.ACCENT);
        g.fill(px + 8, py + titleH - 2, px + pw - 8, py + titleH - 1, GateColors.MAIN_DIM);

        // diff リスト (scissor・スクロール)。
        int listTop = py + titleH;
        int listBottom = listTop + listH;
        cpListTop = listTop;
        cpListBottom = listBottom;
        g.enableScissor(px, listTop, px + pw, listBottom);
        int y = listTop - diffScroll + 2;
        prev = null;
        for (DiffEntry e : diff) {
            if (e.tab != prev) {
                g.text(this.font, Component.translatable(tabLabelKey(e.tab)), px + 8, y + 1, GateColors.SECTION);
                y += CP_SECTION_H;
                prev = e.tab;
            }
            drawDiffLine(g, px, y, pw, e);
            y += CP_LINE;
        }
        g.disableScissor();
        int maxScroll = Math.max(0, rowsH - listH);
        if (diffScroll > maxScroll) {
            diffScroll = maxScroll;
        }

        // ボタン (いいえ=neutral / はい=danger 赤)。
        int gap = 8;
        cpBtnW = (pw - 16 - gap) / 2;
        cpBtnY = py + ph - btnAreaH + 4;
        cpNoX = px + 8;
        cpYesX = cpNoX + cpBtnW + gap;
        drawConfirmButton(g, cpNoX, cpBtnY, cpBtnW, "visualizegate.config.reset.no", mouseX, mouseY,
                GateColors.SUBTEXT);
        drawConfirmButton(g, cpYesX, cpBtnY, cpBtnW, "visualizegate.config.reset.yes", mouseX, mouseY,
                GateColors.STATE_CONFLICT);
    }

    /** diff 1 行: ラベル (左・dim) ／ 現在(dim) → 既定(金) (右)。 */
    private void drawDiffLine(GuiGraphicsExtractor g, int px, int y, int pw, DiffEntry e) {
        int right = px + pw - 8;
        int defW = this.font.width(e.def);
        int defX = right - defW;
        Component arrow = Component.literal("→");
        int arrowW = this.font.width(arrow);
        int arrowX = defX - 4 - arrowW;
        int curW = this.font.width(e.cur);
        int curX = arrowX - 4 - curW;
        // ラベルを先に描き、 右の値群を後で上書き＝重なれば右(値)優先。
        g.text(this.font, e.label, px + 8, y, GateColors.TEXT);
        g.text(this.font, e.cur, curX, y, GateColors.SUBTEXT);
        g.text(this.font, arrow, arrowX, y, GateColors.MAIN_DIM);
        g.text(this.font, e.def, defX, y, GateColors.ACCENT);
    }

    private void drawConfirmButton(GuiGraphicsExtractor g, int x, int y, int w, String key,
            int mouseX, int mouseY, int color) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 18;
        g.fill(x, y, x + w, y + 18, hover ? GateColors.PANEL : GateColors.BASE);
        thinBorder(g, x, y, w, 18, color);
        Component label = Component.translatable(key);
        g.text(this.font, label, x + (w - this.font.width(label)) / 2, y + 5, color);
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力 (MouseButtonEvent / KeyEvent: 全ノード同一・PointCloudScreen/GateRenameScreen で javap 確認済)
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        // modal: ポップアップ表示中は下層へ通さない。
        if (confirmOpen) {
            if (mx >= cpYesX && mx <= cpYesX + cpBtnW && my >= cpBtnY && my <= cpBtnY + 18) {
                applyReset();
            } else if (mx >= cpNoX && mx <= cpNoX + cpBtnW && my >= cpBtnY && my <= cpBtnY + 18) {
                confirmOpen = false; // いいえ
            } else if (mx < cpX || mx > cpX + cpW || my < cpY || my > cpY + cpH) {
                confirmOpen = false; // スクリム外クリック=取消
            }
            return true;
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
                if (anyChangedFromDefault()) {
                    openConfirm();
                }
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
        // 詳細コントロール (スクロール考慮・ビューポート内のみ・活性タブのみ)。
        if (mx >= SIDEBAR_W && my >= detailTop() && my <= footerTop()) {
            int y = detailTop() - scroll;
            for (Row r : rows) {
                if (r.tab != activeTab) {
                    continue;
                }
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
        if (confirmOpen) {
            return true; // modal: 下層ドラッグを通さない
        }
        int y = detailTop() - scroll;
        for (Row r : rows) {
            if (r.tab != activeTab) {
                continue;
            }
            if (r.drag(event.x(), event.y(), y)) {
                return true;
            }
            y += r.height();
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (confirmOpen) {
            return true;
        }
        for (Row r : rows) {
            r.release();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (confirmOpen) {
            if (mouseX >= cpX && mouseX <= cpX + cpW && mouseY >= cpListTop && mouseY <= cpListBottom) {
                diffScroll = Math.max(0, diffScroll - (int) (scrollY * 14));
            }
            return true;
        }
        if (mouseX >= SIDEBAR_W && mouseY >= detailTop() && mouseY <= footerTop()) {
            openDropdown = null; // 座標がずれるので閉じる
            int max = Math.max(0, contentHeight - (footerTop() - detailTop()));
            scroll = Math.max(0, Math.min(max, scroll - (int) (scrollY * 18)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // modal 中の Esc はポップアップだけ閉じる (画面は閉じない)。 Enter は はい に割り当てない (誤操作防止)。
        if (confirmOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            confirmOpen = false;
            return true;
        }
        return super.keyPressed(event);
    }

    private void doSave() {
        GateConfigManager.apply(draft);
        closeToParent();
    }

    @Override
    public void onClose() {
        // Cancel / Esc = 破棄 (apply しない)。 ポップアップ中の Esc は keyPressed が先に消費。
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
    // サイドバーエントリ / diff エントリ
    // ════════════════════════════════════════════════════════════════════

    private static final class SideEntry {
        String section; // null なら tab エントリ
        Tab tab;
        String labelKey; // 単一言語タブラベル
        int y;
        int h;
    }

    private static final class DiffEntry {
        final Tab tab;
        final Component label;
        final Component cur;
        final Component def;

        DiffEntry(Tab tab, Component label, Component cur, Component def) {
            this.tab = tab;
            this.label = label;
            this.cur = cur;
            this.def = def;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // コントロール (immediate・reader は GateConfig を受け取り draft/defaults 双方を整形)
    // ════════════════════════════════════════════════════════════════════

    private abstract class Row {
        Tab tab;

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

    /** ラベル(単一言語)→コントロール→任意インライン補足 の共通枠 (Toggle/Slider/Dropdown/Info の基底)。
     *  長い説明はホバーの {@link #tipKey} ツールチップへ退避 (本体テキストはコンパクトに保つ)。 */
    private abstract class ItemRow extends Row {
        final String inlineKey; // 任意の 1 行インライン補足 (基本 null・Render Distance のみ)
        final String labelKey;  // 単一言語ラベル (MC 言語追従・1 項目 1 キー)
        final String tipKey;    // ホバーツールチップ (任意・詳細はここへ)

        ItemRow(String inlineKey, String labelKey, String tipKey) {
            this.inlineKey = inlineKey;
            this.labelKey = labelKey;
            this.tipKey = tipKey;
        }

        int inlineH() {
            return inlineKey != null ? LINE : 0;
        }

        @Override
        int height() {
            return LABEL_LINE + inlineH() + ROW_GAP;
        }

        /** ラベル＋コントロールが乗る行の screen Y (= 行頭)。 */
        int lineY(int sy) {
            return sy;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int sx, int sy, int mouseX, int mouseY) {
            g.text(font, Component.translatable(labelKey), sx, sy + 4, GateColors.TEXT);
            drawControl(g, sy, mouseX, mouseY);
            if (inlineKey != null) {
                g.text(font, Component.translatable(inlineKey), sx, sy + LABEL_LINE, GateColors.SUBTEXT);
            }
        }

        /** コントロールを行 {@code lineY} に右寄せで描く。 */
        abstract void drawControl(GuiGraphicsExtractor g, int lineY, int mouseX, int mouseY);

        /** ラベル行に当たっているか (右側のコントロール帯)。 */
        boolean onLine(double my, int sy) {
            int ly = lineY(sy);
            return my >= ly && my <= ly + LABEL_LINE;
        }

        /** ツールチップを出すホバー帯か (ラベル行・詳細 x 範囲)。 */
        boolean hoveredForTip(double mx, double my, int sy) {
            return tipKey != null && mx >= detailX() && mx <= detailRight()
                    && my >= sy && my <= sy + LABEL_LINE;
        }

        // ── diff / reset 用 (記述子を流用・二重定義なし) ──
        /** Reset/diff の対象か (Info=Keybinds は false)。 */
        boolean resettable() {
            return true;
        }

        /** 任意 cfg からの表示値 (current/default 両用)。 */
        abstract Component value(GateConfig cfg);

        /** draft が既定と異なるか (整形値の文字列比較＝記述子のフォーマッタを流用)。 */
        boolean changedFromDefault(GateConfig draftCfg, GateConfig def) {
            return !value(draftCfg).getString().equals(value(def).getString());
        }

        /** draft の当該フィールドを既定値へ書き戻す。 */
        abstract void applyDefault(GateConfig draftCfg, GateConfig def);
    }

    /** フラットトグル (track + knob + ON/OFF テキスト)。 */
    private final class ToggleRow extends ItemRow {
        private final Function<GateConfig, Boolean> get;
        private final Consumer<Boolean> set;
        private static final int TRACK_W = 26;
        private static final int TRACK_H = 12;
        private static final int HIT_W = 74;

        ToggleRow(String inlineKey, String labelKey, String tipKey,
                Function<GateConfig, Boolean> get, Consumer<Boolean> set) {
            super(inlineKey, labelKey, tipKey);
            this.get = get;
            this.set = set;
        }

        @Override
        void drawControl(GuiGraphicsExtractor g, int lineY, int mouseX, int mouseY) {
            boolean on = get.apply(draft);
            Component st = onOff(on);
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
                set.accept(!get.apply(draft));
                return true;
            }
            return false;
        }

        @Override
        Component value(GateConfig cfg) {
            return onOff(get.apply(cfg));
        }

        @Override
        void applyDefault(GateConfig draftCfg, GateConfig def) {
            set.accept(get.apply(def));
        }
    }

    /** 細トラック・紫フィル・右端に金の数値。 */
    private final class SliderRow extends ItemRow {
        private final double min;
        private final double max;
        private final boolean intStep;
        private final ToDoubleFunction<GateConfig> get;
        private final DoubleConsumer set;
        private final Function<Double, String> fmt;
        private static final int TRACK_W = 130;
        private boolean dragging;
        private int trackX; // 直近 draw の screen X (drag/click 用)

        SliderRow(String inlineKey, String labelKey, String tipKey, double min, double max,
                boolean intStep, ToDoubleFunction<GateConfig> get, DoubleConsumer set, Function<Double, String> fmt) {
            super(inlineKey, labelKey, tipKey);
            this.min = min;
            this.max = max;
            this.intStep = intStep;
            this.get = get;
            this.set = set;
            this.fmt = fmt;
        }

        @Override
        void drawControl(GuiGraphicsExtractor g, int lineY, int mouseX, int mouseY) {
            String valStr = fmt.apply(get.applyAsDouble(draft));
            int valW = font.width(Component.literal(valStr));
            int tx = detailRight() - valW - 10 - TRACK_W;
            trackX = tx;
            int ty = lineY + LABEL_LINE / 2 - 2;
            double frac = clampFrac((get.applyAsDouble(draft) - min) / (max - min));
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

        @Override
        Component value(GateConfig cfg) {
            return Component.literal(fmt.apply(get.applyAsDouble(cfg)));
        }

        @Override
        void applyDefault(GateConfig draftCfg, GateConfig def) {
            set.accept(get.applyAsDouble(def));
        }
    }

    /** ほぼ黒矩形＋細枠。 展開リストは不透明・選択行のみ控えめ塗り。 */
    private final class DropdownRow extends ItemRow {
        private final Component[] options;
        private final ToIntFunction<GateConfig> selIdx;
        private final Function<GateConfig, Component> currentText; // null なら options[clamp(selIdx)]
        private final IntConsumer choose;
        private static final int BOX_W = 124;
        private int boxX;
        private int boxY; // 直近 draw の screen 座標 (展開/hit-test 用)

        DropdownRow(String inlineKey, String labelKey, String tipKey, Component[] options,
                ToIntFunction<GateConfig> selIdx, Function<GateConfig, Component> currentText, IntConsumer choose) {
            super(inlineKey, labelKey, tipKey);
            this.options = options;
            this.selIdx = selIdx;
            this.currentText = currentText;
            this.choose = choose;
        }

        private Component current(GateConfig cfg) {
            if (currentText != null) {
                return currentText.apply(cfg);
            }
            int i = Math.max(0, Math.min(options.length - 1, selIdx.applyAsInt(cfg)));
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
            g.text(font, current(draft), x + 5, ty + 3, GateColors.TEXT);
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
            int sel = selIdx.applyAsInt(draft);
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

        /** 展開中の click: オプション命中で choose して true。 */
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

        @Override
        Component value(GateConfig cfg) {
            return current(cfg);
        }

        @Override
        void applyDefault(GateConfig draftCfg, GateConfig def) {
            choose.accept(selIdx.applyAsInt(def));
        }
    }

    /** 読み取り専用の情報行 (キーバインド表示＝再割当はバニラ Controls)。 diff/reset 対象外。 */
    private final class InfoRow extends ItemRow {
        private final java.util.function.Supplier<String> value;

        InfoRow(String labelKey, String tipKey, java.util.function.Supplier<String> value) {
            super(null, labelKey, tipKey);
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

        @Override
        boolean resettable() {
            return false;
        }

        @Override
        Component value(GateConfig cfg) {
            return Component.empty();
        }

        @Override
        void applyDefault(GateConfig draftCfg, GateConfig def) {
            // no-op (Controls 管理)
        }
    }

    private static Component onOff(boolean b) {
        return Component.translatable(b ? "visualizegate.state.on" : "visualizegate.state.off");
    }

    private static double clampFrac(double f) {
        return Math.max(0.0, Math.min(1.0, f));
    }
}
