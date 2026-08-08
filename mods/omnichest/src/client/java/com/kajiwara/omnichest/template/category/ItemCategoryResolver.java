package com.kajiwara.omnichest.template.category;

import com.kajiwara.omnichest.classify.CategoryScore;
import com.kajiwara.omnichest.classify.CategoryScorer;
import com.kajiwara.omnichest.classify.StorageCategory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 「ItemStack → 単一の StorageCategory」を導出するヘルパ。
 *
 * <p>
 * テンプレートは「このスロットは FOOD」のように <b>単一カテゴリ</b> を持つので、
 * {@link CategoryScorer} が返す多軸スコアから「ベストカテゴリ 1 つ」を選び出す必要がある。
 * 本クラスはそのアダプタ層。
 *
 * <p>
 * 同時に、 1 つの Minecraft セッション内では「同じアイテム = 同じカテゴリ」が保たれるはずなので、
 * カテゴリ解決結果を WeakHashMap ではなく軽い HashMap でキャッシュする
 * (1.21 ではアイテム再登録は起きない前提)。
 */
public final class ItemCategoryResolver {

    /** ItemStack の identity を生かして使い回す 1 プロセス共有キャッシュ。 */
    private static final Map<String, StorageCategory> CACHE = new HashMap<>();

    /** カテゴリ枠の候補から外す「倉庫バッジ専用」の下位区分 (レッドストーン一族 + 石材一族)。 */
    private static final java.util.EnumSet<StorageCategory> BADGE_ONLY_SUBS = java.util.EnumSet.of(
            StorageCategory.REDSTONE_CIRCUIT,
            StorageCategory.REDSTONE_TRANSPORT,
            StorageCategory.REDSTONE_MOVEMENT,
            StorageCategory.REDSTONE_TRAP,
            StorageCategory.BUILDING_STONE,
            StorageCategory.BUILDING_GRANITE,
            StorageCategory.BUILDING_DIORITE,
            StorageCategory.BUILDING_ANDESITE,
            StorageCategory.BUILDING_DEEPSLATE,
            StorageCategory.BUILDING_TUFF,
            StorageCategory.BUILDING_SANDSTONE,
            StorageCategory.BUILDING_PRISMARINE,
            StorageCategory.BUILDING_MUD_BRICK);

    private ItemCategoryResolver() {
    }

    /**
     * 単一スタックを 1 カテゴリに「寄せて」返す。
     *
     * <p>
     * 判定方針:
     * <ol>
     * <li>{@link CategoryScorer#DEFAULT} で全カテゴリのスコアを取る。</li>
     * <li>最大スコアを取るカテゴリが 1 つに定まればそれを返す。</li>
     * <li>合計スコア 0 (= ルール上どのカテゴリにも属さない) なら null を返す。</li>
     * <li>同点 (= 拮抗) のときは null を返す (= 「カテゴリ枠」では受け入れない判定にする)。</li>
     * </ol>
     *
     * <p>
     * MIXED / UNKNOWN は意図的に返さない: テンプレートのカテゴリ枠は「Concrete category」
     * (= isConcrete) に絞り、 fallback は呼び出し側が決められるようにする。
     */
    @Nullable
    public static StorageCategory resolveBest(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return null;

        String cacheKey = cacheKeyOf(stack);
        StorageCategory cached = CACHE.get(cacheKey);
        if (cached != null)
            return cached == StorageCategory.UNKNOWN ? null : cached;

        CategoryScore score = CategoryScorer.DEFAULT.scoreOf(stack);
        StorageCategory best = pickSingleBest(score);
        // null をキャッシュに入れたいが Map に直接 null は入れにくいので UNKNOWN を sentinel として使う。
        CACHE.put(cacheKey, best == null ? StorageCategory.UNKNOWN : best);
        return best;
    }

    private static String cacheKeyOf(ItemStack stack) {
        // Data Components の違いは category 解釈に関係しない (ポーション種別を除く) ので、
        // item registry id だけでキャッシュする。
        // 例外: ポーション種別の差異は STRONG な category 影響を持つので、いずれ拡張が必要。
        return stack.getItem().toString();
    }

    @Nullable
    private static StorageCategory pickSingleBest(CategoryScore score) {
        if (score.total() <= 0)
            return null;
        StorageCategory best = null;
        int bestScore = 0;
        int tie = 0;
        for (StorageCategory cat : StorageCategory.values()) {
            if (!cat.isConcrete())
                continue;
            // レッドストーンのサブ (回路/搬送/移動/トラップ) と石材のサブ (材質軸 9 種) は
            // 「倉庫バッジの内訳」専用で、テンプレートのカテゴリ枠は従来どおり
            // 傘 REDSTONE / 傘 BUILDING の粒度で扱う。
            // ここを外さないと piston / repeater / granite 等が「傘とサブが同点」で拮抗 → null になり、
            // 保存済みテンプレの REDSTONE / BUILDING 枠とも一致しなくなる。
            // (BUILDING_STONE_MIXED は isConcrete() が false なので上の判定で既に落ちている。)
            if (BADGE_ONLY_SUBS.contains(cat))
                continue;
            int v = score.get(cat);
            if (v <= 0)
                continue;
            if (v > bestScore) {
                best = cat;
                bestScore = v;
                tie = 1;
            } else if (v == bestScore) {
                tie++;
            }
        }
        if (tie > 1)
            return null; // 拮抗 → 不確定
        return best;
    }

    /**
     * 「アイテム A とアイテム B が同じカテゴリに属するか」。
     * 両方 resolve できて、かつ同じカテゴリのときのみ true。
     */
    public static boolean shareCategory(ItemStack a, ItemStack b) {
        StorageCategory ca = resolveBest(a);
        if (ca == null) return false;
        StorageCategory cb = resolveBest(b);
        return ca == cb;
    }

    /** テスト / リセット用 (内部キャッシュをクリア)。 */
    public static void clearCacheForTest() {
        CACHE.clear();
    }
}
