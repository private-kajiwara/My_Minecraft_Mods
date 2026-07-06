package com.kajiwara.omnichest.client.render;

import com.kajiwara.omnichest.i18n.OmniChestLocale;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * ディメンションを「文字ラベル＋色」だけで表現する単一ソース。
 *
 * <p>
 * <b>方針 (= 任意の大型Mod追加ディメンションでも設定/画像追加なしで自動対応)</b>:
 * <ul>
 *   <li><b>アイコン/モデルは使わない</b>。 バニラ固有画像やハードコード列挙に依存しない。</li>
 *   <li><b>ラベル</b>: バニラ3種は分かりやすい名称 (ローカライズ)。 Mod追加は identifier
 *       (namespace:path) から機械的に読みやすい文字列を生成 (path を単語整形＋非 minecraft は
 *       namespace 併記)。 未訪問ディメンションでも保存文字列 identifier から確実に作れる。</li>
 *   <li><b>色</b>: バニラ3種は慣習的な固定色。 Mod追加は identifier をハッシュした決定的な色
 *       (同じ dim は常に同じ色)。 可読性のため彩度/明度をクランプ (暗背景で潰れない)。</li>
 * </ul>
 *
 * <p>
 * identifier アクセスは {@code dim.identifier()} (26.1 基準名)。 &lt;1.21.11 は Stonecutter が
 * {@code .location()} へ前方変換する。 {@code getNamespace()/getPath()} は両世代共通。
 */
public final class DimensionStyle {

    private DimensionStyle() {
    }

    // ─── バニラ3種の慣習色 (ARGB・不透明) ───
    private static final int C_OVERWORLD = 0xFF6FCF6F;   // 緑
    private static final int C_NETHER = 0xFFE0574A;      // 赤
    private static final int C_END = 0xFFC9A6EC;         // 紫

    /** Mod追加 dim のハッシュ色: 暗背景で読める彩度/明度に固定 (色相のみ identifier 依存)。 */
    private static final float MODDED_SAT = 0.55f;
    private static final float MODDED_VAL = 0.95f;

    /** ディメンションの表示ラベル (文字のみ)。 */
    public static Component label(ResourceKey<Level> dim) {
        if (dim.equals(Level.OVERWORLD)) {
            return OmniChestLocale.get("omnichest.dimension.overworld", "Overworld");
        }
        if (dim.equals(Level.NETHER)) {
            return OmniChestLocale.get("omnichest.dimension.the_nether", "Nether");
        }
        if (dim.equals(Level.END)) {
            return OmniChestLocale.get("omnichest.dimension.the_end", "The End");
        }
        // Mod追加/未知: identifier を機械的に整形 (ハードコード列挙なし)。
        var id = dim.identifier();
        String pretty = titleCase(id.getPath().replace('_', ' ').replace('-', ' '));
        String ns = id.getNamespace();
        if (!ns.equals("minecraft")) {
            pretty = pretty + " (" + ns + ")";
        }
        return Component.literal(pretty);
    }

    /** ラベルの生文字列 (幅計算・String 経路用)。 */
    public static String labelString(ResourceKey<Level> dim) {
        return label(dim).getString();
    }

    /** ディメンションの文字色/バッジ色 (ARGB・不透明)。 同じ dim は常に同じ色。 */
    public static int color(ResourceKey<Level> dim) {
        if (dim.equals(Level.OVERWORLD)) {
            return C_OVERWORLD;
        }
        if (dim.equals(Level.NETHER)) {
            return C_NETHER;
        }
        if (dim.equals(Level.END)) {
            return C_END;
        }
        // 決定的ハッシュ → 色相。 String.hashCode は仕様で決定的 (JVM 非依存)。
        int h = dim.identifier().toString().hashCode();
        h ^= (h >>> 16);
        float hue = Math.floorMod(h, 360) / 360.0f;
        return 0xFF000000 | hsvToRgb(hue, MODDED_SAT, MODDED_VAL);
    }

    /** 並び順の rank: OW=0 / Nether=1 / End=2 / その他=3 (その他はラベル alpha で安定化)。 */
    public static int orderRank(ResourceKey<Level> dim) {
        if (dim.equals(Level.OVERWORLD)) {
            return 0;
        }
        if (dim.equals(Level.NETHER)) {
            return 1;
        }
        if (dim.equals(Level.END)) {
            return 2;
        }
        return 3;
    }

    // ─── 小物 ───

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                cap = true;
                sb.append(c);
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** HSV→RGB (0xRRGGBB)。 バニラ API 差を避けるため純算術で実装 (全ノード共通)。 */
    private static int hsvToRgb(float hue, float sat, float val) {
        int i = (int) (hue * 6.0f) % 6;
        if (i < 0) {
            i += 6;
        }
        float f = hue * 6.0f - (float) Math.floor(hue * 6.0f);
        float p = val * (1.0f - sat);
        float q = val * (1.0f - f * sat);
        float t = val * (1.0f - (1.0f - f) * sat);
        float r;
        float g;
        float b;
        switch (i) {
            case 0 -> { r = val; g = t; b = p; }
            case 1 -> { r = q; g = val; b = p; }
            case 2 -> { r = p; g = val; b = t; }
            case 3 -> { r = p; g = q; b = val; }
            case 4 -> { r = t; g = p; b = val; }
            default -> { r = val; g = p; b = q; }
        }
        int ri = clamp255(Math.round(r * 255.0f));
        int gi = clamp255(Math.round(g * 255.0f));
        int bi = clamp255(Math.round(b * 255.0f));
        return (ri << 16) | (gi << 8) | bi;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
