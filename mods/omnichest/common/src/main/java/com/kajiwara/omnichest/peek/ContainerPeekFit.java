package com.kajiwara.omnichest.peek;

/**
 * コンテナ ピーク (= 設置済みコンテナに照準を合わせて中身を覗く機能) の
 * <b>状態判定 / 出典選択 / 画面配置</b> を担う純粋関数。 Minecraft 型に一切依存しないため
 * {@code common} 側に置き、 単体テスト可能にしている ({@link PeekFreshness} /
 * {@code GuiScaleFit} / {@code SidePanelFit} / {@code ExistingCategoriesFit} /
 * {@code TextContrastFit} と同じ流儀)。
 *
 * <p>
 * <b>この機能の性質</b>: バニラはコンテナのブロックエンティティの中身をクライアントへ同期しない
 * ({@code BlockEntity#getUpdatePacket} は {@code null} を返し、 チェスト / シュルカーは override
 * していない = バイトコード実測)。 したがってピークは <b>「既に開いて記録済みの中身を、
 * 開かずに引き出して見せる」</b> 読み取り専用ビューであり、 未記録なら未記録と正直に言う以外にない。
 * その 「何を言うべきか」 の判定が {@link #status}。
 *
 * <p>
 * <b>不変条件 (invariant)</b>:
 * <ul>
 *   <li>{@link #status} は<b>中身を捏造しない</b>: スナップショットが無ければ必ず
 *       {@link Status#NOT_RECORDED} 以外を返さない。</li>
 *   <li>エンダーチェストで収集設定が OFF のときは {@link Status#ENDER_SEARCH_DISABLED} を返す。
 *       これを {@code NOT_RECORDED} に潰すと 「一度開けば記録される」 という<b>誤誘導</b>になる
 *       (設定が OFF の間は何度開いても記録されないため)。</li>
 *   <li>{@link #popupX} の返り値は、 パネルが画面幅に収まる限り必ず
 *       {@code [margin, guiWidth - margin - panelWidth]} の内側に入る。 収まらない場合でも
 *       {@code margin} 以上を返し、 左端が画面外へ飛び出すことはない。</li>
 *   <li>{@link #popupY} は<b>画面下端ではなく {@link #BOTTOM_HUD_HEIGHT} を除いた安全帯</b>を
 *       下端の基準にする。 安全帯にパネルが収まる限り、 返り値は必ず
 *       {@code [margin, guiHeight - BOTTOM_HUD_HEIGHT - panelHeight]} の内側に入る
 *       (= ホットバー / 経験値バー / 体力・満腹度 / 防具・空気 / 持ち替えアイテム名 に
 *       重ならない)。</li>
 * </ul>
 */
public final class ContainerPeekFit {

    private ContainerPeekFit() {
    }

    // ════════════════════════════════════════════════════════════════════
    // 状態
    // ════════════════════════════════════════════════════════════════════

    /** ピークが表示すべき内容の種別。 */
    public enum Status {
        /** スナップショットがある = グリッドを描く。 */
        AVAILABLE,
        /** まだ一度も開いていない = 「未登録」 と正直に出す。 */
        NOT_RECORDED,
        /**
         * 視線先がエンダーチェストで、 かつ {@code search.enableEnderChestSearch} が OFF。
         * 「一度開くと記録します」 は嘘になるので、 設定が無効である旨を案内する。
         */
        ENDER_SEARCH_DISABLED
    }

    /**
     * ポップアップに出すべき状態を決める。
     *
     * @param enderChest          視線先がエンダーチェストか
     * @param enderSearchEnabled  {@code search.enableEnderChestSearch} の現在値
     * @param hasSnapshot         キャッシュから引けたか (エンダーチェストの場合は
     *                            {@link #pickLatestIndex} で 1 件選べたか)
     */
    public static Status status(boolean enderChest, boolean enderSearchEnabled, boolean hasSnapshot) {
        if (enderChest && !enderSearchEnabled) {
            // 収集そのものが止まっている。 スナップショットが (設定を切る前の残骸として)
            // 残っていても、 今後更新されないことを伝えるほうが誠実なので設定案内を優先する。
            return Status.ENDER_SEARCH_DISABLED;
        }
        return hasSnapshot ? Status.AVAILABLE : Status.NOT_RECORDED;
    }

