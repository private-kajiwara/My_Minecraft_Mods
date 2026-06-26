package com.kajiwara.worldchange.core;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * {@code /worldChange <名前:シード値>} の引数を解析した結果 (MC 非依存の純粋データ)。
 *
 * <p>仕様の書式は {@code 名前:シード値}。 <b>最初の {@code ':'}</b> を区切りとし、 前=ワールド名、
 * 後=シード token とする (Windows のフォルダ名に {@code ':'} は使えないため最初の区切りで一意)。
 * {@code ':'} が無い・後ろが空なら シード指定なし ({@link #seed()} は空)。
 *
 * <p>シード token は Minecraft 本来の規則 ({@code WorldOptions.parseSeed} 相当) で long 化する:
 * <ul>
 *   <li>空文字 → シードなし (照合に使わない)。</li>
 *   <li>long として解釈できる → その値。</li>
 *   <li>それ以外の文字列 → {@code String.hashCode()} を long にした値 (テキストシードのハッシュ)。</li>
 * </ul>
 * これにより {@code /seed} が表示する数値でも、 ワールド作成時に打ったテキストシードでも一致する。
 */
public record WorldQuery(String name, OptionalLong seed) {

    public WorldQuery {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(seed, "seed");
    }

    /** 名前部分のみ (シードなし) のクエリ。 */
    public static WorldQuery ofName(String name) {
        return new WorldQuery(name.trim(), OptionalLong.empty());
    }

    /**
     * 生入力 {@code "名前:シード値"} を解析する。 名前は trim される。 シードなしなら {@link OptionalLong#empty()}。
     * 入力が空白のみなら名前空文字のクエリ (呼び出し側で空を弾く)。
     */
    public static WorldQuery parse(String raw) {
        if (raw == null) {
            return new WorldQuery("", OptionalLong.empty());
        }
        String s = raw.trim();
        int colon = s.indexOf(':');
        if (colon < 0) {
            return new WorldQuery(s, OptionalLong.empty());
        }
        String name = s.substring(0, colon).trim();
        String seedToken = s.substring(colon + 1).trim();
        return new WorldQuery(name, parseSeed(seedToken));
    }

    /** Minecraft 本来のシード解釈 (long そのまま、 でなければテキストの hashCode)。 空は {@link OptionalLong#empty()}。 */
    public static OptionalLong parseSeed(String token) {
        if (token == null) {
            return OptionalLong.empty();
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(t));
        } catch (NumberFormatException ex) {
            return OptionalLong.of(t.hashCode());
        }
    }

    /** 名前が空 (実質クエリとして無効) か。 */
    public boolean isBlank() {
        return name.isBlank();
    }
}
