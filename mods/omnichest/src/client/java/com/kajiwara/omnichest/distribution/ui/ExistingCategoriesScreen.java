package com.kajiwara.omnichest.distribution.ui;

import com.kajiwara.omnichest.classify.StorageCategory;
import com.kajiwara.omnichest.client.gui.CategoryBadgeRenderer;
import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.client.gui.search.layout.UILayoutMetrics;
import com.kajiwara.omnichest.distribution.StorageAssignmentManager;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
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
 * <b>ロジック非変更</b>: 読み取り専用。 {@link StorageAssignmentManager} を参照するだけで、 登録データには
 * 一切触れない。 テーマ・寸法は倉庫検索 GUI と同じ {@link ThemeColorResolver} / {@link UILayoutMetrics}。
 */
public final class ExistingCategoriesScreen extends Screen {

    private static final int COLS = 2;
    private static final int CELL_H = 22;
    private static final int CHIP_W = 104;
    private static final int CHIP_H = 16;
    private static final int COL_GAP = 16;
    private static final int COUNT_GAP = 6;

    @Nullable
    private final Screen parent;
    private final List<StorageCategory> categories = new ArrayList<>();

    // init で算出。
    private int gridLeft;
    private int gridTop;

    public ExistingCategoriesScreen(@Nullable Screen parent) {
        super(OmniChestLocale.get("omnichest.distribution.existing.title", "Existing Categories"));
        this.parent = parent;
        for (StorageCategory c : StorageCategory.values()) {
            if (c.isConcrete()) {
                this.categories.add(c);
            }
        }
    }

    private int colW() {
        return CHIP_W + COUNT_GAP + this.font.width("×999");
    }

    private int rows() {
        return (categories.size() + COLS - 1) / COLS;
    }

    @Override
    protected void init() {
        super.init();
        int gridW = COLS * colW() + (COLS - 1) * COL_GAP;
        this.gridLeft = (this.width - gridW) / 2;
        this.gridTop = this.height / 2 - (rows() * CELL_H) / 2;

        int rowH = UILayoutMetrics.BUTTON_HEIGHT;
        int backW = 120;
        int backY = this.gridTop + rows() * CELL_H + UILayoutMetrics.SECTION_GAP + 4;
        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get("omnichest.button.back", "Back"), b -> onClose())
                .bounds((this.width - backW) / 2, backY, backW, rowH).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        // タイトル + サブタイトル。
        g.centeredText(this.font, this.getTitle(), this.width / 2,
                this.gridTop - 34, ThemeColorResolver.TEXT_PRIMARY);
        Component subtitle = OmniChestLocale.get("omnichest.distribution.existing.subtitle",
                "Storages registered per category");
        g.centeredText(this.font, subtitle, this.width / 2,
                this.gridTop - 20, ThemeColorResolver.TEXT_SECONDARY);

        int total = StorageAssignmentManager.get().size();
        if (total == 0) {
            // 登録が 1 件も無い: 何も設定されていないことを 1 行で伝える (= 余計な空グリッドを出さない)。
            Component empty = OmniChestLocale.get("omnichest.distribution.existing.empty",
                    "No storages registered yet.");
            g.centeredText(this.font, empty, this.width / 2,
                    this.height / 2 - this.font.lineHeight / 2, ThemeColorResolver.TEXT_DIM);
        } else {
            renderGrid(g);
        }

        // フッターヒント (= 倉庫検索と同じ backdrop 帯で視認性確保)。
        Component hint = OmniChestLocale.get("omnichest.distribution.existing.hint", "ESC = back");
        int hintW = this.font.width(hint);
        int hintY = this.height - UILayoutMetrics.FOOTER_HINT_FROM_BOTTOM;
        int cx = this.width / 2;
        g.fill(cx - hintW / 2 - 6, hintY - 2, cx + hintW / 2 + 6,
                hintY + this.font.lineHeight + 2, ThemeColorResolver.FOOTER_BACKDROP);
        g.centeredText(this.font, hint, cx, hintY, ThemeColorResolver.TEXT_DIM);
    }

    private void renderGrid(GuiGraphicsExtractor g) {
        int colW = colW();
        for (int i = 0; i < categories.size(); i++) {
            StorageCategory cat = categories.get(i);
            int col = i % COLS;
            int row = i / COLS;
            int x = gridLeft + col * (colW + COL_GAP);
            int y = gridTop + row * CELL_H;

            // カテゴリ色チップ (= 在庫バッジ / カテゴリ設定と同じ視覚言語)。 表示専用なので hover/focus は false。
            CategoryBadgeRenderer.renderCategoryChip(g, x, y, CHIP_W, CHIP_H, cat,
                    false, false, cat.displayComponent());

            // 登録倉庫数を ×N で添える。 0 件はあえて控えめ色で 「未設定」 を伝える (= コントラスト)。
            int count = StorageAssignmentManager.get().byCategory(cat).size();
            Component countC = OmniChestLocale.get("omnichest.distribution.existing.count", "×%1$d", count);
            int countColor = count > 0 ? ThemeColorResolver.TEXT_SECONDARY : ThemeColorResolver.TEXT_DIM;
            g.text(this.font, countC, x + CHIP_W + COUNT_GAP,
                    y + (CHIP_H - this.font.lineHeight) / 2 + 1, countColor, false);
        }
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
