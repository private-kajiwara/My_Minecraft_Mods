package com.kajiwara.omnichest.client.render;

import com.kajiwara.omnichest.client.compat.SafeRenderDispatcher;
import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「選択中アイテム情報 HUD」 — 検索でクリック固定したアイテムの
 * <b>名前 / 合計個数 / 場所</b> を、 プレイ中の画面左上に読み取り専用で常時表示する純追加 HUD。
 *
 * <p>
 * <b>設計 (OmniChest データ資産 × VisualizeGate の HUD 作法・性能教訓の掛け合わせ)</b>:
 * <ul>
 *   <li><b>唯一のソース</b>: {@link ChestHighlighter#selectedItemsForHud()} = ピン/ビームと同じ
 *       {@code active} を集計した結果。 独自の全スナップショット走査は<b>しない</b> (= 相乗り)。
 *       よって HUD の寿命はピンと完全一致する (= 選択で現れ、 15 秒 or {@code pinPersistUntilOpened}
 *       でピンと同時に消える)。</li>
 *   <li><b>性能</b>: 集計は {@link ChestHighlighter#activeVersion()} が変化したときだけ再計算し、
 *       結果をキャッシュする。 毎フレームは軽量な派生値 (最寄り方向/距離) のみ算出する
 *       (= VG の「描画スレッド毎秒サンプリングで毎秒カクつき」事故を再現しない)。</li>
 *   <li><b>次元</b>: 場所は必ず次元でフィルタし、 必ず次元ラベルを付す
 *       (= VG の「ネザーなのに OW が出た」取り違えを避ける)。</li>
 *   <li><b>HUD 規律</b>: F1 (hideGui) / F3 デバッグ / いずれかの Screen 表示中 は非描画。
 *       入力を一切奪わない (= 読み取り専用・クリック透過)。 半透明・コンパクト。 画面<b>左上</b>
 *       アンカーで VG 右下ドック・ホットバー・クロスヘア・右上ステータスと非重複。</li>
 *   <li><b>見た目</b>: 色は {@link ThemeColorResolver} トークンに集約 (ハードコードしない)。
 *       フラットな暗い半透明パネル + 細い 1 本線、 見出しは淡色、 本文は白系、 金 (アクセント) は数値。
 *       枠で飾らず余白と淡色見出しで構造を見せる。</li>
 * </ul>
 *
 * <p>
 * 描画経路は既存の {@link ChestHighlighter} と同じ HUD 登録 API (26.1+={@code HudElementRegistry} /
 * legacy={@code HudRenderCallback}) に載せる (= 新規 Mixin を増やさない)。 legacy=texbatch /
 * modern=GPU3D 双方の 2D GUI 描画で破綻しない (= 全 5 ノードのバックエンド共通の {@code g.fill/text/item})。
 */
public final class SelectedItemHudRenderer {

    private static final SelectedItemHudRenderer INSTANCE = new SelectedItemHudRenderer();

    // ─── レイアウト定数 (すべて論理 px = GUIスケール後・resize 安全) ───
    private static final int SCREEN_MARGIN = 4;
    private static final int PANEL_PAD = 4;
    private static final int ICON_SIZE = 16;
    private static final int ICON_TEXT_GAP = 3;
    private static final int ROW_GAP = 2;
    private static final int SEP_H = 1;
    /** アイテム名の最大幅 (px)。 超過分は末尾を省略記号で切り詰めてパネル横幅の暴発を防ぐ。 */
    private static final int NAME_MAX_WIDTH = 140;
    private static final String ELLIPSIS = "…";

    /** 上位いくつまでの選択アイテムを対象にするか (= 主 1 件表示、 残りは「+K 件」)。 */
    private static final int PRIMARY_LIMIT = 1;

    // ─── キャッシュ (activeVersion 変化時のみ再計算) ───
    private long cachedVersion = Long.MIN_VALUE;
    private List<ChestHighlighter.SelectedItem> cached = List.of();

    private SelectedItemHudRenderer() {
    }

    public static SelectedItemHudRenderer get() {
        return INSTANCE;
    }

    /**
     * HUD 登録。 {@link com.kajiwara.omnichest.OmniChestClient} から 1 回だけ呼ぶ。
     * 既存 {@link ChestHighlighter#register()} と同じ HUD パスへ載せる (= 新規 Mixin なし)。
     */
    public static void register() {
        //? if >=26.1 {
        HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("omnichest", "selected_item_hud"),
                (g, deltaTracker) -> SafeRenderDispatcher.safeRun("selected-item-hud",
                        () -> INSTANCE.onHudRender(g)));
        //?} else {
        /*HudRenderCallback.EVENT.register((g, deltaTracker) ->
                SafeRenderDispatcher.safeRun("selected-item-hud",
                        () -> INSTANCE.onHudRender(g)));*/
        //?}
    }

    private void onHudRender(GuiGraphicsExtractor g) {
        // ─── 表示可否ゲート ───
        if (!showEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        // HUD 規律: Screen 表示中 / F1 (hideGui) / F3 デバッグ は非描画。 入力は奪わない (描画のみ)。
        //? if >=26.2 {
        /*if (mc.gui.screen() != null || mc.gui.hud.isHidden()) {
            return;
        }*/
        //?} else {
        if (mc.screen != null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        //?}

        // ─── 集計キャッシュ: activeVersion が変わったときだけ再計算 (= 毎フレーム走査しない) ───
        long version = ChestHighlighter.get().activeVersion();
        if (version != cachedVersion) {
            cached = ChestHighlighter.get().selectedItemsForHud();
            cachedVersion = version;
        }
        if (cached.isEmpty()) {
            return;
        }

        draw(g, mc, cached);
    }

    private static boolean showEnabled() {
        try {
            return ConfigManager.get().render.enableOverlay
                    && ConfigManager.get().render.showSelectedItemHud;
        } catch (Throwable ignored) {
            // 設定読込前/失敗時は安全側 = 非表示。
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    private void draw(GuiGraphicsExtractor g, Minecraft mc, List<ChestHighlighter.SelectedItem> items) {
        Font font = mc.font;
        int lh = font.lineHeight;

        ChestHighlighter.SelectedItem primary = items.get(0);
        int others = items.size() - PRIMARY_LIMIT;

        // ── 表示文字列を用意 (主 1 件) ──
        String name = clip(font, primary.icon().getHoverName().getString(), NAME_MAX_WIDTH);
        String countStr = "×" + primary.totalCount();          // "×N"
        String dimStr = dimBreakdown(primary.locations());          // "OW ×120 · Nether ×8"
        String nearStr = nearest(mc, primary.locations());          // "▲ 42m" / 他次元
        String moreStr = others > 0
                ? OmniChestLocale.get("omnichest.hud.selected.more", "+%1$d more", others).getString()
                : null;

        // ── 幅・高さ算出 (論理 px) ──
        int headerW = ICON_SIZE + ICON_TEXT_GAP + font.width(name) + ICON_TEXT_GAP + font.width(countStr);
        int contentW = headerW;
        contentW = Math.max(contentW, font.width(dimStr));
        contentW = Math.max(contentW, font.width(nearStr));
        if (moreStr != null) {
            contentW = Math.max(contentW, font.width(moreStr));
        }
        int panelW = PANEL_PAD * 2 + contentW;

        int bodyRows = 2 + (moreStr != null ? 1 : 0);               // dim + near (+more)
        int panelH = PANEL_PAD * 2 + ICON_SIZE + ROW_GAP + SEP_H
                + bodyRows * lh + (bodyRows - 1) * ROW_GAP;

        int x0 = SCREEN_MARGIN;
        int y0 = SCREEN_MARGIN;

        // ── パネル背景 (フラット暗半透明) ──
        g.fill(x0, y0, x0 + panelW, y0 + panelH, ThemeColorResolver.PANEL_BG);

        int cx = x0 + PANEL_PAD;
        int cy = y0 + PANEL_PAD;

        // ── ヘッダ行: アイコン + 名前(白) + ×個数(金) ──
        g.item(primary.icon(), cx, cy);
        int headerTextY = cy + (ICON_SIZE - lh) / 2;
        int tx = cx + ICON_SIZE + ICON_TEXT_GAP;
        text(g, font, name, tx, headerTextY, ThemeColorResolver.TEXT_PRIMARY);
        tx += font.width(name) + ICON_TEXT_GAP;
        text(g, font, countStr, tx, headerTextY, ThemeColorResolver.TEXT_HIGHLIGHT);

        // ── 細い区切り 1 本 ──
        int sepY = cy + ICON_SIZE + ROW_GAP;
        g.fill(cx, sepY, x0 + panelW - PANEL_PAD, sepY + SEP_H, ThemeColorResolver.SEPARATOR);

        // ── 本文行 (淡色) ──
        int rowY = sepY + SEP_H + ROW_GAP;
        text(g, font, dimStr, cx, rowY, ThemeColorResolver.TEXT_SECONDARY);
        rowY += lh + ROW_GAP;
        text(g, font, nearStr, cx, rowY, ThemeColorResolver.TEXT_SECONDARY);
        if (moreStr != null) {
            rowY += lh + ROW_GAP;
            text(g, font, moreStr, cx, rowY, ThemeColorResolver.TEXT_DIM);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 派生値の算出
    // ════════════════════════════════════════════════════════════════════

    /** 場所を次元別に集計して "OW ×120 · Nether ×8" の要約文字列にする (= 次元ラベル必須)。 */
    private String dimBreakdown(List<ChestHighlighter.Located> locations) {
        Map<ResourceKey<Level>, Integer> byDim = new LinkedHashMap<>();
        for (ChestHighlighter.Located l : locations) {
            byDim.merge(l.dimension(), l.count(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ResourceKey<Level>, Integer> e : byDim.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" · ");                              // " · "
            }
            sb.append(dimLabel(e.getKey())).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * <b>毎フレームの唯一の走査</b>: 現在の次元にある出現場所のうち最寄り 1 件の
     * プレイヤー相対の方向 (▲▶▼◀) + 距離 (m) を返す。 現在の次元に無ければ「他次元にあり」。
     * 対象は既に解決済みの小さな集合 (= 選択アイテムの出現コンテナ) なので軽量。
     */
    private String nearest(Minecraft mc, List<ChestHighlighter.Located> locations) {
        ResourceKey<Level> curDim = mc.level.dimension();
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (ChestHighlighter.Located l : locations) {
            if (!l.dimension().equals(curDim)) {
                continue;
            }
            BlockPos p = l.pos();
            double dx = (p.getX() + 0.5) - px;
            double dy = (p.getY() + 0.5) - py;
            double dz = (p.getZ() + 0.5) - pz;
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = p;
            }
        }
        if (best == null) {
            return OmniChestLocale.get("omnichest.hud.selected.nearest.other_dim",
                    "in another dimension").getString();
        }
        double dist = Math.sqrt(bestSq);
        String arrow = directionArrow(mc, px, pz, best);
        return OmniChestLocale.get("omnichest.hud.selected.nearest",
                "%1$s %2$dm", arrow, (int) Math.round(dist)).getString();
    }

    /** プレイヤーの向き基準で最寄り座標への 4 方位アイコン (▲=前 / ▶=右 / ▼=後 / ◀=左) を返す。 */
    private static String directionArrow(Minecraft mc, double px, double pz, BlockPos target) {
        double dx = (target.getX() + 0.5) - px;
        double dz = (target.getZ() + 0.5) - pz;
        // MC yaw: 0=+Z(南), 増加で時計回り (= 右回り)。 ベクトル (vx,vz) → yaw=atan2(-vx, vz)。
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        int idx = ((int) Math.round(rel / 90.0) % 4 + 4) % 4;      // 0=前,1=右,2=後,3=左
        switch (idx) {
            case 1:
                return "▶";                                   // ▶
            case 2:
                return "▼";                                   // ▼
            case 3:
                return "◀";                                   // ◀
            default:
                return "▲";                                   // ▲
        }
    }

    /** 3 バニラ次元は短いローカライズラベル、 その他は次元 ID の path を返す。 */
    private String dimLabel(ResourceKey<Level> dim) {
        if (dim.equals(Level.OVERWORLD)) {
            return OmniChestLocale.get("omnichest.hud.selected.dim.overworld", "OW").getString();
        }
        if (dim.equals(Level.NETHER)) {
            return OmniChestLocale.get("omnichest.hud.selected.dim.nether", "Nether").getString();
        }
        if (dim.equals(Level.END)) {
            return OmniChestLocale.get("omnichest.hud.selected.dim.end", "End").getString();
        }
        return dim.identifier().getPath();
    }

    // ════════════════════════════════════════════════════════════════════
    // 小物
    // ════════════════════════════════════════════════════════════════════

    /** 文字列を最大幅で切り詰め (超過時のみ末尾を省略記号化)。 パネル横幅の暴発を防ぐ。 */
    private static String clip(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) {
            return s;
        }
        int ellipsisW = font.width(ELLIPSIS);
        String head = font.plainSubstrByWidth(s, Math.max(0, maxWidth - ellipsisW));
        return head + ELLIPSIS;
    }

    private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int argb) {
        FormattedCharSequence seq = Component.literal(s).getVisualOrderText();
        g.text(font, seq, x, y, argb);
    }
}
