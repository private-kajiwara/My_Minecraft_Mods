package com.kajiwara.omnichest.template.gui;

import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.client.gui.search.layout.UILayoutMetrics;
import com.kajiwara.omnichest.client.gui.search.preview.AdaptiveTooltipPositioner;
import com.kajiwara.omnichest.client.gui.search.preview.AltPreviewPopupRenderer;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.config.gui.widget.NavyFooterButton;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.i18n.RTLLayoutManager;
import com.kajiwara.omnichest.template.TemplateManager;
import com.kajiwara.omnichest.template.data.ChestTemplate;
import com.kajiwara.omnichest.template.data.SlotRule;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 「テンプレート一覧」管理 GUI。
 *
 * <p>
 * <b>デザイン方針</b>: 倉庫検索 ({@code SearchScreen}) と <b>同じトーン / 同じメニュー構造</b> に
 * 統一する。 色は {@link ThemeColorResolver}、 寸法は {@link UILayoutMetrics} を再利用し、 別画面と
 * リズム (= padding / 高さ / フレーム / スクロールバー / フッター) を共有する (= マジックナンバの分散防止)。
 *
 * <p>
 * <b>レイアウト</b> (倉庫検索のゾーン構成を踏襲):
 * <pre>
 *   +-------------------------------------------------+
 *   | title (left)                  summary (right)   |  ← Header
 *   +-------------------------------------------------+
 *   | [現チェストを保存]  [ Filter box (wide) ] [閉じる] |  ← 単一コントロール行
 *   +-------------------------------------------------+
 *   | ┌─────────────────────────────────────────┐|║|  |
 *   | │ ◇ 名前       [種別]                       │|║|  |
 *   | │   54 スロット / 32 ルール   [適用][複製][削除][↑][↓]│  ← 黄枠リスト + 細スクロールバー
 *   | └─────────────────────────────────────────┘|║|  |
 *   +-------------------------------------------------+
 *   |             footer hint (pill, dim)             |  ← Footer
 *   +-------------------------------------------------+
 * </pre>
 *
 * <p>
 * <b>4 原則</b>:
 * <ul>
 *   <li><b>近接</b>: 1 行内で「アイコン + 名前 + 種別 + 件数」を密に、 操作ボタン群を右側に
 *       まとめる。 ヘッダ / コントロール / リスト / フッターは {@link UILayoutMetrics#SECTION_GAP}
 *       で分離。</li>
 *   <li><b>整列</b>: 全座標を {@link UILayoutMetrics} 基準に揃え、 コントロールは同一ベースライン
 *       (= {@link UILayoutMetrics#BUTTON_HEIGHT})。 Filter box が主役として最大幅を吸収。</li>
 *   <li><b>反復</b>: 黄枠 ({@link ThemeColorResolver#TAB_ACTIVE_LINE})・4px スクロールバー・
 *       ピル状フッターを倉庫検索と完全共有。</li>
 *   <li><b>コントラスト</b>: テキスト階層 (PRIMARY / SECONDARY / DIM)、 操作は紺地 + 金枠で
 *       ホバー反転 (= {@code NavyFooterButton} と同トーン)。</li>
 * </ul>
 *
 * <p>
 * 「適用」 は menu / containerSlotCount が分かっているときのみ有効。 ホットキーやコマンドから直接
 * 開いた場合は menu=null となり、 一覧確認 / 編集はできるが apply は不可。
 *
 * <p>
 * <b>RTL</b>: 倉庫検索同様、 全要素を水平ミラー配置する。
 */
public class TemplateManagerScreen extends Screen {

    /** 1 行の高さ。 2 行テキスト (名前 + 件数) + 16px ボタンが収まる GRID 倍数 (= 24)。 */
    private static final int ROW_HEIGHT = 24;
    /** 行内ボタンの高さ。 */
    private static final int ROW_BTN_HEIGHT = 16;

    /** 行アクションをバニラ標準ボタンの見た目で描くための使い回しウィジェット (画面には追加しない)。 */
    private final Button rowBtnRenderer =
            Button.builder(Component.empty(), b -> {}).bounds(0, 0, 0, ROW_BTN_HEIGHT).build();

    private final Screen parent;
    @Nullable
    private final AbstractContainerMenu menu;
    private final int containerSlotCount;

    private boolean rtl;
    private EditBox searchBox;
    private List<ChestTemplate> visible = new ArrayList<>();
    private double scrollPx = 0.0;

    public TemplateManagerScreen(Screen parent,
            @Nullable AbstractContainerMenu menu, int containerSlotCount) {
        super(OmniChestLocale.get(Keys.SCREEN_TEMPLATE_MANAGER_TITLE, "Templates"));
        this.parent = parent;
        this.menu = menu;
        this.containerSlotCount = containerSlotCount;
    }

    public static void open(Screen parent, @Nullable AbstractContainerMenu menu, int containerSlotCount) {
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(new TemplateManagerScreen(parent, menu, containerSlotCount));*/
        //?} else {
        Minecraft.getInstance().setScreen(new TemplateManagerScreen(parent, menu, containerSlotCount));
        //?}
    }

    private boolean canApply() {
        return this.menu != null && this.containerSlotCount > 0;
    }

    // ════════════════════════════════════════════════════════════════════
    // レイアウト (倉庫検索のゾーン計算を踏襲)
    // ════════════════════════════════════════════════════════════════════

    private int contentLeft() {
        return UILayoutMetrics.SCREEN_INSET_X;
    }

    private int contentRight() {
        return this.width - UILayoutMetrics.SCREEN_INSET_X;
    }

    private int headerBottom() {
        return UILayoutMetrics.SCREEN_INSET_TOP + this.font.lineHeight;
    }

    private int controlRowY() {
        return headerBottom() + UILayoutMetrics.SECTION_GAP;
    }

    private int listTop() {
        return controlRowY() + UILayoutMetrics.BUTTON_HEIGHT + UILayoutMetrics.SECTION_GAP;
    }

    private int footerY() {
        return this.height - UILayoutMetrics.FOOTER_HINT_FROM_BOTTOM;
    }

    private int listBottom() {
        return footerY() - UILayoutMetrics.SECTION_GAP;
    }

    private int listHeight() {
        return listBottom() - listTop();
    }

    private int contentHeight() {
        return this.visible.size() * ROW_HEIGHT;
    }

    /** スクロールバーのトラック x (内容がはみ出すときだけ描画)。 LTR=右端 / RTL=左端。 */
    private int scrollbarX() {
        return this.rtl
                ? contentLeft()
                : contentRight() - UILayoutMetrics.SCROLLBAR_WIDTH;
    }

    /** 行の内容領域左端 (アイコン側 padding を含む)。 RTL ではボタン側になる。 */
    private int rowContentLeft() {
        int base = contentLeft() + UILayoutMetrics.LIST_CONTENT_PAD_X;
        // スクロールバーは LTR では右、 RTL では左。 RTL のときだけ左に逃がす。
        return this.rtl ? base + UILayoutMetrics.CONTENT_RIGHT_PAD_FROM_SCROLLBAR : base;
    }

    /** 行の内容領域右端。 LTR ではスクロールバーぶん控える。 */
    private int rowContentRight() {
        int base = contentRight() - UILayoutMetrics.LIST_CONTENT_PAD_X;
        return this.rtl ? base : base - UILayoutMetrics.CONTENT_RIGHT_PAD_FROM_SCROLLBAR;
    }

    // ════════════════════════════════════════════════════════════════════
    // 初期化
    // ════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        super.init();
        this.rtl = RTLLayoutManager.get().isRtl();

        int rowY = controlRowY();
        int btnH = UILayoutMetrics.BUTTON_HEIGHT;
        int gap = UILayoutMetrics.SECTION_GAP;

        // CTA (= 現チェストを保存): menu が分かっているときのみ。 倉庫検索の Find Selected と同じ
        // 「左アンカー (RTL では右) の主役 CTA」 ポジション。
        Component ctaLabel = OmniChestLocale.get(Keys.BUTTON_SAVE_CURRENT_CHEST, "Save Current Chest");
        Component closeLabel = OmniChestLocale.get(Keys.BUTTON_CLOSE, "Close");
        int ctaW = canApply() ? fitWidth(ctaLabel) : 0;
        int closeW = fitWidth(closeLabel);

        // searchBox は中央で余剰幅を全て吸収 (= 主役)。
        int left = contentLeft();
        int right = contentRight();
        int sbLeft;
        int sbW;
        if (this.rtl) {
            // RTL: [閉じる] (左) ... [Filter] ... [CTA] (右)
            Button close = Button.builder(closeLabel, b -> this.onClose())
                    .bounds(left, rowY, closeW, btnH).build();
            this.addRenderableWidget(close);
            int ctaX = right - ctaW;
            if (canApply()) {
                this.addRenderableWidget(
                        new NavyFooterButton(ctaX, rowY, ctaW, btnH, ctaLabel, b -> openSave()));
            }
            sbLeft = close.getX() + closeW + gap;
            int sbRight = canApply() ? ctaX - gap : right;
            sbW = Math.max(40, sbRight - sbLeft);
        } else {
            // LTR: [CTA] (左) ... [Filter] ... [閉じる] (右)
            int ctaX = left;
            if (canApply()) {
                this.addRenderableWidget(
                        new NavyFooterButton(ctaX, rowY, ctaW, btnH, ctaLabel, b -> openSave()));
            }
            int closeX = right - closeW;
            Button close = Button.builder(closeLabel, b -> this.onClose())
                    .bounds(closeX, rowY, closeW, btnH).build();
            this.addRenderableWidget(close);
            sbLeft = canApply() ? ctaX + ctaW + gap : left;
            sbW = Math.max(40, closeX - gap - sbLeft);
        }

        this.searchBox = new EditBox(this.font, sbLeft, rowY, sbW, UILayoutMetrics.EDITBOX_HEIGHT,
                OmniChestLocale.get(Keys.EDITBOX_SEARCH_LABEL, "Search"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setHint(OmniChestLocale.get(
                Keys.EDITBOX_SEARCH_HINT_TEMPLATE, "Filter by template name"));
        this.searchBox.setResponder(text -> rebuildList());
        this.addRenderableWidget(this.searchBox);

        rebuildList();
    }

    /** 翻訳済みラベルにフィットするボタン幅 (= 倉庫検索の fitWidth と同じ「実寸 + 余白 + GRID snap」)。 */
    private int fitWidth(Component label) {
        return UILayoutMetrics.snap(this.font.width(label) + 12);
    }

    private void openSave() {
        if (canApply()) {
            //? if >=26.2 {
            /*Minecraft.getInstance().setScreenAndShow(
                    new TemplateSaveScreen(this, this.menu, this.containerSlotCount));*/
            //?} else {
            Minecraft.getInstance().setScreen(
                    new TemplateSaveScreen(this, this.menu, this.containerSlotCount));
            //?}
        }
    }

    private void rebuildList() {
        String q = this.searchBox == null ? "" : this.searchBox.getValue();
        this.visible = TemplateManager.search(q);
        clampScroll();
    }

    private void clampScroll() {
        double maxScroll = Math.max(0, contentHeight() - listHeight());
        if (this.scrollPx < 0)
            this.scrollPx = 0;
        if (this.scrollPx > maxScroll)
            this.scrollPx = maxScroll;
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        renderHeader(g);
        renderListFrame(g);
        renderList(g, mouseX, mouseY, partialTick);
        renderScrollbar(g);
        renderFooterHint(g);

        // ALT ホバー: テンプレートの中身プレビュー (= 倉庫検索の ALT プレビューと同じトーン)。
        // ルールが 0 件 (= 中身が空) でも、 空グリッドのパネルを必ず表示する。
        if (isAltDown()) {
            ChestTemplate hovered = hoveredTemplate(mouseX, mouseY);
            if (hovered != null) {
                renderTemplatePreview(g, hovered, mouseX, mouseY);
            }
        }
    }

    private void renderHeader(GuiGraphicsExtractor g) {
        Font font = this.font;
        int left = contentLeft();
        int right = contentRight();
        int y = UILayoutMetrics.SCREEN_INSET_TOP;

        Component title = this.getTitle().copy().withStyle(net.minecraft.ChatFormatting.BOLD);
        Component summary = OmniChestLocale.get(Keys.TEMPLATE_MANAGER_SUMMARY,
                "Saved: %1$d  /  Shown: %2$d",
                TemplateManager.list().size(), this.visible.size());
        int sumW = font.width(summary);
        if (this.rtl) {
            g.text(font, title, right - font.width(title), y, ThemeColorResolver.TEXT_PRIMARY, true);
            g.text(font, summary, left, y, ThemeColorResolver.TEXT_SECONDARY, false);
        } else {
            g.text(font, title, left, y, ThemeColorResolver.TEXT_PRIMARY, true);
            g.text(font, summary, right - sumW, y, ThemeColorResolver.TEXT_SECONDARY, false);
        }
    }

    private void renderListFrame(GuiGraphicsExtractor g) {
        int x0 = contentLeft();
        int y0 = listTop();
        int x1 = contentRight();
        int y1 = listBottom();
        g.fill(x0, y0, x1, y1, ThemeColorResolver.LIST_BG);
        g.outline(x0, y0, x1 - x0, y1 - y0, ThemeColorResolver.TAB_ACTIVE_LINE);
    }

    private void renderList(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int top = listTop();
        int bottom = listBottom();
        int left = contentLeft();
        int right = contentRight();

        g.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        try {
            int firstVisible = (int) Math.floor(this.scrollPx / ROW_HEIGHT);
            int lastVisible = firstVisible + (listHeight() / ROW_HEIGHT) + 2;
            lastVisible = Math.min(lastVisible, this.visible.size());

            for (int i = Math.max(0, firstVisible); i < lastVisible; i++) {
                int rowY = top + UILayoutMetrics.LIST_CONTENT_PAD_Y + (i * ROW_HEIGHT) - (int) this.scrollPx;
                if (rowY + ROW_HEIGHT < top || rowY > bottom)
                    continue;
                ChestTemplate t = this.visible.get(i);
                renderRow(g, t, rowY, mouseX, mouseY, partialTick);
            }
        } finally {
            g.disableScissor();
        }
    }

    private void renderRow(GuiGraphicsExtractor g, ChestTemplate t, int y, int mouseX, int mouseY, float partialTick) {
        Font font = this.font;
        int left = contentLeft();
        int right = contentRight();

        // 行ホバー (= 操作ボタンの上を除く本文領域)。
        boolean rowHover = mouseX >= left && mouseX <= right && mouseY >= y && mouseY < y + ROW_HEIGHT;
        if (rowHover) {
            g.fill(left + 1, y, right - 1, y + ROW_HEIGHT, ThemeColorResolver.ROW_HOVER_OVERLAY);
        }
        // 行間の薄い区切り。
        g.fill(left + 1, y + ROW_HEIGHT - 1, right - 1, y + ROW_HEIGHT, ThemeColorResolver.ROW_SEPARATOR);

        // ─── アクションボタン (先に確保して本文の使える幅を決める) ───
        List<BtnRect> btns = layoutRowButtons(t, y);
        for (BtnRect b : btns) {
            drawVanillaRowButton(g, b, mouseX, mouseY, partialTick);
        }
        // ボタン群の内側端 (本文が侵入してよい限界)。 リストは常に左→右順 ([適用..↓])。
        // LTR: ボタン群は右側 → 本文の右限界は最左ボタン (適用) の左。
        // RTL: ボタン群は左側 → 本文の左限界は最右ボタン (↓) の右。
        int btnInnerEdge = btns.isEmpty()
                ? (this.rtl ? rowContentLeft() : rowContentRight())
                : (this.rtl ? btns.get(btns.size() - 1).x1 + UILayoutMetrics.BUTTON_GAP
                            : btns.get(0).x0 - UILayoutMetrics.BUTTON_GAP);

        // ─── アイコン + テキスト ───
        ItemStack icon = t.iconStack();
        int iconY = y + (ROW_HEIGHT - 16) / 2;
        int textLeft;
        int textRight;
        if (this.rtl) {
            int iconX = rowContentRight() - 16;
            g.item(icon, iconX, iconY);
            textRight = iconX - UILayoutMetrics.BUTTON_GAP;
            textLeft = btnInnerEdge;
        } else {
            int iconX = rowContentLeft();
            g.item(icon, iconX, iconY);
            textLeft = iconX + 16 + UILayoutMetrics.BUTTON_GAP;
            textRight = btnInnerEdge;
        }
        int textW = Math.max(0, textRight - textLeft);

        Component kindBadge = switch (t.kind()) {
            case EXACT -> OmniChestLocale.get(Keys.TEMPLATE_KIND_BADGE_EXACT, "[Exact]");
            case CATEGORY -> OmniChestLocale.get(Keys.TEMPLATE_KIND_BADGE_CATEGORY, "[Category]");
            case HYBRID -> OmniChestLocale.get(Keys.TEMPLATE_KIND_BADGE_HYBRID, "[Hybrid]");
        };
        int badgeW = font.width(kindBadge);
        // 1 行目: 名前 (主) + 種別バッジ (副)。 名前はバッジを除いた幅で省略。
        int nameMaxW = Math.max(0, textW - badgeW - UILayoutMetrics.BUTTON_GAP);
        Component name = truncate(t.name(), nameMaxW);
        Component count = OmniChestLocale.get(Keys.TEMPLATE_MANAGER_SLOT_RULE_COUNT,
                "%1$d slots / %2$d rules", t.containerSize(), t.slotRules().size());

        int line1Y = y + 3;
        int line2Y = y + 3 + font.lineHeight;
        if (this.rtl) {
            int nameW = font.width(name);
            g.text(font, name, textRight - nameW, line1Y, ThemeColorResolver.TEXT_PRIMARY, false);
            g.text(font, kindBadge, textRight - nameW - UILayoutMetrics.BUTTON_GAP - badgeW,
                    line1Y, ThemeColorResolver.TEXT_SECONDARY, false);
            g.text(font, count, textRight - font.width(count), line2Y,
                    ThemeColorResolver.TEXT_SECONDARY, false);
        } else {
            g.text(font, name, textLeft, line1Y, ThemeColorResolver.TEXT_PRIMARY, false);
            g.text(font, kindBadge, textLeft + font.width(name) + UILayoutMetrics.BUTTON_GAP,
                    line1Y, ThemeColorResolver.TEXT_SECONDARY, false);
            g.text(font, count, textLeft, line2Y, ThemeColorResolver.TEXT_SECONDARY, false);
        }
    }

    /** 行アクションをバニラ標準ボタンの見た目で描く (ホバー / 押下スプライト込み)。 高さは builder 固定。 */
    private void drawVanillaRowButton(GuiGraphicsExtractor g, BtnRect b, int mouseX, int mouseY, float partialTick) {
        this.rowBtnRenderer.setMessage(b.action.label());
        this.rowBtnRenderer.setX(b.x0);
        this.rowBtnRenderer.setY(b.y0);
        this.rowBtnRenderer.setWidth(b.x1 - b.x0);
        this.rowBtnRenderer.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void renderScrollbar(GuiGraphicsExtractor g) {
        int contentH = contentHeight();
        int viewH = listHeight();
        if (contentH <= viewH)
            return;
        int trackX = scrollbarX();
        int trackTop = listTop() + 1;
        int trackBottom = listBottom() - 1;
        int trackH = trackBottom - trackTop;
        g.fill(trackX, trackTop, trackX + UILayoutMetrics.SCROLLBAR_WIDTH, trackBottom,
                ThemeColorResolver.SCROLLBAR_TRACK);
        double ratio = (double) viewH / contentH;
        int thumbH = Math.max(16, (int) (trackH * ratio));
        double maxScroll = contentH - viewH;
        double tt = maxScroll <= 0 ? 0 : this.scrollPx / maxScroll;
        int thumbY = trackTop + (int) ((trackH - thumbH) * tt);
        g.fill(trackX, thumbY, trackX + UILayoutMetrics.SCROLLBAR_WIDTH, thumbY + thumbH,
                ThemeColorResolver.SCROLLBAR_THUMB);
    }

    private void renderFooterHint(GuiGraphicsExtractor g) {
        Font font = this.font;
        Component hint = OmniChestLocale.get(Keys.TEMPLATE_MANAGER_HINT,
                "Click row = Apply / Alt = Template details / ESC = close");
        int w = font.width(hint);
        int y = footerY();
        int cx = (this.width - w) / 2;
        // ピル状 backdrop (= 倉庫検索と同じトーン)。
        int pad = 6;
        g.fill(cx - pad, y - 3, cx + w + pad, y + font.lineHeight - 1 + 2, ThemeColorResolver.FOOTER_BACKDROP);
        g.text(font, hint, cx, y, ThemeColorResolver.TEXT_DIM, false);
    }

    /** ALT が押されているか (= 倉庫検索の同名ヘルパと同じ {@link InputConstants} 経由判定)。 */
    private boolean isAltDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RALT);
    }

    /** カーソル下のテンプレート行を返す (リスト範囲外 / 行なしは null)。 mouseClicked と同じ座標計算。 */
    @Nullable
    private ChestTemplate hoveredTemplate(int mouseX, int mouseY) {
        int top = listTop();
        int bottom = listBottom();
        int left = contentLeft();
        int right = contentRight();
        if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom)
            return null;
        int rel = (int) (mouseY - (top + UILayoutMetrics.LIST_CONTENT_PAD_Y) + this.scrollPx);
        if (rel < 0)
            return null;
        int index = rel / ROW_HEIGHT;
        if (index < 0 || index >= this.visible.size())
            return null;
        return this.visible.get(index);
    }

    /**
     * ALT ホバー時に、 テンプレートの中身をカーソル近くにポップアップ表示する。
     *
     * <p>
     * コンテナのスロット数ぶんのグリッドを描き、 各スロットに該当する {@link SlotRule} があれば
     * アイテム + 個数を描く。 ルールが 0 件 (= 中身が空) でも、 空グリッドをパネルとして必ず表示する。
     * 配置 / クランプ規則は倉庫検索の ALT プレビューと統一する。
     */
    private void renderTemplatePreview(GuiGraphicsExtractor g, ChestTemplate t, int mouseX, int mouseY) {
        // 倉庫検索の ALT プレビュー (シュルカー) と <b>同じ描画パス</b> を使う:
        // AltPreviewPopupRenderer.renderSlots に「テンプレートのスロット内容」 を渡すだけ。
        // 列数 / 背景 dim はユーザの倉庫検索設定 (previewGridColumns / previewBackgroundBlur) を流用。
        var cfg = ConfigManager.get().search;
        int size = Math.max(1, t.containerSize());
        int columns = AltPreviewPopupRenderer.clampColumns(cfg.previewGridColumns);

        // スロット内容を ItemStack リストに展開 (空スロットは EMPTY)。 中身が空でも空グリッドを出す。
        List<ItemStack> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(ItemStack.EMPTY);
        }
        for (SlotRule r : t.slotRules()) {
            int idx = r.slotIndex();
            if (idx >= 0 && idx < size && !r.optionalEmpty()) {
                ItemStack icon = r.preferredItem().iconStack();
                if (!icon.isEmpty()) {
                    slots.set(idx, icon);
                }
            }
        }

        Component title = Component.literal(t.name());
        int w = AltPreviewPopupRenderer.panelWidth(columns);
        int h = AltPreviewPopupRenderer.panelHeight(columns, size);
        int[] xy = AdaptiveTooltipPositioner.place(mouseX, mouseY, w, h, this.width, this.height);
        AltPreviewPopupRenderer.renderSlots(g, this.font, title, slots, size,
                xy[0], xy[1], columns, cfg.previewBackgroundBlur, t);
    }

    private Component truncate(String text, int maxW) {
        if (this.font.width(text) <= maxW)
            return Component.literal(text);
        String ell = "…";
        int ellW = this.font.width(ell);
        StringBuilder sb = new StringBuilder();
        int acc = 0;
        for (int i = 0; i < text.length(); i++) {
            int cw = this.font.width(String.valueOf(text.charAt(i)));
            if (acc + cw + ellW > maxW)
                break;
            sb.append(text.charAt(i));
            acc += cw;
        }
        return Component.literal(sb.append(ell).toString());
    }

    // ════════════════════════════════════════════════════════════════════
    // 行アクション
    // ════════════════════════════════════════════════════════════════════

    private enum RowAction {
        APPLY(Keys.TEMPLATE_ROW_ACTION_APPLY, "Apply"),
        DUPLICATE(Keys.TEMPLATE_ROW_ACTION_DUPLICATE, "Duplicate"),
        DELETE(Keys.TEMPLATE_ROW_ACTION_DELETE, "Delete"),
        UP(Keys.TEMPLATE_ROW_ACTION_UP, "↑"),
        DOWN(Keys.TEMPLATE_ROW_ACTION_DOWN, "↓");

        private final String key;
        private final String fallback;

        RowAction(String key, String fallback) {
            this.key = key;
            this.fallback = fallback;
        }

        Component label() {
            return OmniChestLocale.get(this.key, this.fallback);
        }

        boolean isArrow() {
            return this == UP || this == DOWN;
        }
    }

    private RowAction[] actionsFor() {
        List<RowAction> a = new ArrayList<>(5);
        if (canApply())
            a.add(RowAction.APPLY);
        a.add(RowAction.DUPLICATE);
        a.add(RowAction.DELETE);
        // 並べ替えはフィルタ中だと storage の全件順とズレるため、 検索が空のときだけ提供する。
        if (this.searchBox != null && this.searchBox.getValue().isEmpty()) {
            a.add(RowAction.UP);
            a.add(RowAction.DOWN);
        }
        return a.toArray(new RowAction[0]);
    }

    private int btnWidth(RowAction a) {
        if (a.isArrow())
            return 14;
        return this.font.width(a.label()) + 8;
    }

    /** 行のボタン矩形を右 (RTL では左) アンカーで並べる。 順序は [適用 複製 削除 ↑ ↓]。 */
    private List<BtnRect> layoutRowButtons(ChestTemplate t, int rowY) {
        RowAction[] actions = actionsFor();
        int btnY = rowY + (ROW_HEIGHT - ROW_BTN_HEIGHT) / 2;
        int gap = UILayoutMetrics.BUTTON_GAP;
        List<BtnRect> out = new ArrayList<>(actions.length);
        if (this.rtl) {
            // RTL: 左端からアイテム順に右へ並べる (= 視覚的に LTR の鏡像)。
            int x = rowContentLeft();
            for (RowAction a : actions) {
                int w = btnWidth(a);
                out.add(new BtnRect(a, x, btnY, x + w, btnY + ROW_BTN_HEIGHT));
                x += w + gap;
            }
        } else {
            // LTR: 右端から逆順に左へ詰めることで「適用」 をテキスト寄りに置く。
            int x = rowContentRight();
            for (int i = actions.length - 1; i >= 0; i--) {
                int w = btnWidth(actions[i]);
                out.add(0, new BtnRect(actions[i], x - w, btnY, x, btnY + ROW_BTN_HEIGHT));
                x -= w + gap;
            }
        }
        return out;
    }

    private record BtnRect(RowAction action, int x0, int y0, int x1, int y1) {
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick))
            return true;

        double mouseX = event.x();
        double mouseY = event.y();
        int top = listTop();
        int bottom = listBottom();
        int left = contentLeft();
        int right = contentRight();
        if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom)
            return false;

        int rel = (int) (mouseY - (top + UILayoutMetrics.LIST_CONTENT_PAD_Y) + this.scrollPx);
        if (rel < 0)
            return false;
        int index = rel / ROW_HEIGHT;
        if (index < 0 || index >= this.visible.size())
            return false;
        ChestTemplate t = this.visible.get(index);
        int rowY = top + UILayoutMetrics.LIST_CONTENT_PAD_Y + (index * ROW_HEIGHT) - (int) this.scrollPx;

        // 行内ボタン判定が最優先。
        for (BtnRect b : layoutRowButtons(t, rowY)) {
            if (mouseX >= b.x0 && mouseX < b.x1 && mouseY >= b.y0 && mouseY < b.y1) {
                handleRowAction(b.action, t, index);
                return true;
            }
        }
        // 本文クリック = 適用 (フッターヒント通り、 menu があるときのみ)。
        if (canApply()) {
            handleRowAction(RowAction.APPLY, t, index);
            return true;
        }
        return false;
    }

    private void handleRowAction(RowAction action, ChestTemplate t, int visibleIndex) {
        switch (action) {
            case APPLY -> {
                if (canApply()) {
                    TemplatePreviewScreen.openOrApply(this.parent, this.menu, this.containerSlotCount, t);
                }
            }
            case DUPLICATE -> {
                TemplateManager.duplicate(t.id());
                rebuildList();
            }
            case DELETE -> {
                TemplateManager.delete(t.id());
                rebuildList();
            }
            case UP -> moveInList(visibleIndex, -1);
            case DOWN -> moveInList(visibleIndex, +1);
        }
    }

    /** Manager 内のリスト上で順序を入れ替えて Storage の priority に保存する。 */
    private void moveInList(int from, int delta) {
        int to = from + delta;
        if (to < 0 || to >= this.visible.size() || from == to)
            return;
        // 並べ替えは検索が空のときだけ (= visible が全件と一致する保証がある)。
        if (!this.searchBox.getValue().isEmpty())
            return;
        List<ChestTemplate> all = TemplateManager.list();
        if (from >= all.size() || to >= all.size())
            return;
        ChestTemplate tmp = all.get(from);
        all.set(from, all.get(to));
        all.set(to, tmp);
        List<String> ids = new ArrayList<>(all.size());
        for (ChestTemplate ct : all)
            ids.add(ct.id());
        TemplateManager.reorder(ids);
        rebuildList();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy))
            return true;
        this.scrollPx -= dy * ROW_HEIGHT * 2;
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE)
            return super.keyPressed(event);
        // Tab はフォーカス移動として画面 (Screen) 側に通す。 Esc は上で処理済み。
        if (event.key() != GLFW.GLFW_KEY_TAB
                && this.searchBox != null && this.searchBox.canConsumeInput()) {
            // 検索欄にフォーカスがある間は、 EditBox が処理しなかったキーも消費する
            // (= 素通りさせると他ハンドラへ届き、 タイプ中に暴発する)。
            this.searchBox.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        // ALT プレビューのフェード追跡が最後のテンプレ参照を保持し続けないよう解放 (= 倉庫検索と同様)。
        AltPreviewPopupRenderer.resetFadeTracking();
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(this.parent);*/
        //?} else {
        Minecraft.getInstance().setScreen(this.parent);
        //?}
    }
}
