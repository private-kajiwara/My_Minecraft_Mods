package com.kajiwara.omnichest.mixin;

import com.kajiwara.omnichest.catsort.engine.CategorySortEngine;
import com.kajiwara.omnichest.catsort.ui.SortButtonWidget;
import com.kajiwara.omnichest.client.ClientKeyBindings;
import com.kajiwara.omnichest.client.gui.CategoryBadgeRenderer;
import com.kajiwara.omnichest.client.gui.OmniChestScaledScreen;
import com.kajiwara.omnichest.client.gui.SearchScreen;
import com.kajiwara.omnichest.client.gui.search.preview.UnifiedPanelRenderer;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.distribution.StorageDistributionManager;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.search.ContainerScanner;
import com.kajiwara.omnichest.slotlock.SlotLockConfig;
import com.kajiwara.omnichest.template.config.TemplateConfig;
import com.kajiwara.omnichest.template.gui.TemplateManagerScreen;
import com.kajiwara.omnichest.template.gui.TemplateSaveScreen;
import com.kajiwara.omnichest.util.ContainerSorter;
import com.kajiwara.omnichest.util.DepositMatchingHelper;
import com.kajiwara.omnichest.util.StackCompactor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class GenericContainerScreenMixin extends Screen implements OmniChestScaledScreen {

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageWidth;

    @Shadow
    protected int imageHeight;

    @Unique
    private EditBox cits$searchBox;

    @Unique
    private Button cits$sortByTypeButton;

    @Unique
    private Button cits$sortByCountButton;

    @Unique
    private Button cits$layoutLeftButton;

    @Unique
    private Button cits$layoutRightButton;

    // 「Deposit Matching」ボタン本体。対応 GUI のときのみ生成される。
    @Unique
    private Button cits$depositButton;

    // 「Compact (スタック圧縮)」ボタン本体。対応 GUI のときのみ生成される。
    // Shift+Click 時はプレイヤーインベントリ側も圧縮する (オプション仕様)。
    @Unique
    private Button cits$compactButton;

    // 「倉庫検索 (Chest Network Search)」ボタン本体。
    // クリックで {@link SearchScreen} を開く (チェスト GUI は閉じられる)。
    // Deposit / Compact と同じく、対応 GUI のときのみ生成し、 Compact の直下に同サイズで配置。
    @Unique
    private Button cits$searchNetworkButton;

    // ───────────────────────────────────────────────────────────
    // Chest Template System のボタン群:
    //   - Save Template     : 現在のチェスト配置を新テンプレートとして保存
    //   - Manage Templates  : 管理画面 (一覧/名前変更/削除/複製/並び替え)
    // 配置は「倉庫検索」の更に下に縦並びで追加する。
    // ───────────────────────────────────────────────────────────
    @Unique
    private Button cits$saveTemplateButton;

    @Unique
    private Button cits$manageTemplateButton;

    // 「カテゴリ整理 (Category Sort)」 ボタン本体。 対応 GUI のときのみ生成される。
    // 既存の「種類」 ボタン (= 簡易ハードコードソート) とは別系統で、 タグベースの
    // 16 カテゴリ分類 + tick 安全移動を行う {@link CategorySortEngine} を呼ぶ。
    @Unique
    private Button cits$categorySortButton;

    // ───────────────────────────────────────────────────────────
    // Storage Auto Distribution のボタン群 (= 検索/整理/テンプレとは独立機能):
    //   - Set Category    : このチェストを登録倉庫として設定/更新
    //   - Auto Distribute : 開いているチェスト + インベントリを既知倉庫へ振り分け
    // 設定 (distribution.enableAutoDistribution && showButtons) が ON のときのみ生成。
    // ───────────────────────────────────────────────────────────
    @Unique
    private Button cits$setCategoryButton;

    @Unique
    private Button cits$autoDistributeButton;

    // Deposit / Compact ボタン用の寸法定数 (ボタン右上配置の右端基準)
    @Unique
    private static final int CITS_DEPOSIT_WIDTH = 110;
    @Unique
    private static final int CITS_DEPOSIT_HEIGHT = 14;

    @Unique
    private boolean cits$isLargeChest = false;

    @Unique
    private boolean cits$layoutRight = true;

    /**
     * この画面が 「検索/種類/数量 の上部行」 を持てるタイプか (= ChestMenu / ShulkerBoxMenu)。
     *
     * <p>
     * 旧コードは {@code cits$searchBox == null} を 「上部行が無い画面」 のセンチネルに流用していたが、
     * Main Menu Visibility で <b>検索バーだけ非表示</b> にすると searchBox が null になり、 種類/数量/◀▶ の
     * レイアウトまで巻き添えでスキップされてしまう。 「上部行を持てるか」 と 「検索バーを出すか」 は別概念
     * なので、 前者を専用フラグに分離する。
     */
    @Unique
    private boolean cits$hasSearchRow = false;

    /**
     * この画面が OmniChest の右列ボタン群を出せる対応コンテナか (= {@code containerSlotCount > 0})。
     * 個々のボタンは Main Menu Visibility で非表示にできるため、 「対応画面か」 の判定を
     * 特定ボタン (旧: depositButton) の null チェックに依存させず、 専用フラグで持つ。
     */
    @Unique
    private boolean cits$supportedContainer = false;

    protected GenericContainerScreenMixin(Component title) {
        super(title);
    }

    /** Main Menu Visibility 等の Render/UI 設定への短縮アクセサ。 */
    @Unique
    private com.kajiwara.omnichest.config.data.RenderConfig cits$ui() {
        return com.kajiwara.omnichest.config.ConfigManager.get().render;
    }

    /**
     * チェスト GUI のフレーム描画末尾で、 Smart Storage Classification の
     * カテゴリバッジを上に乗せる。
     *
     * <p>
     * 「[FOOD STORAGE] Confidence: 92%」のような小さい帯を GUI の上部に表示する。
     * 既存ウィジェット (検索バー / 種類 / 数量) と衝突しない y 座標を、
     * GUI 種別に応じて算出する:
     * <ul>
     * <li><b>小型 ContainerScreen (3 行チェスト)</b>: 検索/種類/数量 行が {@code topPos - 18} に出ている。
     * バッジはその更に上 ({@code topPos - 32}) に置く。</li>
     * <li><b>ラージチェスト</b>: 検索バーは側面 (左右パネル) なので GUI の真上は空。
     * {@code topPos - 14} に置く。</li>
     * <li><b>シュルカー等の非 ContainerScreen</b>: GUI の真上は空。
     * {@code topPos - 14} に置く。</li>
     * </ul>
     *
     * 設定でオフにできる ({@link com.kajiwara.omnichest.classify.ClassifyConfig#showCategoryBadge})。
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
    private void cits$renderCategoryBadge(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        // Main Menu Visibility: 「カテゴリインジケータ」 OFF ならバッジ自体を出さない (= 表示のみ。
        // 分類キャッシュ / 予測ロジックはそのまま動き続ける)。
        if (!cits$ui().showCategoryIndicator) {
            return;
        }
        int badgeY = cits$badgeY();
        // バッジの帯 (背景) の左端を上部コンテンツ列のアンカーに揃える。 帯は描画 x より
        // BADGE_PAD_X だけ外側に張り出すため、 描画 x = アンカー + BADGE_PAD_X とすることで
        // 帯の左端 (= バッジの視覚的な左端) が検索行の左端と同じ縦ラインに乗る。
        // 「予測表示」 OFF のときは Confidence% / Manual を省き、 カテゴリ名だけを出す。
        int anchorX = this.leftPos + CITS_TOP_CONTENT_LEFT;
        CategoryBadgeRenderer.renderBadge(g, anchorX + CategoryBadgeRenderer.BADGE_PAD_X, badgeY,
                ContainerScanner.currentActiveKey(), cits$ui().showPredictionDisplay);
    }

    // ───────────────────────────────────────────────────────────
    // 操作方法ヘルプパネル (チェスト GUI の脇に、 ボタン群と反対側に出す)
    // ───────────────────────────────────────────────────────────
    /** 折り返しで生じた「同じ操作の続き」行の行送り (= 改行を含めない素のフォント行高)。 */
    @Unique
    private static final int CITS_HELP_LINE_HEIGHT = 10;
    /** 別操作の「項目間」の行送り (= 視覚的に項目を区切るため少し広めに取る)。 */
    @Unique
    private static final int CITS_HELP_ENTRY_SPACING = 13;
    /** パネル内の左右パディング (px)。 */
    @Unique
    private static final int CITS_HELP_PADDING = 4;
    /**
     * パネル描画を試みる最小幅 (px)。 これ未満の余白しか取れない GUI スケールでは
     * チェストに被るのを避けて描画自体スキップする (= 「無い」が「壊れる」より優先)。
     */
    @Unique
    private static final int CITS_HELP_MIN_WIDTH = 60;
    /** 本文用の控えめなグレー (ARGB)。 */
    @Unique
    private static final int CITS_HELP_COLOR_BODY = 0xFF888888;
    /** タイトル用の少し明るいグレー (ARGB)。 */
    @Unique
    private static final int CITS_HELP_COLOR_TITLE = 0xFFAAAAAA;
    /** 区切り線用の暗めのグレー (ARGB)。 */
    @Unique
    private static final int CITS_HELP_COLOR_RULE = 0x66888888;
    /**
     * 操作方法パネルが画面高さに収まらない (= GUI Scale Auto の全画面化で論理高さが縮む) ときに、
     * パネル全体を一様縮小して 1 枚に収める際の最小縮小率。
     * <p>
     * これを下回るほど極端に縮む場合は下端でクリップを許容する (= 折り返して読めなくなる崩れより
     * 「小さめ・1 枚」 を優先)。 {@link com.kajiwara.omnichest.client.gui.SearchScreen} のフッター
     * ヒントと同じ思想・同じ値で揃える。
     */
    @Unique
    private static final float CITS_HELP_MIN_SCALE = 0.5f;

    /**
     * チェスト GUI を開いている間、 マウス/キー操作の早見表を脇に小さく出す。
     *
     * <p>
     * <b>レイアウト破綻防止</b>: パネル幅は GUI の脇に <b>残っている隙間</b> から動的に算出する。
     * 余ったスペースが狭ければテキストを自動折り返し ({@link Font#split}) して詰める。
     * 残スペースが {@link #CITS_HELP_MIN_WIDTH} 未満なら描画しない (= チェストのアイテム上には
     * 絶対に被せない)。
     *
     * <p>
     * 配置順位:
     * <ol>
     * <li>ボタン群と反対側 ({@code cits$layoutRight = true} なら左、 false なら右)。</li>
     * <li>1) の残スペースが狭すぎる場合はボタン側 (= ボタン列の <b>更に外側</b>) にフォールバック。</li>
     * <li>どちらにも入らないほど狭い GUI スケールでは何も描画しない。</li>
     * </ol>
     *
     * <p>
     * 表示条件: 右列ボタンが生成された (= deposit ボタンが存在する) 画面のみ。
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
    private void cits$renderControlsHelp(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        // 対応コンテナでのみ表示 (= Deposit ボタンの有無に依存させない: 個別非表示でも消えないように)。
        if (!this.cits$supportedContainer)
            return;
        // Main Menu Visibility: 「操作ヘルプ」 OFF なら早見表を出さない (= 表示のみ)。
        if (!cits$ui().showControlsHelp)
            return;

        // ─── 1) 表示する行を組み立て (= 実際にコードが反応するキー組合せだけ) ───
        // 「実際に効く入力」 と「説明テキスト」 を 1:1 に保つため、 各エントリは対応する設定 /
        // ボタン生成状況に紐付けて条件付き表示する (= ユーザーが OFF にした入力は説明にも出さない)。
        //
        // 各行の対応コード:
        //   (1) Alt + 左クリック   → SlotLockScreenMixin#cits_slotLock$onMouseClicked
        //                            (button=0, hasAltDown, !hasShiftDown)
        //   (2) ロック ホットキー   → 同上 (KeyMapping.matchesMouse / keyPressed の matches。
        //                            既定は中マウスボタン・Controls で再割当/未割当可)
        //   (3) Shift + Alt + 左   → 同上 (button=0, hasAltDown, hasShiftDown) サイクル モード
        //   (4) Alt + ドラッグ      → SlotLockScreenMixin#cits_slotLock$onMouseDragged
        //                            (Alt 押下中の drag)
        //   (5) Alt + シュルカー上ホバー → ShulkerPreviewScreenMixin / AltPreviewTooltip
        //                                   (search.enableAltPreview)
        //   (6) Shift + [Compact] → GenericContainerScreenMixin の Compact ボタン Lambda
        //                            (hasShiftDown 時に compactContainerAndPlayer)
        SlotLockConfig lockCfg = SlotLockConfig.get();
        java.util.List<Component> lines = new java.util.ArrayList<>(6);
        if (lockCfg.toggleWithAltClick) {
            lines.add(OmniChestLocale.get(Keys.CONTROLS_LINE_SLOT_LOCK_ALT_CLICK,
                    "Alt + Left Click: Toggle slot lock"));
        }
        // スロットロック ホットキーは vanilla Controls が唯一の源。 実際に割り当てられている
        // キー名をそのまま出し、 未割当なら行ごと消す (= 「効く入力」 と説明の 1:1 を維持)。
        KeyMapping slotLockHotkey = ClientKeyBindings.slotLockToggleMapping();
        if (slotLockHotkey != null && !slotLockHotkey.isUnbound()) {
            lines.add(OmniChestLocale.get(Keys.CONTROLS_LINE_SLOT_LOCK_HOTKEY,
                    "%1$s: Toggle slot lock", slotLockHotkey.getTranslatedKeyMessage()));
        }
        if (lockCfg.cycleWithShiftAltClick) {
            lines.add(OmniChestLocale.get(Keys.CONTROLS_LINE_ITEM_LOCK_CYCLE,
                    "Shift + Alt + Click: Cycle slot/item lock"));
        }
        if (lockCfg.toggleWithAltClick) {
            lines.add(OmniChestLocale.get(Keys.CONTROLS_LINE_ALT_DRAG,
                    "Alt + Drag: Multi-slot lock toggle"));
        }
        // ALT ホバーシュルカープレビューは search.enableAltPreview が ON のときのみ表示する。
        // 設定が OFF なら入力は無効化されているので、 説明欄からも消す (= 1:1 整合性)。
        boolean altPreviewOn;
        try {
            altPreviewOn = com.kajiwara.omnichest.config.ConfigManager.get().search.enableAltPreview;
        } catch (Throwable ignored) {
            altPreviewOn = false;
        }
        if (altPreviewOn) {
            lines.add(OmniChestLocale.get(Keys.CONTROLS_LINE_ALT_HOVER_SHULKER_PREVIEW,
                    "Alt + Hover Shulker Box: Preview contents"));
        }
        if (this.cits$compactButton != null) {
            lines.add(OmniChestLocale.get(Keys.CONTROLS_LINE_SHIFT_COMPACT,
                    "Shift + Click Compact: Compact player too"));
        }
        if (lines.isEmpty())
            return;

        // ─── 2) 配置サイドを決める: 「GUI の脇に残ってる余白」から算出 ───
        // ボタン列の幅 (= CITS_DEPOSIT_WIDTH) も計算に含め、 同じ側に置く場合は
        // ボタンの外側 (= screen 端寄り) に追いやる。
        int margin = 4;
        int edgePad = 2;
        // 反対側 (= ボタンと逆) に取れる幅。
        int oppositeAvail = this.cits$layoutRight
                ? this.leftPos - margin - edgePad
                : this.width - (this.leftPos + this.imageWidth) - margin - edgePad;
        // 同じ側 (= ボタンの外側) に取れる幅。 ボタン領域を除外する。
        int sameSideAvail = (this.cits$layoutRight
                ? this.width - (this.leftPos + this.imageWidth) - margin - CITS_DEPOSIT_WIDTH - margin
                : this.leftPos - margin - CITS_DEPOSIT_WIDTH - margin) - edgePad;

        boolean placeOpposite;
        int avail;
        if (oppositeAvail >= CITS_HELP_MIN_WIDTH) {
            placeOpposite = true;
            avail = oppositeAvail;
        } else if (sameSideAvail >= CITS_HELP_MIN_WIDTH) {
            placeOpposite = false;
            avail = sameSideAvail;
        } else {
            return; // どちらの脇にも収まらない → 描画しない (= チェストには絶対に被せない)。
        }

        // パネル幅は「テキスト最大幅 + パディング」と「残スペース」の小さい方。
        Component title = OmniChestLocale.get(Keys.CONTROLS_TITLE, "Controls");
        int textMaxW = this.font.width(title);
        for (Component line : lines) {
            textMaxW = Math.max(textMaxW, this.font.width(line));
        }
        int desiredW = textMaxW + CITS_HELP_PADDING * 2;
        int panelWidth = Math.min(desiredW, avail);

        // ─── 3) 配置 X 座標 ───
        int x;
        if (placeOpposite) {
            x = this.cits$layoutRight
                    ? this.leftPos - panelWidth - margin
                    : this.leftPos + this.imageWidth + margin;
        } else {
            // 同じ側: ボタン列の外側にずらす。
            x = this.cits$layoutRight
                    ? this.leftPos + this.imageWidth + margin + CITS_DEPOSIT_WIDTH + margin
                    : this.leftPos - margin - CITS_DEPOSIT_WIDTH - margin - panelWidth;
        }
        int y = this.topPos;

        // ─── 4) 全行をパネル幅で折り返し、 まず総高さを測ってから描画する ───
        // Font.split は 1 行を複数の FormattedCharSequence に分割する (= スタイル保持)。
        int wrapWidth = panelWidth - CITS_HELP_PADDING * 2;
        if (wrapWidth < 20) return; // パディング控除で 20 px 未満になるなら諦める。

        int textX = x + CITS_HELP_PADDING;
        java.util.List<net.minecraft.util.FormattedCharSequence> titleLines =
                this.font.split(title, wrapWidth);
        // 各操作行も先に折り返しておく (= 測定と描画で 2 度 split しない)。
        java.util.List<java.util.List<net.minecraft.util.FormattedCharSequence>> bodyLines =
                new java.util.ArrayList<>(lines.size());
        for (Component line : lines) {
            bodyLines.add(this.font.split(line, wrapWidth));
        }

        // 総コンテンツ高さ。 行送り規則は下の描画ループと厳密に一致させる
        // (= タイトル各行 LINE_HEIGHT + 区切り 4px + 本文)。
        int contentH = titleLines.size() * CITS_HELP_LINE_HEIGHT + 4;
        for (int i = 0; i < bodyLines.size(); i++) {
            java.util.List<net.minecraft.util.FormattedCharSequence> wrapped = bodyLines.get(i);
            for (int w = 0; w < wrapped.size(); w++) {
                boolean lastWrapInEntry = (w == wrapped.size() - 1);
                boolean lastEntry = (i == bodyLines.size() - 1);
                contentH += (lastWrapInEntry && !lastEntry)
                        ? CITS_HELP_ENTRY_SPACING
                        : CITS_HELP_LINE_HEIGHT;
            }
        }

        // ─── 画面高さへのクランプ (= 全画面化で論理高さが縮んだ時の下端見切れ対策) ───
        // 通常 (= 高さに余裕がある windowed 等) は startY=topPos / scale=1 で<b>従来と完全一致</b>。
        // topPos 起点では下端が画面外に出る場合、 まず開始 Y を上へ詰めて画面内に収める (= 中身は不変)。
        // それでも収まらない極小高さでは、 フッターヒントと同じ方式で全体を一様縮小して 1 枚に収める。
        // edgePad は本メソッド冒頭で定義済み (= 配置サイド算出と同じ画面端余白を流用)。
        int availH = this.height - 2 * edgePad;
        int startY = y;
        float scale = 1.0f;
        if (contentH <= availH) {
            if (startY + contentH > this.height - edgePad) {
                startY = this.height - edgePad - contentH;
            }
            if (startY < edgePad) {
                startY = edgePad;
            }
        } else {
            startY = edgePad;
            scale = Math.max(CITS_HELP_MIN_SCALE, availH / (float) contentH);
        }

        // 縮小時はパネル左上を原点にローカル座標で描く (= 拡縮の基準を左上に固定)。
        boolean scaled = scale < 1.0f;
        if (scaled) {
            g.pose().pushMatrix();
            g.pose().translate((float) x, (float) startY);
            g.pose().scale(scale, scale);
        }
        int originX = scaled ? 0 : x;
        int originTextX = scaled ? CITS_HELP_PADDING : textX;
        int lineY = scaled ? 0 : startY;

        for (net.minecraft.util.FormattedCharSequence tl : titleLines) {
            g.text(this.font, tl, originTextX, lineY, CITS_HELP_COLOR_TITLE, false);
            lineY += CITS_HELP_LINE_HEIGHT;
        }
        // 区切り線。
        g.fill(originX, lineY, originX + panelWidth, lineY + 1, CITS_HELP_COLOR_RULE);
        lineY += 4;

        // 「異なる操作 (= 別の lines[i])」 の間だけ広めの spacing を取る。
        // 折り返しで増えた continuation 行は元の行送り (LINE_HEIGHT) のまま — 1 項目内の改行が
        // 不自然に広がるのを避ける。
        for (int i = 0; i < bodyLines.size(); i++) {
            java.util.List<net.minecraft.util.FormattedCharSequence> wrapped = bodyLines.get(i);
            for (int w = 0; w < wrapped.size(); w++) {
                g.text(this.font, wrapped.get(w), originTextX, lineY,
                        CITS_HELP_COLOR_BODY, false);
                // 同じ項目内の continuation は LINE_HEIGHT、 最後の行 → 次の項目は ENTRY_SPACING。
                boolean lastWrapInEntry = (w == wrapped.size() - 1);
                boolean lastEntry = (i == bodyLines.size() - 1);
                lineY += (lastWrapInEntry && !lastEntry)
                        ? CITS_HELP_ENTRY_SPACING
                        : CITS_HELP_LINE_HEIGHT;
            }
        }

        if (scaled) {
            g.pose().popMatrix();
        }
    }

    /**
     * バッジ描画用の y 座標を選ぶ。
     *
     * <p>
     * <b>判定軸</b>: 「画面に <b>検索行が出ているか</b>」 (= {@link #cits$searchBox} が非 null) で
     * 分岐する。 検索行は ChestScreen / ShulkerBoxScreen の両方で {@code topPos - 18} に置かれる
     * ため、 そこを避けて更に上 ({@code topPos - 32}) に押し上げる必要がある。
     *
     * <p>
     * 旧実装は {@code instanceof ContainerScreen} で判定していたが、 シュルカーに検索行を
     * 追加した時点でその前提が崩れ、 シュルカーで「バッジ y = topPos - 14」 と「検索行 y = topPos - 18」
     * が縦に 4px しか開かず、 バッジ高 (= {@code font.lineHeight + 2}) との合計で大きく重なる
     * バグ (= UX レビュー指摘: 「シュルカー分類 UI と検索バーが被って読めない」) が起きていた。
     *
     * <p>
     * <b>原理</b>: 「検索行があるなら、 検索行の更に上にバッジ」 「無いなら GUI 直上にバッジ」 と
     * 1 つの軸でレイアウトを決定する (= {@code instanceof Screen} で枝分かれせず、 実存するウィジェット
     * に対して相対的に並べる)。 ラージチェストは検索行を <b>側面パネル</b> に持つので、 ここでは
     * 「検索行が上に居ない」 状態。 旧コードの「ラージのときは topPos - 14」 と同じ結果になる。
     */
    @Unique
    private int cits$badgeY() {
        // 上部行に「実際に見えている」 ウィジェットが 1 つでもあれば、 その上にバッジを退避させる。
        // 検索/種類/数量 を全部非表示にした場合は上部行が空くので、 バッジを GUI 直上まで下げてよい
        // (= 不要な空きを作らない = レイアウト適応)。
        boolean hasTopRowWidget = !this.cits$isLargeChest && this.cits$hasSearchRow
                && (this.cits$searchBox != null || this.cits$sortByTypeButton != null
                        || this.cits$sortByCountButton != null);
        int y = hasTopRowWidget ? this.topPos - 32 : this.topPos - 14;
        // ─── 上端クリップ対策 (= GUI Scale Auto の全画面化で論理高さが縮み topPos が小さい時) ───
        // バッジは GUI 枠の上に出すため topPos が小さいと y が画面上端 (0) より上に出て
        // 「[未分類]」 等が見切れていた。 画面内へクランプする (= topPos が十分ある通常時は
        // 従来と同一 y = 見た目不変。 極小キャンバスでのみ発火)。
        return Math.max(CITS_SCREEN_EDGE_PAD, y);
    }

    /**
     * 任意ボタン (= 実体は {@link AbstractWidget}) に Tooltip を貼るユーティリティ。
     * 翻訳キーが lang に存在しなければ {@code fallback} (= 英語固定) を使う。
     *
     * <p>
     * <b>なぜヘルパ化するか</b>:
     * <ul>
     *   <li>呼び出し側を「行 1 本」 に揃え、 ボタン生成ブロックの読みやすさを保つ。</li>
     *   <li>将来 Tooltip の遅延 / 配色 を統一する際の 1 点に集約する。</li>
     *   <li>{@link Tooltip#create(Component)} を直接 import するノイズを mixin 側に持ち込まない。</li>
     * </ul>
     */
    @Unique
    private static void cits$applyTooltip(AbstractWidget widget, String key, String fallback) {
        if (widget == null) return;
        widget.setTooltip(Tooltip.create(OmniChestLocale.get(key, fallback)));
    }

    // ───────────────────────────────────────────────────────────
    // ボタン群の背景パネル
    //
    // 既存ボタン (Deposit / Compact / Category Sort / Chest Search / Save・Apply・Manage
    // Template / Set Category / Auto Distribute) は、 配置・ハンドラ・サイズを<b>変更せず</b>に、
    // 後ろに統一テーマのパネル ({@link UnifiedPanelRenderer}) を 1 枚だけ敷く。
    //
    // 目的:
    //   - 視覚的に「これらは一連の倉庫操作ボタンである」 と グループ化する (= デザイン 4 原則
    //     「proximity」 を明示する)
    //   - チェスト本体 (= バニラ UI) と地面 (= world) との視覚的コントラストを上げ、 押し間違いを減らす
    //   - 既存配色と浮かないよう、 倉庫検索 / 設定 GUI と同じ {@link UnifiedPanelRenderer} を流用
    //
    // 描き方: 描画は @At("HEAD") に挟む。 こうすると Screen 自身がウィジェット (= 各 Button) を
    // 描く前に 1 度だけ背景が出るため、 「ボタンより手前にパネル」 にならず、 押下も阻害しない。
    //
    // パネルはアクションボタン群 (= 2 列グリッド + 全幅行) の union にのみ被さる。 小型 / ラージとも
    // ◀▶ / 検索 / 種類数量 はパネル外 (= 上のヘッダ領域) に置くため union に含めない。 区切り線は
    // 引かず、 グループ分けは「グリッド (背高セル) ↔ 全幅行」 の構造と余白 (proximity) で表現する。
    // ───────────────────────────────────────────────────────────
    /** パネル外周マージン (= ボタン四辺と背景四辺の隙間, px)。 */
    @Unique
    private static final int CITS_PANEL_MARGIN = 3;

    /**
     * メニューパネルを画面端へクランプする際に確保する、 画面端からの最小余白 (論理 px)。
     *
     * <p>
     * <b>重複排除 (Phase 8)</b>: 旧実装は {@link #cits$applyDepositButtonLayout} と
     * {@link #cits$applyLargeRightColumn} の双方に同じリテラル {@code 2} を直書きしており、
     * クランプ思想が 2 か所に散っていた。 1 つの定数に集約して「画面端クランプの余白」 という
     * 意味を 1 か所で管理する。
     *
     * <p>
     * <b>GUI スケール非依存である理由</b>: {@code this.width} はすでに <em>スケール後の論理座標</em>
     * なので、 この余白も論理 px。 GUI スケールを上げても論理 px は縮まない (= 物理 px が増えるだけ)
     * ため、 値をスケール倍する必要はない。 パネルが論理画面に収まらない極小ケースは各クランプ側の
     * {@code if (maxX >= minX)} ガードが既に処理する。
     */
    @Unique
    private static final int CITS_SCREEN_EDGE_PAD = 2;

    /** {@link UnifiedPanelRenderer} の影 (SHADOW_OFFSET) が右下へ張り出す量 (px)。 クランプ時に考慮する。 */
    @Unique
    private static final int CITS_PANEL_SHADOW_OVERHANG = 2;

    /**
     * チェスト GUI の <b>真上</b> に積む OmniChest 要素 (= カテゴリバッジ + 検索/種類/数量 行) が
     * 必要とする高さ (px)。
     *
     * <p>
     * 小型チェストではバッジが {@code topPos - 32} まで上がるため、 その最大値を基準に確保する。
     * GUI Scale Auto で全画面化して論理高さが縮み {@code topPos} が小さくなると、 この上段が画面
     * 上端に張り付いて <b>チェストから浮いて見える</b> ため、 {@link #cits$shiftDownForTopRow} が
     * 下に余裕がある範囲でチェスト本体を下げ、 上段がチェスト直上へ収まるようにする。
     */
    @Unique
    private static final int CITS_TOP_STACK_HEIGHT = 34;

    /**
     * ラージ (ダブル) チェストの GUI 真上に必要な高さ (px)。
     *
     * <p>
     * ラージチェストは検索/種類/数量 行を<b>側面パネル</b>に持つため ({@link #cits$applyLargeRightColumn})、
     * GUI 真上に積むのはカテゴリバッジ ({@code topPos - 14}, {@link #cits$badgeY} 参照) <b>だけ</b>。
     * 小型チェストの {@link #CITS_TOP_STACK_HEIGHT} (= バッジ + 検索行) より小さい。
     *
     * <p>
     * これを区別しないと、 高 GUI スケール (= 論理高さが縮む全画面 / 4K ウィンドウ) で
     * {@link #cits$shiftDownForTopRow} がラージチェストを<b>不要に下へ押し下げ</b>、 下端
     * (インベントリ / ホットバー) が窮屈になり中央から外れていた。 バッジは {@link #cits$badgeY} 側で
     * 画面端クランプ済なので、 ここで確保する高さは「バッジがチェスト枠に被らない最小限」 で足りる。
     */
    @Unique
    private static final int CITS_LARGE_TOP_STACK_HEIGHT = 14;

    // ───────────────────────────────────────────────────────────
    // GUI 上部コンテンツ列 (= カテゴリ バッジ + 検索行) の共通アンカー
    //
    // バッジの帯・検索ボックス・種類ボタン・数量ボタンは、 すべて <b>1 つのアンカー</b>
    // ({@code leftPos + CITS_TOP_CONTENT_LEFT}) から x を導出する。 これにより
    // 「同じ縦ライン」 を構造的に保証し、 マジックオフセット (旧: leftPos+112 / +140) を排除する。
    // GUI スケール / 翻訳長が変わってもアンカー基準なのでズレない。
    // ───────────────────────────────────────────────────────────
    /** 上部コンテンツ列の共通左端 (leftPos からのオフセット, px)。 */
    @Unique
    private static final int CITS_TOP_CONTENT_LEFT = 4;
    /** 上部検索行: 検索ボックスの幅 (px)。 */
    @Unique
    private static final int CITS_SEARCH_BOX_WIDTH = 106;
    /** 上部検索行: 種類/数量ボタンの幅 (px)。 */
    @Unique
    private static final int CITS_TOP_SORT_BUTTON_WIDTH = 26;
    /** 上部検索行: 要素間の隙間 (px)。 */
    @Unique
    private static final int CITS_TOP_ROW_GAP = 2;

    // ───────────────────────────────────────────────────────────
    // 右側 OmniChest メインメニューパネル (= 小型チェスト / エンダー / シュルカー専用)
    //
    // モックアップ準拠の構造:
    //   ┌──────────────┐
    //   │   ◀    │    ▶  │  ← ナビ行 (= 既存のレイアウト左右切替トグル)
    //   ├───────┬───────┤
    //   │ 倉庫検索 │カテゴリ整理│  ← 2 列グリッド (= 主要 4 アクション)
    //   │ 同種預入 │スタック圧縮│
    //   ├──────────────┤
    //   │   配置を保存    │  ← 全幅行 (= テンプレ / 振り分け系)
    //   │   テンプレ適用   │
    //   │   テンプレ管理   │
    //   │   自動振り分け   │
    //   │   カテゴリ設定   │
    //   └──────────────┘
    //
    // ラージ (ダブル) チェストはスコープ外 (= 従来の側面 1 列レイアウトを維持) なので、
    // これらの定数は {@code !cits$isLargeChest} のときのみ使用する。
    // すべて leftPos / topPos からのアンカー基準で導出し、 ハードコード座標は持たない。
    // ───────────────────────────────────────────────────────────
    /** メニューパネルのコンテンツ幅 (px)。 2 列グリッド + 全幅行の共通幅。 */
    @Unique
    private static final int CITS_MENU_PANEL_WIDTH = 146;
    /**
     * メニューパネルと GUI 画像の間の左右間隔 (px)。
     *
     * <p>
     * 既定の margin (4px) だと、 背景パネルが外周 ({@link #CITS_PANEL_MARGIN} = 3px) ぶん内側へ
     * 張り出すため、 パネルの視覚的な左端がチェスト枠の 1px 隣まで迫り、 チェストインベントリと
     * 被って見える。 ここを少し広げて、 背景パネルの外周 + 影を含めてもチェスト枠と数 px の
     * 余白が残るようにする (= 「被り」 解消)。
     */
    @Unique
    private static final int CITS_MENU_PANEL_GAP = 8;
    /** パネル内の要素間隔 (列 / 行 共通, px)。 */
    @Unique
    private static final int CITS_MENU_GAP = 2;
    /** 機能セクション間 (ナビ↔グリッド↔全幅) の間隔 (px)。 */
    @Unique
    private static final int CITS_MENU_SECTION_GAP = 4;
    /** ナビ行 (◀▶) のボタン高さ (px)。 */
    @Unique
    private static final int CITS_MENU_NAV_H = 16;
    /** 2 列グリッドのセル高さ (px)。 全幅行より高くしてグループを視覚的に区別する。 */
    @Unique
    private static final int CITS_MENU_GRID_H = 20;
    /** 全幅行のボタン高さ (px)。 */
    @Unique
    private static final int CITS_MENU_FULL_H = 16;
    /**
     * ラージチェスト右カラムの検索バー高さ (px)。
     * ナビ行 ({@link #CITS_MENU_NAV_H}) と揃え、 検索バー → ◀▶ → 種類数量 の上端ラインを統一する。
     */
    @Unique
    private static final int CITS_MENU_SEARCH_H = 16;
    /** ラージチェスト右カラムの 種類 / 数量 行 (2 列) の高さ (px)。 ナビ行と揃える。 */
    @Unique
    private static final int CITS_MENU_SORT_H = 16;
    /**
     * ラージチェスト右カラムで、 浮かせた検索バーと ◀▶ ナビ行の間に空ける縦の間隔 (px)。
     *
     * <p>
     * 既定の {@link #CITS_MENU_GAP} (2px) だと、 EditBox が枠線を境界より 1px 外側へ描く都合で
     * ◀▶ ボタンとほぼ接触して見える (= 「かぶってる」)。 これより広く取り、 視覚的に明確に離す。
     */
    @Unique
    private static final int CITS_MENU_SEARCH_NAV_GAP = 10;
    /**
     * ラージチェストの充填レイアウトで、 行間に上乗せする余白の上限 (px)。
     *
     * <p>
     * ボタン自体は自然な高さ ({@link #CITS_MENU_GRID_H} / {@link #CITS_MENU_FULL_H}) に固定し、
     * 区間の余りは<b>行間の余白</b>へ均等に振り分ける。 この上限を小さめに取ることで行間を詰め、
     * 配り切れなかった余りは下端へ寄せる (= ボタン群が<b>上側に詰まり</b>、 下端に余白が残る)。
     * 値を上げると行間が広がり、 下げるとより上に詰まる。
     */
    @Unique
    private static final int CITS_MENU_FILL_GAP_MAX = 2;

    /**
     * ボタン群の背景パネルを描画する。 描かない条件 (Deposit ボタン未生成 etc.) では即 return。
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"))
    private void cits$renderButtonPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (!this.cits$supportedContainer) return;

        // ─── パネル矩形の決定 (= 実際に存在するボタンの BB を 1 つに合体させる) ───
        // 「null チェック付きで union」 することで、 個々のボタンを Main Menu Visibility で隠しても
        // パネルが見えているボタンだけにぴったり収まる (= 余白だらけ / はみ出しを作らない)。
        // 先頭ボタン (Deposit) を隠してもよいよう、 アンカーを特定ボタンに固定せず union から求める。
        //
        // 小型チェスト系 (= 再設計対象) では ◀▶ ナビ行もパネル上端に取り込むため union に含める。
        // ラージ (ダブル) チェストでは ◀▶ は側面パネルの別領域 (検索行の上) に居るため<b>含めない</b>
        // (= 含めるとパネルが検索/種類/数量の上まで伸びてしまう)。 = スコープ外の従来挙動を維持。
        AbstractWidget[] inGroup = new AbstractWidget[] {
                this.cits$isLargeChest ? null : this.cits$layoutLeftButton,
                this.cits$isLargeChest ? null : this.cits$layoutRightButton,
                this.cits$depositButton,
                this.cits$compactButton,
                this.cits$categorySortButton,
                this.cits$searchNetworkButton,
                this.cits$saveTemplateButton,
                this.cits$manageTemplateButton,
                this.cits$setCategoryButton,
                this.cits$autoDistributeButton,
        };
        int x = Integer.MAX_VALUE;
        int y = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (AbstractWidget wdg : inGroup) {
            if (wdg == null) continue;
            x = Math.min(x, wdg.getX());
            y = Math.min(y, wdg.getY());
            right = Math.max(right, wdg.getX() + wdg.getWidth());
            bottom = Math.max(bottom, wdg.getY() + wdg.getHeight());
        }
        if (right == Integer.MIN_VALUE) {
            return; // 右列ボタンが 1 つも表示されていない → 背景パネルも描かない。
        }
        x -= CITS_PANEL_MARGIN;
        y -= CITS_PANEL_MARGIN;
        int w = right - x + CITS_PANEL_MARGIN;
        int h = bottom - y + CITS_PANEL_MARGIN;

        UnifiedPanelRenderer.drawPanel(g, x, y, w, h, 1.0f);

        // セパレータ (区切り線) は描かない。 小型 / ラージとも「ナビ↔ (種類数量) ↔ 2 列グリッド ↔
        // 全幅行」 という構造でグループ分けを表現し (= モックアップにも区切り線は無い)、 旧来の
        // 縦 1 列前提セパレータは廃止した。 背景パネルはアクションボタン群 (= グリッド + 全幅行) の
        // union にのみ被さる (ラージの ◀▶ / 検索 / 種類数量 は inGroup に含めないため、 パネル外)。
    }

    // ───────────────────────────────────────────────────────────
    // 26.2: コンテナ GUI 開時の GUI スケールずれ (= 開くたびに少しずれ F11 で直る) の修正
    //
    // <b>原因 (26.2 固有・jar 実証)</b>: サーバ起動のコンテナ GUI は {@code ClientPacketListener#handleOpenScreen}
    // が {@code Gui.setScreen()} 経由で開き、 {@code Minecraft#setScreenAndShow} を通らない。 そのため
    // {@link MinecraftGuiScaleMixin} の scale 再適用 (setScreenAndShow TAIL) が発火せず、 コンテナが
    // クランプ前 (= ワールド HUD 用) スケールのままレイアウトされ、 文字・アイテム・ボタンがまとめてずれる。
    // F11 (resize→resizeGui→calculateScale→setGuiScale→screen.resize) すると clamp されて直る。 26.1.x は
    // inject 先が {@code Minecraft#setScreen} で当時の開経路も setScreen だったため発生しない (= 26.2 限定の回帰)。
    //
    // <b>前回失敗の真因 (実証)</b>: 前回は init HEAD で clamp を試みたが、 clamp の有無を決める
    // {@link #omnichest$wantsScaleClamp()} は {@code cits$supportedContainer} を返し、 これは下の
    // {@link #cits$initWidgets} (init TAIL) で初めて true になる。 init HEAD 時点では false のため
    // {@code WindowGuiScaleMixin#calculateScale} が clamp せず {@code desired==現状} で no-op になっていた。
    //
    // <b>今回の修正</b>: {@code cits$supportedContainer} 確定後の init TAIL 末尾 ({@link #cits$initWidgets} 最終行)
    // で {@link #cits$reapplyScaleForContainer()} を呼ぶ。 F11 と<b>同一経路の {@code resizeGui()}</b> を 1 度
    // だけ実行し、 ウィンドウ GUI スケールの clamp と {@code screen.resize→init} による全伝播を行う (= 論理寸法
    // だけでなくレンダリング変換まで F11 と同じ状態にする)。 resizeGui は init を再入させるため
    // {@code cits$reapplyingScale} 再入ガードで二重発火を防ぐ (再入側は guard で skip し、 clamp 後スケールで
    // 正しく再レイアウトされる)。 既に正しいスケール (低スケール / クランプ不要 / 非対応コンテナ) なら
    // {@code desired==現状} で即 return = 従来と完全一致。
    //? if >=26.2 {
    /*@org.spongepowered.asm.mixin.Unique
    private boolean cits$reapplyingScale;

    @org.spongepowered.asm.mixin.Unique
    private void cits$reapplyScaleForContainer() {
        if (this.cits$reapplyingScale) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        com.mojang.blaze3d.platform.Window window = mc.getWindow();
        if (window == null) {
            return;
        }
        // cits$supportedContainer が設定済なので calculateScale (WindowGuiScaleMixin) が正しく clamp する。
        int desired = window.calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
        if (window.getGuiScale() == desired) {
            return;
        }
        this.cits$reapplyingScale = true;
        try {
            mc.resizeGui(); // F11 と同一経路: setGuiScale + screen.resize→init で clamp と全伝播。
        } finally {
            this.cits$reapplyingScale = false;
        }
    }*/
    //?}

    // ────────────────────────────────────────────────────────────────────
    // チェスト GUI に載せた検索欄への入力を、 バニラのインベントリ操作から守る
    // ────────────────────────────────────────────────────────────────────

    /**
     * {@link #cits$searchBox} にフォーカスがある間、 キー入力を検索欄で処理して<b>消費</b>する。
     *
     * <p>
     * <b>症状</b>: 検索欄に "Shell" / "enderchest" などを打つとインベントリが閉じ、
     * 続く打鍵で別のメニューが開いてしまう。
     *
     * <p>
     * <b>原因</b>: {@link EditBox} は印字キーを {@code keyPressed} では消費しない (= 文字は
     * {@code charTyped} 経由で入る) ため、 バニラ {@code AbstractContainerScreen#keyPressed} の
     * 続き —
     * <ol>
     * <li>{@code options.keyInventory.matches(event)} → {@code onClose()} … 既定 <b>E</b> で閉じる</li>
     * <li>{@code checkHotbarKeyPressed(event)} … 数字キーでホットバー入替</li>
     * <li>{@code keyPickItem} / {@code keyDrop} … ホバー中スロットへの複製 / ドロップ</li>
     * </ol>
     * にそのまま到達していた。 閉じた瞬間 {@code minecraft.screen == null} になるので、
     * 以降の打鍵は生の {@link net.minecraft.client.KeyMapping} に届き、 別メニューが開く
     * (= 「タイプ中に別のメニューが開く」 の正体)。
     *
     * <p>
     * <b>対処</b>: バニラ {@code CreativeModeInventoryScreen} と同じ流儀で、 フォーカス中は
     * 入力ウィジェットへ渡したうえで <b>true を返して消費</b>する。 通すのは
     * <b>Esc</b> (フォーカス解除 / 画面を閉じる) と <b>Tab</b> (フォーカス移動) のみ。
     * <b>フォーカスしていない時は素通り</b>なので、 E で閉じる等の従来のバニラ挙動は不変。
     *
     * <p>
     * {@code charTyped} 側は対処不要: {@code AbstractContainerScreen} は charTyped を override
     * しておらず、 {@code Screen} のフォーカス委譲で EditBox が確実に消費するため
     * (= バニラのインベントリ操作へは元々流れない)。 IME / dead key もこの経路のまま。
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cits$searchBoxKeyGuard(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        EditBox box = this.cits$searchBox;
        // canConsumeInput() = 可視 + フォーカス + 有効 (= 「今まさに打てる」)。
        if (box == null || !box.canConsumeInput())
            return;
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_TAB)
            return;
        // 編集キー (矢印 / Backspace / Ctrl+A,C,V,X …) は EditBox が処理する。
        // 処理されなかったキーも、 バニラのインベントリ操作へは渡さず黙って消費する。
        box.keyPressed(event);
        cir.setReturnValue(true);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void cits$initWidgets(CallbackInfo ci) {
        // ───────────────────────────────────────────────────────────
        // RTL ロケール時の初期レイアウト方向
        // ユーザーが ◀▶ で明示的に切替するまでの初期値だけ反転する。
        // 既存挙動 (LTR 言語) は cits$layoutRight=true のまま、 配置・色・動作は不変。
        // ───────────────────────────────────────────────────────────
        if (com.kajiwara.omnichest.i18n.RTLLayoutManager.get().isRtl()) {
            this.cits$layoutRight = false;
        }

        // ───────────────────────────────────────────────────────────
        // (1) 「Deposit Matching」ボタンの追加
        // 対応している ScreenHandler のときだけ生成する。
        // 非対応 (例: InventoryScreen の InventoryMenu) では生成しない。
        // ───────────────────────────────────────────────────────────
        AbstractContainerMenu anyMenu = ((AbstractContainerScreen<?>) (Object) this).getMenu();
        int containerSlotCount = DepositMatchingHelper.detectContainerSlotCount(anyMenu);
        if (containerSlotCount > 0) {
            this.cits$supportedContainer = true;
            // Main Menu Visibility: 各ボタンは対応する表示トグルが ON のときだけ生成する。
            // 生成しない = ウィジェットとして存在しない (= 描画もクリックもされない) だけで、
            // 預入 / 圧縮 / 整理 / 振り分け の各ロジック自体は他経路 (キーバインド等) から従来通り使える。
            // 表示トグル (showDepositButton) に加え、 機能トグル (deposit.enable) でもゲートする。
            // deposit.enable=false なら Smart Deposit 機能を切る = ボタンを生成しない。
            if (cits$ui().showDepositButton && ConfigManager.get().deposit.enable) {
            this.cits$depositButton = Button.builder(
                    OmniChestLocale.get(Keys.BUTTON_DEPOSIT, "Deposit Matching"),
                    btn -> DepositMatchingHelper.depositMatching(
                            Minecraft.getInstance(), anyMenu, containerSlotCount))
                    .bounds(0, 0, CITS_DEPOSIT_WIDTH, CITS_DEPOSIT_HEIGHT)
                    .build();
            cits$applyTooltip(this.cits$depositButton, Keys.BUTTON_DEPOSIT_TOOLTIP,
                    "Move items from your inventory into this chest, "
                            + "but only items already present in the chest.");
            this.addRenderableWidget(this.cits$depositButton);
            }

            // ───────────────────────────────────────────────────────────
            // 「Compact」ボタン。 Deposit と同じ条件 (= 対応 GUI) でのみ生成する。
            // 通常クリック  : チェスト内のみ圧縮
            // Shift+クリック: プレイヤーインベントリ側も併せて圧縮
            // ───────────────────────────────────────────────────────────
            // 表示トグル (showCompactButton) に加え、 機能トグル (compact.enable) でもゲートする。
            if (cits$ui().showCompactButton && ConfigManager.get().compact.enable) {
            this.cits$compactButton = Button.builder(
                    OmniChestLocale.get(Keys.BUTTON_COMPACT, "Compact"),
                    btn -> {
                        Minecraft mc = Minecraft.getInstance();
                        // Shift キー判定は InputConstants 経由で直接確認する (Mojang Mappings 環境差を回避)。
                        var window = mc.getWindow();
                        boolean shift = InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
                        // プレイヤーインベントリも圧縮するか: Shift 押下 OR 設定 compactPlayerInventory が ON。
                        boolean alsoPlayer = shift || ConfigManager.get().compact.compactPlayerInventory;
                        if (alsoPlayer) {
                            StackCompactor.compactContainerAndPlayer(mc, anyMenu, containerSlotCount);
                        } else {
                            StackCompactor.compactContainer(mc, anyMenu, containerSlotCount);
                        }
                    })
                    .bounds(0, 0, CITS_DEPOSIT_WIDTH, CITS_DEPOSIT_HEIGHT)
                    .build();
            cits$applyTooltip(this.cits$compactButton, Keys.BUTTON_COMPACT_TOOLTIP,
                    "Merge partial stacks of the same item together.\n"
                            + "Shift + Click: also compacts your inventory.");
            this.addRenderableWidget(this.cits$compactButton);
            }

            // ───────────────────────────────────────────────────────────
            // 「カテゴリ整理 (Category Sort)」 ボタン。
            // Compact の直下、 倉庫検索の上に、 Tooltip 付き標準ボタンとして生成する。
            // クリックで {@link CategorySortEngine#sort} を発火し、 tick 分散で安全に整列する。
            // ───────────────────────────────────────────────────────────
            if (cits$ui().showCategorySortButton
                    && CategorySortEngine.detectContainerSlotCount(anyMenu) > 0) {
                this.cits$categorySortButton = SortButtonWidget.create(
                        anyMenu, containerSlotCount,
                        0, 0, CITS_DEPOSIT_WIDTH, CITS_DEPOSIT_HEIGHT);
                // SortButtonWidget は内部で setTooltip 済 (Keys.CATEGORY_SORT_TOOLTIP)。
                // 二重 setTooltip すると上書きされてしまうので、 ここでは付け直さない。
                this.addRenderableWidget(this.cits$categorySortButton);
            }

            // ───────────────────────────────────────────────────────────
            // 「倉庫検索 (Chest Network Search)」ボタン。 Compact の直下に同サイズで配置。
            //
            // 押下の流れ:
            //   1) player.closeContainer() でサーバへ ContainerClose パケットを送る。
            //      これをしないと mc.setScreen(...) だけでは ContainerClose が送られず、
            //      サーバ側でチェストが「開きっぱなし」扱いになる
            //      ({@link AbstractContainerScreen#onClose} を経由しないため)。
            //   2) その上で SearchScreen を開く (parent は null = 戻り先はゲーム画面)。
            // ───────────────────────────────────────────────────────────
            if (cits$ui().showChestSearchButton) {
            this.cits$searchNetworkButton = Button.builder(
                    OmniChestLocale.get(Keys.BUTTON_SEARCH_NETWORK, "Chest Search"),
                    btn -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            // 直後に来る CHEST_CLOSE 等のコンテナ閉じ効果音を 1.5 秒間ミュートする。
                            // (サーバ側で発生する → client に packet が届く → SoundManager.play で
                            //  SoundSuppressor が検知してキャンセル) という流れ。
                            com.kajiwara.omnichest.client.render.SoundSuppressor
                                    .suppressContainerSoundsFor(1500L);
                            // ContainerClose を送信し、 client 側の containerMenu を inventoryMenu に戻す。
                            mc.player.closeContainer();
                        }
                        // チェスト GUI は既に閉じる扱いになっているので parent は不要。
                        SearchScreen.open(null);
                    })
                    .bounds(0, 0, CITS_DEPOSIT_WIDTH, CITS_DEPOSIT_HEIGHT)
                    .build();
            cits$applyTooltip(this.cits$searchNetworkButton, Keys.BUTTON_SEARCH_NETWORK_TOOLTIP,
                    "Search every chest you have opened on this server. "
                            + "Find any item and see where it is stored.");
            this.addRenderableWidget(this.cits$searchNetworkButton);
            }

            // ───────────────────────────────────────────────────────────
            // Chest Template System のボタン 3 連
            // (ユーザー設定で非表示にできる: TemplateConfig.showButtons)
            // ───────────────────────────────────────────────────────────
            if (TemplateConfig.get().showButtons && cits$ui().showTemplateButtons) {
                Screen selfScreen = (Screen) (Object) this;

                this.cits$saveTemplateButton = Button.builder(
                        OmniChestLocale.get(Keys.BUTTON_SAVE_TEMPLATE, "Save Layout"),
                        //? if >=26.2 {
                        /*btn -> Minecraft.getInstance().setScreenAndShow(
                                new TemplateSaveScreen(selfScreen, anyMenu, containerSlotCount)))*/
                        //?} else {
                        btn -> Minecraft.getInstance().setScreen(
                                new TemplateSaveScreen(selfScreen, anyMenu, containerSlotCount)))
                        //?}
                        .bounds(0, 0, CITS_DEPOSIT_WIDTH, CITS_DEPOSIT_HEIGHT)
                        .build();
                cits$applyTooltip(this.cits$saveTemplateButton, Keys.BUTTON_SAVE_TEMPLATE_TOOLTIP,
                        "Save the current arrangement of this chest as a reusable template.");
                this.addRenderableWidget(this.cits$saveTemplateButton);

                this.cits$manageTemplateButton = Button.builder(
                        OmniChestLocale.get(Keys.BUTTON_MANAGE_TEMPLATES, "Manage Templates"),
                        //? if >=26.2 {
                        /*btn -> Minecraft.getInstance().setScreenAndShow(
                                new TemplateManagerScreen(selfScreen, anyMenu, containerSlotCount)))*/
                        //?} else {
                        btn -> Minecraft.getInstance().setScreen(
                                new TemplateManagerScreen(selfScreen, anyMenu, containerSlotCount)))
                        //?}
                        .bounds(0, 0, CITS_DEPOSIT_WIDTH, CITS_DEPOSIT_HEIGHT)
                        .build();
                cits$applyTooltip(this.cits$manageTemplateButton, Keys.BUTTON_MANAGE_TEMPLATES_TOOLTIP,
                        "Browse, rename, duplicate, reorder, or delete your saved templates.");
                this.addRenderableWidget(this.cits$manageTemplateButton);
            }

            // ───────────────────────────────────────────────────────────
            // Storage Auto Distribution のボタン (= テンプレの更に下に縦並び)。
            // 検索系とは独立した {@link StorageDistributionManager} に委譲するだけで、
            // 位置情報 (= どのチェストか) は DistributionOpenTracker 側が保持している。
            // ───────────────────────────────────────────────────────────
            if (ConfigManager.get().distribution.enableAutoDistribution
                    && ConfigManager.get().distribution.showButtons) {
                Screen distSelf = (Screen) (Object) this;

                if (cits$ui().showSetCategoryButton) {
                this.cits$setCategoryButton = Button.builder(
                        OmniChestLocale.get("omnichest.button.set_category", "Set Category"),
                        btn -> StorageDistributionManager.openSetCategoryForCurrent(distSelf))
                        .bounds(0, 0, CITS_DEPOSIT_WIDTH, CITS_DEPOSIT_HEIGHT)
                        .build();
                cits$applyTooltip(this.cits$setCategoryButton,
                        "omnichest.button.set_category.tooltip",
                        "Mark this chest as the destination for a category of items.");
                this.addRenderableWidget(this.cits$setCategoryButton);
                }

                if (cits$ui().showAutoSortButton) {
                this.cits$autoDistributeButton = Button.builder(
                        OmniChestLocale.get("omnichest.button.auto_distribute", "Auto Distribute"),
                        btn -> StorageDistributionManager.openDistributePreview(distSelf))
                        .bounds(0, 0, CITS_DEPOSIT_WIDTH, CITS_DEPOSIT_HEIGHT)
                        .build();
                cits$applyTooltip(this.cits$autoDistributeButton,
                        "omnichest.button.auto_distribute.tooltip",
                        "Send items from this chest and your inventory to "
                                + "each registered category chest automatically.");
                this.addRenderableWidget(this.cits$autoDistributeButton);
                }
            }
        }

        // ───────────────────────────────────────────────────────────
        // (2) 検索 / ソート / レイアウト切替 行の生成。
        //
        // <b>対象</b>: 小型チェスト系の <b>「上部行」</b> を持てる GUI:
        //   - {@link ContainerScreen} (= ChestScreen): バニラチェスト / ラージチェスト
        //   - {@link ShulkerBoxScreen}: 27 スロット固定の小型チェスト相当
        //
        // <b>なぜ Shulker も対象に含めるか</b>: 以前は ContainerScreen のみで生成していたため、
        // シュルカーボックスを開いたときに「検索バー / 種類 / 数量 / ◀▶」 が出ず、 通常チェストと
        // 機能差が生じていた (UX レビュー指摘: 「チェスト/シュルカーで挙動が一貫してない」)。
        // 同じ「上部行 = topPos-18」 配置はシュルカーでも空いている領域なので、 同じ生成パスを共有する。
        //
        // <b>なぜラージチェストフラグが残るか</b>: ラージチェストはサイドパネル配置に切替えるため
        // {@link #cits$applyLayout} で分岐するための情報。 シュルカーは常に「小型扱い」 で良い。
        // ───────────────────────────────────────────────────────────
        int searchRowSlotCount = cits$searchRowSlotCount(anyMenu);
        if (searchRowSlotCount <= 0) {
            this.cits$applyLayout();
            return;
        }

        // ラージチェスト判定は ChestMenu のときだけ (= シュルカーは固定で false = 小型扱い)。
        this.cits$isLargeChest = (anyMenu instanceof ChestMenu chestMenu) && chestMenu.getRowCount() == 6;
        // この画面は上部行を持てる (= 検索バーだけ非表示でも 種類/数量/◀▶ のレイアウトは行う)。
        this.cits$hasSearchRow = true;

        // Main Menu Visibility: 検索バーの表示トグルが ON のときだけ生成する。 非表示でも検索索引
        // (ContainerScanner / SearchIndex) は従来通り動く (= チェスト内ハイライト UI を出さないだけ)。
        if (cits$ui().showSearchBar) {
        this.cits$searchBox = new EditBox(this.font, 0, 0, 100, 14,
                OmniChestLocale.get(Keys.EDITBOX_SEARCH_LABEL, "Search"));
        this.cits$searchBox.setMaxLength(50);
        this.cits$searchBox.setBordered(true);
        this.cits$searchBox.setHint(OmniChestLocale.get(
                Keys.EDITBOX_SEARCH_HINT_GENERIC, "Search..."));
        cits$applyTooltip(this.cits$searchBox, Keys.EDITBOX_SEARCH_TOOLTIP,
                "Highlight items in this chest by name.");
        this.addRenderableWidget(this.cits$searchBox);
        }

        // 「種類」 ショートカット: 新しい {@link CategorySortEngine} (タグベース 16 カテゴリ) を起動。
        // 旧 ContainerSorter.sortByCategory (= 7 種ハードコード) は ContainerSorter 側に互換用として残るが、
        // GUI からはこちらの本格的なエンジンを呼び出す。
        // anyMenu / searchRowSlotCount は lambda 内で参照されるため effectively final。
        final AbstractContainerMenu sortMenu = anyMenu;
        final int sortSlotCount = searchRowSlotCount;
        if (cits$ui().showSortByType) {
        this.cits$sortByTypeButton = Button.builder(
                OmniChestLocale.get(Keys.BUTTON_SORT_BY_TYPE, "Type"),
                btn -> CategorySortEngine.sort(Minecraft.getInstance(), sortMenu, sortSlotCount))
                .bounds(0, 0, 26, 14)
                .build();
        cits$applyTooltip(this.cits$sortByTypeButton, Keys.BUTTON_SORT_BY_TYPE_TOOLTIP,
                "Sort this chest by item type (building, wood, ore, food, ...).");
        this.addRenderableWidget(this.cits$sortByTypeButton);
        }

        if (cits$ui().showSortByCount) {
        this.cits$sortByCountButton = Button.builder(
                OmniChestLocale.get(Keys.BUTTON_SORT_BY_COUNT, "Count"),
                btn -> ContainerSorter.sortByCount(Minecraft.getInstance(), sortMenu, sortSlotCount))
                .bounds(0, 0, 26, 14)
                .build();
        cits$applyTooltip(this.cits$sortByCountButton, Keys.BUTTON_SORT_BY_COUNT_TOOLTIP,
                "Sort this chest by item count, largest stacks first.");
        this.addRenderableWidget(this.cits$sortByCountButton);
        }

        // ◀▶ レイアウト切替ボタンは「小型チェスト」「ラージチェスト」両方で生成する。
        // ラージチェストでは側面パネル全体の左右切替、
        // 小型チェストでは右列ボタン (同種預入/圧縮/倉庫検索/テンプレ系) の左右切替に使われる。
        // 三角記号は文字ではなくレイアウト指示の図形なので翻訳は不要 (= literal を維持)。
        this.cits$layoutLeftButton = Button.builder(
                OmniChestLocale.get(Keys.BUTTON_LAYOUT_LEFT, "◀"),
                btn -> {
                    this.cits$layoutRight = false;
                    this.cits$applyLayout();
                }).bounds(0, 0, 20, 14).build();
        cits$applyTooltip(this.cits$layoutLeftButton, Keys.BUTTON_LAYOUT_LEFT_TOOLTIP,
                "Move the button panel to the left of the chest.");
        this.addRenderableWidget(this.cits$layoutLeftButton);

        this.cits$layoutRightButton = Button.builder(
                OmniChestLocale.get(Keys.BUTTON_LAYOUT_RIGHT, "▶"),
                btn -> {
                    this.cits$layoutRight = true;
                    this.cits$applyLayout();
                }).bounds(0, 0, 20, 14).build();
        cits$applyTooltip(this.cits$layoutRightButton, Keys.BUTTON_LAYOUT_RIGHT_TOOLTIP,
                "Move the button panel to the right of the chest.");
        this.addRenderableWidget(this.cits$layoutRightButton);

        this.cits$applyLayout();

        // 26.2: cits$supportedContainer 確定後にここで GUI スケール clamp を F11 同一経路で再適用する
        //   (= 開時ずれの修正。 詳細は cits$reapplyScaleForContainer / 上の解説コメント参照)。
        //? if >=26.2 {
        /*this.cits$reapplyScaleForContainer();*/
        //?}
    }

    /**
     * 「検索バー + 種類 + 数量 + ◀▶」 上部行に対応する画面 (= ChestMenu / ShulkerBoxMenu) の
     * コンテナ側スロット数を返す。 対応外なら 0 を返して、 上部行生成をスキップさせる。
     *
     * <p>
     * Compact / Deposit / カテゴリ整理 等の側面パネルは {@link DepositMatchingHelper} の
     * containerSlotCount で別途生成判定するため、 ここでは <b>「ChestScreen と同じ
     * 上部行レイアウトを与えて自然な GUI</b> かどうか」 だけを判定する。
     */
    @Unique
    private static int cits$searchRowSlotCount(AbstractContainerMenu menu) {
        if (menu instanceof ChestMenu chest) {
            return chest.getRowCount() * 9;
        }
        if (menu instanceof ShulkerBoxMenu) {
            // シュルカーは常に 27 スロット (= 3x9)、 ChestType.SINGLE と同サイズ扱い。
            return 27;
        }
        return 0;
    }

    // ───────────────────────────────────────────────────────────
    // OmniChestScaledScreen: 高 GUI スケール時の「実効スケールクランプ」 のための必要論理サイズ。
    //
    // {@link com.kajiwara.omnichest.mixin.WindowGuiScaleMixin} がこの画面を開いている間だけ
    // {@code Window#calculateScale} の戻り値を「ここで返す論理幅・高さが収まる最大スケール」 へ
    // 下げる。 これにより高スケールでも UI が余裕レジームに収まり、 低スケールと同じ見た目になる。
    // 必要サイズはチェスト種別 (ラージ / 小型) で imageWidth/imageHeight が異なるため動的算出する。
    // ───────────────────────────────────────────────────────────
    @Override
    public boolean omnichest$wantsScaleClamp() {
        return this.cits$supportedContainer;
    }

    @Override
    public int omnichest$requiredLogicalWidth() {
        // チェスト幅 + 左右の予約幅。 中央寄せチェストの<b>パネル側</b>に、 パネルが緊急クランプ
        // (shiftAside) を起こさず収まる余地を確保できる幅。 panelReserve = GAP + パネル幅 +
        // 背景外周 + 影 + 画面端余白 (= cits$shiftAsideForPanel の needed と同一思想)。
        int panelReserve = CITS_MENU_PANEL_GAP + CITS_MENU_PANEL_WIDTH
                + CITS_PANEL_MARGIN + CITS_PANEL_SHADOW_OVERHANG + CITS_SCREEN_EDGE_PAD;
        return this.imageWidth + 2 * panelReserve;
    }

    @Override
    public int omnichest$requiredLogicalHeight() {
        // チェスト高 + 真上に積む要素の予約高 (中央寄せの上下対称ぶん)。
        // ラージ: 検索バーが側面パネル上端に浮く余地 (= cits$applyLargeRightColumn の searchY)。
        // 小型: バッジ + 検索/種類/数量 行 (= CITS_TOP_STACK_HEIGHT)。 いずれも緊急の上端クランプを
        // 起こさず収まる最小高さ。
        int topReserve = this.cits$isLargeChest
                ? (CITS_MENU_SEARCH_NAV_GAP + CITS_MENU_SEARCH_H + CITS_SCREEN_EDGE_PAD)
                : (CITS_TOP_STACK_HEIGHT + CITS_SCREEN_EDGE_PAD);
        return this.imageHeight + 2 * topReserve;
    }

    /**
     * 高 GUI スケール (= 論理高さが縮む) でチェストの真上に上段 (バッジ + 検索行) を置く縦の余地が
     * 無いとき、 <b>下に余裕がある範囲でチェスト本体を下げて</b>上段を直上に収める。
     *
     * <p>
     * {@code this.topPos} は {@code @Shadow} で書き換え可能。 これを増やすと vanilla の slot 描画 /
     * ホバー判定 / タイトルがすべて topPos 基準で追従するため、 チェスト GUI 全体が一体で下がる
     * (= レイアウト整合は保たれる)。 通常スケール (= 上に十分な余地がある) では {@code deficit <= 0}
     * で<b>何もしない (= topPos 不変 = 見た目不変)</b>。 また下に余裕が無ければ下げない (= 下端の
     * インベントリ/ホットバーを画面外へ押し出さない)。
     *
     * <p>
     * <b>冪等</b>: 一度下げると {@code topPos} が {@code needed} 以上になるため、 同一 init 内で
     * {@link #cits$applyLayout} が複数回 (◀▶ トグル等) 呼ばれても 2 回目以降は no-op。 リサイズ時は
     * vanilla init が topPos を中央へ戻すので、 都度ここで再計算される。
     */
    @Unique
    private void cits$shiftDownForTopRow() {
        if (!this.cits$supportedContainer)
            return;
        // ラージチェストは検索行を側面パネルに持つため、 真上に必要なのはバッジぶんだけ。
        // 小型チェストはバッジ + 検索/種類/数量 行を真上に積むため、より広い余地が要る。
        int topStack = this.cits$isLargeChest ? CITS_LARGE_TOP_STACK_HEIGHT : CITS_TOP_STACK_HEIGHT;
        int needed = topStack + CITS_SCREEN_EDGE_PAD;
        int deficit = needed - this.topPos;
        if (deficit <= 0)
            return; // 既に十分な余地 → 何もしない。
        int roomBelow = this.height - (this.topPos + this.imageHeight) - CITS_SCREEN_EDGE_PAD;
        int shift = Math.min(deficit, Math.max(0, roomBelow));
        if (shift > 0) {
            this.topPos += shift;
        }
    }

    /**
     * 右 (または左) のメインメニュー パネルがチェスト/インベントリと <b>重ならない</b>よう、
     * 必要なら <b>チェスト本体を反対側へ寄せて</b>パネル側の横幅を確保する。
     *
     * <p>
     * <b>根本原因</b>: パネルはチェスト右端 ({@code leftPos + imageWidth + GAP}) に置かれた後、
     * 右端が画面内に収まるよう {@code maxX} へクランプされる。 vanilla チェストは画面中央寄せのため、
     * 高 GUI スケール (= 論理幅が狭い全画面) では中央チェストの右に 146px パネル + GAP を置く余地が
     * 無く、 クランプがパネルを <b>左へ引き込んでチェスト右スロットに重ねて</b>しまう。
     *
     * <p>
     * <b>対処</b>: パネルの配置 (右端アンカー / 幅 / ボタン間隔) は <b>一切変えず</b>、 チェスト本体を
     * {@code @Shadow} の {@code leftPos} 経由で反対側へ寄せて「パネルの左端がチェスト右端から GAP 以上
     * 離れる」だけの横幅を空ける。 {@code leftPos} を動かすと vanilla の slot 描画 / ホバー判定も追従する
     * ため、 チェスト GUI 全体が一体で寄り、 レイアウト整合は保たれる。
     *
     * <p>
     * <b>不変条件</b>: パネルの x / 右端は <b>不変</b> (= 動くのはチェストだけ)。 余地が十分な通常幅
     * (= windowed) では {@code deficit <= 0} で <b>何もしない (= 見た目完全一致)</b>。 反対側に寄せる
     * 余地が無ければ寄せない (= 反対端をはみ出させない)。 冪等 (= 2 回目以降は no-op)、 リサイズ時は
     * vanilla が leftPos を中央へ戻すので都度再計算。
     */
    @Unique
    private void cits$shiftAsideForPanel() {
        if (!this.cits$supportedContainer)
            return;
        // パネル側に確保したい横幅 (= GAP + パネル幅 + 背景外周 + 影 + 画面端余白)。
        // これだけ空いていれば、 右端アンカーされたパネルの左端がチェスト右端から GAP 以上離れる。
        int needed = CITS_MENU_PANEL_GAP + CITS_MENU_PANEL_WIDTH + CITS_PANEL_MARGIN
                + CITS_PANEL_SHADOW_OVERHANG + CITS_SCREEN_EDGE_PAD;
        if (this.cits$layoutRight) {
            // パネルは右 → 右側に needed の余地が要る。 足りなければチェストを左へ寄せる。
            int have = this.width - (this.leftPos + this.imageWidth);
            int deficit = needed - have;
            if (deficit <= 0)
                return;
            int leftRoom = this.leftPos - CITS_SCREEN_EDGE_PAD;
            int shift = Math.min(deficit, Math.max(0, leftRoom));
            if (shift > 0)
                this.leftPos -= shift;
        } else {
            // パネルは左 → 左側に needed の余地が要る。 足りなければチェストを右へ寄せる。
            int have = this.leftPos;
            int deficit = needed - have;
            if (deficit <= 0)
                return;
            int rightRoom = this.width - (this.leftPos + this.imageWidth) - CITS_SCREEN_EDGE_PAD;
            int shift = Math.min(deficit, Math.max(0, rightRoom));
            if (shift > 0)
                this.leftPos += shift;
        }
    }

    @Unique
    private void cits$applyLayout() {
        // 高スケールで上段がチェストから浮かないよう、 必要なら先にチェスト本体を下げる
        // (= 通常時は no-op = 見た目不変)。 以降の配置はすべて新しい topPos を基準にする。
        cits$shiftDownForTopRow();
        // 同様に、 メインメニュー パネルがチェスト/インベントリに重ならないよう、 必要なら
        // チェスト本体を横へ寄せてパネル側の横幅を確保する (= パネルは動かさず、 通常幅では no-op)。
        cits$shiftAsideForPanel();

        // Deposit ボタンの配置 (GUI 右上)。
        // 既存ウィジェット (search / sort) と縦に重ならないよう、
        // ChestScreen のときだけ既存行の上 (topPos - 36) に置く。
        // ShulkerBoxScreen 等のときは GUI 直上 (topPos - 18) に置く。
        cits$applyDepositButtonLayout();

        // 上部行 (検索/ソート/◀▶) を持てる画面でなければ以降は不要。
        // 注意: 検索バーだけ非表示にしても上部行自体は存在し得るので、 searchBox の null ではなく
        // 専用フラグで判定する (= 検索バー非表示でも 種類/数量/◀▶ は正しく配置する)。
        if (!this.cits$hasSearchRow)
            return;

        if (!this.cits$isLargeChest) {
            // 検索バー + 種類 + 数量 を、 上部カテゴリ バッジと<b>同じアンカー</b>
            // ({@code leftPos + CITS_TOP_CONTENT_LEFT}) から左→右へ連結配置する。
            // 各 x は「前要素の右端 + GAP」 で導出し、 マジックオフセット (旧: leftPos+112 / +140)
            // を排除する。 これによりバッジの帯・検索ボックス・種類・数量がすべて同じ縦ラインに乗り、
            // GUI スケールや翻訳長が変わってもアンカー基準で整列が保たれる。
            //   search 106 + gap 2 + type 26 + gap 2 + count 26 = 162 (= 旧来と同じ行末)
            //
            // ─── 短い論理キャンバスでの上端クリップ対策 (= GUI Scale Auto の全画面化) ───
            // 上部行はチェスト GUI 枠の<b>上</b> (topPos - 18) に浮かせる。 高 GUI スケールで論理高さが
            // 縮むと topPos が小さくなり、 topPos - 18 が画面上端 (0) より上に出て検索行が見切れていた。
            // ラージチェスト側 ({@link #cits$applyLargeRightColumn}) と同じ思想で画面内へクランプする
            // (= topPos >= 20 の通常時は従来と完全に同一座標 = 見た目不変。 極小キャンバスでのみ発火)。
            int y = this.topPos - 18;
            if (y < CITS_SCREEN_EDGE_PAD) {
                y = CITS_SCREEN_EDGE_PAD;
            }
            int anchorX = this.leftPos + CITS_TOP_CONTENT_LEFT;

            // 上部行は「見えている要素だけ」 を左→右へ詰める (= 非表示要素の隙間を残さない = #9 reflow)。
            // 各要素は前要素の右端 + GAP に置き、 アンカー基準の整列を保つ。
            int cursor = anchorX;
            if (this.cits$searchBox != null) {
                this.cits$searchBox.setX(cursor);
                this.cits$searchBox.setY(y);
                this.cits$searchBox.setWidth(CITS_SEARCH_BOX_WIDTH);
                cursor += CITS_SEARCH_BOX_WIDTH + CITS_TOP_ROW_GAP;
            }
            if (this.cits$sortByTypeButton != null) {
                this.cits$sortByTypeButton.setX(cursor);
                this.cits$sortByTypeButton.setY(y);
                this.cits$sortByTypeButton.setWidth(CITS_TOP_SORT_BUTTON_WIDTH);
                cursor += CITS_TOP_SORT_BUTTON_WIDTH + CITS_TOP_ROW_GAP;
            }
            if (this.cits$sortByCountButton != null) {
                this.cits$sortByCountButton.setX(cursor);
                this.cits$sortByCountButton.setY(y);
                this.cits$sortByCountButton.setWidth(CITS_TOP_SORT_BUTTON_WIDTH);
                cursor += CITS_TOP_SORT_BUTTON_WIDTH + CITS_TOP_ROW_GAP;
            }

            // ◀▶ レイアウト切替ボタンは、 モックアップ準拠で<b>右側メニューパネルの上端 (ナビ行)</b>
            // へ移動した (= {@link #cits$applyMenuPanel} が配置する)。 上部検索行には
            // 検索バー + 種類 + 数量 のみを残し、 モックアップの「Row 2」 と一致させる。
            // 機能 (= cits$layoutRight トグル) は不変。
            return;
        }

        // ラージ (ダブル) チェスト: モックアップ準拠の<b>右カラム</b>に、 検索バー (全幅) → ◀▶ →
        // 種類 | 数量 (2 列) → アクションパネル (2 列グリッド + 全幅行) を 1 本に縦積みする。
        // 検索行とアクションボタンは Y が連動するため、 1 メソッドで一括配置する
        // (= cits$applyDepositButtonLayout のラージ分岐は何もしない)。
        cits$applyLargeRightColumn();
    }

    /**
     * ラージ (ダブル) チェストの右カラム全体をモックアップ準拠で配置する。
     *
     * <p>
     * 構造 (すべて同じ左端 {@code sideX} / 同じ幅 {@code CITS_MENU_PANEL_WIDTH} に揃える):
     * <ul>
     * <li><b>検索バー</b>: 全幅。 ◀▶ 行の<b>上</b> (= topPos より上、 左の「分類表示」 バッジと同じ
     *     上段) に浮かせる。 上に余白が足りない (= topPos が小さい) ときは画面上端付近に置き、
     *     ◀▶ 以降を下へ押し下げて隙間を確保する (= かぶり防止)。</li>
     * <li><b>◀ | ▶</b>: 2 列。 通常はチェスト GUI 上端 ({@code topPos}) に揃え、 上記の押し下げ時のみ
     *     その下から始める。</li>
     * <li><b>種類 | 数量</b>: 2 列 (見えているものだけ左詰め)。</li>
     * <li><b>アクションパネル</b>: 2 列グリッド (倉庫検索 | カテゴリ整理 / 同種預入 | スタック圧縮) +
     *     全幅行 (配置を保存 / テンプレ適用 / テンプレ管理 / 自動振り分け / カテゴリ設定)。
     *     下端をチェスト GUI 下端 ({@code topPos + imageHeight}) に揃えるため、 行高を区間いっぱいに
     *     分配して充填する ({@link #cits$applyMenuGridAndFullFilled})。</li>
     * </ul>
     *
     * <p>
     * 右端の見切れ防止: パネル幅 + 外周 + 影が画面内に収まるよう x をクランプする (小型と同思想)。
     * 背景パネル ({@link #cits$renderButtonPanel}) はアクションボタン群の union にのみ被さり、
     * 検索バー / ◀▶ / 種類数量 はパネル外に置かれる (= モックアップの暗いパネル領域と一致)。
     * 各セクションの左端・右端・2 列分割点はすべて共通アンカーから導出するので「ライン」 が揃う。
     */
    @Unique
    private void cits$applyLargeRightColumn() {
        final int gap = CITS_MENU_GAP;
        final int width = CITS_MENU_PANEL_WIDTH;
        final int leftW = (width - gap) / 2;
        final int rightW = width - gap - leftW;

        // ─── X 配置 + 画面端クランプ ───
        // 高 GUI スケール / 小ウィンドウだと「leftPos + imageWidth + GAP」 がパネル幅を足すと
        // 画面右外へはみ出し、 右端が見切れる。 背景パネルの外周 ({@link #CITS_PANEL_MARGIN}) +
        // 影 (2px) まで含めて画面内に収まるよう x をクランプする (= 「見切れる」 より 「チェスト枠に
        // 数 px 重なる」 を優先 = 小型チェストと同じ思想)。
        int x = this.cits$layoutRight
                ? this.leftPos + this.imageWidth + CITS_MENU_PANEL_GAP
                : this.leftPos - width - CITS_MENU_PANEL_GAP;
        int edgePad = CITS_SCREEN_EDGE_PAD;
        int shadowOverhang = CITS_PANEL_SHADOW_OVERHANG; // UnifiedPanelRenderer の影 (SHADOW_OFFSET)
        int minX = edgePad + CITS_PANEL_MARGIN;
        int maxX = this.width - edgePad - shadowOverhang - CITS_PANEL_MARGIN - width;
        if (maxX >= minX) {
            x = Math.max(minX, Math.min(x, maxX));
        }
        final int sideX = x;
        final int rightX = sideX + leftW + gap;

        // ─── (1) 検索バー (全幅) + ◀▶ ナビ行の開始 Y ───
        // モックアップでは 検索バーは左の「分類表示」 バッジと同じ<b>上段</b>に居り、 チェスト GUI 枠
        // (= 白 box) の上端には ◀▶ 行が揃う。 そこで検索バーは topPos より上 (= GUI 枠の外側) に浮かせ、
        // ◀▶ は topPos から始める。
        //
        // ただし topPos より上に「検索バー高 + 隙間」 ぶんの余白が無い (= 高 GUI スケール / 小ウィンドウで
        // topPos が小さい) とき、 検索バーを上端でクランプして固定すると ◀▶ とかぶる。 しかも GAP を
        // 増やしても動かない (= 「隙間が反映されない」)。 その場合は検索バーを画面上端付近に置き、
        // ◀▶ 以降を<b>その下へ押し下げて</b> CITS_MENU_SEARCH_NAV_GAP を必ず確保する (= かぶり防止)。
        int navTop = this.topPos;
        if (this.cits$searchBox != null) {
            int searchY = this.topPos - CITS_MENU_SEARCH_NAV_GAP - CITS_MENU_SEARCH_H;
            if (searchY < edgePad) {
                searchY = edgePad;
                navTop = searchY + CITS_MENU_SEARCH_H + CITS_MENU_SEARCH_NAV_GAP;
            }
            cits$placeCell(this.cits$searchBox, sideX, searchY, width, CITS_MENU_SEARCH_H);
        }

        int cursorY = navTop;
        boolean hasHeader = false;

        // ─── (2) ◀ | ▶ ナビ行: チェスト GUI 上端 (topPos) に揃える (= 対応コンテナでは常に存在) ───
        if (this.cits$layoutLeftButton != null && this.cits$layoutRightButton != null) {
            cits$placeCell(this.cits$layoutLeftButton, sideX, cursorY, leftW, CITS_MENU_NAV_H);
            cits$placeCell(this.cits$layoutRightButton, rightX, cursorY, rightW, CITS_MENU_NAV_H);
            cursorY += CITS_MENU_NAV_H + gap;
            hasHeader = true;
        }

        // ─── (3) 種類 | 数量: 2 列 (見えているものだけ左→右で詰める) ───
        Button[] sortRow = { this.cits$sortByTypeButton, this.cits$sortByCountButton };
        int sortCol = 0;
        boolean placedSort = false;
        for (Button b : sortRow) {
            if (b == null) {
                continue;
            }
            if (sortCol == 0) {
                cits$placeCell(b, sideX, cursorY, leftW, CITS_MENU_SORT_H);
                sortCol = 1;
            } else {
                cits$placeCell(b, rightX, cursorY, rightW, CITS_MENU_SORT_H);
                sortCol = 0;
            }
            placedSort = true;
        }
        if (placedSort) {
            cursorY += CITS_MENU_SORT_H + gap;
            hasHeader = true;
        }

        // ─── ヘッダ (検索/ナビ/種類数量) とアクションパネルの間をセクション間隔へ広げる ───
        if (hasHeader) {
            cursorY += CITS_MENU_SECTION_GAP - gap;
        }

        // ─── (4) アクションパネル: 2 列グリッド + 全幅行 ───
        // ボトムをチェスト GUI 下端に揃えるため、 ヘッダ下端から「GUI 下端 - 背景パネル外周」 まで
        // を充填する (= 背景パネル下端が topPos + imageHeight に一致 = モックアップの暗いパネル下端)。
        int actionBottom = this.topPos + this.imageHeight - CITS_PANEL_MARGIN;
        cits$applyMenuGridAndFullFilled(sideX, cursorY, actionBottom, width);
    }

    /**
     * 右側 OmniChest メインメニュー (アクションボタン群) を配置する。
     *
     * <p>
     * 配置先:
     * <ul>
     * <li><b>ラージ (ダブル) チェスト</b> = スコープ外: 既存の側面パネル (◀▶/検索/種類/数量) の
     * 「数量ボタンの下」 に、 数量ボタンと同じ幅 (panelWidth = 80) で<b>従来通りの縦 1 列</b>に配置する。
     * layoutRight に追従して左右どちらの側面でも正しく付く。 このレイアウトは変更しない。</li>
     * <li><b>小型チェスト / エンダーチェスト / シュルカー</b> = 再設計対象:
     * GUI 画像の真横 (右隣、 ◀ 押下時は左隣) に、 モックアップ準拠の専用パネル
     * (ナビ行 ◀▶ → 2 列グリッド → 全幅行) を {@link #cits$applyMenuPanel} で配置する。</li>
     * </ul>
     */
    @Unique
    private void cits$applyDepositButtonLayout() {
        // 対応コンテナでのみ配置する。 個々のボタンは Main Menu Visibility で非表示にでき、
        // その場合 null になるので、 旧来の「depositButton == null で return」 ではなく
        // 専用フラグで判定する (= deposit だけ隠しても他ボタンが消えないように)。
        if (!this.cits$supportedContainer)
            return;

        if (this.cits$isLargeChest) {
            // ラージ (ダブル) チェスト: 右カラム全体 (検索バー / ◀▶ / 種類数量 / アクションパネル) を
            // {@link #cits$applyLargeRightColumn} で一括配置する (= 検索行とアクションボタンの Y が
            // 連動するため、 ここでは何もしない)。 cits$applyLargeRightColumn は cits$applyLayout の
            // ラージ分岐 (この後) から呼ばれる。
            return;
        }

        // 小型チェスト / エンダー / シュルカー (= 非ラージの対応コンテナ): モックアップ準拠の右パネル。
        // ◀▶ で左右切替できる。 x はパネル左端 (layoutRight=false のときは GUI 左隣に反転)。
        // 間隔は CITS_MENU_PANEL_GAP を使う (= 既定 margin より広め)。 背景パネルの外周 + 影が
        // チェスト枠に被らないよう、 GUI 画像から数 px だけ離して配置する。
        int width = CITS_MENU_PANEL_WIDTH;
        int x = this.cits$layoutRight
                ? this.leftPos + this.imageWidth + CITS_MENU_PANEL_GAP
                : this.leftPos - width - CITS_MENU_PANEL_GAP;

        // 画面端クランプ: 高 GUI スケール等で GUI 右 (左) の余白がパネル幅に足りないと、
        // パネルが画面外へはみ出して右端が切れる。 背景パネルはボタン BB より外側へ
        // CITS_PANEL_MARGIN ぶん広がり、 更に {@link UnifiedPanelRenderer} の影が 2px 張り出す
        // ため、 その視覚的な張り出しまで含めて画面内に収まるよう x をクランプする
        // (= 「切れる」 より 「チェスト枠に数 px 重なる」 を優先 = レイアウト破綻防止)。
        int edgePad = CITS_SCREEN_EDGE_PAD;
        int shadowOverhang = CITS_PANEL_SHADOW_OVERHANG; // UnifiedPanelRenderer の影 (SHADOW_OFFSET)
        int minX = edgePad + CITS_PANEL_MARGIN;
        int maxX = this.width - edgePad - shadowOverhang - CITS_PANEL_MARGIN - width;
        if (maxX >= minX) {
            x = Math.max(minX, Math.min(x, maxX));
        }
        // 背景パネルは先頭ボタンより CITS_PANEL_MARGIN だけ上へ張り出す。 先頭 (ナビ行) を
        // topPos そのものに置くと、 パネル上端が topPos - CITS_PANEL_MARGIN まで上にはみ出し、
        // チェストインベントリのアッパーライン (= GUI 枠上端 = topPos) より上にずれて見える。
        // 先頭を CITS_PANEL_MARGIN ぶん下げ、 パネルの視覚的な上端を topPos に揃える。
        cits$applyMenuPanel(x, this.topPos + CITS_PANEL_MARGIN, width);
    }

    /**
     * 小型チェスト系の右側メインメニューを「ナビ行 → 2 列グリッド → 全幅行」 の順で縦に積む。
     *
     * <p>
     * すべて {@code (x, y, width)} アンカー基準で相対配置し、 ハードコード座標は持たない
     * (= GUI スケール / 翻訳長が変わってもズレない)。 各ボタンは Main Menu Visibility で
     * 非表示 (= null) になり得るため、 <b>非 null のものだけ</b> を詰めて隙間を残さない
     * (= reflow)。 グリッドは「左→右、 上→下」 の順で可視ボタンを充填する。
     *
     * @param x     パネル左端の X 座標
     * @param y     パネル上端の Y 座標 (= ナビ行の上端)
     * @param width パネルのコンテンツ幅 (= 全幅行の幅, 2 列グリッドはこれを 2 分割)
     */
    @Unique
    private void cits$applyMenuPanel(int x, int y, int width) {
        final int gap = CITS_MENU_GAP;
        final int leftW = (width - gap) / 2;
        final int rightW = width - gap - leftW;
        final int rightX = x + leftW + gap;
        int cursorY = y;

        // ─── ナビ行: ◀ ▶ (= 既存のレイアウト左右切替トグル) ───
        // パネル幅を 2 分割し、 左半分に ◀、 右半分に ▶ を置く。 機能 (= cits$layoutRight トグル)
        // は不変で、 表示位置だけをパネル上端へ移す。 検索行を全部非表示にしていても ◀▶ は生成
        // されているため (= 対応コンテナでは常に存在)、 ここで配置される。
        if (this.cits$layoutLeftButton != null && this.cits$layoutRightButton != null) {
            cits$placeCell(this.cits$layoutLeftButton, x, cursorY, leftW, CITS_MENU_NAV_H);
            cits$placeCell(this.cits$layoutRightButton, rightX, cursorY, rightW, CITS_MENU_NAV_H);
            cursorY += CITS_MENU_NAV_H + CITS_MENU_SECTION_GAP;
        }

        // ナビ行の下に、 2 列グリッド + 全幅行 (= 共通レイアウタ) を積む。
        cits$applyMenuGridAndFull(x, cursorY, width);
    }

    /**
     * アクションパネル (= 2 列グリッド + 全幅行) を {@code startY} から縦に積む共通レイアウタ。
     * 小型チェスト (ナビ行の下) と ラージチェスト (検索/ナビ/種類数量 ヘッダの下) の<b>両方</b>から
     * 呼ばれ、 同一の見た目・並び順を保証する。
     *
     * <p>
     * 各ボタンは Main Menu Visibility で非表示 (= null) になり得るため、 <b>非 null のものだけ</b> を
     * 詰めて隙間を残さない (= reflow)。 グリッドは「左→右、 上→下」 の順で可視ボタンを充填する。
     *
     * @param x      パネル左端の X 座標
     * @param startY 2 列グリッド先頭行の上端 Y
     * @param width  パネルのコンテンツ幅 (= 全幅行の幅, 2 列グリッドはこれを 2 分割)
     */
    @Unique
    private void cits$applyMenuGridAndFull(int x, int startY, int width) {
        final int gap = CITS_MENU_GAP;
        final int leftW = (width - gap) / 2;
        final int rightW = width - gap - leftW;
        final int rightX = x + leftW + gap;
        int cursorY = startY;

        // ─── 2 列グリッド: 倉庫検索 | カテゴリ整理 / 同種預入 | スタック圧縮 ───
        // モックアップの並び順 (左→右、 上→下) でセルを充填する。 非表示ぶんは左詰めで繰り上がる。
        Button[] grid = {
                this.cits$searchNetworkButton, this.cits$categorySortButton,
                this.cits$depositButton, this.cits$compactButton,
        };
        int col = 0;
        boolean placedGrid = false;
        for (Button b : grid) {
            if (b == null) {
                continue;
            }
            if (col == 0) {
                cits$placeCell(b, x, cursorY, leftW, CITS_MENU_GRID_H);
                col = 1;
            } else {
                cits$placeCell(b, rightX, cursorY, rightW, CITS_MENU_GRID_H);
                cursorY += CITS_MENU_GRID_H + gap;
                col = 0;
            }
            placedGrid = true;
        }
        if (col == 1) {
            // 可視グリッドボタンが奇数 → 左セルだけの行を閉じる。
            cursorY += CITS_MENU_GRID_H + gap;
        }
        if (placedGrid) {
            // グリッド最終行の行間 gap を、 グリッド↔全幅 のセクション間隔へ広げる。
            cursorY += CITS_MENU_SECTION_GAP - gap;
        }

        // ─── 全幅行: 配置を保存 / テンプレ適用 / テンプレ管理 / 自動振り分け / カテゴリ設定 ───
        Button[] full = {
                this.cits$saveTemplateButton,
                this.cits$manageTemplateButton, this.cits$autoDistributeButton,
                this.cits$setCategoryButton,
        };
        for (Button b : full) {
            if (b == null) {
                continue;
            }
            cits$placeCell(b, x, cursorY, width, CITS_MENU_FULL_H);
            cursorY += CITS_MENU_FULL_H + gap;
        }
    }

    /**
     * アクションパネル (= 2 列グリッド + 全幅行) を {@code top}〜{@code bottom} の区間に配置する
     * ラージチェスト専用レイアウタ。
     *
     * <p>
     * <b>方針</b>: ボタン自体は自然な高さ ({@link #CITS_MENU_GRID_H} / {@link #CITS_MENU_FULL_H}) に
     * <b>固定</b>し、 区間に対する余りは<b>行間の余白</b>へ均等に振り分ける (= ボタンを縦に引き伸ばして
     * 圧迫感を出すのを避け、 breathing room を作る)。 1 行間あたりの上乗せは {@link #CITS_MENU_FILL_GAP_MAX}
     * で頭打ちにし、 それ以上の余りは下端の余白として残す (= 可視ボタンが少ないとき行間が間延びしない)。
     * 余りが上限に達しない通常ケースでは、 余白を端数まで配り切るので最終行の下端は {@code bottom} に
     * 揃う (= ボトムのライン揃え)。
     *
     * <p>
     * 並び順・reflow (非表示ぶんを詰める) は {@link #cits$applyMenuGridAndFull} と同一。
     *
     * @param x      パネル左端の X 座標
     * @param top    2 列グリッド先頭行の上端 Y
     * @param bottom 区間の下端 Y (= 余白を配り切れれば最終行の下端がここに揃う)
     * @param width  パネルのコンテンツ幅 (= 全幅行の幅, 2 列グリッドはこれを 2 分割)
     */
    @Unique
    private void cits$applyMenuGridAndFullFilled(int x, int top, int bottom, int width) {
        final int gap = CITS_MENU_GAP;
        final int leftW = (width - gap) / 2;
        final int rightW = width - gap - leftW;
        final int rightX = x + leftW + gap;

        // ─── 可視ボタンを並び順どおりに収集 ───
        java.util.List<Button> gridCells = new java.util.ArrayList<>(4);
        for (Button b : new Button[] { this.cits$searchNetworkButton, this.cits$categorySortButton,
                this.cits$depositButton, this.cits$compactButton }) {
            if (b != null) {
                gridCells.add(b);
            }
        }
        java.util.List<Button> fullRows = new java.util.ArrayList<>(5);
        for (Button b : new Button[] { this.cits$saveTemplateButton,
                this.cits$manageTemplateButton, this.cits$autoDistributeButton,
                this.cits$setCategoryButton }) {
            if (b != null) {
                fullRows.add(b);
            }
        }

        int nGrid = (gridCells.size() + 1) / 2; // 2 列なので行数は切り上げ
        int nFull = fullRows.size();
        if (nGrid == 0 && nFull == 0) {
            return; // 可視アクションボタンが 1 つも無い → 何もしない (背景パネルも描かれない)。
        }

        final int gridH = CITS_MENU_GRID_H;
        final int fullH = CITS_MENU_FULL_H;

        // ─── 余りを行間へ振り分けるための量を確定 ───
        // 行間の数 (= 行数 - 1)。 そのうちグリッド↔全幅の境界 1 つだけ SECTION_GAP を使う。
        int nGaps = (nGrid + nFull) - 1;
        boolean gridFullBoundary = (nGrid > 0 && nFull > 0);
        int buttonsH = nGrid * gridH + nFull * fullH;
        int baseGaps = nGaps * gap + (gridFullBoundary ? (CITS_MENU_SECTION_GAP - gap) : 0);
        int extra = (bottom - top) - (buttonsH + baseGaps);
        // 各行間へ配る余白の総量 (1 行間あたり CITS_MENU_FILL_GAP_MAX で頭打ち)。 残りは下端余白。
        int bonusTotal = (extra > 0 && nGaps > 0)
                ? Math.min(extra, nGaps * CITS_MENU_FILL_GAP_MAX)
                : 0;

        int y = top;
        int gapIndex = 0; // 何番目の行間か (端数 +1px を先頭側へ寄せるため)

        // (a) 2 列グリッド行
        for (int row = 0; row < nGrid; row++) {
            cits$placeCell(gridCells.get(row * 2), x, y, leftW, gridH);
            int rightIdx = row * 2 + 1;
            if (rightIdx < gridCells.size()) {
                cits$placeCell(gridCells.get(rightIdx), rightX, y, rightW, gridH);
            }
            y += gridH;
            boolean lastGridRow = (row == nGrid - 1);
            if (!lastGridRow) {
                y += gap + cits$gapBonus(bonusTotal, nGaps, gapIndex++);
            } else if (nFull > 0) {
                y += CITS_MENU_SECTION_GAP + cits$gapBonus(bonusTotal, nGaps, gapIndex++);
            }
        }

        // (b) 全幅行
        for (int row = 0; row < nFull; row++) {
            cits$placeCell(fullRows.get(row), x, y, width, fullH);
            y += fullH;
            if (row < nFull - 1) {
                y += gap + cits$gapBonus(bonusTotal, nGaps, gapIndex++);
            }
        }
    }

    /**
     * {@code total} px を {@code count} 個の行間へできるだけ均等に配ったときの、
     * {@code index} 番目 (0 始まり) の取り分を返す。 端数 (= {@code total % count}) は先頭側の
     * 行間へ 1px ずつ寄せるので、 総和は厳密に {@code total} になる (= 配り残し / 過剰なし)。
     */
    @Unique
    private static int cits$gapBonus(int total, int count, int index) {
        if (count <= 0) {
            return 0;
        }
        return total / count + (index < total % count ? 1 : 0);
    }

    /** ウィジェットの位置・サイズを 1 行で設定するユーティリティ (= グリッド配置の可読性のため)。 */
    @Unique
    private static void cits$placeCell(AbstractWidget w, int x, int y, int width, int height) {
        w.setX(x);
        w.setY(y);
        w.setWidth(width);
        w.setHeight(height);
    }
}
