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
 *   <li>{@link #popupX} / {@link #popupY} の返り値は、 パネルが画面に収まる限り必ず
 *       {@code [margin, size - margin - panel]} の内側に入る。 収まらない場合でも
 *       {@code margin} 以上を返し、 左上/上端が画面外へ飛び出すことはない。</li>
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
    // 画面配置 (= クロスヘア直下 + 画面内クランプ)
    // ════════════════════════════════════════════════════════════════════

    /** クロスヘアとポップアップ上端の間隔 (論理 px)。 照準そのものを隠さないための下駄。 */
    public static final int CROSSHAIR_GAP = 12;
    /** 画面端との最小マージン (論理 px)。 */
    public static final int SCREEN_MARGIN = 4;

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

    /**
     * ポップアップの上端 Y。 まずクロスヘアの少し下に置き、 下に収まらなければ上へ回し、
     * どちらにも収まらなければ画面内へクランプする。
     */
    public static int popupY(int guiHeight, int panelHeight, int crosshairY, int gap, int margin) {
        int below = crosshairY + gap;
        if (below + panelHeight + margin <= guiHeight) {
            return below;
        }
        int above = crosshairY - gap - panelHeight;
        if (above >= margin) {
            return above;
        }
        int max = guiHeight - margin - panelHeight;
        if (max < margin) {
            return margin;
        }
        return Math.min(Math.max(below, margin), max);
    }

    /** {@link #popupX(int, int, int, int)} の既定マージン版。 */
    public static int popupX(int guiWidth, int panelWidth, int crosshairX) {
        return popupX(guiWidth, panelWidth, crosshairX, SCREEN_MARGIN);
    }

    /** {@link #popupY(int, int, int, int, int)} の既定 gap / マージン版。 */
    public static int popupY(int guiHeight, int panelHeight, int crosshairY) {
        return popupY(guiHeight, panelHeight, crosshairY, CROSSHAIR_GAP, SCREEN_MARGIN);
    }
}
