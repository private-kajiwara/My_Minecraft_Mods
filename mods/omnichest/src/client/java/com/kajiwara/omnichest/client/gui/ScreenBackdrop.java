package com.kajiwara.omnichest.client.gui;

import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * 「自前のパネル背景を持たない」 OmniChest 画面のための、 全面の暗転 1 枚。
 *
 * <p>
 * <b>なぜ必要か</b>: バニラが in-world の Screen に敷く暗転は
 * {@code textures/gui/inworld_menu_background.png} のタイルだけで、 実測すると
 * <b>16x16 の全ピクセルが gray=0 / alpha=64 = ARGB 0x40000000 (黒 25%)</b> しかない
 * (26.1.2 の jar から取り出してデコード)。 ぼかしは {@code Options#getMenuBackgroundBlurriness()}
 * が 1 以上のときだけ走るため、 ユーザ設定で 0 にすると<b>ぼかし自体が発生しない</b>。
 * このため シェーダー環境の雪原など高輝度の場面では、 世界がほぼ素通しで文字と競合する。
 * 実測で {@code ×N} (0xAAAAAA) のコントラストは <b>1.12:1</b> = 実質不可視だった。
 *
 * <p>
 * <b>対象</b>: 自前のパネル背景を描いていない 4 画面
 * ({@code ExistingCategoriesScreen} / {@code SetCategoryScreen} /
 * {@code TemplateSaveScreen} / {@code TemplatePreviewScreen})。
 * 既に {@link ThemeColorResolver#PANEL_BG} などのパネルを敷いている画面
 * (倉庫検索 / 振り分け / 設定 / テンプレ管理 / ディメンション / 振り分けプレビュー) には<b>敷かない</b>。
 * 同じ 80% を重ねるとパネルと地の濃さが並び、 <b>パネルが領域として見えなくなる</b>ため。
 * チェスト GUI に重ねる描画 (スロットオーバーレイ / 検索ピン / ビーム) も対象外
 * (インベントリが見えなくなる)。
 *
 * <p>
 * <b>★ 実装上の必須制約</b>: ここでは {@code renderBackground} / blur 系の経路を<b>絶対に呼ばない</b>。
 * MC 1.21.5+ では {@code GameRenderer} が screen render の<b>前</b>に
 * {@code screen.renderBackground} を 1 回だけ呼ぶ仕様で、 Screen 側から再度呼ぶと blur が
 * 2 回起動して 「Can only blur once per frame」 で確実にクラッシュする
 * ({@code OmniChestSettingsScreen} にも同じ注意が記録済み)。 よって<b>単純な塗りつぶし 1 枚</b>に留める。
 *
 * <p>
 * <b>呼ぶ位置</b>: {@code extractRenderState} の<b>先頭</b> (= {@code super.extractRenderState} より前)。
 * {@code Screen#extractRenderState} は renderables を iterate するだけなので、 先頭で敷けば
 * ウィジェットにも自前描画にも被らない。
 */
public final class ScreenBackdrop {

    private ScreenBackdrop() {
    }

    /**
     * 暗転の色。 <b>既存の {@link ThemeColorResolver#PANEL_BG} と同値</b>を使い、 新しい
     * マジックナンバを増やさない (= 他画面のパネルと同じトーンで統一される)。
     * 雪原 (#F0F0F0) の上でも実効背景は #242424 まで落ち、 {@code ×N} が 6.68:1 /
     * {@code ×0} が 5.75:1 と、 いずれも WCAG AA を超える。
     */
    public static final int DIM_ARGB = ThemeColorResolver.PANEL_BG;

    /**
     * 画面全面に暗転を 1 枚敷く。 {@code extractRenderState} の先頭で呼ぶこと。
     *
     * @param g      現在の {@link GuiGraphicsExtractor}
     * @param screen 対象の画面 (幅・高さの取得のみに使う)
     */
    public static void dim(GuiGraphicsExtractor g, Screen screen) {
        g.fill(0, 0, screen.width, screen.height, DIM_ARGB);
    }
}
