package com.kajiwara.visualizegate.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ㉕ ④ `/vg` シンタックスハイライト (暗背景で読める明るい紫 #B57BFF・在世界の #8E3BE6 とは別管理)。
 *
 * <p>コマンド入力欄の整形 ({@code CommandSuggestions.formatChat(String,int)→FormattedCharSequence}) の
 * 戻り値を、 入力が {@code /vg} で始まる時だけ<b>ラップして先頭3字 ({@code /vg}) を紫に再着色</b>する。
 * 残り (引数の赤/通常＝既存 Brigadier 妥当性ハイライト) は<b>そのまま温存</b>。
 *
 * <p><b>フォールバック必須</b>: {@code require=0} ＋全体 try/catch で、 介入点が見つからない/失敗するノードでは
 * <b>色付けしないだけ</b> (白/通常表示) に degrade する。 クラッシュ・入力阻害・他コマンドの色破壊はしない。
 *
 * <p>メソッド名/シグネチャ ({@code formatChat} / {@code FormattedCharSequence} / {@code Style.withColor(int)} /
 * {@code FormattedCharSink.accept(int,Style,int)}) は全ノードで同一 (現物 javap 確認・26.1=非難読化、
 * legacy=Mojmap) のため版分岐は不要。
 */
@Mixin(CommandSuggestions.class)
public class CommandSuggestionsMixin {

    /**
     * ハイライト専用の<b>独立色</b> (在世界ジオメトリの #8E3BE6 とは別管理)。 暗いチャット背景で読めるよう
     * 在世界のポータル紫より<b>明るい</b>紫 (#B57BFF・RGB 181,123,255)。 まだ暗ければ #C9A6FF、 白っぽければ
     * #A05CFF へここだけで調整可。 {@code GateColors.MAIN}/{@code PC_LINK} 等の在世界紫は不変。
     */
    private static final int VG_HIGHLIGHT_ARGB = 0xB57BFF;
    /** 着色する先頭リテラル長 ("/vg" = 3 文字)。 */
    private static final int VG_PREFIX_LEN = 3;

    @Inject(method = "formatChat", at = @At("RETURN"), cancellable = true, require = 0)
    private void visualizegate$highlightVg(String input, int firstCharacterIndex,
            CallbackInfoReturnable<FormattedCharSequence> cir) {
        try {
            if (input == null || !input.startsWith("/vg")) {
                return;
            }
            FormattedCharSequence base = cir.getReturnValue();
            if (base == null) {
                return;
            }
            cir.setReturnValue(visualizegate$recolorPrefix(base, firstCharacterIndex));
        } catch (Throwable ignored) {
            // degrade: 色付けしないだけ (元の戻り値のまま)。
        }
    }

    /**
     * {@code base} をラップし、 走査順の絶対文字 index が {@code [0, VG_PREFIX_LEN)} の文字だけ紫へ上書き。
     * {@code firstCharacterIndex}=入力欄の水平スクロールで最初に見える文字の絶対 index。 自前カウンタで
     * 絶対 index を再構成する (合成 FormattedCharSequence は position が部分列ごとに 0 起算のため)。
     */
    private static FormattedCharSequence visualizegate$recolorPrefix(FormattedCharSequence base,
            int firstCharacterIndex) {
        return sink -> {
            int[] local = {0};
            return base.accept((position, style, codePoint) -> {
                int abs = firstCharacterIndex + local[0];
                local[0]++;
                Style s = (abs < VG_PREFIX_LEN) ? style.withColor(VG_HIGHLIGHT_ARGB) : style;
                return sink.accept(position, s, codePoint);
            });
        };
    }
}
