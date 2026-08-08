package com.kajiwara.omnichest.template.gui;

import com.kajiwara.omnichest.client.gui.ScreenBackdrop;
import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.template.apply.MovePlan;
import com.kajiwara.omnichest.template.apply.SlotPlanner;
import com.kajiwara.omnichest.template.apply.TemplateApplyEngine;
import com.kajiwara.omnichest.template.config.TemplateConfig;
import com.kajiwara.omnichest.template.data.ChestTemplate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 「テンプレート適用」前の確認ダイアログ。
 *
 * <p>
 * {@link MovePlan} の概要 (移動件数 / 不足 / 動かせない) を 1 画面で見せ、
 * ユーザーが OK を押した時点で {@link TemplateApplyEngine#applyPlan} に流す。
 *
 * <p>
 * 注意: ここでも parent {@link Screen} を保持するが、これは「元のチェスト GUI」 を想定する。
 * Apply 中は {@link com.kajiwara.omnichest.template.apply.MoveQueue} が裏で
 * クリックを発火するので、 OK 押下 = 即 parent に戻す (= ユーザーは元のチェスト GUI で
 * リアルタイムに整理されていく様子を見られる)。
 */
public class TemplatePreviewScreen extends Screen {

    private final Screen parent;
    private final AbstractContainerMenu menu;
    private final int containerSlotCount;
    private final ChestTemplate template;

    private MovePlan plan;

    public TemplatePreviewScreen(Screen parent, AbstractContainerMenu menu, int containerSlotCount,
            ChestTemplate template) {
        super(buildTitle(template));
        this.parent = parent;
        this.menu = menu;
        this.containerSlotCount = containerSlotCount;
        this.template = template;
    }

    /** タイトル文字列を翻訳キーで組み立てる (= テンプレ名の差し込みに対応)。 */
    private static net.minecraft.network.chat.Component buildTitle(ChestTemplate template) {
        String name = template != null
                ? template.name()
                : OmniChestLocale.getString(Keys.TEMPLATE_PREVIEW_TITLE_NULL, "(null)");
        return OmniChestLocale.get(Keys.TEMPLATE_PREVIEW_TITLE_FORMAT, "Preview: %1$s", name);
    }

    /**
     * 「ユーザー設定で Preview をスキップする」モード用の高水準エントリ。
     * skipPreview=true ならその場で apply して終了、 false ならこの GUI を開く。
     */
    public static void openOrApply(Screen parent, AbstractContainerMenu menu, int containerSlotCount,
            ChestTemplate template) {
        if (template == null)
            return;
        if (TemplateConfig.get().skipPreview) {
            TemplateApplyEngine.planAndApply(Minecraft.getInstance(), menu, containerSlotCount, template);
            return;
        }
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(
                new TemplatePreviewScreen(parent, menu, containerSlotCount, template));*/
        //?} else {
        Minecraft.getInstance().setScreen(
                new TemplatePreviewScreen(parent, menu, containerSlotCount, template));
        //?}
    }

    @Override
    protected void init() {
        super.init();
        // 開いた瞬間に Plan を確定する (= スロット状態を再読込せず確定的な動作にする)。
        this.plan = SlotPlanner.plan(this.menu, this.containerSlotCount, this.template, TemplateConfig.get());

        int cx = this.width / 2;
        int bottomY = this.height - 36;

        // 新規作成: このチェストから新しいテンプレートを作る画面 (= TemplateSaveScreen) へ。
        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get(Keys.BUTTON_CREATE_NEW, "Create New"),
                //? if >=26.2 {
                /*b -> Minecraft.getInstance().setScreenAndShow(
                        new TemplateSaveScreen(this.parent, this.menu, this.containerSlotCount)))*/
                //?} else {
                b -> Minecraft.getInstance().setScreen(
                        new TemplateSaveScreen(this.parent, this.menu, this.containerSlotCount)))
                //?}
                .bounds(cx - 120, bottomY, 115, 20).build());

        // 戻る: テンプレート一覧 (= テンプレートメニュー) へ。
        this.addRenderableWidget(Button.builder(
                OmniChestLocale.get(Keys.BUTTON_BACK, "Back"),
                //? if >=26.2 {
                /*b -> Minecraft.getInstance().setScreenAndShow(
                        new TemplateManagerScreen(this.parent, this.menu, this.containerSlotCount)))*/
                //?} else {
                b -> Minecraft.getInstance().setScreen(
                        new TemplateManagerScreen(this.parent, this.menu, this.containerSlotCount)))
                //?}
                .bounds(cx + 5, bottomY, 115, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 全面の暗転を最初に 1 枚 (= この画面は自前のパネル背景を持たない)。
        ScreenBackdrop.dim(g, this);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.getTitle(), this.width / 2, 20, 0xFFFFFFFF);

        if (this.plan == null) {
            g.centeredText(this.font,
                    OmniChestLocale.get(Keys.TEMPLATE_PREVIEW_COMPUTING, "Computing..."),
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
            return;
        }

        int yLine = 50;
        int totalItems = this.plan.totalItemsMoved();
        int moves = this.plan.moves().size();
        int shortages = this.plan.shortages().size();
        int stranded = this.plan.stranded().size();

        g.centeredText(this.font,
                OmniChestLocale.get(Keys.TEMPLATE_PREVIEW_MOVES,
                        "Moves to execute: %1$d (total %2$d items)", moves, totalItems),
                this.width / 2, yLine, 0xFFFFFFFF);
        yLine += 14;
        if (shortages > 0) {
            g.centeredText(this.font,
                    OmniChestLocale.get(Keys.TEMPLATE_PREVIEW_SHORTAGES,
                            "Short items: %1$d kinds (some slots cannot be filled)", shortages),
                    this.width / 2, yLine, 0xFFFFAA55);
            yLine += 14;
        }
        if (stranded > 0) {
            g.centeredText(this.font,
                    OmniChestLocale.get(Keys.TEMPLATE_PREVIEW_STRANDED,
                            "Stranded slots: %1$d (no place to put these items)", stranded),
                    this.width / 2, yLine, 0xFFFF7777);
            yLine += 14;
        }
        if (moves == 0 && shortages == 0 && stranded == 0) {
            g.centeredText(this.font,
                    OmniChestLocale.get(Keys.TEMPLATE_PREVIEW_NOTHING_TO_DO,
                            "Already matches the template."),
                    this.width / 2, yLine, 0xFF88FF88);
            yLine += 14;
        }

        // ─── 移動詳細 (最大 10 件) ───
        int detailY = yLine + 8;
        int maxRows = 10;
        int shown = 0;
        String swapTag = OmniChestLocale.getString(Keys.TEMPLATE_PREVIEW_MOVE_SWAP, "(swap)");
        for (MovePlan.Move m : this.plan.moves()) {
            if (shown >= maxRows)
                break;
            int rowY = detailY + shown * 18;
            // アイコン
            g.item(m.icon(), this.width / 2 - 130, rowY);
            // テキスト (= 翻訳キー駆動)
            String text = OmniChestLocale.getString(Keys.TEMPLATE_PREVIEW_MOVE_ROW,
                    "  slot %1$d  →  slot %2$d   ×%3$d %4$s",
                    m.fromSlot(), m.toSlot(), m.count(), m.swap() ? swapTag : "");
            g.text(this.font, Component.literal(text),
                    this.width / 2 - 108, rowY + 4, 0xFFFFFFFF, false);
            shown++;
        }
        if (this.plan.moves().size() > maxRows) {
            g.centeredText(this.font,
                    OmniChestLocale.get(Keys.TEMPLATE_PREVIEW_MORE_ITEMS,
                            "...and %1$d more", this.plan.moves().size() - maxRows),
                    this.width / 2, detailY + maxRows * 18 + 4, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(this.parent);*/
        //?} else {
        Minecraft.getInstance().setScreen(this.parent);
        //?}
    }
}
