package com.kajiwara.omnichest.classify;

import com.kajiwara.omnichest.search.ContainerSnapshot;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 「コンテナの中身 (ItemStack 列) → {@link Classification}」を計算する分類エンジン。
 *
 * <p>
 * アルゴリズム:
 * <ol>
 * <li>各 ItemStack について {@link CategoryScorer} でカテゴリ別スコアを算出。</li>
 * <li>そのスタックの個数 (count) を重みとして掛けて、コンテナ全体のスコアに合算する。
 * → 「個数が多いカテゴリほど倉庫の用途を表す」という直感に合う。</li>
 * <li>合算スコアの 1 位カテゴリを取り、占有率 = top / total を confidence とする。
 * <ul>
 * <li>占有率が {@link #LOW_CONFIDENCE_THRESHOLD} 未満なら {@link StorageCategory#MIXED}。</li>
 * <li>2 位との差が {@link #MIXED_GAP_THRESHOLD} 未満なら MIXED 扱い。</li>
 * <li>スコアが全く付かなかった (中身がない / 全アイテムが未知 MOD アイテム) なら
 * {@link StorageCategory#UNKNOWN}。</li>
 * </ul>
 * </li>
 * <li>1 位が傘カテゴリだったときだけ、 その内訳を見てサブカテゴリへ差し替える。
 * 対象は 2 一族:
 * {@link StorageCategory#REDSTONE} (回路 / 搬送 / 移動 / トラップ) と
 * {@link StorageCategory#BUILDING} (石材の材質軸 9 種 + 中間層)。
 * 上記 1〜3 の競争にサブカテゴリは参加しないので、 この 2 つ以外の結果は不変。
 * 2 一族は互いのサブを参照しないので、 片方を変えてももう片方には影響しない。</li>
 * </ol>
 *
 * <p>
 * 注意: 個数重み付けは log(1+count) / count の二択がある。
 * いま「個数 64 一杯のチェスト」と「個数 1 が 64 種類入ったチェスト」を区別したいので、
 * 個数を素のまま掛ける (≒ count) 設計にしている。
 * ただし上限を {@link #PER_STACK_COUNT_CAP} で抑え、シュルカー類 (中身全 64) で
 * 暴れすぎないように調整している。
 */
public final class ChestClassifier {

    /** 1 位カテゴリの占有率がこの値未満なら MIXED 扱い (= 「拮抗してる」)。 */
    public static final float LOW_CONFIDENCE_THRESHOLD = 0.45f;

    /** 1 位 - 2 位 の差がこの比率未満なら MIXED 扱い (= 「2 位が近すぎる」)。 */
    public static final float MIXED_GAP_THRESHOLD = 0.10f;

    /** 1 スタック分の重みは最大ここまで (= 大量同種が偏り過ぎないようにキャップ)。 */
    public static final int PER_STACK_COUNT_CAP = 16;

    // ────────────────────────────────────────────────────────────────────
    // レッドストーン一族のロールアップ (= 傘 REDSTONE → サブカテゴリ)
    //
    //   判定は 2 フェーズ:
    //     フェーズ 1 (競争)   : 全サブ (両一族) を集計から除外して従来どおり 1 位 / MIXED を決める。
    //                          → 勝者が REDSTONE 以外なら、 結果は従来と完全に一致する。
    //     フェーズ 2 (内訳)   : 勝者が REDSTONE のときだけ、 サブの内訳を見る。
    //                          下の 2 つのゲートを両方通ればサブ名で表示し、
    //                          どちらか欠ければ傘 REDSTONE のまま表示する。
    //
    //   しきい値はここ 1 箇所。 表示が細かすぎ/粗すぎのときはこの 2 つだけを動かす。
    // ────────────────────────────────────────────────────────────────────

    /** サブ合計 / 傘 REDSTONE スコア がこの値未満なら「サブの手掛かりが薄い」= 傘のまま。 */
    public static final float REDSTONE_SUB_PRESENCE = 0.50f;

    /** 最大サブ / サブ合計 がこの値未満なら「サブ同士が拮抗」= 傘のまま。 */
    public static final float REDSTONE_SUB_DOMINANCE = 0.60f;

    /** 傘 REDSTONE の下位区分。 ロールアップ以外の経路には影響させない。 */
    private static final EnumSet<StorageCategory> REDSTONE_SUBS = EnumSet.of(
            StorageCategory.REDSTONE_CIRCUIT,
            StorageCategory.REDSTONE_TRANSPORT,
            StorageCategory.REDSTONE_MOVEMENT,
            StorageCategory.REDSTONE_TRAP);

    // ────────────────────────────────────────────────────────────────────
    // 建築ブロック一族のロールアップ (= 傘 BUILDING → 石材サブ)
    //
    //   レッドストーン一族と <b>完全に独立</b> した 2 フェーズ判定。 フェーズ 1 の勝者が
    //   BUILDING のときだけ石材の内訳を見る (REDSTONE のときは従来どおり
    //   rollUpRedstone しか動かない)。 どちらの一族も相手のサブを一切参照しない。
    //
    //   レッドストーンとの違いは 1 点だけ: dominance を割ったときに傘へ戻さず、
    //   中間層 BUILDING_STONE_MIXED (= 石材ではあるが材質が 1 つに寄っていない) を返す。
    //   これが無いと「花崗岩+閃緑岩+安山岩の箱」が羊毛やコンクリートの箱と同じ
    //   傘 BUILDING になって区別できない。
    //
    //   しきい値はここ 1 箇所。 表示が細かすぎ/粗すぎのときはこの 2 つだけを動かす。
    // ────────────────────────────────────────────────────────────────────

    /** サブ合計 / 傘 BUILDING スコア がこの値未満なら「石材の手掛かりが薄い」= 傘のまま。 */
    public static final float BUILDING_SUB_PRESENCE = 0.50f;

    /** 最大サブ / サブ合計 がこの値未満なら「材質同士が拮抗」= 石材混合。 */
    public static final float BUILDING_SUB_DOMINANCE = 0.60f;

    /**
     * 傘 BUILDING の下位区分 (= 石材の材質軸)。
     *
     * <p>
     * {@link StorageCategory#BUILDING_STONE_MIXED} はここに入れない。 あれはスコアを
     * 受け取らず、 dominance を割ったときの <i>戻り値</i> としてのみ現れる中間層だから。
     */
    private static final EnumSet<StorageCategory> STONE_SUBS = EnumSet.of(
            StorageCategory.BUILDING_STONE,
            StorageCategory.BUILDING_GRANITE,
            StorageCategory.BUILDING_DIORITE,
            StorageCategory.BUILDING_ANDESITE,
            StorageCategory.BUILDING_DEEPSLATE,
            StorageCategory.BUILDING_TUFF,
            StorageCategory.BUILDING_SANDSTONE,
            StorageCategory.BUILDING_PRISMARINE,
            StorageCategory.BUILDING_MUD_BRICK);

    /**
     * フェーズ 1 (競争) から外す全サブカテゴリ = 両一族の合併。
     * これらは 1 位候補にも占有率の分母にも 2 位にも入らないので、
     * サブを足してもフェーズ 1 の勝敗は従来と完全に一致する。
     */
    private static final EnumSet<StorageCategory> ALL_SUBS = allSubs();

    private static EnumSet<StorageCategory> allSubs() {
        EnumSet<StorageCategory> set = EnumSet.copyOf(REDSTONE_SUBS);
        set.addAll(STONE_SUBS);
        return set;
    }

    private final CategoryScorer scorer;

    public ChestClassifier(CategoryScorer scorer) {
        this.scorer = scorer == null ? CategoryScorer.DEFAULT : scorer;
    }

    /** デフォルトの Scorer を使うショートカット。 */
    public ChestClassifier() {
        this(CategoryScorer.DEFAULT);
    }

    /**
     * スナップショットを丸ごと評価して {@link Classification} を返す。
     */
    public Classification classify(ContainerSnapshot snapshot) {
        if (snapshot == null) {
            return emptyResult();
        }
        return classify(snapshot.items(), snapshot.lastSeenMillis());
    }

    /** 直接 ItemStack 列で評価する (テスト・他用途向け)。 */
    public Classification classify(List<ItemStack> items, long timestamp) {
        CategoryScore aggregate = new CategoryScore();
        int itemCount = 0;

        if (items != null) {
            for (ItemStack stack : items) {
                if (stack == null || stack.isEmpty())
                    continue;
                itemCount++;
                CategoryScore single = scorer.scoreOf(stack);
                // 個数重みは「count を加算」だがキャップ。 0 < weight ≤ CAP。
                int weight = Math.min(stack.getCount(), PER_STACK_COUNT_CAP);
                // single の各カテゴリスコアに weight 倍を掛けて aggregate に積む。
                for (Map.Entry<StorageCategory, Integer> e : single.asMap().entrySet()) {
                    aggregate.add(e.getKey(), e.getValue() * weight);
                }
            }
        }

        // ─── 全アイテム空 ───
        if (itemCount == 0) {
            return new Classification(StorageCategory.UNKNOWN, 0f, 0, aggregate.asMap(),
                    timestamp, false);
        }

        // ─── フェーズ 1: 競争 (サブカテゴリは除外して従来どおり争わせる) ───
        CategoryScore.Top top = topExcludingSubs(aggregate);
        // ─── ルールにかすりもしなかった (全 MOD 未知) ───
        if (top == null || top.score() <= 0) {
            return new Classification(StorageCategory.UNKNOWN, 0f, 0, aggregate.asMap(),
                    timestamp, false);
        }

        int total = positiveTotal(aggregate);
        float share = total <= 0 ? 0f : (float) top.score() / (float) total;

        // 2 位スコアを探して MIXED 判定
        int second = secondScore(aggregate, top.category());
        float gap = total <= 0 ? 0f : (float) (top.score() - second) / (float) total;

        boolean mixed = share < LOW_CONFIDENCE_THRESHOLD || gap < MIXED_GAP_THRESHOLD;

        StorageCategory finalCategory = mixed ? StorageCategory.MIXED : top.category();
        // ─── フェーズ 2: 勝者が傘カテゴリのときだけ内訳を見る ───
        //   confidence / totalScore は一族としての値 (= フェーズ 1 の結果) をそのまま使い、
        //   表示カテゴリだけをサブへ差し替える。
        //   2 つの一族は排他: REDSTONE が勝てば石材は一切見ないし、 その逆も同じ。
        if (finalCategory == StorageCategory.REDSTONE) {
            finalCategory = rollUpRedstone(aggregate, top.score());
        } else if (finalCategory == StorageCategory.BUILDING) {
            finalCategory = rollUpStone(aggregate, top.score());
        }
        return new Classification(finalCategory, share, top.score(), aggregate.asMap(),
                timestamp, false);
    }

    // ────────────────────────────────────────────────────────────────────
    // 内部ユーティリティ
    // ────────────────────────────────────────────────────────────────────

    /**
     * フェーズ 1 用の 1 位。 サブカテゴリは候補から外すので、
     * サブへの加点があってもフェーズ 1 の勝敗は従来と変わらない。
     */
    private static CategoryScore.Top topExcludingSubs(CategoryScore score) {
        StorageCategory best = null;
        int bestVal = Integer.MIN_VALUE;
        for (Map.Entry<StorageCategory, Integer> e : score.asMap().entrySet()) {
            if (ALL_SUBS.contains(e.getKey()))
                continue;
            if (e.getValue() > bestVal) {
                bestVal = e.getValue();
                best = e.getKey();
            }
        }
        return best == null ? null : new CategoryScore.Top(best, bestVal);
    }

    /** 占有率の分母。 サブカテゴリは分母にも入れない (= 従来の share と一致させる)。 */
    private static int positiveTotal(CategoryScore score) {
        int sum = 0;
        for (Map.Entry<StorageCategory, Integer> e : score.asMap().entrySet()) {
            if (ALL_SUBS.contains(e.getKey()))
                continue;
            if (e.getValue() > 0)
                sum += e.getValue();
        }
        return sum;
    }

    /** MIXED 判定用の 2 位。 ここもサブカテゴリは無視する。 */
    private static int secondScore(CategoryScore score, StorageCategory excluded) {
        int second = 0;
        for (Map.Entry<StorageCategory, Integer> e : score.asMap().entrySet()) {
            if (e.getKey() == excluded)
                continue;
            if (ALL_SUBS.contains(e.getKey()))
                continue;
            if (e.getValue() > second)
                second = e.getValue();
        }
        return second;
    }

    /**
     * フェーズ 2: 傘 REDSTONE が 1 位だったときの内訳判定。
     *
     * <p>
     * 二重ゲート:
     * <ol>
     * <li>サブ合計 / 傘スコア &ge; {@link #REDSTONE_SUB_PRESENCE}
     * (= そもそもサブに割り当たる中身がある)</li>
     * <li>最大サブ / サブ合計 &ge; {@link #REDSTONE_SUB_DOMINANCE}
     * (= サブの中で 1 つに寄っている)</li>
     * </ol>
     * 片方でも欠ければ傘 REDSTONE を返す (= 混在レッドストーン倉庫は傘のまま)。
     * 最大サブが同点のときは必ず dominance を割るので、 タイブレークは要らない。
     */
    private static StorageCategory rollUpRedstone(CategoryScore score, int umbrellaScore) {
        if (umbrellaScore <= 0)
            return StorageCategory.REDSTONE;

        int subTotal = 0;
        StorageCategory bestSub = null;
        int bestSubScore = 0;
        for (StorageCategory sub : REDSTONE_SUBS) {
            int v = score.get(sub);
            if (v <= 0)
                continue;
            subTotal += v;
            if (v > bestSubScore) {
                bestSubScore = v;
                bestSub = sub;
            }
        }
        if (bestSub == null)
            return StorageCategory.REDSTONE;
        if ((float) subTotal / (float) umbrellaScore < REDSTONE_SUB_PRESENCE)
            return StorageCategory.REDSTONE;
        if ((float) bestSubScore / (float) subTotal < REDSTONE_SUB_DOMINANCE)
            return StorageCategory.REDSTONE;
        return bestSub;
    }

    /**
     * フェーズ 2: 傘 BUILDING が 1 位だったときの石材内訳判定。
     *
     * <p>
     * 二重ゲート ({@link #rollUpRedstone} と同型):
     * <ol>
     * <li>サブ合計 / 傘スコア &ge; {@link #BUILDING_SUB_PRESENCE}
     * (= そもそも石材が中身の主役である)</li>
     * <li>最大サブ / サブ合計 &ge; {@link #BUILDING_SUB_DOMINANCE}
     * (= 石材の中で 1 つの材質に寄っている)</li>
     * </ol>
     *
     * <p>
     * レッドストーンとの唯一の違いは 2 番目を割ったときの戻り値:
     * <ul>
     * <li>1 を割る → 石材が主役ではない (羊毛/コンクリート/ガラス/木材混じり等)
     * ので傘 {@link StorageCategory#BUILDING}。</li>
     * <li>2 を割る → 石材ではあるが材質が拮抗しているので中間層
     * {@link StorageCategory#BUILDING_STONE_MIXED}。</li>
     * </ul>
     * 非石材の建材には石材サブの加点が 1 点も入らないため、 それらの箱は
     * subTotal = 0 で 1 番目を割り、 従来どおり傘 BUILDING のままになる。
     */
    private static StorageCategory rollUpStone(CategoryScore score, int umbrellaScore) {
        if (umbrellaScore <= 0)
            return StorageCategory.BUILDING;

        int subTotal = 0;
        StorageCategory bestSub = null;
        int bestSubScore = 0;
        for (StorageCategory sub : STONE_SUBS) {
            int v = score.get(sub);
            if (v <= 0)
                continue;
            subTotal += v;
            if (v > bestSubScore) {
                bestSubScore = v;
                bestSub = sub;
            }
        }
        if (bestSub == null)
            return StorageCategory.BUILDING;
        if ((float) subTotal / (float) umbrellaScore < BUILDING_SUB_PRESENCE)
            return StorageCategory.BUILDING;
        if ((float) bestSubScore / (float) subTotal < BUILDING_SUB_DOMINANCE)
            return StorageCategory.BUILDING_STONE_MIXED;
        return bestSub;
    }

    private static Classification emptyResult() {
        return new Classification(StorageCategory.UNKNOWN, 0f, 0, java.util.Map.of(),
                System.currentTimeMillis(), false);
    }
}
