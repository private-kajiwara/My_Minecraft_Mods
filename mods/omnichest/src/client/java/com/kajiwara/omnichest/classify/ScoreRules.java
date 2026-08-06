package com.kajiwara.omnichest.classify;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * デフォルトの {@link ScoreRule} 群を提供する静的レジストリ。
 *
 * <p>
 * 設計方針:
 * <ul>
 * <li><b>巨大な switch 文を書かない。</b> 1 ルール = 「条件 → 1 つ以上のカテゴリ加点」の組として、
 * リストへ追加していくだけにする。</li>
 * <li>判定根拠は強い順に: <b>Item Tag &gt; Data Component &gt; Identifier path 文字列</b>。
 * Item Tag はバニラ/データパック双方で網羅できるので最優先。
 * Identifier path は MOD アイテムに緩く効かせる「フォールバック」用。</li>
 * <li>「MOD アイテムでもなるべく取りこぼさない」を目的に、 path 文字列ルールは
 * <i>ある程度ゆるい部分一致</i> にしてあるが、ノイズが多い語 (例: "block") は使わない。</li>
 * </ul>
 *
 * <p>
 * カテゴリ追加時の拡張ポイント:
 * <ol>
 * <li>{@link StorageCategory} に enum 値を足す。</li>
 * <li>{@link #buildDefault()} の末尾に新ルールを追加する (条件 → addScore)。</li>
 * <li>必要なら {@link CategoryScorer#withCustomRule} 経由でユーザールールを差し込む
 * (= ハードコード非依存に MOD カテゴリを足す経路)。</li>
 * </ol>
 */
public final class ScoreRules {

    private ScoreRules() {
    }

    // ────────────────────────────────────────────────────────────────────
    // 加点定数 (= 「強い」「中」「弱い」「微」)
    //
    // 単純な数字より、意味を持った定数で扱うほうが拡張時に揺れにくい。
    // ────────────────────────────────────────────────────────────────────
    private static final int STRONG = 10;
    private static final int MEDIUM = 6;
    private static final int WEAK = 3;
    private static final int HINT = 1;

    //? if >=26.2 {
    /*// 26.2: バニラの便利定数 ItemTags.FLOWERS/SMALL_FLOWERS/STAIRS/SLABS/BUTTONS は削除されたが、
    //   データタグ (minecraft:flowers 等) は 26.2 jar に現存する。 同じ resource location から
    //   TagKey を再構築すれば一致するアイテム集合は不変 (= 分類挙動を温存)。 26.1.x は定数を
    //   そのまま使う (バイト一致維持) ため、 この helper は 26.2 でのみコンパイルされる。
    private static TagKey<Item> oc262$itemTag(String path) {
        return TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                Identifier.withDefaultNamespace(path));
    }*/
    //?}

    /**
     * デフォルトルール一式 (immutable list)。
     * 並び順は基本的に独立 (= 互いに干渉しないルール) なので任意。
     */
    public static List<ScoreRule> buildDefault() {
        List<ScoreRule> rules = new ArrayList<>();

        // ════════════════════════════════════════════════════════════════
        // FOOD: 食べ物全般
        //   - 食料判定は「FoodProperties data component を持つ」が最強。
        //     これでバニラ/MOD 双方の食料を網羅できる。
        // ════════════════════════════════════════════════════════════════
        rules.add(componentRule(DataComponents.FOOD, StorageCategory.FOOD, STRONG));
        // 派生: fish / meat / egg
        rules.add(tagRule(ItemTags.FISHES, StorageCategory.FOOD, MEDIUM));
        rules.add(tagRule(ItemTags.MEAT, StorageCategory.FOOD, MEDIUM));
        rules.add(tagRule(ItemTags.EGGS, StorageCategory.FOOD, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // FARM: 種・苗木・植物・骨粉・農具など
        //   食料に振らない「栽培・繁殖サイクル材」を集約する。
        // ════════════════════════════════════════════════════════════════
        rules.add(tagRule(ItemTags.SAPLINGS, StorageCategory.FARM, STRONG));
        rules.add(tagRule(ItemTags.VILLAGER_PLANTABLE_SEEDS, StorageCategory.FARM, STRONG));
        //? if >=26.2 {
        /*rules.add(tagRule(oc262$itemTag("flowers"), StorageCategory.FARM, MEDIUM));
        rules.add(tagRule(oc262$itemTag("small_flowers"), StorageCategory.FARM, MEDIUM));*/
        //?} else {
        rules.add(tagRule(ItemTags.FLOWERS, StorageCategory.FARM, MEDIUM));
        rules.add(tagRule(ItemTags.SMALL_FLOWERS, StorageCategory.FARM, MEDIUM));
        //?}
        rules.add(tagRule(ItemTags.LEAVES, StorageCategory.FARM, WEAK));
        rules.add(pathContainsRule("seed", StorageCategory.FARM, MEDIUM));
        rules.add(pathExactRule("bone_meal", StorageCategory.FARM, STRONG));
        rules.add(pathExactRule("wheat", StorageCategory.FARM, MEDIUM));
        rules.add(pathExactRule("hay_block", StorageCategory.FARM, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // WOOD: 原木・板材・木製パーツ
        // ════════════════════════════════════════════════════════════════
        rules.add(tagRule(ItemTags.LOGS, StorageCategory.WOOD, STRONG));
        rules.add(tagRule(ItemTags.PLANKS, StorageCategory.WOOD, STRONG));
        rules.add(tagRule(ItemTags.WOODEN_SLABS, StorageCategory.WOOD, MEDIUM));
        rules.add(tagRule(ItemTags.WOODEN_STAIRS, StorageCategory.WOOD, MEDIUM));
        rules.add(tagRule(ItemTags.WOODEN_FENCES, StorageCategory.WOOD, MEDIUM));
        rules.add(tagRule(ItemTags.WOODEN_DOORS, StorageCategory.WOOD, MEDIUM));
        rules.add(tagRule(ItemTags.WOODEN_TRAPDOORS, StorageCategory.WOOD, MEDIUM));
        // path フォールバック (MOD の独自木材も拾う)
        rules.add(pathSuffixRule("_log", StorageCategory.WOOD, MEDIUM));
        rules.add(pathSuffixRule("_planks", StorageCategory.WOOD, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // ORE: 鉱石・原石・インゴット・raw block
        //   - Coal / Redstone は別カテゴリ寄りにしたいので、ここでは「弱め」に
        //     ORE 加点だけ与え、より強い加点は専用ルールで上乗せする。
        // ════════════════════════════════════════════════════════════════
        rules.add(tagRule(ItemTags.IRON_ORES, StorageCategory.ORE, STRONG));
        rules.add(tagRule(ItemTags.GOLD_ORES, StorageCategory.ORE, STRONG));
        rules.add(tagRule(ItemTags.DIAMOND_ORES, StorageCategory.ORE, STRONG));
        rules.add(tagRule(ItemTags.EMERALD_ORES, StorageCategory.ORE, STRONG));
        rules.add(tagRule(ItemTags.LAPIS_ORES, StorageCategory.ORE, STRONG));
        rules.add(tagRule(ItemTags.COPPER_ORES, StorageCategory.ORE, STRONG));
        rules.add(tagRule(ItemTags.COAL_ORES, StorageCategory.ORE, MEDIUM));
        rules.add(tagRule(ItemTags.REDSTONE_ORES, StorageCategory.ORE, WEAK)); // REDSTONE 加点との二重ヒット
        rules.add(tagRule(ItemTags.BEACON_PAYMENT_ITEMS, StorageCategory.ORE, MEDIUM));
        // path フォールバック
        rules.add(pathSuffixRule("_ore", StorageCategory.ORE, MEDIUM));
        rules.add(pathSuffixRule("_ingot", StorageCategory.ORE, MEDIUM));
        rules.add(pathContainsRule("raw_", StorageCategory.ORE, MEDIUM));
        rules.add(pathExactRule("coal", StorageCategory.ORE, WEAK));
        rules.add(tagRule(ItemTags.COALS, StorageCategory.ORE, WEAK));

        // ════════════════════════════════════════════════════════════════
        // REDSTONE: 回路パーツ
        // ════════════════════════════════════════════════════════════════
        rules.add(pathExactRule("redstone", StorageCategory.REDSTONE, STRONG));
        rules.add(tagRule(ItemTags.REDSTONE_ORES, StorageCategory.REDSTONE, MEDIUM));
        rules.add(pathContainsRule("redstone", StorageCategory.REDSTONE, MEDIUM));
        rules.add(pathContainsRule("piston", StorageCategory.REDSTONE, STRONG));
        rules.add(pathContainsRule("repeater", StorageCategory.REDSTONE, STRONG));
        rules.add(pathContainsRule("comparator", StorageCategory.REDSTONE, STRONG));
        rules.add(pathContainsRule("observer", StorageCategory.REDSTONE, STRONG));
        rules.add(pathContainsRule("dispenser", StorageCategory.REDSTONE, MEDIUM));
        rules.add(pathContainsRule("dropper", StorageCategory.REDSTONE, MEDIUM));
        rules.add(pathContainsRule("hopper", StorageCategory.REDSTONE, MEDIUM));
        rules.add(pathContainsRule("lever", StorageCategory.REDSTONE, MEDIUM));
        //? if >=26.2 {
        /*rules.add(tagRule(oc262$itemTag("buttons"), StorageCategory.REDSTONE, WEAK));*/
        //?} else {
        rules.add(tagRule(ItemTags.BUTTONS, StorageCategory.REDSTONE, WEAK));
        //?}
        rules.add(tagRule(ItemTags.RAILS, StorageCategory.REDSTONE, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // REDSTONE サブカテゴリ: 傘 REDSTONE の内訳 (回路 / 搬送 / 移動 / トラップ)
        //
        //   加点の型は 2 通りある:
        //   - 既に傘 REDSTONE が付くアイテム (piston / hopper / repeater / rail 等)
        //     → サブ側にだけ加点する (= 上の 13 ルールは一切変えない)。
        //   - 傘が付いていなかったアイテム (target / tnt / tripwire_hook / 感圧板 /
        //     slime_block / honey_block)
        //     → 「傘 + サブ」の両方へ加点する。 傘に入らないとレッドストーン一族として
        //       1 位を取れず、 ChestClassifier のサブ判定 (フェーズ 2) まで到達しないため。
        //
        //   版差メモ: 26.2 で削除された ItemTags.BUTTONS はここでは使わず path 判定にする
        //   (RAILS は 26.2 にも現存するのでタグのままで良い)。
        //   → このセクションに //? 版分岐は 1 つも要らない = 全ノードで同一挙動。
        //
        //   なお「サブに入れないもの」は意図的な据え置き:
        //     string (= MOB_DROP) / lightning_rod (= 未分類) / daylight_detector /
        //     note_block / crafter。
        // ════════════════════════════════════════════════════════════════

        // ── 回路 (信号を作る・伝える・受ける) ──
        rules.add(pathExactRule("redstone", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        rules.add(pathExactRule("redstone_torch", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        rules.add(pathExactRule("redstone_block", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        rules.add(pathExactRule("redstone_lamp", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        rules.add(pathContainsRule("repeater", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        rules.add(pathContainsRule("comparator", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        rules.add(pathContainsRule("observer", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        rules.add(pathContainsRule("lever", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        // ボタンは BUTTONS タグ (26.2 で削除) を避けて path 末尾一致で拾う。
        rules.add(pathSuffixRule("_button", StorageCategory.REDSTONE_CIRCUIT, STRONG));
        // target は今まで無分類 → 傘 + サブの両方へ。
        rules.add(pathExactRule("target", StorageCategory.REDSTONE, STRONG));
        rules.add(pathExactRule("target", StorageCategory.REDSTONE_CIRCUIT, STRONG));

        // ── 搬送 (アイテムを運ぶ) ──
        rules.add(pathContainsRule("hopper", StorageCategory.REDSTONE_TRANSPORT, STRONG));
        rules.add(pathContainsRule("dropper", StorageCategory.REDSTONE_TRANSPORT, STRONG));
        rules.add(pathContainsRule("dispenser", StorageCategory.REDSTONE_TRANSPORT, STRONG));
        rules.add(tagRule(ItemTags.RAILS, StorageCategory.REDSTONE_TRANSPORT, STRONG));

        // ── 移動 (ブロックを動かす) ──
        rules.add(pathContainsRule("piston", StorageCategory.REDSTONE_MOVEMENT, STRONG));
        // slime_block / honey_block は今まで無分類 → 傘 + サブの両方へ。
        rules.add(pathExactRule("slime_block", StorageCategory.REDSTONE, STRONG));
        rules.add(pathExactRule("slime_block", StorageCategory.REDSTONE_MOVEMENT, STRONG));
        rules.add(pathExactRule("honey_block", StorageCategory.REDSTONE, STRONG));
        rules.add(pathExactRule("honey_block", StorageCategory.REDSTONE_MOVEMENT, STRONG));

        // ── トラップ (検知・起爆) ──
        //   感圧板は木/石/黒石/重み付きを path 末尾一致でまとめて拾う。
        //   石・磨いた黒石の感圧板は今まで BUILDING / NETHER 側に寄っていたが、
        //   ここで意図的に TRAP へ寄せる (= 合意済みの flip)。
        rules.add(pathSuffixRule("_pressure_plate", StorageCategory.REDSTONE, STRONG));
        rules.add(pathSuffixRule("_pressure_plate", StorageCategory.REDSTONE_TRAP, STRONG));
        rules.add(pathExactRule("tripwire_hook", StorageCategory.REDSTONE, STRONG));
        rules.add(pathExactRule("tripwire_hook", StorageCategory.REDSTONE_TRAP, STRONG));
        rules.add(pathExactRule("tnt", StorageCategory.REDSTONE, STRONG));
        rules.add(pathExactRule("tnt", StorageCategory.REDSTONE_TRAP, STRONG));

        // ════════════════════════════════════════════════════════════════
        // BUILDING: 建築ブロック (石系/コンクリ/ガラス/羊毛/階段/塀)
        // ════════════════════════════════════════════════════════════════
        rules.add(tagRule(ItemTags.STONE_BRICKS, StorageCategory.BUILDING, STRONG));
        //? if >=26.2 {
        /*rules.add(tagRule(oc262$itemTag("slabs"), StorageCategory.BUILDING, MEDIUM));
        rules.add(tagRule(oc262$itemTag("stairs"), StorageCategory.BUILDING, MEDIUM));*/
        //?} else {
        rules.add(tagRule(ItemTags.SLABS, StorageCategory.BUILDING, MEDIUM));
        rules.add(tagRule(ItemTags.STAIRS, StorageCategory.BUILDING, MEDIUM));
        //?}
        rules.add(tagRule(ItemTags.WALLS, StorageCategory.BUILDING, MEDIUM));
        rules.add(tagRule(ItemTags.SAND, StorageCategory.BUILDING, MEDIUM));
        rules.add(tagRule(ItemTags.WOOL, StorageCategory.BUILDING, MEDIUM));
        rules.add(tagRule(ItemTags.TERRACOTTA, StorageCategory.BUILDING, MEDIUM));
        rules.add(tagRule(ItemTags.DIRT, StorageCategory.BUILDING, WEAK));
        rules.add(pathContainsRule("cobblestone", StorageCategory.BUILDING, MEDIUM));
        rules.add(pathSuffixRule("_concrete", StorageCategory.BUILDING, MEDIUM));
        rules.add(pathSuffixRule("_concrete_powder", StorageCategory.BUILDING, MEDIUM));
        rules.add(pathSuffixRule("_glass", StorageCategory.BUILDING, MEDIUM));
        rules.add(pathSuffixRule("_glass_pane", StorageCategory.BUILDING, MEDIUM));
        rules.add(pathContainsRule("stone", StorageCategory.BUILDING, HINT));
        rules.add(pathContainsRule("brick", StorageCategory.BUILDING, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // BUILDING サブカテゴリ: 傘 BUILDING の内訳を「石材の材質軸」で分ける
        //
        //   加点は全て MEDIUM (= 傘 BUILDING の最頻値と同じ 6 点)。 STRONG にしない理由:
        //   フェーズ 2 の PRESENCE は「サブ合計 / 傘スコア」なので、 サブ加点が傘より大きいと
        //   石材が 1/4 しか入っていない箱でも比が 0.5 を超えてしまう。 6 に揃えると
        //   PRESENCE が素直に「箱に占める石材の割合」を表す (実測: 石材 25% → 0.32 で傘のまま)。
        //
        //   判定は全て Identifier path のみ。 石材の「材質軸」を束ねるバニラ item タグは
        //   存在せず (あるのは形状軸の slabs/stairs/walls と stone_bricks 4 件のみ)、
        //   しかもそれらは 26.2 で定数が消える。 path 判定なら版差ゼロなので、
        //   → このセクションに //? 版分岐は 1 つも要らない = 全ノードで同一挙動。
        //   材質トークンは階段/ハーフ/塀も同時に拾える (例: "granite" だけで 7 件全部)。
        //
        //   なお「サブに入れないもの」は合意済みの意図的な据え置き:
        //     blackstone / basalt (= NETHER) / quartz (= NETHER) /
        //     end_stone / purpur (= END)。 これらは次元素材としての識別を優先する。
        // ════════════════════════════════════════════════════════════════

        // ── 石・丸石・石レンガ ──
        //   "stone" は redstone / sandstone / blackstone / end_stone / glowstone /
        //   lodestone / grindstone / dripstone / stonecutter / 石ツール まで拾ってしまう
        //   ノイズの多い語なので、 除外リスト付きで使う。 除外後は 29 件ちょうどで、
        //   全て現行でも BUILDING が 1 位のもの (= 他カテゴリからの奪取ゼロ)。
        //   stone_button / stone_pressure_plate も除外し、 レッドストーン一族と独立させる。
        rules.add(pathContainsExceptRule("stone",
                List.of("redstone", "sandstone", "blackstone", "end_stone", "glowstone",
                        "lodestone", "grindstone", "dripstone", "stonecutter",
                        "stone_axe", "stone_hoe", "stone_sword", "stone_shovel",
                        "stone_pickaxe", "stone_spear", "stone_button", "stone_pressure_plate"),
                StorageCategory.BUILDING_STONE, MEDIUM));

        // ── 花崗岩 / 閃緑岩 / 安山岩 (衝突ゼロの安全トークン) ──
        rules.add(pathContainsRule("granite", StorageCategory.BUILDING_GRANITE, MEDIUM));
        rules.add(pathContainsRule("diorite", StorageCategory.BUILDING_DIORITE, MEDIUM));
        rules.add(pathContainsRule("andesite", StorageCategory.BUILDING_ANDESITE, MEDIUM));

        // ── 深層岩 (deepslate_*_ore 8 件は ORE / REDSTONE なので除外) ──
        rules.add(pathContainsExceptRule("deepslate", List.of("_ore"),
                StorageCategory.BUILDING_DEEPSLATE, MEDIUM));

        // ── 凝灰岩 + 方解石 (方解石は 1 件しかないので同居させる) ──
        rules.add(pathContainsRule("tuff", StorageCategory.BUILDING_TUFF, MEDIUM));
        rules.add(pathContainsRule("calcite", StorageCategory.BUILDING_TUFF, MEDIUM));

        // ── 砂岩 (red_sandstone も同じトークンで拾える。 衝突ゼロ) ──
        rules.add(pathContainsRule("sandstone", StorageCategory.BUILDING_SANDSTONE, MEDIUM));

        // ── プリズマリン (shard / crystals は建材ではないので除外) ──
        rules.add(pathContainsExceptRule("prismarine",
                List.of("prismarine_shard", "prismarine_crystals"),
                StorageCategory.BUILDING_PRISMARINE, MEDIUM));

        // ── 泥レンガ ──
        rules.add(pathContainsRule("mud_brick", StorageCategory.BUILDING_MUD_BRICK, MEDIUM));

        // ── 傘 BUILDING への補完 (= サブ加点だけではフェーズ 2 に届かない素ブロック) ──
        //   下の 20 件は現行ルールで BUILDING が 0 点 (= 無分類) だった。 階段/ハーフ/塀だけが
        //   形状タグで拾われ、 素ブロックと磨かれた形は id に "stone" を含まないため
        //   HINT すら当たっていなかった。 傘に入らないと一族として 1 位を取れず、
        //   フェーズ 2 のサブ判定まで到達しないので、 傘 + サブの両方へ加点する。
        //
        //   ここを pathContains で一括にしないのは意図的:
        //   トークン加点にすると granite_slab 等の <b>既存</b> BUILDING 点が 6 → 12 に増え、
        //   フェーズ 1 の他カテゴリとの勝敗が動いてしまう。 pathExact で 0 点の item だけを
        //   個別に持ち上げれば、 それ以外の item のスコアはビット単位で不変になる。
        for (String bare : List.of(
                "granite", "polished_granite",
                "diorite", "polished_diorite",
                "andesite", "polished_andesite",
                "deepslate", "cobbled_deepslate", "polished_deepslate", "chiseled_deepslate",
                "deepslate_tiles", "cracked_deepslate_tiles", "infested_deepslate",
                "reinforced_deepslate",
                "tuff", "polished_tuff", "chiseled_tuff", "calcite",
                "prismarine", "dark_prismarine")) {
            rules.add(pathExactRule(bare, StorageCategory.BUILDING, MEDIUM));
        }

        // ════════════════════════════════════════════════════════════════
        // COMBAT: 武器・防具・矢・トライデント・盾
        // ════════════════════════════════════════════════════════════════
        rules.add(tagRule(ItemTags.SWORDS, StorageCategory.COMBAT, STRONG));
        rules.add(tagRule(ItemTags.ARROWS, StorageCategory.COMBAT, STRONG));
        rules.add(tagRule(ItemTags.HEAD_ARMOR, StorageCategory.COMBAT, STRONG));
        rules.add(tagRule(ItemTags.CHEST_ARMOR, StorageCategory.COMBAT, STRONG));
        rules.add(tagRule(ItemTags.LEG_ARMOR, StorageCategory.COMBAT, STRONG));
        rules.add(tagRule(ItemTags.FOOT_ARMOR, StorageCategory.COMBAT, STRONG));
        rules.add(tagRule(ItemTags.TRIMMABLE_ARMOR, StorageCategory.COMBAT, MEDIUM));
        rules.add(pathExactRule("bow", StorageCategory.COMBAT, STRONG));
        rules.add(pathExactRule("crossbow", StorageCategory.COMBAT, STRONG));
        rules.add(pathExactRule("shield", StorageCategory.COMBAT, STRONG));
        rules.add(pathExactRule("trident", StorageCategory.COMBAT, STRONG));
        rules.add(pathExactRule("totem_of_undying", StorageCategory.COMBAT, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // TOOL: ピッケル・斧・シャベル・クワ・はさみ・釣り竿など
        // ════════════════════════════════════════════════════════════════
        rules.add(tagRule(ItemTags.PICKAXES, StorageCategory.TOOL, STRONG));
        rules.add(tagRule(ItemTags.AXES, StorageCategory.TOOL, STRONG));
        rules.add(tagRule(ItemTags.SHOVELS, StorageCategory.TOOL, STRONG));
        rules.add(tagRule(ItemTags.HOES, StorageCategory.TOOL, STRONG));
        rules.add(pathExactRule("shears", StorageCategory.TOOL, STRONG));
        rules.add(pathExactRule("fishing_rod", StorageCategory.TOOL, STRONG));
        rules.add(pathExactRule("flint_and_steel", StorageCategory.TOOL, MEDIUM));
        rules.add(pathExactRule("compass", StorageCategory.TOOL, MEDIUM));
        rules.add(pathExactRule("clock", StorageCategory.TOOL, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // POTION: ポーション・醸造素材
        //   PotionContents data component が付いている = 確定ポーション系。
        //   path 一致は brewing stand / blaze powder などの素材も拾う。
        // ════════════════════════════════════════════════════════════════
        rules.add(componentRule(DataComponents.POTION_CONTENTS, StorageCategory.POTION, STRONG + STRONG));
        rules.add(pathContainsRule("potion", StorageCategory.POTION, STRONG));
        rules.add(pathExactRule("glass_bottle", StorageCategory.POTION, MEDIUM));
        rules.add(pathExactRule("brewing_stand", StorageCategory.POTION, MEDIUM));
        rules.add(pathExactRule("blaze_powder", StorageCategory.POTION, MEDIUM));
        rules.add(pathExactRule("nether_wart", StorageCategory.POTION, MEDIUM));
        rules.add(pathExactRule("ghast_tear", StorageCategory.POTION, MEDIUM));
        rules.add(pathExactRule("dragon_breath", StorageCategory.POTION, MEDIUM));
        rules.add(pathExactRule("spider_eye", StorageCategory.POTION, WEAK));
        rules.add(pathExactRule("fermented_spider_eye", StorageCategory.POTION, MEDIUM));
        rules.add(pathExactRule("magma_cream", StorageCategory.POTION, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // NETHER: ネザー由来素材
        // ════════════════════════════════════════════════════════════════
        rules.add(tagRule(ItemTags.CRIMSON_STEMS, StorageCategory.NETHER, STRONG));
        rules.add(tagRule(ItemTags.WARPED_STEMS, StorageCategory.NETHER, STRONG));
        rules.add(pathContainsRule("netherite", StorageCategory.NETHER, STRONG));
        rules.add(pathContainsRule("netherrack", StorageCategory.NETHER, STRONG));
        rules.add(pathContainsRule("nether_brick", StorageCategory.NETHER, STRONG));
        rules.add(pathContainsRule("blackstone", StorageCategory.NETHER, MEDIUM));
        rules.add(pathContainsRule("basalt", StorageCategory.NETHER, MEDIUM));
        rules.add(pathContainsRule("soul_", StorageCategory.NETHER, MEDIUM));
        rules.add(pathContainsRule("quartz", StorageCategory.NETHER, MEDIUM));
        rules.add(pathContainsRule("crimson", StorageCategory.NETHER, MEDIUM));
        rules.add(pathContainsRule("warped", StorageCategory.NETHER, MEDIUM));
        rules.add(pathContainsRule("glowstone", StorageCategory.NETHER, MEDIUM));
        rules.add(pathContainsRule("magma_block", StorageCategory.NETHER, MEDIUM));

        // ════════════════════════════════════════════════════════════════
        // END: エンド素材
        // ════════════════════════════════════════════════════════════════
        rules.add(pathContainsRule("end_stone", StorageCategory.END, STRONG));
        rules.add(pathContainsRule("purpur", StorageCategory.END, STRONG));
        rules.add(pathContainsRule("chorus", StorageCategory.END, STRONG));
        rules.add(pathContainsRule("shulker_shell", StorageCategory.END, STRONG));
        rules.add(pathExactRule("ender_pearl", StorageCategory.END, STRONG));
        rules.add(pathExactRule("ender_eye", StorageCategory.END, STRONG));
        rules.add(pathExactRule("elytra", StorageCategory.END, STRONG));
        rules.add(pathExactRule("dragon_head", StorageCategory.END, STRONG));
        rules.add(pathExactRule("dragon_egg", StorageCategory.END, STRONG));

        // ════════════════════════════════════════════════════════════════
        // MAGIC: 経験値・エンチャント本・グロウインク
        // ════════════════════════════════════════════════════════════════
        rules.add(pathExactRule("experience_bottle", StorageCategory.MAGIC, STRONG));
        rules.add(pathExactRule("enchanted_book", StorageCategory.MAGIC, STRONG));
        rules.add(pathExactRule("lapis_lazuli", StorageCategory.MAGIC, MEDIUM));
        rules.add(pathExactRule("amethyst_shard", StorageCategory.MAGIC, MEDIUM));
        rules.add(pathExactRule("amethyst_block", StorageCategory.MAGIC, MEDIUM));
        rules.add(pathExactRule("glow_ink_sac", StorageCategory.MAGIC, MEDIUM));
        rules.add(pathExactRule("nether_star", StorageCategory.MAGIC, STRONG));
        rules.add(pathExactRule("heart_of_the_sea", StorageCategory.MAGIC, STRONG));
        rules.add(pathExactRule("echo_shard", StorageCategory.MAGIC, STRONG));

        // ════════════════════════════════════════════════════════════════
        // MOB_DROP: モブから得るドロップ素材
        // ════════════════════════════════════════════════════════════════
        rules.add(pathExactRule("string", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathExactRule("bone", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathExactRule("rotten_flesh", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathExactRule("gunpowder", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathExactRule("slime_ball", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathExactRule("ender_pearl", StorageCategory.MOB_DROP, WEAK));
        rules.add(pathExactRule("phantom_membrane", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathExactRule("blaze_rod", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathExactRule("feather", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathExactRule("leather", StorageCategory.MOB_DROP, MEDIUM));
        rules.add(pathContainsRule("spawn_egg", StorageCategory.MOB_DROP, STRONG));

        // ════════════════════════════════════════════════════════════════
        // DECORATION: 装飾用 (絵画・額縁・看板・松明・カーペット・ベッド・バナー・花瓶)
        // ════════════════════════════════════════════════════════════════
        rules.add(tagRule(ItemTags.SIGNS, StorageCategory.DECORATION, MEDIUM));
        rules.add(tagRule(ItemTags.HANGING_SIGNS, StorageCategory.DECORATION, MEDIUM));
        rules.add(tagRule(ItemTags.BANNERS, StorageCategory.DECORATION, MEDIUM));
        rules.add(tagRule(ItemTags.CANDLES, StorageCategory.DECORATION, MEDIUM));
        rules.add(tagRule(ItemTags.BEDS, StorageCategory.DECORATION, MEDIUM));
        rules.add(tagRule(ItemTags.WOOL_CARPETS, StorageCategory.DECORATION, MEDIUM));
        rules.add(pathExactRule("painting", StorageCategory.DECORATION, STRONG));
        rules.add(pathExactRule("item_frame", StorageCategory.DECORATION, STRONG));
        rules.add(pathExactRule("glow_item_frame", StorageCategory.DECORATION, STRONG));
        rules.add(pathExactRule("torch", StorageCategory.DECORATION, WEAK));
        rules.add(pathExactRule("lantern", StorageCategory.DECORATION, MEDIUM));
        rules.add(pathExactRule("soul_lantern", StorageCategory.DECORATION, MEDIUM));
        rules.add(pathExactRule("flower_pot", StorageCategory.DECORATION, MEDIUM));
        rules.add(pathSuffixRule("_dye", StorageCategory.DECORATION, MEDIUM));
        rules.add(pathContainsRule("decorated_pot", StorageCategory.DECORATION, MEDIUM));

        return Collections.unmodifiableList(rules);
    }

    // ════════════════════════════════════════════════════════════════════
    // ヘルパーファクトリ
    //
    //   コードのほとんどはこの 3 種類のファクトリで構築されており、
    //   「新しいルール」 = 「ファクトリ呼び出しを 1 行足す」だけで増やせる。
    // ════════════════════════════════════════════════════════════════════

    /** Item Tag が一致したら指定スコアを加点するルール。 */
    public static ScoreRule tagRule(TagKey<Item> tag, StorageCategory category, int score) {
        return (stack, sink) -> {
            if (stack.is(tag)) {
                sink.add(category, score);
            }
        };
    }

    /** Item の Identifier path が完全一致するなら加点。 namespace は問わない。 */
    public static ScoreRule pathExactRule(String exactPath, StorageCategory category, int score) {
        String p = exactPath.toLowerCase(Locale.ROOT);
        return (stack, sink) -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null)
                return;
            if (id.getPath().equals(p)) {
                sink.add(category, score);
            }
        };
    }

    /** Item の Identifier path に部分一致するなら加点 (大文字小文字無視)。 */
    public static ScoreRule pathContainsRule(String fragment, StorageCategory category, int score) {
        String f = fragment.toLowerCase(Locale.ROOT);
        return (stack, sink) -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null)
                return;
            if (id.getPath().contains(f)) {
                sink.add(category, score);
            }
        };
    }

    /**
     * Item の Identifier path に部分一致し、 かつ除外語をどれも含まないなら加点。
     *
     * <p>
     * ノイズの多い語 (例: "stone" は redstone / sandstone / blackstone まで拾う) を
     * それでも使いたいときの限定版。 {@link #pathContainsRule} と同じく path 判定だけなので
     * バニラのタグ構成に依存せず、 版差 (26.2 のタグ定数削除) の影響を受けない。
     */
    public static ScoreRule pathContainsExceptRule(String fragment, List<String> excluded,
            StorageCategory category, int score) {
        String f = fragment.toLowerCase(Locale.ROOT);
        List<String> ex = excluded.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return (stack, sink) -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null)
                return;
            String path = id.getPath();
            if (!path.contains(f))
                return;
            for (String e : ex) {
                if (path.contains(e))
                    return;
            }
            sink.add(category, score);
        };
    }

    /** Item の Identifier path が末尾一致するなら加点。 (e.g. "_log" → oak_log / birch_log) */
    public static ScoreRule pathSuffixRule(String suffix, StorageCategory category, int score) {
        String s = suffix.toLowerCase(Locale.ROOT);
        return (stack, sink) -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null)
                return;
            if (id.getPath().endsWith(s)) {
                sink.add(category, score);
            }
        };
    }

    /** ItemStack に指定 DataComponentType がセットされていれば加点。 */
    public static ScoreRule componentRule(
            net.minecraft.core.component.DataComponentType<?> type,
            StorageCategory category,
            int score) {
        return (stack, sink) -> {
            if (stack.has(type)) {
                sink.add(category, score);
            }
        };
    }
}
