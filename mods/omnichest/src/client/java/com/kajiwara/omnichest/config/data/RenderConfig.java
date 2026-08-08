package com.kajiwara.omnichest.config.data;

/**
 * Render / UI 設定 (= 画面表示・装飾系)。
 *
 * <p>
 * 各機能のオーバーレイ・カテゴリラベル・GUI アニメーション ON/OFF を一括で扱う。
 */
public final class RenderConfig {

    /** ハイライト・バッジ・スロット枠などのオーバーレイ全体を ON/OFF する。 */
    public boolean enableOverlay = true;

    /**
     * 「選択アイテム情報 HUD」 (= 検索でクリック固定したアイテムの名前 / 合計個数 / 場所を
     * プレイ中の画面左上に読み取り専用で常時表示) を ON/OFF する。 既定 <b>true</b>。
     * <p>
     * これは<b>表示専用</b>の好み設定。 OFF にしてもハイライト・ピン・検索索引などの内部ロジックは
     * 一切止めない (= HUD パネルを描画しないだけ)。 キーバインド ({@code toggle_selected_item_hud}) や
     * {@code /omnichest hud <on|off>} で即座に切り替えできる。
     */
    public boolean showSelectedItemHud = true;

    /**
     * 「ディメンション別アイテム一覧メニュー」 ({@link com.kajiwara.omnichest.client.gui.DimensionMenuScreen})
     * を <b>既定 Alt+C</b> のグローバル ポールで開閉するか。 既定 <b>true</b>。
     * <p>
     * Alt+C が他 Mod / 用途と衝突する場合は OFF にして、 再割当可能なキーバインド
     * ({@code key.omnichest.toggle_dimension_menu}・既定 未バインド) 側に好みのキーを割り当てる。
     * メニュー自体の存在には影響しない (= Alt+C ポールの有効/無効だけを切り替える表示専用設定)。
     */
    public boolean dimensionMenuAltC = true;

    /**
     * 「コンテナ ピーク」 (= 設置済みコンテナに照準を合わせ、 修飾キーを押している間だけ
     * 中身をポップアップ表示する読み取り専用ビュー) を有効化するか。 既定 <b>false</b>。
     * <p>
     * <b>既定 OFF の理由</b>: 既存ユーザーの操作感を勝手に変えないため。 OFF の間は
     * {@code ContainerPeekRenderer} が最初の 1 行で return し、 視線判定 ({@code mc.hitResult})
     * にもキャッシュ参照にも一切入らない (= 性能影響ゼロを構造で保証)。
     * <p>
     * <b>これは読み取り専用の表示機能</b>: バニラはコンテナの中身をクライアントへ同期しないため、
     * 表示できるのは 「既に開いて記録済みのスナップショット」 だけ。 サーバへの問い合わせも
     * キャッシュへの書き込みも一切行わない。 未記録のコンテナには 「未登録」 と正直に表示する。
     * <p>
     * キーは再割当可能な {@code key.omnichest.container_peek} (既定 <b>Z</b>)。 未割当にすれば
     * ON のままでも発火しない。
     */
    public boolean enableContainerPeek = false;

    /**
     * ハイライト枠の色 (0xRRGGBB)。
     * Cloth Config の Color Picker (= startColorField) で編集する想定。
     * デフォルト 0xFFAA00 (= オレンジ)。
     */
    public int highlightColorRgb = 0xFFAA00;

    /** チェストの上方に「[ORE STORAGE]」などのカテゴリラベルを表示するか。 */
    public boolean showCategoryLabels = true;

    /** スロット ホバー時の補足 Tooltip (= [LOCKED] 等の追加行) を表示するか。 */
    public boolean enableTooltips = true;

    /**
     * GUI 全般のアニメーション (= フェード, スライド) を有効化するか。
     * <p>
     * 既定値は <b>false</b>: 大量にチェストを開閉する実プレイ中は、 アニメーションの遅延より
     * 「今すぐ表示」 の即応性のほうが体感を損ねないため。 雰囲気重視で使いたい場合は
     * 設定 GUI から ON に切り替える。
     */
    public boolean guiAnimation = false;

    // ════════════════════════════════════════════════════════════════════
    // Main Menu Visibility (= チェスト GUI 上に出る OmniChest 各要素の表示 ON/OFF)
    //
    // <b>方針 (= タスク #7/#8)</b>:
    //   - すべて既定 <b>true</b> = 既存挙動を完全維持 (新規導入で見た目が変わらない)。
    //   - これは <b>表示専用</b> の好み設定。 OFF にしても倉庫検索 / 分類 / 自動振り分け / 索引などの
    //     <b>内部ロジックは一切止めない</b> (= 該当ウィジェットを生成/描画しないだけ)。
    //   - GSON は欠落フィールドを初期化子の値で埋めるため、 旧 omnichest.json でも自動的に true。
    //
    // 個々のチェスト GUI 要素 (= {@code GenericContainerScreenMixin} が生成する実コンポーネント) に
    // 1:1 対応する。 存在しない架空のコントロールは作らない。
    // ════════════════════════════════════════════════════════════════════

    /**
     * チェスト GUI 内の検索バー (EditBox) を表示するか。
     *
     * <p>
     * ⚠ <b>既知の不具合 (1.1.1 時点): この欄は現在<u>無機能</u></b> — 入力を受け取る配線と
     * 絞り込み描画が 1.21.11 移行時に削除されたまま復旧していないため、 打っても何も起きない
     * (詳細は {@code GenericContainerScreenMixin} の生成箇所のコメント / CHANGELOG 1.1.1 の
     * 「既知の不具合」 を参照)。 このトグルは<b>欄の表示有無だけ</b>を制御する。
     * 倉庫検索 ({@code SearchScreen}) / 分類 / 索引 は本設定とは無関係に従来どおり動作する。
     */
    public boolean showSearchBar = true;
    /** 「種類」 (Type) ソートボタン。 */
    public boolean showSortByType = true;
    /** 「数量」 (Count) ソートボタン。 */
    public boolean showSortByCount = true;
    /** 「倉庫検索」 (Chest Search) ボタン。 */
    public boolean showChestSearchButton = true;
    /** 「カテゴリ整理」 (Category Sort) ボタン。 */
    public boolean showCategorySortButton = true;
    /** 「同種預入」 (Deposit Matching) ボタン。 */
    public boolean showDepositButton = true;
    /** 「圧縮」 (Compact) ボタン。 */
    public boolean showCompactButton = true;
    /** テンプレート 3 連 (保存 / 適用 / 管理) ボタン。 */
    public boolean showTemplateButtons = true;
    /** 「カテゴリ設定」 (Set Category) ボタン。 */
    public boolean showSetCategoryButton = true;
    /** 「自動振り分け」 (Category Auto Sort) ボタン。 */
    public boolean showAutoSortButton = true;
    /** カテゴリインジケータ (= チェスト上部の {@code [○○倉庫]} バッジ)。 */
    public boolean showCategoryIndicator = true;
    /** 予測表示 (= バッジ内の Confidence% / Manual 補足)。 */
    public boolean showPredictionDisplay = true;
    /** 操作方法ヘルプパネル (= チェスト脇の早見表)。 */
    public boolean showControlsHelp = true;
}
