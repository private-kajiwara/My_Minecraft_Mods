package com.kajiwara.omnichest.distribution.ui;

import com.kajiwara.omnichest.classify.ClassificationCache;
import com.kajiwara.omnichest.classify.StorageCategory;
import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.client.gui.search.layout.UILayoutMetrics;
import com.kajiwara.omnichest.distribution.CategoryMapper;
import com.kajiwara.omnichest.distribution.DistributionOpenTracker.OpenContext;
import com.kajiwara.omnichest.distribution.StorageAssignment;
import com.kajiwara.omnichest.distribution.StorageAssignmentManager;
import com.kajiwara.omnichest.distribution.StorageKey;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.search.ContainerSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 「Set Category」 画面 — チェスト GUI 内のボタンから開き、 現在のチェストを登録倉庫として
 * 設定 / 更新 / 削除する。
 *
 * <p>
 * <b>テーマ統一</b>: 倉庫検索 GUI と同じ {@link ThemeColorResolver} / {@link UILayoutMetrics} を使い、
 * 色・寸法・トーンを揃える (= 仕様 「GUI デザインは Storage Search GUI と統一」)。 ロジックは完全独立。
 *
 * <p>
 * 登録情報 (= 仕様): Chest Name / Category / Priority / Favorite。 World ID / Dimension / BlockPos /
 * Last Access は {@link OpenContext} と保存時刻から自動で埋める。
 */
public final class SetCategoryScreen extends Screen {

    @Nullable
    private final Screen parent;
    private final OpenContext ctx;

    /** 割り当て候補となる 「具体的な」 カテゴリ (= MIXED / UNKNOWN を除く)。 */
    private final List<StorageCategory> categories = new ArrayList<>();

    private EditBox nameBox;
    private Button categoryButton;
    private Button priorityButton;
    private Button favoriteButton;

    private StorageCategory category;
    private int priority;
    private boolean favorite;
    private final boolean alreadyRegistered;

