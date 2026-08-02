package com.kajiwara.omnichest.client.gui;

import com.kajiwara.omnichest.client.gui.search.DisplayModeDropdown;
import com.kajiwara.omnichest.client.gui.search.FavoriteInteractionHandler;
import com.kajiwara.omnichest.client.gui.search.FavoritesManager;
import com.kajiwara.omnichest.client.gui.search.ItemDisplayMode;
import com.kajiwara.omnichest.client.gui.search.LocalizationBridge;
import com.kajiwara.omnichest.client.gui.search.SearchCategory;
import com.kajiwara.omnichest.client.gui.search.SearchCategoryManager;
import com.kajiwara.omnichest.client.gui.search.SearchCategoryTab;
import com.kajiwara.omnichest.client.gui.search.StorageSearchListRenderer;
import com.kajiwara.omnichest.client.gui.search.layout.LayoutBox;
import com.kajiwara.omnichest.client.gui.search.layout.SearchScreenLayout;
import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.client.gui.search.layout.TooltipPlacementHelper;
import com.kajiwara.omnichest.client.gui.search.layout.UILayoutMetrics;
import com.kajiwara.omnichest.client.gui.search.preview.AdaptiveTooltipPositioner;
import com.kajiwara.omnichest.client.gui.search.preview.AltPreviewPopupRenderer;
import com.kajiwara.omnichest.search.nested.RecursiveContainerHelper;
import com.kajiwara.omnichest.client.render.ChestHighlighter;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.config.data.SearchConfig;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.search.ChestNetworkManager;
import com.kajiwara.omnichest.search.ContainerSnapshot;
import com.kajiwara.omnichest.search.SearchIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「Chest Network Search」のメイン GUI。
 *
 * <p>
 * <b>レイアウト方針 (4 原則)</b>:
 * <ul>
 *   <li><b>近接</b>: 検索ボックスと sort ボタンを 1 行に。 アクション群 (Find / Clear) と
 *       Display Mode は離して配置 (= 別グループ)。 タブ列とリストは {@link UILayoutMetrics#SECTION_GAP}
 *       で区切る。</li>
 *   <li><b>整列</b>: すべての y / x は {@link SearchScreenLayout} が 1 か所で計算。
 *       Screen 側は座標を生成しない。</li>
 *   <li><b>反復</b>: ボタン高 / padding / gap は {@link UILayoutMetrics} の定数を共有。</li>
 *   <li><b>コントラスト</b>: 色 / アニメ / テーマは既存維持。 情報階層は距離で表現。</li>
 * </ul>
 *
 * <p>
 * <b>非破壊原則 (継続)</b>: 既存の検索ロジック・ピン座標・Overlay 描画・Search Engine・
 * 操作感 (Find Selected / Clear Selection / 行クリック / スクロール) は全て維持する。
 */
public class SearchScreen extends Screen {

    private final Screen parent;

    private EditBox searchBox;

    /** 直近検索結果 (= フィルタ前)。 init / クエリ変更 / ソート変更で再構築する。 */
    private List<SearchIndex.SearchResult> baseResults = new ArrayList<>();
    /** カテゴリタブ + お気に入りソートを適用した「描画用ビュー」。 */
    private List<SearchIndex.SearchResult> results = new ArrayList<>();

    private SortMode sortMode = SortMode.DISTANCE;

    private double scrollPx = 0.0;
    private boolean draggingScroll = false;
    private double scrollDragOffsetY = 0.0;

    /** タブ列の縦スクロール量 (= 単一列タブが strip より長い時のスクロール)。 */
    private double tabScrollPx = 0.0;
    private boolean draggingTabScroll = false;
    private double tabScrollDragOffsetY = 0.0;

    /** 選択中の行データ。 行 = チェスト × アイテム種 × 階層、 LinkedHashMap で順序安定。 */
    private final Map<String, SelectedRow> selectedRows = new LinkedHashMap<>();

    /**
     * Shift 範囲選択のアンカー行キー ({@link #makeRowKey})。 通常クリックのたびに更新し、
     * Shift+クリック時は「アンカー〜クリック」 の範囲を選択する。 <b>行キーで保持</b>するため
     * フィルタ / ソート / スクロールで表示順が変わっても、 Shift 時に現在位置を再計算できる。
     * null = アンカー未設定 (= 最初の Shift+クリックは通常クリック扱い)。
     */
    private String selectionAnchorKey = null;

    private record SelectedRow(ContainerSnapshot snapshot, ItemStack stack, int count,
                               List<ItemStack> containerPath) {
    }

    private static String makeRowKey(SearchIndex.SearchResult r) {
        ContainerSnapshot.Key c = r.snapshot().key();
        // 「同じチェスト × 同じアイテム ID × 同じ Data Components × 同じネスト経路」 を
        // ユニーク識別する。
        //
        // <p>
        // <b>旧実装のバグ</b>: 鍵に {@link ItemStack#toString} を使っていたが、 これは
        // {@code "count itemId"} だけで Data Components を含まないため、 Sharpness V と
        // Sharpness IV の同一 ID のソードが同じ鍵に潰れて 1 行扱い (= 一括選択) になっていた。
        // 装備全般 / ポーション / エンチャ本 / カスタム名 / 染色 / 旗パターン 等の
        // components 違いアイテムで再現する一般バグだった。
        //
        // <p>
        // <b>修正</b>: {@link ItemStack#hashItemAndComponents} は アイテム ID と
        // 全 Data Components を畳んだ 32bit ハッシュを返す。 異なる components のスタックは
        // ほぼ確実に異なる値になるため、 装備違い / 効果違い / 名前違い を個別行として扱える。
        // ネスト経路の各コンテナにも同じハッシュを使うことで、 染色違いシュルカーも別経路に分かれる。
        StringBuilder path = new StringBuilder();
        for (ItemStack cont : r.containerPath()) {
            path.append('/').append(ItemStack.hashItemAndComponents(cont));
        }
        return c.dimension() + "|" + c.pos()
                + "|" + ItemStack.hashItemAndComponents(r.stack())
                + "|" + path;
    }

    // ─── 拡張 UI 状態 ───────────────────────────────────────────────
    private SearchCategory currentCategory = SearchCategory.ALL;
    private ItemDisplayMode displayMode = ItemDisplayMode.DETAILED;
    private List<SearchCategoryTab.TabHit> tabHits = new ArrayList<>();
    /** Display Mode の popup (開いていれば描画 + クリック吸収)。 */
    private DisplayModeDropdown displayDropdown = null;

    /** init() で算出されたレイアウト一式 (= 各 widget の位置)。 */
    private SearchScreenLayout layout;

    // ───────────────────────────────────────────────────────────
    // 「ALT + ホイールクリック」 で出す <b>固定 (sticky)</b> シュルカープレビュー。
    //
    // 既存の ALT ホバー Tooltip ({@link AltPreviewTooltip}) は ALT を離した瞬間に消えるため、
    // 「中身を見ながらマウスを動かす / クリックする」 ことができない。
    // ALT + 中ボタン (= ホイール押下) クリックを 1 つの「ピン留め」 トリガとして使い、
    // 同じプレビュー描画 ({@link AltPreviewPopupRenderer}) を <b>離してもそのまま表示</b> させる。
    //
    // 動作:
    //   - ALT + 中ボタン (button = 2) で行のアイテム (= 通常はシュルカー) を pin → stickyPreview 設定
    //   - 同じ行を ALT + 中ボタンで再度クリック / ESC / シュルカー以外をクリック → 解除
    //   - 描画は ALT ホバーと同じパス (= テーマ統一 + フェード考慮なし = 不動の安定描画)
    // ───────────────────────────────────────────────────────────
    /** 現在 pin 中のプレビュー対象スタック。 null = 非表示。 */
    private ItemStack stickyPreviewStack = ItemStack.EMPTY;
    /** プレビュー表示のアンカ (= クリックした位置)。 ALT を離してもここに固定。 */
    private int stickyPreviewAnchorX = 0;
    private int stickyPreviewAnchorY = 0;

    /**
     * 直近に {@code render()} が受け取ったマウス座標。 キーボードショートカット
     * (ALT+D など) の hit-test 用に保持する。 keyPressed は mouseX/Y を引数で受け取らないため、
     * render 経由で更新される最新値を参照するのが最も低コスト (= mouseHandler の毎フレーム再計算を回避)。
     */
    private int lastMouseX = -1;
    private int lastMouseY = -1;

    public SearchScreen(Screen parent) {
        super(OmniChestLocale.get(Keys.SCREEN_SEARCH_TITLE, "Chest Network Search"));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(new SearchScreen(parent));*/
        //?} else {
        Minecraft.getInstance().setScreen(new SearchScreen(parent));
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
        SearchConfig cfg = ConfigManager.get().search;
        this.displayMode = (cfg.defaultDisplayMode != null) ? cfg.defaultDisplayMode : ItemDisplayMode.DETAILED;
        // ─── Sort モードの永続復元 ───
        // ユーザが前回画面を閉じる前に選んでいたソート順 (= 距離 / 数量 / 名前) を
        // {@code cfg.resultSortMode} から戻す。 これにより「チェストを閉じて再度開く度に距離順に
        // リセットされる」 旧挙動 (= 苛立たしいデフォルト戻り) を排除し、 検索ワークフローの連続性
        // を保つ (= 「あなたの好みを覚えている UI」)。
        this.sortMode = sortModeFromConfig(cfg.resultSortMode);

        // ─── ラベルを先に解決してから layout に渡す (= 翻訳長で幅が変わるため) ───
        Component sortDistanceLabel = OmniChestLocale.get(Keys.BUTTON_SORT_DISTANCE, "By Distance");
        Component sortCountLabel = OmniChestLocale.get(Keys.BUTTON_SORT_COUNT, "By Count");
        Component sortNameLabel = OmniChestLocale.get(Keys.BUTTON_SORT_NAME, "By Name");
        Component findSelectedLabel = OmniChestLocale.get(Keys.BUTTON_SEARCH_SELECTED, "Find Selected");
        Component clearSelectionLabel = OmniChestLocale.get(Keys.BUTTON_CLEAR_SELECTION, "Clear Selection");
        Component displayModeLabel = Component.literal("▼ ").append(this.displayMode.displayName());
        Component searchHint = OmniChestLocale.get(Keys.EDITBOX_SEARCH_HINT_NETWORK,
                "Search (e.g. diamond, food, mekanism)");

        // タブ列幅は翻訳済みラベルから動的に算出 (= 言語別に最適化)。
        List<SearchCategory> visibleCats = visibleCategories(cfg);
        this.layout = SearchScreenLayout.compute(this.width, this.height, this.font,
                0,
                searchHint,
                new Component[]{sortDistanceLabel, sortCountLabel, sortNameLabel},
                new Component[]{findSelectedLabel, clearSelectionLabel},
                displayModeLabel,
                visibleCats);

        // ─── 検索ボックス ─────────────────────────────────────────
        this.searchBox = new EditBox(this.font, layout.searchBox.x(), layout.searchBox.y(),
                layout.searchBox.w(), layout.searchBox.h(),
                OmniChestLocale.get(Keys.EDITBOX_SEARCH_LABEL, "Search"));
        this.searchBox.setMaxLength(64);
        // <b>Hint テキストはボックス幅に収まるよう動的に切り詰める</b>。
        // 旧仕様 (2 行レイアウト) では検索バーが画面幅いっぱい (= 約 250px) あったため
        // 「Search (e.g. diamond, food, mekanism)」 のような長い hint が収まっていたが、
        // 単行レイアウト化で SearchBox 幅が狭くなった結果、 hint が右側 (種類順 / 詳細ボタン) に
        // <b>はみ出して描画</b> されてしまっていた。 EditBox の drawHint は scissor も clip もしないため、
        // 「収まる長さ」 まで <em>呼び出し側</em> で短縮する必要がある。
        this.searchBox.setHint(cits$fitHintToWidth(searchHint, layout.searchBox.w()));
        this.searchBox.setResponder(text -> rebuildResults());
        this.addRenderableWidget(this.searchBox);
        this.setInitialFocus(this.searchBox);

        // ─── Sort ボタン (3 つ) ───────────────────────────────────
        // 押下時に sortMode を更新するだけでなく、 {@link #applyAndPersistSort} 経由で
        // {@code SearchConfig.resultSortMode} に書き戻し → 永続化する。 これにより
        // 「チェストを閉じても次回起動でも同じソート順」 が保たれる。
        this.addRenderableWidget(Button.builder(sortDistanceLabel,
                b -> applyAndPersistSort(SortMode.DISTANCE))
                .bounds(layout.sortDistanceBtn.x(), layout.sortDistanceBtn.y(),
                        layout.sortDistanceBtn.w(), layout.sortDistanceBtn.h()).build());

        this.addRenderableWidget(Button.builder(sortCountLabel,
                b -> applyAndPersistSort(SortMode.COUNT))
                .bounds(layout.sortCountBtn.x(), layout.sortCountBtn.y(),
                        layout.sortCountBtn.w(), layout.sortCountBtn.h()).build());

        this.addRenderableWidget(Button.builder(sortNameLabel,
                b -> applyAndPersistSort(SortMode.NAME))
                .bounds(layout.sortNameBtn.x(), layout.sortNameBtn.y(),
                        layout.sortNameBtn.w(), layout.sortNameBtn.h()).build());

        // ─── アクションボタン ─────────────────────────────────────
        // <b>Find Selected</b>: 単行レイアウトの「検索ボタン」 (mockup 左端)。 選択中アイテムを
        // ワールド上でハイライトしつつ GUI を閉じる primary action。
        //
        // <b>スタイル</b>: 設定画面フッタの「Save」 と同じ {@link NavyFooterButton} を採用
        // (= 紺塗り + 黄金枠 + BOLD + ホバーで色反転)。 これにより:
        //   - 「重要操作 (= Save / Find)」 は MOD 内で同じ見た目に統一 = Repetition の徹底。
        //   - 検索 GUI 内の他ボタン (= バニラ素地) に対しても主従が一目で分かる = Contrast の強化。
        // サイズは layout 側で modeW より広く保証済み (= BUTTON_PRIMARY_CTA_MIN_WIDTH)。
        com.kajiwara.omnichest.config.gui.widget.NavyFooterButton findBtn =
                new com.kajiwara.omnichest.config.gui.widget.NavyFooterButton(
                        layout.findSelectedBtn.x(), layout.findSelectedBtn.y(),
                        layout.findSelectedBtn.w(), layout.findSelectedBtn.h(),
                        findSelectedLabel,
                        b -> highlightSelectedAndClose());
        // <b>Tooltip 無し</b>: ラベルが常時可視 (= ボタン幅で必ず収まる) なので、 hover で
        // 同じ文字列をもう一度出すのは冗長 = ユーザ要件で削除済み。 他ボタンの tooltip は維持。
        this.addRenderableWidget(findBtn);

        // <b>Clear Selection</b>: 可視 UI から取り除いた (mockup に存在しない、 単行レイアウトの
        // 圧迫を避けるため)。 同等機能はフッターで明示済みの <b>ALT + S ショートカット</b> で実行できる
        // (= keyPressed の selectedRows.clear() に集約)。 翻訳ラベル
        // {@code clearSelectionLabel} はフッター行の hint で「ALT + S = Clear selection」
        // として依然ユーザに見えているので、 ラベル自体の翻訳は引き続き活きる。
        @SuppressWarnings("unused") Component _keepClearLabel = clearSelectionLabel;

        // ─── Display Mode ボタン ──────────────────────────────────
        this.addRenderableWidget(Button.builder(displayModeLabel,
                b -> openDisplayDropdown(b.getX(), b.getY() + b.getHeight()))
                .bounds(layout.displayModeBtn.x(), layout.displayModeBtn.y(),
                        layout.displayModeBtn.w(), layout.displayModeBtn.h()).build());

        rebuildResults();
    }

    private void openDisplayDropdown(int anchorX, int anchorBottomY) {
        this.displayDropdown = new DisplayModeDropdown(
                this.displayMode,
                m -> {
                    this.displayMode = m;
                    SearchConfig cfg = ConfigManager.get().search;
                    if (cfg.rememberLastDisplayMode) {
                        cfg.defaultDisplayMode = m;
                        ConfigManager.save();
                    }
                    // 表示モード変更: スクロールは同じ index 位置へ寄せ直し
                    clampScroll();
                    // ボタンラベルを更新するため layout 再計算
                    this.rebuild();
                },
                anchorX, anchorBottomY,
                this.width, this.height);
    }

    /** ラベル変化 (= Display Mode 切替) 等に応じて widget を作り直す。 */
    private void rebuild() {
        this.clearWidgets();
        init();
    }

    /**
     * 画面リサイズ (F11 全画面トグル / GUI スケール変更 / 解像度変更) ハンドラ。
     *
     * <p>
     * {@code super.resize()} が {@link #init()} を呼び直し、 {@link SearchScreenLayout#compute} が
     * 生きた {@code this.width/height} から全 widget 座標 (検索ボックス / ソート / リスト / タブ列) を
     * 再計算するため、 Screen 本体のレイアウトは自動で追従する。
     *
     * <p>
     * ただし widget ツリー外で保持している <b>一時オーバーレイ状態</b> は init() では触られないため、
     * ここで明示的に破棄する。 いずれも開いた時点の<b>絶対座標</b>に紐づくため、 リサイズ後は意味を失う:
     * <ul>
     *   <li>{@link #displayDropdown}: 「Display Mode ▼」 ボタン直下に出る anchor 追従型。 ボタンが
     *       リサイズで動くため、 残すと古い位置に取り残される → null 化して閉じる。</li>
     *   <li>{@link #stickyPreviewStack} / anchor: ALT+ホイールクリックで pin した中身プレビュー。
     *       anchor はクリック時の絶対マウス座標なので、 リサイズ後は元の意味を失う → 解除する
     *       (画面端クランプで見切れはしないが、 pin 位置の意味が崩れるため畳むのが自然)。</li>
     * </ul>
     */
    @Override
    public void resize(int w, int h) {
        this.displayDropdown = null;
        this.stickyPreviewStack = ItemStack.EMPTY;
        super.resize(w, h);
    }

    private void highlightSelectedAndClose() {
        for (SelectedRow sr : this.selectedRows.values()) {
            if (sr.containerPath() != null && !sr.containerPath().isEmpty()) {
                // 階層 (= シュルカー内) アイテム: チェスト → シュルカー → アイテム の段階ハイライト。
                com.kajiwara.omnichest.client.render.NestedHighlightRenderer.highlight(
                        sr.snapshot(), sr.stack(), sr.count(), sr.containerPath());
            } else if (sr.snapshot().type() == com.kajiwara.omnichest.search.ContainerType.ENDER_CHEST) {
                // エンダーチェスト内のヒット: 全エンダーチェストは同一インベントリを共有するため、
                // 現在ディメンションの全エンダーチェストをハイライトする (= どこからでも取り出せる)。
                ChestHighlighter.get().highlightAllEnderChests(sr.stack(), sr.count());
            } else {
                // トップレベル: 既存挙動を維持。
                ChestHighlighter.get().highlight(sr.snapshot(), sr.stack(), sr.count());
            }
            FavoritesManager.get().touch(sr.stack());
        }
        this.onClose();
    }

    private void rebuildResults() {
        SearchConfig cfg = ConfigManager.get().search;
        String query = this.searchBox == null ? "" : this.searchBox.getValue();
        List<SearchIndex.SearchResult> raw = SearchIndex.search(query);
        switch (this.sortMode) {
            case DISTANCE -> this.baseResults = SearchIndex.sortByDistance(raw);
            case COUNT -> this.baseResults = SearchIndex.sortByCount(raw);
            case NAME -> this.baseResults = SearchIndex.sortByName(raw);
        }
        List<SearchIndex.SearchResult> filtered = cfg.enableCategoryTabs
                ? SearchCategoryManager.get().filter(this.baseResults, this.currentCategory)
                : this.baseResults;

        if (cfg.enableFavorites && cfg.favoriteSortMode != null) {
            FavoritesManager fav = FavoritesManager.get();
            filtered = switch (cfg.favoriteSortMode) {
                case "favorites_first" -> fav.sortFavoritesFirst(filtered);
                case "recently_used" -> fav.sortRecentlyUsed(filtered);
                case "most_searched" -> fav.sortMostSearched(filtered);
                default -> filtered;
            };
        }
        this.results = filtered;
        clampScroll();
    }

    private int contentHeight() {
        if (this.layout == null) return 0;
        return StorageSearchListRenderer.computeContentHeight(this.displayMode,
                this.results.size(), this.layout.list.w());
    }

    /**
     * スクロール最大時に <b>最終行と下端フレームの間に確保する</b> 追加余白 (px)。
     *
     * <p>
     * 選択行のハイライト (= 行全体を覆う黄色 tint) が黄色フレームに張り付いて見えないよう、
     * フレーム厚 ({@link UILayoutMetrics#LIST_FRAME_THICKNESS} = 1px) を超える視覚的な
     * 余裕を持たせるための値。 値が大きいほど下方向のクリア スペースが広がる
     * (= 4 原則 「コントラスト・近接」 の観点で、 行ハイライトと枠を視覚的に分離する)。
     */
    private static final int LIST_BOTTOM_BREATHING_ROOM = 6;

    /**
     * フッターヒントを 1 行に収めるための最小縮小率。
     * <p>
     * 1 行の合計幅が画面幅を超える狭い論理キャンバスでは、 折り返さずにこの下限までテキストを
     * 縮小して 1 行を維持する (= ユーザ要件「下部の説明は 1 行」)。
     */
    private static final float FOOTER_MIN_SCALE = 0.5f;

    /**
     * 「実際にコンテンツを描画して見せられる」 垂直方向の有効ビューポート高さ。
     *
     * <p>
     * リスト矩形 ({@link #layout.list}) の高さから:
     * <ul>
     *   <li>上下の黄色フレーム ({@link UILayoutMetrics#LIST_FRAME_THICKNESS} × 2)</li>
     *   <li>下端の追加余白 ({@link #LIST_BOTTOM_BREATHING_ROOM})</li>
     * </ul>
     * を差し引いた値。 スクロール量 / scrollbar の handle 比率はすべてこの値を基準にする
     * (= 旧実装は素の {@code list.h()} を使っていたため、 最終行の下端が黄色フレームに
     * 隠れる「下端見切れ」 が発生していた)。
     *
     * <p>
     * 下端の追加余白により、 スクロール最大時に最終行の選択ハイライトと黄色フレームの間に
     * クリア スペースが残り、 視認性が向上する (= ハイライトの四角が枠に張り付かない)。
     */
    private int contentViewportHeight() {
        if (this.layout == null) return 0;
        return Math.max(0, this.layout.list.h()
                - 2 * UILayoutMetrics.LIST_FRAME_THICKNESS
                - LIST_BOTTOM_BREATHING_ROOM);
    }

    private void clampScroll() {
        if (this.layout == null) return;
        double maxScroll = Math.max(0, contentHeight() - contentViewportHeight());
        if (this.scrollPx < 0) this.scrollPx = 0;
        if (this.scrollPx > maxScroll) this.scrollPx = maxScroll;
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // キーボードショートカット (= ALT+D 等) 用に直近マウス座標を覚えておく。
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        // ─── ALT 押下中は検索バーを「無効化」 する ───
        // ALT は本画面で「インスペクション修飾キー」 (= ホバーで詳細 / ALT+W で全選択 /
        // ALT+S で選択解除 / ALT+中ボタンでシュルカープレビュー) に再定義されている。
        // この間に検索バーが入力フォーカスを維持していると、 ALT+W/S 以外のキー (ALT+E, ALT+R…)
        // やフォーカスを掴んだままの IME 変換などが、 意図しない文字として検索バーに紛れ込む
        // 余地が残る。 setEditable(false) を毎フレーム同期することで:
        //   - キー入力は EditBox 側で拒否される (= 入力統合の二重ガード)
        //   - EditBox は disabled 時に色を落として描画 → ユーザに「今は打てない」 と明示
        // フォーカス自体は失わせない (= ALT を離した瞬間にそのまま続行できる UX)。
        // {@code super.render} がウィジェットを描く<b>前</b>に状態を同期するのが重要
        // (= 描画直前の状態が反映される / 1 フレームの遅延を作らない)。
        if (this.searchBox != null) {
            this.searchBox.setEditable(!isAltDown());
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Font font = this.font;
        SearchConfig cfg = ConfigManager.get().search;

        // ─── ヘッダ (= タイトル + 統計を 1 行で並べる) ─────────────────────────────
        // <b>レイアウト</b>: 最左端 (LTR) / 最右端 (RTL) にタイトル、 反対端に統計テキスト (= テンプレート管理画面と統一)。
        // 旧実装はタイトル中央 / 統計左寄せで配置していたが、 新レイアウト (= mockup 準拠) では
        // タイトル「倉庫検索 (見出し)」 と 統計「ヒット/選択/総スタック数」 が
        // <b>同一の水平線上</b> で「タイトル中央〜やや左 / 統計右」 と分かれている。
        // 統計は数値情報 = 右寄せが視線移動として自然 (= 横書き言語で「結果は右で確認」 慣習)。
        //
        // <b>タイトルは bold で描画</b>: 統計テキスト (= dim, regular) との視覚的階層を作り、
        // 「画面の主役は何か」 を一目で伝える (= 4 原則の Contrast)。 bold は <em>描画時に
        // 適用</em> するため、 翻訳ファイル側で {@code §l} 等のフォーマットコードを書く必要がない
        // (= 全言語に自動で乗る = 翻訳者の作業 0)。
        boolean rtl = LocalizationBridge.isRtl();
        // ─── タイトル (= LTR では最左端、 RTL では最右端 ── テンプレート管理画面と同じ寄せ方) ──
        Component boldTitle = this.getTitle().copy().withStyle(net.minecraft.ChatFormatting.BOLD);
        if (rtl) {
            g.text(font, boldTitle,
                    this.width - UILayoutMetrics.SCREEN_INSET_X - font.width(boldTitle),
                    UILayoutMetrics.SCREEN_INSET_TOP, ThemeColorResolver.TEXT_PRIMARY, true);
        } else {
            g.text(font, boldTitle, UILayoutMetrics.SCREEN_INSET_X,
                    UILayoutMetrics.SCREEN_INSET_TOP, ThemeColorResolver.TEXT_PRIMARY, true);
        }

        // ─── サマリ (= LTR では右、 RTL では左 ── mockup 準拠) ──
        int total = ChestNetworkManager.get().size();
        Component summary = OmniChestLocale.get(Keys.SEARCH_SUMMARY,
                "Registered: %1$d  /  Hits: %2$d  /  Selected: %3$d",
                total, this.results.size(), this.selectedRows.size());
        // ─── 狭い幅での「タイトルと統計が被る」 対策 ───
        // タイトル (左/右端) と統計 (反対端) が 1 行で両端寄せされるため、 GUI Scale Auto の全画面化で
        // 論理幅が縮むと中央で衝突して文字が重なっていた。 両者 + inset + 最小間隔が画面幅に収まる
        // ときだけ統計を描画する (= 通常幅は従来どおり両方表示 = 見た目不変。 収まらない極小幅では
        // 主役のタイトルを優先し、 副次的な統計を伏せて重なりを防ぐ。 統計は検索すれば再表示される)。
        int summaryW = font.width(summary);
        int titleW = font.width(boldTitle);
        boolean headerFits = titleW + summaryW + 2 * UILayoutMetrics.SCREEN_INSET_X + 6 <= this.width;
        if (headerFits) {
            if (rtl) {
                // RTL: 統計を画面左に貼り付け (= タイトルの「外側」 が 統計、 という対称性を保つ)。
                g.text(font, summary, UILayoutMetrics.SCREEN_INSET_X,
                        UILayoutMetrics.SCREEN_INSET_TOP, ThemeColorResolver.TEXT_SECONDARY, false);
            } else {
                g.text(font, summary, this.width - UILayoutMetrics.SCREEN_INSET_X - summaryW,
                        UILayoutMetrics.SCREEN_INSET_TOP, ThemeColorResolver.TEXT_SECONDARY, false);
            }
        }

        // ─── カテゴリタブ列 (= 左側固定 / RTL は右側) ───────────────
        LayoutBox selectedBox = null;
        if (cfg.enableCategoryTabs) {
            LayoutBox strip = this.layout.tabStrip;
            // パネル背景 (= list と同系色だが少しコントラスト)
            g.fill(strip.x(), strip.y(), strip.right(), strip.bottom(),
                    ThemeColorResolver.CATEGORY_PANEL_BG);
            List<SearchCategory> visible = visibleCategories(cfg);
            clampTabScroll(visible, cfg.compactTabMode);
            this.tabHits = SearchCategoryTab.extractRenderState(g, mouseX, mouseY,
                    strip, this.currentCategory, visible, cfg.compactTabMode, this.tabScrollPx);

            // タブ列のスクロールバー描画 (= 中身が strip 高さを超える時のみ)
            renderTabScrollbar(g, strip, visible, cfg.compactTabMode);
            selectedBox = findSelectedTabBox(this.tabHits, this.currentCategory);
        } else {
            this.tabHits = new ArrayList<>();
        }

        // ─── 結果リスト ───────────────────────────────────────────
        renderList(g, mouseX, mouseY, cfg);

        // ─── 黄色フレーム (= list 上に描画して背景に上書きされないようにする) ───
        LayoutBox stripForFrame = cfg.enableCategoryTabs ? this.layout.tabStrip : null;
        drawYellowConnectingFrame(g, stripForFrame, this.layout.list, selectedBox, this.layout.rtl);

        // ─── フッターヒント ───────────────────────────────────────
        // 3 つのショートカット説明を 1 行に並べる。 「4 デザイン原則」 を遵守:
        //   - Proximity:  「キー = 動作」のペアを 1 単位として近接配置。 グループ間は広めの gap。
        //   - Repetition: 全グループで同じフォーマット「<キー> = <短い動詞句>」 + 同じ色。
        //   - Alignment:  全体を中央寄せに 1 行で横並び (= ベースラインも自動で揃う)。
        //   - Contrast:   テキストは TEXT_DIM (= 控えめ) で本文より暗く描画 = 補助情報であることを示す。
        // 翻訳長が増えても、 各テキストは個別に font.width で測ってから幅を確定するため
        // 折り返しなしで自然に収まる (= レイアウト固定値に依存しない)。
        cits$renderFooterHints(g);

        // ─── Display Mode dropdown (overlay) ───
        if (this.displayDropdown != null) {
            if (this.displayDropdown.isClosed()) {
                this.displayDropdown = null;
            } else {
                this.displayDropdown.extractRenderState(g, mouseX, mouseY);
            }
        }

        // ─── 固定 (sticky) シュルカープレビュー (= ALT + ホイールクリックで pin した中身) ───
        // dropdown の <b>後</b>、 ALT ホバー Tooltip の <b>前</b> に描画する:
        //   - dropdown より手前 = 「上に開いた dropdown が pin を隠す」 のを避ける
        //   - ALT Tooltip より奥 = ALT 押下中はバニラ詳細ツールチップが優先 (= ALT 同時押し時に
        //     片方だけ見せる必要が無いよう、 詳細を見たいユーザの意図を妨げない)
        if (!this.stickyPreviewStack.isEmpty()) {
            renderStickyPreview(g, cfg);
        }

        // ─── ALT ホバー: アイテムリスト行に対する vanilla Item Tooltip ───
        // ALT を押している間、 リスト内でホバー中の検索結果行のアイテムについて、
        // バニラのアイテムツールチップ (Advanced Tooltip 含む / カスタム名 / エンチャ / 効果) を
        // 1 フレーム遅延描画で表示する。
        // 制約:
        //  - dropdown 表示中はユーザーが選択操作中なので出さない。
        //  - hitTest が -1 を返す場合 (= マウスがリスト外, 例えばタブ上) は出さない。
        //  - 既存のタブ Tooltip ロジックとは独立 (= タブ上では tooltip = タブ名のまま)。
        if (this.displayDropdown == null && isAltDown()) {
            int hoveredIdx = StorageSearchListRenderer.hitTest(this.displayMode, this.results,
                    this.layout.list.x(), this.layout.list.y(),
                    this.layout.list.right(), this.layout.list.bottom(),
                    this.scrollPx, mouseX, mouseY);
            if (hoveredIdx >= 0 && hoveredIdx < this.results.size()) {
                ItemStack hoveredStack = this.results.get(hoveredIdx).stack();
                if (!hoveredStack.isEmpty()) {
                    g.setComponentTooltipForNextFrame(this.font,
                            Screen.getTooltipFromItem(Minecraft.getInstance(), hoveredStack),
                            mouseX, mouseY);
                }
            }
        }

        // ─── タブホバー Tooltip (= 非選択タブの名前) ───
        // 縦並びタブなので tooltip は list 側 (= anchor の横) に出す。 上下に出すと他タブが隠れる。
        if (cfg.enableCategoryTabs) {
            SearchCategoryTab.TabHit hovered = SearchCategoryTab.hoveredHit(this.tabHits, mouseX, mouseY);
            if (hovered != null && hovered.cat != this.currentCategory) {
                int[] pos = TooltipPlacementHelper.preferSide(font, hovered.cat.displayName(),
                        hovered.box, this.width, this.height);
                g.setComponentTooltipForNextFrame(font, java.util.List.of(hovered.cat.displayName()),
                        pos[0], pos[1]);
            }
        }
    }

    /** 現在選択タブの描画矩形を返す (= 「繋がる」演出用に list 側でも参照する)。 */
    private LayoutBox findSelectedTabBox(List<SearchCategoryTab.TabHit> hits, SearchCategory current) {
        for (SearchCategoryTab.TabHit h : hits) {
            if (h.cat == current) return h.box;
        }
        return null;
    }

    /**
     * <b>分離レイアウト用</b>の黄色アクセント描画。
     * <ul>
     *   <li>list: 全 4 辺を細い黄色枠 ({@link UILayoutMetrics#LIST_FRAME_THICKNESS}) で囲む</li>
     *   <li>選択タブ: 外側エッジ (= 反 list 側) に <b>太い</b> 黄色ライン
     *       ({@link UILayoutMetrics#TAB_SELECTED_OUTER_LINE}) を引く</li>
     *   <li>タブと list は <b>分離</b>。 連結のための塗りつぶしは行わない</li>
     * </ul>
     */
    private static void drawYellowConnectingFrame(GuiGraphicsExtractor g, LayoutBox strip, LayoutBox list,
                                                  LayoutBox selectedBox, boolean rtl) {
        int frame = ThemeColorResolver.TAB_ACTIVE_LINE; // 黄色 (= 0xFFFFD700)
        int lt = UILayoutMetrics.LIST_FRAME_THICKNESS;

        // list の細い枠 (= 4 辺)
        g.fill(list.x(), list.y(), list.right(), list.y() + lt, frame);
        g.fill(list.x(), list.bottom() - lt, list.right(), list.bottom(), frame);
        g.fill(list.x(), list.y(), list.x() + lt, list.bottom(), frame);
        g.fill(list.right() - lt, list.y(), list.right(), list.bottom(), frame);

        // 選択タブ: 外側エッジに太いライン (= list の細枠より目立たせる)
        if (strip != null && selectedBox != null) {
            int outerY1 = Math.max(selectedBox.y(), strip.y());
            int outerY2 = Math.min(selectedBox.bottom(), strip.bottom());
            if (outerY2 > outerY1) {
                int at = UILayoutMetrics.TAB_SELECTED_OUTER_LINE;
                if (rtl) {
                    // RTL: 外側 = 右側
                    int x = selectedBox.right() - at;
                    g.fill(x, outerY1, x + at, outerY2, frame);
                } else {
                    // LTR: 外側 = 左側
                    int x = selectedBox.x();
                    g.fill(x, outerY1, x + at, outerY2, frame);
                }
            }
        }
    }

    /** タブ列の中身高さがストリップを超える時、 scrollPx を [0, max] に丸める。 */
    private void clampTabScroll(List<SearchCategory> visible, boolean compactAlways) {
        int contentH = SearchCategoryTab.computeContentHeight(this.font, this.layout.tabStrip,
                visible, this.currentCategory, compactAlways);
        int viewH = this.layout.tabStrip.h();
        double max = Math.max(0, contentH - viewH);
        if (this.tabScrollPx < 0) this.tabScrollPx = 0;
        if (this.tabScrollPx > max) this.tabScrollPx = max;
    }

    /** タブ列のスクロールバー描画 (= strip の <b>外側</b> に独立配置)。 */
    private void renderTabScrollbar(GuiGraphicsExtractor g, LayoutBox strip,
                                    List<SearchCategory> visible, boolean compactAlways) {
        int contentH = SearchCategoryTab.computeContentHeight(this.font, strip,
                visible, this.currentCategory, compactAlways);
        int viewH = strip.h();
        if (contentH <= viewH) return;
        LayoutBox sbBox = this.layout.tabScrollbar;
        int trackTop = sbBox.y() + 1;
        int trackBottom = sbBox.bottom() - 1;
        int trackH = trackBottom - trackTop;
        int thumbH = Math.max(16, (int) ((long) viewH * viewH / contentH));
        int maxScroll = contentH - viewH;
        int thumbY = trackTop + (int) ((this.tabScrollPx / maxScroll) * (trackH - thumbH));
        g.fill(sbBox.x(), trackTop, sbBox.right(), trackBottom, ThemeColorResolver.SCROLLBAR_TRACK);
        int color = this.draggingTabScroll
                ? ThemeColorResolver.SCROLLBAR_THUMB_DRAG
                : ThemeColorResolver.SCROLLBAR_THUMB;
        g.fill(sbBox.x(), thumbY, sbBox.right(), thumbY + thumbH, color);
    }

    /** マウスがタブ列のスクロールバー領域上にあるか。 */
    private boolean isMouseOverTabScrollbar(double mouseX, double mouseY) {
        if (this.layout == null) return false;
        LayoutBox sbBox = this.layout.tabScrollbar;
        return mouseX >= sbBox.x() - 2 && mouseX <= sbBox.right() + 2
                && mouseY >= sbBox.y() && mouseY <= sbBox.bottom();
    }

    /** マウスがタブ列領域 (= strip) 上にあるか (= wheel 操作の判定用)。 */
    private boolean isMouseOverTabStrip(double mouseX, double mouseY) {
        if (this.layout == null) return false;
        return this.layout.tabStrip.contains(mouseX, mouseY)
                || this.layout.tabScrollbar.contains(mouseX, mouseY);
    }

    private List<SearchCategory> visibleCategories(SearchConfig cfg) {
        List<SearchCategory> all = new ArrayList<>(Arrays.asList(SearchCategory.values()));
        if (!cfg.enableFavorites) all.remove(SearchCategory.FAVORITES);
        return all;
    }

    private void renderList(GuiGraphicsExtractor g, int mouseX, int mouseY, SearchConfig cfg) {
        LayoutBox list = this.layout.list;
        int left = list.x();
        int top = list.y();
        int right = list.right();
        int bottom = list.bottom();

        // 背景パネル (= 設定画面と統一されたテーマ色)。
        // 上下境界線は描かない — 黄色の外枠が後段で上書き描画される。
        g.fill(left, top, right, bottom, ThemeColorResolver.LIST_BG);

        g.enableScissor(left, top + 1, right, bottom - 1);
        try {
            if (this.currentCategory == SearchCategory.FAVORITES && this.results.isEmpty()) {
                Component msg = LocalizationBridge.favoritesEmpty();
                int tw = this.font.width(msg);
                g.text(this.font, msg, list.centerX() - tw / 2,
                        list.centerY() - this.font.lineHeight / 2, ThemeColorResolver.TEXT_SECONDARY, false);
            } else {
                FavoritesManager fav = FavoritesManager.get();
                StorageSearchListRenderer.render(this.displayMode, g, this.results,
                        left, top, right, bottom, this.scrollPx,
                        mouseX, mouseY,
                        r -> this.selectedRows.containsKey(makeRowKey(r)),
                        r -> cfg.enableFavorites && fav.isFavorite(r.stack()),
                        cfg.enableFavorites && cfg.favoriteHighlight);
            }
        } finally {
            g.disableScissor();
        }

        renderScrollbar(g, top, bottom);
    }

    private void renderScrollbar(GuiGraphicsExtractor g, int top, int bottom) {
        int contentH = contentHeight();
        int viewH = bottom - top;
        if (contentH <= viewH) return;
        int barX = scrollbarBarX();
        int barH = scrollbarHandleHeight();
        int barY = scrollbarHandleY();
        g.fill(barX, top, barX + UILayoutMetrics.SCROLLBAR_WIDTH, bottom, ThemeColorResolver.SCROLLBAR_TRACK);
        int handleColor = this.draggingScroll
                ? ThemeColorResolver.SCROLLBAR_THUMB_DRAG
                : ThemeColorResolver.SCROLLBAR_THUMB;
        g.fill(barX, barY, barX + UILayoutMetrics.SCROLLBAR_WIDTH, barY + barH, handleColor);
    }

    // ════════════════════════════════════════════════════════════════════
    // スクロールバー: 形状計算 (既存仕様維持 / 定数化のみ)
    // ════════════════════════════════════════════════════════════════════

    private int scrollbarBarX() {
        return this.layout.list.right() - UILayoutMetrics.SCROLLBAR_WIDTH;
    }

    private int scrollbarHandleHeight() {
        int contentH = contentHeight();
        int viewH = contentViewportHeight();
        if (contentH <= viewH) return 0;
        // handle 大きさ = 「見える割合 × トラック全長」。 トラックはフレーム込みのリスト全高
        // (= 視覚的にバーがリスト両端まで届く) を採用、 比率は有効ビューポートを基準。
        int trackH = this.layout.list.h();
        return Math.max(20, (int) ((long) trackH * viewH / contentH));
    }

    private int scrollbarHandleY() {
        int barH = scrollbarHandleHeight();
        int viewH = contentViewportHeight();
        int contentH = contentHeight();
        if (barH == 0) return this.layout.list.y();
        int maxScroll = contentH - viewH;
        int trackH = this.layout.list.h();
        return this.layout.list.y() + (int) ((this.scrollPx / maxScroll) * (trackH - barH));
    }

    private boolean isMouseOverScrollbar(double mouseX, double mouseY) {
        if (scrollbarHandleHeight() == 0) return false;
        int barX = scrollbarBarX();
        return mouseX >= (barX - UILayoutMetrics.SCROLLBAR_HIT_MARGIN)
                && mouseX <= (barX + UILayoutMetrics.SCROLLBAR_WIDTH + UILayoutMetrics.SCROLLBAR_HIT_MARGIN)
                && mouseY >= this.layout.list.y()
                && mouseY <= this.layout.list.bottom();
    }

    private void setScrollFromHandleTopY(double desiredHandleTopY) {
        int barH = scrollbarHandleHeight();
        int contentH = contentHeight();
        int viewH = contentViewportHeight();
        if (barH == 0 || contentH <= viewH) return;
        int trackH = this.layout.list.h();
        int trackRange = trackH - barH;
        if (trackRange <= 0) return;
        double frac = (desiredHandleTopY - this.layout.list.y()) / trackRange;
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        int maxScroll = contentH - viewH;
        this.scrollPx = frac * maxScroll;
        clampScroll();
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // ─── Display Mode dropdown のクリック処理 (= 「Find Selected が反応しない」 Bug 修正) ───
        //
        // <b>旧仕様の問題</b>: dropdown が開いている間、 dropdown.mouseClicked() を <em>すべての</em>
        // クリックに対して呼び、 戻り値が常に true なので Screen 側の {@code super.mouseClicked}
        // (= ボタン群へのディスパッチ) が走らず、 Find Selected が押せない時間帯ができていた。
        // ユーザ報告 「Search ボタンが時々無反応」 の主因 (= 直前に Display Mode を開いた状態でクリックすると再現)。
        //
        // <b>修正</b>:
        //   - クリック座標が dropdown popup の <b>内側</b> → これまで通り dropdown が処理 (= 項目選択)
        //   - クリック座標が popup の <b>外側</b> → dropdown を閉じる <em>だけ</em>。 戻り値で
        //     consume しない (= 同じクリックが super.mouseClicked にも届き、 ボタン押下が成立する)
        //
        // これで「dropdown 開いた状態で Find Selected を 1 回押す = dropdown 閉じる + Find Selected 発火」
        // という直感的な 1-click 動作になる (= 2 回押す必要が無くなる)。
        if (this.displayDropdown != null && !this.displayDropdown.isClosed()) {
            if (this.displayDropdown.contains(event.x(), event.y())) {
                // 内側クリック: dropdown が消費。
                if (this.displayDropdown.mouseClicked(event.x(), event.y(), event.button())) {
                    if (this.displayDropdown.isClosed()) this.displayDropdown = null;
                    return true;
                }
            } else {
                // 外側クリック: dropdown を閉じるだけで、 クリックは下のウィジェットへ素通り。
                this.displayDropdown.mouseClicked(event.x(), event.y(), event.button());
                this.displayDropdown = null;
                // ← return しない: そのまま下の処理 (= タブ判定 / ボタン押下 / 行クリック) へ続行。
            }
        }

        SearchConfig cfg = ConfigManager.get().search;

        // カテゴリタブクリック処理
        if (cfg.enableCategoryTabs && event.button() == 0
                && SearchCategoryTab.handleClick(this.tabHits, event.x(), event.y(),
                cat -> {
                    this.currentCategory = cat;
                    rebuildResults();
                    // タブ折り返しが変化することがあるので layout を作り直す
                    this.rebuild();
                })) {
            return true;
        }

        if (super.mouseClicked(event, doubleClick)) return true;

        double mouseX = event.x();
        double mouseY = event.y();

        // タブ列のスクロールバードラッグ開始
        if (event.button() == 0 && isMouseOverTabScrollbar(mouseX, mouseY)) {
            this.draggingTabScroll = true;
            this.tabScrollDragOffsetY = 0; // 簡易: スクロール量を直接マウス位置から計算
            updateTabScrollFromMouse(mouseY);
            return true;
        }

        // スクロールバー
        if (event.button() == 0 && isMouseOverScrollbar(mouseX, mouseY)) {
            int handleY = scrollbarHandleY();
            int handleH = scrollbarHandleHeight();
            if (mouseY >= handleY && mouseY < handleY + handleH) {
                this.draggingScroll = true;
                this.scrollDragOffsetY = mouseY - handleY;
            } else {
                setScrollFromHandleTopY(mouseY - handleH / 2.0);
                this.draggingScroll = true;
                this.scrollDragOffsetY = handleH / 2.0;
            }
            return true;
        }

        // 行クリック判定
        LayoutBox list = this.layout.list;
        if (mouseX < list.x() || mouseX > list.right()
                || mouseY < list.y() || mouseY > list.bottom()) {
            // リスト外の左クリックは sticky preview を解除する (= 通常 GUI の「ダイアログ外をクリックで閉じる」 慣習)。
            // 中ボタン / 右クリックは別系統 (= スクロール / 既存ハンドラ) なので解除しない。
            if (event.button() == 0 && !this.stickyPreviewStack.isEmpty()) {
                this.stickyPreviewStack = ItemStack.EMPTY;
            }
            return false;
        }

        int index = StorageSearchListRenderer.hitTest(this.displayMode, this.results,
                list.x(), list.y(), list.right(), list.bottom(), this.scrollPx, mouseX, mouseY);
        if (index < 0) {
            // リスト矩形内で行の隙間 (= 行 hit ミス) を左クリックしたケースも、 上と同様に解除する。
            if (event.button() == 0 && !this.stickyPreviewStack.isEmpty()) {
                this.stickyPreviewStack = ItemStack.EMPTY;
            }
            return false;
        }

        SearchIndex.SearchResult clicked = this.results.get(index);

        // ─── ホイールクリック (= 中ボタン) でシュルカー中身プレビューを <b>純粋トグル</b> ───
        // 旧仕様は ALT+ホイールクリックの組合せだったが、 「ALT を押している間しか効かない」 ことが
        // トグルとして自然でない (= 開く/閉じるという 2 状態の切替に修飾キーは要らない)。
        // 新仕様: ホイールクリック単独 (= 修飾なし) で開閉:
        //   - 同じ行を再度ホイールクリック → 閉じる (OFF)
        //   - 別の行をホイールクリック → そっちに切替 (= 内容更新)
        //   - 通常アイテム (= 中身を持たない) なら何もしない (= 誤動作で空 popup を出さない)
        // ホイールクリックはバニラ MC 検索 GUI で別用途に当たっていないので、
        // この単独入力で奪っても既存挙動に衝突しない (= 行クリック左/右、 スクロール ホイール は無傷)。
        if (event.button() == 2) {
            ItemStack rowStack = clicked.stack();
            if (!rowStack.isEmpty() && RecursiveContainerHelper.isContainerItem(rowStack)) {
                boolean sameAsPinned = !this.stickyPreviewStack.isEmpty()
                        && ItemStack.isSameItemSameComponents(this.stickyPreviewStack, rowStack);
                if (sameAsPinned) {
                    this.stickyPreviewStack = ItemStack.EMPTY;
                } else {
                    this.stickyPreviewStack = rowStack.copy();
                    this.stickyPreviewAnchorX = (int) Math.round(mouseX);
                    this.stickyPreviewAnchorY = (int) Math.round(mouseY);
                }
            }
            // ホイールクリックは行選択 / お気に入り には流さない (= consume)。
            return true;
        }

        // ─── クリック種別判定 (= FavoriteInteractionHandler に集約) ───
        FavoriteInteractionHandler.ClickKind kind = FavoriteInteractionHandler.classify(event, cfg);
        boolean continueAsSelect = FavoriteInteractionHandler.handle(clicked, kind);
        if (kind == FavoriteInteractionHandler.ClickKind.TOGGLE_FAVORITE) {
            rebuildResults();
            return true;
        }
        if (!continueAsSelect) {
            // IGNORE (= middle click 等) は何もせず consume だけ。
            return true;
        }

        String key = makeRowKey(clicked);

        // ─── Shift+クリック = アンカーからの範囲選択 (Windows Explorer 準拠・純追加) ───
        // 通常クリック挙動は不変。 Shift が押されていて有効なアンカーが現在の表示リストにあるときだけ、
        // アンカー〜クリック行の範囲で選択を「置換」する。 アンカーは行キーで保持するため、
        // フィルタ / ソート / スクロールをまたいでも表示順どおりに範囲が取れる (= キーで現在位置を再計算)。
        // アンカーが絞り込みで消えている / 未設定なら通常クリック扱いへフォールバックする。
        if (isShiftDown() && this.selectionAnchorKey != null) {
            int anchorIdx = indexOfRowKey(this.selectionAnchorKey);
            if (anchorIdx >= 0) {
                applyRangeSelection(anchorIdx, index, cfg);
                // アンカーは動かさない (= 同一アンカーから再 Shift+クリックで範囲を伸縮できる)。
                return true;
            }
        }

        // 通常クリック = 行選択トグル (既存仕様維持) + アンカー更新 (= 次の範囲選択の起点)。
        if (this.selectedRows.containsKey(key)) {
            this.selectedRows.remove(key);
        } else {
            this.selectedRows.put(key,
                    new SelectedRow(clicked.snapshot(), clicked.stack().copy(), clicked.count(),
                            clicked.containerPath()));
            if (cfg.enableFavorites) FavoritesManager.get().touch(clicked.stack());
        }
        this.selectionAnchorKey = key;
        return true;
    }

    /**
     * 現在の表示リスト {@link #results} から行キーの位置を線形探索する (= 表示順の連番インデックス)。
     * 見つからなければ -1 (= アンカーが絞り込みで消えている)。
     */
    private int indexOfRowKey(String rowKey) {
        for (int i = 0; i < this.results.size(); i++) {
            if (makeRowKey(this.results.get(i)).equals(rowKey)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * アンカー〜クリックの範囲 (表示順の {@code [min..max]}) で選択を<b>置換</b>する。
     *
     * <p>
     * グリッド表示 ({@link ItemDisplayMode#isGrid()}) でも {@link #results} は<b>行優先</b>で並ぶため、
     * 線形インデックス範囲がそのまま行優先の範囲になる (= Explorer の Shift 範囲と同じ意味)。
     * 作用は通常クリックの選択と<b>同一</b> (= {@link #selectedRows} へ put + favorites.touch) を
     * 範囲内の各要素へ適用するだけで、 新しい作用は発明しない。 一括 put は
     * {@link #selectAllVisibleResults()} と同じ O(N) で既存の性能範囲に収まる。
     */
    private void applyRangeSelection(int anchorIdx, int clickIdx, SearchConfig cfg) {
        int from = Math.min(anchorIdx, clickIdx);
        int to = Math.max(anchorIdx, clickIdx);
        this.selectedRows.clear();
        FavoritesManager fav = cfg.enableFavorites ? FavoritesManager.get() : null;
        for (int i = from; i <= to; i++) {
            SearchIndex.SearchResult r = this.results.get(i);
            this.selectedRows.put(makeRowKey(r),
                    new SelectedRow(r.snapshot(), r.stack().copy(), r.count(), r.containerPath()));
            if (fav != null) fav.touch(r.stack());
        }
    }

    /**
     * SHIFT (左右いずれか) が押されているか。
     * 既存の {@link #isAltDown()} と同じ {@link InputConstants} 経由方式 (= マッピング差異吸収済み)。
     */
    private static boolean isShiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.draggingTabScroll) {
            updateTabScrollFromMouse(event.y());
            return true;
        }
        if (this.draggingScroll) {
            setScrollFromHandleTopY(event.y() - this.scrollDragOffsetY);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && this.draggingTabScroll) this.draggingTabScroll = false;
        if (event.button() == 0 && this.draggingScroll) this.draggingScroll = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) return true;
        // タブ列上でホイールを回した場合: タブ列をスクロール
        if (isMouseOverTabStrip(mouseX, mouseY)) {
            this.tabScrollPx -= dy * (UILayoutMetrics.TAB_HEIGHT + UILayoutMetrics.TAB_GAP);
            SearchConfig cfg = ConfigManager.get().search;
            clampTabScroll(visibleCategories(cfg), cfg.compactTabMode);
            return true;
        }
        this.scrollPx -= dy * this.displayMode.rowHeight() * 2;
        clampScroll();
        return true;
    }

    /** タブ列スクロールバーの thumb 位置をマウス Y から更新する。 */
    private void updateTabScrollFromMouse(double mouseY) {
        SearchConfig cfg = ConfigManager.get().search;
        List<SearchCategory> visible = visibleCategories(cfg);
        int contentH = SearchCategoryTab.computeContentHeight(this.font,
                this.layout.tabStrip, visible, this.currentCategory, cfg.compactTabMode);
        int viewH = this.layout.tabStrip.h();
        if (contentH <= viewH) return;
        int thumbH = Math.max(16, (int) ((long) viewH * viewH / contentH));
        int trackTop = this.layout.tabScrollbar.y() + 1;
        int trackH = (this.layout.tabScrollbar.bottom() - 1) - trackTop;
        double frac = (mouseY - trackTop - thumbH / 2.0) / (trackH - thumbH);
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        this.tabScrollPx = frac * (contentH - viewH);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.displayDropdown != null && !this.displayDropdown.isClosed()) {
            if (this.displayDropdown.keyPressed(event.key())) {
                if (this.displayDropdown.isClosed()) this.displayDropdown = null;
                return true;
            }
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            // ESC は「外側 → 内側」 で段階的にキャンセル: sticky preview が出ているなら
            // 先にそれを閉じ、 画面ごと閉じない (= バニラ寄りの 1 ステップ ESC 慣習)。
            if (!this.stickyPreviewStack.isEmpty()) {
                this.stickyPreviewStack = ItemStack.EMPTY;
                return true;
            }
            return super.keyPressed(event);
        }

        // ─── ALT 押下中の入力ガード ───
        // ALT 修飾下のキー入力は <b>全て</b> 画面側で処理し、 SearchBox へは一切流さない。
        // 既知ショートカット (ALT+W / ALT+S / ALT+D) は所定の動作を実行、 それ以外 (ALT+任意) は
        // 「何もしないが consume」 する (= ALT+E 等で検索バーに余計な文字が入るのを防ぐ)。
        // 修飾なしの W / S / D は従来どおり SearchBox に流れ、 検索クエリへの通常タイプとして扱う。
        if (isAltDown()) {
            int k = event.key();
            if (k == GLFW.GLFW_KEY_W) {
                selectAllVisibleResults();
                return true;
            }
            if (k == GLFW.GLFW_KEY_S) {
                this.selectedRows.clear();
                this.selectionAnchorKey = null;   // 全解除でアンカーもリセット (= 次の Shift は通常クリック扱い)。
                return true;
            }
            if (k == GLFW.GLFW_KEY_D) {
                deselectHoveredRow();
                return true;
            }
            // ALT 修飾下の他のキー (= 文字キー / 矢印 etc.) は SearchBox に届けず黙って consume。
            // ALT 自体の押下イベント (= LALT/RALT を押した瞬間) も consume されるが、
            // 修飾キーは「離した時に何かする」 性質のものではないので副作用なし。
            return true;
        }

        // Tab はフォーカス移動として画面 (Screen) 側に通す。 Esc は上で処理済み。
        if (event.key() != GLFW.GLFW_KEY_TAB
                && this.searchBox != null && this.searchBox.canConsumeInput()) {
            // 検索欄にフォーカスがある間は、 EditBox が処理しなかったキーも <b>消費</b> する。
            // 素通りさせると mod / バニラの他ハンドラに届いてしまう (= タイプ中の暴発)。
            this.searchBox.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * 文字入力イベントは {@code charTyped} 経由で来る (= IME / dead key / 多バイト文字)。
     * ALT 押下中は SearchBox に文字を流さず、 ここで黙って消費する。
     *
     * <p>
     * {@link #keyPressed} ガードだけだと、 OS の IME や一部キーボードレイアウトで
     * 「keyPressed は ALT 抑止できても charTyped は素通り」 のケースが残るため、
     * 両方の入口でガードを掛けて整合性を取る。
     *
     * <p>
     * <b>シグネチャ</b>: 1.21.11 から {@code charTyped(char, int)} は
     * {@code charTyped(CharacterEvent)} に置き換わっている (= modifier 等の追加情報が
     * 1 つのイベントオブジェクトに纏まった)。 ここでも新シグネチャで override する。
     */
    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (isAltDown()) {
            return true; // 黙って consume
        }
        return super.charTyped(event);
    }

    /**
     * 現在 GUI に表示されている全結果 (= 「カテゴリ + お気に入りフィルタを通過した」 セット) を
     * 一括で選択状態に追加する (= 「ALT + W」 の動作)。
     *
     * <p>
     * 「baseResults」 ではなく {@link #results} を対象にするのが重要: ユーザが目で見て
     * 「今リストに出ているもの」 が「全て」 の対象。 カテゴリ絞り込み中なら絞り込み後の集合だけ、
     * フィルタ無しなら全件 — どちらも操作モデルとして自然 (= "Select what I see")。
     *
     * <p>
     * 既存選択は維持し、 重複しないように同じ {@code makeRowKey} で put する (= 既に選択中の
     * 行は上書きされるだけで効果は変わらない / 順序も既存のものを優先 = LinkedHashMap)。
     * Favorites の recency 更新は触らない (= 個別クリックと違って一括選択は閲覧寄りの操作で、
     * 「使った印 (= touch)」 を全件にばら撒くと、 後で recency ベースの並び替えが破綻するため)。
     */
    private void selectAllVisibleResults() {
        for (SearchIndex.SearchResult r : this.results) {
            String key = makeRowKey(r);
            if (this.selectedRows.containsKey(key)) continue;
            this.selectedRows.put(key,
                    new SelectedRow(r.snapshot(), r.stack().copy(), r.count(),
                            r.containerPath()));
        }
    }

    /**
     * 「カーソル下の行」 を選択状態 + ワールド上のピンの両方から取り除く (= ALT+D の動作)。
     *
     * <p>
     * <b>動作モデル</b>: 1 行 = 1 検索ヒット (= 「指定コンテナ × 指定アイテム」) なので、
     * ALT+D 1 回で次の 2 処理を同時に行う:
     * <ol>
     *   <li>{@link #selectedRows} から行を削除 (= 次回 Find Selected で pin され直さなくする)。</li>
     *   <li>{@link ChestHighlighter#removeItemForSnapshot} で <b>既に pin 中</b> の同行を世界から消す
     *       (= 「以前 Find Selected を押した結果」 のピンも 1 行ぶん取り除く)。</li>
     * </ol>
     * 両者は独立して処理されるため、 staging だけある / pin だけある / 両方ある の 3 状態すべてで
     * 期待通り動く (= idempotent)。
     *
     * <p>
     * <b>カーソルが行外にある時は no-op</b> (= 誤発火を防ぐ)。 リスト上にホバーしている時のみ、
     * 直近の {@link #lastMouseX} / {@link #lastMouseY} を使って row index を引く。
     */
    private void deselectHoveredRow() {
        if (this.layout == null) return;
        LayoutBox list = this.layout.list;
        if (this.lastMouseX < list.x() || this.lastMouseX > list.right()
                || this.lastMouseY < list.y() || this.lastMouseY > list.bottom()) {
            return; // リスト外 → no-op
        }
        int idx = StorageSearchListRenderer.hitTest(this.displayMode, this.results,
                list.x(), list.y(), list.right(), list.bottom(),
                this.scrollPx, this.lastMouseX, this.lastMouseY);
        if (idx < 0 || idx >= this.results.size()) return;

        SearchIndex.SearchResult target = this.results.get(idx);

        // (1) staging selection から除去 (= 同 row key で put 済みなら remove)。
        this.selectedRows.remove(makeRowKey(target));

        // (2) ワールド上のピン (= ChestHighlighter.active) から該当行を狙い撃ち削除。
        ChestHighlighter.get().removeItemForSnapshot(target.snapshot().key(), target.stack());
    }

    /** フッターに出す固定 4 件の ALT ヒント。 */
    private Component[] cits$footerHints() {
        return new Component[]{
                OmniChestLocale.get(Keys.SEARCH_HINT,
                        "ALT = Item details"),
                OmniChestLocale.get(Keys.SEARCH_HINT_SELECT_ALL,
                        "ALT + W = Select all"),
                OmniChestLocale.get(Keys.SEARCH_HINT_CLEAR_SELECTION,
                        "ALT + S = Clear selection"),
                OmniChestLocale.get(Keys.SEARCH_HINT_DESELECT_HOVERED,
                        "ALT + D = Deselect hovered"),
        };
    }

    /** フッターヒントのグループ間 spacing (= ペア外余白)。 半角スペース 4 つぶん相当。 */
    private int cits$footerHintGap() {
        return this.font.width("    ");
    }

    /**
     * フッターのショートカットヒントを <b>常に 1 行</b>で描画する。
     *
     * <p>
     * 4 つの「キー = 動作」 ペアを 1 行に中央寄せで並べる。 ペア間は gap (= グループ間 spacing) で
     * 区切り、 ペア内は空白 1 つ (= proximity)。
     *
     * <p>
     * <b>狭い幅 (= GUI Scale Auto の全画面化で論理幅が縮む) での扱い</b>: 1 行の合計幅が画面に
     * 収まらない場合、 <b>折り返さずにテキスト全体を中央基準で縮小</b>して 1 行に押し込む
     * (= ユーザ要件「下部の説明は 1 行」)。 内容は削らず、 ボタン・リストとも重ならない。
     * 収まる通常幅では等倍で <b>従来と完全に同一</b>の描画になる。
     */
    private void cits$renderFooterHints(GuiGraphicsExtractor g) {
        Component[] hints = cits$footerHints();
        int gap = cits$footerHintGap();
        int color = ThemeColorResolver.TEXT_DIM;
        int lineH = this.font.lineHeight;

        // 1 行での総幅 (= 各ヒント幅 + (n-1) gap)。
        int[] widths = new int[hints.length];
        int totalW = 0;
        for (int i = 0; i < hints.length; i++) {
            widths[i] = this.font.width(hints[i]);
            totalW += widths[i];
        }
        totalW += gap * (hints.length - 1);

        int y = this.layout.footerHint.y();
        int padX = 6;
        int padY = 2;
        int avail = this.width - 2 * UILayoutMetrics.SCREEN_INSET_X;

        if (totalW <= avail || totalW <= 0) {
            // ─── 等倍 1 行 (= 従来と完全一致) ───
            // 中央寄せの開始 X。 1 px 単位の整数で確定 (= 浮動小数による滲み回避)。
            int startX = (this.width - totalW) / 2;
            g.fill(startX - padX, y - padY, startX + totalW + padX, y + lineH + padY,
                    ThemeColorResolver.FOOTER_BACKDROP);
            int cursor = startX;
            for (int i = 0; i < hints.length; i++) {
                // footer は subtle なので shadow=false (= 既存の TEXT_DIM と同じ控えめ表現)。
                g.text(this.font, hints[i], cursor, y, color, false);
                cursor += widths[i];
                if (i < hints.length - 1) cursor += gap;
            }
            return;
        }

        // ─── 縮小 1 行 (= 収まらない幅): 中央基準でスケールして 1 行に押し込む ───
        // 下限 {@link #FOOTER_MIN_SCALE} で潰れすぎを防ぐ (= それ未満になる極端な幅では端で切れるが、
        // 折り返して 2 段になる崩れよりは「1 行・小さめ」 を優先する = ユーザ要件)。
        float scale = Math.max(FOOTER_MIN_SCALE, avail / (float) totalW);
        float scaledW = totalW * scale;
        float left = (this.width - scaledW) / 2f;
        // backdrop は等倍座標 (= 縮小後の実寸) で描く。
        g.fill((int) Math.floor(left) - padX, y - padY,
                (int) Math.ceil(left + scaledW) + padX, y + lineH + padY,
                ThemeColorResolver.FOOTER_BACKDROP);
        g.pose().pushMatrix();
        g.pose().translate(left, (float) y);
        g.pose().scale(scale, scale);
        int cursor = 0;
        for (int i = 0; i < hints.length; i++) {
            g.text(this.font, hints[i], cursor, 0, color, false);
            cursor += widths[i];
            if (i < hints.length - 1) cursor += gap;
        }
        g.pose().popMatrix();
    }

    /**
     * sticky preview popup を描画する。
     *
     * <p>
     * 表示位置は ALT ホバー版と同じ {@link AdaptiveTooltipPositioner#place} で「画面端クランプ +
     * RTL」 を行うため、 マウスを離した後にカーソル を動かしても popup 自体は anchor 固定。
     * 列数 / dim backdrop は ALT ホバー版と同じ設定 ({@code search.previewGridColumns} /
     * {@code search.previewBackgroundBlur}) を流用する (= ユーザの好みを再宣言不要)。
     */
    private void renderStickyPreview(GuiGraphicsExtractor g, SearchConfig cfg) {
        if (this.stickyPreviewStack.isEmpty()) return;
        if (!RecursiveContainerHelper.isContainerItem(this.stickyPreviewStack)) {
            // 中身を持たないアイテムが何らかの理由で残ったケースの安全弁。
            this.stickyPreviewStack = ItemStack.EMPTY;
            return;
        }
        int columns = AltPreviewPopupRenderer.clampColumns(cfg.previewGridColumns);
        int slotCount = RecursiveContainerHelper.DEFAULT_CONTAINER_SLOTS;
        int w = AltPreviewPopupRenderer.panelWidth(columns);
        int h = AltPreviewPopupRenderer.panelHeight(columns, slotCount);
        int[] xy = AdaptiveTooltipPositioner.place(
                this.stickyPreviewAnchorX, this.stickyPreviewAnchorY,
                w, h, this.width, this.height);
        AltPreviewPopupRenderer.extractRenderState(g, this.font, this.stickyPreviewStack,
                xy[0], xy[1], columns, cfg.previewBackgroundBlur);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        // ALT プレビューのフェード追跡が保持する最後のスタック参照を解放する
        // (= 画面を閉じた後にプレビュー対象 1 個を残さない)。 識別比較専用の状態なので
        // 次回プレビューは初回描画と同じフェード挙動になり、 ユーザー可視挙動は変わらない。
        AltPreviewPopupRenderer.resetFadeTracking();
        //? if >=26.2 {
        /*if (this.minecraft != null) this.minecraft.setScreenAndShow(null);*/
        //?} else {
        if (this.minecraft != null) this.minecraft.setScreen(null);
        //?}
    }

    /** ソートモード。 */
    public enum SortMode {
        DISTANCE, COUNT, NAME
    }

    @SuppressWarnings("unused")
    private Screen parentForReference() {
        return this.parent;
    }

    /**
     * 「SearchConfig 側の {@link com.kajiwara.omnichest.config.data.SearchSortMode}」 から
     * 「SearchScreen ローカルの {@link SortMode}」 への 1:1 マッピング。
     *
     * <p>
     * 両方とも DISTANCE / COUNT / NAME の 3 値で共通。 設定側だけが将来 {@code RECENCY} 等を
     * 持つかもしれないので、 未対応値が来た場合は安全に DISTANCE (= 既定) にフォールバックする。
     */
    private static SortMode sortModeFromConfig(com.kajiwara.omnichest.config.data.SearchSortMode cfgMode) {
        if (cfgMode == null) return SortMode.DISTANCE;
        return switch (cfgMode) {
            case DISTANCE -> SortMode.DISTANCE;
            case COUNT -> SortMode.COUNT;
            case NAME -> SortMode.NAME;
            // RECENCY 等は未実装なので DISTANCE にフォールバック。
            default -> SortMode.DISTANCE;
        };
    }

    /**
     * Sort ボタン押下の統合エントリ。
     * <ol>
     *   <li>ローカル状態を更新 ({@code this.sortMode = newMode})。</li>
     *   <li>{@link SearchConfig#resultSortMode} に書き戻し、 {@link ConfigManager#save()} で永続化。</li>
     *   <li>結果リストを再構築 ({@link #rebuildResults})。</li>
     * </ol>
     *
     * <p>
     * <b>save() 呼び出しコスト</b>: 1 ファイル ~数 KB を gson で書き出すだけ。 ユーザクリックの粒度
     * (= 秒オーダ) で 1 回なので連続発火しても問題なし (= debounce 不要)。
     */
    private void applyAndPersistSort(SortMode newMode) {
        if (newMode == null) return;
        this.sortMode = newMode;
        try {
            SearchConfig cfg = ConfigManager.get().search;
            cfg.resultSortMode = switch (newMode) {
                case DISTANCE -> com.kajiwara.omnichest.config.data.SearchSortMode.DISTANCE;
                case COUNT -> com.kajiwara.omnichest.config.data.SearchSortMode.COUNT;
                case NAME -> com.kajiwara.omnichest.config.data.SearchSortMode.NAME;
            };
            ConfigManager.save();
        } catch (Throwable ignored) {
            // 設定保存に失敗してもユーザ操作は止めない (= ローカル状態は更新済み、 次フレーム表示は OK)。
        }
        rebuildResults();
    }

    /**
     * EditBox の placeholder (hint) が、 指定したボックス幅に収まる長さに切り詰めた {@link Component} を返す。
     *
     * <p>
     * <b>背景</b>: {@code EditBox#drawHint} はテキスト幅が描画領域を超えても切り詰めずに描く
     * (= 右側の他ウィジェットに被って描画されてしまう) 仕様。 単行レイアウト化で SearchBox 幅が
     * 狭くなった結果、 翻訳済みの長い hint (例: 「Search (e.g. diamond, food, mekanism)」)
     * が右側のソート / 詳細ボタンへはみ出すバグの修正用ヘルパ。
     *
     * <p>
     * <b>処理</b>:
     * <ol>
     *   <li>EditBox 内部の左右 padding (≈ 4px ずつ) を控除して「実描画可能幅」 を出す。</li>
     *   <li>hint の本来の幅が実描画可能幅以下なら、 そのまま返す (= 切り詰め不要)。</li>
     *   <li>超えるなら、 末尾が <b>U+2026 HORIZONTAL ELLIPSIS</b> (「…」) で終わる最長文字列に
     *       切り詰める。 文字単位の切断は {@link Font#plainSubstrByWidth} を使い、
     *       マルチバイト / 合字 / RTL を考慮した安全な分割をフォントに任せる。</li>
     * </ol>
     *
     * <p>
     * <b>翻訳互換</b>: 切り詰めは「翻訳済みの文字列」 に対して動的に行うため、 各 lang JSON は
     * 自然な完全文 (= 「検索 (例: diamond, food, mekanism)」 のような長文) のまま置けて、
     * 表示時に必要に応じて短縮される (= 翻訳者が UI 幅を意識して文字数を削る必要がない)。
     */
    private Component cits$fitHintToWidth(Component hint, int boxWidth) {
        String raw = hint.getString();
        // EditBox 左右内部 padding (= 概ね 4 px ずつ)。 「概算で十分」 = 1px の余裕は許容。
        int innerPad = 8;
        int maxW = Math.max(0, boxWidth - innerPad);
        int rawW = this.font.width(raw);
        if (rawW <= maxW) {
            return hint; // 元から収まる → 元のまま (= スタイルや format args を保持)
        }
        String ellipsis = "…";
        int ellipsisW = this.font.width(ellipsis);
        int budget = Math.max(0, maxW - ellipsisW);
        // plainSubstrByWidth は「先頭から budget 幅に収まる substring」 を返す
        // (= multi-byte / 結合文字 / RTL 安全)。
        String trimmed = this.font.plainSubstrByWidth(raw, budget);
        // RTL 言語では右から切るのが自然だが、 EditBox の hint は常に左から描かれるため
        // 切断方向は同一でよい (= bidi 文字方向は Font が処理する)。
        return Component.literal(trimmed + ellipsis);
    }

    /**
     * ALT (左右いずれか) が押されているか。
     * 既存コードの Shift / Alt 判定と同じ {@link InputConstants} 経由方式 (= マッピング差異吸収済み)。
     */
    private static boolean isAltDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RALT);
    }
}
