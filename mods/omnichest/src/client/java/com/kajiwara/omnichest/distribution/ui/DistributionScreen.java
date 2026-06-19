package com.kajiwara.omnichest.distribution.ui;

import com.kajiwara.omnichest.classify.StorageCategory;
import com.kajiwara.omnichest.client.gui.search.LargeIconRenderer;
import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.client.gui.search.layout.UILayoutMetrics;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.config.data.DistributionConfig;
import com.kajiwara.omnichest.distribution.DistributionQueue;
import com.kajiwara.omnichest.distribution.PendingTransfer;
import com.kajiwara.omnichest.distribution.StorageAssignment;
import com.kajiwara.omnichest.distribution.StorageAssignmentManager;
import com.kajiwara.omnichest.distribution.TransferHistoryManager;
import com.kajiwara.omnichest.distribution.TransferRecord;
import com.kajiwara.omnichest.distribution.VirtualTransferRegistry;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.i18n.RTLLayoutManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 「Storage Distribution Menu」 — Storage Auto Distribution のメイン GUI。
 *
 * <p>
 * <b>テーマ統一・ロジック分離</b> (= 仕様の最重要要件):
 * 配色・寸法・トーンは倉庫検索 GUI と同じ {@link ThemeColorResolver} / {@link UILayoutMetrics} を
 * <em>再利用</em> する。 一方でデータ源・キュー・永続化は分配系専用クラス
 * ({@link StorageAssignmentManager} / {@link VirtualTransferRegistry} /
 * {@link DistributionQueue} / {@link TransferHistoryManager}) のみを参照し、 検索系とは一切共有しない。
 *
 * <p>
 * 画面構成 (= 仕様の GUI 内容):
 * <ul>
 *   <li>左: カテゴリタブ列 (= Registered Storage のフィルタ。 ALL / Favorites / 各カテゴリ)。</li>
 *   <li>上: セクション切替 ({@link DistributionSection}) — Storage / Pending / Queue / History / Failed。</li>
 *   <li>中央: 選択セクションのリスト (= 転送行は {@link TransferVisualizationRenderer} で矢印表示)。</li>
 * </ul>
 *
 * <p>
 * デザイン 4 原則 (近接 / 整列 / 反復 / コントラスト) を踏襲し、 全座標は {@link UILayoutMetrics}
 * の定数に揃える。 RTL では {@link RTLLayoutManager} でタブ列とリストを左右反転する。
 */
public final class DistributionScreen extends Screen {

    @Nullable
    private final Screen parent;

    // ─── レイアウト (init で算出) ───
    private int tabX, tabTop, tabW, tabBottom;
    private int listX, listTop, listRight, listBottom;
    private int rowHeight = 22;

    // ─── 状態 ───
    private DistributionSection currentSection = DistributionSection.STORAGE;
    private final List<TabEntry> tabs = new ArrayList<>();
    private int selectedTab = 0; // 0 = ALL
    private double scrollPx = 0.0;
    private double tabScrollPx = 0.0;
    private boolean draggingScroll = false;
    private double scrollDragOffsetY = 0.0;

    private final List<Button> sectionButtons = new ArrayList<>();

    public DistributionScreen(@Nullable Screen parent) {
        super(OmniChestLocale.get("omnichest.distribution.title", "Storage Distribution"));
        this.parent = parent;
    }

    public static void open(@Nullable Screen parent) {
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(new DistributionScreen(parent));*/
        //?} else {
        Minecraft.getInstance().setScreen(new DistributionScreen(parent));
        //?}
    }