    public SetCategoryScreen(@Nullable Screen parent, OpenContext ctx) {
        super(OmniChestLocale.get("omnichest.distribution.set_category.title", "Set Storage Category"));
        this.parent = parent;
        this.ctx = ctx;
        for (StorageCategory c : StorageCategory.values()) {
            if (c.isConcrete()) {
                this.categories.add(c);
            }
        }
        StorageAssignment existing = StorageAssignmentManager.get().get(ctx.key());
        this.alreadyRegistered = existing != null;
        if (existing != null) {
            this.category = existing.category();
            this.priority = existing.priority();
            this.favorite = existing.favorite();
        } else {
            this.category = guessCategory(ctx);
            this.priority = 0;
            this.favorite = false;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ライフサイクル
    // ════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        super.init();
        int panelW = 220;
        int rowH = UILayoutMetrics.BUTTON_HEIGHT;
        int gap = UILayoutMetrics.ROW_GAP + 2;
        int cx = this.width / 2;
        int left = cx - panelW / 2;
        // 「登録済みカテゴリ」 行を 1 つ増やすぶん、 パネル上端を引き上げて全体を縦中央に保つ
        // (= 既存コントロールの寸法は一切変えず、 上端 y と行間だけで吸収する = #8)。
        int y = this.height / 2 - 76;

        StorageAssignment existing = StorageAssignmentManager.get().get(ctx.key());
        String initialName = existing != null ? existing.name()
                : (ctx.title() != null && !ctx.title().isBlank() ? ctx.title() : category.displayName());

        // 名前入力。
        y += 14; // ラベルぶんの余白
        this.nameBox = new EditBox(this.font, left, y, panelW, rowH,
                OmniChestLocale.get("omnichest.distribution.set_category.name", "Chest Name"));
        this.nameBox.setMaxLength(48);
        this.nameBox.setValue(initialName);
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);
        y += rowH + gap + 4;

        // カテゴリ (クリックで次へサイクル)。
        this.categoryButton = Button.builder(categoryLabel(), b -> {
            int idx = categories.indexOf(category);
            category = categories.get((idx + 1) % categories.size());
            b.setMessage(categoryLabel());
        }).bounds(left, y, panelW, rowH).build();
        this.addRenderableWidget(this.categoryButton);
        y += rowH + gap;

        // 優先度 (0..9 をサイクル)。
        this.priorityButton = Button.builder(priorityLabel(), b -> {
            priority = (priority + 1) % 10;
            b.setMessage(priorityLabel());
        }).bounds(left, y, panelW, rowH).build();
        this.addRenderableWidget(this.priorityButton);
        y += rowH + gap;

        // お気に入りトグル。
        this.favoriteButton = Button.builder(favoriteLabel(), b -> {
            favorite = !favorite;
            b.setMessage(favoriteLabel());
        }).bounds(left, y, panelW, rowH).build();
        this.addRenderableWidget(this.favoriteButton);
        y += rowH + gap;

        // 「登録済みカテゴリ」 一覧へのナビゲーション。 既存ボタンと同じフル幅 (panelW) × 同じ高さ (rowH) の
        // 1 行を足すだけで、 他コントロールのサイズは変えない (= #8 のレイアウト制約)。
        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get("omnichest.distribution.set_category.existing", "Existing Categories"),
                b -> openExistingCategories())
                .bounds(left, y, panelW, rowH).build());
        y += rowH + gap + 4;

        // Save / Delete / Cancel。
        int btnW = alreadyRegistered ? (panelW - 2 * 6) / 3 : (panelW - 6) / 2;
        int bx = left;
        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get("omnichest.button.save", "Save"), b -> save())
                .bounds(bx, y, btnW, rowH).build());
        bx += btnW + 6;
        if (alreadyRegistered) {
            this.addRenderableWidget(Button.builder(
                    OmniChestLocale.get("omnichest.distribution.set_category.unregister", "Unregister"),
                    b -> unregister())
                    .bounds(bx, y, btnW, rowH).build());
            bx += btnW + 6;
        }
        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get("omnichest.button.cancel", "Cancel"), b -> onClose())
                .bounds(bx, y, btnW, rowH).build());
    }

    // ════════════════════════════════════════════════════════════════════
    // 動作
    // ════════════════════════════════════════════════════════════════════

    private void save() {
        String name = this.nameBox.getValue();
        int used = countUsed();
        StorageAssignment assignment = new StorageAssignment(
                ctx.key(), ctx.secondaryPos(), ctx.type(),
                name, category, priority, favorite,
                System.currentTimeMillis(), used, ctx.slotCount());
        StorageAssignmentManager.get().put(assignment);

        // 手動で決めたカテゴリを、 バッジ等が参照する分類キャッシュにも <b>ロック付き</b> で反映する。
        // これをしないと、 Set Category で設定したカテゴリが in-world バッジに出てこない
        // (= 旧実装は StorageAssignment にしか書かず、 バッジが読む ClassificationCache と
        // 分断されていた)。 locked=true なので以後の自動再分類で上書きされず、 「手動」 表示になる。
        ClassificationCache.get().override(snapshotKey(), category, true);

        onClose();
    }

    /** 「登録済みカテゴリ」 一覧画面を開く (= 現在のナビゲーションパターンに従い、 戻ると本画面へ)。 */
    private void openExistingCategories() {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        //? if >=26.2 {
        /*mc.setScreenAndShow(new ExistingCategoriesScreen(this));*/
        //?} else {
        mc.setScreen(new ExistingCategoriesScreen(this));
        //?}
    }

    private void unregister() {
        StorageAssignmentManager.get().remove(ctx.key());
        // 登録解除したら手動ロックも解いて、 自動分類を再開させる (= 次のスナップショット更新で再判定)。
        ClassificationCache.get().unlock(snapshotKey());
        onClose();
    }

    /**
     * このチェストの {@link StorageKey} を、 分類キャッシュ/バッジが使う
     * {@link ContainerSnapshot.Key} に変換する。 どちらも (dimension, 正規化 BlockPos) で、
     * ラージチェストの正規化アルゴリズムも一致しているため、 値はそのまま対応する
     * (= 型だけが検索系/分配系で分かれている)。
     */
    private ContainerSnapshot.Key snapshotKey() {
        StorageKey k = ctx.key();
        return new ContainerSnapshot.Key(k.dimension(), k.pos());
    }

    private int countUsed() {
        int used = 0;
        int n = Math.min(ctx.slotCount(), ctx.menu().slots.size());
        for (int i = 0; i < n; i++) {
            if (!ctx.menu().slots.get(i).getItem().isEmpty()) {
                used++;
            }
        }
        return used;
    }

    /** チェストの中身から最頻カテゴリを推定する (= 新規登録時の初期値)。 */
    private static StorageCategory guessCategory(OpenContext ctx) {
        Map<StorageCategory, Integer> tally = new EnumMap<>(StorageCategory.class);
        int n = Math.min(ctx.slotCount(), ctx.menu().slots.size());
        for (int i = 0; i < n; i++) {
            ItemStack stack = ctx.menu().slots.get(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            StorageCategory c = CategoryMapper.toStorageCategory(stack);
            if (c.isConcrete()) {
                tally.merge(c, stack.getCount(), Integer::sum);
            }
        }
        StorageCategory best = StorageCategory.BUILDING;
        int bestCount = -1;
        for (Map.Entry<StorageCategory, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    // ════════════════════════════════════════════════════════════════════
    // ラベル
    // ════════════════════════════════════════════════════════════════════

    /**
     * カテゴリ選択ボタンのラベル。 <b>テキスト色だけ</b> にカテゴリ色を載せる (= #7)。
     * ボタン地・ホバー・押下はバニラのまま残し、 カテゴリの識別は文字色のみで伝える
     * (= 地をカテゴリ色で塗らない / 枠も色付けしない)。 バニラ Button は地色を上書きできないが、
     * Style に色を持つ Component はその色で描かれるため、 「バニラ地 + カテゴリ色文字」 になる。
     */
    private Component categoryLabel() {
        Component base = OmniChestLocale.get("omnichest.distribution.set_category.category_label",
                "Category: %1$s", category.displayComponent().getString());
        return base.copy().withStyle(Style.EMPTY.withColor(TextColor.fromRgb(category.rgb())));
    }

    private Component priorityLabel() {
        return OmniChestLocale.get("omnichest.distribution.set_category.priority_label",
                "Priority: %1$d", priority);
    }

    private Component favoriteLabel() {
        Component state = favorite
                ? OmniChestLocale.get("omnichest.toggle.on", "§a✔ ON")
                : OmniChestLocale.get("omnichest.toggle.off", "§c✘ OFF");
        return OmniChestLocale.get("omnichest.distribution.set_category.favorite_label",
                "Favorite: %1$s", state.getString());
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // タイトル + アクセント線の Y は、 パネル上端 (init の y = height/2 - 76) と歩調を合わせて引き上げる。
        // 「登録済みカテゴリ」 行を足してパネルを 12px 上げたぶん、 ここも上げないとチェスト名ラベルと
        // 重なる (= 旧位置のままだと見出し/バーが名前行に被る)。
        // タイトル。
        g.centeredText(this.font, this.getTitle(), this.width / 2,
                this.height / 2 - 96, ThemeColorResolver.TEXT_PRIMARY);

        // 行き先カテゴリの色を小さなアクセントで示す (= コントラスト原則)。 ボタン地は塗らず、
        // カテゴリ色はこの細いアクセント線とボタンの文字色だけで伝える (= #7: 地・枠は色付けしない)。
        int accent = 0xFF000000 | category.rgb();
        int cx = this.width / 2;
        g.fill(cx - 110, this.height / 2 - 86, cx + 110, this.height / 2 - 84, accent);

        // 名前ラベル。
        if (this.nameBox != null) {
            g.text(this.font,
                    OmniChestLocale.get("omnichest.distribution.set_category.name", "Chest Name"),
                    this.nameBox.getX(), this.nameBox.getY() - 11,
                    ThemeColorResolver.TEXT_SECONDARY, false);
        }

        // フッターヒント。
        Component hint = OmniChestLocale.get("omnichest.distribution.set_category.hint",
                "Click a chest, choose a category, then Save.  ESC = cancel");
        g.centeredText(this.font, hint, this.width / 2,
                this.height - UILayoutMetrics.FOOTER_HINT_FROM_BOTTOM, ThemeColorResolver.TEXT_DIM);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (this.nameBox != null && this.nameBox.isFocused()) {
            if (this.nameBox.keyPressed(event)) {
                return true;
            }
            if (this.nameBox.canConsumeInput()) {
                return false;
            }
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
