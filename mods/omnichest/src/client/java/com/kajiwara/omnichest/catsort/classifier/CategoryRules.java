package com.kajiwara.omnichest.catsort.classifier;

import com.kajiwara.omnichest.catsort.ItemCategory;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 「カテゴリ判定ルールの デフォルト レジストリ」。
 *
 * <p>
 * <b>方針</b>:
 * <ol>
 * <li>Item Tag マッチを最優先 (= バニラ + データパック / MOD のタグでも拾える)。</li>
 * <li>続いて Data Component (FoodProperties / PotionContents 等) を見る。</li>
 * <li>最後に Identifier path 文字列の suffix / contains マッチで MOD アイテムを救う。</li>
 * </ol>
 *
 * <p>
 * <b>並び順 = 優先度</b>。 上から順にルールを評価し、 最初にマッチしたカテゴリを採用する
 * ({@link TagCategoryClassifier} 内で実装)。
 * したがって 「より具体的なルール ({@code piston} は REDSTONE) を先に書く」
 * → 「より広いルール ({@code stone} は STONE) を後に書く」 順で並べる。
 *
 * <p>
 * <b>巨大 switch 禁止</b> の方針より、 ここはファクトリ呼び出しを並べるだけで
 * 1 つの巨大関数を作らない。 新カテゴリを増やすときは:
 * <ol>
 * <li>{@link ItemCategory} に enum 値を 1 行足す。</li>
 * <li>本ファイル {@link #defaults()} に該当ルールを追記する。</li>
 * </ol>
 */
public final class CategoryRules {

    private CategoryRules() {
    }

    /**
     * すべてのバンドル ルール (immutable, 評価順)。
     */
    public static List<CategoryRule> defaults() {
        List<CategoryRule> r = new ArrayList<>(192);

        // ════════════════════════════════════════════════════════════════
        // POTION (= 醸造由来は他カテゴリとも被るため最先頭で確定させる)
        // ════════════════════════════════════════════════════════════════
        r.add(componentRule(DataComponents.POTION_CONTENTS, ItemCategory.POTION));
        r.add(pathExact("potion", ItemCategory.POTION));
        r.add(pathExact("splash_potion", ItemCategory.POTION));
        r.add(pathExact("lingering_potion", ItemCategory.POTION));
        r.add(pathExact("tipped_arrow", ItemCategory.POTION));
        r.add(pathExact("brewing_stand", ItemCategory.POTION));
        r.add(pathExact("glass_bottle", ItemCategory.POTION));
        r.add(pathExact("blaze_powder", ItemCategory.POTION));
        r.add(pathExact("nether_wart", ItemCategory.POTION));
        r.add(pathExact("ghast_tear", ItemCategory.POTION));
        r.add(pathExact("dragon_breath", ItemCategory.POTION));
        r.add(pathExact("fermented_spider_eye", ItemCategory.POTION));
        r.add(pathExact("magma_cream", ItemCategory.POTION));

        // ════════════════════════════════════════════════════════════════
        // COMBAT (= 防具 / 剣 / 弓 / トライデント / 盾 / 不死のトーテム)
        //   武器・防具は ENCHANTABLE タグでも検出できるが TOOL と衝突しやすいので
        //   COMBAT 専用タグ群 (= SWORDS / *_ARMOR / ARROWS) を優先する。
        // ════════════════════════════════════════════════════════════════
        r.add(tagRule(ItemTags.SWORDS, ItemCategory.COMBAT));
        r.add(tagRule(ItemTags.HEAD_ARMOR, ItemCategory.COMBAT));
        r.add(tagRule(ItemTags.CHEST_ARMOR, ItemCategory.COMBAT));
        r.add(tagRule(ItemTags.LEG_ARMOR, ItemCategory.COMBAT));
        r.add(tagRule(ItemTags.FOOT_ARMOR, ItemCategory.COMBAT));
        r.add(tagRule(ItemTags.ARROWS, ItemCategory.COMBAT));
        r.add(pathExact("bow", ItemCategory.COMBAT));
        r.add(pathExact("crossbow", ItemCategory.COMBAT));
        r.add(pathExact("shield", ItemCategory.COMBAT));
        r.add(pathExact("trident", ItemCategory.COMBAT));
        r.add(pathExact("mace", ItemCategory.COMBAT));
        r.add(pathExact("totem_of_undying", ItemCategory.COMBAT));

        // ════════════════════════════════════════════════════════════════
        // TOOL (= ピッケル / 斧 / シャベル / クワ / はさみ / 釣竿 / コンパス / 時計)
        //   COMBAT の後に置くこと (= 「斧 = AXES タグ」が COMBAT 系の SWORDS とは別タグなので衝突しないが、
        //   念のための優先順位を明示する目的)。
        // ════════════════════════════════════════════════════════════════
        r.add(tagRule(ItemTags.PICKAXES, ItemCategory.TOOL));
        r.add(tagRule(ItemTags.AXES, ItemCategory.TOOL));
        r.add(tagRule(ItemTags.SHOVELS, ItemCategory.TOOL));
        r.add(tagRule(ItemTags.HOES, ItemCategory.TOOL));
        r.add(pathExact("shears", ItemCategory.TOOL));
        r.add(pathExact("fishing_rod", ItemCategory.TOOL));
        r.add(pathExact("flint_and_steel", ItemCategory.TOOL));
        r.add(pathExact("compass", ItemCategory.TOOL));
        r.add(pathExact("recovery_compass", ItemCategory.TOOL));
        r.add(pathExact("clock", ItemCategory.TOOL));
        r.add(pathExact("spyglass", ItemCategory.TOOL));
        r.add(pathExact("name_tag", ItemCategory.TOOL));
        r.add(pathExact("lead", ItemCategory.TOOL));
        r.add(pathExact("bucket", ItemCategory.TOOL));
        r.add(pathSuffix("_bucket", ItemCategory.TOOL));

        // ════════════════════════════════════════════════════════════════
        // FOOD (= FoodProperties data component + 食べ物タグ群)
        // ════════════════════════════════════════════════════════════════
        r.add(componentRule(DataComponents.FOOD, ItemCategory.FOOD));
        r.add(tagRule(ItemTags.FISHES, ItemCategory.FOOD));
        r.add(tagRule(ItemTags.MEAT, ItemCategory.FOOD));
        r.add(tagRule(ItemTags.EGGS, ItemCategory.FOOD));
        r.add(pathContains("cake", ItemCategory.FOOD));
        r.add(pathExact("milk_bucket", ItemCategory.FOOD));

        // ════════════════════════════════════════════════════════════════
        // FARM (= 苗木 / 種 / 花 / 葉 / 骨粉 / 小麦 / 干草)
        // ════════════════════════════════════════════════════════════════
        r.add(tagRule(ItemTags.SAPLINGS, ItemCategory.FARM));
        r.add(tagRule(ItemTags.VILLAGER_PLANTABLE_SEEDS, ItemCategory.FARM));
        //? if >=26.2 {
        /*r.add(tagRule(oc262$itemTag("flowers"), ItemCategory.FARM));
        r.add(tagRule(oc262$itemTag("small_flowers"), ItemCategory.FARM));*/
        //?} else {
        r.add(tagRule(ItemTags.FLOWERS, ItemCategory.FARM));
        r.add(tagRule(ItemTags.SMALL_FLOWERS, ItemCategory.FARM));
        //?}
        r.add(tagRule(ItemTags.LEAVES, ItemCategory.FARM));
        r.add(pathContains("seed", ItemCategory.FARM));
        r.add(pathExact("bone_meal", ItemCategory.FARM));
        r.add(pathExact("wheat", ItemCategory.FARM));
        r.add(pathExact("hay_block", ItemCategory.FARM));
        r.add(pathExact("composter", ItemCategory.FARM));

        // ════════════════════════════════════════════════════════════════
        // REDSTONE (= 動力部品 + 回路素子 + レール)
        // ════════════════════════════════════════════════════════════════
        r.add(pathExact("redstone", ItemCategory.REDSTONE));
        r.add(pathExact("redstone_block", ItemCategory.REDSTONE));
        r.add(pathExact("redstone_torch", ItemCategory.REDSTONE));
        r.add(pathExact("repeater", ItemCategory.REDSTONE));
        r.add(pathExact("comparator", ItemCategory.REDSTONE));
        r.add(pathExact("observer", ItemCategory.REDSTONE));
        r.add(pathExact("piston", ItemCategory.REDSTONE));
        r.add(pathExact("sticky_piston", ItemCategory.REDSTONE));
        r.add(pathExact("dispenser", ItemCategory.REDSTONE));
        r.add(pathExact("dropper", ItemCategory.REDSTONE));
        r.add(pathExact("hopper", ItemCategory.REDSTONE));
        r.add(pathExact("lever", ItemCategory.REDSTONE));
        r.add(pathExact("daylight_detector", ItemCategory.REDSTONE));
        r.add(pathExact("tripwire_hook", ItemCategory.REDSTONE));
        r.add(pathExact("target", ItemCategory.REDSTONE));
        r.add(pathExact("crafter", ItemCategory.REDSTONE));
        //? if >=26.2 {
        /*r.add(tagRule(oc262$itemTag("buttons"), ItemCategory.REDSTONE));*/
        //?} else {
        r.add(tagRule(ItemTags.BUTTONS, ItemCategory.REDSTONE));
        //?}
        r.add(tagRule(ItemTags.RAILS, ItemCategory.REDSTONE));
        r.add(pathContains("pressure_plate", ItemCategory.REDSTONE));
        r.add(pathContains("minecart", ItemCategory.REDSTONE));

        // ════════════════════════════════════════════════════════════════
        // MAGIC (= エンチャント / 経験値 / レア素材)
        //   ※ Item は ENCHANTED 等の専用タグが無いので個別に列挙する
        // ════════════════════════════════════════════════════════════════
        r.add(pathExact("experience_bottle", ItemCategory.MAGIC));
        r.add(pathExact("enchanted_book", ItemCategory.MAGIC));
        r.add(pathExact("nether_star", ItemCategory.MAGIC));
        r.add(pathExact("heart_of_the_sea", ItemCategory.MAGIC));
        r.add(pathExact("echo_shard", ItemCategory.MAGIC));
        r.add(pathExact("amethyst_shard", ItemCategory.MAGIC));
        r.add(pathExact("amethyst_block", ItemCategory.MAGIC));
        r.add(pathExact("glow_ink_sac", ItemCategory.MAGIC));
        r.add(pathExact("lapis_lazuli", ItemCategory.MAGIC));

        // ════════════════════════════════════════════════════════════════
        // ORE (= 鉱石 / 原石 / インゴット / 金塊 / raw block)
        //   ※ "raw_iron" / "iron_ingot" / "iron_nugget" / "iron_ore" を 1 グループにしたい
        // ════════════════════════════════════════════════════════════════
        r.add(tagRule(ItemTags.IRON_ORES, ItemCategory.ORE));
        r.add(tagRule(ItemTags.GOLD_ORES, ItemCategory.ORE));
        r.add(tagRule(ItemTags.DIAMOND_ORES, ItemCategory.ORE));
        r.add(tagRule(ItemTags.EMERALD_ORES, ItemCategory.ORE));
        r.add(tagRule(ItemTags.LAPIS_ORES, ItemCategory.ORE));
        r.add(tagRule(ItemTags.COPPER_ORES, ItemCategory.ORE));
        r.add(tagRule(ItemTags.COAL_ORES, ItemCategory.ORE));
        r.add(tagRule(ItemTags.REDSTONE_ORES, ItemCategory.ORE));
        r.add(tagRule(ItemTags.COALS, ItemCategory.ORE));
        r.add(pathSuffix("_ore", ItemCategory.ORE));
        r.add(pathSuffix("_ingot", ItemCategory.ORE));
        r.add(pathSuffix("_nugget", ItemCategory.ORE));
        r.add(pathContains("raw_", ItemCategory.ORE));
        r.add(pathExact("coal", ItemCategory.ORE));
        r.add(pathExact("charcoal", ItemCategory.ORE));
        r.add(pathExact("diamond", ItemCategory.ORE));
        r.add(pathExact("emerald", ItemCategory.ORE));

        // ════════════════════════════════════════════════════════════════
        // NETHER (= ネザー由来素材)
        //   "netherite" / "netherrack" / "nether_brick" 等は ORE / BUILDING に流れないよう先に処理。
        // ════════════════════════════════════════════════════════════════
        r.add(tagRule(ItemTags.CRIMSON_STEMS, ItemCategory.NETHER));
        r.add(tagRule(ItemTags.WARPED_STEMS, ItemCategory.NETHER));
        r.add(pathContains("netherite", ItemCategory.NETHER));
        r.add(pathContains("netherrack", ItemCategory.NETHER));
        r.add(pathContains("nether_brick", ItemCategory.NETHER));
        r.add(pathContains("blackstone", ItemCategory.NETHER));
        r.add(pathContains("basalt", ItemCategory.NETHER));
        r.add(pathContains("soul_", ItemCategory.NETHER));
        r.add(pathContains("quartz", ItemCategory.NETHER));
        r.add(pathContains("crimson", ItemCategory.NETHER));
        r.add(pathContains("warped", ItemCategory.NETHER));
        r.add(pathContains("glowstone", ItemCategory.NETHER));
        r.add(pathContains("magma_block", ItemCategory.NETHER));

        // ════════════════════════════════════════════════════════════════
        // END (= エンド由来素材)
        // ════════════════════════════════════════════════════════════════
        r.add(pathContains("end_stone", ItemCategory.END));
        r.add(pathContains("purpur", ItemCategory.END));
        r.add(pathContains("chorus", ItemCategory.END));
        r.add(pathContains("shulker_shell", ItemCategory.END));
        r.add(pathExact("ender_pearl", ItemCategory.END));
        r.add(pathExact("ender_eye", ItemCategory.END));
        r.add(pathExact("elytra", ItemCategory.END));
        r.add(pathExact("dragon_head", ItemCategory.END));
        r.add(pathExact("dragon_egg", ItemCategory.END));

        // ════════════════════════════════════════════════════════════════
        // MOB_DROP (= モブから得る素材)
        //   ※ end_crystal は END、 spawn_egg は MOB_DROP に振り分ける。
        // ════════════════════════════════════════════════════════════════
        r.add(pathContains("spawn_egg", ItemCategory.MOB_DROP));
        r.add(pathExact("string", ItemCategory.MOB_DROP));
        r.add(pathExact("bone", ItemCategory.MOB_DROP));
        r.add(pathExact("rotten_flesh", ItemCategory.MOB_DROP));
        r.add(pathExact("gunpowder", ItemCategory.MOB_DROP));
        r.add(pathExact("slime_ball", ItemCategory.MOB_DROP));
        r.add(pathExact("phantom_membrane", ItemCategory.MOB_DROP));
        r.add(pathExact("blaze_rod", ItemCategory.MOB_DROP));
        r.add(pathExact("feather", ItemCategory.MOB_DROP));
        r.add(pathExact("leather", ItemCategory.MOB_DROP));
        r.add(pathExact("ink_sac", ItemCategory.MOB_DROP));
        r.add(pathExact("spider_eye", ItemCategory.MOB_DROP));

        // ════════════════════════════════════════════════════════════════
        // WOOD (= 原木 / 板材 / 木製階段・スラブ・柵・扉・トラップドア)
        //   STONE よりも前に置くこと: "_planks" を含む木材を STONE のフォールバックに渡さない。
        // ════════════════════════════════════════════════════════════════
        r.add(tagRule(ItemTags.LOGS, ItemCategory.WOOD));
        r.add(tagRule(ItemTags.PLANKS, ItemCategory.WOOD));
        r.add(tagRule(ItemTags.WOODEN_SLABS, ItemCategory.WOOD));
        r.add(tagRule(ItemTags.WOODEN_STAIRS, ItemCategory.WOOD));
        r.add(tagRule(ItemTags.WOODEN_FENCES, ItemCategory.WOOD));
        r.add(tagRule(ItemTags.WOODEN_DOORS, ItemCategory.WOOD));
        r.add(tagRule(ItemTags.WOODEN_TRAPDOORS, ItemCategory.WOOD));
        r.add(tagRule(ItemTags.WOODEN_BUTTONS, ItemCategory.WOOD));
        r.add(tagRule(ItemTags.WOODEN_PRESSURE_PLATES, ItemCategory.WOOD));
        r.add(pathSuffix("_log", ItemCategory.WOOD));
        r.add(pathSuffix("_wood", ItemCategory.WOOD));
        r.add(pathSuffix("_planks", ItemCategory.WOOD));

        // ════════════════════════════════════════════════════════════════
        // STONE (= 丸石 / 石 / 深層岩 / 安山岩・閃緑岩・花崗岩 / 凝灰岩)
        //   BUILDING との分離: 「素の石系」 = STONE、 「加工された石ブリック / コンクリ / テラコッタ」 = BUILDING。
        // ════════════════════════════════════════════════════════════════
        r.add(pathExact("stone", ItemCategory.STONE));
        r.add(pathExact("cobblestone", ItemCategory.STONE));
        r.add(pathExact("mossy_cobblestone", ItemCategory.STONE));
        r.add(pathExact("smooth_stone", ItemCategory.STONE));
        r.add(pathExact("deepslate", ItemCategory.STONE));
        r.add(pathExact("cobbled_deepslate", ItemCategory.STONE));
        r.add(pathExact("polished_deepslate", ItemCategory.STONE));
        r.add(pathExact("andesite", ItemCategory.STONE));
        r.add(pathExact("diorite", ItemCategory.STONE));
        r.add(pathExact("granite", ItemCategory.STONE));
        r.add(pathExact("polished_andesite", ItemCategory.STONE));
        r.add(pathExact("polished_diorite", ItemCategory.STONE));
        r.add(pathExact("polished_granite", ItemCategory.STONE));
        r.add(pathExact("tuff", ItemCategory.STONE));
        r.add(pathExact("calcite", ItemCategory.STONE));
        r.add(pathExact("dripstone_block", ItemCategory.STONE));

        // ════════════════════════════════════════════════════════════════
        // DECORATION (= 看板 / バナー / 旗 / ベッド / カーペット / 染料 / 額縁 / 松明 / 装飾鉢)
        // ════════════════════════════════════════════════════════════════
        r.add(tagRule(ItemTags.SIGNS, ItemCategory.DECORATION));
        r.add(tagRule(ItemTags.HANGING_SIGNS, ItemCategory.DECORATION));
        r.add(tagRule(ItemTags.BANNERS, ItemCategory.DECORATION));
        r.add(tagRule(ItemTags.CANDLES, ItemCategory.DECORATION));
        r.add(tagRule(ItemTags.BEDS, ItemCategory.DECORATION));
        r.add(tagRule(ItemTags.WOOL_CARPETS, ItemCategory.DECORATION));
        r.add(pathExact("painting", ItemCategory.DECORATION));
        r.add(pathExact("item_frame", ItemCategory.DECORATION));
        r.add(pathExact("glow_item_frame", ItemCategory.DECORATION));
        r.add(pathExact("torch", ItemCategory.DECORATION));
        r.add(pathExact("lantern", ItemCategory.DECORATION));
        r.add(pathExact("soul_lantern", ItemCategory.DECORATION));
        r.add(pathExact("flower_pot", ItemCategory.DECORATION));
        r.add(pathSuffix("_dye", ItemCategory.DECORATION));
        r.add(pathContains("decorated_pot", ItemCategory.DECORATION));
        r.add(pathContains("armor_trim", ItemCategory.DECORATION));

        // ════════════════════════════════════════════════════════════════
        // BUILDING (= 加工石ブロック / コンクリ / ガラス / 羊毛 / テラコッタ / 階段 / 塀 / 砂)
        //   STONE / WOOD の後、 「ブロックアイテム全般のフォールバック」 として最後に近い位置に置く。
        // ════════════════════════════════════════════════════════════════
        r.add(tagRule(ItemTags.STONE_BRICKS, ItemCategory.BUILDING));
        //? if >=26.2 {
        /*r.add(tagRule(oc262$itemTag("slabs"), ItemCategory.BUILDING));
        r.add(tagRule(oc262$itemTag("stairs"), ItemCategory.BUILDING));*/
        //?} else {
        r.add(tagRule(ItemTags.SLABS, ItemCategory.BUILDING));
        r.add(tagRule(ItemTags.STAIRS, ItemCategory.BUILDING));
        //?}
        r.add(tagRule(ItemTags.WALLS, ItemCategory.BUILDING));
        r.add(tagRule(ItemTags.SAND, ItemCategory.BUILDING));
        r.add(tagRule(ItemTags.WOOL, ItemCategory.BUILDING));
        r.add(tagRule(ItemTags.TERRACOTTA, ItemCategory.BUILDING));
        r.add(tagRule(ItemTags.DIRT, ItemCategory.BUILDING));
        r.add(pathSuffix("_concrete", ItemCategory.BUILDING));
        r.add(pathSuffix("_concrete_powder", ItemCategory.BUILDING));
        r.add(pathSuffix("_glass", ItemCategory.BUILDING));
        r.add(pathSuffix("_glass_pane", ItemCategory.BUILDING));
        r.add(pathContains("brick", ItemCategory.BUILDING));
        r.add(pathContains("stone", ItemCategory.BUILDING));
        r.add(pathExact("gravel", ItemCategory.BUILDING));
        // 最後のフォールバック: BlockItem は他に該当が無ければ BUILDING にまとめる。
        r.add(stack -> stack.getItem() instanceof BlockItem
                ? Optional.of(ItemCategory.BUILDING)
                : Optional.empty());

        return Collections.unmodifiableList(r);
    }

    // ────────────────────────────────────────────────────────────────────
    // ファクトリ (= 1 行で 1 ルール)
    //
    // 「巨大な switch を作らない」設計目標を守るため、 本ファイル外でも
    // ユーザー定義ルールを足しやすいよう public にしてある。
    // ────────────────────────────────────────────────────────────────────

    /** Item Tag マッチ。 タグが見つかれば対応カテゴリ。 */
    public static CategoryRule tagRule(TagKey<Item> tag, ItemCategory category) {
        return stack -> stack.is(tag) ? Optional.of(category) : Optional.empty();
    }

    //? if >=26.2 {
    /*// 26.2: バニラの便利定数 ItemTags.FLOWERS/SMALL_FLOWERS/STAIRS/SLABS/BUTTONS は削除されたが、
    //   データタグ (minecraft:flowers 等) は 26.2 jar に現存する。 同じ resource location から
    //   TagKey を再構築すれば一致するアイテム集合は不変 (= 分類挙動を温存)。 26.1.x は定数を
    //   そのまま使う (バイト一致維持) ため、 この helper は 26.2 でのみコンパイルされる。
    static TagKey<Item> oc262$itemTag(String path) {
        return TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                Identifier.withDefaultNamespace(path));
    }*/
    //?}

    /** Item Identifier の path が完全一致するならマッチ (namespace 不問)。 */
    public static CategoryRule pathExact(String exactPath, ItemCategory category) {
        String p = exactPath.toLowerCase(Locale.ROOT);
        return stack -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && id.getPath().equals(p)
                    ? Optional.of(category)
                    : Optional.empty();
        };
    }

    /** Item Identifier path の末尾一致 (例: "_log")。 */
    public static CategoryRule pathSuffix(String suffix, ItemCategory category) {
        String s = suffix.toLowerCase(Locale.ROOT);
        return stack -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && id.getPath().endsWith(s)
                    ? Optional.of(category)
                    : Optional.empty();
        };
    }

    /** Item Identifier path の部分一致 (例: "spawn_egg")。 */
    public static CategoryRule pathContains(String fragment, ItemCategory category) {
        String f = fragment.toLowerCase(Locale.ROOT);
        return stack -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && id.getPath().contains(f)
                    ? Optional.of(category)
                    : Optional.empty();
        };
    }

    /** Data Component が付与されていればマッチ (例: FOOD / POTION_CONTENTS)。 */
    public static CategoryRule componentRule(DataComponentType<?> type, ItemCategory category) {
        return stack -> stack.has(type)
                ? Optional.of(category)
                : Optional.empty();
    }
}
