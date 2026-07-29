package com.kajiwara.hyperslice.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Mod Menu エントリポイント (fabric.mod.json の "modmenu" entrypoint 経由で発見される)。
 *
 * <p>v0.1 は設定項目を持たないため、 情報表示のみの最小画面を返す。
 * 独自テクスチャ・独自装飾は使わず、 バニラのフォントとウィジェットだけで構成する。
 * ModMenu API ({@code com.terraformersmc.modmenu.api.*}) は ModMenu 16〜20 で共通。
 * ModMenu 未導入時は本クラスはロードされない (= ハード依存にしない)。
 */
public final class HyperSliceModMenuApi implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            try {
                return new InfoScreen(parent);
            } catch (Throwable t) {
                // 構築失敗時は null (= ModMenu は「設定なし」扱い・クラッシュしない)。
                return null;
            }
        };
    }

    /** v0.1 の情報表示のみの画面 (設定項目なし)。 */
    private static final class InfoScreen extends Screen {

        private static final int LINE_GAP = 4;
        private static final String[] LINE_KEYS = {
                "hyperslice.info.line1",
                "hyperslice.info.line2",
                "hyperslice.info.line3",
        };

        private final Screen parent;

        InfoScreen(Screen parent) {
            super(Component.translatable("hyperslice.info.title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.done"),
                            b -> minecraft.setScreen(parent))
                    .bounds(width / 2 - 100, height - 32, 200, 20)
                    .build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);

            int lh = font.lineHeight + LINE_GAP;
            int y = height / 2 - (LINE_KEYS.length * lh) / 2;

            Component titleText = title;
            g.text(font, titleText, width / 2 - font.width(titleText) / 2, y - lh * 2, 0xFFFFFFFF);

            for (String key : LINE_KEYS) {
                Component line = Component.translatable(key);
                g.text(font, line, width / 2 - font.width(line) / 2, y, 0xFFA0A0B0);
                y += lh;
            }
        }

        @Override
        public void onClose() {
            minecraft.setScreen(parent);
        }
    }
}
