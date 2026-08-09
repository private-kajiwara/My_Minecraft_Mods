package com.kajiwara.omnichest.chestsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@link ChestSearchQuery} の単体テスト。
 *
 * <p>
 * この検索欄は 1.21.11 移行コミット ({@code 8a16b71}) で入力配線と絞り込み描画が巻き添えで
 * 削除され、 <b>357 コミットの間 無機能のまま</b>だった。 復旧にあたり 「どういう判定なのか」 を
 * コード外に固定しておかないと、 次の大きな移行でまた無言で壊れても誰も気づけない。 そこで
 * 判定仕様を<b>消えないテスト</b>として据える。
 *
 * <p>
 * 特に重要な 2 点:
 * <ul>
 *   <li><b>語 1 個のとき v1.0.0 と完全同一</b>であること。 複数語 AND を足したせいで
 *       単語 1 個の結果が変わっていないことを、 v1.0.0 の式そのものと突き合わせて検証する
 *       ({@link #singleTermIsByteForByteV100})。</li>
 *   <li><b>全角スペース (U+3000) が区切りとして効く</b>こと。 {@code trim()} も正規表現
 *       {@code \s} も U+3000 を扱わないため、 素朴な実装だと<b>日本語入力のときだけ</b>
 *       無言で 0 件になる (= 直そうとしているバグと区別がつかない)。</li>
 * </ul>
 */
class ChestSearchQueryTest {

    /** 全角スペース (IDEOGRAPHIC SPACE)。 */
    private static final String IDEO_SP = "　";

    /**
     * v1.0.0 ({@code 1c3b444}) の判定式そのもの。 リグレッションの基準として複製してある
     * (= 実装を参照せず、 当時のコードの見た目のまま書く)。
     */
    private static boolean v100Matches(String displayName, String query) {
        return displayName.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    // ════════════════════════════════════════════════════════════════════
    // 空クエリ / 空白のみ — 「判定を呼ばない」 側の契約
    // ════════════════════════════════════════════════════════════════════

    @Test
    void blankQueriesHaveNoTerms() {
        assertTrue(ChestSearchQuery.terms(null).isEmpty());
        assertTrue(ChestSearchQuery.terms("").isEmpty());
        assertTrue(ChestSearchQuery.terms(" ").isEmpty());
        assertTrue(ChestSearchQuery.terms("   ").isEmpty());
        assertTrue(ChestSearchQuery.terms("\t").isEmpty());
        // 全角スペースのみ。 trim() 基準の実装だとここが 「1 語」 になって全件が暗転する。
        assertTrue(ChestSearchQuery.terms(IDEO_SP).isEmpty());
        assertTrue(ChestSearchQuery.terms(IDEO_SP + " " + IDEO_SP).isEmpty());
    }

    @Test
    void isBlankAgreesWithTerms() {
        String[] blanks = {null, "", " ", "   ", "\t", "\n", IDEO_SP, " " + IDEO_SP + "\t"};
        for (String s : blanks) {
            assertTrue(ChestSearchQuery.isBlank(s), "isBlank should be true for [" + s + "]");
            assertTrue(ChestSearchQuery.terms(s).isEmpty(), "terms should be empty for [" + s + "]");
        }
        String[] nonBlanks = {"a", " a ", IDEO_SP + "鉄" + IDEO_SP, "  iron  ingot  "};
        for (String s : nonBlanks) {
            assertFalse(ChestSearchQuery.isBlank(s), "isBlank should be false for [" + s + "]");
            assertFalse(ChestSearchQuery.terms(s).isEmpty(), "terms should be non-empty for [" + s + "]");
        }
    }

    @Test
    void emptyTermsMatchEverything() {
        // 語 0 個 = 全件一致 = 暗転ゼロ。 呼び出し側は isBlank でループごと飛ばすが、
        // 万一到達しても 「全部暗い」 にならないことを保証する。
        assertTrue(ChestSearchQuery.matches("Iron Ingot", List.of()));
        assertTrue(ChestSearchQuery.matches("", List.of()));
        assertTrue(ChestSearchQuery.matches(null, List.of()));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", ""));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "   "));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", IDEO_SP));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", null));
    }

    // ════════════════════════════════════════════════════════════════════
    // trim (前後の空白) — v1.0.0 が持っていた粗さの改善点 (1)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void surroundingWhitespaceIsStripped() {
        assertEquals(List.of("iron"), ChestSearchQuery.terms(" iron "));
        assertEquals(List.of("iron"), ChestSearchQuery.terms("\tiron\n"));
        assertEquals(List.of("iron"), ChestSearchQuery.terms(IDEO_SP + "iron" + IDEO_SP));

        // v1.0.0 は末尾スペースを落とさないので、 <b>語が名前の末尾にある</b>とき 0 件になっていた
        // (= 「ingot」 まで打って半角スペースを 1 つ余分に打つと、 出ていたはずの結果が消える)。
        // ここが直っていることが本題。
        assertFalse(v100Matches("Iron Ingot", "ingot "), "v1.0.0 では末尾スペースで落ちていた (= 改善前の挙動)");
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "ingot "));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", " ingot"));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "  ingot  "));
        // 先頭スペースは語が名前の先頭にあるときに同じことが起きる。
        assertFalse(v100Matches("Iron Ingot", " iron"), "v1.0.0 では先頭スペースで落ちていた (= 改善前の挙動)");
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", " iron"));
    }

    // ════════════════════════════════════════════════════════════════════
    // ★ 語 1 個 = v1.0.0 と完全同一
    // ════════════════════════════════════════════════════════════════════

    @Test
    void singleTermIsByteForByteV100() {
        // 空白を含まないクエリ (= 語 1 個) では、 新実装と v1.0.0 の式が全ケースで一致すること。
        String[] names = {
            "Iron Ingot", "Iron Block", "Diamond", "Oak Log", "Enchanted Book",
            "鉄インゴット", "ダイヤモンド", "エンダーチェスト", "", "Shulker Box",
            "Nether Quartz", "iron", "IRON", "IrOn",
        };
        String[] queries = {
            "iron", "IRON", "IrOn", "ingot", "on i", // "on i" は空白入りなので下の分岐で扱う
            "diamond", "z", "鉄", "インゴット", "box", "quartz", "",
        };
        for (String name : names) {
            for (String q : queries) {
                if (ChestSearchQuery.isBlank(q)) {
                    continue; // 空クエリは v1.0.0 と契約が違う (v1.0.0 は呼び出し側で弾いていた)
                }
                if (ChestSearchQuery.terms(q).size() != 1) {
                    continue; // ここで検証するのは 「語 1 個」 のときの同一性
                }
                assertEquals(v100Matches(name, q.trim()), ChestSearchQuery.matchesRaw(name, q),
                        "語 1 個では v1.0.0 と一致すべき: name=[" + name + "] q=[" + q + "]");
            }
        }
    }

    @Test
    void singleTermDoesNotSplitInsideWord() {
        // 語が 1 個である限り、 部分列としての連続性は v1.0.0 と同じく保たれる。
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "ron in"));   // 空白ありなので 2 語
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "ronin") == false);
        assertEquals(List.of("ron", "in"), ChestSearchQuery.terms("ron in"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 複数語 AND — v1.0.0 が持っていた粗さの改善点 (2)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void multipleTermsAreAnded() {
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "iron ingot"));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "iron got"));
        assertFalse(ChestSearchQuery.matchesRaw("Iron Ingot", "iron diamond"));
        assertFalse(ChestSearchQuery.matchesRaw("Iron Block", "iron ingot"));
    }

    @Test
    void termOrderDoesNotMatter() {
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "iron ingot"));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "ingot iron"));
        assertEquals(ChestSearchQuery.matchesRaw("Block of Redstone", "block redstone"),
                ChestSearchQuery.matchesRaw("Block of Redstone", "redstone block"));
    }

    @Test
    void duplicatedTermsDoNotChangeResult() {
        assertEquals(ChestSearchQuery.matchesRaw("Iron Ingot", "iron"),
                ChestSearchQuery.matchesRaw("Iron Ingot", "iron iron"));
        assertEquals(ChestSearchQuery.matchesRaw("Iron Ingot", "iron ingot"),
                ChestSearchQuery.matchesRaw("Iron Ingot", "iron ingot iron"));
        assertEquals(List.of("iron", "iron"), ChestSearchQuery.terms("iron iron"));
    }

    @Test
    void repeatedSeparatorsCollapse() {
        assertEquals(List.of("iron", "ingot"), ChestSearchQuery.terms("iron   ingot"));
        assertEquals(List.of("iron", "ingot"), ChestSearchQuery.terms("  iron \t\n ingot  "));
        assertEquals(List.of("iron", "ingot"),
                ChestSearchQuery.terms(IDEO_SP + IDEO_SP + "iron" + IDEO_SP + " " + IDEO_SP + "ingot" + IDEO_SP));
    }

    // ════════════════════════════════════════════════════════════════════
    // 全角スペース (U+3000) — 日本語入力での落とし穴
    // ════════════════════════════════════════════════════════════════════

    @Test
    void ideographicSpaceIsASeparator() {
        // IME が全角のまま確定したケース。 これが区切りにならないと無言で 0 件になる。
        assertEquals(List.of("鉄", "インゴット"), ChestSearchQuery.terms("鉄" + IDEO_SP + "インゴット"));
        assertTrue(ChestSearchQuery.matchesRaw("鉄インゴット", "鉄" + IDEO_SP + "インゴット"));
        assertFalse(ChestSearchQuery.matchesRaw("鉄ブロック", "鉄" + IDEO_SP + "インゴット"));

        // 半角と全角が混ざっても等しく区切りとして働く。
        assertEquals(List.of("iron", "ingot"), ChestSearchQuery.terms("iron" + IDEO_SP + "ingot"));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "iron" + IDEO_SP + "ingot"));
    }

    @Test
    void ideographicSpaceInNameIsStillReachable() {
        // 表示名そのものが全角スペースを含む場合でも、 語へ分割して AND 判定するので取りこぼさない。
        String name = "鉄" + IDEO_SP + "インゴット";
        assertTrue(ChestSearchQuery.matchesRaw(name, "鉄" + IDEO_SP + "インゴット"));
        assertTrue(ChestSearchQuery.matchesRaw(name, "鉄 インゴット"));
        assertTrue(ChestSearchQuery.matchesRaw(name, "インゴット"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 大小文字 / 部分一致 / 日本語
    // ════════════════════════════════════════════════════════════════════

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "IRON"));
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "iron"));
        assertTrue(ChestSearchQuery.matchesRaw("IRON INGOT", "iron"));
        assertTrue(ChestSearchQuery.matchesRaw("iron ingot", "IrOn InGoT"));
    }

    @Test
    void caseFoldingUsesLocaleRootNotDefault() {
        // トルコ語ロケールでは "I".toLowerCase() が "ı" (dotless) になり、 "i" と一致しなくなる。
        // Locale.ROOT 固定なので、 既定ロケールが何であってもここは通る。
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "IRON"));
            assertTrue(ChestSearchQuery.matchesRaw("IRON INGOT", "iron"));
            assertEquals(List.of("iron"), ChestSearchQuery.terms("IRON"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void matchingIsSubstring() {
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "ron"));   // 語頭でなくてよい
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "got"));   // 語末でもよい
        assertTrue(ChestSearchQuery.matchesRaw("Iron Ingot", "n I"));   // 単語をまたいでもよい (1 語扱いではない)
        assertFalse(ChestSearchQuery.matchesRaw("Iron Ingot", "steel"));
    }

    @Test
    void japaneseNamesMatchWithoutCaseFoldingArtifacts() {
        assertTrue(ChestSearchQuery.matchesRaw("鉄インゴット", "鉄"));
        assertTrue(ChestSearchQuery.matchesRaw("鉄インゴット", "インゴット"));
        assertTrue(ChestSearchQuery.matchesRaw("エンダーチェスト", "チェスト"));
        assertFalse(ChestSearchQuery.matchesRaw("鉄インゴット", "金"));
        // 小文字化しても日本語は変化しない (= 畳み込みの副作用が無い)。
        assertEquals(List.of("鉄インゴット"), ChestSearchQuery.terms("鉄インゴット"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 該当ゼロ / 全件該当 (= 描画側の 「全部暗い」 「何も暗くない」 に直結)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void zeroHitsAcrossAWholeContainer() {
        String[] chest = {"Iron Ingot", "Gold Ingot", "Diamond", "Oak Log", "鉄インゴット"};
        List<String> terms = ChestSearchQuery.terms("netherite");
        for (String name : chest) {
            assertFalse(ChestSearchQuery.matches(name, terms), "該当ゼロのはず: " + name);
        }
    }

    @Test
    void allHitsAcrossAWholeContainer() {
        String[] chest = {"Iron Ingot", "Iron Block", "Iron Nugget", "Raw Iron"};
        List<String> terms = ChestSearchQuery.terms("iron");
        for (String name : chest) {
            assertTrue(ChestSearchQuery.matches(name, terms), "全件該当のはず: " + name);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // null / 空の表示名 (= 防御的な入力)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void nullOrEmptyDisplayNameNeverMatchesANonEmptyQuery() {
        List<String> terms = ChestSearchQuery.terms("iron");
        assertFalse(ChestSearchQuery.matches(null, terms));
        assertFalse(ChestSearchQuery.matches("", terms));
    }

    @Test
    void termsResultIsUnmodifiable() {
        // 描画ループへ持ち回す値なので、 呼び出し側から壊せないことを保証する。
        List<String> terms = ChestSearchQuery.terms("iron ingot");
        assertEquals(2, terms.size());
        try {
            terms.add("gold");
            throw new AssertionError("terms() の戻り値は変更不可であるべき");
        } catch (UnsupportedOperationException expected) {
            // 期待どおり
        }
    }
}
