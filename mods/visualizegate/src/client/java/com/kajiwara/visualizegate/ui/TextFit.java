package com.kajiwara.visualizegate.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * 文字列をピクセル幅へ収める省略ヘルパ (フォント幅<b>実測</b>・末尾切り＋「…」)。
 *
 * <p>B-P2 レイアウト堅牢化: 英語長さ前提の固定オフセット配置 (ドック列/タブ/ドロップダウン等) で、
 * 長い言語 (独/露) や幅広 CJK がはみ出し/重なりを起こさないよう、 描画前に各セル幅へクランプする。
 * 元は {@code PointCloudScreen.fitWidth} に閉じていたロジックを {@link Font} 引数の static として共有化
 * (各レンダラは {@code mc.font}/{@code this.font} を渡す＝新規規則 0・既存 idiom の横展開)。
 */
public final class TextFit {

    private TextFit() {
    }

    /** {@code s} を {@code maxW}(px) 以内へ。 収まればそのまま、 超過は末尾を切り「…」を付す。 */
    public static String clip(Font font, String s, int maxW) {
        if (maxW <= 0 || s == null || s.isEmpty()) {
            return s;
        }
        if (font.width(Component.literal(s)) <= maxW) {
            return s;
        }
        int ew = font.width(Component.literal("…"));
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = font.width(Component.literal(String.valueOf(s.charAt(i))));
            if (w + cw + ew > maxW) {
                break;
            }
            sb.append(s.charAt(i));
            w += cw;
        }
        return sb.append("…").toString();
    }
}