    public static void open() {
        open(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // ライフサイクル
    // ════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        super.init();
        buildTabs();

        boolean rtl = RTLLayoutManager.get().isRtl();
        int inset = UILayoutMetrics.SCREEN_INSET_X;
        Font font = this.font;

        // ─── セクションボタン行 ───
        int sectionY = UILayoutMetrics.SCREEN_INSET_TOP + font.lineHeight + 6;
        this.sectionButtons.clear();
        int sx = inset;
        for (DistributionSection sec : DistributionSection.values()) {
            int bw = Math.max(48, font.width(sec.displayName()) + 12);
            Button b = Button.builder(sec.displayName(), btn -> {
                this.currentSection = sec;
                this.scrollPx = 0;
            }).bounds(sx, sectionY, bw, UILayoutMetrics.BUTTON_HEIGHT).build();
            this.sectionButtons.add(b);
            this.addRenderableWidget(b);
            sx += bw + UILayoutMetrics.BUTTON_GAP;
        }

        // ─── 縦タブ列 + リスト領域 ───
        int contentTop = sectionY + UILayoutMetrics.BUTTON_HEIGHT + UILayoutMetrics.SECTION_GAP;
        int contentBottom = this.height - UILayoutMetrics.FOOTER_HINT_FROM_BOTTOM - 6;
        this.tabW = UILayoutMetrics.VERTICAL_TAB_WIDTH_MIN;
        this.tabTop = contentTop;
        this.tabBottom = contentBottom;

        int listGap = UILayoutMetrics.VERTICAL_TAB_GAP_X + 4;
        if (rtl) {
            this.tabX = this.width - inset - tabW;
            this.listX = inset;
            this.listRight = this.tabX - listGap;
        } else {
            this.tabX = inset;
            this.listX = inset + tabW + listGap;
            this.listRight = this.width - inset;
        }
        this.listTop = contentTop;
        this.listBottom = contentBottom;

        clampScroll();
    }

    /** タブ一覧を組み立てる: ALL / Favorites / 各 concrete カテゴリ。 */
    private void buildTabs() {
        this.tabs.clear();
        this.tabs.add(new TabEntry(null, false,
                OmniChestLocale.get("omnichest.distribution.tab.all", "All"), Items.COMPASS));
        this.tabs.add(new TabEntry(null, true,
                OmniChestLocale.get("omnichest.distribution.tab.favorites", "Favorites"), Items.NETHER_STAR));
        for (StorageCategory c : StorageCategory.values()) {
            if (c.isConcrete()) {
                this.tabs.add(new TabEntry(c, false, c.displayComponent(), categoryIcon(c)));
            }
        }
        if (this.selectedTab >= this.tabs.size()) {
            this.selectedTab = 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // データ取得 (= セクション別)
    // ════════════════════════════════════════════════════════════════════

    /** STORAGE セクションで表示する登録倉庫 (= タブフィルタ + ソート適用)。 */
    private List<StorageAssignment> filteredAssignments() {
        TabEntry tab = this.tabs.get(this.selectedTab);
        List<StorageAssignment> list;
        if (tab.favorites()) {
            list = StorageAssignmentManager.get().favorites();
        } else if (tab.category() == null) {
            list = new ArrayList<>(StorageAssignmentManager.get().all());
        } else {
            list = StorageAssignmentManager.get().byCategory(tab.category());
        }
        list.sort(Comparator
                .comparing((StorageAssignment a) -> a.favorite() ? 0 : 1)
                .thenComparing(a -> a.category().ordinal())
                .thenComparingInt(StorageAssignment::priority)
                .thenComparing(StorageAssignment::name));
        return list;
    }

    private int rowCount() {
        return switch (this.currentSection) {
            case STORAGE -> filteredAssignments().size();
            case PENDING -> VirtualTransferRegistry.get().all().size();
            case QUEUE -> DistributionQueue.get().remaining();
            case HISTORY -> TransferHistoryManager.get().successes().size();
            case FAILED -> TransferHistoryManager.get().failures().size();
        };
    }

    private int contentHeight() {
        return rowCount() * rowHeight;
    }

    private void clampScroll() {
        double max = Math.max(0, contentHeight() - (listBottom - listTop));
        if (scrollPx < 0) scrollPx = 0;
        if (scrollPx > max) scrollPx = max;
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Font font = this.font;

        // タイトル。
        g.centeredText(font, this.getTitle(), this.width / 2,
                UILayoutMetrics.SCREEN_INSET_TOP, ThemeColorResolver.TEXT_PRIMARY);

        // サマリ (登録数 / 予約数 / キュー数)。
        Component summary = OmniChestLocale.get("omnichest.distribution.summary",
                "Storages: %1$d  /  Pending: %2$d  /  Queue: %3$d",
                StorageAssignmentManager.get().size(),
                VirtualTransferRegistry.get().totalCount(),
                DistributionQueue.get().remaining());
        boolean rtl = RTLLayoutManager.get().isRtl();
        if (rtl) {
            int sw = font.width(summary);
            g.text(font, summary, this.width - UILayoutMetrics.SCREEN_INSET_X - sw,
                    UILayoutMetrics.SCREEN_INSET_TOP, ThemeColorResolver.TEXT_SECONDARY, false);
        } else {
            g.text(font, summary, UILayoutMetrics.SCREEN_INSET_X,
                    UILayoutMetrics.SCREEN_INSET_TOP, ThemeColorResolver.TEXT_SECONDARY, false);
        }

        // アクティブセクションの強調 (= ボタン背後に黄色アンダーライン)。
        for (int i = 0; i < sectionButtons.size(); i++) {
            if (DistributionSection.values()[i] == currentSection) {
                Button b = sectionButtons.get(i);
                g.fill(b.getX(), b.getY() + b.getHeight(), b.getX() + b.getWidth(),
                        b.getY() + b.getHeight() + 2, ThemeColorResolver.TAB_ACTIVE_LINE);
            }
        }

        // タブ列。
        renderTabs(g, mouseX, mouseY);

        // リスト。
        renderList(g, mouseX, mouseY);

        // リスト枠 (= 黄色細枠でテーマ統一)。
        drawListFrame(g);

        // フッターヒント。
        Component hint = OmniChestLocale.get("omnichest.distribution.hint",
                "Open a chest → [Set Category] to register  /  [Auto Distribute] to sort  /  ESC = close");
        g.centeredText(font, hint, this.width / 2,
                this.height - UILayoutMetrics.FOOTER_HINT_FROM_BOTTOM, ThemeColorResolver.TEXT_DIM);
    }

    private void renderTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // パネル背景。
        g.fill(tabX, tabTop, tabX + tabW, tabBottom, ThemeColorResolver.CATEGORY_PANEL_BG);
        g.enableScissor(tabX, tabTop, tabX + tabW, tabBottom);
        try {
            int cell = UILayoutMetrics.TAB_HEIGHT;
            int gap = UILayoutMetrics.TAB_GAP;
            int y = tabTop - (int) tabScrollPx;
            for (int i = 0; i < tabs.size(); i++) {
                TabEntry tab = tabs.get(i);
                boolean selected = (i == selectedTab);
                boolean hover = mouseX >= tabX && mouseX < tabX + tabW
                        && mouseY >= y && mouseY < y + cell;
                int bg = selected ? ThemeColorResolver.TAB_ACTIVE_BG
                        : (hover ? ThemeColorResolver.TAB_HOVER_BG : ThemeColorResolver.TAB_NORMAL_BG);
                g.fill(tabX + 2, y, tabX + tabW - 2, y + cell, bg);
                if (selected) {
                    g.fill(tabX, y, tabX + UILayoutMetrics.TAB_SELECTED_OUTER_LINE, y + cell,
                            ThemeColorResolver.TAB_ACTIVE_LINE);
                }
                // アイコン中央。
                int iconX = tabX + (tabW - 16) / 2;
                int iconY = y + (cell - 16) / 2;
                LargeIconRenderer.extractRenderState(g, new ItemStack(tab.icon()), iconX, iconY, 16, false, this.font);
                y += cell + gap;
            }
        } finally {
            g.disableScissor();
        }

        // ホバー中タブの名前 tooltip。
        int cell = UILayoutMetrics.TAB_HEIGHT;
        int gap = UILayoutMetrics.TAB_GAP;
        int y = tabTop - (int) tabScrollPx;
        for (int i = 0; i < tabs.size(); i++) {
            if (mouseX >= tabX && mouseX < tabX + tabW && mouseY >= y && mouseY < y + cell
                    && mouseY >= tabTop && mouseY < tabBottom) {
                g.setComponentTooltipForNextFrame(this.font, List.of(tabs.get(i).label()), mouseX, mouseY);
                break;
            }
            y += cell + gap;
        }
    }

    private void renderList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(listX, listTop, listRight, listBottom, ThemeColorResolver.LIST_BG);
        g.enableScissor(listX + 1, listTop + 1, listRight - 1, listBottom - 1);
        try {
            switch (currentSection) {
                case STORAGE -> renderStorageList(g, mouseX, mouseY);
                case PENDING -> renderPendingList(g);
                case QUEUE -> renderQueueList(g);
                case HISTORY -> renderHistoryList(g, true);
                case FAILED -> renderHistoryList(g, false);
            }
        } finally {
            g.disableScissor();
        }
        renderScrollbar(g);
    }

    // ─── STORAGE ───
    private void renderStorageList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<StorageAssignment> list = filteredAssignments();
        if (list.isEmpty()) {
            drawEmpty(g, OmniChestLocale.get("omnichest.distribution.empty.storage",
                    "No registered storages. Open a chest and press [Set Category]."));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ResourceKey<Level> dim = mc.player != null ? mc.player.level().dimension() : null;
        Vec3 playerPos = mc.player != null ? mc.player.position() : null;
        boolean rtl = RTLLayoutManager.get().isRtl();

        int y = listTop + 2 - (int) scrollPx;
        for (StorageAssignment a : list) {
            if (y + rowHeight >= listTop && y <= listBottom) {
                boolean hover = mouseY >= y && mouseY < y + rowHeight && mouseX >= listX && mouseX < listRight;
                renderStorageRow(g, a, listX, y, listRight - listX, rowHeight, hover, dim, playerPos, rtl);
            }
            y += rowHeight;
        }
    }

    private void renderStorageRow(GuiGraphicsExtractor g, StorageAssignment a, int x, int y, int w, int h,
            boolean hover, @Nullable ResourceKey<Level> dim, @Nullable Vec3 playerPos, boolean rtl) {
        if (hover) {
            g.fill(x, y, x + w, y + h, ThemeColorResolver.ROW_HOVER_OVERLAY);
        }
        int catColor = 0xFF000000 | a.category().rgb();
        int pad = 4;
        // 左端: カテゴリ色バー。
        int barX = rtl ? (x + w - pad - 3) : (x + pad);
        g.fill(barX, y + 2, barX + 3, y + h - 2, catColor);

        // カテゴリ代表アイコン。
        int iconX = rtl ? (x + w - pad - 3 - 4 - 16) : (x + pad + 3 + 4);
        int iconY = y + (h - 16) / 2;
        LargeIconRenderer.extractRenderState(g, new ItemStack(categoryIcon(a.category())), iconX, iconY, 16, false, this.font);

        int textLeft = rtl ? (x + pad) : (iconX + 16 + 4);
        int line1Y = y + 3;
        int line2Y = y + 3 + this.font.lineHeight;

        // 名前 (+ ★)。
        String name = a.name();
        Component nameC = a.favorite() ? Component.literal("★ " + name) : Component.literal(name);
        g.text(this.font, nameC, textLeft, line1Y, ThemeColorResolver.TEXT_PRIMARY, false);

        // 2 行目: カテゴリ • P{priority} • used/total • distance。
        StringBuilder sub = new StringBuilder();
        sub.append(a.category().displayComponent().getString());
        sub.append("  •  P").append(a.priority());
        if (a.knownTotalSlots() > 0) {
            sub.append("  •  ").append(a.knownUsedSlots()).append('/').append(a.knownTotalSlots());
        }
        if (dim != null && playerPos != null && dim.equals(a.key().dimension())) {
            BlockPos p = a.key().pos();
            double dx = (p.getX() + 0.5) - playerPos.x;
            double dy = (p.getY() + 0.5) - playerPos.y;
            double dz = (p.getZ() + 0.5) - playerPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            sub.append("  •  ").append(String.format(Locale.ROOT, "%.0fm", dist));
        }
        g.text(this.font, sub.toString(), textLeft, line2Y, ThemeColorResolver.TEXT_SECONDARY, false);

        // 行区切り線 (= 反復原則)。
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, ThemeColorResolver.ROW_SEPARATOR);
    }

