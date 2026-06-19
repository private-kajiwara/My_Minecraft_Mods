package com.kajiwara.omnichest.distribution.ui;

import com.kajiwara.omnichest.classify.StorageCategory;
import com.kajiwara.omnichest.client.gui.CategoryBadgeRenderer;
import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.client.gui.search.layout.UILayoutMetrics;
import com.kajiwara.omnichest.config.gui.widget.DropdownPopup;
import com.kajiwara.omnichest.config.gui.widget.NavyFooterButton;
import com.kajiwara.omnichest.config.gui.widget.OverlayPopup;
import com.kajiwara.omnichest.distribution.StorageDistributionManager;
import com.kajiwara.omnichest.distribution.StorageDistributionManager.DestinationGroup;
import com.kajiwara.omnichest.distribution.StorageDistributionManager.DistributionPreview;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 「Auto Distribute — Preview」 — チェスト GUI の {@code [Auto Distribute]} ボタンから開き、
 * <b>実行前</b> に 「送り元チェストの中身」 と 「送り先チェストが受け取るアイテム」 を、
 * <b>バニラのインベントリスロット</b> で並べて見せる確認ダイアログ。
 *
 * <pre>
 *   ┌─ Auto Distribute — Preview ──────────────────┐
 *   │ Source                    Destination         │
 *   │ [▣] This Chest    →    [▼ [▣] Mining Storage ] │
 *   │ ▦▦▦                        ▦▦                  │
 *   │            [ Distribute ]  [ Cancel ]         │
 *   └───────────────────────────────────────────────┘
 * </pre>
 *
 * <p>
 * <b>設計</b> (= OmniChest UI ファミリへの統一):
 * <ul>
 *   <li>背景は倉庫検索/設定と同じ {@link ThemeColorResolver#LIST_BG} (= 紺の半透明)。</li>
 *   <li>ボタンは設定画面の Save/Cancel と同じ {@link NavyFooterButton}。</li>
 *   <li>送り先が複数あるときだけ {@link DropdownPopup} で 1 つを選んでプレビューする
 *       (= 単一なら出さない)。</li>
 *   <li>送り元/送り先の名前左に {@link ContainerTypeIconHelper} のコンテナ種別アイコンを出す。</li>
 *   <li>インベントリ表現は {@link InventoryPreviewRenderer} のバニラスロット。</li>
 * </ul>
 * Confirm でのみ実際の {@link StorageDistributionManager#distributeFromOpen()} を走らせる。
 */
public final class DistributePreviewScreen extends Screen {

    private static final int COLS = 6;
    private static final int SOURCE_MAX_ROWS = 3;
    private static final int DEST_MAX_ROWS = 2;        // 選択中の送り先アイテムの最大行数
    private static final int CELL = InventoryPreviewRenderer.CELL;
    private static final int PANEL_W = COLS * CELL;    // 片側パネル幅 (= 108)
    private static final int PAD = 10;
    private static final int HEADER_H = 22;
    private static final int ARROW_W = 40;
    private static final int ICON = 16;                // コンテナアイコン辺
    private static final int NAME_X = ICON + 4;        // 名前テキストの左オフセット (アイコン幅 + 余白)
    private static final int NAME_ROW_H = 16;          // 名前行の高さ (= アイコンに合わせる)
    private static final int OFFSCREEN = -10000;       // popup 展開中に widget へ渡す画面外座標
    private static final int TAG_GAP = 4;              // empty-state の必要カテゴリ タグ間の隙間
    private static final int FOOTER_PAD_X = 6;         // フッター backdrop の左右 padding
    private static final int FOOTER_PAD_Y = 2;         // フッター backdrop の上下 padding

    @Nullable
    private final Screen parent;
    private final DistributionPreview preview;

    /** 現在プレビュー中の送り先 index (= ドロップダウンで切り替え)。 */
    private int selectedDest = 0;
    /** 送り先選択ドロップダウン (開いている間のみ非 null)。 */
    @Nullable
    private OverlayPopup activePopup;

    /** empty-state で表示する 「必要なカテゴリ」 を、 ダイアログ幅で折り返した行リスト。 */
    private List<List<StorageCategory>> requiredRows = List.of();

    // ─── computeLayout() が確定させる座標 ───
    private int dialogX;
    private int dialogY;
    private int dialogW;
    private int dialogH;
    private int contentTop;
    private int leftX;
    private int rightX;
    private int arrowCx;

    public DistributePreviewScreen(@Nullable Screen parent, DistributionPreview preview) {
        super(OmniChestLocale.get("omnichest.distribution.preview.title", "Category Auto Sort"));
        this.parent = parent;
        this.preview = preview;
    }

    // ════════════════════════════════════════════════════════════════════
    // レイアウト
    // ════════════════════════════════════════════════════════════════════

    private boolean multiDest() {
        return preview.destinations().size() > 1;
    }

    private int sourceGridHeight() {
        return InventoryPreviewRenderer.gridHeight(preview.sourceItems().size(), COLS, SOURCE_MAX_ROWS);
    }

    private boolean sourceOverflow() {
        return InventoryPreviewRenderer.overflow(preview.sourceItems().size(), COLS, SOURCE_MAX_ROWS) > 0;
    }

    /** 名前行の上端 (ラベルの下)。 */
    private int nameRowY() {
        return contentTop + 10;
    }

    /** アイテムグリッドの上端 (名前行の下)。 */
    private int gridY() {
        return nameRowY() + NAME_ROW_H + 2;
    }

    /** 送り先選択ドロップダウンのアンカー矩形 (= 右パネルの名前行)。 */
    private int triggerY() {
        return nameRowY();
    }

    private void computeLayout() {
        int lineH = this.font.lineHeight;
        int sourceBlockH = 10 + NAME_ROW_H + 2 + sourceGridHeight() + (sourceOverflow() ? lineH + 1 : 0);
        // 送り先は選択で中身が変わってもダイアログ高さが揺れないよう、 常に最大行ぶん確保する。
        int destBlockH = 10 + NAME_ROW_H + 2 + DEST_MAX_ROWS * CELL;

        int contentH;
        if (preview.isEmpty()) {
            // empty-state: 「動かせない」 見出し + (あれば) 「必要なカテゴリ」 ラベル + タグ行。
            this.requiredRows = computeRequiredRows(PANEL_W + ARROW_W + PANEL_W);
            int h = lineH + 2;
            if (!requiredRows.isEmpty()) {
                h += 6 + lineH + 4 + requiredRows.size() * (lineH + 3);
            }
            contentH = h + 4;
        } else {
            this.requiredRows = List.of();
            contentH = Math.max(sourceBlockH, destBlockH);
        }

        this.dialogW = PAD + PANEL_W + ARROW_W + PANEL_W + PAD;
        this.dialogH = HEADER_H + 4 + contentH + 8 + UILayoutMetrics.BUTTON_HEIGHT + PAD;
        this.dialogX = (this.width - dialogW) / 2;
        this.dialogY = Math.max(8, (this.height - dialogH) / 2);
        this.contentTop = dialogY + HEADER_H + 4;
        this.leftX = dialogX + PAD;
        this.rightX = dialogX + PAD + PANEL_W + ARROW_W;
        this.arrowCx = dialogX + PAD + PANEL_W + ARROW_W / 2;
    }

    /**
     * 「必要なカテゴリ」 タグを、 与えられた最大幅で左から詰めて折り返す (= 1 行に収まらなければ改行)。
     * タグの採寸は {@link CategoryBadgeRenderer#tagWidth} を使い、 描画 ({@link #renderRequiredCategories})
     * と完全に一致させる。
     */
    private List<List<StorageCategory>> computeRequiredRows(int maxW) {
        List<List<StorageCategory>> rows = new ArrayList<>();
        List<StorageCategory> cur = new ArrayList<>();
        int curW = 0;
        for (StorageCategory cat : preview.requiredCategories()) {
            int w = CategoryBadgeRenderer.tagWidth(this.font, cat);
            int add = (cur.isEmpty() ? 0 : TAG_GAP) + w;
            if (!cur.isEmpty() && curW + add > maxW) {
                rows.add(cur);
                cur = new ArrayList<>();
                curW = 0;
                add = w;
            }
            cur.add(cat);
            curW += add;
        }
        if (!cur.isEmpty()) {
            rows.add(cur);
        }
        return rows;
    }

    @Override
    protected void init() {
        super.init();
        if (selectedDest >= preview.destinations().size()) {
            selectedDest = 0;
        }
        computeLayout();

        int rowH = UILayoutMetrics.BUTTON_HEIGHT;
        int btnY = dialogY + dialogH - PAD - rowH;

        if (preview.isEmpty()) {
            // empty-state は <b>確認ワークフローではなくナビゲーション</b>。 実行できない操作 (= Distribute)
            // は出さず、 「戻る」 だけを <b>バニラ標準ボタン</b> で 1 つ中央に置く (= Back は中断ではなく遷移)。
            int backW = Math.min(120, dialogW - PAD * 2);
            int backX = dialogX + (dialogW - backW) / 2;
            this.addRenderableWidget(Button.builder(
                    OmniChestLocale.get("omnichest.button.back", "Back"), b -> onClose())
                    .bounds(backX, btnY, backW, rowH).build());
            return;
        }

        int btnW = (dialogW - PAD * 2 - 6) / 2;
        int bx = dialogX + PAD;

        // 設定画面と同じ NavyFooterButton で Distribute / Cancel を出す (= ネイティブな OmniChest ダイアログ感)。
        this.addRenderableWidget(new NavyFooterButton(bx, btnY, btnW, rowH,
                OmniChestLocale.get("omnichest.distribution.preview.confirm", "Distribute"),
                b -> confirm()));
        this.addRenderableWidget(new NavyFooterButton(bx + btnW + 6, btnY, btnW, rowH,
                OmniChestLocale.get("omnichest.button.cancel", "Cancel"),
                b -> onClose()));
    }

    /**
     * 画面リサイズ (F11 / GUI スケール / 解像度変更) ハンドラ。
     *
     * <p>
     * {@code super.resize()} が {@link #init()} → {@link #computeLayout()} を呼び直してダイアログ座標を
     * 生きた画面サイズで再計算するため、 ダイアログ本体は自動で再センタリングされる。 一方
     * 送り先選択ドロップダウン ({@code activePopup}) は <b>右パネルの名前行</b> という移動するアンカーに
     * 紐づくため、 リサイズ後は anchor が動いて追従できない。 {@code survivesResize()} が false なら
     * 破棄して、 古いアンカー座標での描画・誤クリックを防ぐ ({@link OverlayPopup} の契約)。
     */
    @Override
    public void resize(int w, int h) {
        super.resize(w, h);
        if (this.activePopup != null && !this.activePopup.survivesResize()) {
            this.activePopup = null;
        }
    }

    private void confirm() {
        if (preview.isEmpty()) {
            onClose(); // 動かすものが無い: Distribute は走らせず、 戻るだけ (= 防御的)。
            return;
        }
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        // 先にチェストへ戻ってから実行する (= プレイヤーは元のチェスト画面で移動を見られる)。
        // チェストは開いたままなので active コンテキストは保たれ、 distributeFromOpen が正しく走る。
        //? if >=26.2 {
        /*mc.setScreenAndShow(parent);*/
        //?} else {
        mc.setScreen(parent);
        //?}
        StorageDistributionManager.distributeFromOpen();
    }

    private void openDestinationDropdown() {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < preview.destinations().size(); i++) {
            idx.add(i);
        }
        this.activePopup = new DropdownPopup<>(this.width, this.height,
                idx, selectedDest,
                i -> Component.literal(preview.destinations().get(i).name()),
                i -> this.selectedDest = i,
                rightX, triggerY(), PANEL_W, NAME_ROW_H);
    }

    private boolean popupOpen() {
        return this.activePopup != null && !this.activePopup.isClosed();
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        boolean popup = popupOpen();

        // ─── 1) 背景パネル (検索/設定と同じ紺) + 金色フレーム ───
        g.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, ThemeColorResolver.LIST_BG);
        g.outline(dialogX, dialogY, dialogW, dialogH, ThemeColorResolver.TAB_ACTIVE_LINE);

        // ヘッダ帯 + タイトル + 区切り線。
        g.centeredText(this.font, this.getTitle(), dialogX + dialogW / 2, dialogY + 7,
                ThemeColorResolver.TEXT_HIGHLIGHT);
        g.fill(dialogX + 1, dialogY + HEADER_H - 1, dialogX + dialogW - 1, dialogY + HEADER_H,
                ThemeColorResolver.SEPARATOR);

        // ─── 2) コンテンツ (パネル/スロット/トリガ) ───
        if (preview.isEmpty()) {
            renderEmptyState(g);
        } else {
            renderSourcePanel(g);
            renderArrow(g);
            renderDestinationPanel(g, mouseX, mouseY, popup);
        }

        // ─── 3) ボタン (= 背景の上) ───
        // popup 展開中は背後ボタンに hover/tooltip を出さないよう画面外座標を渡す (入力も後段でゲート)。
        int wmx = popup ? OFFSCREEN : mouseX;
        int wmy = popup ? OFFSCREEN : mouseY;
        super.extractRenderState(g, wmx, wmy, partialTick);

        // フッターヒント (= popup モードに応じて利用可能な操作だけを出す。 倉庫検索と同じ backdrop 帯で
        // 視認性を確保 = #5/#6)。 empty-state は 「戻る」 しかできないので Back のヒントだけに絞る。
        Component hint = preview.isEmpty()
                ? OmniChestLocale.get("omnichest.distribution.preview.hint_empty", "ESC = back")
                : OmniChestLocale.get("omnichest.distribution.preview.hint",
                        "Enter = distribute,  ESC = cancel");
        int hintW = this.font.width(hint);
        int hintY = this.height - UILayoutMetrics.FOOTER_HINT_FROM_BOTTOM;
        int cx = this.width / 2;
        g.fill(cx - hintW / 2 - FOOTER_PAD_X, hintY - FOOTER_PAD_Y,
                cx + hintW / 2 + FOOTER_PAD_X, hintY + this.font.lineHeight + FOOTER_PAD_Y,
                ThemeColorResolver.FOOTER_BACKDROP);
        g.centeredText(this.font, hint, cx, hintY, ThemeColorResolver.TEXT_DIM);

        // ─── 4) ドロップダウン (= 最前面) ───
        if (this.activePopup != null) {
            if (this.activePopup.isClosed()) {
                this.activePopup = null;
            } else {
                this.activePopup.extractRenderState(g, mouseX, mouseY);
            }
        }
    }

    private void renderSourcePanel(GuiGraphicsExtractor g) {
        int x = leftX;
        g.text(this.font, OmniChestLocale.get("omnichest.distribution.preview.source", "Source"),
                x, contentTop, ThemeColorResolver.TEXT_DIM, false);

        // アイコン + 名前 (割り当てカテゴリがあればその色)。
        g.item(ContainerTypeIconHelper.iconStack(preview.sourceType()), x, nameRowY());
        int nameColor = preview.sourceCategory() != null
                ? (0xFF000000 | preview.sourceCategory().rgb())
                : ThemeColorResolver.TEXT_PRIMARY;
        g.text(this.font, trim(preview.sourceLabel(), PANEL_W - NAME_X),
                x + NAME_X, nameRowY() + (NAME_ROW_H - 8) / 2, nameColor, true);

        InventoryPreviewRenderer.renderGrid(g, this.font, x, gridY(), COLS, SOURCE_MAX_ROWS,
                preview.sourceItems(), true);
        if (sourceOverflow()) {
            int extra = InventoryPreviewRenderer.overflow(preview.sourceItems().size(), COLS, SOURCE_MAX_ROWS);
            g.text(this.font,
                    OmniChestLocale.get("omnichest.distribution.preview.more", "…and %1$d more", extra),
                    x, gridY() + sourceGridHeight() + 1, ThemeColorResolver.TEXT_DIM, false);
        }
    }

    private void renderDestinationPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean popup) {
        int x = rightX;
        DestinationGroup d = preview.destinations().get(selectedDest);
        int catColor = 0xFF000000 | d.category().rgb();

        g.text(this.font, OmniChestLocale.get("omnichest.distribution.preview.destination", "Destination"),
                x, contentTop, ThemeColorResolver.TEXT_DIM, false);
        // 受け取る総個数を同じ行の右端に控えめに出す (= 別行を増やさずレイアウトを安定させる)。
        Component count = OmniChestLocale.get("omnichest.distribution.preview.count", "×%1$d", d.totalCount());
        g.text(this.font, count, x + PANEL_W - this.font.width(count), contentTop,
                ThemeColorResolver.TEXT_SECONDARY, false);

        int rowY = nameRowY();
        if (multiDest()) {
            // ドロップダウン トリガ (= 枠 + アイコン + 名前 + ▼)。 ホバーで明るくする。
            boolean hover = !popup && mouseX >= x && mouseX < x + PANEL_W
                    && mouseY >= rowY && mouseY < rowY + NAME_ROW_H;
            g.fill(x, rowY, x + PANEL_W, rowY + NAME_ROW_H,
                    hover ? ThemeColorResolver.TAB_HOVER_BG : ThemeColorResolver.TAB_NORMAL_BG);
            g.outline(x, rowY, PANEL_W, NAME_ROW_H, ThemeColorResolver.TAB_BORDER);
            g.item(ContainerTypeIconHelper.iconStack(d.type()), x + 1, rowY);
            g.text(this.font, trim(d.name(), PANEL_W - NAME_X - 8),
                    x + NAME_X, rowY + (NAME_ROW_H - 8) / 2, catColor, true);
            g.text(this.font, Component.literal("▼"), x + PANEL_W - 9,
                    rowY + (NAME_ROW_H - 8) / 2, ThemeColorResolver.TEXT_DIM, false);
        } else {
            // 単一の送り先: ドロップダウンは出さず、 アイコン + 名前のみ。
            g.item(ContainerTypeIconHelper.iconStack(d.type()), x, rowY);
            g.text(this.font, trim(d.name(), PANEL_W - NAME_X),
                    x + NAME_X, rowY + (NAME_ROW_H - 8) / 2, catColor, true);
        }

        // アイテムグリッド (= 送り元グリッドと同じ y に揃える)。
        InventoryPreviewRenderer.renderGrid(g, this.font, x, gridY(), COLS, DEST_MAX_ROWS,
                d.items(), false);
        int extra = InventoryPreviewRenderer.overflow(d.items().size(), COLS, DEST_MAX_ROWS);
        if (extra > 0) {
            g.text(this.font,
                    OmniChestLocale.get("omnichest.distribution.preview.more", "…and %1$d more", extra),
                    x, gridY() + DEST_MAX_ROWS * CELL + 1, ThemeColorResolver.TEXT_DIM, false);
        }
    }

    /**
     * empty-state の本文を描く: 「いま動かせない」 見出し + (あれば) 「次のカテゴリの倉庫を用意して」
     * というガイドを、 在庫バッジと同じカテゴリタグ ({@link CategoryBadgeRenderer#renderTag}) で示す。
     * これにより 「なぜ振り分けできないか」 と 「何をすれば良いか」 を一目で伝える (= #4)。
     */
    private void renderEmptyState(GuiGraphicsExtractor g) {
        int cx = dialogX + dialogW / 2;
        int y = contentTop + 2;

        Component empty = OmniChestLocale.get("omnichest.distribution.preview.empty",
                "Nothing to distribute right now.");
        g.centeredText(this.font, empty, cx, y, ThemeColorResolver.TEXT_SECONDARY);
        y += this.font.lineHeight;

        if (requiredRows.isEmpty()) {
            return;
        }

        y += 6;
        Component label = OmniChestLocale.get("omnichest.distribution.preview.required_label",
                "Set up a storage for:");
        g.centeredText(this.font, label, cx, y, ThemeColorResolver.TEXT_DIM);
        y += this.font.lineHeight + 4;

        for (List<StorageCategory> row : requiredRows) {
            int rowW = 0;
            for (int i = 0; i < row.size(); i++) {
                rowW += CategoryBadgeRenderer.tagWidth(this.font, row.get(i));
                if (i < row.size() - 1) {
                    rowW += TAG_GAP;
                }
            }
            int tx = cx - rowW / 2;
            for (StorageCategory cat : row) {
                tx += CategoryBadgeRenderer.renderTag(g, this.font, tx, y, cat);
                tx += TAG_GAP;
            }
            y += this.font.lineHeight + 3;
        }
    }

    private void renderArrow(GuiGraphicsExtractor g) {
        int arrowY = gridY() + Math.max(0, (sourceGridHeight() - this.font.lineHeight) / 2);
        g.centeredText(this.font, Component.literal("→"), arrowCx, arrowY,
                ThemeColorResolver.TAB_ACTIVE_LINE);
    }

    private String trim(String s, int maxW) {
        if (s == null) {
            return "";
        }
        if (this.font.width(s) <= maxW) {
            return s;
        }
        while (s.length() > 1 && this.font.width(s + "…") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力 (= popup 最優先ルーティング)
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (this.activePopup != null) {
            this.activePopup.mouseClicked(mx, my, event.button());
            if (this.activePopup.isClosed()) {
                this.activePopup = null;
            }
            return true;
        }
        // 送り先トリガのクリック → ドロップダウンを開く (複数のときのみ)。
        if (!preview.isEmpty() && multiDest() && event.button() == 0
                && mx >= rightX && mx < rightX + PANEL_W
                && my >= triggerY() && my < triggerY() + NAME_ROW_H) {
            openDestinationDropdown();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.activePopup != null) {
            this.activePopup.mouseDragged(event.x(), event.y(), event.button(), dx, dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.activePopup != null) {
            this.activePopup.mouseReleased(event.x(), event.y(), event.button());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.activePopup != null && this.activePopup.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.activePopup != null) {
            if (this.activePopup.keyPressed(event.key()) && this.activePopup.isClosed()) {
                this.activePopup = null;
            }
            return true; // popup 中は他キーを画面へ流さない。
        }
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            // empty-state では Distribute が無いので Enter も 「戻る」 に倒す (= 出している操作と一致)。
            if (preview.isEmpty()) {
                onClose();
            } else {
                confirm();
            }
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
}
