package com.kajiwara.omnichest.client.gui.search.preview;

import com.kajiwara.omnichest.gui.CursorPopupFit;
import com.kajiwara.omnichest.i18n.RTLLayoutManager;

/**
 * カーソル追従 Popup を「画面 / オーバーレイの邪魔をしない位置」 に置くための配置決定器。
 *
 * <p>
 * <b>実体は {@link CursorPopupFit}</b> (= {@code common} 側の MC 非依存な純関数) で、
 * 本クラスは <b>RTL 判定を足すだけの薄いラッパ</b>である。 配置規則そのもの・不変条件・
 * バニラとの差異・掃き出しテストは {@link CursorPopupFit} の javadoc を参照。
 * 旧定数 {@code CURSOR_OFFSET} / {@code SCREEN_MARGIN} は
 * {@link CursorPopupFit#CURSOR_GAP} / {@link CursorPopupFit#SCREEN_MARGIN} へ<b>移した</b>
 * (= 二重に持って食い違うのを避けるため、 ここには残していない)。
 *
 * <p>
 * <b>配置ポリシー</b> (要約):
 * <ul>
 *   <li><b>縦はカーソル中心揃え</b> = 「カーソルの真横」。 上下端はクランプ。</li>
 *   <li>LTR: カーソル右優先 → 右端からはみ出るならカーソル左へ折り返し。</li>
 *   <li>RTL: カーソル左優先 → 左端からはみ出るならカーソル右へ折り返し。</li>
 *   <li>間隔はスロット 1 マス ({@link CursorPopupFit#CURSOR_GAP}) で、 カーソルにも
 *       ホバー中スロットのアイコンにも被らない。</li>
 * </ul>
 *
 * <p>
 * <b>★この配置は 3 画面で共有している</b>: ALT ホバーのシュルカープレビュー
 * ({@link AltPreviewTooltip})、 倉庫検索画面の sticky preview
 * ({@code SearchScreen#renderStickyPreview})、 テンプレート管理画面のプレビュー
 * ({@code TemplateManagerScreen#renderTemplatePreview})。 <b>ここを変えると 3 つとも動く</b>。
 * 「配置規則を統一する」 のは意図した設計なので、 片方だけ変えたくなったら分岐ではなく
 * 別メソッドを足すこと。
 *
 * <p>
 * <b>REI / EMI / レシピビューア との共存</b>: それらの正確な overlay 矩形は MOD API 連携なしには
 * 取れないので、 直接の衝突判定はしない。 代わりに「画面端まで距離を取る」 + 「カーソル方向に
 * 重ねない」 の 2 点で運用上の干渉を最小化する。
 *
 * <p>
 * <b>GUI スケール変更</b>: 入力の {@code mouseX/mouseY} と {@code screenW/screenH} はスケール
 * 後の論理座標なので、 倍率変更で破綻しない。
 */
public final class AdaptiveTooltipPositioner {

    private AdaptiveTooltipPositioner() {
    }

    /**
     * 画面に収まる Popup 左上座標 (x, y) を返す。
     *
     * @param mouseX  カーソル X (= 論理座標)
     * @param mouseY  カーソル Y
     * @param w       Popup 幅
     * @param h       Popup 高さ
     * @param screenW 画面幅 (= スクリーンの this.width)
     * @param screenH 画面高 (= スクリーンの this.height)
     */
    public static int[] place(int mouseX, int mouseY, int w, int h, int screenW, int screenH) {
        return CursorPopupFit.place(mouseX, mouseY, w, h, screenW, screenH,
                RTLLayoutManager.get().isRtl());
    }
}