    // ─── PENDING ───
    private void renderPendingList(GuiGraphicsExtractor g) {
        List<PendingTransfer> list = VirtualTransferRegistry.get().all();
        if (list.isEmpty()) {
            drawEmpty(g, OmniChestLocale.get("omnichest.distribution.empty.pending",
                    "No pending transfers. Press [Auto Distribute] near a chest."));
            return;
        }
        list.sort(Comparator.comparingLong(PendingTransfer::createdMillis).reversed());
        DistributionConfig cfg = ConfigManager.get().distribution;
        boolean rtl = RTLLayoutManager.get().isRtl();
        int y = listTop + 2 - (int) scrollPx;
        for (PendingTransfer p : list) {
            if (y + rowHeight >= listTop && y <= listBottom) {
                String to = targetName(p);
                TransferVisualizationRenderer.renderRow(g, this.font, listX, y, listRight - listX, rowHeight,
                        p.representative(), p.count(), p.sourceLabel(), to, p.category(),
                        p.createdMillis(), true, cfg.showTransferAnimation, animSpeed(cfg), rtl);
            }
            y += rowHeight;
        }
    }

    // ─── QUEUE ───
    private void renderQueueList(GuiGraphicsExtractor g) {
        List<DistributionQueue.MoveOp> ops = DistributionQueue.get().snapshot();
        if (ops.isEmpty()) {
            drawEmpty(g, OmniChestLocale.get("omnichest.distribution.empty.queue",
                    "Queue is idle."));
            return;
        }
        DistributionConfig cfg = ConfigManager.get().distribution;
        boolean rtl = RTLLayoutManager.get().isRtl();
        String here = OmniChestLocale.getString("omnichest.distribution.this_chest", "This Chest");
        int y = listTop + 2 - (int) scrollPx;
        for (DistributionQueue.MoveOp op : ops) {
            if (op.expected() == null || op.expected().isEmpty()) {
                continue;
            }
            if (y + rowHeight >= listTop && y <= listBottom) {
                TransferVisualizationRenderer.renderRow(g, this.font, listX, y, listRight - listX, rowHeight,
                        op.expected(), op.expected().getCount(), "", here,
                        com.kajiwara.omnichest.distribution.CategoryMapper.toStorageCategory(op.expected()),
                        0, true, cfg.showTransferAnimation, animSpeed(cfg), rtl);
            }
            y += rowHeight;
        }
    }

