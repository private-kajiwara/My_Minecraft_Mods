package com.kajiwara.visualizegate.memory;

/**
 * ㉙ 永続化される<b>確定接続ペア</b>の 1 件 (GSON POJO・プリミティブのみ)。
 *
 * <p>「開いて繋がった」= 両端が記憶済み活性ポータル (スキャナは NETHER_PORTAL = lit のみ検出) かつ resolver が
 * LINKED (理想ターゲット半径内一致・OFFSET 込み) になった OW↔ネザーのペア。 両端の anchor (グローバル最低
 * コーナー) で同一性キー化する。 一旦確定すれば<b>セッションをまたいで永続</b>し、 点群の接続線はこの記録から
 * 再描画される (= 毎セッションの再解決に依存しない)。 どちらかの端が reconcile で記憶除去 (= 破壊/消灯) されたら
 * このペアも剪定される (= 線が嘘にならない)。
 */
public final class MemoryLink {

    public int owX;  // OW 側 anchor
    public int owY;
    public int owZ;
    public int nX;   // ネザー側 anchor
    public int nY;
    public int nZ;
    public long lastConfirmedTick; // 最後に LINKED 確認した tick (鮮度・将来用)

    public MemoryLink() {
        // GSON 用 no-arg ctor
    }

    public MemoryLink(int owX, int owY, int owZ, int nX, int nY, int nZ, long tick) {
        this.owX = owX;
        this.owY = owY;
        this.owZ = owZ;
        this.nX = nX;
        this.nY = nY;
        this.nZ = nZ;
        this.lastConfirmedTick = tick;
    }

    /** 両端 anchor による同一性キー (upsert/照合用)。 */
    public String key() {
        return owX + "," + owY + "," + owZ + "->" + nX + "," + nY + "," + nZ;
    }

    /** 指定 anchor (どちらかの端) を参照しているか (剪定判定用)。 */
    public boolean referencesAnchor(int x, int y, int z) {
        return (owX == x && owY == y && owZ == z) || (nX == x && nY == y && nZ == z);
    }
}