    // ════════════════════════════════════════════════════════════════════
    // 出典選択 (= エンダーチェストの座標非依存な共有)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 「最後に観測した 1 件」 の添字を返す。 空 / null なら {@code -1}。
     *
     * <p>
     * エンダーチェストの中身はバニラではプレイヤー単位で共有され、 <b>どのブロックから開いても同一</b>。
     * 一方キャッシュは 「開いたブロック座標ごと」 にスナップショットを持つため、 座標どおりに引くと
     * 「A は見えるが B は未登録」 という<b>バニラ仕様と食い違う</b>表示になる。 そこで
     * エンダーチェストに限り、 種別が一致するスナップショット群から {@code lastSeenMillis} が
     * 最大のものを選ぶ (= 座標非依存)。
     *
     * <p>
     * <b>キャッシュは書き換えない</b>。 これは読み出し時の選択規則にすぎない。
     *
     * <p>
     * 同値が複数ある場合は<b>先に現れたほう</b>を選ぶ (= 決定論的。 走査順が同じなら結果も同じ)。
     */
    public static int pickLatestIndex(long[] lastSeenMillis) {
        if (lastSeenMillis == null || lastSeenMillis.length == 0) {
            return -1;
        }
        int best = 0;
        for (int i = 1; i < lastSeenMillis.length; i++) {
            if (lastSeenMillis[i] > lastSeenMillis[best]) {
                best = i;
            }
        }
        return best;
    }

    // ════════════════════════════════════════════════════════════════════
    // グリッド列数
    // ════════════════════════════════════════════════════════════════════

    /** 既存プレビュー Popup の列数下限 (= {@code PopupThemeResolver.MIN_COLUMNS} と同値)。 */
    public static final int MIN_COLUMNS = 5;
    /** 既存プレビュー Popup の列数上限 (= {@code PopupThemeResolver.MAX_COLUMNS} と同値)。 */
    public static final int MAX_COLUMNS = 11;
    /** バニラのコンテナ GUI の 1 行あたりスロット数。 */
    public static final int VANILLA_ROW_WIDTH = 9;

    /**
     * このスロット数のコンテナをバニラと同じ見た目で並べるための列数を返す。
     *
     * <p>
     * バニラのチェスト / シュルカー / 樽はすべて 9 列なので、 9 列で割り切れる (= 27 / 54)
     * ものは 9 列にする。 ホッパー等の少スロットは 9 列だと横に間延びするため、 スロット数
     * そのものを列数にして 1 行に収める。 いずれも既存 Popup の許容範囲
     * {@code [MIN_COLUMNS, MAX_COLUMNS]} へクランプする (= {@code clampColumns} と同じ規則)。
     */
    public static int gridColumns(int slotCount) {
        if (slotCount <= 0) {
            return MIN_COLUMNS;
        }
        int cols = (slotCount % VANILLA_ROW_WIDTH == 0) ? VANILLA_ROW_WIDTH : slotCount;
        if (cols < MIN_COLUMNS) {
            return MIN_COLUMNS;
        }
        if (cols > MAX_COLUMNS) {
            return MAX_COLUMNS;
        }
        return cols;
    }

    // ════════════════════════════════════════════════════════════════════
    // 画面配置 (= クロスヘア基準 + 下部 HUD 回避 + 画面内クランプ)
    // ════════════════════════════════════════════════════════════════════

    /** クロスヘアとポップアップの間隔 (論理 px)。 照準そのものを隠さないための下駄。 */
    public static final int CROSSHAIR_GAP = 12;
    /** 画面端との最小マージン (論理 px)。 */
    public static final int SCREEN_MARGIN = 4;

    // ─── バニラ HUD の縦占有 (すべて MC 26.1.2 のバイトコード実測値) ───────────
    //   ここの数値を勝手に発明しないこと。 出典を各定数の javadoc に明記してある。

    /** ホットバー上端。 {@code Gui#extractItemHotbar} が {@code guiHeight - 22} に 22px の帯を描く。 */
    public static final int HOTBAR_TOP_OFFSET = 22;
    /**
     * 経験値 / 騎乗ジャンプ バーの上端。
     * {@code ContextualBarRenderer#top} = {@code guiHeight - MARGIN_BOTTOM(24) - HEIGHT(5)}。
     */
    public static final int CONTEXTUAL_BAR_TOP_OFFSET = 29;
    /** 体力 / 満腹度の行の上端。 {@code Gui#extractPlayerHealth} が {@code guiHeight - 39} を使う。 */
    public static final int STATUS_BAR_TOP_OFFSET = 39;
    /** 防具 / 空気の行の上端。 {@code Gui#extractArmor} は体力行の 10px 上に描く。 */
    public static final int ARMOR_BAR_TOP_OFFSET = STATUS_BAR_TOP_OFFSET + 10;
    /**
     * 持ち替えたアイテム名の表示上端。 {@code Gui#extractSelectedItemName} が
     * {@code guiHeight - 59} を使う。 一時表示だが、 ポップアップと同じく<b>画面中央下</b>に出るため
     * ここまで含めて避ける。
     */
    public static final int HELD_ITEM_NAME_TOP_OFFSET = 59;

