package com.kajiwara.hyperslice.core;

/**
 * 4 次元エンティティの型定義。
 *
 * <p><b>調整用の数値はここに集約する</b> (人間が後で触る場所)。
 * v0.2 マイルストーン1 はテスト用の 1 種のみ。
 *
 * <p>{@code wThickness} と w 速度の比が体験の一次判定を決める:
 * 速すぎると一瞬で消え、 遅すぎると変化に気づかない。
 * 既定値は「点 → 膨張 → 収縮 → 点」の一往復が数秒で読める比にしてある。
 */
public enum HyperEntityType {

    /**
     * テスト用の漂う 4 次元球。 重力なし・地形衝突なし・AI なし (v0.2 M1)。
     *
     * <p>{@code wThickness=2.0} なので w 方向の半径は 1.0。
     * 既定 w 速度 {@code 0.02/tick} = 0.4/秒 なので、
     * dw が {@code -1 → +1} を通過するのに 100 tick = <b>5 秒</b>。
     * その間に断面半径は 0 → 1.0 → 0 と変化する。
     */
    DRIFTER("drifter", 2.0, 0xFF5FA8FF);

    // ─── 調整用定数 (人間が触るのはここ) ───────────────────────

    /** {@code /hyperentity spawn} で w を省略したときの既定 w 速度 [w/tick]。 */
    public static final double DEFAULT_W_VELOCITY = 0.02;

    /** 球の見た目の大きさの倍率。 断面半径 [w単位] → 描画半径 [ブロック] の換算。 */
    public static final double RENDER_SCALE = 1.0;

    /** 断面半径がこれ未満なら描画しない [ブロック]。 極小の点がチラつくのを防ぐ。 */
    public static final double MIN_RENDER_RADIUS = 0.02;

    // ────────────────────────────────────────────────────────

    private final String id;
    private final double wThickness;
    private final int argb;

    HyperEntityType(String id, double wThickness, int argb) {
        this.id = id;
        this.wThickness = wThickness;
        this.argb = argb;
    }

    /** 直列化・コマンド引数に使う安定した ID。 {@link #name()} には依存しない。 */
    public String id() {
        return id;
    }

    /** w 方向の厚み (直径)。 断面半径の計算に使う ({@link CrossSection#radius})。 */
    public double wThickness() {
        return wThickness;
    }

    /** 描画色 (ARGB)。 アルファは常に不透明にすること (断面はスケールのみで表現する)。 */
    public int argb() {
        return argb;
    }

    /** 翻訳キー。 */
    public String translationKey() {
        return "hyperslice.entity." + id;
    }

    /** ID から型を引く。 未知なら {@code null}。 */
    public static HyperEntityType byId(String id) {
        for (HyperEntityType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** 直列化用の序数 → 型。 範囲外なら {@code null} (壊れたパケットで例外を投げない)。 */
    public static HyperEntityType byOrdinal(int ordinal) {
        HyperEntityType[] all = values();
        return (ordinal >= 0 && ordinal < all.length) ? all[ordinal] : null;
    }
}
