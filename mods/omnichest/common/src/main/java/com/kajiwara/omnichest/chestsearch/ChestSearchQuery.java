package com.kajiwara.omnichest.chestsearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * チェスト GUI に載せた検索欄の <b>マッチ判定</b> を担う純粋関数。 Minecraft 型に一切依存しない
 * ため {@code common} 側に置き、 単体テスト可能にしている ({@code GuiScaleFit} /
 * {@code SidePanelFit} / {@code ExistingCategoriesFit} / {@code TextContrastFit} /
 * {@code ContainerPeekFit} / {@code PeekSummary} と同じ流儀)。
 *
 * <p>
 * <b>役割分担</b>: 「アイテムの表示名を得る」 のは Minecraft 側でしかできない
 * ({@code ItemStack#getHoverName().getString()})。 そこで呼び出し側が<b>表示名の文字列</b>を
 * 渡し、 本クラスは <b>クエリの正規化 / 語分割 / 部分一致の AND 判定</b> という、
 * 間違えやすいがテストできる部分だけを担う。
 *
 * <p>
 * <b>仕様の由来</b>: v1.0.0 ({@code 1c3b444}) の実装
 * {@code getHoverName().getString().toLowerCase(ROOT).contains(query.toLowerCase(ROOT))}
 * を基準とし、 <b>粗さ 2 点だけ</b>を改善した:
 * <ol>
 *   <li><b>前後の空白を落とす</b> — v1.0.0 は trim していなかったので、 末尾に空白が 1 つ
 *       入っただけで 0 件になっていた (= 無言で 「全部暗い」 になる)。</li>
 *   <li><b>空白区切りの複数語を AND で扱う</b> — v1.0.0 は 1 つの連続部分列としか照合できず、
 *       {@code "iron ingot"} は 「その並びで連続している名前」 しか拾えなかった。</li>
 * </ol>
 * <b>語が 1 個のときの結果は v1.0.0 と完全に同一</b>である (= 複数語ロジックは単語 1 個の
 * 判定を一切変えない)。 これは {@code ChestSearchQueryTest} が消えないテストとして固定する。
 *
 * <p>
 * <b>判定対象は表示名のみ</b>。 Item ID / namespace / 翻訳キー / エンチャント名は<b>見ない</b>。
 * これは検索欄のツールチップ ({@code omnichest.editbox.search.tooltip} =
 * "Highlight items in this chest by name.") と挙動を 1:1 に保つため。 それらまで拾う
 * 「倉庫検索」 側の判定 ({@code SearchMatcher#matchesQuery}) とは<b>意図的に別物</b>であり、
 * 本クラスから倉庫検索の挙動を変えることはない。
 *
 * <p>
 * <b>★ 全角スペース (U+3000) を区切りとして扱う</b>。 実測 (JDK 21) で
 * <ul>
 *   <li>{@code Character.isWhitespace('　')} = <b>true</b></li>
 *   <li>{@code String.trim()} は U+3000 を<b>落とさない</b> (U+0020 以下しか見ないため)</li>
 *   <li>正規表現 {@code \s} も U+3000 に<b>一致しない</b> (Java の {@code \s} は ASCII 限定)</li>
 * </ul>
 * であるため、 素朴に {@code trim()} や {@code split("\\s+")} を使うと<b>日本語入力の
 * スペースだけが区切りにならない</b>。 IME が全角のまま確定するのはごく普通に起こるので、
 * その場合 {@code "鉄　インゴット"} が 「U+3000 を含む 1 語」 になり、 どのアイテム名にも
 * 一致せず<b>無言で 0 件</b>になる (= まさに今回直している 「打っても何も起きない」 と
 * 見分けがつかない)。 そこで分割も除去も {@link Character#isWhitespace} を基準に統一する。
 *
 * <p>
 * この扱いは<b>取りこぼしを増やさない</b>: 仮に表示名そのものが U+3000 を含んでいても、
 * クエリは前後の語へ分割されて AND 判定になるだけなので、 その名前は依然としてヒットする。
 *
 * <p>
 * <b>不変条件 (invariant)</b>:
 * <ul>
 *   <li><b>空クエリ (空文字 / 空白のみ) は語 0 個</b> になり、 {@link #matches} は常に true を返す
 *       (= 全件一致 = 暗転ゼロ)。 呼び出し側は {@link #isBlank} で<b>ループ自体を回さない</b>
 *       ことで、 検索欄が空のときに描画を 1 回も足さない。</li>
 *   <li><b>大小文字を無視する</b>。 畳み込みは {@link Locale#ROOT} 固定 (= トルコ語ロケールで
 *       {@code "I"} が {@code "ı"} に落ちる等の環境依存を排除する)。</li>
 *   <li><b>部分一致</b>。 語は表示名の<b>どこに</b>あってもよい。</li>
 *   <li><b>語の順序に依存しない</b>。 {@code "ingot iron"} と {@code "iron ingot"} は同じ結果。</li>
 *   <li><b>語の重複は結果を変えない</b> ({@code "iron iron"} = {@code "iron"})。</li>
 * </ul>
 *
 * <p>
 * <b>外すと何が壊れるか</b>: 呼び出し側が自前で {@code trim()} / {@code split} を書き始めると、
 * 上の U+3000 の落とし穴を再び踏み、 日本語入力でだけ無言で 0 件になる。 クエリの正規化は
 * <b>必ずここを通す</b>こと。
 */
public final class ChestSearchQuery {

    private ChestSearchQuery() {
    }

    /**
     * 生のクエリ文字列を、 判定に使う<b>語のリスト</b>へ正規化する。
     *
     * <p>
     * 前後および語間の空白は {@link Character#isWhitespace} 基準で落とす (= 半角スペース /
     * タブ / <b>全角スペース U+3000</b> を等しく区切りとして扱う)。 各語は
     * {@link Locale#ROOT} で小文字化する。
     *
     * @param raw 検索欄の生の値 (null 可)
     * @return 語のリスト (空クエリなら空リスト)。 返り値は変更不可。
     */
    public static List<String> terms(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(4);
        int n = raw.length();
        int i = 0;
        while (i < n) {
            // 区切り (空白類) を読み飛ばす。
            while (i < n && Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            if (i > start) {
                out.add(raw.substring(start, i).toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    /**
     * クエリが実質的に空か (= 語が 1 つも無いか)。
     *
     * <p>
     * 空文字だけでなく<b>空白のみ</b> (半角 / タブ / 全角) も true になる。 呼び出し側は
     * これが true のとき<b>絞り込み描画そのものを行わない</b> — 「全件一致だから結果的に
     * 何も暗くならない」 ではなく、 <b>1 回も描かない</b>ことで検索欄が空のときの描画を
     * 変更前とピクセル等価に保つ。
     *
     * @param raw 検索欄の生の値 (null 可)
     */
    public static boolean isBlank(String raw) {
        if (raw == null || raw.isEmpty()) {
            return true;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (!Character.isWhitespace(raw.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 表示名が全ての語を含むか (= AND 判定)。
     *
     * <p>
     * 語が 0 個 (= 空クエリ) なら常に true (= 全件一致)。 語が 1 個のときは
     * {@code displayName.toLowerCase(ROOT).contains(term)} そのもの、 すなわち
     * <b>v1.0.0 と完全に同一</b>の判定になる。
     *
     * @param displayName アイテムの表示名 ({@code ItemStack#getHoverName().getString()})。 null 可
     * @param terms       {@link #terms(String)} が返した語のリスト
     * @return 全ての語が表示名に部分一致すれば true
     */
    public static boolean matches(String displayName, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return true;
        }
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        String haystack = displayName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < terms.size(); i++) {
            if (!haystack.contains(terms.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生クエリを直接与える版 (= {@link #terms(String)} + {@link #matches(String, List)})。
     *
     * <p>
     * 毎スロット呼ぶと語分割を繰り返すため、 <b>実際の描画ループでは使わない</b>
     * (呼び出し側は {@link #terms(String)} を 1 回だけ評価してループへ持ち込む)。
     * テストと単発の判定用。
     */
    public static boolean matchesRaw(String displayName, String raw) {
        return matches(displayName, terms(raw));
    }
}
