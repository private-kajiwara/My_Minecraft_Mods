package com.kajiwara.omnichest.distribution.ui;

import com.kajiwara.omnichest.classify.StorageCategory;
import com.kajiwara.omnichest.client.gui.CategoryBadgeRenderer;
import com.kajiwara.omnichest.client.gui.ScreenBackdrop;
import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.distribution.StorageAssignmentManager;
import com.kajiwara.omnichest.gui.ExistingCategoriesFit;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 「Existing Categories」 — カテゴリ設定 ({@link SetCategoryScreen}) から開く、 既存カテゴリの一覧 (= #8)。
 *
 * <p>
 * 各 <b>具体</b> カテゴリ ({@link StorageCategory#isConcrete()}) を、 在庫バッジ / カテゴリ設定と同じ
 * カテゴリ色チップ ({@link CategoryBadgeRenderer#renderCategoryChip}) で並べ、 そのカテゴリに何個の倉庫が
 * 登録済みかを {@code ×N} で添える。 これにより 「どんなカテゴリがあり、 どれが設定済みか」 を一目で把握でき、
 * empty-state プレビューの 「必要なカテゴリ」 表示と視覚言語が揃う (= 反復)。
 *
 * <p>
 * <b>スクロール対応</b> (1.3.0 の Known Issue の解消): 旧実装はグリッドを画面中央に縦センタリングし、
 * その下端に Back ボタンを積んでいたため、 必要な論理高さが項目数に比例して増え、 カテゴリが
 * 18 → 27 に増えた 1.3.0 では多くの解像度 / GUI スケールで見切れ・重なりが起きていた。
 * 現在は上下のバンド (タイトル / 副題 … Back / フッターヒント) を画面端に固定し、 その間を
 * スクロールするリスト帯にしてある。 <b>必要な論理サイズは項目数に依存しない</b>。
 *
 * <p>
 * <b>レイアウト算術はこの画面に一切置かない</b>。 座標はすべて {@link ExistingCategoriesFit} が返す
 * {@link ExistingCategoriesFit.Layout} を読むだけにしてある。 こうしておくと
 * {@code ExistingCategoriesFitTest} (解像度 20 種 × GUI スケール 9 種 × Force Unicode 2 通り) が
 * <b>実挙動そのもの</b>を検証したことになる。 ここに独自の算術を書き足すとその保証が壊れる。
 *
 * <p>
 * <b>ロジック非変更</b>: 読み取り専用。 {@link StorageAssignmentManager} を参照するだけで、 登録データには
 * 一切触れない。 テーマ・寸法は倉庫検索 GUI と同じ {@link ThemeColorResolver} /
 * {@code UILayoutMetrics} 系の値 ({@link ExistingCategoriesFit} に集約) を使う。
 */
public final class ExistingCategoriesScreen extends Screen {

    @Nullable
    private final Screen parent;
    private final List<StorageCategory> categories = new ArrayList<>();

    /** init で確定するレイアウト (= この画面が持つ唯一の座標源)。 */
    @Nullable
    private ExistingCategoriesFit.Layout layout;

    /**
     * スクロール量 (px)。 画面インスタンス変数なので、 開き直すと ({@link SetCategoryScreen} が
     * 毎回 new する) 自動的に先頭へ戻る。 ウィンドウリサイズ時は {@code init} が再実行されるが、
     * 値は保持したまま新しいレイアウトで再クランプされる。
     */
    private double scrollPx;
    private boolean draggingScroll;

    public ExistingCategoriesScreen(@Nullable Screen parent) {
        super(OmniChestLocale.get("omnichest.distribution.existing.title", "Existing Categories"));
        this.parent = parent;
        for (StorageCategory c : StorageCategory.values()) {
            if (c.isConcrete()) {
                this.categories.add(c);
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        this.layout = ExistingCategoriesFit.compute(this.width, this.height, this.categories.size(),
                this.font.width("×999"), this.font.lineHeight);
        // リサイズ後も位置を保ったうえで新しい可視高さへ丸める (= 下端より先へ残らない)。
        this.scrollPx = this.layout.clampScroll(this.scrollPx);

        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get("omnichest.button.back", "Back"), b -> onClose())
                .bounds(this.layout.backX(), this.layout.backY(),
                        this.layout.backW(), this.layout.backH())
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 全面の暗転を最初に 1 枚。 バニラ既定の in-world 暗転は 25% 黒だけで、
        // シェーダー環境の雪原などでは世界がほぼ素通しになり文字と競合する。
        ScreenBackdrop.dim(g, this);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        ExistingCategoriesFit.Layout l = this.layout;
        if (l == null) {
            return;
        }

        // タイトル + サブタイトル (画面上端のバンドに固定)。
        g.centeredText(this.font, this.getTitle(), this.width / 2,
                l.titleY(), ThemeColorResolver.TEXT_PRIMARY);
        Component subtitle = OmniChestLocale.get("omnichest.distribution.existing.subtitle",
                "Storages registered per category");
        g.centeredText(this.font, subtitle, this.width / 2,
                l.subtitleY(), ThemeColorResolver.TEXT_SECONDARY);

        int total = StorageAssignmentManager.get().size();
        if (total == 0) {
            // 登録が 1 件も無い: 何も設定されていないことを 1 行で伝える (= 余計な空グリッドを出さない)。
            Component empty = OmniChestLocale.get("omnichest.distribution.existing.empty",
                    "No storages registered yet.");
            g.centeredText(this.font, empty, this.width / 2,
                    l.emptyTextY(this.font.lineHeight), ThemeColorResolver.TEXT_DIM);
        } else {
            renderGrid(g, l);
            renderScrollbar(g, l);
        }

        // フッターヒント (= 倉庫検索と同じ backdrop 帯で視認性確保)。
        Component hint = OmniChestLocale.get("omnichest.distribution.existing.hint", "ESC = back");
        int hintW = this.font.width(hint);
        int cx = this.width / 2;
        g.fill(cx - hintW / 2 - 6, l.hintBandTop(), cx + hintW / 2 + 6,
                l.hintY() + this.font.lineHeight + 2, ThemeColorResolver.FOOTER_BACKDROP);
        g.centeredText(this.font, hint, cx, l.hintY(), ThemeColorResolver.TEXT_DIM);
    }

    private void renderGrid(GuiGraphicsExtractor g, ExistingCategoriesFit.Layout l) {
        g.enableScissor(0, l.listTop(), this.width, l.listBottom());
        try {
            for (int i = 0; i < categories.size(); i++) {
                int x = l.cellX(i);
                int y = l.cellY(i, this.scrollPx);
                if (y + ExistingCategoriesFit.CELL_H < l.listTop() || y > l.listBottom()) {
                    continue;   // 帯の外は描かない (= 軽量化。 既存 DimensionMenuScreen と同流儀)
                }
                StorageCategory cat = categories.get(i);

                // カテゴリ色チップ (= 在庫バッジ / カテゴリ設定と同じ視覚言語)。 表示専用なので hover/focus は false。
                CategoryBadgeRenderer.renderCategoryChip(g, x, y, l.chipW(),
                        ExistingCategoriesFit.CHIP_H, cat, false, false, cat.displayComponent());

                // 登録倉庫数を ×N で添える。 0 件はあえて控えめ色で 「未設定」 を伝える (= コントラスト)。
                int count = StorageAssignmentManager.get().byCategory(cat).size();
                Component countC = OmniChestLocale.get("omnichest.distribution.existing.count", "×%1$d", count);
                int countColor = count > 0 ? ThemeColorResolver.TEXT_SECONDARY : ThemeColorResolver.TEXT_DIM;
                // 影を付ける。 件数はチップの<b>外側</b> = 素の背景の上に描かれるため、 この画面で
                // 唯一 影が無い要素だった (タイトル / 副題 / ヒント / チップラベルはすべて影あり)。
                // 色と階層 (0 件は控えめ) は変えない。
                g.text(this.font, countC, x + l.chipW() + ExistingCategoriesFit.COUNT_GAP,
                        y + (ExistingCategoriesFit.CHIP_H - this.font.lineHeight) / 2 + 1, countColor, true);
            }
        } finally {
            g.disableScissor();
        }
    }

    /**
     * スクロールバー (= 倉庫分配画面と同じ 4px バー)。 全項目が収まっているときは描かない。
     *
     * <p>
     * この画面は 「登録済みカテゴリの<b>網羅</b>一覧」 なので、 隠れている項目があることが
     * 見えないと 「これで全部」 と誤解される。 バーはその可視化のために必須。
     */
    private void renderScrollbar(GuiGraphicsExtractor g, ExistingCategoriesFit.Layout l) {
        if (!l.scrollbarVisible()) {
            return;
        }
        int x = l.scrollbarX();
        int w = l.scrollbarW();
        int thumbY = l.thumbY(this.scrollPx);
        g.fill(x, l.listTop(), x + w, l.listBottom(), ThemeColorResolver.SCROLLBAR_TRACK);
        g.fill(x, thumbY, x + w, thumbY + l.thumbHeight(),
                this.draggingScroll ? ThemeColorResolver.SCROLLBAR_THUMB_DRAG
                        : ThemeColorResolver.SCROLLBAR_THUMB);
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力 (既存 DistributionScreen / DimensionMenuScreen と同一の流儀。 慣性なし)
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        ExistingCategoriesFit.Layout l = this.layout;
        if (l == null || l.maxScroll() <= 0) {
            return false;   // 収まっているときはスクロール自体を無効化
        }
        this.scrollPx = l.clampScroll(this.scrollPx - dy * ExistingCategoriesFit.SCROLL_STEP);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;    // Back ボタンなどの widget が先
        }
        ExistingCategoriesFit.Layout l = this.layout;
        if (event.button() == 0 && l != null && l.isOverScrollbar(event.x(), event.y())) {
            this.draggingScroll = true;
            this.scrollPx = l.clampScroll(l.scrollFromMouseY(event.y()));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        ExistingCategoriesFit.Layout l = this.layout;
        if (this.draggingScroll && l != null) {
            this.scrollPx = l.clampScroll(l.scrollFromMouseY(event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            this.draggingScroll = false;
        }
        return super.mouseReleased(event);
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
}
