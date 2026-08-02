package com.kajiwara.omnichest.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * 「今、文字入力を受け付けている最中か」 を返す <b>唯一のヘルパ</b>。
 *
 * <p>
 * <b>存在理由</b>: OmniChest のホットキーには 2 系統の入口がある。
 * <ol>
 * <li>{@link net.minecraft.client.KeyMapping} 経由 (= {@code consumeClick})。 これは
 *     バニラが <b>Screen が開いている間は tick しない</b> ので、 タイプ中に暴発しない。</li>
 * <li>GLFW の生ポーリング + エッジ検出 (= Alt+C / Alt+D)。 こちらは <b>Screen の有無に関係なく
 *     毎 tick 走る</b> ため、 テキスト欄にフォーカスがあっても発火しうる。</li>
 * </ol>
 * 加えて、 GUI 内で処理する mod 側のキー判定 (= スロットロックのホットキー) も
 * 「検索欄に打っている最中」 は黙っている必要がある。 判定条件が各所にコピーされると
 * 片方だけ直し忘れるので、 <b>判定はこのクラス 1 箇所</b> に集約する。
 *
 * <p>
 * <b>判定規則</b>: 「現在の Screen のフォーカス連鎖を辿った先が {@link EditBox} で、 かつ
 * その EditBox が入力を受け取れる状態 ({@code canConsumeInput})」。 ウィジェット型だけで
 * 判定するので、 mod 側の画面 (SearchScreen / TemplateManagerScreen / SetCategoryScreen /
 * TemplateSaveScreen) も、 チェスト GUI に注入した検索欄
 * ({@link com.kajiwara.omnichest.mixin.GenericContainerScreenMixin}) も、 追加登録なしで
 * 同じ規則で拾える。
 *
 * <p>
 * <b>影響範囲</b>: このクラスは <em>OmniChest 自身のホットキーを黙らせる</em> ためだけに使う。
 * バニラの入力処理 (看板 / 本 / チャット / 金床など) には一切介入しない。
 */
public final class TextInputState {

    /**
     * フォーカス連鎖を辿る最大段数 (= 無限ループ保険)。
     * 実際の GUI 階層は深くても 2〜3 段。
     */
    private static final int MAX_FOCUS_DEPTH = 8;

    private TextInputState() {
    }

    /**
     * 文字入力中なら true。
     *
     * <p>
     * Screen が開いていない (= ゲーム画面) ときは常に false なので、 通常プレイ中の
     * ホットキーには一切影響しない。
     */
    public static boolean isTextInputActive() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null)
            return false;
        //? if >=26.2 {
        /*Screen screen = mc.gui == null ? null : mc.gui.screen();*/
        //?} else {
        Screen screen = mc.screen;
        //?}
        if (screen == null)
            return false;

        GuiEventListener node = screen.getFocused();
        for (int depth = 0; node != null && depth < MAX_FOCUS_DEPTH; depth++) {
            if (node instanceof EditBox box) {
                // canConsumeInput() = 可視 + フォーカス + 有効 (= 「今まさに打てる」)。
                return box.canConsumeInput();
            }
            if (node instanceof ContainerEventHandler nested) {
                node = nested.getFocused();
                continue;
            }
            return false;
        }
        return false;
    }
}
