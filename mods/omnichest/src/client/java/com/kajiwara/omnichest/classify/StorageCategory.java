package com.kajiwara.omnichest.classify;

import com.kajiwara.omnichest.i18n.Keys;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import net.minecraft.network.chat.Component;

/**
 * 倉庫 (チェスト) の用途カテゴリ。
 *
 * <p>
 * このカテゴリは ChestClassifier がスコアベースで自動推定する
 * 「この倉庫は何用か」の結果値であり、 GUI 表示・自動投入の振り分け・検索フィルタの
 * いずれにも使う中心 enum である。
 *
 * <p>
 * 拡張方針:
 * <ul>
 * <li>新カテゴリを追加するときは、ここに enum 値を増やしてから
 * {@link ScoreRules} 側に対応するスコアルールを足す。</li>
 * <li>UI 表示文字列 (日本語) と HUD 色を持つ。
 * 色は ARGB の RGB 部分 (= 0x00RRGGBB) で、 alpha は描画側が決める。</li>
 * <li>{@link #MIXED} は「複数カテゴリが拮抗していてどれにも寄せられない」の意味。
 * {@link #UNKNOWN} は「中身が空 / カテゴリ判定不能」の意味。</li>
 * </ul>
 *
 * <p>
 * 巨大な switch 文を避けるため、本 enum は「データのみ」を持ち、
 * 判定ロジックは {@link CategoryScorer} / {@link ScoreRules} 側に寄せている。
 */
public enum StorageCategory {

    BUILDING("建築ブロック倉庫", 0xA0A0A0),
    // ── BUILDING の下位区分 = 「石材の材質軸」 (= 傘 BUILDING はそのまま残す) ──
    //   ChestClassifier が「建築ブロック一族が 1 位」と判定したときだけ、
    //   さらに内訳が特定の材質へ偏っていれば傘の代わりにこれらを表示する。
    //   詳細は ChestClassifier の 2 フェーズ判定を参照。
    //   非石材の建材 (木材/羊毛/コンクリート/ガラス/テラコッタ) には一切加点しないので、
    //   それらの箱はフェーズ 2 の PRESENCE を割って従来どおり傘 BUILDING のままになる。
    BUILDING_STONE("石・丸石倉庫", 0x9A9A9A),
    BUILDING_GRANITE("花崗岩倉庫", 0xB0705A),
    BUILDING_DIORITE("閃緑岩倉庫", 0xD8D8D0),
    BUILDING_ANDESITE("安山岩倉庫", 0x8A8C8A),
    BUILDING_DEEPSLATE("深層岩倉庫", 0x4A4A50),
    BUILDING_TUFF("凝灰岩・方解石倉庫", 0x7C7D6E),
    BUILDING_SANDSTONE("砂岩倉庫", 0xDCCFA0),
    BUILDING_PRISMARINE("プリズマリン倉庫", 0x5FA8A0),
    BUILDING_MUD_BRICK("泥レンガ倉庫", 0x9C7B5E),
    /**
     * 石材の中間層 = 「石材ではあるが材質が 1 つに寄っていない」。
     *
     * <p>
     * これは <b>分類結果としてのみ出る集約バッジ</b> であり、 プレイヤーが手動で選ぶ意味を持たない。
     * よって {@link #MIXED} / {@link #UNKNOWN} と同じく {@link #isConcrete()} が false を返し、
     * 手動割り当ての候補 (カテゴリ設定 / 投入先 / タブ一覧) には並ばない。
     */
    BUILDING_STONE_MIXED("石材混合倉庫", 0x8F8B80),
    WOOD("木材倉庫", 0x9B6B3F),
    ORE("鉱石倉庫", 0xC0C0C0),
    REDSTONE("レッドストーン倉庫", 0xD63A3A),
    // ── REDSTONE の下位区分 (= 傘 REDSTONE はそのまま残す) ──
    //   ChestClassifier が「レッドストーン一族が 1 位」と判定したときだけ、
    //   さらに内訳が偏っていれば傘の代わりにこれらを表示する。
    //   詳細は ChestClassifier の 2 フェーズ判定を参照。
    REDSTONE_CIRCUIT("回路倉庫", 0xD64545),
    REDSTONE_TRANSPORT("搬送倉庫", 0xC85A2E),
    REDSTONE_MOVEMENT("移動倉庫", 0xB0603A),
    REDSTONE_TRAP("トラップ倉庫", 0xA83A5A),
    FOOD("食料倉庫", 0xF4B860),
    FARM("農業倉庫", 0x6FBF3A),
    COMBAT("戦闘装備倉庫", 0xB23B3B),
    TOOL("道具倉庫", 0x6FA0D8),
    POTION("ポーション倉庫", 0xB05DF5),
    NETHER("ネザー素材倉庫", 0x842A2A),
    END("エンド素材倉庫", 0x6E59B0),
    MAGIC("エンチャント素材倉庫", 0xD862E0),
    MOB_DROP("モブドロップ倉庫", 0x88AA66),
    DECORATION("装飾倉庫", 0xE0C97F),
    MIXED("混合倉庫", 0x8E8E8E),
    UNKNOWN("未分類", 0x606060);

    private final String displayName;
    private final int rgb;

    StorageCategory(String displayName, int rgb) {
        this.displayName = displayName;
        this.rgb = rgb;
    }

    /**
     * GUI 用の表示名 (= fallback 文字列。 翻訳が無い環境ではこれがそのまま出る)。
     * 翻訳対応版が必要な呼び出し側は {@link #displayComponent()} を使うこと。
     */
    public String displayName() {
        return displayName;
    }

    /**
     * GUI 用の表示名を翻訳キーで解決した {@link Component}。
     * 翻訳キーは {@code omnichest.storage_category.<lower_name>}。
     */
    public Component displayComponent() {
        return OmniChestLocale.get(
                Keys.STORAGE_CATEGORY_PREFIX + name().toLowerCase(java.util.Locale.ROOT),
                this.displayName);
    }

    /** カテゴリの代表色 (0x00RRGGBB)。 alpha は呼び出し側が付与する。 */
    public int rgb() {
        return rgb;
    }

    /**
     * 「分類結果として有効か」 = MIXED / UNKNOWN / BUILDING_STONE_MIXED ではないか。
     * 自動投入の振り分け先として選んで良いかの判定に使う。
     *
     * <p>
     * {@link #BUILDING_STONE_MIXED} をここに含めるのは、 それが
     * 「複数の石材が混ざっている」 という <i>集約</i> であって手動で選ぶ行き先ではないため。
     * 既存の MIXED / UNKNOWN と同じ除外機構に乗せるだけで、 新しい仕組みは足していない。
     */
    public boolean isConcrete() {
        return this != MIXED && this != UNKNOWN && this != BUILDING_STONE_MIXED;
    }
}
