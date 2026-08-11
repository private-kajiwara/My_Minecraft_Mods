package com.kajiwara.omnichest.gui;

/**
 * <b>カーソルに追従するポップアップ</b> (= ALT シュルカープレビュー / 倉庫検索の sticky preview /
 * テンプレート管理のプレビュー) の画面配置を決める純粋関数。 Minecraft 型に一切依存しないため
 * {@code common} 側に置き、 単体テスト可能にしている ({@link GuiScaleFit} / {@link SidePanelFit} /
 * {@link ExistingCategoriesFit} / {@link TextContrastFit} /
 * {@code ContainerPeekFit} / {@code PeekSummary} / {@code ChestSearchQuery} と同じ流儀)。
 *
 * <p>
 * <b>配置ポリシー</b>:
 * <ul>
 *   <li><b>縦はカーソル中心揃え</b> ({@code y = cursorY - panelH / 2})。 パネルの縦中心が
 *       カーソルの高さに一致する = 「カーソルの真横」 に出る。 画面上下端はクランプする。</li>
 *   <li>横は LTR ならカーソルの右、 右端で溢れるならカーソルの左へ<b>反転</b>
 *       (RTL は左右を入れ替えたミラー)。</li>
 *   <li>間隔は {@link #CURSOR_GAP}、 画面端は {@link #SCREEN_MARGIN} を空ける。</li>
 * </ul>
 *
 * <p>
 * <b>★バニラとの差異 (誤解しないための記録)</b>: バニラの
 * {@code DefaultTooltipPositioner.positionTooltip} は {@code (mouseX + 12, mouseY - 12)}
 * = <b>カーソルの右「上」</b> に置き、 右端で溢れたら {@code x - 24 - w} へ反転、 下端で溢れたら
 * {@code y = screenH - h - 3} へ押し上げる (上端クランプは無い) ── MC 26.1.2 のバイトコード実測。
 * <b>本クラスはバニラを踏襲していない</b>。 縦をカーソル中心へ揃えるのは OmniChest 固有の方針で、
 * グリッド状のポップアップはバニラの縦長ツールチップより背が高く、 右上に出すと視線が
 * カーソルから大きく離れるためである。 旧実装の 「バニラと同程度の距離感」 という注記は
 * 距離の<b>大きさ</b>にしか当てはまらず (符号は逆だった)、 誤解を招くので撤回した。
 *
 * <p>
 * <b>★設計上の裏付け</b>: 「カーソル (クロスヘア) の高さに縦中心を揃えて横へ逃がす」 という
 * 発想は、 既に {@code ContainerPeekFit.tryPlace} の {@code SIDE_RIGHT / SIDE_LEFT} 分岐
 * ({@code y = clamp(crosshairY - h / 2, ...)}) が同じ形で持っている。 <b>ただしコードは共有しない</b>:
 * ピークは 「下 → 上 → 横」 の縦優先で、 画面下部 HUD 帯の回避とコンパクト表示への
 * フォールバックを持つ。 こちらは横優先で HUD 帯もコンパクトも無い。 優先順位が直交しており、
 * 共通化すると {@code ContainerPeekFit} に分岐が増えて 1.6.0 のピーク配置を触ることになるため、
 * <b>意図的に別実装</b>にしている。
 *
 * <p>
 * <b>不変条件 (invariant)</b>:
 * <ul>
 *   <li>{@link #canCenterVertically} が true のとき、 {@link #placeY} は必ず
 *       {@code cursorY - panelH / 2} を返す (= 縦中心がカーソルに一致)。 false のときだけ
 *       上下端へクランプする。</li>
 *   <li>{@link #fitsBesideCursor} が true のとき、 {@link #placeX} が返す矩形は
 *       カーソル矩形 ({@link #CURSOR_SPRITE}) ともホバー中スロット矩形 ({@link #SLOT_SIZE}) とも
 *       <b>交差しない</b>。 false のときはカーソルの左右どちらにもパネルが収まらない極小論理画面
 *       なので、 非交差は幾何学的に達成できない (= 左端へクランプして可読性を優先する)。</li>
 *   <li>パネルが画面より高い場合 ({@code panelH > screenH - 2 * SCREEN_MARGIN}) は
 *       {@link #SCREEN_MARGIN} を返し、 <b>上端を優先</b>する (= タイトルとグリッド上部を残す)。
 *       この場合だけ下端がはみ出す。</li>
 * </ul>
 */
public final class CursorPopupFit {

    /**
     * カーソルとパネルの水平間隔 (論理 px)。
     *
     * <p>
     * <b>由来</b>: {@code PopupThemeResolver.CELL} (= バニラのスロット 1 マス = 18px) と<b>同値</b>。
     * 新しいマジックナンバーではない。 縦をカーソル中心へ揃えると縦方向の逃げが無くなるため、
     * 非交差を担保するのは水平間隔だけになる。 カーソルはホバー中スロットの<b>内側のどこにでも</b>
     * 置けるので、 スロット矩形は最悪 {@code cursorX ± (SLOT_SIZE - 1)} まで伸びる。 したがって
     * スロットアイコンに被らない最小値がちょうど {@link #SLOT_SIZE} = {@code CELL} になる
     * (掃き出しで実測: 16 ではスロットに、 14 以下ではカーソルにも被る)。
     */
    public static final int CURSOR_GAP = 18;

