package com.kajiwara.omnichest.mixin;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 画面遷移のたびに、 現在の画面に対する「望ましい実効 GUI スケール」 を再適用するトリガ。
 *
 * <p>
 * 実際のクランプ判定は {@link WindowGuiScaleMixin}（{@code Window#calculateScale} フック）が
 * ステートレスに行う。 ただし {@code calculateScale} は<b>ウィンドウリサイズ時にしか呼ばれない</b>ため、
 * 「OmniChest コンテナ画面を開いた / 閉じた」 という<b>画面遷移だけ</b>では再計算が走らない。
 * そこで {@link Minecraft#setScreen(Screen)} の TAIL で、 現在の画面に対する望ましいスケールを
 * 算出し、 現状と違えば {@link Minecraft#resizeDisplay()} を 1 回呼んで反映する。
 *
 * <p>
 * <b>開く時</b>: 対応コンテナへ遷移 → desired = クランプ後スケール ({@code calculateScale} が
 * {@link WindowGuiScaleMixin} 経由で下げた値) ≠ 現状 → {@code resizeDisplay} でクランプ適用。<br>
 * <b>閉じる時</b>: 非対応画面 / null へ遷移 → desired = 素のバニラスケール ≠ クランプ中の現状 →
 * {@code resizeDisplay} で<b>元のスケールへ確実に復元</b> (HUD / ワールドが歪んだスケールで
 * 残らない)。
 *
 * <p>
 * {@code setScreenAndShow} も内部で {@code setScreen} を呼ぶため、 ここ 1 か所で両経路を捕捉する。
 * 再入防止フラグ ({@link #omnichest$adjustingScale}) で、 {@code resizeDisplay} → {@code screen.resize}
 * → {@code init} の最中に再度トリガが走るのを防ぐ (= 通常は desired==現状で止まるが二重安全)。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftGuiScaleMixin {

    @Shadow
    @Final
    public Options options;

    @Shadow
    public abstract Window getWindow();

    // 26.1: Minecraft.resizeDisplay() は resizeGui() に改名。
    @Shadow
    public abstract void resizeGui();

    @Shadow
    public abstract boolean isEnforceUnicode();

    @Unique
    private boolean omnichest$adjustingScale;

    // 26.2: Minecraft.setScreen は削除され setScreenAndShow へ統合 (screen フィールドも Gui へ移動)。
    //   画面遷移の入口は setScreenAndShow なのでそこへリターゲットする (26.1.x は setScreen のまま)。
    //? if >=26.2 {
    /*@Inject(method = "setScreenAndShow", at = @At("TAIL"))*/
    //?} else {
    @Inject(method = "setScreen", at = @At("TAIL"))
    //?}
    private void omnichest$reapplyScaleForScreen(Screen newScreen, CallbackInfo ci) {
        if (this.omnichest$adjustingScale) {
            return;
        }
        Window window = this.getWindow();
        if (window == null) {
            return;
        }
        // calculateScale は WindowGuiScaleMixin により「現在の画面」 に応じてクランプ済みの値を返す。
        int desired = window.calculateScale(this.options.guiScale().get(), this.isEnforceUnicode());
        if (window.getGuiScale() == desired) {
            return; // 変更不要 (= 低スケールや非対応画面で従来どおり)。
        }
        this.omnichest$adjustingScale = true;
        try {
            this.resizeGui();
        } finally {
            this.omnichest$adjustingScale = false;
        }
    }
}
