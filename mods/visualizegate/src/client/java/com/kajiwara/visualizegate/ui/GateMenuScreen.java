package com.kajiwara.visualizegate.ui;

import java.util.function.Supplier;

import com.kajiwara.visualizegate.config.GateConfigManager;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.pointcloud.PointCloudAnalysis;
import com.kajiwara.visualizegate.state.GateMenuState;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * VisualizeGate のメイン操作画面 / ハブ (共通 Screen・Mixin 不使用)。
 *
 * <p><b>レイアウト</b>: 固定幅・中央寄せ・グループ分け (将来トグルが増えても崩れない形)。
 * 表示トグル (枠/隅アイコン/ホログラム) は<b>2 列グリッド</b>で縦の伸びを抑える → 表示モード →
 * ツール (点群/使い方) → Done。 上部に状態 1 行 (現次元 / 記憶ゲート数 / モード)。 ボタン外観はバニラ標準のまま。
 * ㉟ {@link #isPauseScreen()} は true ＝ SP では表示中にゲーム進行を一時停止 (MP は統合サーバ非搭載のため
 * 進行は止まらず描画/入力のみ)。
 */
public class GateMenuScreen extends Screen {

    // 固定寸法 (画面幅に比例させない＝ワイド画面での巨大化を防ぐ)。
    private static final int COL_W = 150;   // 2 列トグルの 1 列幅
    private static final int COL_GAP = 6;
    private static final int FULL_W = COL_W * 2 + COL_GAP; // 単列 (mode/ツール/Done) = グリッド全幅に揃える
    private static final int H = 20;
    private static final int GAP = 4;
    private static final int GROUP_GAP = 10;
    private static final int HEADER_H = 32; // タイトル＋状態行＋余白

    // init で算出し extractRenderState と共有 (タイトル/状態行の y / 記憶ゲート数キャッシュ)。
    private int contentTop;
    private int rememberedGates;

    public GateMenuScreen() {
        super(Component.literal("VisualizeGate"));
    }

