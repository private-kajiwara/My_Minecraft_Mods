package com.kajiwara.omnichest.mixin;

import com.kajiwara.omnichest.client.gui.OmniChestScaledScreen;
import com.kajiwara.omnichest.gui.GuiScaleFit;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OmniChest の対応コンテナ画面を開いている間だけ、 実効 GUI スケールを「UI が破綻なく収まる
 * 最大スケール」 へクランプする。
 *
 * <p>
 * <b>なぜ Window#calculateScale か</b>: {@link Minecraft#resizeDisplay()} は
 * {@code window.setGuiScale(window.calculateScale(option, unicode))} → {@code guiScaledWidth/Height}
 * → {@code screen.resize(...)} の順に流れる。 ここで {@code calculateScale} の戻り値を下げると、
 * バニラのスロット座標 / クリック / ドラッグ / ツールチップ / クイックムーブが<b>全て同じ実スケール</b>で
 * 一貫動作する (= render の行列スケールと違い、 マウス座標の再マップが一切不要 = 入力ズレが原理的に
 * 起きない)。 {@code calculateScale} は {@code framebufferWidth/Height} だけを見る純関数なので、
 * 戻り値を絞っても副作用は無い。
 *
 * <p>
 * <b>適用範囲</b>: {@code Minecraft.screen} が {@link OmniChestScaledScreen} かつ
 * {@link OmniChestScaledScreen#omnichest$wantsScaleClamp()} の時だけクランプする。 それ以外
 * (他画面 / 他 Mod / プレイヤーインベントリ) や、 そもそも収まる低スケールでは<b>素のバニラ値を
 * そのまま返す</b> (= 完全に従来挙動 = 影響ゼロ)。 ステートレス (現在の screen を見るだけ) なので、
 * 画面を閉じれば次の {@code resizeDisplay} で自動的に素のスケールへ戻る (= 復元漏れ・状態残りが無い)。
 *
 * <p>
 * 実際の再計算トリガ (画面遷移時の {@code resizeDisplay} 呼び出し) は
 * {@link MinecraftGuiScaleMixin} が担う。 ウィンドウリサイズ時は vanilla が自前で
 * {@code resizeDisplay} を呼ぶため、 本 Mixin だけで追従する。
 */
@Mixin(Window.class)
public class WindowGuiScaleMixin {

    @Shadow
    private int framebufferWidth;
    @Shadow
    private int framebufferHeight;

    @Inject(method = "calculateScale", at = @At("RETURN"), cancellable = true)
    private void omnichest$clampForContainerScreen(int guiScaleSetting, boolean forceUnicode,
            CallbackInfoReturnable<Integer> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        //? if >=26.2 {
        /*Screen screen = mc.gui.screen();*/
        //?} else {
        Screen screen = mc.screen;
        //?}
        if (!(screen instanceof OmniChestScaledScreen scaled) || !scaled.omnichest$wantsScaleClamp()) {
            return; // 対応画面以外は素通し (= バニラ挙動)。
        }

        int vanilla = cir.getReturnValueI();
        if (vanilla <= 1) {
            return; // これ以上下げられない。
        }

        int requiredW = scaled.omnichest$requiredLogicalWidth();
        int requiredH = scaled.omnichest$requiredLogicalHeight();

        // 収まる最大スケールを純粋関数で算出する (= 単体テスト済みの GuiScaleFit に一元化)。
        // vanilla 段で既に収まる (= 低スケール) 場合は vanilla がそのまま返るのでクランプ不発火。
        int scale = GuiScaleFit.clampScaleToFit(vanilla, this.framebufferWidth, this.framebufferHeight,
                requiredW, requiredH, forceUnicode);

        if (scale < vanilla) {
            cir.setReturnValue(scale);
        }
    }
}