    // ─── HISTORY / FAILED ───
    private void renderHistoryList(GuiGraphicsExtractor g, boolean successOnly) {
        List<TransferRecord> list = successOnly
                ? TransferHistoryManager.get().successes()
                : TransferHistoryManager.get().failures();
        if (list.isEmpty()) {
            drawEmpty(g, successOnly
                    ? OmniChestLocale.get("omnichest.distribution.empty.history", "No transfer history yet.")
                    : OmniChestLocale.get("omnichest.distribution.empty.failed", "No failed transfers."));
            return;
        }
        DistributionConfig cfg = ConfigManager.get().distribution;
        boolean rtl = RTLLayoutManager.get().isRtl();
        int y = listTop + 2 - (int) scrollPx;
        for (TransferRecord r : list) {
            if (y + rowHeight >= listTop && y <= listBottom) {
                TransferVisualizationRenderer.renderRow(g, this.font, listX, y, listRight - listX, rowHeight,
                        r.representative(), r.count(), r.fromLabel(), r.toLabel(), r.category(),
                        r.timeMillis(), r.success(), false, 0, rtl);
            }
            y += rowHeight;
        }
    }

    private void drawEmpty(GuiGraphicsExtractor g, Component msg) {
        int tw = this.font.width(msg);
        g.text(this.font, msg, (listX + listRight) / 2 - tw / 2,
                (listTop + listBottom) / 2 - this.font.lineHeight / 2,
                ThemeColorResolver.TEXT_SECONDARY, false);
    }

