package com.kajiwara.visualizegate.client.keybind;

import com.kajiwara.visualizegate.state.VgOverlayState;
import com.kajiwara.visualizegate.ui.GateMenuScreen;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * メニュー起動キーバインド (Mixin 不使用・Fabric API のみ)。
 *
 * <p>カテゴリ "VisualizeGate"、 既定キー <b>V</b> (バニラ既定と非衝突。 端末で衝突する場合は
 * バニラ「コントロール設定」 から再割当可)。 押下でゲーム画面 (= 他 Screen 非表示時) からのみ
 * {@link GateMenuScreen} を開く。
 */
public final class GateKeyBindings {

    public static final String OPEN_MENU_KEY = "key.visualizegate.open_menu";
    // ㊲ ドック展/畳トグル (V はハブで使用中＝再利用しない)。 既定<b>未割当</b>＝Controls から任意割当。
    public static final String DOCK_KEY = "key.visualizegate.dock";

    // 独自カテゴリを Identifier 版 register で登録 (String 版は 1.21.11+ で package-private)。
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("visualizegate", "main"));

    private static KeyMapping openMenu;
    private static KeyMapping toggleDock;

    private GateKeyBindings() {
    }

    public static void register() {
        openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                OPEN_MENU_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CATEGORY));
        // ㊲ 既定未割当 (GLFW_KEY_UNKNOWN=-1)＝Controls で割り当てるまで発火しない (V 等と非衝突)。
        toggleDock = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                DOCK_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(GateKeyBindings::onTick);
    }

    private static void onTick(Minecraft mc) {
        if (openMenu != null) {
            while (openMenu.consumeClick()) {
                // 他の Screen が開いている時は抑止 (誤発火防止)。
                //? if >=26.2 {
                /*if (mc.gui.screen() == null) {*/
                //?} else {
                if (mc.screen == null) {
                //?}
                    // ㉜C 初回の自動ガイド (4 枚カード) を撤去＝強制ポップアップ無し。 使い方はハブの「使い方」pull のみ。
                    // 学習は「一貫した言語＋その場のカード＋常設凡例」に委ねる (過剰なチュートリアル禁止)。
                    //? if >=26.2 {
                    /*mc.setScreenAndShow(new GateMenuScreen());*/
                    //?} else {
                    mc.setScreen(new GateMenuScreen());
                    //?}
                }
            }
        }
        // ㊲ ドック展/畳トグル (ゲーム画面中のみ＝Screen 表示中は抑止・入力非干渉)。
        if (toggleDock != null) {
            while (toggleDock.consumeClick()) {
                //? if >=26.2 {
                /*if (mc.gui.screen() == null) {*/
                //?} else {
                if (mc.screen == null) {
                //?}
                    VgOverlayState.toggleDock();
                }
            }
        }
    }

    /** ㊲ ドックのキーヒント用: 現在割当キーの表示名 (未割当なら "Not bound" 相当)。 */
    public static String dockKeyDisplay() {
        return toggleDock == null ? "?" : toggleDock.getTranslatedKeyMessage().getString();
    }

    /** HUD のキーヒント用: 現在割当キーの表示名 (再割当に追従)。 未登録時は "?"。 */
    public static String boundKeyDisplay() {
        return openMenu == null ? "?" : openMenu.getTranslatedKeyMessage().getString();
    }
}
