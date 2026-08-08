package com.kajiwara.omnichest.peek;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * コンテナ ピークの<b>コンパクトモード</b> (= グリッドがどこにも置けないときのフォールバック)
 * で使う、 スロット一覧から 「上位 N 種 + 他 M 種 + 合計」 を組み立てる純粋関数。
 * Minecraft 型に一切依存しないため {@code common} 側に置き、 単体テスト可能にしている
 * ({@link ContainerPeekFit} / {@link PeekFreshness} と同じ流儀)。
 *
 * <p>
 * <b>役割分担</b>: 「2 つのスタックが同じ種類か」 の判定だけは Minecraft 側でしかできない
 * ({@code ItemStack.isSameItemSameComponents})。 そこで呼び出し側が<b>同種のスロットに
 * 同じ {@code id} を振って</b>渡し、 本クラスは <b>合算 / 並べ替え / 上位 N の切り出し /
 * 「他 M 種」 の算出</b>という、 間違えやすいがテストできる部分だけを担う。
 *
 * <p>
 * <b>不変条件 (invariant)</b>:
 * <ul>
 *   <li><b>同じ {@code id} は必ず 1 行に合算される</b> (= 「石 ×64」 が 3 行に割れない)。</li>
 *   <li><b>並び順は決定的</b>: 個数の降順、 同数なら<b>先に現れたほう</b>が先。 これにより
 *       同じスナップショットからは毎フレーム同じ並びが出る (= ちらつかない)。</li>
 *   <li><b>種類数が上限以下なら {@code otherKinds} は 0</b> (= 「他 0 種」 という無意味な行を
 *       呼び出し側が描かずに済む)。</li>
 *   <li>{@code usedSlots} / {@code totalCount} は上位 N に切り詰める<b>前</b>の全体値
 *       (= サマリ行が 「38 / 54 · ×1204」 のように実数を出せる)。</li>
 * </ul>
 */
public final class PeekSummary {

    private PeekSummary() {
    }

    /** コンパクトモードで並べるアイテムの種類数の上限。 */
    public static final int TOP_N = 5;

    /**
     * 中身のあるスロット 1 つぶん。
     *
     * @param id    同種スタックに同じ値を振った識別子 (= 呼び出し側が
     *              {@code ItemStack.isSameItemSameComponents} で採番する)
     * @param count そのスロットの個数
     */
    public record Slot(int id, int count) {
    }

    /** 合算後の 1 行。 */
    public record Line(int id, int count) {
    }

    /**
     * 集計結果。
     *
     * @param top           上位 {@code limit} 種 (個数の降順・同数は先に現れた順)
     * @param otherKinds    {@code top} に載らなかった種類数。 0 なら 「他 M 種」 行は出さない
     * @param distinctKinds 種類数の総数
     * @param usedSlots     中身のあるスロット数
     * @param totalCount    全アイテムの個数の総和
     */
    public record Summary(List<Line> top, int otherKinds, int distinctKinds,
            int usedSlots, int totalCount) {
    }

    /** 空のコンテナ用。 */
    public static final Summary EMPTY = new Summary(List.of(), 0, 0, 0, 0);

    /**
     * スロット一覧を集計する。
     *
     * <p>
     * {@code null} / 空リストは {@link #EMPTY} を返す。 {@code count <= 0} のスロットは
     * 「中身なし」 とみなして無視する (= 呼び出し側が空スロットを混ぜても壊れない)。
     *
     * @param slots 中身のあるスロット (同じ {@code id} が何度現れてもよい)
     * @param limit 上位何種まで {@code top} に載せるか。 0 以下なら {@code top} は空になり、
     *              すべてが {@code otherKinds} に回る
     */
    public static Summary summarize(List<Slot> slots, int limit) {
        if (slots == null || slots.isEmpty()) {
            return EMPTY;
        }
        // 挿入順 (= 先に現れた順) を保つ map。 同数タイブレークの決定性はこの順序に由来する。
        Map<Integer, int[]> merged = new LinkedHashMap<>();
        int usedSlots = 0;
        int totalCount = 0;
        for (Slot s : slots) {
            if (s == null || s.count() <= 0) {
                continue;
            }
            usedSlots++;
            totalCount += s.count();
            int[] acc = merged.get(s.id());
            if (acc == null) {
                merged.put(s.id(), new int[] { s.count() });
            } else {
                acc[0] += s.count();
            }
        }
        if (merged.isEmpty()) {
            return EMPTY;
        }

        List<Line> all = new ArrayList<>(merged.size());
        // 挿入順のまま取り出し、 その添字を同数タイブレークに使う。
        List<Integer> firstSeen = new ArrayList<>(merged.size());
        for (Map.Entry<Integer, int[]> e : merged.entrySet()) {
            firstSeen.add(e.getKey());
            all.add(new Line(e.getKey(), e.getValue()[0]));
        }
        // 個数降順、 同数は先に現れたほう (= 元の添字が小さいほう) を先に。
        // sort は安定なので、 個数だけで比較すれば挿入順が自然に保たれる。
        all.sort((a, b) -> Integer.compare(b.count(), a.count()));

        int distinct = all.size();
        int take = Math.max(0, Math.min(limit, distinct));
        List<Line> top = Collections.unmodifiableList(new ArrayList<>(all.subList(0, take)));
        return new Summary(top, distinct - take, distinct, usedSlots, totalCount);
    }

    /** {@link #TOP_N} を上限にした {@link #summarize}。 */
    public static Summary summarize(List<Slot> slots) {
        return summarize(slots, TOP_N);
    }
}
