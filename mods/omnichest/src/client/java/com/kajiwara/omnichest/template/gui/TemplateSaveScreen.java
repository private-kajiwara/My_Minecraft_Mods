package com.kajiwara.omnichest.template.gui;

import com.kajiwara.omnichest.client.gui.ScreenBackdrop;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.template.TemplateManager;
import com.kajiwara.omnichest.template.data.ChestTemplate;
import com.kajiwara.omnichest.template.data.TemplateKind;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 「現在のチェストをテンプレートとして保存」モーダル。
 *
 * <p>
 * 構成:
 * <ul>
 * <li>名前入力ボックス (デフォルトは「テンプレート #N」)</li>
 * <li>種別ボタン: EXACT / CATEGORY / HYBRID をトグルで選ぶ</li>
 * <li>保存 / キャンセル</li>
 * </ul>
 *
 * <p>
 * このスクリーンは {@link AbstractContainerMenu} を引数で受け取って閉じても破棄しない。
 * 保存後は親 GUI (= 元のチェスト画面) に戻すため、 parent を保持する。
 */
public class TemplateSaveScreen extends Screen {

    private final Screen parent;
    private final AbstractContainerMenu menu;
    private final int containerSlotCount;

    private EditBox nameBox;
    private TemplateKind kind = TemplateKind.HYBRID;
    private Button kindButton;

    public TemplateSaveScreen(Screen parent, AbstractContainerMenu menu, int containerSlotCount) {
        super(OmniChestLocale.get(Keys.SCREEN_TEMPLATE_SAVE_TITLE, "Save Template"));
        this.parent = parent;
        this.menu = menu;
        this.containerSlotCount = containerSlotCount;
    }

    @Override
    protected void init() {
        super.init();

        int cx = this.width / 2;
        int cy = this.height / 2;

        this.nameBox = new EditBox(this.font, cx - 120, cy - 30, 240, 20,
                OmniChestLocale.get(Keys.EDITBOX_NAME_LABEL, "Name"));
        this.nameBox.setMaxLength(64);
        // 既存テンプレート数 +1 をデフォルト名にする (毎回入れ直しを避ける QoL)。
        int suggested = TemplateManager.list().size() + 1;
        this.nameBox.setValue(OmniChestLocale.getString(
                Keys.TEMPLATE_DEFAULT_NAME, "Template %1$d", suggested));
        this.nameBox.setHint(OmniChestLocale.get(
                Keys.EDITBOX_TEMPLATE_NAME_HINT, "Template name"));
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);

        // 種別トグル (1 ボタンで EXACT → CATEGORY → HYBRID を循環する)。
        this.kindButton = Button.builder(kindLabel(), b -> {
            this.kind = nextKind(this.kind);
            this.kindButton.setMessage(kindLabel());
        }).bounds(cx - 120, cy, 240, 20).build();
        this.addRenderableWidget(this.kindButton);

        // 保存
        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get(Keys.BUTTON_SAVE, "Save"),
                b -> doSave())
                .bounds(cx - 120, cy + 30, 115, 20).build());

        // キャンセル
        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get(Keys.BUTTON_CANCEL, "Cancel"),
                b -> this.onClose())
                .bounds(cx + 5, cy + 30, 115, 20).build());
    }

    /**
     * 名前欄にフォーカスがある間は、 EditBox が処理しなかったキーも <b>消費</b> する
     * (= 他の入力付き画面と同じ扱いに揃える)。 通すのは Esc (画面を閉じる) と
     * Tab (フォーカス移動) のみ。
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key != InputConstants.KEY_ESCAPE && key != InputConstants.KEY_TAB
                && this.nameBox != null && this.nameBox.canConsumeInput()) {
            this.nameBox.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    private Component kindLabel() {
        String key = switch (this.kind) {
            case EXACT -> Keys.TEMPLATE_KIND_EXACT;
            case CATEGORY -> Keys.TEMPLATE_KIND_CATEGORY;
            case HYBRID -> Keys.TEMPLATE_KIND_HYBRID;
        };
        String englishFallback = switch (this.kind) {
            case EXACT -> "Exact (fixed items)";
            case CATEGORY -> "Category (substitutes allowed)";
            case HYBRID -> "Hybrid (recommended)";
        };
        String body = OmniChestLocale.getString(key, englishFallback);
        return OmniChestLocale.get(Keys.TEMPLATE_KIND_LABEL, "Kind: %1$s", body);
    }

    private static TemplateKind nextKind(TemplateKind cur) {
        return switch (cur) {
            case EXACT -> TemplateKind.CATEGORY;
            case CATEGORY -> TemplateKind.HYBRID;
            case HYBRID -> TemplateKind.EXACT;
        };
    }

    private void doSave() {
        String name = this.nameBox == null ? "" : this.nameBox.getValue().trim();
        if (name.isEmpty())
            name = OmniChestLocale.getString(Keys.TEMPLATE_UNTITLED, "(untitled)");
        ChestTemplate t = TemplateManager.captureCurrentChest(this.menu, this.containerSlotCount, name, this.kind);
        TemplateManager.save(t);
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(this.parent);*/
        //?} else {
        Minecraft.getInstance().setScreen(this.parent);
        //?}
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 全面の暗転を最初に 1 枚 (= この画面は自前のパネル背景を持たない)。
        ScreenBackdrop.dim(g, this);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.getTitle(), this.width / 2, this.height / 2 - 60, 0xFFFFFFFF);

        // 種別の補足説明 (現在の選択ヒント)。
        String helpKey = switch (this.kind) {
            case EXACT -> Keys.TEMPLATE_KIND_HELP_EXACT;
            case CATEGORY -> Keys.TEMPLATE_KIND_HELP_CATEGORY;
            case HYBRID -> Keys.TEMPLATE_KIND_HELP_HYBRID;
        };
        String helpFallback = switch (this.kind) {
            case EXACT -> "Oak Planks and Birch Planks are treated as different items.";
            case CATEGORY -> "Items in the same category can substitute each other. (Best for material chests)";
            case HYBRID -> "Prefers the original item, falls back to same-category substitutes.";
        };
        g.centeredText(this.font, OmniChestLocale.get(helpKey, helpFallback),
                this.width / 2, this.height / 2 + 55, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        // キャンセル / ESC は「呼び出し元」 (= parent) にそのまま戻す。 入口ごとに戻り先が変わる:
        //   - メインメニュー (チェスト GUI) の [配置を保存] / [テンプレ適用] から開いた場合 → チェストに戻る。
        //   - テンプレート管理画面から開いた場合 → 管理画面に戻る。
        // これにより、 ユーザが直前にいた文脈を壊さずキャンセルできる。
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(this.parent);*/
        //?} else {
        Minecraft.getInstance().setScreen(this.parent);
        //?}
    }
}