    /**
     * ポップアップが侵入してはならない画面下部の帯の高さ (論理 px)。
     *
     * <p>
     * 上の実測値の<b>最大</b>を採る (= 最も高い位置に出る要素まで避ける)。 クリエイティブでは
     * 体力 / 満腹度 / 経験値バーが描かれず実際の占有は 22px まで縮むが、 <b>広いほうに倒して
     * おけばモードによらず安全</b>なので分岐しない (= 判定を 1 本に保つ)。 F1 (hideGui) 中は
     * エンジンが HUD 描画そのものをスキップしポップアップも呼ばれないため、 考慮不要。
     */
    public static final int BOTTOM_HUD_HEIGHT = HELD_ITEM_NAME_TOP_OFFSET;

    /** クロスヘアの一辺 (px)。 {@code Gui#extractCrosshair} が 15x15 のスプライトを中央に描く。 */
    public static final int CROSSHAIR_SIZE = 15;
    /** クロスヘア中心から端までの距離 (= {@code ceil(15/2)})。 「覆っていない」 判定の基準。 */
    public static final int CROSSHAIR_HALF = (CROSSHAIR_SIZE + 1) / 2;

    /**
     * ポップアップの左端 X。 クロスヘア中心に対して水平中央寄せし、 画面内へクランプする。
     *
     * <p>
     * パネルが画面より広い場合 (= 極端に低い GUI スケール / 極小ウィンドウ) は
     * {@code margin} を返す (= 左端を優先し、 右へはみ出させる)。 左上に飛び出して
     * 既存の選択アイテム HUD と重なるより、 右へ流れるほうが被害が小さいため。
     */
    public static int popupX(int guiWidth, int panelWidth, int crosshairX, int margin) {
        int desired = crosshairX - panelWidth / 2;
        int max = guiWidth - margin - panelWidth;
        if (max < margin) {
            return margin;
        }
        if (desired < margin) {
            return margin;
        }
        if (desired > max) {
            return max;
        }
        return desired;
    }

    /** ポップアップをどこに置いたか。 {@link #layout} が返す。 */
    public enum Placement {
        /** クロスヘアの下 (= 既定)。 対象ブロックの見通しが最も良い。 */
        BELOW,
        /** クロスヘアの上へ反転。 下に置くと HUD 帯に食い込むため。 */
        ABOVE,
        /** クロスヘアの右へ逃がす。 上下どちらにも入らない縦長パネル用。 */
        SIDE_RIGHT,
        /** クロスヘアの左へ逃がす。 右に入らないとき。 */
        SIDE_LEFT,
        /**
         * どこにも置けない極小画面での最後の砦 (= 左上へクランプ)。
         * <b>これだけはクロスヘア非重複を保証しない</b>。 サポート範囲
         * ({@code Window#calculateScale} が保証する論理 320x240 以上) では発生しない
         * ({@code ContainerPeekPlacementTest} が掃き出しで確認)。
         */
        CLAMPED
    }

    /**
     * 配置の結果。 {@code compact} が true なら、 グリッドがどこにも置けなかったので
     * 要約リスト (= コンパクトモード) に切り替えたことを意味する。
     */
    public record Layout(Placement placement, boolean compact,
            int x, int y, int width, int height) {
    }