    private void drawListFrame(GuiGraphicsExtractor g) {
        int frame = ThemeColorResolver.TAB_ACTIVE_LINE;
        int lt = UILayoutMetrics.LIST_FRAME_THICKNESS;
        g.fill(listX, listTop, listRight, listTop + lt, frame);
        g.fill(listX, listBottom - lt, listRight, listBottom, frame);
        g.fill(listX, listTop, listX + lt, listBottom, frame);
        g.fill(listRight - lt, listTop, listRight, listBottom, frame);
    }

    private void renderScrollbar(GuiGraphicsExtractor g) {
        int viewH = listBottom - listTop;
        int contentH = contentHeight();
        if (contentH <= viewH) {
            return;
        }
        int barX = listRight - UILayoutMetrics.SCROLLBAR_WIDTH - 1;
        int thumbH = Math.max(20, (int) ((long) viewH * viewH / contentH));
        int maxScroll = contentH - viewH;
        int thumbY = listTop + (int) ((scrollPx / maxScroll) * (viewH - thumbH));
        g.fill(barX, listTop, barX + UILayoutMetrics.SCROLLBAR_WIDTH, listBottom, ThemeColorResolver.SCROLLBAR_TRACK);
        g.fill(barX, thumbY, barX + UILayoutMetrics.SCROLLBAR_WIDTH, thumbY + thumbH,
                draggingScroll ? ThemeColorResolver.SCROLLBAR_THUMB_DRAG : ThemeColorResolver.SCROLLBAR_THUMB);
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        // タブクリック。
        if (event.button() == 0 && mx >= tabX && mx < tabX + tabW && my >= tabTop && my < tabBottom) {
            int cell = UILayoutMetrics.TAB_HEIGHT;
            int gap = UILayoutMetrics.TAB_GAP;
            int y = tabTop - (int) tabScrollPx;
            for (int i = 0; i < tabs.size(); i++) {
                if (my >= y && my < y + cell) {
                    this.selectedTab = i;
                    this.currentSection = DistributionSection.STORAGE;
                    this.scrollPx = 0;
                    return true;
                }
                y += cell + gap;
            }
        }

        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

        // スクロールバードラッグ開始。
        if (event.button() == 0 && isOverScrollbar(mx, my)) {
            this.draggingScroll = true;
            this.scrollDragOffsetY = 0;
            setScrollFromMouse(my);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScroll) {
            setScrollFromMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            draggingScroll = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        // タブ列上ならタブをスクロール。
        if (mouseX >= tabX && mouseX < tabX + tabW && mouseY >= tabTop && mouseY < tabBottom) {
            tabScrollPx -= dy * (UILayoutMetrics.TAB_HEIGHT + UILayoutMetrics.TAB_GAP);
            clampTabScroll();
            return true;
        }
        scrollPx -= dy * rowHeight * 2;
        clampScroll();
        return true;
    }

    private void clampTabScroll() {
        int cell = UILayoutMetrics.TAB_HEIGHT + UILayoutMetrics.TAB_GAP;
        int contentH = tabs.size() * cell;
        int viewH = tabBottom - tabTop;
        double max = Math.max(0, contentH - viewH);
        if (tabScrollPx < 0) tabScrollPx = 0;
        if (tabScrollPx > max) tabScrollPx = max;
    }

    private boolean isOverScrollbar(double mx, double my) {
        int viewH = listBottom - listTop;
        if (contentHeight() <= viewH) {
            return false;
        }
        int barX = listRight - UILayoutMetrics.SCROLLBAR_WIDTH - 1;
        return mx >= barX - UILayoutMetrics.SCROLLBAR_HIT_MARGIN
                && mx <= barX + UILayoutMetrics.SCROLLBAR_WIDTH + UILayoutMetrics.SCROLLBAR_HIT_MARGIN
                && my >= listTop && my <= listBottom;
    }

    private void setScrollFromMouse(double my) {
        int viewH = listBottom - listTop;
        int contentH = contentHeight();
        if (contentH <= viewH) {
            return;
        }
        int thumbH = Math.max(20, (int) ((long) viewH * viewH / contentH));
        double frac = (my - listTop - thumbH / 2.0) / (viewH - thumbH);
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        scrollPx = frac * (contentH - viewH);
        clampScroll();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        //? if >=26.2 {
        /*mc.setScreenAndShow(parent);*/
        //?} else {
        mc.setScreen(parent);
        //?}
    }

    // ════════════════════════════════════════════════════════════════════
    // ヘルパ
    // ════════════════════════════════════════════════════════════════════

    private static String targetName(PendingTransfer p) {
        StorageAssignment a = StorageAssignmentManager.get().get(p.target());
        if (a != null) {
            return a.name();
        }
        BlockPos pos = p.target().pos();
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    private static double animSpeed(DistributionConfig cfg) {
        if (!cfg.showTransferAnimation) {
            return 0;
        }
        // GUI 全般のアニメーション スイッチ (= RenderConfig.guiAnimation) を尊重する。
        // OFF なら速度 0 (= アニメ無し / 即時最終状態) として扱う。
        if (!ConfigManager.get().render.guiAnimation) {
            return 0;
        }
        double s = ConfigManager.get().general.animationSpeed;
        return s <= 0 ? 0 : s;
    }

    /** カテゴリ代表アイコン (= 視覚分類)。 検索 UI のアイコン規約と整合。 */
    private static Item categoryIcon(StorageCategory c) {
        return switch (c) {
            case BUILDING -> Items.BRICKS;
            case WOOD -> Items.OAK_LOG;
            case ORE -> Items.DIAMOND_ORE;
            case REDSTONE -> Items.REDSTONE;
            case FOOD -> Items.BREAD;
            case FARM -> Items.WHEAT;
            case COMBAT -> Items.IRON_SWORD;
            case TOOL -> Items.IRON_PICKAXE;
            case POTION -> Items.POTION;
            case NETHER -> Items.NETHERRACK;
            case END -> Items.END_STONE;
            case MAGIC -> Items.ENCHANTED_BOOK;
            case MOB_DROP -> Items.BONE;
            case DECORATION -> Items.PAINTING;
            case MIXED -> Items.BUNDLE;
            case UNKNOWN -> Items.BARRIER;
        };
    }

    /** タブ 1 個ぶんの定義。 category=null かつ favorites=false なら ALL。 */
    private record TabEntry(@Nullable StorageCategory category, boolean favorites, Component label, Item icon) {
    }
}
