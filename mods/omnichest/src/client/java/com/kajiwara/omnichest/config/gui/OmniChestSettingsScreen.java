package com.kajiwara.omnichest.config.gui;

import com.kajiwara.omnichest.config.gui.widget.ColorPickerPopup;
import com.kajiwara.omnichest.config.gui.widget.ControlSize;
import com.kajiwara.omnichest.config.gui.widget.DropdownPopup;
import com.kajiwara.omnichest.config.gui.widget.NavyFooterButton;
import com.kajiwara.omnichest.config.gui.widget.OverlayPopup;
import com.kajiwara.omnichest.config.gui.widget.ResetConfirmationPopup;
import com.kajiwara.omnichest.config.gui.widget.RowEntry;
import com.kajiwara.omnichest.config.gui.widget.TabGroup;
import com.kajiwara.omnichest.config.gui.widget.TabModel;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.i18n.RTLLayoutManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Iris ライクなサイドバー型の Config 画面 (改訂版)。
 *
 * <p>
 * <b>このリビジョンでの変更点</b>:
 * <ul>
 * <li>左サイドバーが Reset / Save / Cancel フッタに被らないよう、独立の縦スクロール領域に変更。
 *     サイドバー背景を濃い黒 (0xEE000000) に変更し、コンテンツと視覚的に分離。</li>
 * <li>ヘッダの「OmniChest Settings」を中央寄せにし、 「チェストの蓋 + 鉄帯 + 鍵」風の
 *     木目バナーをタイトル背景として描画。</li>
 * <li>長い見出し (例: "Chest Network Search") がサイドバー幅に収まらない問題を、
 *     サイドバー下端に横スクロールバーを設けて「ラベル領域を横にパン」させることで解決。</li>
 * </ul>
 *
 * <p>
 * <b>レイアウト</b>:
 * <pre>
 * ┌────────────────────────────────────────────────────────┐
 * │  ╔══════════════════════╗                              │ ← チェスト風ヘッダ
 * │  ║  OmniChest Settings  ║                              │
 * │  ╚══════════════════════╝                              │
 * ├──────────┬─────────────────────────────────────────────┤
 * │█General  │  ☑ Enable Mod                               │
 * │ Sort     │  ☐ Debug Mode                               │
 * │ Compact  │  Slider: ──●─────  1.0                      │
 * │ ...     │  (vertical content scroll)                  │
 * │ ▌─ ─ ▌  │                                              │ ← サイドバー横スクロール
 * ├──────────┴─────────────────────────────────────────────┤
 * │           [Reset]   [Save]    [Cancel]                 │
 * └────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class OmniChestSettingsScreen extends Screen {

    // ════════════════════════════════════════════════════════════════════
    // レイアウト定数
    // ════════════════════════════════════════════════════════════════════

    /** サイドバー (= 左カラム) の幅 (px)。 */
    private static final int SIDEBAR_WIDTH = 120;
    /** サイドバー内のタブ 1 個の高さ (px)。 */
    private static final int TAB_HEIGHT = 22;
    /** サイドバー内のグループ ヘッダ (= カテゴリ見出し) の高さ (px)。 */
    private static final int GROUP_HEADER_HEIGHT = 18;
    /** グループ ヘッダの「上に開ける余白」(= 直前のグループとの間の空白行)。 */
    private static final int GROUP_HEADER_TOP_GAP = 6;
    /** ヘッダ (= 木目バナーを置く領域) の高さ (px)。 */
    private static final int HEADER_HEIGHT = 48;
    /**
     * ポップアップ展開中に背後 widget へ渡す 「画面外」 のマウス座標。
     * どの widget の矩形にも当たらない値にして hover (= ツールチップ/強調) を起こさせない。
     */
    private static final int OFFSCREEN_MOUSE = -10000;
    /** タイトル文字の拡大倍率 (= フォントを {@code n}倍にスケールして描画する)。 */
    private static final float HEADER_TITLE_SCALE = 1.6f;
    /** フッタ (= Save / Cancel / Reset ボタン) の高さ (px)。 */
    private static final int FOOTER_HEIGHT = 36;
    /** コンテンツ領域とサイドバーの間に空ける余白 (px)。 */
    private static final int SIDEBAR_GAP = 4;
    /** コンテンツ領域の左右パディング (px)。 */
    private static final int CONTENT_PAD_X = 8;
    /** サイドバー縦スクロールバーの太さ (px)。 */
    private static final int SB_V_W = 4;
    /** サイドバー横スクロールバーの太さ (px)。 */
    private static final int SB_H_H = 4;
    /** タブ ラベルの左パディング (px)。 active indicator の幅を考慮。 */
    private static final int TAB_LABEL_PAD_LEFT = 8;

    // ─── 色 ──────────────────────────────────────────────────────────

    /** サイドバー背景 (濃い目)。 */
    private static final int COLOR_SIDEBAR_BG = 0xCC000000;
    /** ヘッダ下の区切り線。 */
    private static final int COLOR_SEP = 0xFF333333;
    /** チェスト木目: 外周 (= 鉄帯)。 */
    private static final int COLOR_CHEST_RIM = 0xFFD4AF37;
    /**
     * バナー本体: 濃い紺色。
     * 旧実装は濃茶 (0xFF2E1F12) だったが、 金色タイトル文字との対比を保ちつつ
     * もう少し涼しげな印象にするため紺色 (= 深い navy) に変更。
     */
    private static final int COLOR_CHEST_WOOD = 0xFF0D1B3D;
    /**
     * バナー縦継ぎ目ライン。 旧 plank の「板 3 枚」表現を、 紺バージョンでは
     * もう一段暗い紺で代用 (= 文字の視認性を落とさないように非常に控えめ)。
     */
    private static final int COLOR_CHEST_PLANK = 0xFF050B1F;
    /**
     * バナー ハイライト (= 上辺 1 px のみ)。
     * 上から光が当たっている感を残すために、 やや明るい青みグレーを 1 行だけ。
     */
    private static final int COLOR_CHEST_HIGHLIGHT = 0xFF1E305C;
    /** チェスト鍵 (鋲) 色 + タイトル文字色。 */
    private static final int COLOR_CHEST_LOCK = 0xFFFFD700;

    /** タブ active 背景。 */
    private static final int COLOR_TAB_ACTIVE_BG = 0x553A6FA5;
    /** タブ active 左ライン。 */
    private static final int COLOR_TAB_ACTIVE_LINE = 0xFFFFD700;
    /** タブ hover 背景。 */
    private static final int COLOR_TAB_HOVER_BG = 0x33FFFFFF;
    /** グループ ヘッダのラベル色 (= 薄い青みグレー、 タブ ラベルとは別系統で区別)。 */
    private static final int COLOR_GROUP_HEADER_TEXT = 0xFF8A9DCC;
    /** グループ ヘッダ下のアンダーライン色 (= グループ間の視覚的な区切り)。 */
    private static final int COLOR_GROUP_HEADER_UNDERLINE = 0xFF333E5C;
    /** スクロールバー track。 */
    private static final int COLOR_SB_TRACK = 0x66000000;
    /** スクロールバー thumb (= 通常)。 */
    private static final int COLOR_SB_THUMB = 0xAAAAAAAA;
    /** スクロールバー thumb (= ドラッグ中)。 */
    private static final int COLOR_SB_THUMB_DRAG = 0xFFDDDDDD;

    /**
     * 「contentBottom 以下」の footer 帯背景。
     * row 内 widget が content 領域からはみ出した時のマスクとして使うため、
     * <b>必ず完全不透明 (alpha=FF)</b> にする (= 半透明だと overflow が透けてしまう)。
     *
     * <p>
     * フッタ ボタン ({@link com.kajiwara.omnichest.config.gui.widget.NavyFooterButton}) の紺
     * <b>より暗い</b> 紺を使うことで、 ボタンと背景帯が同じ紺系統で
     * グラデーションのように重なり、 ボタンが浮き出て見える。
     */
    private static final int COLOR_FOOTER_BG = 0xFF03081A;

    // ════════════════════════════════════════════════════════════════════
    // 状態
    // ════════════════════════════════════════════════════════════════════

    @Nullable
    private final Screen parent;
    /** カテゴリ別にまとめた tab の入れ物。 サイドバー描画 / クリック判定の元データ。 */
    private final List<TabGroup> tabGroups;
    /** 上のグループから順に並べた flat tab list (= {@link #activeTab} の索引対象)。 */
    private final List<TabModel> tabs;
    /** サイドバーに「ヘッダ → タブ → ヘッダ → タブ ...」 と並べるための描画 / クリック判定エントリ列。 */
    private final List<SidebarEntry> sidebarEntries;
    private final Runnable onSave;
    private final Runnable onReset;

    /** 現在選択中のタブ index (= {@link #tabs} 内の flat index)。 */
    private int activeTab = 0;

    /** コンテンツ領域のスクロール量 (px、 0 = 最上段)。 */
    private double scrollPx = 0.0;

    /** サイドバー縦スクロール量 (px、 0 = 最上段)。 */
    private double sidebarScrollY = 0.0;
    /** サイドバー横スクロール量 (px、 0 = 左端、 = ラベルがサイドバー幅に収まらない時の横パン)。 */
    private double sidebarScrollX = 0.0;

    /** サイドバー縦スクロールバーを drag 中か。 */
    private boolean draggingSidebarV = false;
    /** サイドバー横スクロールバーを drag 中か。 */
    private boolean draggingSidebarH = false;
    /** コンテンツ領域 (= 右端) の縦スクロールバーを drag 中か。 */
    private boolean draggingContentV = false;
    /** drag 開始時に「thumb 内のどこを掴んだか」(= drag 中はこの距離を維持)。 */
    private double sbDragOffset = 0.0;

    /**
     * 上から被せて入力を独占するポップアップ (= 表示中のみ非 null)。
     * カラーピッカー / ドロップダウン両対応 — どちらも {@link OverlayPopup} を実装する。
     * 表示中はすべての入力をこちらに優先ルーティングし、 widget / タブクリックを抑止する。
     */
    @Nullable
    private OverlayPopup activePopup = null;

    /**
     * 自前で保持する renderables のミラーリスト。
     *
     * <p>
     * MC 1.21.11 で {@code Screen#renderables} は private に変更されており、 サブクラスから直接
     * iterate できない。 一方で本 Screen では widget の描画を scissor で囲む必要がある
     * (= ヘッダ領域へのはみ出しをカットする目的) ため、 widget リスト自体は読みたい。
     *
     * <p>
     * 対策として {@link #addRenderableWidget} をオーバーライドして自前リストにも同じ widget を
     * 積んでおき、 render 時はこちらを iterate する。 親の renderables は親が握ったまま、
     * 自前ミラーは {@link #init()} 冒頭で毎回 clear して再構築する (= リサイズ時に重複しないように)。
     */
    private final List<Renderable> myRenderables = new ArrayList<>();

    /**
     * フッタの 3 ボタンへの直接参照。
     *
     * <p>
     * row の widget が content 領域からはみ出した時に、 contentBottom 以下を
     * mask の塗りで覆って 「文字と同じ位置で widget も切る」 ようにするため、
     * その mask の <b>上に</b> フッタボタンを再描画する必要がある。
     * super.extractRenderState() で 1 回描かれた上に mask で覆われる → ここから再度
     * {@code render()} を呼び直す、 という二段描画。
     */
    @Nullable
    private Button footerResetBtn;
    @Nullable
    private Button footerSaveBtn;
    @Nullable
    private Button footerCancelBtn;

    public OmniChestSettingsScreen(@Nullable Screen parent, Component title,
            List<TabGroup> groups, Runnable onSave, Runnable onReset) {
        super(title);
        this.parent = parent;
        this.tabGroups = List.copyOf(groups);
        // ─── group → 「flat tab list + sidebar entry list」へ展開 ───
        // tabs は activeTab の index 用にフラット化。
        // sidebarEntries は描画/クリック用に「ヘッダ / タブ」を順番に並べる。
        List<TabModel> flatTabs = new ArrayList<>();
        List<SidebarEntry> entries = new ArrayList<>();
        int flatIdx = 0;
        for (int gi = 0; gi < this.tabGroups.size(); gi++) {
            TabGroup group = this.tabGroups.get(gi);
            entries.add(new HeaderEntry(group.title(), gi == 0));
            for (TabModel tab : group.tabs()) {
                entries.add(new TabEntry(tab, flatIdx));
                flatTabs.add(tab);
                flatIdx++;
            }
        }
        this.tabs = List.copyOf(flatTabs);
        this.sidebarEntries = List.copyOf(entries);
        this.onSave = onSave;
        this.onReset = onReset;
    }

    /**
     * サイドバーに並ぶ 1 要素。 グループ ヘッダ ({@link HeaderEntry}) かタブ ({@link TabEntry}) のどちらか。
     * 描画ループ / クリック判定はこの sealed 階層の上を一様に歩く。
     */
    private sealed interface SidebarEntry {
        int height();
    }

    /**
     * グループ ヘッダ (カテゴリ見出し)。 クリック不可。
     *
     * @param title カテゴリ名 (= 翻訳済み Component)
     * @param first リスト先頭のグループか (= 上の隙間を取らない)
     */
    private record HeaderEntry(Component title, boolean first) implements SidebarEntry {
        @Override
        public int height() {
            return GROUP_HEADER_HEIGHT + (first ? 0 : GROUP_HEADER_TOP_GAP);
        }
    }

    /** タブ 1 件。 クリックで {@link #activeTab} を {@code flatIndex} に切り替える。 */
    private record TabEntry(TabModel tab, int flatIndex) implements SidebarEntry {
        @Override
        public int height() {
            return TAB_HEIGHT;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 寸法ヘルパ
    // ════════════════════════════════════════════════════════════════════

    /**
     * 現在のロケールが RTL かどうか。 サイドバーとコンテンツ全体を左右反転させる
     * 唯一のトリガ。 値は {@link RTLLayoutManager} に従い、 言語切替の瞬間に切り替わる。
     */
    private boolean rtl() {
        return RTLLayoutManager.get().isRtl();
    }

    /** サイドバー全体 (= 背景塗り + 区切り線含む) の左端 X。 */
    private int sidebarLeft() { return rtl() ? this.width - SIDEBAR_WIDTH : 0; }
    private int sidebarTop() { return HEADER_HEIGHT; }
    /** サイドバー全体の右端 X。 */
    private int sidebarRight() { return rtl() ? this.width : SIDEBAR_WIDTH; }
    private int sidebarBottom() { return this.height - FOOTER_HEIGHT; }

    /**
     * タブ ビューポート (= タブ ラベルが描画される領域) の左端 X。
     * LTR: 0 (= 画面左端)。 V スクロールバーは右端側。
     * RTL: width - SIDEBAR_WIDTH + SB_V_W (= V スクロールバーぶんだけ内側へ寄せる)。
     */
    private int sidebarTabViewportLeft() {
        return rtl() ? this.width - SIDEBAR_WIDTH + SB_V_W : 0;
    }
    /** タブ ビューポートの右端 X。 */
    private int sidebarTabViewportRight() {
        return rtl() ? this.width : SIDEBAR_WIDTH - SB_V_W;
    }
    /** V スクロールバーの左端 X。 LTR=サイドバー右寄り / RTL=サイドバー左寄り。 */
    private int sidebarVScrollbarLeft() {
        return rtl() ? this.width - SIDEBAR_WIDTH : SIDEBAR_WIDTH - SB_V_W;
    }

    /** タブ ビューポートの可視幅 (= 縦スクロールバーぶんを引いた値)。 */
    private int sidebarTabViewportW() {
        return SIDEBAR_WIDTH - SB_V_W;
    }
    /** タブ ビューポートの可視高さ (= 横スクロールバーぶんを引いた値)。 */
    private int sidebarTabViewportH() {
        return sidebarBottom() - sidebarTop() - SB_H_H;
    }
    /** タブ + グループ ヘッダ を全部縦に並べた時の高さ。 */
    private int sidebarContentTotalH() {
        int total = 0;
        for (SidebarEntry e : this.sidebarEntries) total += e.height();
        return total;
    }
    /**
     * ラベルが必要とする最大幅 (= 横スクロールの logical 幅)。
     * タブ ラベルとグループ ヘッダのラベル両方を見る。
     */
    private int sidebarContentTotalW() {
        Font font = Minecraft.getInstance().font;
        int maxLabelW = 0;
        for (SidebarEntry e : this.sidebarEntries) {
            Component label = (e instanceof TabEntry t) ? t.tab().title()
                    : ((HeaderEntry) e).title();
            int w = font.width(label);
            if (w > maxLabelW) maxLabelW = w;
        }
        // active indicator (2px) + label padding (左 8px + 右 8px) を含める。
        int logical = maxLabelW + TAB_LABEL_PAD_LEFT + 12;
        // ビューポート幅より小さい場合はビューポート幅に合わせる (= 横スクロール不要)。
        return Math.max(logical, sidebarTabViewportW());
    }

    private boolean needsSidebarVScroll() {
        return sidebarContentTotalH() > sidebarTabViewportH();
    }
    private boolean needsSidebarHScroll() {
        return sidebarContentTotalW() > sidebarTabViewportW();
    }

    // ════════════════════════════════════════════════════════════════════
    // Screen ライフサイクル
    // ════════════════════════════════════════════════════════════════════

    /**
     * Screen への widget 登録をフックして自前ミラーリストにも積む。
     * resize 時に親 (Screen) は {@code clearWidgets} 経由で renderables をクリアして init を
     * 呼び直すため、 自前リストは {@link #init()} 冒頭で clear する。
     */
    @Override
    protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
        T result = super.addRenderableWidget(widget);
        this.myRenderables.add(result);
        return result;
    }

    @Override
    protected void init() {
        super.init();

        // ★ リサイズ時の重複登録防止: 親の renderables は親が clearWidgets で消すが、
        //   こちらのミラーは自前で持っているので明示的に空にする。
        this.myRenderables.clear();

        // ─── 1) 各タブの全 row を Screen に登録 (init 内で 1 度だけ) ───
        ControlSize.WidgetSink sink = new ControlSize.WidgetSink() {
            @Override
            public <W extends AbstractWidget> W add(W widget) {
                return OmniChestSettingsScreen.this.addRenderableWidget(widget);
            }

            @Override
            public void openColorPicker(int initialRgb,
                    java.util.function.IntConsumer onConfirm) {
                OmniChestSettingsScreen.this.activePopup = new ColorPickerPopup(
                        OmniChestSettingsScreen.this.width,
                        OmniChestSettingsScreen.this.height,
                        initialRgb, onConfirm);
            }

            @Override
            public <E> void openDropdown(java.util.List<E> values, E current,
                    java.util.function.Function<E, Component> labelFn,
                    java.util.function.Consumer<E> onSelect,
                    int anchorX, int anchorY, int anchorW, int anchorH) {
                OmniChestSettingsScreen.this.activePopup = new DropdownPopup<>(
                        OmniChestSettingsScreen.this.width,
                        OmniChestSettingsScreen.this.height,
                        values, current, labelFn, onSelect,
                        anchorX, anchorY, anchorW, anchorH);
            }
        };
        for (TabModel tab : this.tabs) {
            for (RowEntry row : tab.rows()) {
                row.attachTo(sink);
            }
        }

        // ─── 2) フッタ ボタン (Reset / Save / Cancel) ───
        int footerY = this.height - FOOTER_HEIGHT + 8;
        int btnW = 80;
        int btnH = 20;
        int gap = 8;
        int totalW = btnW * 3 + gap * 2;
        int startX = (this.width - totalW) / 2;

        // フッタボタンは「マスク再描画」のためフィールドにも保持する。
        // バニラ Button の代わりに NavyFooterButton を使い、 背景を不透明な濃紺で描く。
        // ラベルは Keys.BUTTON_RESET / SAVE / CANCEL を OmniChestLocale で解決して多言語対応。
        this.footerResetBtn = addRenderableWidget(new NavyFooterButton(
                startX, footerY, btnW, btnH,
                OmniChestLocale.get(Keys.BUTTON_RESET, "Reset"),
                b -> {
                    // 即時リセットせず、 まず確認 Popup を出す (= 誤操作で全設定を失う事故を防ぐ)。
                    // 「はい」 が押されたときだけ onReset を実行して画面を作り直す。
                    // reset 直後の値を row に再注入する手段がないため、 Screen を parent へ戻して反映する。
                    this.activePopup = new ResetConfirmationPopup(
                            this.width, this.height,
                            () -> {
                                this.onReset.run();
                                //? if >=26.2 {
                                /*Minecraft.getInstance().setScreenAndShow(this.parent);*/
                                //?} else {
                                Minecraft.getInstance().setScreen(this.parent);
                                //?}
                            });
                }));

        this.footerSaveBtn = addRenderableWidget(new NavyFooterButton(
                startX + (btnW + gap), footerY, btnW, btnH,
                OmniChestLocale.get(Keys.BUTTON_SAVE, "Save"),
                b -> {
                    saveAll();
                    //? if >=26.2 {
                    /*Minecraft.getInstance().setScreenAndShow(this.parent);*/
                    //?} else {
                    Minecraft.getInstance().setScreen(this.parent);
                    //?}
                }));

        this.footerCancelBtn = addRenderableWidget(new NavyFooterButton(
                startX + (btnW + gap) * 2, footerY, btnW, btnH,
                OmniChestLocale.get(Keys.BUTTON_CANCEL, "Cancel"),
                //? if >=26.2 {
                /*b -> Minecraft.getInstance().setScreenAndShow(this.parent)));*/
                //?} else {
                b -> Minecraft.getInstance().setScreen(this.parent)));
                //?}

        // 初期表示: 選択中以外のタブを隠す。
        applyTabVisibility();
    }

    /**
     * 画面リサイズ (F11 全画面トグル / GUI スケール変更 / 解像度変更) ハンドラ。
     *
     * <p>
     * バニラの {@code super.resize()} が {@code this.width/height} を更新し {@link #init()} を呼び直して
     * 全 widget を生きた画面サイズで再構築するため、 Screen 本体のレイアウトは自動で追従する。
     * ただし {@code activePopup} フィールドは init() では触られないので、 ここで明示的に整理する:
     * <ul>
     *   <li>中央寄せ popup ({@link ColorPickerPopup} / {@link ResetConfirmationPopup} =
     *       {@code survivesResize()==true}) は毎フレーム再センタリングされるため<b>維持</b>。</li>
     *   <li>anchor 追従型 ({@link DropdownPopup} = デフォルト false) は anchor が動くため<b>破棄</b>し、
     *       stale 座標で描画・誤クリックされるのを防ぐ。</li>
     * </ul>
     */
    @Override
    public void resize(int w, int h) {
        super.resize(w, h);
        if (this.activePopup != null && !this.activePopup.survivesResize()) {
            dismissPopup();
        }
    }

    /** 全 row の値を Config へコミットする。 */
    private void saveAll() {
        for (TabModel tab : this.tabs) {
            for (RowEntry row : tab.rows()) {
                row.save();
            }
        }
        this.onSave.run();
    }

    /**
     * 開いている popup を閉じて参照を破棄する。
     *
     * <p>
     * 単に {@code activePopup = null} とするだけでなく <b>フォーカスも解除</b> する。
     * バニラの widget はクリックされた直後にキーボードフォーカスを受け取るため、
     * Reset ボタンを押して popup を開くと Reset ボタンが focused 状態のまま残り、
     * popup を閉じた後も {@link NavyFooterButton#isHoveredOrFocused()} が true を返して
     * 「ホバー時の黄色反転」 表示が固定されてしまう。 ここでフォーカスを切ることで
     * マウスを外せば通常の紺色表示へ戻るようにする。
     */
    private void dismissPopup() {
        this.activePopup = null;
        this.setFocused(null);
    }

    /** 現在のタブだけ visible にし、他は不可視化する。 */
    private void applyTabVisibility() {
        for (int i = 0; i < this.tabs.size(); i++) {
            boolean visible = (i == this.activeTab);
            for (RowEntry row : this.tabs.get(i).rows()) {
                row.setVisible(visible);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // ★ 重要: super.extractRenderState() より <b>前</b> に widget の位置 + 可視範囲を確定させる。
        //
        // 旧実装はレイアウトを {@link #renderContent} 内 (= super.render の後) で行っていたため、
        // タブを切り替えた直後の最初の 1 フレームだけ、新しいタブの widget が
        // 生成時の位置 (= bounds(0,0,…) なので画面左上) で super.render に描画されてしまい、
        // 一瞬だけ画面左上にボタンの残像が見える不具合があった。
        // 「設定項目を 1 度クリックしたら治る」のは、その時点で render が一巡し
        // widget の position が正しい座標に書き換わったため。
        prepareActiveTabLayout();

        int contentTop = HEADER_HEIGHT + 4;

        // ─── 背景描画について (重要) ───
        //
        // MC 1.21.5+ で {@code Screen#render} から {@code renderBackground} の呼び出しが消えた。
        // 代わりに GameRenderer が screen render の <b>前</b> に外側から
        // {@code screen.renderBackground} を 1 回だけ呼ぶ仕様に変更されている。
        //
        // よって Screen サブクラスの {@code render} 内で {@code this.renderBackground} を呼ぶと、
        // GameRenderer の呼び出しと合わせて blur が 2 回起動して
        // 「Can only blur once per frame」で確実にクラッシュする。 ここでは <b>呼ばない</b>。
        //
        // 同様に {@code super.extractRenderState(...)} (= Screen.render) は 1.21.5+ では
        // renderables の iterate しかしないので、 自前で iterate するか super を呼ぶかは
        // どちらでも良い。 本実装は widget の描画範囲を scissor で contentTop に制限したいので、
        // 自前 iterate を選択している。

        // ─── widget を scissor で囲んで自前で iterate ───
        //
        // 旧実装は content からはみ出した widget を不透明色マスクで覆っていた。
        // しかし上側 (= ヘッダ領域) にも widget がはみ出す (スクロール時の row 1 行目など) ため
        // 同じことを上にもやろうとすると 「ヘッダ全幅を濃い色で塗る」 必要があり、
        // チェストバナーの上下が真っ黒になってデザインが死ぬ。
        //
        // そこで上側は scissor のみで物理的に描画を止め、 塗り (= 背景色) は追加しない。
        // 結果: widget は contentTop の真上で切れ、 切れた先には GameRenderer 側で既に敷かれた
        // 半透明 backdrop がそのまま見える。
        // ─── ポップアップ展開中は背後 widget の hover を無効化する ───
        //
        // バニラ Button はホバー時に自前でツールチップを描画するため、 ドロップダウン
        // (= DropdownPopup) を開いている間も、 その下にあるコントロール (RTL Layout /
        // Unicode Font Safety など) にマウスが乗るとツールチップが開いたリストに重なって
        // 読みづらくなる。 入力は既に activePopup 側へゲート済み (mouseClicked 等) なので、
        // 描画フェーズでも背後 widget には 「画面外」 のマウス座標を渡して hover を起こさせない。
        // → ツールチップもホバー強調も出ず、 ポップアップが最前面の唯一の操作対象になる。
        // ポップアップを閉じれば通常のマウス座標に戻り、 ツールチップ挙動も完全復元される
        // (= グローバル無効化ではなく、 この画面でポップアップが開いている間だけの抑制)。
        boolean popupOpen = this.activePopup != null && !this.activePopup.isClosed();
        int wMouseX = popupOpen ? OFFSCREEN_MOUSE : mouseX;
        int wMouseY = popupOpen ? OFFSCREEN_MOUSE : mouseY;

        g.enableScissor(0, contentTop, this.width, this.height);
        for (Renderable renderable : this.myRenderables) {
            renderable.extractRenderState(g, wMouseX, wMouseY, partialTick);
        }
        g.disableScissor();

        // ─── ヘッダ (= チェスト風バナー) ───
        renderChestHeader(g);

        // ─── サイドバー ───
        renderSidebar(g, mouseX, mouseY);

        // ─── コンテンツ領域 (ラベル + スクロールバー) ───
        renderContent(g, mouseX, mouseY, partialTick);

        // ─── 下側はみ出しマスク + footer 再描画 ───
        //
        // 下側は scissor だけだと footer ボタンも切れてしまうため (= 全 widget が super.render
        // 経由で 1 回描かれてしまっている) 、 塗りで footer 帯を上書きしてから footer ボタンを
        // 再描画する二段方式を取る。
        // マスクの開始位置は contentBottom (= this.height - FOOTER_HEIGHT) — グレーの区切り線と
        // ぴったり同じ位置に揃えることで、 文字の切れ位置と widget の切れ位置を一致させる。
        g.fill(0, this.height - FOOTER_HEIGHT, this.width, this.height, COLOR_FOOTER_BG);

        // ─── フッタ separator (mask の上に置く) ───
        g.fill(0, this.height - FOOTER_HEIGHT, this.width, this.height - FOOTER_HEIGHT + 1, COLOR_SEP);

        // ─── footer ボタンを mask の上から再描画 (= マスクで覆われたぶんを復元) ───
        // フッタボタンも同様に、 ポップアップ展開中は hover を無効化する (= ツールチップ抑制)。
        if (this.footerResetBtn != null) this.footerResetBtn.extractRenderState(g, wMouseX, wMouseY, partialTick);
        if (this.footerSaveBtn != null) this.footerSaveBtn.extractRenderState(g, wMouseX, wMouseY, partialTick);
        if (this.footerCancelBtn != null) this.footerCancelBtn.extractRenderState(g, wMouseX, wMouseY, partialTick);

        // ─── ポップアップは最後に上から被せ描画する ───
        // closed フラグが立っていたら参照を切る (= popup 自身が cancel/commit で閉じる)。
        if (this.activePopup != null) {
            if (this.activePopup.isClosed()) {
                dismissPopup();
            } else {
                this.activePopup.extractRenderState(g, mouseX, mouseY);
            }
        }
    }

    /**
     * チェストの蓋を模した中央タイトル バナーを描画する。
     *
     * <p>
     * 構成: 鉄帯 (rim) で囲まれた木目板の中央に「OmniChest Settings」を黄金色で描く。
     * 左右に「□」鋲、 中央上部に「▼」南京錠アクセントを置いて、 一目で「チェストの蓋」と分かる外観にする。
     */
    private void renderChestHeader(GuiGraphicsExtractor g) {
        Font font = this.font;
        // 太字 + 拡大表示するので、 バナー サイズ計算は スケール後 の幅 / 高さ で行う。
        Component boldTitle = this.title.copy().withStyle(ChatFormatting.BOLD);
        int rawTitleW = font.width(boldTitle);
        int scaledTitleW = Math.round(rawTitleW * HEADER_TITLE_SCALE);
        int scaledTitleH = Math.round(8 * HEADER_TITLE_SCALE);
        // バナーの内側 (鉄帯の内側) の幅は タイトル幅 + 余白 80 px。 最小幅 240 px。
        int innerW = Math.max(240, scaledTitleW + 80);
        int innerH = scaledTitleH + 8;
        int rim = 2; // 鉄帯の厚み
        int totalW = innerW + rim * 2;
        int totalH = innerH + rim * 2;
        int x = (this.width - totalW) / 2;
        int y = (HEADER_HEIGHT - totalH) / 2;

        // ─── 1) 外周の鉄帯 (= 金色) ───
        g.fill(x, y, x + totalW, y + totalH, COLOR_CHEST_RIM);

        // ─── 2) 木目本体 ───
        int wx1 = x + rim, wy1 = y + rim, wx2 = x + totalW - rim, wy2 = y + totalH - rim;
        g.fill(wx1, wy1, wx2, wy2, COLOR_CHEST_WOOD);

        // 上 1px ハイライト (= 光が当たる側)。
        g.fill(wx1, wy1, wx2, wy1 + 1, COLOR_CHEST_HIGHLIGHT);

        // 板の継ぎ目を 2 本 (= 板 3 枚に見せる)。
        int p1 = wy1 + innerH / 3;
        int p2 = wy1 + (innerH * 2) / 3;
        g.fill(wx1, p1, wx2, p1 + 1, COLOR_CHEST_PLANK);
        g.fill(wx1, p2, wx2, p2 + 1, COLOR_CHEST_PLANK);

        // ─── 3) 角の鋲 (= 黒い小四角を 4 隅に) ───
        drawRivet(g, wx1 + 2, wy1 + 2);
        drawRivet(g, wx2 - 4, wy1 + 2);
        drawRivet(g, wx1 + 2, wy2 - 4);
        drawRivet(g, wx2 - 4, wy2 - 4);

        // ─── 4) 中央上部に小さな南京錠 (▼形) ───
        int lockX = x + totalW / 2;
        int lockY = y - 2;
        g.fill(lockX - 2, lockY, lockX + 2, lockY + 4, COLOR_CHEST_LOCK);
        g.fill(lockX - 1, lockY - 2, lockX + 1, lockY, COLOR_CHEST_LOCK);

        // ─── 5) タイトル文字を中央に (= 太字 + 拡大 + 影付き) ───
        // Matrix3x2fStack で scale を掛けて描画する。 翻訳後にスケールする標準パターン:
        //   translate(centerX, centerY) → scale(s, s) → drawString(-rawW/2, -8/2)
        // とすると、 (centerX, centerY) を中心とした s 倍の文字描画になる。
        int cx = x + totalW / 2;
        int cy = y + totalH / 2;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(HEADER_TITLE_SCALE, HEADER_TITLE_SCALE);
        g.text(font, boldTitle, -rawTitleW / 2, -4 /* font height 8 / 2 */,
                COLOR_CHEST_LOCK, true);
        g.pose().popMatrix();

        // ─── 6) バナー下に区切り線 (= サイドバー / コンテンツとの境界) ───
        g.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, COLOR_SEP);
    }

    private void drawRivet(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 2, y + 2, 0xFF1A1009);
    }

    /** サイドバー全体 (背景 + クリップ済みタブ + 2 種スクロールバー) を描画する。 */
    private void renderSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = sidebarTop();
        int bottom = sidebarBottom();
        int left = sidebarLeft();
        int right = sidebarRight();

        // 背景 (濃い目)。
        g.fill(left, top, right, bottom, COLOR_SIDEBAR_BG);

        // タブ領域 (= スクロールバー 2 本ぶんを除外)。 LTR / RTL で X の範囲が変わる。
        int viewportLeft = sidebarTabViewportLeft();
        int viewportRight = sidebarTabViewportRight();
        int viewportTop = top;
        int viewportBottom = bottom - SB_H_H;
        boolean rtl = rtl();

        clampSidebarScroll();

        // ─── サイドバー エントリ描画 (scissor でクリップ) ───
        g.enableScissor(viewportLeft, viewportTop, viewportRight, viewportBottom);
        Font font = this.font;
        int yCursor = viewportTop - (int) Math.round(this.sidebarScrollY);
        // 文字の X 基準点: LTR は viewportLeft からの「左→右」スクロール, RTL は
        // viewportRight からの「右→左」スクロールで反映する (= テキストの「先頭」が常に
        // ロケールの主たる読み開始側に寄るようにする)。
        int xBase = rtl
                ? viewportRight + (int) Math.round(this.sidebarScrollX)
                : viewportLeft - (int) Math.round(this.sidebarScrollX);

        for (SidebarEntry entry : this.sidebarEntries) {
            int entryTop = yCursor;
            int entryH = entry.height();
            yCursor += entryH;

            // 完全に画面外なら描画スキップ (= 入力判定は別途座標で行う)。
            if (entryTop + entryH < viewportTop || entryTop > viewportBottom) continue;

            if (entry instanceof HeaderEntry h) {
                renderGroupHeader(g, font, h, entryTop, entryH, xBase, viewportLeft, viewportRight, rtl);
            } else if (entry instanceof TabEntry t) {
                renderTabEntry(g, font, t, entryTop, entryH, xBase,
                        viewportLeft, viewportRight, viewportTop, viewportBottom,
                        mouseX, mouseY, rtl);
            }
        }
        g.disableScissor();

        // ─── スクロールバー 2 本 ───
        renderSidebarVScrollbar(g, mouseX, mouseY);
        renderSidebarHScrollbar(g, mouseX, mouseY);

        // サイドバーとコンテンツの境界線 (= LTR は右端、 RTL は左端の 1px ライン)。
        int sepX = rtl ? this.width - SIDEBAR_WIDTH - 1 : SIDEBAR_WIDTH;
        g.fill(sepX, top, sepX + 1, bottom, COLOR_SEP);
    }

    /**
     * グループ ヘッダ (= カテゴリ見出し) を 1 件描画する。
     * 配下のタブと色 / 形状を変えて「見出し」と分かるようにする。
     *
     * <p>
     * <b>太字</b>: タイトル Component を {@link ChatFormatting#BOLD} で包んで描画する。
     * Minecraft の bold スタイルは Latin / CJK 両方でフォント側が太字グリフ
     * (= 1px シフト重ね描き) を出してくれるため、 多言語で同じく太く見える。
     */
    private void renderGroupHeader(GuiGraphicsExtractor g, Font font, HeaderEntry h,
            int entryTop, int entryH, int xBase,
            int viewportLeft, int viewportRight, boolean rtl) {
        // 「上の隙間」は描画しない (= 透明にして区切りとして機能させる)。
        int textAreaTop = entryTop + (h.first() ? 0 : GROUP_HEADER_TOP_GAP);
        int textAreaH = entryH - (h.first() ? 0 : GROUP_HEADER_TOP_GAP);
        int textY = textAreaTop + (textAreaH - 8) / 2;
        Component boldTitle = h.title().copy().withStyle(ChatFormatting.BOLD);
        // LTR は左端からの padding、 RTL は右端からの padding で右寄せ。
        int textX = rtl
                ? xBase - TAB_LABEL_PAD_LEFT - font.width(boldTitle)
                : xBase + TAB_LABEL_PAD_LEFT;
        g.text(font, boldTitle, textX, textY, COLOR_GROUP_HEADER_TEXT, false);
        // ヘッダ下に薄い水平ライン (= タブとの区切りを視覚化)。
        // RTL のときは余白側が逆になるよう左右の引き量を入れ替える。
        int lineY = textAreaTop + textAreaH - 1;
        int lineX1 = rtl ? viewportLeft + 4 : viewportLeft + TAB_LABEL_PAD_LEFT;
        int lineX2 = rtl ? viewportRight - TAB_LABEL_PAD_LEFT : viewportRight - 4;
        g.fill(lineX1, lineY, lineX2, lineY + 1, COLOR_GROUP_HEADER_UNDERLINE);
    }

    /** タブ 1 件を描画する (= 旧 renderSidebar の 1 イテレーション分を関数化)。 */
    private void renderTabEntry(GuiGraphicsExtractor g, Font font, TabEntry t,
            int entryTop, int entryH, int xBase,
            int viewportLeft, int viewportRight, int viewportTop, int viewportBottom,
            int mouseX, int mouseY, boolean rtl) {
        boolean active = (t.flatIndex() == this.activeTab);
        boolean hovered = mouseX >= viewportLeft && mouseX < viewportRight
                && mouseY >= entryTop && mouseY < entryTop + entryH
                && mouseY >= viewportTop && mouseY < viewportBottom;

        int bg = active ? COLOR_TAB_ACTIVE_BG : (hovered ? COLOR_TAB_HOVER_BG : 0);
        if (bg != 0) {
            g.fill(viewportLeft, entryTop, viewportRight, entryTop + entryH, bg);
        }
        if (active) {
            // active indicator は「ロケールの読み開始側」(LTR=左端 / RTL=右端) に立てる。
            if (rtl) {
                g.fill(viewportRight - 2, entryTop, viewportRight, entryTop + entryH,
                        COLOR_TAB_ACTIVE_LINE);
            } else {
                g.fill(viewportLeft, entryTop, viewportLeft + 2, entryTop + entryH,
                        COLOR_TAB_ACTIVE_LINE);
            }
        }
        int textColor = active ? COLOR_TAB_ACTIVE_LINE : (hovered ? 0xFFFFFFFF : 0xFFCCCCCC);
        int textY = entryTop + (entryH - 8) / 2;
        Component title = t.tab().title();
        int textX = rtl
                ? xBase - TAB_LABEL_PAD_LEFT - font.width(title)
                : xBase + TAB_LABEL_PAD_LEFT;
        g.text(font, title, textX, textY, textColor, false);
    }

    private void renderSidebarVScrollbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = sidebarVScrollbarLeft();
        int y = sidebarTop();
        int h = sidebarTabViewportH();
        // track。
        g.fill(x, y, x + SB_V_W, y + h, COLOR_SB_TRACK);

        if (!needsSidebarVScroll()) return;

        // thumb。
        int totalH = sidebarContentTotalH();
        int thumbH = Math.max(20, (int) ((double) h / totalH * h));
        int thumbY = y + (int) ((double) this.sidebarScrollY / (totalH - h) * (h - thumbH));
        int color = this.draggingSidebarV ? COLOR_SB_THUMB_DRAG : COLOR_SB_THUMB;
        g.fill(x, thumbY, x + SB_V_W, thumbY + thumbH, color);
    }

    private void renderSidebarHScrollbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = sidebarTabViewportLeft();
        int y = sidebarBottom() - SB_H_H;
        int w = sidebarTabViewportW();
        // track。
        g.fill(x, y, x + w, y + SB_H_H, COLOR_SB_TRACK);

        if (!needsSidebarHScroll()) return;

        int totalW = sidebarContentTotalW();
        int thumbW = Math.max(20, (int) ((double) w / totalW * w));
        // thumb 位置は LTR=左→右 / RTL=右→左 と反転させて、
        // 「読み開始側にスクロールしている」感覚を維持する。
        double frac = (double) this.sidebarScrollX / (totalW - w);
        if (rtl()) frac = 1.0 - frac;
        int thumbX = x + (int) (frac * (w - thumbW));
        int color = this.draggingSidebarH ? COLOR_SB_THUMB_DRAG : COLOR_SB_THUMB;
        g.fill(thumbX, y, thumbX + thumbW, y + SB_H_H, color);
    }

    /** サイドバーのスクロール量を範囲内に収める。 */
    private void clampSidebarScroll() {
        int maxY = Math.max(0, sidebarContentTotalH() - sidebarTabViewportH());
        if (this.sidebarScrollY < 0) this.sidebarScrollY = 0;
        if (this.sidebarScrollY > maxY) this.sidebarScrollY = maxY;
        int maxX = Math.max(0, sidebarContentTotalW() - sidebarTabViewportW());
        if (this.sidebarScrollX < 0) this.sidebarScrollX = 0;
        if (this.sidebarScrollX > maxX) this.sidebarScrollX = maxX;
    }

    /**
     * 現在アクティブなタブの row を配置 + ビューポート可視判定する。
     * {@link #render} の冒頭 (= {@code super.extractRenderState()} の前) で呼ぶ前提。
     *
     * <p>
     * 「位置決定」と「描画」を分離することで、 widget が
     * {@code Button.bounds(0,0,…)} で生成された後の最初の super.render が
     * (0,0) で描いてしまう問題 (= タブ切替直後の左上フラッシュ) を防ぐ。
     */
    private void prepareActiveTabLayout() {
        if (this.tabs.isEmpty()) return;
        TabModel tab = this.tabs.get(this.activeTab);

        boolean rtl = rtl();
        // RTL ではサイドバーが右側に来るので、 コンテンツ領域も左右が反転する。
        int contentLeft = rtl ? CONTENT_PAD_X
                : SIDEBAR_WIDTH + 1 + SIDEBAR_GAP + CONTENT_PAD_X;
        int contentRight = rtl
                ? this.width - SIDEBAR_WIDTH - 1 - SIDEBAR_GAP - CONTENT_PAD_X
                : this.width - CONTENT_PAD_X;
        int contentTop = HEADER_HEIGHT + 4;
        // 下端はグレー区切り線 (= footer 上辺) にそのまま合わせる。
        // 旧実装は -4 px のバッファを取っていたが、 「区切り線で切ってほしい」 という要求に合わせて廃止。
        int contentBottom = this.height - FOOTER_HEIGHT;
        int contentWidth = contentRight - contentLeft;
        int viewportHeight = contentBottom - contentTop;

        int totalHeight = 0;
        for (RowEntry row : tab.rows()) totalHeight += row.getHeight();
        clampContentScroll(totalHeight, viewportHeight);

        int yCursor = contentTop - (int) Math.round(this.scrollPx);
        for (RowEntry row : tab.rows()) {
            row.layout(contentLeft, yCursor, contentWidth);
            yCursor += row.getHeight();
        }
        // 視認範囲外の widget を非表示にして、 super.render の描画対象から外す
        // (= 左上フラッシュ防止の本命処理)。
        for (RowEntry row : tab.rows()) {
            boolean inViewport = row.getY() + row.getHeight() > contentTop
                    && row.getY() < contentBottom;
            row.setVisible(inViewport);
        }
    }

    /** コンテンツ領域 (= タブ中身) のラベル / スクロールバー描画。 widget 本体は super.render が描く。 */
    private void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (this.tabs.isEmpty()) return;
        TabModel tab = this.tabs.get(this.activeTab);

        boolean rtl = rtl();
        // prepareActiveTabLayout と同じ式で contentLeft/Right を導出する。 RTL では
        // コンテンツが左側 (= サイドバーが右側) になる。
        int contentLeft = rtl ? CONTENT_PAD_X
                : SIDEBAR_WIDTH + 1 + SIDEBAR_GAP + CONTENT_PAD_X;
        int contentRight = rtl
                ? this.width - SIDEBAR_WIDTH - 1 - SIDEBAR_GAP - CONTENT_PAD_X
                : this.width - CONTENT_PAD_X;
        int contentTop = HEADER_HEIGHT + 4;
        // grey 区切り線と切れ位置を合わせる (= prepareActiveTabLayout 側と同じ式)。
        int contentBottom = this.height - FOOTER_HEIGHT;
        int contentWidth = contentRight - contentLeft;
        int viewportHeight = contentBottom - contentTop;

        // totalHeight はスクロールバーの描画判定にのみ使う (= レイアウト計算は prepareActiveTabLayout で完了済み)。
        int totalHeight = 0;
        for (RowEntry row : tab.rows()) totalHeight += row.getHeight();

        // content を clip して row.render (= ラベル等の追加描画) を呼ぶ。
        g.enableScissor(contentLeft - CONTENT_PAD_X, contentTop,
                contentRight + CONTENT_PAD_X, contentBottom);
        for (RowEntry row : tab.rows()) {
            if (row.getY() + row.getHeight() > contentTop && row.getY() < contentBottom) {
                row.extractRenderState(g, contentLeft, row.getY(), contentWidth,
                        mouseX, mouseY, partialTick);
            }
        }
        g.disableScissor();

        // 縦スクロールバー (= コンテンツ用)。 RTL ではコンテンツの左側 (= 画面左寄り) に出す。
        if (totalHeight > viewportHeight) {
            int sbRightX = rtl ? contentLeft - 1 : contentRight + 1;
            int sbY = contentTop;
            int sbH = viewportHeight;
            g.fill(sbRightX - 4, sbY, sbRightX, sbY + sbH, COLOR_SB_TRACK);
            int thumbH = Math.max(20, (int) ((double) viewportHeight / totalHeight * sbH));
            int thumbY = sbY + (int) ((double) this.scrollPx / (totalHeight - viewportHeight)
                    * (sbH - thumbH));
            // drag 中はサイドバー スクロールバーと同様に thumb を明るくして反応を伝える。
            int thumbColor = this.draggingContentV ? COLOR_SB_THUMB_DRAG : COLOR_SB_THUMB;
            g.fill(sbRightX - 4, thumbY, sbRightX, thumbY + thumbH, thumbColor);
        }
    }

    private void clampContentScroll(int totalHeight, int viewportHeight) {
        int max = Math.max(0, totalHeight - viewportHeight);
        if (this.scrollPx < 0) this.scrollPx = 0;
        if (this.scrollPx > max) this.scrollPx = max;
    }

    // ─── コンテンツ領域 ジオメトリ ヘルパ ───────────────────────────────
    // {@link #prepareActiveTabLayout} / {@link #renderContent} と同じ式で
    // コンテンツ領域 / スクロールバーの座標を再現する (= 描画とクリック判定をズラさない)。

    /** コンテンツ領域の左端 X。 RTL ではコンテンツが左側に来る。 */
    private int contentLeft() {
        return rtl() ? CONTENT_PAD_X : SIDEBAR_WIDTH + 1 + SIDEBAR_GAP + CONTENT_PAD_X;
    }
    /** コンテンツ領域の右端 X。 */
    private int contentRight() {
        return rtl() ? this.width - SIDEBAR_WIDTH - 1 - SIDEBAR_GAP - CONTENT_PAD_X
                : this.width - CONTENT_PAD_X;
    }
    /** コンテンツ領域の上端 Y。 */
    private int contentTop() {
        return HEADER_HEIGHT + 4;
    }
    /** コンテンツ領域の下端 Y (= フッタ上辺)。 */
    private int contentBottom() {
        return this.height - FOOTER_HEIGHT;
    }

    /** アクティブタブの全 row 合計高さ。 */
    private int activeTabContentHeight() {
        if (this.tabs.isEmpty()) return 0;
        int total = 0;
        for (RowEntry row : this.tabs.get(this.activeTab).rows()) {
            total += row.getHeight();
        }
        return total;
    }

    /**
     * コンテンツ用縦スクロールバー (= 右端、 RTL では左端) の矩形 {@code [x0, y0, x1, y1]} を返す。
     * 全 row がビューポートに収まりスクロール不要なら null。
     * {@link #renderContent} の描画式と完全に一致させる。
     */
    @Nullable
    private int[] contentScrollbarRect() {
        int total = activeTabContentHeight();
        int top = contentTop();
        int bottom = contentBottom();
        int viewportHeight = bottom - top;
        if (total <= viewportHeight) return null;
        int sbRightX = rtl() ? contentLeft() - 1 : contentRight() + 1;
        return new int[] { sbRightX - 4, top, sbRightX, top + viewportHeight };
    }

    private boolean isOverContentVScrollbar(double mx, double my) {
        int[] r = contentScrollbarRect();
        if (r == null) return false;
        return mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3];
    }

    /** コンテンツ スクロールバーの drag 開始。 thumb を掴めばオフセット保持、 track なら thumb 中央へジャンプ。 */
    private void startContentVDrag(double mouseY) {
        this.draggingContentV = true;
        int total = activeTabContentHeight();
        int top = contentTop();
        int viewportHeight = contentBottom() - top;
        int thumbH = Math.max(20, (int) ((double) viewportHeight / total * viewportHeight));
        int thumbY = top + (int) ((double) this.scrollPx / (total - viewportHeight) * (viewportHeight - thumbH));
        if (mouseY >= thumbY && mouseY < thumbY + thumbH) {
            this.sbDragOffset = mouseY - thumbY;
        } else {
            this.sbDragOffset = thumbH / 2.0;
            setContentScrollFromThumbTop(mouseY - thumbH / 2.0);
        }
    }

    private void updateContentVDrag(double mouseY) {
        setContentScrollFromThumbTop(mouseY - this.sbDragOffset);
    }

    /** thumb 上端 Y から {@link #scrollPx} を逆算する (= {@link #renderContent} の thumbY 式の逆)。 */
    private void setContentScrollFromThumbTop(double thumbTopY) {
        int total = activeTabContentHeight();
        int top = contentTop();
        int viewportHeight = contentBottom() - top;
        if (total <= viewportHeight) return;
        int thumbH = Math.max(20, (int) ((double) viewportHeight / total * viewportHeight));
        double frac = (thumbTopY - top) / Math.max(1.0, (viewportHeight - thumbH));
        frac = Math.max(0.0, Math.min(1.0, frac));
        this.scrollPx = frac * (total - viewportHeight);
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        // ─── ポップアップ最優先ルーティング ───
        // ピッカーが開いている間は、 widget / タブクリック / スクロールバーよりも先に
        // ポップアップへクリックを渡す。 外側クリックは「Cancel と同等」として閉じる
        // (popup 側で isClosed = true がセットされる)。
        // ポップアップが開いている限り、 内外問わずクリックは消費して背後を触らせない。
        if (this.activePopup != null) {
            this.activePopup.mouseClicked(mx, my, event.button());
            if (this.activePopup.isClosed()) {
                dismissPopup();
            }
            return true;
        }

        // ─── サイドバー縦スクロールバー ───
        if (event.button() == 0 && isOverSidebarVScrollbar(mx, my)) {
            startSidebarVDrag(my);
            return true;
        }
        // ─── サイドバー横スクロールバー ───
        if (event.button() == 0 && isOverSidebarHScrollbar(mx, my)) {
            startSidebarHDrag(mx);
            return true;
        }
        // ─── コンテンツ領域 (右端) 縦スクロールバー ───
        // widget 群より <b>先に</b> 判定して、 右端の widget とスクロールバーの当たり判定衝突を防ぐ。
        if (event.button() == 0 && isOverContentVScrollbar(mx, my)) {
            startContentVDrag(my);
            return true;
        }

        // ─── サイドバー クリック (= viewport 内のみ判定) ───
        // tab / group-header どちらの上にあるかを線形にスキャンして決定する。
        // RTL では viewport が画面右側へ移動しているので、 X 範囲を helper 経由で取る。
        if (mx >= sidebarTabViewportLeft() && mx < sidebarTabViewportRight()
                && my >= sidebarTop() && my < sidebarBottom() - SB_H_H) {
            int relY = (int) (my - sidebarTop() + this.sidebarScrollY);
            int yWalk = 0;
            for (SidebarEntry entry : this.sidebarEntries) {
                int entryH = entry.height();
                if (relY >= yWalk && relY < yWalk + entryH) {
                    if (entry instanceof TabEntry t) {
                        if (t.flatIndex() != this.activeTab) {
                            this.activeTab = t.flatIndex();
                            this.scrollPx = 0.0;
                            applyTabVisibility();
                        }
                    }
                    // グループ ヘッダクリックは consume するだけ (= no-op)。
                    return true;
                }
                yWalk += entryH;
            }
        }

        // ─── widget 系の super.mouseClicked を先に消費させる ───
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

        // ─── row 自前のクリック領域 (= ColorPaletteRow のスウォッチ等) を発火 ───
        // widget には拾われなかったクリックだけがここに到達するので、 衝突しない。
        if (!this.tabs.isEmpty()) {
            TabModel tab = this.tabs.get(this.activeTab);
            for (RowEntry row : tab.rows()) {
                if (row.mouseClicked(mx, my, event.button())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        // ポップアップが開いている時はドラッグもそちらへ。 SV/Hue ドラッグの追従に必須。
        if (this.activePopup != null) {
            this.activePopup.mouseDragged(event.x(), event.y(), event.button(), dx, dy);
            return true;
        }
        if (this.draggingSidebarV) {
            updateSidebarVDrag(event.y());
            return true;
        }
        if (this.draggingSidebarH) {
            updateSidebarHDrag(event.x());
            return true;
        }
        if (this.draggingContentV) {
            updateContentVDrag(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // ポップアップが開いていれば release もそちらに委譲 (drag mode リセット用)。
        if (this.activePopup != null) {
            this.activePopup.mouseReleased(event.x(), event.y(), event.button());
            return true;
        }
        if (event.button() == 0) {
            this.draggingSidebarV = false;
            this.draggingSidebarH = false;
            this.draggingContentV = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // ポップアップが開いている時はホイールもそちらへ (= dropdown / 確認 Popup のリスト スクロール用)。
        // OverlayPopup#mouseScrolled は default false なので、 スクロールを持たない popup は素通り。
        if (this.activePopup != null && this.activePopup.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (this.activePopup instanceof DropdownPopup<?> dd
                && dd.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        // サイドバー領域でのホイールはサイドバー縦スクロール。
        if (mouseX >= sidebarLeft() && mouseX < sidebarRight()
                && mouseY >= sidebarTop() && mouseY < sidebarBottom()) {
            this.sidebarScrollY -= scrollY * 18.0;
            return true;
        }
        // コンテンツ領域でのホイールはコンテンツ縦スクロール。
        // RTL ではコンテンツが左側に来るので「サイドバー以外の領域」で判定する。
        boolean inContentX = rtl()
                ? (mouseX >= 0 && mouseX < this.width - SIDEBAR_WIDTH)
                : (mouseX >= SIDEBAR_WIDTH + SIDEBAR_GAP && mouseX < this.width);
        if (inContentX && mouseY >= HEADER_HEIGHT
                && mouseY < this.height - FOOTER_HEIGHT) {
            this.scrollPx -= scrollY * 18.0;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // ポップアップが開いている時は Esc / Enter をそちらで吸う (= Screen 全体は閉じない)。
        if (this.activePopup != null) {
            if (this.activePopup.keyPressed(event.key())) {
                if (this.activePopup.isClosed()) {
                    dismissPopup();
                }
                return true;
            }
            return true; // ポップアップ中は他のキーも通常画面へ渡さない。
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            //? if >=26.2 {
            /*Minecraft.getInstance().setScreenAndShow(this.parent);*/
            //?} else {
            Minecraft.getInstance().setScreen(this.parent);
            //?}
            return true;
        }
        return super.keyPressed(event);
    }

    // ─── スクロールバー drag ヘルパ ─────────────────────────────────

    private boolean isOverSidebarVScrollbar(double mx, double my) {
        if (!needsSidebarVScroll()) return false;
        int x = sidebarVScrollbarLeft();
        int y = sidebarTop();
        int h = sidebarTabViewportH();
        return mx >= x && mx < x + SB_V_W && my >= y && my < y + h;
    }

    private boolean isOverSidebarHScrollbar(double mx, double my) {
        if (!needsSidebarHScroll()) return false;
        int x = sidebarTabViewportLeft();
        int y = sidebarBottom() - SB_H_H;
        return mx >= x && mx < x + sidebarTabViewportW() && my >= y && my < y + SB_H_H;
    }

    private void startSidebarVDrag(double mouseY) {
        this.draggingSidebarV = true;
        int y = sidebarTop();
        int h = sidebarTabViewportH();
        int totalH = sidebarContentTotalH();
        int thumbH = Math.max(20, (int) ((double) h / totalH * h));
        int thumbY = y + (int) ((double) this.sidebarScrollY / (totalH - h) * (h - thumbH));
        if (mouseY >= thumbY && mouseY < thumbY + thumbH) {
            // thumb 本体: 掴んだオフセットを保持。
            this.sbDragOffset = mouseY - thumbY;
        } else {
            // track クリック: thumb の中央へ瞬間移動。
            this.sbDragOffset = thumbH / 2.0;
            setSidebarScrollYFromThumbTop(mouseY - thumbH / 2.0);
        }
    }

    private void updateSidebarVDrag(double mouseY) {
        setSidebarScrollYFromThumbTop(mouseY - this.sbDragOffset);
    }

    private void setSidebarScrollYFromThumbTop(double thumbTopY) {
        int y = sidebarTop();
        int h = sidebarTabViewportH();
        int totalH = sidebarContentTotalH();
        int thumbH = Math.max(20, (int) ((double) h / totalH * h));
        double frac = (thumbTopY - y) / Math.max(1.0, (h - thumbH));
        frac = Math.max(0.0, Math.min(1.0, frac));
        this.sidebarScrollY = frac * (totalH - h);
    }

    private void startSidebarHDrag(double mouseX) {
        this.draggingSidebarH = true;
        int trackLeft = sidebarTabViewportLeft();
        int w = sidebarTabViewportW();
        int totalW = sidebarContentTotalW();
        int thumbW = Math.max(20, (int) ((double) w / totalW * w));
        // 描画と同じ式で thumb の絶対 X を求める。 RTL では frac を反転している。
        double frac = (double) this.sidebarScrollX / (totalW - w);
        if (rtl()) frac = 1.0 - frac;
        int thumbX = trackLeft + (int) (frac * (w - thumbW));
        if (mouseX >= thumbX && mouseX < thumbX + thumbW) {
            this.sbDragOffset = mouseX - thumbX;
        } else {
            this.sbDragOffset = thumbW / 2.0;
            setSidebarScrollXFromThumbLeft(mouseX - thumbW / 2.0);
        }
    }

    private void updateSidebarHDrag(double mouseX) {
        setSidebarScrollXFromThumbLeft(mouseX - this.sbDragOffset);
    }

    private void setSidebarScrollXFromThumbLeft(double thumbLeftX) {
        int trackLeft = sidebarTabViewportLeft();
        int w = sidebarTabViewportW();
        int totalW = sidebarContentTotalW();
        int thumbW = Math.max(20, (int) ((double) w / totalW * w));
        double frac = (thumbLeftX - trackLeft) / Math.max(1.0, (w - thumbW));
        frac = Math.max(0.0, Math.min(1.0, frac));
        // 描画時の反転に合わせて RTL ではユーザのドラッグ方向と scrollX を逆対応させる。
        if (rtl()) frac = 1.0 - frac;
        this.sidebarScrollX = frac * (totalW - w);
    }

    // ════════════════════════════════════════════════════════════════════
    // 公開ユーティリティ
    // ════════════════════════════════════════════════════════════════════

    /** ホーム画面 (タイトル / 一時停止) から開いた時の戻り先。 */
    @Nullable
    public Screen parent() {
        return this.parent;
    }

    /** タブ追加・差し替え用の簡易ファクトリ。 */
    public static OmniChestSettingsScreen create(@Nullable Screen parent, Component title,
            List<TabGroup> groups, Runnable onSave, Runnable onReset) {
        return new OmniChestSettingsScreen(parent, title, groups, onSave, onReset);
    }
}
