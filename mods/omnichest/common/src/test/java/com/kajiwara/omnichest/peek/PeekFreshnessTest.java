package com.kajiwara.omnichest.peek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kajiwara.omnichest.peek.PeekFreshness.Label;
import com.kajiwara.omnichest.peek.PeekFreshness.Unit;
import org.junit.jupiter.api.Test;

/**
 * {@link PeekFreshness} の単体テスト。
 *
 * <p>
 * ピークが出す中身は 「最後に開いたときのスナップショット」 であって現在の中身ではないため、
 * 鮮度表示が壊れる = 機能が嘘をつく、 という直結の関係にある。 特に
 * <ul>
 *   <li><b>負の経過時間</b> (= 壁時計の巻き戻し / 別 PC のキャッシュ持ち込み) で
 *       「-3 分前」 のような破綻表示が出ないこと</li>
 *   <li><b>境界</b> (59s / 60s / 59min / 1h / 23h / 1d / 30d) で単位が正しく切り替わること</li>
 *   <li><b>上限</b> で日数が無限に伸びず 「30 日以上前」 に丸まること</li>
 * </ul>
 * を消えないテストとして固定する。
 */
class PeekFreshnessTest {

    private static final long S = PeekFreshness.SECOND_MS;
    private static final long M = PeekFreshness.MINUTE_MS;
    private static final long H = PeekFreshness.HOUR_MS;
    private static final long D = PeekFreshness.DAY_MS;

    // ════════════════════════════════════════════════════════════════════
    // elapsedMs: 負値クランプ
    // ════════════════════════════════════════════════════════════════════

    @Test
    void elapsedIsNeverNegative() {
        // 壁時計が巻き戻った / 未来のキャッシュを持ち込んだ場合。
        assertEquals(0L, PeekFreshness.elapsedMs(1_000L, 5_000L));
        assertEquals(0L, PeekFreshness.elapsedMs(0L, Long.MAX_VALUE));
        assertEquals(0L, PeekFreshness.elapsedMs(-1_000L, 1_000L));
    }

    @Test
    void elapsedIsPlainDifferenceWhenForward() {
        assertEquals(0L, PeekFreshness.elapsedMs(5_000L, 5_000L));
        assertEquals(4_000L, PeekFreshness.elapsedMs(9_000L, 5_000L));
    }

