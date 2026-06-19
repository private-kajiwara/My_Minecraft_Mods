package com.kajiwara.visualizegate.ui;

import com.kajiwara.visualizegate.domain.GateState;

/**
 * Mod カラー (ARGB・中央集約)。 全 UI (ModMenu 設定画面 / in-game メニュー / 隅アイコン) が
 * ここを参照する。 視覚のみ・挙動には影響しない。 値はユーザー確定値 (調整可)。
 */
public final class GateColors {

    private GateColors() {
    }

    // ── ㉜ ゲート 5 状態色 (点群画面と世界 UX が<b>共有する単一定義</b>・状態色はここ一本)。
    // 正常(緑)/片側(灰)/ズレ(黄)/未接続=新規生成(橙)/競合(赤)。 旧 LINK_GREEN/RED の status 用途を統一。
    // 配列順は {@link GateState} の ordinal (OK/ORPHAN/OFFSET/WILL_CREATE/CONFLICT) と一致。
    /** 正常: 活性＋整合リンク。 */
    public static final int STATE_OK = 0xFF55E07A;
    /** 片側: 対応の手掛かりが無い。 */
    public static final int STATE_ORPHAN = 0xFF9AA0A6;
    /** ズレ: リンクはあるが理想ターゲットから大きくずれる。 */
    public static final int STATE_OFFSET = 0xFFF5D742;
    /** 未接続: 対応ゲートが無く通ると新規生成 (橙＝悪いではなく状態)。 */
    public static final int STATE_WILL_CREATE = 0xFFF59A42;
    /** 競合: 交差/非対称 (先に通った側が繋がり他方はズレ/新規)。 */
    public static final int STATE_CONFLICT = 0xFFE0556B;

    private static final int[] STATE = { STATE_OK, STATE_ORPHAN, STATE_OFFSET, STATE_WILL_CREATE, STATE_CONFLICT };

    /** ㉜ {@link GateState} → 状態色 (点群画面・世界カード/凡例/リンク線が共通参照)。 */
    public static int forState(GateState s) {
        return STATE[s.ordinal()];
    }

    /** ㉜ ordinal 指定の状態色 (範囲外は MAIN フォールバック)。 */
    public static int forStateOrdinal(int ord) {
        return (ord >= 0 && ord < STATE.length) ? STATE[ord] : MAIN;
    }

    /** ベース背景 (最暗)。 */
    public static final int BASE = 0xFF0F0A17;
    /** パネル背景 (サイドバー等)。 */
    public static final int PANEL = 0xFF1A1326;
    /** メイン (紫＝ポータル)。 */
    public static final int MAIN = 0xFF8E3BE6;
    /** メイン暗 (仕切り/サブ)。 */
    public static final int MAIN_DIM = 0xFF5E2A99;
    /** アクセント (金)。 */
    public static final int ACCENT = 0xFFF5C542;
    /** テキスト。 */
    public static final int TEXT = 0xFFECE7F2;

    /** HUD 隅アイコンの半透明背景 (BASE を ~75% alpha 化＝視界を塞がない)。 */
    public static final int HUD_BG = 0xC00F0A17;

    // ── 設定画面 (OmniChest 風フラット) 専用 (視覚のみ) ──
    /** 設定画面の暗スクリム (背後のゲーム/HUD を沈める・ほぼ不透明)。 */
    public static final int SCRIM = 0xE60B0712;
    /** 補助テキスト (説明文/サブラベル・暗め)。 */
    public static final int SUBTEXT = 0xFF9A93A8;
    /** セクション見出しの淡い藤色 (左サイドバー)。 */
    public static final int SECTION = 0xFFB9A8D6;
    /** 選択中タブの控えめな紫帯 (低 alpha の MAIN)。 */
    public static final int SELECT_BAND = 0x558E3BE6;
    /** ドロップダウン矩形のほぼ黒背景。 */
    public static final int DROPDOWN_BG = 0xFF0A0710;

    /** 機能1 ホログラム v2: ポータル内部面の半透明な紫塗り (MAIN を低 alpha 化＝金枠を主張させる)。 */
    public static final int HOLO_FILL = 0x668E3BE6;

    // ── 機能3 探索ドーム (検索範囲＝シアン / 混線＝警告オレンジ) ──
    /** 探索ドームのワイヤフレーム色 (シアン＝検索範囲・緑/赤/金/枠マゼンタと判別可能)。 */
    public static final int DOME = 0xFF49C0E0;
    /** 混線強調色 (警告オレンジ＝範囲内の他ゲート・状態色とは別系統)。 */
    public static final int CROSSTALK = 0xFFFF7A2A;

    // ── /vg back-calculate コマンド専用色 (既存=赤プルイン警告 / 新規=緑) ＋ 中立の淡色テキスト ──
    // ㉜ ゲート状態の配色は STATE_* に一本化済。 ここは back-calculate ワイヤフレーム ({@code VgCommands})
    // の独自規約 (lang cmd.existing/cmd.new) と、 状態でない<b>中立の淡色テキスト/スウォッチ</b>専用に縮退。
    /** /vg: 新規生成 (理想スポット・緑)。 */
    public static final int LINK_GREEN = 0xFF49D17A;
    /** /vg: 既存ゲート (赤プルイン警告)。 */
    public static final int LINK_RED = 0xFFE0544A;
    /** 中立の淡色 (未観測/補助テキスト・状態色ではない)。 */
    public static final int LINK_GRAY = 0xFFA8A2B2;

    // ── 点群ポップアップ (OW=青緑/teal・ネザー=橙・リンク=紫) ──
    // 高さ配色は「各 dim の色相内で明暗だけ」を変える (青↔橙を反転させない＝色相で dim を一意判別)。
    // OW は teal 単色の暗→明、 ネザーは橙単色の暗→明。
    /** OW 地形点の低い高さ (暗い teal)。 */
    public static final int PC_OW_LOW = 0xFF1F8A99;
    /** OW 地形点の高い高さ (明るい teal/aqua)。 */
    public static final int PC_OW_HIGH = 0xFF4FE3D6;
    /** ネザー地形点の低い高さ (暗橙)。 */
    public static final int PC_NETHER_LOW = 0xFF9C3C0A;
    /** ネザー地形点の高い高さ (明橙)。 */
    public static final int PC_NETHER_HIGH = 0xFFFF9A45;
    /** ゲートリンク線 (紫＝ゲート間の水平ズレ)。 */
    public static final int PC_LINK = 0xFF8E3BE6;
}