    @Override
    protected void init() {
        rememberedGates = countGates();

        int cx = this.width / 2;
        int leftX = cx - FULL_W / 2;
        int rightColX = leftX + COL_W + COL_GAP;

        // ブロック全体 (ヘッダ＋ボタン群) の高さを出して縦中央寄せ。
        int buttonsH = (H * 2 + GAP) + GROUP_GAP + H + GROUP_GAP + (H * 2 + GAP) + GROUP_GAP + H;
        int totalH = HEADER_H + buttonsH;
        contentTop = Math.max(8, (this.height - totalH) / 2);

        int y = contentTop + HEADER_H;

        // ── グループ1: 表示トグル (2 列グリッド) ──
        // 行A: 枠 | 隅アイコン
        addToggle(leftX, y, COL_W, GateMenuScreen::boxLabel, GateMenuState::toggleBoxOverlay);
        addToggle(rightColX, y, COL_W, GateMenuScreen::hudLabel, GateMenuState::toggleHudIcon);
        y += H + GAP;
        // 行B: ホログラム | 探索ドーム
        addToggle(leftX, y, COL_W, GateMenuScreen::hologramLabel, GateMenuState::toggleHologram);
        addToggle(rightColX, y, COL_W, GateMenuScreen::domeLabel, GateMenuState::toggleDome);
        y += H + GROUP_GAP;

        // ── グループ2: 表示モード (かんたん/詳細) ──
        addToggle(leftX, y, FULL_W, GateMenuScreen::modeLabel, GateMenuState::toggleAdvancedMode);
        y += H + GROUP_GAP;

        // ── グループ3: ツール ──
        // 点群解析: その場のデータでスナップショットを組み (ワーカー)、 ポップアップを開く。
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.menu.pointcloud"), b -> {
            PointCloudAnalysis.get().requestAnalysis();
            //? if >=26.2 {
            /*this.minecraft.setScreenAndShow(new PointCloudScreen(this));*/
            //?} else {
            this.minecraft.setScreen(new PointCloudScreen(this));
            //?}
        }).bounds(leftX, y, FULL_W, H).build());
        y += H + GAP;
        // 使い方: 初回ガイドをいつでも再表示 (閉じると本メニューへ戻る)。
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.menu.guide"),
                //? if >=26.2 {
                /*b -> this.minecraft.setScreenAndShow(new GuideScreen(this)))*/
                //?} else {
                b -> this.minecraft.setScreen(new GuideScreen(this)))
                //?}
                .bounds(leftX, y, FULL_W, H).build());
        y += H + GROUP_GAP;

        // ── グループ4: Done ──
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.menu.done"), b -> this.onClose())
                .bounds(leftX, y, FULL_W, H).build());
    }

    /** トグルボタン (押下で state を反転 → ラベル更新 → 保存)。 */
    private void addToggle(int x, int y, int w, Supplier<Component> label, Runnable toggle) {
        addRenderableWidget(Button.builder(label.get(), b -> {
            toggle.run();
            b.setMessage(label.get());
            GateConfigManager.save();
        }).bounds(x, y, w, H).build());
    }

    private int countGates() {
        try {
            return PortalMemory.get().knownInDimension(PortalDimension.OVERWORLD).size()
                    + PortalMemory.get().knownInDimension(PortalDimension.NETHER).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Component boxLabel() {
        return Component.translatable("visualizegate.menu.box", onOff(GateMenuState.isBoxOverlayEnabled()));
    }

    private static Component hudLabel() {
        return Component.translatable("visualizegate.menu.hud", onOff(GateMenuState.isHudIconEnabled()));
    }

    private static Component hologramLabel() {
        return Component.translatable("visualizegate.menu.hologram", onOff(GateMenuState.isHologramEnabled()));
    }

    private static Component domeLabel() {
        return Component.translatable("visualizegate.menu.dome", onOff(GateMenuState.isDomeEnabled()));
    }

    private static Component modeLabel() {
        return Component.translatable("visualizegate.menu.mode", modeName(GateMenuState.isAdvancedMode()));
    }

    private static Component modeName(boolean advanced) {
        return Component.translatable(advanced ? "visualizegate.mode.advanced" : "visualizegate.mode.simple");
    }

    private static Component onOff(boolean b) {
        return Component.translatable(b ? "visualizegate.state.on" : "visualizegate.state.off");
    }

    /** 状態行の現次元名 (OW/Nether 以外は「他次元」)。 */
    private Component currentDimName() {
        if (this.minecraft != null && this.minecraft.level != null) {
            PortalDimension d = PortalMemory.dimOf(this.minecraft.level.dimension().identifier().toString());
            if (d == PortalDimension.OVERWORLD) {
                return Component.translatable("visualizegate.dim.overworld");
            }
            if (d == PortalDimension.NETHER) {
                return Component.translatable("visualizegate.dim.nether");
            }
        }
        return Component.translatable("visualizegate.dim.other");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        // タイトル (中央上・アクセント色) ＋ 下線。
        int tw = this.font.width(this.title);
        int tx = cx - tw / 2;
        int ty = contentTop;
        g.text(this.font, this.title, tx, ty, GateColors.ACCENT);
        g.fill(tx, ty + 11, tx + tw, ty + 12, GateColors.MAIN);
        // 状態行: 現次元 / 記憶ゲート数 / モード (現次元・モードは毎フレーム live・ゲート数は init キャッシュ)。
        Component status = Component.translatable("visualizegate.menu.status",
                currentDimName(), rememberedGates, modeName(GateMenuState.isAdvancedMode()));
        g.text(this.font, status, cx - this.font.width(status) / 2, contentTop + 16, GateColors.TEXT);
    }

    @Override
    public boolean isPauseScreen() {
        return true; // ㉟ メニュー表示中は SP のゲーム進行を一時停止 (MP は統合サーバ非搭載＝描画/入力のみ・進行は止まらない)。
    }
}