    @Test
    void elapsedDoesNotOverflowOnExtremeInputs() {
        // Long.MIN_VALUE を素で引くと long がラップするが、 先に比較しているのでクランプされる。
        assertEquals(0L, PeekFreshness.elapsedMs(Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(PeekFreshness.elapsedMs(Long.MAX_VALUE, 0L) > 0L);
    }

    // ════════════════════════════════════════════════════════════════════
    // label: 境界値 (= タスク指定の 0ms / 59s / 60s / 59min / 1h / 23h / 1d / 30d / 負値)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void zeroIsJustNow() {
        Label l = PeekFreshness.label(0L);
        assertEquals(Unit.JUST_NOW, l.unit());
        assertEquals(PeekFreshness.KEY_JUST_NOW, l.key());
        assertFalse(l.hasAmount(), "just_now は %1$d を取らない");
    }

    @Test
    void negativeIsClampedToJustNow() {
        assertEquals(Unit.JUST_NOW, PeekFreshness.label(-1L).unit());
        assertEquals(Unit.JUST_NOW, PeekFreshness.label(Long.MIN_VALUE).unit());
    }

    @Test
    void fiftyNineSecondsIsStillJustNow() {
        assertEquals(Unit.JUST_NOW, PeekFreshness.label(59 * S).unit());
        assertEquals(Unit.JUST_NOW, PeekFreshness.label(M - 1).unit());
    }

    @Test
    void sixtySecondsFlipsToOneMinute() {
        Label l = PeekFreshness.label(60 * S);
        assertEquals(Unit.MINUTES, l.unit());
        assertEquals(PeekFreshness.KEY_MINUTES, l.key());
        assertEquals(1, l.amount());
        assertTrue(l.hasAmount());
    }

    @Test
    void fiftyNineMinutesIsStillMinutes() {
        Label l = PeekFreshness.label(59 * M);
        assertEquals(Unit.MINUTES, l.unit());
        assertEquals(59, l.amount());
        assertEquals(Unit.MINUTES, PeekFreshness.label(H - 1).unit());
    }

    @Test
    void oneHourFlipsToHours() {
        Label l = PeekFreshness.label(H);
        assertEquals(Unit.HOURS, l.unit());
        assertEquals(PeekFreshness.KEY_HOURS, l.key());
        assertEquals(1, l.amount());
    }

    @Test
    void twentyThreeHoursIsStillHours() {
        Label l = PeekFreshness.label(23 * H);
        assertEquals(Unit.HOURS, l.unit());
        assertEquals(23, l.amount());
        assertEquals(Unit.HOURS, PeekFreshness.label(D - 1).unit());
    }

    @Test
    void oneDayFlipsToDays() {
        Label l = PeekFreshness.label(D);
        assertEquals(Unit.DAYS, l.unit());
        assertEquals(PeekFreshness.KEY_DAYS, l.key());
        assertEquals(1, l.amount());
    }

    @Test
    void twentyNineDaysIsStillDays() {
        Label l = PeekFreshness.label(29 * D);
        assertEquals(Unit.DAYS, l.unit());
        assertEquals(29, l.amount());
        assertEquals(Unit.DAYS, PeekFreshness.label(PeekFreshness.LONG_AGO_MS - 1).unit());
    }

    @Test
    void thirtyDaysIsClampedToLongAgo() {
        Label l = PeekFreshness.label(PeekFreshness.LONG_AGO_MS);
        assertEquals(Unit.LONG_AGO, l.unit());
        assertEquals(PeekFreshness.KEY_LONG_AGO, l.key());
        assertEquals(PeekFreshness.LONG_AGO_DAYS, l.amount(), "上限表現の数値は常に 30 で固定");
    }

    @Test
    void veryOldSnapshotsNeverGrowBeyondTheCap() {
        // 「412 日前」 のような無意味な数値でパネル幅が暴れないこと。
        for (long days : new long[] { 31, 100, 365, 10_000 }) {
            Label l = PeekFreshness.label(days * D);
            assertEquals(Unit.LONG_AGO, l.unit(), days + " 日");
            assertEquals(PeekFreshness.LONG_AGO_DAYS, l.amount(), days + " 日");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 構造的な性質
    // ════════════════════════════════════════════════════════════════════

    @Test
    void unitIsMonotonicAcrossTheWholeRange() {
        // 経過が増えて単位が「戻る」ことは無い (just_now → minutes → hours → days → long_ago)。
        Unit prev = Unit.JUST_NOW;
        long[] samples = {
                0, 1, S, 59 * S, M, 5 * M, 59 * M, H, 2 * H, 23 * H,
                D, 2 * D, 29 * D, 30 * D, 400 * D
        };
        for (long ms : samples) {
            Unit u = PeekFreshness.label(ms).unit();
            assertTrue(u.ordinal() >= prev.ordinal(),
                    "単位が戻った: " + ms + "ms で " + prev + " → " + u);
            prev = u;
        }
    }

    @Test
    void everyLabelHasAKeyAndAFallback() {
        for (long ms : new long[] { 0, M, H, D, 40 * D }) {
            Label l = PeekFreshness.label(ms);
            assertTrue(l.key() != null && l.key().startsWith("omnichest.peek.age."), "key: " + l.key());
            assertTrue(l.enFallback() != null && !l.enFallback().isEmpty(), "fallback: " + l.enFallback());
            // %1$d を要求するキーだけが hasAmount=true であること (= 引数の渡し忘れ / 余りを防ぐ)。
            assertEquals(l.enFallback().contains("%1$d"), l.hasAmount(), "key=" + l.key());
        }
    }

    @Test
    void labelForCombinesClampAndMapping() {
        long now = 1_000_000_000L;
        // 未来のスナップショット → クランプされて just_now。
        assertEquals(Unit.JUST_NOW, PeekFreshness.labelFor(now, now + 999_999L).unit());
        assertEquals(Unit.MINUTES, PeekFreshness.labelFor(now, now - 5 * M).unit());
        assertEquals(5, PeekFreshness.labelFor(now, now - 5 * M).amount());
    }
}
