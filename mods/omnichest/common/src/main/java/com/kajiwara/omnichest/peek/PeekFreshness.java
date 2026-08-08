package com.kajiwara.omnichest.peek;

/**
 * コンテナ ピーク ポップアップに出す 「いつ観測した中身か」 (= 鮮度) を、
 * 経過ミリ秒から <b>翻訳キー + 数値引数</b> へ写す純粋関数。 Minecraft 型に一切依存しないため
 * {@code common} 側に置き、 単体テスト可能にしている ({@link ContainerPeekFit} /
 * {@code GuiScaleFit} / {@code SidePanelFit} / {@code ExistingCategoriesFit} /
 * {@code TextContrastFit} と同じ流儀)。
 *
 * <p>
 * <b>なぜ必要か</b>: ピーク機能が出す中身は <b>「最後にそのコンテナを開いたときのスナップショット」</b>
 * であって、 今この瞬間の中身ではない (バニラはコンテナの中身をクライアントへ同期しないので、
 * 原理的にこれ以上は取れない)。 古い情報が最新に見える表示は嘘になるため、
 * <b>鮮度は必ず併記する</b>。 その文言化を 1 か所に集約するのが本クラス。
 *
 * <p>
 * <b>不変条件 (invariant)</b>:
 * <ul>
 *   <li><b>負の経過時間を出さない</b>。 元データ ({@code ContainerSnapshot.lastSeenMillis}) は
 *       {@code System.currentTimeMillis()} = <b>壁時計</b> なので、 システム時刻の変更・タイムゾーン跨ぎ・
 *       別 PC で保存したキャッシュの持ち込みなどで {@code now < lastSeen} が起こりうる。
 *       {@link #elapsedMs} が {@code max(0, now - lastSeen)} にクランプし、
 *       「-3 分前」 のような破綻表示を構造的に排除する。</li>
 *   <li><b>上限がある</b>。 {@link #LONG_AGO_DAYS} 日以上は個別の日数を出さず
 *       {@code long_ago} (= 「30 日以上前」) に丸める。 「412 日前」 のような無意味に長い
 *       数値でパネル幅が暴れるのを防ぐ。</li>
 *   <li><b>単調</b>。 経過時間が増えたときに表示単位が戻ることはない
 *       ({@code just_now → minutes → hours → days → long_ago} の一方向)。</li>
 * </ul>
 *
 * <p>
 * <b>外すと何が壊れるか</b>: 呼び出し側が自前で経過時間を割り算し始めると、 単体テストが
 * 実挙動を保証しなくなる (= 負値クランプや境界の取りこぼしが再発する)。
 * 鮮度の文言化は<b>必ずここを通す</b>こと。
 */
public final class PeekFreshness {

    private PeekFreshness() {
    }

    // ════════════════════════════════════════════════════════════════════
    // 時間の単位 (ms)
    // ════════════════════════════════════════════════════════════════════

    public static final long SECOND_MS = 1000L;
    public static final long MINUTE_MS = 60L * SECOND_MS;
    public static final long HOUR_MS = 60L * MINUTE_MS;
    public static final long DAY_MS = 24L * HOUR_MS;

    /** これ以上古い観測は日数を出さず 「N 日以上前」 に丸める閾値 (日)。 */
    public static final int LONG_AGO_DAYS = 30;
    /** {@link #LONG_AGO_DAYS} の ms 表現。 */
    public static final long LONG_AGO_MS = LONG_AGO_DAYS * DAY_MS;

    // ════════════════════════════════════════════════════════════════════
    // 翻訳キー (= lang en_us / ja_jp に実在するキーと 1:1)
    // ════════════════════════════════════════════════════════════════════

    public static final String KEY_JUST_NOW = "omnichest.peek.age.just_now";
    public static final String KEY_MINUTES = "omnichest.peek.age.minutes";
    public static final String KEY_HOURS = "omnichest.peek.age.hours";
    public static final String KEY_DAYS = "omnichest.peek.age.days";
    public static final String KEY_LONG_AGO = "omnichest.peek.age.long_ago";

    /** 表示に使う時間の粒度。 経過が増えるほど粗くなる (= 一方向)。 */
    public enum Unit {
        /** 1 分未満。 数値引数なし。 */
        JUST_NOW,
        /** 1 分以上 1 時間未満。 引数 = 分。 */
        MINUTES,
        /** 1 時間以上 1 日未満。 引数 = 時。 */
        HOURS,
        /** 1 日以上 {@link #LONG_AGO_DAYS} 日未満。 引数 = 日。 */
        DAYS,
        /** {@link #LONG_AGO_DAYS} 日以上。 引数 = {@link #LONG_AGO_DAYS} (= 「30 日以上前」)。 */
        LONG_AGO
    }

    /**
     * 鮮度の表示指示。
     *
     * @param unit         粒度
     * @param key          翻訳キー
     * @param enFallback   en_us が引けない場合のフォールバック文字列 (= {@code OmniChestLocale.get} の第 2 引数)
     * @param amount       数値引数。 {@link Unit#JUST_NOW} では意味を持たない (= 0)
     * @param hasAmount    {@code key} が {@code %1$d} を要求するか (= false なら引数を渡さない)
     */
    public record Label(Unit unit, String key, String enFallback, int amount, boolean hasAmount) {
    }

    // ════════════════════════════════════════════════════════════════════
    // 本体
    // ════════════════════════════════════════════════════════════════════

    /**
     * 壁時計の 2 点から経過 ms を求める。 <b>負にならない</b> (= {@code max(0, now - lastSeen)})。
     *
     * <p>
     * オーバーフロー安全: {@code now - lastSeen} を素で引くと極端な値 (= 破損キャッシュ) で
     * long がラップしうるため、 先に比較してから引く。
     */
    public static long elapsedMs(long nowMillis, long lastSeenMillis) {
        if (nowMillis <= lastSeenMillis) {
            return 0L;
        }
        return nowMillis - lastSeenMillis;
    }

    /** {@link #elapsedMs} と {@link #label(long)} をまとめたもの (= 呼び出し側の定型を 1 行にする)。 */
    public static Label labelFor(long nowMillis, long lastSeenMillis) {
        return label(elapsedMs(nowMillis, lastSeenMillis));
    }

    /**
     * 経過 ms を表示指示へ写す。 負値は 0 として扱う (= {@link Unit#JUST_NOW})。
     *
     * <p>
     * 境界は <b>すべて「以上」で切り替わる</b>: 59,999ms は {@code JUST_NOW}、 60,000ms ちょうどで
     * {@code MINUTES}(1)。 以下同様に 1 時間ちょうどで {@code HOURS}(1)、 1 日ちょうどで
     * {@code DAYS}(1)、 30 日ちょうどで {@code LONG_AGO}。
     */
    public static Label label(long ageMs) {
        long age = Math.max(0L, ageMs);
        if (age < MINUTE_MS) {
            return new Label(Unit.JUST_NOW, KEY_JUST_NOW, "just now", 0, false);
        }
        if (age < HOUR_MS) {
            return new Label(Unit.MINUTES, KEY_MINUTES, "%1$d min ago", (int) (age / MINUTE_MS), true);
        }
        if (age < DAY_MS) {
            return new Label(Unit.HOURS, KEY_HOURS, "%1$d h ago", (int) (age / HOUR_MS), true);
        }
        if (age < LONG_AGO_MS) {
            return new Label(Unit.DAYS, KEY_DAYS, "%1$d d ago", (int) (age / DAY_MS), true);
        }
        return new Label(Unit.LONG_AGO, KEY_LONG_AGO, "over %1$d d ago", LONG_AGO_DAYS, true);
    }
}
