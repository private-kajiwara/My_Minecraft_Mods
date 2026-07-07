package com.kajiwara.omnichest.client.gui;

import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.client.render.ChestHighlighter;
import com.kajiwara.omnichest.client.render.DimensionStyle;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「ハイライト中のアイテムが どのディメンションにあるか」 を一覧する軽量 Screen (読み取り専用)。
 *
 * <p>
 * <b>データソース</b>: {@link ChestHighlighter#selectedItemsForHud()} (= ピン/ハイライト集合)。
 * 検索クエリは画面を閉じると失われる (= 永続しない) ため、 in-game (Screen 無し) から開ける
 * この menu の唯一の恒久ソースはハイライト集合。 寿命はピンと一致 (15s or pinPersistUntilOpened)。
 * 集計は {@link ChestHighlighter#activeVersion()} 変化時のみ再計算しキャッシュ。
 *
 * <p>
 * <b>見せ方</b>: ディメンション別グループ (= 色付き見出し {@link DimensionStyle} + 配下アイテム行)。
 * ディメンションは<b>文字ラベル＋色のみ</b>で表現し、 固有アイコン/モデルは使わない
 * (= 任意の Mod 追加 dim でも設定/画像追加なしで自動対応)。 純追加・既存挙動不変。
 *
 * <p>
 * 開閉トグルは {@link com.kajiwara.omnichest.client.ClientKeyBindings} の Alt+C ポール / 再割当キーから
 * {@link #toggle()} を呼ぶ。 ESC はバニラ既定で閉じる。
 */
public final class DimensionMenuScreen extends Screen {

    private static final int PANEL_MAX_W = 300;
    private static final int MARGIN = 20;
    private static final int PAD = 8;
    private static final int TITLE_H = 22;
    private static final int FOOTER_H = 16;
    private static final int GROUP_HEADER_H = 14;
    private static final int ITEM_ROW_H = 18;
    private static final int GROUP_GAP = 6;
    private static final int ICON = 16;

    private final Screen parent;

    private long cachedVersion = Long.MIN_VALUE;
    private List<Group> model = List.of();
    private double scrollPx = 0.0;

    // レイアウト (render で毎フレーム算出・scroll clamp 用に保持)
    private int listTop;
    private int listBottom;

    public DimensionMenuScreen(Screen parent) {
        super(OmniChestLocale.get("omnichest.dimension_menu.title", "Items by Dimension"));
        this.parent = parent;
    }

    // ════════════════════════════════════════════════════════════════════
    // 開閉トグル (ClientKeyBindings から呼ぶ)
    // ════════════════════════════════════════════════════════════════════

    /** 自画面が開いていれば閉じ、 何も開いていなければ開く (他 Screen 表示中は何もしない)。 */
    public static void toggle() {
        Minecraft mc = Minecraft.getInstance();
        //? if >=26.2 {
        /*Screen cur = mc.gui.screen();*/
        //?} else {
        Screen cur = mc.screen;
        //?}
        if (cur instanceof DimensionMenuScreen) {
            //? if >=26.2 {
            /*mc.setScreenAndShow(null);*/
            //?} else {
            mc.setScreen(null);
            //?}
        } else if (cur == null) {
            open();
        }
    }

    public static void open() {
        //? if >=26.2 {
        /*Minecraft.getInstance().setScreenAndShow(new DimensionMenuScreen(null));*/
        //?} else {
        Minecraft.getInstance().setScreen(new DimensionMenuScreen(null));
        //?}
    }

    @Override
    public void onClose() {
        //? if >=26.2 {
        /*if (this.minecraft != null) this.minecraft.setScreenAndShow(this.parent);*/
        //?} else {
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
        //?}
    }

    // ════════════════════════════════════════════════════════════════════
    // モデル (ディメンション別グループ)
    // ════════════════════════════════════════════════════════════════════

    private record Row(ItemStack icon, String name, int count) {
    }

    private record Group(int color, String label, List<Row> rows) {
    }

    private void rebuildIfChanged() {
        long v = ChestHighlighter.get().activeVersion();
        if (v == cachedVersion) {
            return;
        }
        cachedVersion = v;

        List<ChestHighlighter.SelectedItem> items = ChestHighlighter.get().selectedItemsForHud();
        // ディメンション → (アイテム行) に反転集計する。 1 アイテムが複数 dim にまたがる場合は
        // その dim ごとに、 その dim での合計個数で 1 行を立てる。
        LinkedHashMap<ResourceKey<Level>, List<Row>> byDim = new LinkedHashMap<>();
        for (ChestHighlighter.SelectedItem si : items) {
            LinkedHashMap<ResourceKey<Level>, Integer> perDim = new LinkedHashMap<>();
            for (ChestHighlighter.Located l : si.locations()) {
                perDim.merge(l.dimension(), l.count(), Integer::sum);
            }
            String name = si.icon().getHoverName().getString();
            for (Map.Entry<ResourceKey<Level>, Integer> e : perDim.entrySet()) {
                byDim.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new Row(si.icon(), name, e.getValue()));
            }
        }

        List<ResourceKey<Level>> dims = new ArrayList<>(byDim.keySet());
        dims.sort((a, b) -> {
            int ra = DimensionStyle.orderRank(a);
            int rb = DimensionStyle.orderRank(b);
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            return DimensionStyle.labelString(a).compareToIgnoreCase(DimensionStyle.labelString(b));
        });

        List<Group> groups = new ArrayList<>(dims.size());
        for (ResourceKey<Level> d : dims) {
            List<Row> rows = byDim.get(d);
            rows.sort((a, b) -> Integer.compare(b.count(), a.count()));
            groups.add(new Group(DimensionStyle.color(d), DimensionStyle.labelString(d), rows));
        }
        this.model = groups;
    }

    private int contentHeight() {
        int h = 0;
        for (int i = 0; i < model.size(); i++) {
            Group g = model.get(i);
            h += GROUP_HEADER_H + g.rows().size() * ITEM_ROW_H;
            if (i < model.size() - 1) {
                h += GROUP_GAP;
            }
        }
        return h;
    }

    private void clampScroll() {
        int view = Math.max(0, listBottom - listTop);
        double max = Math.max(0, contentHeight() - view);
        if (scrollPx < 0) {
            scrollPx = 0;
        } else if (scrollPx > max) {
            scrollPx = max;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        rebuildIfChanged();
        super.extractRenderState(g, mouseX, mouseY, partialTick);   // 暗転背景

        Font font = this.font;
        int panelW = Math.min(PANEL_MAX_W, this.width - 2 * MARGIN);
        int cx = this.width / 2;
        int left = cx - panelW / 2;
        int right = cx + panelW / 2;
        int top = MARGIN;
        int bottom = this.height - MARGIN;

        // パネル背景 (フラット暗半透明) + 見出し区切り
        g.fill(left, top, right, bottom, ThemeColorResolver.PANEL_BG);
        g.centeredText(font, this.title, cx, top + 7, ThemeColorResolver.TEXT_PRIMARY);
        g.fill(left + PAD, top + TITLE_H - 2, right - PAD, top + TITLE_H - 1, ThemeColorResolver.SEPARATOR);

        this.listTop = top + TITLE_H;
        this.listBottom = bottom - FOOTER_H;
        clampScroll();

        // フッタヒント
        Component hint = OmniChestLocale.get("omnichest.dimension_menu.hint",
                "Alt+C / Esc to close");
        g.centeredText(font, hint, cx, bottom - 12, ThemeColorResolver.TEXT_DIM);

        // 空状態
        if (model.isEmpty()) {
            Component empty = OmniChestLocale.get("omnichest.dimension_menu.empty",
                    "No highlighted items");
            g.centeredText(font, empty, cx, (listTop + listBottom) / 2 - font.lineHeight / 2,
                    ThemeColorResolver.TEXT_SECONDARY);
            return;
        }

        // リスト (scissor でクリップしてスクロール)
        g.enableScissor(left, listTop, right, listBottom);
        int y = listTop - (int) Math.round(scrollPx);
        for (Group grp : model) {
            // 行がビュー内に少しでもあるときだけ描く (= 軽量化)。
            int groupH = GROUP_HEADER_H + grp.rows().size() * ITEM_ROW_H;
            if (y + groupH >= listTop && y <= listBottom) {
                // ─── ディメンション見出し (色付きラベル + 件数) ───
                String header = grp.label() + "  (" + grp.rows().size() + ")";
                text(g, font, header, left + PAD, y + 3, grp.color());
                int hy = y + GROUP_HEADER_H;
                // 見出し直下に色の細線 (= バッジ色を面で示す)
                g.fill(left + PAD, hy - 2, right - PAD, hy - 1, withAlpha(grp.color(), 0x66));

                int ry = hy;
                for (Row row : grp.rows()) {
                    if (ry + ITEM_ROW_H >= listTop && ry <= listBottom) {
                        g.item(row.icon(), left + PAD, ry + 1);
                        int nameX = left + PAD + ICON + 4;
                        String countStr = "×" + row.count();               // "×N"
                        int countW = font.width(countStr);
                        int countX = right - PAD - countW;
                        int textY = ry + (ITEM_ROW_H - font.lineHeight) / 2;
                        int nameBudget = Math.max(0, countX - 4 - nameX);
                        String name = clip(font, row.name(), nameBudget);
                        text(g, font, name, nameX, textY, ThemeColorResolver.TEXT_PRIMARY);
                        text(g, font, countStr, countX, textY, ThemeColorResolver.TEXT_HIGHLIGHT);
                    }
                    ry += ITEM_ROW_H;
                }
                y = ry;
            } else {
                y += groupH;
            }
            y += GROUP_GAP;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        this.scrollPx -= dy * ITEM_ROW_H * 2;
        clampScroll();
        return true;
    }

    // ─── 小物 ───

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static String clip(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) {
            return s;
        }
        String ell = "…";                                     // …
        int ellW = font.width(ell);
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - ellW)) + ell;
    }

    private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int argb) {
        FormattedCharSequence seq = Component.literal(s).getVisualOrderText();
        g.text(font, seq, x, y, argb);
    }
}
