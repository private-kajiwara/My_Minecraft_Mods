package com.kajiwara.hyperslice.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.kajiwara.hyperslice.core.SliceRegistry;
import com.kajiwara.hyperslice.entity.ClientHyperEntities;
import com.kajiwara.hyperslice.observer.ObserverW;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * 現在の w を常時表示する読み取り専用 HUD。
 *
 * <p>HyperSlice のディメンション内にいるときだけ描く。 通常世界やネザーでは何も出さない。
 *
 * <p>独自テクスチャ・独自装飾は使わず、 バニラのフォントと単色矩形だけで構成する
 * (既存 mod の方針を踏襲)。 文字列は全て翻訳キー経由。
 *
 * <p>クライアント側は w をディメンション ID から読むだけで、 サーバーへ問い合わせない
 * (= ネットワーク往復なし・パケット定義なし)。 N はクライアントからは分からないので
 * 表示は w のみに留める ({@code /hyperslice} で N も確認できる)。
 */
public final class SliceHudRenderer {

    // ─── レイアウト定数 (論理 px = GUI スケール後) ───
    private static final int SCREEN_MARGIN = 4;
    private static final int PANEL_PAD = 4;
    private static final int ROW_GAP = 2;

    // ─── 色 (ARGB) ───
    private static final int PANEL_BG = 0x90101018;
    private static final int TEXT_LABEL = 0xFFA0A0B0;
    private static final int TEXT_VALUE = 0xFFFFFFFF;

    private SliceHudRenderer() {
    }

    /** HUD 登録。 {@code HyperSliceClient} から 1 回だけ呼ぶ (新規 Mixin なし)。 */
    public static void register() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(SliceRegistry.NAMESPACE, "slice_hud"),
                (g, deltaTracker) -> draw(g));
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        // HUD 規律: Screen 表示中 / F1 (hideGui) / F3 デバッグ は非描画。
        if (mc.screen != null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }

        int slice = currentSliceW(mc.level);
        if (slice < 0) {
            // HyperSlice の外 (通常世界など) では何も出さない。
            return;
        }

        Font font = mc.font;
        Component label = Component.translatable("hyperslice.hud.label");
        // 方式B では w は連続なので小数で出す。 EXPERIMENT_ENABLED=false のときは
        // 下の三項が定数畳み込みで消え、 従来どおり整数のスライス番号だけになる
        // (コンパイル結果から ObserverW への参照が 0 件になることを javap で確認済み)。
        double live = ObserverW.EXPERIMENT_ENABLED ? ObserverW.get() : Double.NaN;
        String value = Double.isNaN(live) ? Integer.toString(slice) : fmt(live);

        // 2 行目以降: 近傍 4 次元エンティティのデバッグ表示 (居なければ行ごと出さない)。
        List<Component> extra = entityDebugLines();

        int gap = 4;
        int contentW = font.width(label) + gap + font.width(value);
        for (Component line : extra) {
            contentW = Math.max(contentW, font.width(line));
        }
        int rows = 1 + extra.size();
        int panelW = PANEL_PAD * 2 + contentW;
        int panelH = PANEL_PAD * 2 + rows * font.lineHeight + (rows - 1) * ROW_GAP;

        int x0 = SCREEN_MARGIN;
        int y0 = SCREEN_MARGIN;

        g.fill(x0, y0, x0 + panelW, y0 + panelH, PANEL_BG);

        int tx = x0 + PANEL_PAD;
        int ty = y0 + PANEL_PAD;
        g.text(font, label, tx, ty, TEXT_LABEL);
        g.text(font, value, tx + font.width(label) + gap, ty, TEXT_VALUE);

        for (Component line : extra) {
            ty += font.lineHeight + ROW_GAP;
            g.text(font, line, tx, ty, TEXT_LABEL);
        }
    }

    /**
     * 近傍 4 次元エンティティのデバッグ行。
     *
     * <p>件数と、 <b>最も近いもの</b>の {@code dw} / 断面半径を出す。
     * これが見えていると {@code wThickness} と w 速度の比を調整するときの判断が速い
     * (「点 → 膨張 → 収縮 → 点」が読めるかの一次判定に直結する)。
     */
    private static List<Component> entityDebugLines() {
        List<Component> lines = new ArrayList<>();

        // 【診断実験】観測面 w の連続移動。 実験が無効なら 1 行も足さない (= 実験前と一致)。
        if (ObserverW.EXPERIMENT_ENABLED) {
            double observer = ObserverW.get();
            double nominal = ObserverW.nominalPlane();
            if (!Double.isNaN(observer) && !Double.isNaN(nominal)) {
                lines.add(Component.translatable("hyperslice.hud.observer_w",
                        fmt(observer), fmt(nominal), fmt(observer - nominal)));
            }
            // 【一時デバッグ】キー入力が届いているかを w の計算と切り離して読む行。
            // 入力側の切り分けが済んだら、 この 4 行と ObserverW.keyDebugLine() を消せばよい。
            Component keys = ObserverW.keyDebugLine();
            if (keys != null) {
                lines.add(keys);
            }
        }

        List<ClientHyperEntities.View> views = ClientHyperEntities.snapshot();
        if (views.isEmpty()) {
            return List.copyOf(lines);
        }
        double planeW = ClientHyperEntities.planeW();
        if (Double.isNaN(planeW)) {
            return List.copyOf(lines);
        }

        // 最も断面が大きい (= 観測面に最も近い) ものを代表にする。
        ClientHyperEntities.View nearest = null;
        double bestAbsDw = Double.MAX_VALUE;
        for (ClientHyperEntities.View v : views) {
            double absDw = Math.abs(v.dw(planeW));
            if (absDw < bestAbsDw) {
                bestAbsDw = absDw;
                nearest = v;
            }
        }
        if (nearest == null) {
            return List.copyOf(lines);
        }

        lines.add(Component.translatable("hyperslice.hud.entities", views.size()));
        lines.add(Component.translatable("hyperslice.hud.nearest",
                fmt(nearest.dw(planeW)), fmt(nearest.radius(planeW))));
        return List.copyOf(lines);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** 現在のレベルが HyperSlice のスライスなら w を、 違えば {@code -1} を返す。 */
    private static int currentSliceW(Level level) {
        Identifier id = level.dimension().identifier();
        if (!SliceRegistry.NAMESPACE.equals(id.getNamespace())) {
            return -1;
        }
        return SliceRegistry.wFromPath(id.getPath());
    }
}