    /** 画面端の最小マージン (論理 px)。 REI/EMI 等の常駐 overlay と距離を取るための余裕値。 */
    public static final int SCREEN_MARGIN = 6;

    /**
     * カーソル スプライトの一辺 (論理 px)。 OS のカーソルは物理 px なので GUI スケールが上がるほど
     * 論理サイズは<b>縮む</b>。 最悪ケース (= GUI スケール 1) を採って 16 とする。 非交差判定の基準。
     */
    public static final int CURSOR_SPRITE = 16;

    /** ホバー中スロットの一辺 (論理 px)。 {@code PopupThemeResolver.CELL} と同値。 非交差判定の基準。 */
    public static final int SLOT_SIZE = 18;

    private CursorPopupFit() {
    }

    /**
     * パネル左上の座標 {@code (x, y)} を返す。
     *
     * @param cursorX カーソル X (= 論理座標)
     * @param cursorY カーソル Y (= 論理座標)
     * @param panelW  パネル幅
     * @param panelH  パネル高
     * @param screenW 論理画面幅
     * @param screenH 論理画面高
     * @param rtl     RTL ロケールなら左右をミラーする
     */
    public static int[] place(int cursorX, int cursorY, int panelW, int panelH,
            int screenW, int screenH, boolean rtl) {
        return new int[] {
                placeX(cursorX, panelW, screenW, rtl),
                placeY(cursorY, panelH, screenH),
        };
    }

    /**
     * パネル左端 X。 LTR はカーソル右優先 → 収まらなければ左へ反転。 RTL はその鏡像。
     *
     * <p>
     * 左右どちらにも収まらない極小論理画面では {@link #SCREEN_MARGIN} へクランプする
     * (= カーソルに被るが、 画面外へ飛ばして読めなくなるよりは良い)。 この状況は
     * {@link #fitsBesideCursor} が false を返す場合と一致する。
     */
    public static int placeX(int cursorX, int panelW, int screenW, boolean rtl) {
        int x;
        if (rtl) {
            x = cursorX - CURSOR_GAP - panelW;
            if (x < SCREEN_MARGIN) {
                x = cursorX + CURSOR_GAP;
            }
        } else {
            x = cursorX + CURSOR_GAP;
            if (x + panelW > screenW - SCREEN_MARGIN) {
                x = cursorX - CURSOR_GAP - panelW;
            }
        }
        if (x < SCREEN_MARGIN) {
            x = SCREEN_MARGIN;
        }
        return x;
    }

    /**
     * パネル上端 Y。 <b>縦中心をカーソルに揃え</b>、 画面の上下端でクランプする。
     *
     * <p>
     * {@link #canCenterVertically} が true ならクランプは働かず、 返り値は必ず
     * {@code cursorY - panelH / 2} になる。
     */
    public static int placeY(int cursorY, int panelH, int screenH) {
        int lo = SCREEN_MARGIN;
        int hi = screenH - SCREEN_MARGIN - panelH;
        if (hi < lo) {
            // パネルが画面より高い。 上端を優先して残す (下端ははみ出す)。
            return lo;
        }
        int y = cursorY - panelH / 2;
        if (y < lo) {
            return lo;
        }
        return Math.min(y, hi);
    }

    /**
     * 「縦中心をカーソルに揃えられるか」 (= {@link #placeY} がクランプせずに済むか)。
     *
     * <p>
     * カーソルが画面の上端寄り / 下端寄りにあると、 中心揃えの結果が画面外へ出るためクランプが
     * 働く。 テストで 「縦中心一致」 を要求してよいのは本メソッドが true のときだけである。
     */
    public static boolean canCenterVertically(int cursorY, int panelH, int screenH) {
        int lo = SCREEN_MARGIN;
        int hi = screenH - SCREEN_MARGIN - panelH;
        if (hi < lo) {
            return false;
        }
        int want = cursorY - panelH / 2;
        return want >= lo && want <= hi;
    }

    /**
     * 「カーソルの左右どちらかに、 間隔を空けてパネルが収まるか」。
     *
     * <p>
     * false のときはカーソル矩形 / ホバー中スロット矩形との非交差が<b>幾何学的に不可能</b>
     * (= パネル幅がカーソルの左右いずれの余白よりも広い極小論理画面)。 このときだけ
     * {@link #placeX} は左端へクランプして重なりを許容する。
     */
    public static boolean fitsBesideCursor(int cursorX, int panelW, int screenW) {
        boolean right = cursorX + CURSOR_GAP + panelW <= screenW - SCREEN_MARGIN;
        boolean left = cursorX - CURSOR_GAP - panelW >= SCREEN_MARGIN;
        return right || left;
    }
}
