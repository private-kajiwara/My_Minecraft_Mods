package com.kajiwara.visualizegate.state;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.kajiwara.visualizegate.domain.PortalDimension;

/**
 * ㉕ `/vg back-calculate` が積む<b>予測ワイヤーフレーム要素</b>のクライアント保持 (描画マネージャ)。
 *
 * <p>各要素は<b>絶対ブロック座標</b> (中心) ＋所属ディメンション＋色を持つ。 在世界描画
 * ({@code BackCalcRenderer}) は<b>現在ディメンションに属する要素のみ</b>を、 点群スタックビュー
 * ({@code PointCloudScreen}) は<b>全要素</b>を ÷8＋表示スケールの同じ変換で描く。
 *
 * <p><b>自動消滅しない</b>: `/vg clean` ({@link #clear()}) でのみ消える (プレイヤーの意志で消す設計)。
 * 読み取りが描画スレッド・書き込みがコマンドスレッドのため {@link CopyOnWriteArrayList} で保持。
 */
public final class BackCalcStore {

    /** 予測要素 1 件 (絶対ブロック座標・中心・所属次元・ARGB 色・既存/新規・任意の在世界ピンラベル)。 */
    public static final class Element {
        public final PortalDimension dim;
        public final double x;
        public final double y;
        public final double z;
        public final int colorArgb;
        /** true=既存ゲートへの吸い込み警告 (赤) / false=新規建設推奨 (緑)。 */
        public final boolean existing;
        /** 任意: ボックス上に出す在世界ピンの 1 行テキスト (null=ピン無し＝back-calculate の従来挙動)。 */
        public final String label;
        /** ピンラベルの ARGB 色 (label!=null のときのみ有効)。 */
        public final int labelColorArgb;
        /**
         * 任意: &gt;0 なら通常の小ボックスでなく、 (x,z) 中心・半幅 {@code squareHalf} の<b>平たい正方形リング</b>
         * (排他ゾーン footprint) として描く (resolve-conflict の赤/橙ゾーン)。 0=従来の小ボックス。
         */
        public final double squareHalf;
        /**
         * 任意: この要素を積んだ<b>所有ゲートのキー</b> (dim+anchor・{@code null}=ゲート非依存=back-calculate)。
         * {@code /vg clean <gate-name>} が owner 一致の要素だけ消すために使う。 描画には影響しない。
         */
        public String ownerKey;

        public Element(PortalDimension dim, double x, double y, double z, int colorArgb, boolean existing) {
            this(dim, x, y, z, colorArgb, existing, null, 0, 0.0);
        }

        public Element(PortalDimension dim, double x, double y, double z, int colorArgb, boolean existing,
                String label, int labelColorArgb) {
            this(dim, x, y, z, colorArgb, existing, label, labelColorArgb, 0.0);
        }

        public Element(PortalDimension dim, double x, double y, double z, int colorArgb, boolean existing,
                String label, int labelColorArgb, double squareHalf) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.colorArgb = colorArgb;
            this.existing = existing;
            this.label = label;
            this.labelColorArgb = labelColorArgb;
            this.squareHalf = squareHalf;
        }

        /** 排他ゾーンの平たい正方形リング要素 (中心 (cx,cz)・半幅 half・指定 Y・色)。 ピン無し。 */
        public static Element square(PortalDimension dim, double cx, double y, double cz,
                double half, int colorArgb) {
            return new Element(dim, cx, y, cz, colorArgb, true, null, 0, half);
        }

        /** 所有ゲートキーを付与して自身を返す (チェーン用・追加前に呼ぶ)。 */
        public Element withOwner(String key) {
            this.ownerKey = key;
            return this;
        }
    }

    private static final CopyOnWriteArrayList<Element> ELEMENTS = new CopyOnWriteArrayList<>();

    /** add/clear で増える版番号 (点群 Screen が変化検出して VBO/投影キャッシュを再構築する)。 */
    private static volatile int version = 0;

    private BackCalcStore() {
    }

    public static void add(Element e) {
        ELEMENTS.add(e);
        version++;
    }

    /** `/vg clean` 用: 全要素を消す (自動タイムアウトはしない)。 */
    public static void clear() {
        ELEMENTS.clear();
        version++;
    }

    /** `/vg clean <gate-name>` 用: 指定 owner キーの要素だけ消し、 消した件数を返す (0=該当なし)。 */
    public static int clearOwner(String ownerKey) {
        if (ownerKey == null) {
            return 0;
        }
        int before = ELEMENTS.size();
        ELEMENTS.removeIf(e -> ownerKey.equals(e.ownerKey));
        int removed = before - ELEMENTS.size();
        if (removed > 0) {
            version++;
        }
        return removed;
    }

    /** 指定 owner キーの要素が現在 store にあるか (サジェスト用)。 */
    public static boolean hasOwner(String ownerKey) {
        if (ownerKey == null) {
            return false;
        }
        for (Element e : ELEMENTS) {
            if (ownerKey.equals(e.ownerKey)) {
                return true;
            }
        }
        return false;
    }

    /** 変化検出用の版番号 (add/clean で単調増加)。 */
    public static int version() {
        return version;
    }

    public static boolean isEmpty() {
        return ELEMENTS.isEmpty();
    }

    public static int size() {
        return ELEMENTS.size();
    }

    /** 読み取り専用ビュー (CopyOnWrite なので反復中の add/clear も安全)。 */
    public static List<Element> all() {
        return ELEMENTS;
    }
}
