package com.kajiwara.omnichest.client.gui;

import com.kajiwara.omnichest.classify.Classification;
import com.kajiwara.omnichest.classify.ClassificationCache;
import com.kajiwara.omnichest.classify.ClassifyConfig;
import com.kajiwara.omnichest.classify.StorageCategory;
import com.kajiwara.omnichest.gui.TextContrastFit;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.search.ContainerSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * チェスト GUI 上に「[FOOD STORAGE] Confidence: 92%」のバッジを描画するためのヘルパ。
 *
 * <p>
 * このクラスはレイアウトを<b>知らない</b>: 与えられた (x, y) に描くだけ。
 * GUI 種別ごとの座標決定は呼び出し側 (Mixin) の責務とし、
 * 検索/種類/数量行・側面パネル・シュルカー等の既存ウィジェットとの衝突回避は
 * 「どのレイアウトか」を知っている層で行う。
 *
 * <p>
 * 設定 {@link ClassifyConfig#showCategoryBadge} が false の場合は何も描かない (= 完全 off)。
 */
public final class CategoryBadgeRenderer {

    /**
     * バッジ背景帯がテキスト左端 ({@code x}) より外側に張り出す量 (px)。
     *
     * <p>
     * 背景は {@code x - BADGE_PAD_X} から塗られるため、 バッジの<b>視覚的な左端</b>は
     * {@code x - BADGE_PAD_X} になる。 呼び出し側はこの値を使って、 バッジの帯の左端を
     * 他のウィジェット列 (= 検索行など) と同じ縦ラインに揃えられる。
     */
    public static final int BADGE_PAD_X = 3;

    private CategoryBadgeRenderer() {
    }

    /**
     * バッジ 1 行を (x, y) を左上として描画する。
     *
     * <p>
     * 戻り値: 描画した矩形の幅 (= 「次のウィジェットをここから右に置きたい」呼び出し側用)。
     * 何も描かなかった場合は 0。
     *
     * @param g   現在の {@link GuiGraphicsExtractor}
     * @param x   左上 x
     * @param y   左上 y
     * @param key 対象コンテナの key (null = 未追跡のため非表示)
     */
    public static int renderBadge(GuiGraphicsExtractor g, int x, int y, @Nullable ContainerSnapshot.Key key) {
        return renderBadge(g, x, y, key, true);
    }

    /**
     * {@link #renderBadge(GuiGraphicsExtractor, int, int, ContainerSnapshot.Key)} の拡張版。
     *
     * @param showStatus 予測メタ (= Confidence% / Manual) を出すか (= Main Menu Visibility の
     *                   「予測表示」 トグル)。 false でもカテゴリ名 ({@code [○○倉庫]}) 自体は出す。
     *                   分類ロジックには一切影響しない (= 表示のみ)。
     */
    public static int renderBadge(GuiGraphicsExtractor g, int x, int y, @Nullable ContainerSnapshot.Key key,
            boolean showStatus) {
        if (!ClassifyConfig.get().showCategoryBadge)
            return 0;
        if (key == null)
            return 0;

        Classification cl = ClassificationCache.get().get(key);
        if (cl == null) {
            // まだ分類前 (= スナップショット未保存) のときは静かに何もしない。
            return 0;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        StorageCategory cat = cl.category();
        int rgb = cat.rgb();
        int bgArgb = (0x80 << 24) | (rgb & 0x00FFFFFF); // 半透明背景

        // カテゴリ名 (例: "[FOOD STORAGE]") — 各言語の displayName を [] で囲む。
        Component left = Component.literal("[").append(cat.displayComponent()).append("]");

        // ─── 手動割り当て (locked) と 自動予測 (auto) を明確に区別する ───
        //
        // 重要 (= 概念の取り違え修正): この 2 つは別物。
        //  - <b>手動</b> (= プレイヤーが Set Category で固定): 予測ではないので <b>confidence を出さない</b>。
        //    代わりに 「Manual」 を金色で明示し、 「これは自分が決めたカテゴリ」 と一目で分かるようにする。
        //  - <b>自動 (concrete)</b>: 従来どおり Confidence を白で表示 (= システムの予測メタ情報)。
        //  - <b>自動 (MIXED / UNKNOWN)</b>: 補足表示なし。
        // 色のコントラスト (金 vs 白) でも 手動/自動 を区別できるようにする (= 4 原則: コントラスト)。
        boolean manual = cl.locked();
        Component status;
        int statusColor;
        if (!showStatus) {
            // 予測表示 OFF: カテゴリ名だけ出す (= 分類は内部で続行、 メタ情報のみ非表示)。
            status = Component.empty();
            statusColor = 0xFFFFFFFF;
        } else if (manual) {
            status = OmniChestLocale.get(Keys.CATEGORY_BADGE_MANUAL, " Manual");
            statusColor = 0xFFFFD54A; // 金色 = プレイヤーの意図 (= 予測の白と対比)
        } else if (cat.isConcrete()) {
            status = OmniChestLocale.get(Keys.CATEGORY_BADGE_CONFIDENCE,
                    " Confidence: %1$d%%", cl.confidencePercent());
            statusColor = 0xFFFFFFFF; // 白 = システム予測のメタ情報
        } else {
            status = Component.empty();
            statusColor = 0xFFFFFFFF;
        }

        int leftW = font.width(left);
        int statusW = font.width(status);
        int totalW = leftW + statusW;

        int padX = BADGE_PAD_X;
        int padY = 1;
        int h = font.lineHeight;

        // 背景帯
        g.fill(x - padX, y - padY, x + totalW + padX, y + h + padY, bgArgb);

        // テキスト: カテゴリ名はカテゴリ色 / 状態 (Manual=金 / Confidence=白) は statusColor。
        int textColor = (0xFF << 24) | (rgb & 0x00FFFFFF);
        g.text(font, left, x, y, textColor, true);
        if (statusW > 0) {
            g.text(font, status, x + leftW, y, statusColor, true);
        }
        return totalW + padX * 2;
    }

    /**
     * カテゴリ 「タグ」 (= {@code [カテゴリ名]} を半透明カテゴリ色帯 + カテゴリ色テキストで描く) の幅を返す。
     * 実際に描かずレイアウト採寸だけしたい呼び出し側用 (= 折り返し計算など)。
     */
    public static int tagWidth(Font font, StorageCategory cat) {
        return font.width(Component.literal("[").append(cat.displayComponent()).append("]")) + BADGE_PAD_X * 2;
    }

    /**
     * カテゴリ 「タグ」 を (x, y) を左上として描画する (= in-world バッジの左半分と同じ視覚言語)。
     *
     * <p>
     * {@code [カテゴリ名]} を、 暗めカテゴリ色の帯の上に、 読める明るさのカテゴリ色テキストで描く。
     * {@link #renderBadge} の 「カテゴリ名部分」 を <b>カテゴリ単体から</b> 描けるよう切り出した再利用版で、
     * 振り分けプレビューの 「必要なカテゴリ」 一覧などで使う (= 反復: 在庫バッジと同じ見た目)。
     *
     * <p>
     * <b>不透明化について</b>: 旧実装は帯を {@code 0x80 | rgb} の<b>半透明</b>で敷いていたため、
     * 実効的な背景色が 「その時たまたま後ろにあるもの」 に依存し、 文字とのコントラストを一切
     * 保証できなかった (実測: どの背景でも concrete 27 件すべてが 4.5:1 未満。 明るい背景では
     * 最小 1.04:1)。 帯を {@link TextContrastFit#TAG_BG_FACTOR} で<b>不透明</b>に敷き、
     * 文字を {@link TextContrastFit#readableTextColor} で決めることで、 背後に何があっても
     * 比が確定する。 係数 0.50 は 「黒地に 50% で載せたときの見え」 と一致するので、
     * 暗い背景の上での見た目はほぼ従来どおりになる。
     *
     * @return 描画した帯の総幅 (= 次のタグをここから右に置きたい呼び出し側用)。
     */
    public static int renderTag(GuiGraphicsExtractor g, Font font, int x, int y, StorageCategory cat) {
        int rgb = cat.rgb();
        Component label = Component.literal("[").append(cat.displayComponent()).append("]");
        int textW = font.width(label);
        int padX = BADGE_PAD_X;
        int padY = 1;
        int h = font.lineHeight;
        int bgRgb = darken(rgb, TextContrastFit.TAG_BG_FACTOR);
        g.fill(x, y - padY, x + textW + padX * 2, y + h + padY, 0xFF000000 | bgRgb);
        g.text(font, label, x + padX, y,
                0xFF000000 | TextContrastFit.readableTextColor(rgb, bgRgb), true);
        return textW + padX * 2;
    }

    /**
     * カテゴリ色の 「チップ」 (= ボタン風の塗り) を描く。
     *
     * <p>
     * 設定画面 (Set Category 等) のカテゴリ選択を、 in-world バッジと<b>同じ視覚言語</b>に揃えるための
     * 再利用ヘルパ。 バッジは半透明 (0x80) のカテゴリ色を暗い背景に重ねており、 これは実効的に
     * 「カテゴリ色を約半分の明るさにした色」 になる。 設定画面では下地 (バニラボタン等) が一定しないため、
     * 同じ見た目を <b>不透明な暗めカテゴリ色の塗り + カテゴリ色の枠 + 明るいカテゴリ色テキスト</b> で
     * 再現する。 これによりプレイヤーは 「カテゴリ設定」 と 「在庫上のカテゴリ表示」 を一目で結びつけられる。
     *
     * @param g       現在の {@link GuiGraphicsExtractor}
     * @param x       チップ左上 x
     * @param y       チップ左上 y
     * @param w       チップ幅
     * @param h       チップ高さ
     * @param cat     表示するカテゴリ (色とテキスト色の元)
     * @param hovered ホバー中か (= 少し明るくしてフィードバックを出す)
     * @param focused キーボードフォーカス中か (= 白枠でアクセシビリティを担保)
     * @param label   中央に描くラベル (null なら未描画。 通常はローカライズ済み Component)
     */
    public static void renderCategoryChip(GuiGraphicsExtractor g, int x, int y, int w, int h,
            StorageCategory cat, boolean hovered, boolean focused, @Nullable Component label) {
        int rgb = cat.rgb();
        // ホバー時は明るめ (0.55) / 通常は暗め (0.40) のカテゴリ色を不透明で敷く。
        float baseF = hovered ? TextContrastFit.CHIP_BG_FACTOR_HOVER : TextContrastFit.CHIP_BG_FACTOR;
        int bgRgb = darken(rgb, baseF);
        g.fill(x, y, x + w, y + h, 0xFF000000 | bgRgb);
        // カテゴリ色の枠で輪郭を強調 (= コントラスト)。 フォーカス時は白枠で可視化。
        g.outline(x, y, w, h, focused ? 0xFFFFFFFF : (0xFF000000 | rgb));
        if (label != null) {
            Font font = Minecraft.getInstance().font;
            // 文字色は 「カテゴリ色」 を基準に、 この背景の上で WCAG AA (4.5:1) に届くまで
            // <b>色相を保ったまま</b>白へ寄せた色 ({@link TextContrastFit})。
            // 元の色が暗いカテゴリ (深層岩 / ネザー素材 / エンド素材 等) は darken した背景との
            // 輝度差が足りず、 実測で 27 中 22 件が 4.5:1 を割っていた (最悪 1.91:1)。
            // 既に基準を満たしている 5 件は 1 ビットも変わらない (= 見た目の非回帰)。
            int textColor = 0xFF000000 | TextContrastFit.readableTextColor(rgb, bgRgb);
            int tx = x + (w - font.width(label)) / 2;
            int ty = y + (h - font.lineHeight) / 2 + 1;
            g.text(font, label, tx, ty, textColor, true);
        }
    }

    /** RGB の各チャンネルを係数 {@code f} (0..1) で暗くする (= 黒に向けて補間)。 */
    private static int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int gg = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (gg << 8) | b;
    }
}
