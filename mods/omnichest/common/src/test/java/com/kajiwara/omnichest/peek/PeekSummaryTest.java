package com.kajiwara.omnichest.peek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.kajiwara.omnichest.peek.PeekSummary.Line;
import com.kajiwara.omnichest.peek.PeekSummary.Slot;
import com.kajiwara.omnichest.peek.PeekSummary.Summary;
import org.junit.jupiter.api.Test;

/**
 * {@link PeekSummary} の単体テスト。
 *
 * <p>
 * コンパクトモードは 「グリッドがどこにも置けないときだけ」 出るフォールバックなので、
 * 実機で目に触れる機会が少なく、 壊れても気付きにくい。 だから
 * <ul>
 *   <li>同じアイテムが 1 行に合算されること (= 「石 ×64」 が 3 行に割れない)</li>
 *   <li>同数のときの並びが決定的であること (= 毎フレームちらつかない)</li>
 *   <li>種類数が上限以下のときに 「他 0 種」 を出さないこと</li>
 *   <li>合計値が上位 N に切り詰める<b>前</b>の実数であること</li>
 * </ul>
 * をテストで固定する。
 */
class PeekSummaryTest {

    private static List<Slot> slots(int... idCountPairs) {
        List<Slot> out = new ArrayList<>();
        for (int i = 0; i < idCountPairs.length; i += 2) {
            out.add(new Slot(idCountPairs[i], idCountPairs[i + 1]));
        }
        return out;
    }