    /**
     * ポップアップの配置を決める。
     *
     * <p>
     * <b>満たす条件</b> ({@link Placement#CLAMPED} 以外):
     * <ol>
     *   <li>画面下部の HUD 帯 ({@link #BOTTOM_HUD_HEIGHT}) に侵入しない。 単純に
     *       {@code guiHeight - margin} を下端にすると、 6 行グリッド (= ラージチェスト 54 スロット、
     *       高さ 154px) のサマリ行がホットバーに重なる (実機で報告された不具合)。</li>
     *   <li>画面外へ出ない。</li>
     *   <li><b>クロスヘアの矩形と 1px も交差しない</b>。 {@link #CROSSHAIR_GAP} が
     *       {@link #CROSSHAIR_HALF} より大きいので、 BELOW / ABOVE / SIDE_* のいずれでも
     *       構造的に保証される。</li>
     *   <li>タイトル行とサマリ行が常に可視 (= パネル全体が安全帯の中に入る)。</li>
     * </ol>
     *
     * <p>
     * <b>なぜ「クロスヘアを手前に描く」ではなく「重ねない」なのか</b>: 描画順を変えて
     * クロスヘアを手前に出す方法では、 <b>クロスヘアがグリッド内のアイテムに重なって
     * そのアイテムが読めなくなる</b> (実機で報告)。 「見える」 と 「読める」 は別なので、
     * 重ねること自体をやめる。
     *
     * <p>
     * <b>優先順位</b>: 下 → 上 → 右 → 左 → (グリッドを諦めて) コンパクトで 下 → 上 → 右 → 左。
     * <b>横に逃がす手を入れたことで、 縦に入らないケースの大半 (実測 120 件中 90 件) が
     * グリッドのまま解決する</b>。 残りだけがコンパクトへ落ちる。
     *
     * @param compactWidth  コンパクト表示にしたときのパネル幅 (= 呼び出し側が実測して渡す)
     * @param compactHeight コンパクト表示にしたときのパネル高
     * @param bottomHudHeight 画面下部で避けるべき帯の高さ (通常 {@link #BOTTOM_HUD_HEIGHT})
     */
    public static Layout layout(int guiWidth, int guiHeight, int crosshairX, int crosshairY,
            int gridWidth, int gridHeight, int compactWidth, int compactHeight,
            int gap, int margin, int bottomHudHeight) {
        Layout grid = tryPlace(false, guiWidth, guiHeight, crosshairX, crosshairY,
                gridWidth, gridHeight, gap, margin, bottomHudHeight);
        if (grid != null) {
            return grid;
        }
        Layout compact = tryPlace(true, guiWidth, guiHeight, crosshairX, crosshairY,
                compactWidth, compactHeight, gap, margin, bottomHudHeight);
        if (compact != null) {
            return compact;
        }
        // ここへ来るのは MC が許さないサイズ (= 論理 320x240 未満) だけ。 タイトルを優先して残す。
        return new Layout(Placement.CLAMPED, true, margin, margin, compactWidth, compactHeight);
    }

    /** {@link #layout} の既定 gap / マージン / HUD 帯版。 */
    public static Layout layout(int guiWidth, int guiHeight, int crosshairX, int crosshairY,
            int gridWidth, int gridHeight, int compactWidth, int compactHeight) {
        return layout(guiWidth, guiHeight, crosshairX, crosshairY,
                gridWidth, gridHeight, compactWidth, compactHeight,
                CROSSHAIR_GAP, SCREEN_MARGIN, BOTTOM_HUD_HEIGHT);
    }

    /** 指定サイズのパネルを 下 → 上 → 右 → 左 の順に試す。 どこにも置けなければ null。 */
    private static Layout tryPlace(boolean compact, int guiWidth, int guiHeight,
            int crosshairX, int crosshairY, int w, int h,
            int gap, int margin, int bottomHudHeight) {
        int safeBottom = guiHeight - Math.max(margin, bottomHudHeight);

        // (1) クロスヘアの下。
        if (crosshairY + gap + h <= safeBottom) {
            return new Layout(Placement.BELOW, compact,
                    popupX(guiWidth, w, crosshairX, margin), crosshairY + gap, w, h);
        }
        // (2) クロスヘアの上へ反転。 単に上へずらすと狙っているブロックを覆うため、 反転にする。
        if (crosshairY - gap - h >= margin) {
            return new Layout(Placement.ABOVE, compact,
                    popupX(guiWidth, w, crosshairX, margin), crosshairY - gap - h, w, h);
        }
        // (3)(4) 横へ逃がす。 横で交差しないので、 縦は安全帯に収まりさえすればよい。
        if (h <= safeBottom - margin) {
            int y = clamp(crosshairY - h / 2, margin, safeBottom - h);
            int right = crosshairX + gap;
            if (right + w <= guiWidth - margin) {
                return new Layout(Placement.SIDE_RIGHT, compact, right, y, w, h);
            }
            int left = crosshairX - gap - w;
            if (left >= margin) {
                return new Layout(Placement.SIDE_LEFT, compact, left, y, w, h);
            }
        }
        return null;
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** {@link #popupX(int, int, int, int)} の既定マージン版。 */
    public static int popupX(int guiWidth, int panelWidth, int crosshairX) {
        return popupX(guiWidth, panelWidth, crosshairX, SCREEN_MARGIN);
    }
}