    private static List<Integer> ids(Summary s) {
        List<Integer> out = new ArrayList<>();
        for (Line l : s.top()) {
            out.add(l.id());
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // 合算
    // ════════════════════════════════════════════════════════════════════

    @Test
    void sameItemAcrossSlotsIsMergedIntoOneLine() {
        // 石が 3 スロットに分かれていても 1 行 ×192 になる。
        Summary s = PeekSummary.summarize(slots(1, 64, 1, 64, 1, 64));
        assertEquals(1, s.top().size());
        assertEquals(1, s.top().get(0).id());
        assertEquals(192, s.top().get(0).count());
        assertEquals(1, s.distinctKinds());
        assertEquals(3, s.usedSlots(), "スロット数は合算しても 3 のまま");
        assertEquals(192, s.totalCount());
    }

    @Test
    void differentItemsStaySeparate() {
        Summary s = PeekSummary.summarize(slots(1, 10, 2, 20, 3, 30));
        assertEquals(3, s.distinctKinds());
        assertEquals(List.of(3, 2, 1), ids(s), "個数の降順");
        assertEquals(60, s.totalCount());
        assertEquals(3, s.usedSlots());
    }

    // ════════════════════════════════════════════════════════════════════
    // 並び順 (= 決定性)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void orderIsByCountDescending() {
        Summary s = PeekSummary.summarize(slots(7, 1, 8, 100, 9, 50));
        assertEquals(List.of(8, 9, 7), ids(s));
    }

    @Test
    void tiesKeepFirstSeenOrder() {
        // 同数のときは「先に現れたほう」が先。 走査順が同じなら結果も同じ = 毎フレーム同じ並び。
        assertEquals(List.of(5, 6, 7), ids(PeekSummary.summarize(slots(5, 10, 6, 10, 7, 10))));
        assertEquals(List.of(7, 6, 5), ids(PeekSummary.summarize(slots(7, 10, 6, 10, 5, 10))));
    }

    @Test
    void tieBreakUsesFirstAppearanceNotLast() {
        // id=2 は 2 スロットに割れて後から合計 10 になるが、 初出は id=1 より後なので後ろ。
        Summary s = PeekSummary.summarize(slots(1, 10, 2, 5, 2, 5));
        assertEquals(List.of(1, 2), ids(s));
        assertEquals(10, s.top().get(0).count());
        assertEquals(10, s.top().get(1).count());
    }

    @Test
    void repeatedSummarizeOfTheSameInputIsStable() {
        List<Slot> in = slots(1, 10, 2, 10, 3, 10, 4, 10, 5, 10, 6, 10, 7, 10);
        List<Integer> first = ids(PeekSummary.summarize(in));
        for (int i = 0; i < 20; i++) {
            assertEquals(first, ids(PeekSummary.summarize(in)), "呼ぶたびに並びが変わってはいけない");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 上位 N と「他 M 種」
    // ════════════════════════════════════════════════════════════════════

    @Test
    void topIsCappedAtTheLimitAndTheRestBecomesOtherKinds() {
        // 7 種を上限 5 で切ると、 上位 5 種 + 他 2 種。
        Summary s = PeekSummary.summarize(
                slots(1, 70, 2, 60, 3, 50, 4, 40, 5, 30, 6, 20, 7, 10), 5);
        assertEquals(5, s.top().size());
        assertEquals(List.of(1, 2, 3, 4, 5), ids(s));
        assertEquals(2, s.otherKinds());
        assertEquals(7, s.distinctKinds());
    }

    @Test
    void otherKindsIsZeroWhenEverythingFits() {
        // ★「他 0 種」 という無意味な行を呼び出し側に描かせない。
        for (int kinds = 1; kinds <= PeekSummary.TOP_N; kinds++) {
            List<Slot> in = new ArrayList<>();
            for (int i = 0; i < kinds; i++) {
                in.add(new Slot(i, 10 + i));
            }
            Summary s = PeekSummary.summarize(in);
            assertEquals(0, s.otherKinds(), "kinds=" + kinds);
            assertEquals(kinds, s.top().size(), "kinds=" + kinds);
        }
    }

    @Test
    void exactlyOneOverTheLimitReportsOtherOne() {
        List<Slot> in = new ArrayList<>();
        for (int i = 0; i < PeekSummary.TOP_N + 1; i++) {
            in.add(new Slot(i, 100 - i));
        }
        Summary s = PeekSummary.summarize(in);
        assertEquals(PeekSummary.TOP_N, s.top().size());
        assertEquals(1, s.otherKinds());
    }

    @Test
    void nonPositiveLimitPushesEverythingIntoOtherKinds() {
        Summary s = PeekSummary.summarize(slots(1, 10, 2, 20), 0);
        assertTrue(s.top().isEmpty());
        assertEquals(2, s.otherKinds());
        assertEquals(30, s.totalCount(), "合計は切り詰めの影響を受けない");
    }

    @Test
    void totalsAreComputedBeforeTruncation() {
        // 上位 2 種だけ出しても、 サマリ行の「使用スロット / 総数」は全体の実数。
        Summary s = PeekSummary.summarize(slots(1, 10, 2, 20, 3, 30, 4, 40), 2);
        assertEquals(2, s.top().size());
        assertEquals(4, s.usedSlots());
        assertEquals(100, s.totalCount());
        assertEquals(4, s.distinctKinds());
    }

    // ════════════════════════════════════════════════════════════════════
    // 異常入力
    // ════════════════════════════════════════════════════════════════════

    @Test
    void nullAndEmptyAreEmpty() {
        assertSame(PeekSummary.EMPTY, PeekSummary.summarize(null));
        assertSame(PeekSummary.EMPTY, PeekSummary.summarize(List.of()));
    }

    @Test
    void emptySlotsAreIgnored() {
        // count <= 0 は「中身なし」。 使用スロット数にも合計にも入れない。
        Summary s = PeekSummary.summarize(
                new ArrayList<>(Arrays.asList(new Slot(1, 0), new Slot(2, 5), null, new Slot(3, -3))));
        assertEquals(1, s.distinctKinds());
        assertEquals(1, s.usedSlots());
        assertEquals(5, s.totalCount());
        assertEquals(List.of(2), ids(s));
    }

    @Test
    void allEmptyIsEmpty() {
        assertSame(PeekSummary.EMPTY, PeekSummary.summarize(slots(1, 0, 2, 0)));
    }

    @Test
    void topListIsUnmodifiable() {
        Summary s = PeekSummary.summarize(slots(1, 10));
        try {
            s.top().add(new Line(2, 1));
            throw new AssertionError("top は変更不可であるべき");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 実データ相当
    // ════════════════════════════════════════════════════════════════════

    @Test
    void realisticLargeChestProducesFiveLinesPlusOthers() {
        // ラージチェスト 54 スロット: 8 種が散らばっている状況。
        List<Slot> in = new ArrayList<>();
        int[] perKind = { 12, 9, 8, 7, 6, 5, 4, 3 };   // 各種のスロット数 = 合計 54
        for (int kind = 0; kind < perKind.length; kind++) {
            for (int i = 0; i < perKind[kind]; i++) {
                in.add(new Slot(kind, 64));
            }
        }
        Summary s = PeekSummary.summarize(in);
        assertEquals(54, s.usedSlots());
        assertEquals(54 * 64, s.totalCount());
        assertEquals(8, s.distinctKinds());
        assertEquals(5, s.top().size());
        assertEquals(3, s.otherKinds());
        assertEquals(List.of(0, 1, 2, 3, 4), ids(s));
        assertEquals(12 * 64, s.top().get(0).count());
    }
}
