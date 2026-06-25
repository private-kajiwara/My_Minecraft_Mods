package com.kajiwara.visualizegate.ui;

import java.util.Arrays;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.kajiwara.visualizegate.config.GateConfigManager;
import com.kajiwara.visualizegate.domain.GateConflict;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.pointcloud.GateMeta;
import com.kajiwara.visualizegate.pointcloud.PointCloudAnalysis;
import com.kajiwara.visualizegate.pointcloud.PointCloudSnapshot;
import com.kajiwara.visualizegate.state.BackCalcStore;
import com.kajiwara.visualizegate.state.PointCloudViewState;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.client.render.PointCloudGpuRenderer;
import com.mojang.blaze3d.platform.NativeImage;
//? if >=26.1 {
import com.mojang.blaze3d.textures.GpuTextureView;
//?}
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * ディメンション点群マッピング・ポップアップ (回転可能な GUI 内 3D ビュー・<b>Mixin 不使用</b>)。
 *
 * <p><b>描画方式</b>: 不変スナップショット ({@link PointCloudSnapshot}) の各点を、 オービットカメラ
 * (yaw/pitch/distance) の<b>プレーン Java 行列</b>で 2D へ投影する (行列/頂点の MC API 不使用＝版差レンダ面ゼロ)。
 * <b>バッチ描画</b>: 投影後の全点を<b>ネイティブ解像度 (guiScale 倍) の {@link DynamicTexture}</b> へ丸いソフトドット
 * (円形アルファ減衰) として CPU ラスタライズし、 <b>毎フレーム 1 回の {@code g.blit}</b> で出す
 * (= fills/frame が点数に依存しない・GUI の {@code RenderPipelines.GUI_TEXTURED}/{@code DynamicTexture}/
 * {@code NativeImage} は全版同名・javap 確認)。 ラスタライズはビュー変化時のみ (静止は blit だけ＝最軽量)。
 * リンク線/現在地マーカーも同テクスチャへ焼く。 万一テクスチャ経路が失敗したら従来の {@code g.fill} へ
 * 自動フォールバック (描画途絶しない)。 深度ソート (降順) で近点を上に、 近いほど大きく明るい (= 深度手がかり)。
 *
 * <p><b>整列</b>: ⑥ ネザー点/リンク端はスナップショット側で 1:1 自然スケール・ネザー重心センタリング済
 * (OW の約 1/8 の塊＝"Nether 1:8")。 <b>垂直分離</b>
 * (ディメンション間隔スライダ) はここで Y へ加算する (= スライダ変更で再解析不要)。 クリップは
 * ビューポート矩形の<b>手動境界判定</b> (scissor 不使用＝版差なし)。
 *
 * <p>入力: ビューポート上ドラッグ=回転、 ホイール=ズーム。 トグル 3 種とスライダは
 * {@link PointCloudViewState} を読み書きし {@link GateConfigManager#save()} で即永続化。
 */
public class PointCloudScreen extends Screen {

    private static final int HEADER_H = 28;
    private static final int FOOTER_H = 34;
    /** ㉞ ビューポートの最小幅 (スプリッターで詰めても確保＝GPU3D アスペクト破綻防止)。 */
    private static final int MIN_VP = 220;
    /** ㉞ サイドバー幅 (右パネル)。 スプリッターのドラッグで可変・config 永続 (旧 SIDEBAR_W=200 定数を可変化)。 */
    private int sidebarW = PointCloudViewState.SIDEBAR_W_DEFAULT;
    private static final int MARGIN = 8;
    private static final int SIDE_PAD = 4;     // ⑧ サイドバー内テキスト/スライダの左右インセット

    /** 点の最小ピクセル径 (最遠点)。 細かいドット感のため 1px。 */
    private static final int POINT_MIN_PX = 1;
    /**
     * 最近点で最小径に加算する追加ピクセル (近=POINT_MIN_PX+EXTRA、 遠=POINT_MIN_PX)。 1〜2px。
     * サイズは深度の二乗カーブで割り当てる (= 大半の点は 1px コア・最近のみ 2px)＝モックの細かいドット感。
     * <b>注</b>: {@code g.fill} は整数ピクセル矩形なので、 サブピクセル/float 位置や真円スプライトは
     * この描画方式では不可能 (テクスチャ blit は全版 {@code RenderPipeline} 必須＝版差/Mixin リスクで不採用)。
     * よって「px 基準で極小＋距離フェード＋丸近似」でドットグリッド感を最小化する。
     */
    private static final int POINT_SIZE_EXTRA = 1;
    /** ⑲ GPU3D リンク線 (角柱) の半幅＝雲半径×比。 細い<b>ワイヤー</b>＝重なっても不透明な塊にならず個々を見分けられる
     *  (旧 0.0016＝太いソリッドバーで重なると判別不能だった)。 太さ調整はここ。 */
    private static final float GPU_LINK_W_FRAC = 0.0006f;
    // ㉔ ゲートマーカー＝<b>黒曜石ネザーポータルの形</b> (縦長の中空矩形フレーム・X–Y 平面の固定軸既定向き・法線 Z)。
    // 外形 幅:高さ ≈ 4:5 (黒曜石枠 4×5 のシルエット)。 サイズは<b>雲半径相対の固定マーカーサイズ</b>＝ズーム/÷8 でも
    // 消えない (位置のみ ÷8＋per-dim 表示スケールに追従)。 後微調整はこの 3 定数で。
    // ㊽ ゲートマーカー縮小＋細線ワイヤー化: クラスタ (多ゲートが重なる) でも各ゲートを見分けられるよう、
    //    外形を小さく・バー/格子を細く (旧 solid 寄り 0.022/0.0176/0.0026/0.0014 → 約 0.6 倍)。 外形 4:5・色/格子本数は不変。
    /** ㉔/㉙ GPU3D ゲートフレーム外形の<b>半高</b>＝雲半径×比 (縦長)。 */
    private static final float GPU_GATE_FRAME_HALF_H_FRAC = 0.013f;
    /** ㉔/㉙ GPU3D ゲートフレーム外形の<b>半幅</b>＝雲半径×比 (= 半高 ×0.8 ＝外形 4:5)。 */
    private static final float GPU_GATE_FRAME_HALF_W_FRAC = 0.0104f;
    /** ㉔/㉙ GPU3D ゲートフレームの<b>枠バー半幅</b>＝雲半径×比 (細線ワイヤー)。 */
    private static final float GPU_GATE_BAR_W_FRAC = 0.0013f;
    /** ㉙ GPU3D ゲート内側格子バーの半幅＝雲半径×比 (枠より細く・ポータル面の手がかり)。 */
    private static final float GPU_GATE_GRID_W_FRAC = 0.0009f;
    /** ㉙ ゲートマーカーの色 (PC_LINK より明るい紫＝一目でゲートと分かる・枠+内側格子に使用)。 */
    private static final int GATE_FRAME_COLOR = 0xFFB57BFF;
    /** ⑲ GPU3D 現在地マーカー (金ワイヤー十字) の腕長＝雲半径×比。 */
    private static final float GPU_MARKER_ARM_FRAC = 0.03f;
    /** ⑲ GPU3D 現在地マーカー (金ワイヤー十字) の半幅＝雲半径×比 (細く)。 */
    private static final float GPU_MARKER_W_FRAC = 0.0022f;
    /** 最遠点の明るさ係数 (大気遠近: 遠い点を暗く沈ませる・近点=1.0)。 モック寄せで強めのフェード。 */
    private static final float DEPTH_DIM_MIN = 0.3f;
    /** ⑤ ディメンション色ティント: ブロック色へ混ぜる dim 色とブレンド率 (淡く＝判別補助)。 */
    private static final int DIM_TINT_OW = GateColors.PC_OW_HIGH;
    private static final int DIM_TINT_NETHER = GateColors.PC_NETHER_HIGH;
    private static final float DIM_TINT_FRAC = 0.15f;
    /** ㉔/㉙/㊽ ゲートマーカー (黒曜石ポータル枠) の<b>半幅/半高</b> (論理px・×SSでネイティブ)。 縦長 4:5。
     *  ㊽ GPU3D の縮小に合わせ texbatch も小さく (旧 3.5/5.0)＝両経路で同等の小型マーカー。 */
    private static final float GATE_FRAME_HALF_W = 2.5f; // 外形幅 ≈5px
    private static final float GATE_FRAME_HALF_H = 3.5f; // 外形高 ≈7px (縦長)
    /** ㉙ texbatch ゲート内側のポータル面フィルのアルファ (低め＝地形/点を透かす控えめα)。 */
    private static final int GATE_FILL_ALPHA = 0x4D; // ~30%
    /** ⑫ リンク線の太さ (固定ネイティブpx・SSに乗算しない)。 最細で鮮明＝1。 */
    private static final int LINK_THICK_NATIVE = 1;
    private static final double DRAG_SENS = 0.012;
    private static final double NEAR = 0.1;

    private final Screen parent;

    // ── カメラ ── (既定はモックアップ2枚に寄せた・やや見上げ＆側面寄りで縦分離と扇状収束が出る角度)
    private float yaw = 0.6f;
    private float pitch = 0.32f;
    private float distance = 200f;
    private float focal = 400f;
    // ㊽B 注視点パン (中ボタンドラッグ・頂点バッファ空間の look-at オフセット)。 既定 0=原点オービット
    //     (= 従来挙動)。 yaw/pitch/distance と同流儀の instance 状態＝閉じたらリセット (config 永続なし)。
    //     GPU3D は render の 8 引数 center 版へ、 software は project/projectXY/projectToScreen で減算。
    private float panX = 0f;
    private float panY = 0f;
    private float panZ = 0f;
    /** distance を自動フレームした対象スナップショット (参照同一性で 1 回だけ枠合わせ)。 */
    private PointCloudSnapshot framedFor;

    // ── 入力ドラッグ ──
    private enum Drag {
        NONE, ROTATE, PAN, SLIDER, DETAIL, POINTSIZE, SCALE_OW, SCALE_NETHER, LIST_SCROLL, SPLITTER
    }

    private Drag drag = Drag.NONE;

    /** ㉞ スプリッターの掴み判定の半幅 (vp とサイドバーの MARGIN ギャップ＝±MARGIN/2 を埋める)。 */
    private static final int SPLITTER_GRAB_HALF = 4;

    // ── ㉝B 行操作 (短クリック=選択 / ダブルクリック=リネームポップアップ / ドラッグ=スクロール) ──
    /** クリックとドラッグの分離しきい (px)。 これ以上動いたらスクロール扱い (選択しない)。 */
    private static final int CLICK_SLOP = 4;
    private int pressRow = -1;       // 押下中の行 (rowY0/rowWx... の添字)・-1=非押下
    private double pressX;
    private double pressY;
    private boolean pressMoved;      // CLICK_SLOP 超で true (= ドラッグ＝スクロール確定)
    private int pressScroll0;        // 押下時の gatesScroll (ドラッグ移動量の基準)

    // ── レイアウト矩形 (init で算出) ──
    private int vpX;
    private int vpY;
    private int vpW;
    private int vpH;
    private int slX;
    private int slY;
    private int slW;
    private int slH;
    private int sl2Y; // ⑭ 2 本目のスライダ (GPU detail) のトラック Y
    private int sl3Y; // ⑯ 3 本目のスライダ (点サイズ) のトラック Y
    // ㉓ 表示スケール群: OW/ネザーを<b>2 カラム 1 行</b>で並べる (縦を消費せず手狭を解消)。 同一トラック Y・X が左右。
    private int slScaleY;   // スケール 2 本のトラック Y (共通)
    private int slScaleOwX; // 左ハーフトラック X (OW)
    private int slScaleNX;  // 右ハーフトラック X (Nether)
    private int slScaleHalfW; // ハーフトラック幅

    // ── ㉚ タブ UI (View / Gates / Links)。 右パネル内で内容を差替え・パネル幅は不変。 ──
    private enum Tab { VIEW, GATES, LINKS }

    private static final int TABBAR_H = 16;     // タブバー高
    private static final int ROW_H = 13;        // 一覧の 1 行高
    private static final int EYE_W = 11;        // ㉝C 行右端の表示/非表示トグル (目アイコン) 幅
    // 一覧下端に確保する凡例 (5 状態色キー) の帯高。 行の描画下端 (drawGatesList/drawLinksList の bottom) と
    // クリック判定域 (inListArea) の下端をこの分だけ listBottom から引く＝凡例は行の実 bounds 外＝非インタラクティブ。
    private static final int LEGEND_RESERVE_H = 12;
    // ㊿ View タブ縦フィットの行ピッチ範囲。 余裕があれば MAX (=従来見た目), 詰まったら MIN まで圧縮 (それでも
    //    入らぬ極小高は View タブをスクロール化)。 TOG=トグル群 3 行 / SLIDER=スライダ 4 段。 下限は重ならない最小:
    //    トグルはボタン高 20px が辛うじて触れる 20、 スライダはラベル 11+トラック 10=21。
    private static final int TOG_PITCH_MAX = 24;
    private static final int TOG_PITCH_MIN = 20;
    private static final int SLIDER_PITCH_MAX = 32; // 従来 slH(10)+22
    private static final int SLIDER_PITCH_MIN = 21; // slH(10)+11 (ラベル+トラックが重ならない最小)
    private Tab tab = Tab.VIEW;
    private int sbContentX;   // サイドバー左端 (= sbX)
    private int tabBarY;      // タブバー上端
    private int listTop;      // 一覧/View 内容の上端 (タブバー下)
    private int listBottom;   // 一覧の下端 (フッタ手前)
    private int gatesScroll = 0;  // Gates 一覧スクロール (px)
    private int linksScroll = 0;  // Links 一覧スクロール (px)
    // ㊿ View タブのスクロール (px) と総コンテンツ高。 トグル群＋スライダ 4 段が [listTop, listBottom] に収まらぬ
    //    極小高 (高 GUI スケール×フルスクリーン/小窓) で可動。 収まる通常域では clampScroll が 0 へ固定＝従来不変。
    private int viewScroll = 0;
    private int viewContentH = 0;
    // ㉛ 選択は<b>一意キー (ワールド anchor 座標+dim)</b> で保持 (番号は表示用＝採番衝突しても 1 クリック 1 ゲート)。
    private boolean hasSel = false;
    private int selWx;
    private int selWy;
    private int selWz;
    private boolean selNether;
    private boolean showLabels = true; // ㉚C 3D 番号ラベルの表示トグル
    // View タブのトグルボタン参照 (タブ切替で表示/非表示)。 末尾に Capture トグルを含む。
    private final java.util.List<Button> viewWidgets = new java.util.ArrayList<>();
    // 空状態 (capture OFF) の中央 [キャプチャを有効化] ボタン。 viewWidgets とは別管理＝可視は描画パスで
    // 現在の解析状態 (空 && capture OFF) から毎フレーム算出 (タブ非依存・vp 中央に出す)。
    private Button captureEnableBtn;
    // ディスク蓄積の透明性: 初回有効化時に 1 度だけログ通知 (セッション単位・非永続)。
    private static boolean captureNoticeLogged = false;
    // ㉛ 一覧の行 hit 矩形 (クリック選択用・描画時に確定)。 行→ゲートの<b>ワールド anchor</b>+dim+y を平行配列で。
    private int[] rowWx = new int[0];
    private int[] rowWy = new int[0];
    private int[] rowWz = new int[0];
    private int[] rowNether = new int[0];
    private int[] rowNumber = new int[0]; // 既定名 (OW-n/N-n) seed 用の採番
    private int[] rowY0 = new int[0];
    private int rowCount = 0;

    // ── 投影スクラッチ (rebuild 中のみ使用・フレーム毎に再確保しない) ──
    private float[] bSx = new float[0];
    private float[] bSy = new float[0];
    private float[] bDepth = new float[0];
    private int[] bColor = new int[0];
    private long[] bOrder = new long[0];

    // ── バッチ描画 (DynamicTexture + 1 blit): 全点を 1 枚へ焼き、 毎フレーム 1 ドローコール ──
    /** バッチ経路を使う (false で従来 g.fill)。 失敗時は texFailed で自動フォールバック。 */
    private static final boolean USE_TEXTURE_BATCH = true;
    /** ⑬ 案A スパイク: 真の GPU3D 経路を試す (失敗時は texbatch へ自動フォールバック・非クラッシュ)。 */
    private static final boolean USE_GPU3D_SPIKE = true;
    private boolean gpu3dActive = false; // 直近フレームが GPU3D 経路だったか (HUD 表示用)
    private String gpu3dReason = "(init)"; // GPU3D 不採用時の理由 (経路ログ用)
    private String loggedRoute = null;     // 直近にログした経路+理由 (一度きりログ用)
    // ⑬ GPU3D ジオメトリ署名 (カメラ除く: snapshot/spacing/トグル)。 変化時のみ VBO 再構築。
    private PointCloudSnapshot gSnap;
    private boolean gShowOw;
    private boolean gShowN;
    private boolean gShowLinks;
    private boolean gDimTint;
    private int gSpacing = -1;
    private int gDetail = -1; // ⑭ GPU detail 署名 (変化で VBO 再構築)
    private int gPointSize = -1; // ⑯ 点サイズ署名 (lineWidth は頂点に焼くので変化で再構築)
    private float gOwScale = Float.NaN;     // ㉓ OW 表示スケール署名 (変化で VBO 再構築)
    private float gNetherScale = Float.NaN; // ㉓ ネザー表示スケール署名
    private int gBcVersion = -1; // ㉕ back-calculate 要素の版 (add/clean で変化＝VBO 再構築)
    private int gHiddenVer = -1; // ㉝C 表示版 (hidden トグルで変化＝VBO 再構築・Re-analyze 不要で即反映)
    private int gpuOwPts;     // ⑭ 直近に GPU へ送った OW/ネザー点数 (detail 上限後・HUD 用)
    private int gpuNPts;
    private static final Identifier PC_TEX_ID =
            Identifier.fromNamespaceAndPath("visualizegate", "pointcloud_dyn");
    /**
     * ⑦ スーパーサンプル上限 (テクセル数)。 SS=guiScale でテクスチャ≈ネイティブ解像度＝1:1 でくっきり blit
     * (ネイティブとテクスチャが同寸なので nearest でも 1:1＝ボケない)。 巨大画面 (4K@guiScale1 等) のみ
     * この上限で SS を整数で段階的に下げる (= ラスタライズ/upload コストの暴走を防ぐ)。
     */
    private static final long MAX_TEXELS = 10_000_000L; // ⑩ 上限を引き上げ＝大画面/4K でも真ネイティブ SS
    private static final int MAX_TEX_DIM = 16384;        // GL 最大テクスチャ寸 (これを超えない範囲で SS)
    // テクスチャ/スクラッチは static (同時に開く Screen は 1 つ＝再オープンで再利用・GPU リーク回避)。
    private static DynamicTexture pcTex;
    private static int texW;   // テクスチャ実寸 (= vpW * texSS)
    private static int texH;
    private static int texSS = 1; // 直近のスーパーサンプル倍率 (HUD 表示用)
    private static int[] pix = new int[0]; // ネイティブ解像度 ARGB スクラッチ (rebuild 時のみ書く)
    /** テクスチャ経路が失敗したら true → 以後フォールバック (この Screen を開いている間)。 */
    private boolean texFailed = false;
    /** 直近 rasterize で書いた非透明ピクセル数 (0 なら不発 → drawCached が g.fill へ落とす防御)。 */
    private int lastRasterWrote = 0;

    // ── ⑨ 適応スーパーサンプル: 操作中は SS=1+間引きで安く、 静止 (settle) で一度だけネイティブ SS ──
    private static final long SETTLE_NANOS = 150_000_000L; // 最終入力から ~150ms で「静止」とみなす
    private static final int MOTION_STRIDE = 2;            // 動作中のみ点を 1/2 間引き (静止は全点)
    private long lastInputNanos = Long.MIN_VALUE / 2;      // 直近のドラッグ/ホイール時刻 (初期=静止扱い)
    private int lastBuildStride = 1;                       // 直近 rebuild の間引き (再 rebuild 判定用)
    private boolean lastBuildMotion = false;              // 直近 rebuild が動作中だったか (HUD 表示用)

    // ── 投影キャッシュ: 静止フレームは再投影/再ソートしない (CPU 律速の核を消す) ──
    private float[] dX = new float[0];   // 描画順 (遠→近) の確定スクリーン座標
    private float[] dY = new float[0];
    private int[] dSize = new int[0];     // フォールバック g.fill 用の整数径
    private float[] dRad = new float[0];  // テクスチャ用ソフト半径 (px・rebuild 時に算出)
    private int[] dColor = new int[0];
    private int cachedCount = 0;
    private float[] lkAx = new float[0]; // 投影済みリンク端点 (再投影しない)
    private float[] lkAy = new float[0];
    private float[] lkBx = new float[0];
    private float[] lkBy = new float[0];
    private int cachedLinks = 0;
    private int[] gkCol = new int[0];     // ㉚ キャッシュゲート → 状態色 (rasterize 時 snap 非依存)
    private float[] gkX = new float[0];   // ⑪ 投影済みゲート位置 (screen・紫リング)
    private float[] gkY = new float[0];
    private int cachedGates = 0;
    // ㉕ 投影済み back-calculate 要素 (screen・緑/赤の黒曜石枠・dim 問わず全要素を合成表示)。
    private float[] bcX = new float[0];
    private float[] bcY = new float[0];
    private int[] bcCol = new int[0];
    private int cachedBc = 0;
    private boolean mkVis = false;       // 現在地マーカー
    private float mkX;
    private float mkY;
    private long lastBuildNanos = 0;     // 直近 rebuild 所要 (計測表示用)
    private long lastDrawNanos = 0;      // 直近フレームの drawCached 所要 (静止/回転とも計測)
    private int lastFillCount = 0;       // 直近フレームの g.fill 発行数 (点+リンク+マーカー)
    private int fillCount = 0;           // 集計用 (フレーム毎に drawCached 冒頭でリセット)

    // ── 署名: これが変わったフレームだけ rebuild する ──
    private PointCloudSnapshot sigSnap;
    private float sigYaw = Float.NaN;
    private float sigPitch;
    private float sigDistance;
    private float sigSpacing;
    private boolean sigShowOw;
    private boolean sigShowN;
    private boolean sigShowLinks;
    private boolean sigDimTint;
    private float sigOwScale = Float.NaN;     // ㉓ OW 表示スケール署名
    private float sigNetherScale = Float.NaN; // ㉓ ネザー表示スケール署名
    private int sigBcVersion = -1;            // ㉕ back-calculate 要素の版署名
    private int sigHiddenVer = -1;            // ㉝C 表示版署名 (hidden トグルで再投影＝即反映)
    private int sigVpX;
    private int sigVpY;
    private int sigVpW;
    private int sigVpH;

    public PointCloudScreen(Screen parent) {
        super(Component.translatable("visualizegate.pc.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // ㉓ トグル群 2×2 [OW][Nether] / [Gate links][Dim tint] (㉚ View タブ専用)。 位置/幅は recomputeLayout が設定。
        viewWidgets.clear();
        viewWidgets.add(addRenderableWidget(Button.builder(owLabel(), b -> {
            PointCloudViewState.toggleOverworld();
            b.setMessage(owLabel());
            GateConfigManager.save();
        }).bounds(0, 0, 10, 20).build()));
        viewWidgets.add(addRenderableWidget(Button.builder(netherLabel(), b -> {
            PointCloudViewState.toggleNether();
            b.setMessage(netherLabel());
            GateConfigManager.save();
        }).bounds(0, 0, 10, 20).build()));
        viewWidgets.add(addRenderableWidget(Button.builder(linksLabel(), b -> {
            PointCloudViewState.toggleLinks();
            b.setMessage(linksLabel());
            GateConfigManager.save();
        }).bounds(0, 0, 10, 20).build()));
        viewWidgets.add(addRenderableWidget(Button.builder(tintLabel(), b -> {
            PointCloudViewState.toggleDimTint();
            b.setMessage(tintLabel());
            GateConfigManager.save();
        }).bounds(0, 0, 10, 20).build()));
        // 点群データ収集の恒常 ON/OFF (コマンド /vg point-cloud capture と同一 state を共有)。 OFF へ戻す導線も兼ねる。
        viewWidgets.add(addRenderableWidget(Button.builder(captureLabel(), b -> {
            PointCloudViewState.toggleCaptureEnabled();
            b.setMessage(captureLabel());
            GateConfigManager.save();
        }).bounds(0, 0, 10, 20).build()));

        // 空状態 (capture OFF) の中央ワンクリック有効化ボタン。 enable→永続→即 Re-analyze で今いる周辺地形をその場で出す
        // (onChunkLoad は既ロード分に発火しないため、 有効化だけでは空のまま。 resample で即時反映するのが要点)。
        captureEnableBtn = addRenderableWidget(Button.builder(
                Component.translatable("visualizegate.pc.enableCapture"), b -> enableCaptureAndFill())
                .bounds(0, 0, 160, 20).build());
        captureEnableBtn.visible = false;

        // フッタ: Re-analyze / Done (width 基準・サイドバー幅に非依存)。
        int fy = this.height - FOOTER_H + 7;
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.pc.reanalyze"),
                b -> PointCloudAnalysis.get().requestAnalysis())
                .bounds(MARGIN, fy, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("visualizegate.pc.done"), b -> this.onClose())
                .bounds(this.width - MARGIN - 120, fy, 120, 20).build());

        recomputeLayout(); // ㉞ 全レイアウトを sidebarW から算出 (init / スプリッタードラッグ / リサイズ 共通)
        applyTabVisibility();
    }

    /**
     * ㉞ サイドバー幅 {@link #sidebarW} を現在のウィンドウへクランプし、 ビューポート/タブ/スライダ/一覧/
     * scissor/凡例 と View トグル 4 ボタンの位置・幅を<b>全て再計算</b>する (init・スプリッタードラッグ・リサイズ
     * から呼ぶ＝単一の真実)。 フッタ (Re-analyze/Done) は width 基準で非依存。
     */
    private void recomputeLayout() {
        sidebarW = clampSidebar(PointCloudViewState.getSidebarWidth());

        vpX = MARGIN;
        vpY = HEADER_H + 6;
        vpW = this.width - sidebarW - MARGIN * 3;
        vpH = this.height - HEADER_H - FOOTER_H - 12;
        if (vpW < 40) {
            vpW = 40;
        }
        if (vpH < 40) {
            vpH = 40;
        }
        focal = Math.min(vpW, vpH);

        int sbX = this.width - sidebarW - MARGIN;
        int sbW = sidebarW;
        sbContentX = sbX;
        tabBarY = HEADER_H + 6;
        listTop = tabBarY + TABBAR_H + 4;
        listBottom = this.height - FOOTER_H - 4;

        // スライダ群 X/幅 (スクロール非依存)。 ⑧ パネル内へインセット。
        slX = sbX + SIDE_PAD;
        slW = sbW - 2 * SIDE_PAD;
        slH = 10;
        int trackGap = 8;
        slScaleHalfW = (slW - trackGap) / 2;
        slScaleOwX = slX;
        slScaleNX = slX + slScaleHalfW + trackGap;

        // ㊿ 縦フィット: トグル群 (3 行) ＋ スライダ 4 段 (先頭ラベル 11 + 3 ピッチ + トラック slH) を
        //    View 帯 [listTop, listBottom] に収める。 余裕があれば従来見た目 (トグル 24/スライダ 32)、 詰まれば
        //    スライダ→トグルの順に下限まで圧縮、 それでも入らぬ極小高 (4K Auto≈高 240・小窓) のみ viewScroll で可動。
        //    Capture 行 (5 個目) を含む全段が フッタ Done/Re-analyze に縦で被らないことを全 GUI スケールで保証する。
        int band = listBottom - listTop;
        int togFull = TOG_PITCH_MAX * 3 + 2;              // 74 (=従来 24+24+26)
        int sliderFull = 11 + SLIDER_PITCH_MAX * 3 + slH; // 117 (先頭ラベル + 3 ピッチ + 末トラック)
        int tp;
        int sp;
        if (togFull + sliderFull <= band) {
            tp = TOG_PITCH_MAX;                           // 収まる＝従来見た目 (回帰ゼロ)
            sp = SLIDER_PITCH_MAX;
        } else {
            // スライダ優先で詰め (情報密度)、 足りなければトグルも詰める。 各段/各行で 1 ピッチ縮める毎に 3px 削れる。
            int over = togFull + sliderFull - band;
            int sShrink = Math.min(over, (SLIDER_PITCH_MAX - SLIDER_PITCH_MIN) * 3);
            sp = SLIDER_PITCH_MAX - (sShrink + 2) / 3;    // 切り上げで確実に over を削る
            sp = Math.max(SLIDER_PITCH_MIN, sp);
            over -= (SLIDER_PITCH_MAX - sp) * 3;
            int tShrink = Math.min(Math.max(0, over), (TOG_PITCH_MAX - TOG_PITCH_MIN) * 3);
            tp = TOG_PITCH_MAX - (tShrink + 2) / 3;
            tp = Math.max(TOG_PITCH_MIN, tp);
        }
        int togBlockH = tp * 3 + 2;                        // 末行 (Capture) は +2 (従来 26=24+2 の踏襲)

        // 総コンテンツ高 (listTop 起点・最終トラック下端まで)＝スクロール量クランプの基準。
        viewContentH = togBlockH + 11 + sp * 3 + slH;
        viewScroll = clampScroll(viewScroll, viewContentH, band);

        int y = listTop - viewScroll;                     // スクロール適用後のトグル上端

        // View トグルを 2×2 ([OW][Nether]/[Gate links][Dim tint]) ＋ 5 個目 [Capture] を 1 行で再配置 (sidebarW 依存)。
        int colGap = 4;
        int halfW = (sbW - colGap) / 2;
        int col2X = sbX + halfW + colGap;
        if (viewWidgets.size() == 5) {
            positionBtn(viewWidgets.get(0), sbX, y, halfW);
            positionBtn(viewWidgets.get(1), col2X, y, halfW);
            y += tp;
            positionBtn(viewWidgets.get(2), sbX, y, halfW);
            positionBtn(viewWidgets.get(3), col2X, y, halfW);
            y += tp;
            positionBtn(viewWidgets.get(4), sbX, y, sbW); // Capture トグル (1 行・サイドバー全幅)
            y += tp + 2;
        } else {
            y += togBlockH; // ボタン未生成でもスライダ基準 Y を一致
        }

        // 空状態の中央 [キャプチャを有効化] ボタンを vp 中央 (ヒント直下) に配置 (vp 内＝スクロール非依存)。
        if (captureEnableBtn != null) {
            int bw = Math.min(160, Math.max(80, vpW - 16));
            captureEnableBtn.setWidth(bw);
            captureEnableBtn.setX(vpX + (vpW - bw) / 2);
            captureEnableBtn.setY(vpY + vpH / 2 + 8);
        }

        slScaleY = y + 11;          // ラベル(上)分を空ける
        slY = slScaleY + sp;        // Dimension spacing
        sl2Y = slY + sp;            // ⑭ GPU detail
        sl3Y = sl2Y + sp;           // ⑯ 点サイズ

        // ㊿ スクロール/帯外のトグルは hide (auto 描画は scissor 外＝はみ出し防止)＋タブ可視を再適用。
        applyTabVisibility();
    }

    /**
     * ㉞ サイドバー幅を [SIDEBAR_W_MIN, width - MIN_VP - MARGIN*3] にクランプ。 退化 (低解像度/高 GUI スケールで
     * min &gt; max) では破綻させず、 利用可能幅の 60% をサイドバーへ (= ビューポートに 40% を残す比例分配)。
     */
    private int clampSidebar(int v) {
        int min = PointCloudViewState.SIDEBAR_W_MIN;
        int max = this.width - MIN_VP - MARGIN * 3;
        if (max < min) {
            int avail = Math.max(0, this.width - MARGIN * 3);
            return Math.max(40, Math.min(min, (int) (avail * 0.6)));
        }
        return Math.max(min, Math.min(max, v));
    }

    private static void positionBtn(Button b, int x, int y, int w) {
        b.setX(x);
        b.setY(y);
        b.setWidth(w);
    }

    /**
     * ㉚ View タブのトグルボタンは View 時のみ表示/操作可 (Gates/Links では隠す)。 ㊿ さらに viewScroll で
     * View 帯 [listTop, listBottom] からはみ出したボタンも隠す (auto 描画は scissor 外＝ヘッダ/フッタへの
     * オーバードロー防止)。 帯に全身が収まる通常域では全ボタン可視＝従来不変。
     */
    private void applyTabVisibility() {
        boolean view = tab == Tab.VIEW;
        for (Button b : viewWidgets) {
            boolean vis = view && b.getY() >= listTop && b.getY() + b.getHeight() <= listBottom;
            b.visible = vis;
            b.active = vis;
        }
    }

    private static Component owLabel() {
        return Component.translatable("visualizegate.pc.toggle.overworld", onOff(PointCloudViewState.isShowOverworld()));
    }

    private static Component netherLabel() {
        return Component.translatable("visualizegate.pc.toggle.nether", onOff(PointCloudViewState.isShowNether()));
    }

    private static Component linksLabel() {
        return Component.translatable("visualizegate.pc.toggle.links", onOff(PointCloudViewState.isShowLinks()));
    }

    private static Component tintLabel() {
        return Component.translatable("visualizegate.pc.toggle.tint", onOff(PointCloudViewState.isDimTint()));
    }

    /** Capture トグルのラベル (ON/OFF は既存 state.on/off を再利用)。 */
    private static Component captureLabel() {
        return Component.translatable("visualizegate.pc.capture", onOff(PointCloudViewState.isCaptureEnabled()));
    }

    /** ON/OFF を既存 state.on/off キーで返す (全トグルラベル共有)。 */
    private static Component onOff(boolean b) {
        return Component.translatable(b ? "visualizegate.state.on" : "visualizegate.state.off");
    }

    /**
     * 空状態の [キャプチャを有効化]: capture を ON にし永続＋即 Re-analyze で今ロード中チャンクの地形をその場で出す。
     * 順序が肝＝gate を先に開けてから {@link PointCloudAnalysis#requestAnalysis()} (内部 {@code resampleLoaded}) を呼ぶ。
     */
    private void enableCaptureAndFill() {
        PointCloudViewState.setCaptureEnabled(true);
        GateConfigManager.save();
        // View タブの Capture トグルのラベルも同期 (5 個目)。
        if (viewWidgets.size() == 5) {
            viewWidgets.get(4).setMessage(captureLabel());
        }
        if (!captureNoticeLogged) {
            captureNoticeLogged = true;
            VisualizeGateMod.LOGGER.info(
                    "[visualizegate] point-cloud terrain capture enabled — terrain will now accumulate to disk "
                            + "(config/visualizegate/tiles/). Disable via View tab or /vg point-cloud capture off.");
        }
        PointCloudAnalysis.get().requestAnalysis();
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // パネル背景 (GameRenderer が backdrop を描く前提＝renderBackground は呼ばない)。
        g.fill(0, 0, this.width, this.height, GateColors.BASE);
        g.fill(0, HEADER_H, this.width, HEADER_H + 1, GateColors.MAIN);
        g.fill(0, this.height - FOOTER_H, this.width, this.height - FOOTER_H + 1, GateColors.MAIN);
        // ビューポート枠。
        g.fill(vpX - 1, vpY - 1, vpX + vpW + 1, vpY, GateColors.MAIN_DIM);
        g.fill(vpX - 1, vpY + vpH, vpX + vpW + 1, vpY + vpH + 1, GateColors.MAIN_DIM);
        g.fill(vpX - 1, vpY, vpX, vpY + vpH, GateColors.MAIN_DIM);
        g.fill(vpX + vpW, vpY, vpX + vpW + 1, vpY + vpH, GateColors.MAIN_DIM);

        drawSplitter(g, mouseX, mouseY); // ㉞ vp⇔サイドバー境界の可動グリップ

        PointCloudAnalysis analysis = PointCloudAnalysis.get();
        PointCloudAnalysis.State st = analysis.state();
        PointCloudSnapshot snap = analysis.snapshot();

        // 中央 [キャプチャを有効化] ボタンの可視: 空 && capture OFF && 解析中でない とき (タブ非依存)。
        // super.extractRenderState (widget 描画) の前に確定する。
        boolean emptyCaptureOff = st != PointCloudAnalysis.State.ANALYZING
                && snap.isEmpty() && !PointCloudViewState.isCaptureEnabled();
        if (captureEnableBtn != null) {
            captureEnableBtn.visible = emptyCaptureOff;
            captureEnableBtn.active = emptyCaptureOff;
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick); // widgets (toggles/footer)

        // タイトル + スケール表記。
        g.text(this.font, this.title, MARGIN, 10, GateColors.ACCENT);
        // ㉓ 基準形 (1:1 / 1:8) ＋ 現在の表示スケール倍率を併記。 既定 1/1 では実質「1:1 / 1:8」のまま。
        String scaleHud = Component.translatable("visualizegate.pc.scaleHud",
                fmtScale(PointCloudViewState.getOwDisplayScale()),
                fmtScale(PointCloudViewState.getNetherDisplayScale())).getString();
        g.text(this.font, Component.literal(fitWidth(scaleHud, sidebarW + MARGIN)),
                this.width - sidebarW - MARGIN, 10, GateColors.TEXT);

        if (st == PointCloudAnalysis.State.ANALYZING) {
            centerText(g, Component.translatable("visualizegate.pc.analyzing"), GateColors.TEXT);
        } else if (snap.isEmpty()) {
            // capture OFF: 「壊れている」誤認を避け、 ヒント＋直下の [有効化] ボタン (上で可視化済) で導線を出す。
            // capture ON: 既存どおり「探索して Re-analyze」(正しい状態＝まだ歩いていないだけ)。
            if (!PointCloudViewState.isCaptureEnabled()) {
                centerText(g, Component.translatable("visualizegate.pc.empty.captureOff"), GateColors.LINK_GRAY);
            } else {
                centerText(g, Component.translatable("visualizegate.pc.empty.explore"), GateColors.LINK_GRAY);
            }
        } else {
            frameIfNeeded(snap);
            gpu3dActive = false;
            if (USE_GPU3D_SPIKE && tryGpu3d(g, snap)) {
                logRoute("gpu3d", null);
            } else {
                drawCloud(g, snap);
                logRoute(texFailed ? "fill" : "tex", gpu3dReason);
            }
            drawRouteBanner(g); // 経路を画面に大きく表示 (ログ不要に)
        }

        // ㉚C 3D 番号ラベル/選択ハイライト (3D パス後の 2D オーバーレイ・Mixin 不使用)。
        if (!snap.isEmpty()) {
            drawGateLabels(g, snap);
        }

        // ㉚ タブバー＋タブ別サイドバー内容。
        drawTabBar(g);
        if (tab == Tab.VIEW) {
            // ㊿ スライダ/統計は viewScroll でずれるため View 帯にクリップ (Gates/Links と同型の scissor)。
            //    スクロール時に末段が タブバー上/フッタ下へはみ出さない。 トグルは applyTabVisibility が帯外を hide 済。
            g.enableScissor(sbContentX, listTop, sbContentX + sidebarW, listBottom);
            drawSlider(g);
            drawCounts(g, snap);
            g.disableScissor();
        } else if (tab == Tab.GATES) {
            drawGatesList(g, snap);
            drawLegend(g);
        } else {
            drawLinksList(g, snap);
            drawLegend(g);
        }
    }

    private void centerText(GuiGraphicsExtractor g, String msg, int color) {
        centerText(g, Component.literal(msg), color);
    }

    private void centerText(GuiGraphicsExtractor g, Component c, int color) {
        int tx = vpX + vpW / 2 - this.font.width(c) / 2;
        int ty = vpY + vpH / 2 - 4;
        g.text(this.font, c, tx, ty, color);
    }

    /** スナップショットが変わったら distance を一度だけ枠合わせする。 */
    private void frameIfNeeded(PointCloudSnapshot snap) {
        if (framedFor == snap) {
            return;
        }
        framedFor = snap;
        float spacing = PointCloudViewState.getDimensionSpacing();
        distance = Math.max(snap.radius * 2.2f, Math.max(spacing * 1.5f, 40f));
    }

    /**
     * 点群描画。 ビュー (snapshot/カメラ/spacing/トグル/ビューポート) が変わったフレームだけ
     * {@link #rebuildProjection} で再投影+深度ソート+キャッシュ化し、 静止フレームはキャッシュから
     * 描くだけ (= 毎フレームの投影/ソートを無くす＝CPU 律速の核を消す)。
     */
    /**
     * ⑬ 案A スパイク: FBO へ真の GPU3D (深度付) で描き、 FBO 色をビューポートへ合成。 成功で true。
     * 失敗 (当該 gen で不可/例外) なら false を返し、 呼び出し側が texbatch へフォールバック。
     */
    /** ビューポート上部に描画経路を大きく表示 (ログを掘らず一目で確認・スクショ可)。 */
    private void drawRouteBanner(GuiGraphicsExtractor g) {
        String mode; // モード識別子は据置 (GPU3D / TEX — …)。 ラベル "RENDER:" のみ外部化。
        int col;
        if (gpu3dActive) {
            mode = "GPU3D";
            col = GateColors.PC_OW_HIGH;          // 青緑＝成功
        } else {
            mode = "TEX — " + (texFailed ? "fill (texbatch failed)" : gpu3dReason);
            col = GateColors.PC_NETHER_HIGH;      // 橙＝フォールバック
        }
        String txt = Component.translatable("visualizegate.pc.render", mode).getString();
        int top = vpY + 2;
        g.fill(vpX, top, vpX + vpW, top + 13, 0xC0000000); // 帯背景
        g.fill(vpX, top, vpX + vpW, top + 1, GateColors.MAIN);
        g.text(this.font, Component.literal(fitWidth(txt, vpW - 6)), vpX + 4, top + 3, col);
    }

    /** 経路を一度きりログ (HUD に依存しない自己診断・経路や理由が変わった時だけ出す)。 */
    private void logRoute(String route, String reason) {
        String key = route + "|" + (reason == null ? "" : reason);
        if (!key.equals(loggedRoute)) {
            loggedRoute = key;
            if (reason == null) {
                VisualizeGateMod.LOGGER.info("[visualizegate] point-cloud render route = {}", route);
            } else {
                VisualizeGateMod.LOGGER.info("[visualizegate] point-cloud render route = {} ({})", route, reason);
            }
        }
    }

    //? if >=1.21.11 {
    private boolean tryGpu3d(GuiGraphicsExtractor g, PointCloudSnapshot snap) {
        if (!PointCloudGpuRenderer.usable()) {
            gpu3dReason = "usable=false err=" + PointCloudGpuRenderer.lastError();
            return false;
        }
        long t0 = System.nanoTime();
        if (gpuGeomChanged(snap)) {
            buildGpuGeometry(snap); // データ/トグル/spacing 変化時のみ VBO 再構築 (回転/ズームは行列のみ)
        }
        // ㉞ スプリッタードラッグ中は vp 寸法が毎フレーム変わり FBO を resize するため SS=1 で安価に
        //    (回転中は vp 不変＝FBO resize 無し＝full SS のまま)。 確定後 settle で full SS に戻る。
        int ss = (drag == Drag.SPLITTER) ? 1 : supersample();
        if (!PointCloudGpuRenderer.render(vpW * ss, vpH * ss, yaw, pitch, distance,
                panX, panY, panZ, GateColors.BASE)) {
            gpu3dReason = "render=false err=" + PointCloudGpuRenderer.lastError();
            return false;
        }
        // FBO 色を GUI へ合成。 FBO(GL レンダーターゲット) は下原点なので V を反転 (v0=1 上 / v1=0 下) して GUI 上向きに。
        //? if >=26.1 {
        // >=26.1: GpuTextureView 直 blit (実績パス不変)。 g.fill(GUI_TEXTURED, TextureSetup,…) は UV0 を書かず
        //   GUI_TEXTURED(UV0 必須) と不一致でクラッシュするため不可。 GpuTextureView を直接取る blit は UV0 付き
        //   BlitRenderState を作る (内部で GUI_TEXTURED 使用・javap 確認) ＝正しい合成。
        GpuTextureView cv = PointCloudGpuRenderer.colorView();
        if (cv == null) {
            gpu3dReason = "colorView=null";
            return false;
        }
        g.blit(cv, PointCloudGpuRenderer.sampler(),
                vpX, vpY, vpX + vpW, vpY + vpH, 0f, 1f, 1f, 0f);
        //?} else {
        /*// <26.1 (1.21.11): GpuTextureView 直 blit は public 不在 → FBO color を登録テクスチャ化し全版 public な
        //   Identifier-blit (GUI_TEXTURED 経由・UV0 付き) で合成 (Path A・runtime probe 実証)。
        Identifier cid = PointCloudGpuRenderer.ensureCompositeTexture();
        if (cid == null) {
            gpu3dReason = "compositeId=null";
            return false;
        }
        g.blit(cid, vpX, vpY, vpX + vpW, vpY + vpH, 0f, 1f, 1f, 0f);*/
        //?}
        gpu3dActive = true;
        texSS = ss;
        lastFillCount = 1;
        lastDrawNanos = System.nanoTime() - t0;
        return true;
    }

    /** GPU3D ジオメトリ署名 (カメラを除く: snapshot/spacing/トグル) の変化検出。 */
    private boolean gpuGeomChanged(PointCloudSnapshot snap) {
        boolean showOw = PointCloudViewState.isShowOverworld();
        boolean showN = PointCloudViewState.isShowNether();
        boolean showLinks = PointCloudViewState.isShowLinks();
        boolean dimTint = PointCloudViewState.isDimTint();
        int spacing = PointCloudViewState.getDimensionSpacing();
        int detail = PointCloudViewState.getGpuDetail();
        int pointSize = PointCloudViewState.getPointSize();
        float owScale = PointCloudViewState.getOwDisplayScale();
        float nScale = PointCloudViewState.getNetherDisplayScale();
        int bcVersion = BackCalcStore.version();
        int hiddenVer = PortalMemory.displayVersion(); // ㉝C hidden トグルで変化
        if (snap == gSnap && showOw == gShowOw && showN == gShowN && showLinks == gShowLinks
                && dimTint == gDimTint && spacing == gSpacing && detail == gDetail
                && pointSize == gPointSize && owScale == gOwScale && nScale == gNetherScale
                && bcVersion == gBcVersion && hiddenVer == gHiddenVer) {
            return false;
        }
        gSnap = snap;
        gShowOw = showOw;
        gShowN = showN;
        gShowLinks = showLinks;
        gDimTint = dimTint;
        gSpacing = spacing;
        gDetail = detail;
        gPointSize = pointSize;
        gOwScale = owScale;
        gNetherScale = nScale;
        gBcVersion = bcVersion;
        gHiddenVer = hiddenVer;
        return true;
    }

    /** snapshot からカメラ非依存の 3D 頂点 (点群・線) を組み GPU へアップロード。 */
    private void buildGpuGeometry(PointCloudSnapshot snap) {
        float pivotY = PointCloudViewState.getDimensionSpacing() * 0.5f;
        boolean tint = PointCloudViewState.isDimTint();
        boolean showOw = PointCloudViewState.isShowOverworld();
        boolean showN = PointCloudViewState.isShowNether();
        boolean showLinks = PointCloudViewState.isShowLinks();
        // ㉓ 層ごとの表示スケール (基準形に重ねる XZ 倍率)。 スナップショットの XZ は重心相対なので
        // 乗算＝重心基準の拡縮。 既定 1.0/1.0 で現状一致 (回帰ゼロ)。 地形点・リンク端・ゲート・現在地マーカー
        // すべてに同じ層スケールを掛け、 地形と端点がズレないようにする。
        float owScale = PointCloudViewState.getOwDisplayScale();
        float nScale = PointCloudViewState.getNetherDisplayScale();

        // ── 点群 (OW=+pivot 上層 / ネザー=-pivot 下層・⑤頂点色) ──
        // ⑭ 品質設定: 各層を GPU detail (= 1 層の最大描画点数) へ stride 間引き。 スナップショットは不変
        // ＝再解析不要・ライブ。 GPU3D は Screen 表示中のみ＝通常 FPS/サーバーに影響しない。
        int detail = PointCloudViewState.getGpuDetail();
        int owN = showOw ? snap.owX.length : 0;
        int nN = showN ? snap.nX.length : 0;
        int owStride = (owN > detail) ? (owN + detail - 1) / detail : 1;
        int nStride = (nN > detail) ? (nN + detail - 1) / detail : 1;
        int pc = (owN + owStride - 1) / owStride + (nN + nStride - 1) / nStride;
        float[] pxyz = new float[pc * 3];
        int[] pcol = new int[pc];
        int k = 0;
        for (int i = 0; i < owN; i += owStride) {
            pxyz[k * 3] = snap.owX[i] * owScale;        // ㉓ XZ のみ拡縮 (Y/spacing は不変)
            pxyz[k * 3 + 1] = snap.owY[i] + pivotY;
            pxyz[k * 3 + 2] = snap.owZ[i] * owScale;
            pcol[k] = tint ? mix(snap.owColor[i], DIM_TINT_OW, DIM_TINT_FRAC) : snap.owColor[i];
            k++;
        }
        gpuOwPts = k;
        for (int i = 0; i < nN; i += nStride) {
            pxyz[k * 3] = snap.nX[i] * nScale;          // ㉓ 基準 1/8 に表示スケールを重ねる
            pxyz[k * 3 + 1] = snap.nY[i] - pivotY;
            pxyz[k * 3 + 2] = snap.nZ[i] * nScale;
            pcol[k] = tint ? mix(snap.nColor[i], DIM_TINT_NETHER, DIM_TINT_FRAC) : snap.nColor[i];
            k++;
        }
        gpuNPts = k - gpuOwPts;
        // ⑯ GL 点サイズ (px・スライダ)。 小さく＝密で滑らかな高密度クラウド。
        PointCloudGpuRenderer.uploadPoints(pxyz, pcol, k, PointCloudViewState.getPointSize());

        // ── マーカー類 (⑲ 中空ワイヤー＋細線・QUADS): リンク=細角柱 / ㉔ゲート=黒曜石ポータル枠 / 現在地=細ワイヤー十字 ──
        // 中空ワイヤーで点群を透かし、 点群を隠さずマーカーは明確に。 太さは FRAC 定数 (雲半径×比) で微調整可
        // (ワールド寸＝透視で解像度比例に見える＝4K でも視認・カメラ非依存)。
        float linkW = Math.max(0.035f, snap.radius * GPU_LINK_W_FRAC);       // リンク角柱の半幅 (細線ワイヤー)
        float gateHalfH = Math.max(0.8f, snap.radius * GPU_GATE_FRAME_HALF_H_FRAC); // ㉔ ゲート枠の半高 (縦長)
        float gateHalfW = Math.max(0.6f, snap.radius * GPU_GATE_FRAME_HALF_W_FRAC); // ㉔ ゲート枠の半幅 (4:5)
        float gateBarW = Math.max(0.05f, snap.radius * GPU_GATE_BAR_W_FRAC);  // ㉔ ゲート枠バーの半幅
        float gateGridW = Math.max(0.04f, snap.radius * GPU_GATE_GRID_W_FRAC); // ㉙ ゲート内側格子バーの半幅
        float markArm = Math.max(2f, snap.radius * GPU_MARKER_ARM_FRAC);     // 現在地十字の腕長
        float markW = Math.max(0.1f, snap.radius * GPU_MARKER_W_FRAC);       // 現在地十字の半幅 (細く)

        int links = showLinks ? snap.linkCount() : 0;
        int gates = showLinks ? snap.gateCount() : 0;
        // ㉕ back-calculate 要素 (全 dim・Gate links トグルとは独立＝/vg で出したら常に見える)。 枠=64 頂点/件。
        List<BackCalcStore.Element> bc = BackCalcStore.all();
        int bcN = bc.size();
        // 頂点数: リンク角柱=16 / ㉙ゲート枠+内側格子=(4+3)バー×16=112 / 現在地十字=48 / ㉕back-calc枠=64。
        int ov = links * 16 + gates * 112 + (snap.hasMarker ? 48 : 0) + bcN * 64;
        float[] oxyz = new float[ov * 3];
        int[] ocol = new int[ov];
        int j = 0;
        // ㉝C 非表示ゲート: per-gate スキップ＋リンク端点番号集合 (over-allocate のまま j で詰める＝余りは無描画)。
        boolean[] gateHidden = new boolean[gates];
        java.util.Set<Integer> hidOwNum = new java.util.HashSet<>();
        java.util.Set<Integer> hidNethNum = new java.util.HashSet<>();
        for (int i = 0; i < gates && i < snap.gateMeta.gateNumber().length; i++) {
            boolean h = isGateHidden(snap.gateNether[i], snap.gateMeta.gateWx()[i],
                    snap.gateMeta.gateWy()[i], snap.gateMeta.gateWz()[i]);
            gateHidden[i] = h;
            if (h) {
                (snap.gateNether[i] ? hidNethNum : hidOwNum).add(snap.gateMeta.gateNumber()[i]);
            }
        }
        int linkC = 0xFF000000 | (GateColors.PC_LINK & 0xFFFFFF);
        for (int i = 0; i < links; i++) {
            // ㉝C 端点どちらかが非表示ならリンク線を描かない。
            if (i < snap.gateMeta.linkOwNumber().length
                    && (hidOwNum.contains(snap.gateMeta.linkOwNumber()[i])
                            || hidNethNum.contains(snap.gateMeta.linkNNumber()[i]))) {
                continue;
            }
            // ㉓ A 端=OW 層スケール / B 端=ネザー層スケール (地形と同じ層変換＝端が追従。 両端のスケール差で線は傾いてよい)。
            j = emitBox(oxyz, ocol, j,
                    snap.linkAx[i] * owScale, snap.linkAy[i] + pivotY, snap.linkAz[i] * owScale,
                    snap.linkBx[i] * nScale, snap.linkBy[i] - pivotY, snap.linkBz[i] * nScale, linkW, linkC);
        }
        for (int i = 0; i < gates; i++) {
            if (gateHidden[i]) {
                continue; // ㉝C 非表示ゲートは枠を描かない
            }
            float gs = snap.gateNether[i] ? nScale : owScale; // ㉓ 当該 dim の層スケール (位置のみ)
            float gy = snap.gateNether[i] ? snap.gateY[i] - pivotY : snap.gateY[i] + pivotY;
            // ㉔/㉙/㉚ 黒曜石ポータル枠＋内側格子。 ㉚ 枠色をゲート状態色に (一様紫→状態色)。
            j = emitGateFrame(oxyz, ocol, j, snap.gateX[i] * gs, gy, snap.gateZ[i] * gs,
                    gateHalfW, gateHalfH, gateBarW, gateGridW, gateColorAt(snap, i));
        }
        if (snap.hasMarker) {
            float ms = snap.markerNether ? nScale : owScale; // ㉓ 当該 dim の層スケール
            float my = snap.markerNether ? snap.markerY - pivotY : snap.markerY + pivotY;
            int gold = 0xFF000000 | (GateColors.ACCENT & 0xFFFFFF);
            j = emitCross(oxyz, ocol, j, snap.markerX * ms, my, snap.markerZ * ms, markArm, markW, gold);
        }
        // ㉕ back-calculate 要素を地形/ゲートと同一アンカー (toViewSpace) ＋同一 ÷8/表示スケール経路で黒曜石枠化。
        // 現在層の真上/真下に逆 dim の緑/赤が同期表示される (gate と全く同じ変換なのでズレない)。
        for (BackCalcStore.Element e : bc) {
            float[] vv = snap.toViewSpace(e.dim, e.x, e.y, e.z);
            float es = (e.dim == PortalDimension.NETHER) ? nScale : owScale;
            float ey = (e.dim == PortalDimension.NETHER) ? vv[1] - pivotY : vv[1] + pivotY;
            int ec = 0xFF000000 | (e.colorArgb & 0xFFFFFF);
            j = emitPortalFrame(oxyz, ocol, j, vv[0] * es, ey, vv[2] * es,
                    gateHalfW, gateHalfH, gateBarW, ec);
        }
        PointCloudGpuRenderer.uploadOverlay(oxyz, ocol, j);
    }

    /**
     * 始点(a)→終点(b) を結ぶ<b>四角断面の角柱</b> (半幅 {@code w}) の側面 4 枚 (=16 頂点) を書き込む。
     * 線を太く見せる立体 (どの回転角でも厚みが見える・深度正)。 退化 (同一点) なら無書込みで {@code v} を返す。
     */
    private static int emitBox(float[] xyz, int[] col, int v, float ax, float ay, float az,
            float bx, float by, float bz, float w, int c) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) {
            return v;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        // dir に直交する基準 up (dir がほぼ縦なら X 基準へ切替して縮退回避)。
        float ux = 0f;
        float uy = 1f;
        float uz = 0f;
        if (Math.abs(dy) > 0.9f) {
            ux = 1f;
            uy = 0f;
            uz = 0f;
        }
        // s1 = normalize(dir × up) * w、 s2 = normalize(dir × s1) * w (= dir に直交する 2 軸)。
        float s1x = dy * uz - dz * uy;
        float s1y = dz * ux - dx * uz;
        float s1z = dx * uy - dy * ux;
        float s1l = (float) Math.sqrt(s1x * s1x + s1y * s1y + s1z * s1z);
        s1x = s1x / s1l * w;
        s1y = s1y / s1l * w;
        s1z = s1z / s1l * w;
        float s2x = dy * s1z - dz * s1y;
        float s2y = dz * s1x - dx * s1z;
        float s2z = dx * s1y - dy * s1x;
        float s2l = (float) Math.sqrt(s2x * s2x + s2y * s2y + s2z * s2z);
        s2x = s2x / s2l * w;
        s2y = s2y / s2l * w;
        s2z = s2z / s2l * w;
        // 4 隅 (始点側 a0..a3、 終点側 b0..b3)、 側面を 4 枚。
        float a0x = ax - s1x - s2x, a0y = ay - s1y - s2y, a0z = az - s1z - s2z;
        float a1x = ax + s1x - s2x, a1y = ay + s1y - s2y, a1z = az + s1z - s2z;
        float a2x = ax + s1x + s2x, a2y = ay + s1y + s2y, a2z = az + s1z + s2z;
        float a3x = ax - s1x + s2x, a3y = ay - s1y + s2y, a3z = az - s1z + s2z;
        float b0x = bx - s1x - s2x, b0y = by - s1y - s2y, b0z = bz - s1z - s2z;
        float b1x = bx + s1x - s2x, b1y = by + s1y - s2y, b1z = bz + s1z - s2z;
        float b2x = bx + s1x + s2x, b2y = by + s1y + s2y, b2z = bz + s1z + s2z;
        float b3x = bx - s1x + s2x, b3y = by - s1y + s2y, b3z = bz - s1z + s2z;
        v = emitQuad(xyz, col, v, a0x, a0y, a0z, a1x, a1y, a1z, b1x, b1y, b1z, b0x, b0y, b0z, c);
        v = emitQuad(xyz, col, v, a1x, a1y, a1z, a2x, a2y, a2z, b2x, b2y, b2z, b1x, b1y, b1z, c);
        v = emitQuad(xyz, col, v, a2x, a2y, a2z, a3x, a3y, a3z, b3x, b3y, b3z, b2x, b2y, b2z, c);
        v = emitQuad(xyz, col, v, a3x, a3y, a3z, a0x, a0y, a0z, b0x, b0y, b0z, b3x, b3y, b3z, c);
        return v;
    }

    /**
     * ㉔ 中心 (x,y,z)・半幅 {@code halfW}・半高 {@code halfH} の<b>黒曜石ネザーポータル枠</b>
     * (縦長の中空矩形＝左右の柱 2 本＋上下の桁 2 本・各細角柱 {@link #emitBox}・4×16=64 頂点)。
     * X–Y 平面 (法線 Z) の<b>固定軸既定向き</b>で直立。 中空＝点群が透ける。 {@code barW}=枠バーの半幅。
     */
    private static int emitPortalFrame(float[] xyz, int[] col, int v, float x, float y, float z,
            float halfW, float halfH, float barW, int c) {
        float x0 = x - halfW, x1 = x + halfW, y0 = y - halfH, y1 = y + halfH;
        v = emitBox(xyz, col, v, x0, y0, z, x0, y1, z, barW, c); // 左柱
        v = emitBox(xyz, col, v, x1, y0, z, x1, y1, z, barW, c); // 右柱
        v = emitBox(xyz, col, v, x0, y1, z, x1, y1, z, barW, c); // 上桁
        v = emitBox(xyz, col, v, x0, y0, z, x1, y0, z, barW, c); // 下桁
        return v;
    }

    /**
     * ㉙ ゲート用: 外枠 ({@link #emitPortalFrame}・4 バー) ＋<b>内側格子</b> (縦 2・横 1 バー＝ポータル面の手がかり)。
     * 計 7 バー×16=112 頂点。 格子は枠より細い {@code gridW} で点群を透かす。 不透明 (blend 不要＝quadPipeline のまま)。
     */
    private static int emitGateFrame(float[] xyz, int[] col, int v, float x, float y, float z,
            float halfW, float halfH, float barW, float gridW, int c) {
        v = emitPortalFrame(xyz, col, v, x, y, z, halfW, halfH, barW, c);
        float vx = halfW * 0.34f; // 内側 縦バー 2 本
        v = emitBox(xyz, col, v, x - vx, y - halfH, z, x - vx, y + halfH, z, gridW, c);
        v = emitBox(xyz, col, v, x + vx, y - halfH, z, x + vx, y + halfH, z, gridW, c);
        v = emitBox(xyz, col, v, x - halfW, y, z, x + halfW, y, z, gridW, c); // 内側 横バー 1 本
        return v;
    }

    /** 中心 (x,y,z) の<b>3D 太十字</b> (X/Y/Z 各 1 角柱=48 頂点)。 現在地マーカー (金) 用＝確実に目立つ。 */
    private static int emitCross(float[] xyz, int[] col, int v, float x, float y, float z,
            float arm, float w, int c) {
        v = emitBox(xyz, col, v, x - arm, y, z, x + arm, y, z, w, c);
        v = emitBox(xyz, col, v, x, y - arm, z, x, y + arm, z, w, c);
        v = emitBox(xyz, col, v, x, y, z - arm, x, y, z + arm, w, c);
        return v;
    }

    /** QUADS 1 枚 (4 頂点・順序 0→1→2→3) を書き込み次の頂点 index を返す。 */
    private static int emitQuad(float[] xyz, int[] col, int v,
            float x0, float y0, float z0, float x1, float y1, float z1,
            float x2, float y2, float z2, float x3, float y3, float z3, int c) {
        v = putV(xyz, col, v, x0, y0, z0, c);
        v = putV(xyz, col, v, x1, y1, z1, c);
        v = putV(xyz, col, v, x2, y2, z2, c);
        v = putV(xyz, col, v, x3, y3, z3, c);
        return v;
    }

    private static int putV(float[] xyz, int[] col, int v, float x, float y, float z, int c) {
        xyz[v * 3] = x;
        xyz[v * 3 + 1] = y;
        xyz[v * 3 + 2] = z;
        col[v] = c;
        return v + 1;
    }
    //?} else {
    /*private boolean tryGpu3d(GuiGraphicsExtractor g, PointCloudSnapshot snap) {
        gpu3dReason = "GPU3D not ported to 1.21.10";
        return false; // 1.21.10 のみ GPU3D 未移植 (texbatch 据え置き・別フォローアップ。 1.21.11+ は移植済) → texbatch
    }*/
    //?}

    private void drawCloud(GuiGraphicsExtractor g, PointCloudSnapshot snap) {
        // ⑨ 適応 SS: ドラッグ中 or 最終入力から SETTLE 未満は「動作中」→ SS=1+間引きで安く rasterize。
        // 静止したら一度だけ targetSS!=texSS で再 rebuild され、 ネイティブ SS+全点でくっきり (静止品質不変)。
        long now = System.nanoTime();
        boolean motion = (drag == Drag.ROTATE) || (drag == Drag.SPLITTER) // ㉞ スプリッター中も SS=1 (安価 resize)
                || (now - lastInputNanos < SETTLE_NANOS);
        int targetSS = motion ? 1 : supersample();
        // ⑯ texbatch (CPU フォールバック) も品質設定の detail で上限化＝高 budget(20万) でも暴走しない。
        int detail = PointCloudViewState.getGpuDetail();
        int maxLayer = Math.max(snap.owX.length, snap.nX.length);
        int detailStride = (detail > 0 && maxLayer > detail) ? (maxLayer + detail - 1) / detail : 1;
        int targetStride = Math.max(motion ? MOTION_STRIDE : 1, detailStride);
        boolean texActive = USE_TEXTURE_BATCH && !texFailed;
        boolean sigChanged = signatureChanged(snap);
        if (sigChanged || (texActive && (targetSS != texSS || targetStride != lastBuildStride))) {
            rebuildProjection(snap, targetSS, targetStride);
        }
        drawCached(g);
    }

    /** ビュー署名の変化検出 (変化時は新署名を保存して true)。 */
    private boolean signatureChanged(PointCloudSnapshot snap) {
        boolean showOw = PointCloudViewState.isShowOverworld();
        boolean showN = PointCloudViewState.isShowNether();
        boolean showLinks = PointCloudViewState.isShowLinks();
        boolean dimTint = PointCloudViewState.isDimTint();
        float spacing = PointCloudViewState.getDimensionSpacing();
        float owScale = PointCloudViewState.getOwDisplayScale();
        float nScale = PointCloudViewState.getNetherDisplayScale();
        int bcVersion = BackCalcStore.version();
        int hiddenVer = PortalMemory.displayVersion(); // ㉝C hidden トグルで再投影
        if (snap == sigSnap && yaw == sigYaw && pitch == sigPitch && distance == sigDistance
                && spacing == sigSpacing && showOw == sigShowOw && showN == sigShowN
                && showLinks == sigShowLinks && dimTint == sigDimTint
                && owScale == sigOwScale && nScale == sigNetherScale
                && bcVersion == sigBcVersion && hiddenVer == sigHiddenVer
                && vpX == sigVpX && vpY == sigVpY
                && vpW == sigVpW && vpH == sigVpH) {
            return false;
        }
        sigBcVersion = bcVersion;
        sigHiddenVer = hiddenVer;
        sigSnap = snap;
        sigYaw = yaw;
        sigPitch = pitch;
        sigDistance = distance;
        sigSpacing = spacing;
        sigShowOw = showOw;
        sigShowN = showN;
        sigShowLinks = showLinks;
        sigDimTint = dimTint;
        sigOwScale = owScale;
        sigNetherScale = nScale;
        sigVpX = vpX;
        sigVpY = vpY;
        sigVpW = vpW;
        sigVpH = vpH;
        return true;
    }

    /**
     * 全点/リンク/マーカーを投影し、 深度ソートして描画順の確定配列へ焼く。 変化時のみ呼ばれる。
     * {@code ss}=スーパーサンプル倍率、 {@code stride}=点の間引き (⑨ 動作中=1+MOTION_STRIDE で安く)。
     */
    private void rebuildProjection(PointCloudSnapshot snap, int ss, int stride) {
        long t0 = System.nanoTime();
        float spacing = PointCloudViewState.getDimensionSpacing();
        float pivotY = spacing * 0.5f;
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);
        float cx = vpX + vpW * 0.5f;
        float cy = vpY + vpH * 0.5f;

        boolean showOw = PointCloudViewState.isShowOverworld();
        boolean showN = PointCloudViewState.isShowNether();
        // ㉓ 層ごとの表示スケール (XZ のみ・重心相対座標へ乗算＝重心基準の拡縮)。 GPU 経路と同一適用。
        float owScale = PointCloudViewState.getOwDisplayScale();
        float nScale = PointCloudViewState.getNetherDisplayScale();
        int owN = showOw ? snap.owX.length : 0;
        int nN = showN ? snap.nX.length : 0;
        ensureBuffers(owN + nN);

        // ⑤ 淡いディメンション色ティント (トグル時のみ・ブロック色へ dim 色を 15% 混ぜる)。 ライブ
        // 反映のため描画側で適用 (再解析不要)。 OFF なら純ブロック色 (=解析の色そのまま)。
        boolean tint = PointCloudViewState.isDimTint();

        int st = Math.max(1, stride);
        int total = 0;
        for (int i = 0; i < owN; i += st) {   // OW 層 (上＝広く疎: y += pivot)
            int c = tint ? mix(snap.owColor[i], DIM_TINT_OW, DIM_TINT_FRAC) : snap.owColor[i];
            total = project(snap.owX[i] * owScale, snap.owY[i] + pivotY, snap.owZ[i] * owScale, c,
                    cosY, sinY, cosP, sinP, cx, cy, total);
        }
        for (int i = 0; i < nN; i += st) {    // ネザー層 (下＝密なコンパクト塊: y -= pivot)
            int c = tint ? mix(snap.nColor[i], DIM_TINT_NETHER, DIM_TINT_FRAC) : snap.nColor[i];
            total = project(snap.nX[i] * nScale, snap.nY[i] - pivotY, snap.nZ[i] * nScale, c,
                    cosY, sinY, cosP, sinP, cx, cy, total);
        }

        // 深度範囲 (距離手がかり: 近=大/明、 遠=小/暗＝大気遠近で 3D に見せる)。
        float dMin = Float.MAX_VALUE;
        float dMax = -Float.MAX_VALUE;
        for (int i = 0; i < total; i++) {
            float d = bDepth[i];
            if (d < dMin) {
                dMin = d;
            }
            if (d > dMax) {
                dMax = d;
            }
        }
        float dSpan = (dMax > dMin) ? (dMax - dMin) : 1f;

        // 深度ソート → 描画順 (遠→近) の確定配列へ。 サイズ/明るさを深度から焼き込む。
        for (int i = 0; i < total; i++) {
            bOrder[i] = ((long) Float.floatToIntBits(bDepth[i]) << 32) | (i & 0xFFFFFFFFL);
        }
        Arrays.sort(bOrder, 0, total);
        ensureDrawArrays(total);
        int w = 0;
        for (int k = total - 1; k >= 0; k--) { // 末尾=遠 から
            int i = (int) (bOrder[k] & 0xFFFFFFFFL);
            float near = (dMax - bDepth[i]) / dSpan; // 0=最遠 .. 1=最近
            dX[w] = bSx[i];
            dY[w] = bSy[i];
            // フォールバック g.fill 用の整数径 (大半 1px・最近のみ 2px)。
            dSize[w] = POINT_MIN_PX + Math.round(near * near * POINT_SIZE_EXTRA);
            // ⑩ テクスチャ用半径 (論理px・×SSでネイティブ)。 小さく＝シャープ (遠 ~0.85 / 近 ~1.75)。
            dRad[w] = 0.85f + near * 0.9f;
            dColor[w] = dim(bColor[i], DEPTH_DIM_MIN + near * (1f - DEPTH_DIM_MIN));
            w++;
        }
        cachedCount = total;

        // ㉝C 非表示ゲートの番号集合 (リンク端点判定用・PortalMemory を live 参照)。
        java.util.Set<Integer> hidOwNum = new java.util.HashSet<>();
        java.util.Set<Integer> hidNethNum = new java.util.HashSet<>();
        collectHiddenGateNumbers(snap, hidOwNum, hidNethNum);

        // リンク端点を投影してキャッシュ (DDA は描画時にキャッシュ端点から)。
        cachedLinks = 0;
        if (PointCloudViewState.isShowLinks() && snap.linkCount() > 0) {
            ensureLinkArrays(snap.linkCount());
            for (int i = 0; i < snap.linkCount(); i++) {
                // ㉝C 端点どちらかが非表示ならリンク線を描かない。
                if (i < snap.gateMeta.linkOwNumber().length
                        && (hidOwNum.contains(snap.gateMeta.linkOwNumber()[i])
                                || hidNethNum.contains(snap.gateMeta.linkNNumber()[i]))) {
                    continue;
                }
                // ㉓ A 端=OW スケール / B 端=ネザースケール (地形と同じ層変換)。
                float[] a = projectXY(snap.linkAx[i] * owScale, snap.linkAy[i] + pivotY,
                        snap.linkAz[i] * owScale, cosY, sinY, cosP, sinP, cx, cy);
                float[] b = projectXY(snap.linkBx[i] * nScale, snap.linkBy[i] - pivotY,
                        snap.linkBz[i] * nScale, cosY, sinY, cosP, sinP, cx, cy);
                if (a == null || b == null) {
                    continue;
                }
                lkAx[cachedLinks] = a[0];
                lkAy[cachedLinks] = a[1];
                lkBx[cachedLinks] = b[0];
                lkBy[cachedLinks] = b[1];
                cachedLinks++;
            }
        }

        // ⑪ 既知ゲート位置を投影してキャッシュ (Gate links トグルに追従)。
        cachedGates = 0;
        if (PointCloudViewState.isShowLinks() && snap.gateCount() > 0) {
            ensureGateArrays(snap.gateCount());
            for (int i = 0; i < snap.gateCount(); i++) {
                if (i < snap.gateMeta.gateNumber().length && isGateHidden(snap.gateNether[i],
                        snap.gateMeta.gateWx()[i], snap.gateMeta.gateWy()[i], snap.gateMeta.gateWz()[i])) {
                    continue; // ㉝C 非表示ゲートは描かない
                }
                float gs = snap.gateNether[i] ? nScale : owScale; // ㉓ 当該 dim の層スケール
                float gy2 = snap.gateNether[i] ? snap.gateY[i] - pivotY : snap.gateY[i] + pivotY;
                float[] p = projectXY(snap.gateX[i] * gs, gy2, snap.gateZ[i] * gs,
                        cosY, sinY, cosP, sinP, cx, cy);
                if (p == null || p[0] < vpX || p[0] > vpX + vpW || p[1] < vpY || p[1] > vpY + vpH) {
                    continue;
                }
                gkX[cachedGates] = p[0];
                gkY[cachedGates] = p[1];
                gkCol[cachedGates] = gateColorAt(snap, i); // ㉚ 状態色をキャッシュ
                cachedGates++;
            }
        }

        // ㉕ back-calculate 要素を投影してキャッシュ (全 dim・gate と同一変換＝ズレない・Gate links と独立)。
        cachedBc = 0;
        List<BackCalcStore.Element> bc = BackCalcStore.all();
        if (!bc.isEmpty()) {
            ensureBcArrays(bc.size());
            for (BackCalcStore.Element e : bc) {
                float[] vv = snap.toViewSpace(e.dim, e.x, e.y, e.z);
                float es = (e.dim == PortalDimension.NETHER) ? nScale : owScale;
                float ey = (e.dim == PortalDimension.NETHER) ? vv[1] - pivotY : vv[1] + pivotY;
                float[] p = projectXY(vv[0] * es, ey, vv[2] * es, cosY, sinY, cosP, sinP, cx, cy);
                if (p == null || p[0] < vpX || p[0] > vpX + vpW || p[1] < vpY || p[1] > vpY + vpH) {
                    continue;
                }
                bcX[cachedBc] = p[0];
                bcY[cachedBc] = p[1];
                bcCol[cachedBc] = e.colorArgb;
                cachedBc++;
            }
        }

        // 現在地マーカーを投影してキャッシュ。
        mkVis = false;
        if (snap.hasMarker) {
            float ms = snap.markerNether ? nScale : owScale; // ㉓ 当該 dim の層スケール
            float my = snap.markerNether ? snap.markerY - pivotY : snap.markerY + pivotY;
            float[] m = projectXY(snap.markerX * ms, my, snap.markerZ * ms,
                    cosY, sinY, cosP, sinP, cx, cy);
            if (m != null && m[0] >= vpX && m[0] <= vpX + vpW && m[1] >= vpY && m[1] <= vpY + vpH) {
                mkVis = true;
                mkX = m[0];
                mkY = m[1];
            }
        }

        // バッチ: 投影結果を SS 倍テクスチャへラスタライズ (ビュー変化時のみ＝静止は blit だけ)。
        if (USE_TEXTURE_BATCH && !texFailed) {
            rasterizeTexture(ss);
        }
        lastBuildStride = st;
        lastBuildMotion = st > 1;
        lastBuildNanos = System.nanoTime() - t0; // rebuild ms にラスタライズ+upload も含める (動作中コスト)
    }

    /**
     * 静止フレームの描画。 バッチ経路では<b>テクスチャを 1 回 blit するだけ</b> (= 1 ドローコール・
     * 点数に依存しない)。 失敗時は従来の点ごと {@code g.fill} へフォールバック (描画途絶しない)。 所要時間と
     * ドローコール数を計測表示する (①計測: before=点数比例の fills / after=常に 1)。
     */
    private void drawCached(GuiGraphicsExtractor g) {
        long t0 = System.nanoTime();
        boolean texOk = USE_TEXTURE_BATCH && !texFailed && pcTex != null && texW > 0 && texH > 0
                && (cachedCount == 0 || lastRasterWrote > 0); // 書込み0 (rasterize 不発) なら g.fill へ
        if (texOk) {
            try {
                // ネイティブ解像度テクスチャ (texW×texH の<b>全域</b>) を viewport 論理矩形 (vpW×vpH) へ
                // <b>スケール blit</b> (12 引数: draw=vpW×vpH / source 領域=texW×texH 全域)。 10 引数版は
                // source 領域=draw サイズ＝vpW のため SS 倍テクスチャでは左上 1/SS しかサンプルせず不可視に
                // なる (0.20.0 リグレッションの原因)。 texSS=guiScale なら 1:1 ネイティブ＝くっきり。
                g.blit(RenderPipelines.GUI_TEXTURED, PC_TEX_ID, vpX, vpY, 0f, 0f, vpW, vpH, texW, texH,
                        texW, texH);
                lastFillCount = 1; // 1 ドローコール
                lastDrawNanos = System.nanoTime() - t0;
                return;
            } catch (Throwable t) {
                texFailed = true;
                com.kajiwara.visualizegate.VisualizeGateMod.LOGGER.warn(
                        "[visualizegate] point-cloud blit failed, falling back to g.fill: {}", t.toString());
            }
        }
        // フォールバック: 従来の点ごと g.fill (キャッシュ済み座標から)。
        fillCount = 0;
        for (int k = 0; k < cachedCount; k++) {
            drawDot(g, Math.round(dX[k]), Math.round(dY[k]), dSize[k], dColor[k]);
        }
        for (int i = 0; i < cachedLinks; i++) {
            drawSegment(g, lkAx[i], lkAy[i], lkBx[i], lkBy[i]);
        }
        if (mkVis) {
            drawMarker(g, Math.round(mkX), Math.round(mkY));
        }
        lastFillCount = fillCount;
        lastDrawNanos = System.nanoTime() - t0;
    }

    // ════════════════════════════════════════════════════════════════════
    // バッチ: DynamicTexture へのソフトドット・ラスタライズ (ビュー変化時のみ・Mixin 0)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 投影済みの点/リンク/マーカーを<b>ネイティブ解像度</b> (SS=guiScale) の ARGB スクラッチへ焼き、
     * NativeImage へ転送して upload (⑦ 解像度の本丸: GUI 論理解像度でなくネイティブで描く＝くっきり)。
     */
    private void rasterizeTexture(int ss) {
        try {
            int w = vpW * ss;
            int h = vpH * ss;
            if (w <= 0 || h <= 0) {
                texFailed = true; // ビューポート異常 → g.fill フォールバック
                return;
            }
            ensureTexture(w, h);
            if (pcTex == null || texW <= 0 || texH <= 0) {
                texFailed = true;
                return;
            }
            texSS = ss;
            Arrays.fill(pix, 0, w * h, 0);
            for (int k = 0; k < cachedCount; k++) {
                stampDot((dX[k] - vpX) * ss, (dY[k] - vpY) * ss, dRad[k] * ss, dColor[k], w, h);
            }
            for (int i = 0; i < cachedLinks; i++) {   // ⑫ 線は固定 1 ネイティブpx＝細く鮮明 (SS非乗算)
                rasterLine((lkAx[i] - vpX) * ss, (lkAy[i] - vpY) * ss, (lkBx[i] - vpX) * ss,
                        (lkBy[i] - vpY) * ss, GateColors.PC_LINK, LINK_THICK_NATIVE, w, h);
            }
            for (int i = 0; i < cachedGates; i++) {   // ㉔/㉙ ゲート枠＋内側の半透明ポータル面 (地形/点を透かす控えめα)
                float gfx = (gkX[i] - vpX) * ss;
                float gfy = (gkY[i] - vpY) * ss;
                int gcol = gkCol[i]; // ㉚ ゲート状態色 (キャッシュ済)
                rasterFrameFill(gfx, gfy, GATE_FRAME_HALF_W * ss, GATE_FRAME_HALF_H * ss,
                        gcol, GATE_FILL_ALPHA, w, h);
                rasterFrame(gfx, gfy, GATE_FRAME_HALF_W * ss, GATE_FRAME_HALF_H * ss,
                        gcol, ss, w, h);
            }
            for (int i = 0; i < cachedBc; i++) {      // ㉕ back-calculate 要素の黒曜石枠 (緑/赤・要素色)
                rasterFrame((bcX[i] - vpX) * ss, (bcY[i] - vpY) * ss, GATE_FRAME_HALF_W * ss,
                        GATE_FRAME_HALF_H * ss, bcCol[i], ss, w, h);
            }
            if (mkVis) {
                rasterMarker((mkX - vpX) * ss, (mkY - vpY) * ss, ss, w, h);
            }
            NativeImage img = pcTex.getPixels();
            if (img == null) {
                texFailed = true;
                return;
            }
            int idx = 0;
            int wrote = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = pix[idx++];
                    img.setPixelABGR(x, y, argbToAbgr(p));
                    if ((p & 0xFF000000) != 0) {
                        wrote++;
                    }
                }
            }
            lastRasterWrote = wrote;
            pcTex.upload();
        } catch (Throwable t) {
            texFailed = true;
            com.kajiwara.visualizegate.VisualizeGateMod.LOGGER.warn(
                    "[visualizegate] point-cloud texture batch failed, falling back to g.fill: {}",
                    t.toString());
        }
    }

    /** ビューポートサイズの DynamicTexture を用意 (サイズ変化時のみ作り直し＝再登録)。 */
    private void ensureTexture(int w, int h) {
        if (pcTex != null && texW == w && texH == h) {
            return;
        }
        releaseTexture();
        pcTex = new DynamicTexture(() -> "visualizegate pointcloud", w, h, false);
        this.minecraft.getTextureManager().register(PC_TEX_ID, pcTex);
        texW = w;
        texH = h;
        if (pix.length < w * h) {
            pix = new int[w * h];
        }
    }

    private static void releaseTexture() {
        if (pcTex != null) {
            try {
                pcTex.close();
            } catch (Throwable ignored) {
                // close 失敗は無視 (次の register で置換される)。
            }
            pcTex = null;
        }
    }

    /** スーパーサンプル倍率 = guiScale (≈ネイティブ)。 巨大画面のみ {@link #MAX_TEXELS} 上限で整数縮小。 */
    private int supersample() {
        int ss = Math.max(1, this.minecraft.getWindow().getGuiScale());
        while (ss > 1 && ((long) (vpW * ss) * (long) (vpH * ss) > MAX_TEXELS
                || vpW * ss > MAX_TEX_DIM || vpH * ss > MAX_TEX_DIM)) {
            ss--;
        }
        return ss;
    }

    /** 丸いソフトドット (中心明→縁透明)。 被覆度が高い点が色+αを決める (順不同・近大が勝つ)。 */
    private void stampDot(float cx, float cy, float radius, int argb, int w, int h) {
        int srcA = (argb >>> 24) & 0xFF;
        if (srcA <= 0 || radius <= 0f) {
            return;
        }
        int rgb = argb & 0xFFFFFF;
        int x0 = Math.max(0, (int) Math.floor(cx - radius));
        int x1 = Math.min(w - 1, (int) Math.ceil(cx + radius));
        int y0 = Math.max(0, (int) Math.floor(cy - radius));
        int y1 = Math.min(h - 1, (int) Math.ceil(cy + radius));
        for (int y = y0; y <= y1; y++) {
            float dyf = (y + 0.5f) - cy;
            int row = y * w;
            for (int x = x0; x <= x1; x++) {
                float dxf = (x + 0.5f) - cx;
                float d = (float) Math.sqrt(dxf * dxf + dyf * dyf);
                // ⑩ 硬い芯＋細い (1 テクセル) AA 縁: 芯は cov=1 (フェザー無し)、 縁だけ滑らか＝くっきり。
                float cov = (radius - d) + 0.5f;
                if (cov <= 0f) {
                    continue;
                }
                if (cov > 1f) {
                    cov = 1f;
                }
                int a8 = Math.round(srcA * cov);
                if (a8 <= 0) {
                    continue;
                }
                int idx = row + x;
                if (a8 > ((pix[idx] >>> 24) & 0xFF)) {
                    pix[idx] = (a8 << 24) | rgb;
                }
            }
        }
    }

    /** リンク線 (太さ {@code thick} テクセル≒1 論理px)。 最前として上書き。 */
    private void rasterLine(float ax, float ay, float bx, float by, int color, int thick, int w, int h) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = Math.max(Math.abs(dx), Math.abs(dy));
        if (len <= 0f) {
            return;
        }
        int steps = Math.min((int) len + 1, 16384);
        float sx = dx / steps;
        float sy = dy / steps;
        int a = (color >>> 24) & 0xFF;
        if (a == 0) {
            a = 0xFF;
        }
        int packed = (a << 24) | (color & 0xFFFFFF);
        int t = Math.max(1, thick);
        float px = ax;
        float py = ay;
        for (int s = 0; s <= steps; s++) {
            int ix = Math.round(px);
            int iy = Math.round(py);
            for (int dyy = 0; dyy < t; dyy++) {
                for (int dxx = 0; dxx < t; dxx++) {
                    putPix(ix + dxx, iy + dyy, packed, w, h);
                }
            }
            px += sx;
            py += sy;
        }
    }

    /** 現在地マーカー (金の十字)。 論理サイズを保つよう {@code ss} 倍でスケール。 */
    private void rasterMarker(float cxf, float cyf, int ss, int w, int h) {
        int cx = Math.round(cxf);
        int cy = Math.round(cyf);
        int c = 0xFF000000 | (GateColors.ACCENT & 0xFFFFFF);
        int arm = 6 * ss;
        int half = Math.max(0, ss);          // 縦横棒の太さ ≈1 論理px
        for (int d = -arm; d <= arm; d++) {
            for (int t = -half; t <= half; t++) {
                putPix(cx + d, cy + t, c, w, h); // 横棒
                putPix(cx + t, cy + d, c, w, h); // 縦棒
            }
        }
        int core = 2 * ss;
        for (int dy = -core; dy <= core; dy++) {
            for (int dx = -core; dx <= core; dx++) {
                putPix(cx + dx, cy + dy, c, w, h);
            }
        }
    }

    /**
     * ㉔ 中空の<b>縦長矩形フレーム</b> (黒曜石ポータル枠・紫ゲートマーカー)。 中心 (cxf,cyf)・半幅 {@code halfW}・
     * 半高 {@code halfH}・枠太さ {@code thick} テクセル (≒1 論理px×ss)。 内側は塗らない＝地形を隠さない。
     * texbatch (2D) 経路は射影中心のみ持つため、 画面整列の軸平行矩形 (固定軸既定向きの 2D 投影) で描く。
     */
    private void rasterFrame(float cxf, float cyf, float halfW, float halfH, int color, int thick,
            int w, int h) {
        int packed = 0xFF000000 | (color & 0xFFFFFF);
        int t = Math.max(1, thick);
        int x0 = Math.round(cxf - halfW);
        int x1 = Math.round(cxf + halfW);
        int y0 = Math.round(cyf - halfH);
        int y1 = Math.round(cyf + halfH);
        fillRectPix(x0, y0, x1 + 1, y0 + t, packed, w, h);     // 上桁
        fillRectPix(x0, y1 - t + 1, x1 + 1, y1 + 1, packed, w, h); // 下桁
        fillRectPix(x0, y0, x0 + t, y1 + 1, packed, w, h);     // 左柱
        fillRectPix(x1 - t + 1, y0, x1 + 1, y1 + 1, packed, w, h); // 右柱
    }

    /**
     * ㉙ ゲート内側の<b>半透明ポータル面</b>。 矩形内部を低 alpha 紫で塗る (texbatch はテクスチャ→地形 blit が
     * α 合成するので「面がある」感が出る)。 既に高 alpha が書かれた画素 (= 点群) は上書きしない＝点を透かす。
     */
    private void rasterFrameFill(float cxf, float cyf, float halfW, float halfH, int rgb, int alpha,
            int w, int h) {
        int packed = (alpha << 24) | (rgb & 0xFFFFFF);
        int x0 = Math.max(0, Math.round(cxf - halfW));
        int x1 = Math.min(w - 1, Math.round(cxf + halfW));
        int y0 = Math.max(0, Math.round(cyf - halfH));
        int y1 = Math.min(h - 1, Math.round(cyf + halfH));
        for (int y = y0; y <= y1; y++) {
            int row = y * w;
            for (int x = x0; x <= x1; x++) {
                int idx = row + x;
                if (((pix[idx] >>> 24) & 0xFF) < alpha) { // 点群 (高α) は保持・透明部のみ淡く塗る
                    pix[idx] = packed;
                }
            }
        }
    }

    /** ㉔ [x0,x1)×[y0,y1) を塗る (枠バー用)。 範囲外は putPix がクリップ。 */
    private static void fillRectPix(int x0, int y0, int x1, int y1, int c, int w, int h) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                putPix(x, y, c, w, h);
            }
        }
    }

    private static void putPix(int x, int y, int c, int w, int h) {
        if (x >= 0 && x < w && y >= 0 && y < h) {
            pix[y * w + x] = c;
        }
    }

    /** ARGB → NativeImage の ABGR パック (R↔B 入替・アルファ保持)。 */
    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * 1 点を投影してバッファへ積む。 visible なら total+1 を返す (cull 時は total そのまま)。
     */
    private int project(float x, float y, float z, int color,
            float cosY, float sinY, float cosP, float sinP, float cx, float cy, int total) {
        // ㊽B 注視点パン: look-at オフセットを引いてからオービット (GPU3D 8 引数 center と同一空間・同符号)。
        x -= panX;
        y -= panY;
        z -= panZ;
        float x1 = x * cosY + z * sinY;
        float z1 = -x * sinY + z * cosY;
        float y2 = y * cosP - z1 * sinP;
        float z2 = y * sinP + z1 * cosP;
        float depth = z2 + distance;
        if (depth <= NEAR) {
            return total;
        }
        float proj = focal / depth;
        float sx = cx + x1 * proj;
        float sy = cy - y2 * proj;
        if (sx < vpX || sx > vpX + vpW || sy < vpY || sy > vpY + vpH) {
            return total; // 中心がビューポート外 → 捨てる (手動クリップ)
        }
        bSx[total] = sx;
        bSy[total] = sy;
        bColor[total] = color;
        bDepth[total] = depth;
        return total + 1;
    }

    /** 投影済み端点から DDA で紫線を描く (約 2px 刻みの 2x2 ドット＝fill 数を半減)。 */
    private void drawSegment(GuiGraphicsExtractor g, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = Math.max(Math.abs(dx), Math.abs(dy));
        if (len <= 0f) {
            return;
        }
        int steps = Math.min((int) (len / 2f) + 1, 2048);
        float stepX = dx / steps;
        float stepY = dy / steps;
        float px = ax;
        float py = ay;
        for (int s = 0; s <= steps; s++) {
            int ix = Math.round(px);
            int iy = Math.round(py);
            if (ix >= vpX && ix <= vpX + vpW && iy >= vpY && iy <= vpY + vpH) {
                fillClamped(g, ix, iy, ix + 2, iy + 2, GateColors.PC_LINK);
            }
            px += stepX;
            py += stepY;
        }
    }

    /** 現在地マーカー (金の十字＝地形点と一目で区別)。 */
    private void drawMarker(GuiGraphicsExtractor g, int x, int y) {
        int arm = 6;
        fillClamped(g, x - arm, y - 1, x + arm, y + 1, GateColors.ACCENT); // 横棒
        fillClamped(g, x - 1, y - arm, x + 1, y + arm, GateColors.ACCENT); // 縦棒
        fillClamped(g, x - 2, y - 2, x + 2, y + 2, GateColors.ACCENT);     // 中心
    }

    /** 投影して screen (x,y) だけ返す (depth cull のみ・サイズ不要)。 cull なら null。 */
    private float[] projectXY(float x, float y, float z,
            float cosY, float sinY, float cosP, float sinP, float cx, float cy) {
        // ㊽B 注視点パン (project と同一)。
        x -= panX;
        y -= panY;
        z -= panZ;
        float x1 = x * cosY + z * sinY;
        float z1 = -x * sinY + z * cosY;
        float y2 = y * cosP - z1 * sinP;
        float z2 = y * sinP + z1 * cosP;
        float depth = z2 + distance;
        if (depth <= NEAR) {
            return null;
        }
        float proj = focal / depth;
        return new float[] { cx + x1 * proj, cy - y2 * proj };
    }

    /**
     * 丸いドットを描く (正方形タイルでなく円形シルエット＝Minecraft のドットグリッド脱却)。
     * 円は行スパンの fill で近似 (テクスチャ/blit 不使用＝版差なし・g.fill バッチに乗る)。 径が小さい遠点は
     * 1px、 近点ほど大きい円。 {@code s>=4} は中心やや上を明るくして球状の陰影感を出す。
     */
    private void drawDot(GuiGraphicsExtractor g, int cx, int cy, int s, int color) {
        if (s <= 1) {
            fillClamped(g, cx, cy, cx + 1, cy + 1, color); // 1 fill (大半の点)
            return;
        }
        if (s == 2) {
            fillClamped(g, cx, cy, cx + 2, cy + 2, color); // 2x2 を 1 fill で
            return;
        }
        int r = (s - 1) / 2;
        int r2 = r * r;
        for (int dy = -r; dy <= r; dy++) {
            int hw = (int) Math.round(Math.sqrt(Math.max(0, r2 - dy * dy)));
            int y = cy + dy;
            fillClamped(g, cx - hw, y, cx + hw + 1, y + 1, color);
        }
    }

    /** ARGB の RGB を t で線形ブレンド (a→b・アルファは a を保持)。 ⑤ ティント用。 */
    private static int mix(int a, int b, float t) {
        int al = (a >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    /** ARGB の RGB を係数 f で減衰 (アルファは保持・暗背景なのでアルファでなく明度を落とす)。 */
    private static int dim(int color, float f) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.round(((color >> 16) & 0xFF) * f);
        int gg = Math.round(((color >> 8) & 0xFF) * f);
        int b = Math.round((color & 0xFF) * f);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    private void fillClamped(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        int cx1 = Math.max(vpX, x1);
        int cy1 = Math.max(vpY, y1);
        int cx2 = Math.min(vpX + vpW, x2);
        int cy2 = Math.min(vpY + vpH, y2);
        if (cx2 > cx1 && cy2 > cy1) {
            g.fill(cx1, cy1, cx2, cy2, color);
            fillCount++;
        }
    }

    private void ensureBuffers(int n) {
        if (bSx.length < n) {
            bSx = new float[n];
            bSy = new float[n];
            bColor = new int[n];
            bDepth = new float[n];
            bOrder = new long[n];
        }
    }

    private void ensureDrawArrays(int n) {
        if (dX.length < n) {
            dX = new float[n];
            dY = new float[n];
            dSize = new int[n];
            dRad = new float[n];
            dColor = new int[n];
        }
    }

    private void ensureLinkArrays(int n) {
        if (lkAx.length < n) {
            lkAx = new float[n];
            lkAy = new float[n];
            lkBx = new float[n];
            lkBy = new float[n];
        }
    }

    private void ensureGateArrays(int n) {
        if (gkX.length < n) {
            gkX = new float[n];
            gkY = new float[n];
            gkCol = new int[n];
        }
    }

    private void ensureBcArrays(int n) {
        if (bcX.length < n) {
            bcX = new float[n];
            bcY = new float[n];
            bcCol = new int[n];
        }
    }

    // ── サイドバー: スライダ + 件数 ──

    private void drawSlider(GuiGraphicsExtractor g) {
        // ㉓ 表示スケール群 (2 カラム 1 行): OW / Nether。 基準形 (1:1 / 1:8) に重ねる倍率＝既定 1.0/1.0 で現状一致。
        float ow = PointCloudViewState.getOwDisplayScale();
        float nether = PointCloudViewState.getNetherDisplayScale();
        drawHalfTrack(g, Component.translatable("visualizegate.pc.scale.ow", fmtScale(ow)).getString(),
                slScaleOwX, slScaleHalfW, slScaleY,
                scaleToFrac(ow, PointCloudViewState.OW_SCALE_MIN, PointCloudViewState.OW_SCALE_MAX));
        drawHalfTrack(g, Component.translatable("visualizegate.pc.scale.nether", fmtScale(nether)).getString(),
                slScaleNX, slScaleHalfW, slScaleY,
                scaleToFrac(nether, PointCloudViewState.NETHER_SCALE_MIN, PointCloudViewState.NETHER_SCALE_MAX));

        int spacing = PointCloudViewState.getDimensionSpacing();
        drawTrack(g, Component.translatable("visualizegate.pc.spacing", spacing).getString(), slY,
                (float) (spacing - PointCloudViewState.SPACING_MIN)
                        / (PointCloudViewState.SPACING_MAX - PointCloudViewState.SPACING_MIN));
        // ⑭/㉒B GPU detail (1 層の最大描画点数)。 スライダ実効上限は現 stock に追従＝全域が密度に効く (死に区間なし)。
        int detail = PointCloudViewState.getGpuDetail();
        int stockMax = gpuStockMax();
        drawTrack(g, Component.translatable("visualizegate.pc.gpuDetail", detail, stockMax).getString(), sl2Y,
                (float) (detail - PointCloudViewState.DETAIL_MIN)
                        / Math.max(1, stockMax - PointCloudViewState.DETAIL_MIN));
        // ⑯ 点サイズ (px)。 小さく＝密で滑らか。
        int ps = PointCloudViewState.getPointSize();
        drawTrack(g, Component.translatable("visualizegate.pc.pointSize", ps).getString(), sl3Y,
                (float) (ps - PointCloudViewState.POINT_SIZE_MIN)
                        / (PointCloudViewState.POINT_SIZE_MAX - PointCloudViewState.POINT_SIZE_MIN));
    }

    /** スライダ 1 本 (ラベル上・トラック・ハンドル) を {@code trackY} に描く。 {@code frac}=0..1。 */
    private void drawTrack(GuiGraphicsExtractor g, String label, int trackY, float frac) {
        g.text(this.font, Component.literal(fitWidth(label, slW)), slX, trackY - 11, GateColors.TEXT); // ㉞ 幅追従省略
        g.fill(slX, trackY, slX + slW, trackY + slH, GateColors.PANEL);
        g.fill(slX, trackY, slX + slW, trackY + 1, GateColors.MAIN_DIM);
        frac = Math.max(0f, Math.min(1f, frac));
        int hx = slX + Math.round(frac * (slW - 6));
        g.fill(hx, trackY - 2, hx + 6, trackY + slH + 2, GateColors.MAIN);
    }

    /** ㉓ ハーフ幅スライダ (2 カラム用)。 ラベルは {@code tx} 起点・幅に収める。 {@code frac}=0..1。 */
    private void drawHalfTrack(GuiGraphicsExtractor g, String label, int tx, int tw, int trackY,
            float frac) {
        g.text(this.font, Component.literal(fitWidth(label, tw)), tx, trackY - 11, GateColors.TEXT);
        g.fill(tx, trackY, tx + tw, trackY + slH, GateColors.PANEL);
        g.fill(tx, trackY, tx + tw, trackY + 1, GateColors.MAIN_DIM);
        frac = Math.max(0f, Math.min(1f, frac));
        int hx = tx + Math.round(frac * (tw - 6));
        g.fill(hx, trackY - 2, hx + 6, trackY + slH + 2, GateColors.MAIN);
    }

    /** ㉓/㉘ 表示スケール → スライダ frac (対数: min×max=1 なら 1.0 が中央)。 範囲は層別に渡す。 */
    private static float scaleToFrac(float scale, float min, float max) {
        scale = Math.max(min, Math.min(max, scale));
        return (float) (Math.log(scale / min) / Math.log(max / min));
    }

    /** ㉓/㉘ スライダ frac → 表示スケール (対数・層別範囲)。 0.05 刻みへ丸めて値を安定させる。 */
    private static float fracToScale(float frac, float min, float max) {
        frac = Math.max(0f, Math.min(1f, frac));
        float s = (float) (min * Math.pow(max / min, frac));
        s = Math.round(s / 0.05f) * 0.05f; // 0.05 刻みで安定 (×1.00 などを取りやすく)
        return Math.max(min, Math.min(max, s));
    }

    /** ㉓ 表示スケールの簡潔表記 (整数なら ×2・小数は 2 桁)。 */
    private static String fmtScale(float s) {
        if (Math.abs(s - Math.round(s)) < 0.001f) {
            return String.valueOf(Math.round(s));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", s);
    }

    /**
     * ⑧ stats を再フロー: パネル内幅へ<b>収まる文字だけ</b> (超過は「…」省略)、 縦は<b>フッタ手前で打ち切り</b>
     * (Done ボタンと重ねない)。 GUI スケール 2/3/4 でも論理座標基準＝崩れない。
     */
    private void drawCounts(GuiGraphicsExtractor g, PointCloudSnapshot snap) {
        int x = slX;
        int maxW = slW;                          // インセット済みパネル内幅
        int bottom = this.height - FOOTER_H - 2; // フッタ(Done)を侵さない予約線
        int y = sl3Y + slH + 12;
        String mode = gpu3dActive ? ("gpu3d x" + texSS)
                : (USE_TEXTURE_BATCH && !texFailed)
                        ? ("tex x" + texSS + (lastBuildMotion ? " motion" : " settled")) : "fill";
        // ⑭/⑳ GPU3D 時は「表示数 / 在庫数」を表示＝予算(detail)が在庫を超えたら頭打ちと一目で分かる。
        if (gpu3dActive) {
            int owInv = snap.owX.length;
            int nInv = snap.nX.length;
            int detail = PointCloudViewState.getGpuDetail();
            y = statLine(g, tr("visualizegate.pc.stat.owPts", gpuOwPts + " / " + owInv),
                    x, y, maxW, bottom, GateColors.PC_OW_HIGH);
            y = statLine(g, tr("visualizegate.pc.stat.netherPts", gpuNPts + " / " + nInv), x, y, maxW, bottom,
                    GateColors.PC_NETHER_HIGH);
            String cap = (detail >= Math.max(owInv, nInv))
                    ? tr("visualizegate.pc.stat.stockCapped") : "";
            y = statLine(g, tr("visualizegate.pc.stat.shown",
                    (gpuOwPts + gpuNPts), (owInv + nInv), detail, cap), x, y, maxW, bottom, GateColors.TEXT);
        } else {
            y = statLine(g, tr("visualizegate.pc.stat.owPts", snap.owDrawn + "/" + snap.owSampled),
                    x, y, maxW, bottom, GateColors.PC_OW_HIGH);
            y = statLine(g, tr("visualizegate.pc.stat.netherPts", snap.netherDrawn + "/" + snap.netherSampled),
                    x, y, maxW, bottom, GateColors.PC_NETHER_HIGH);
        }
        y = statLine(g, tr("visualizegate.pc.stat.links", snap.linkCount()), x, y, maxW, bottom, GateColors.PC_LINK);
        if (snap.hasMarker) {
            y = statLine(g, tr("visualizegate.pc.stat.you"), x, y, maxW, bottom, GateColors.ACCENT);
        }
        // ⑱ snapshot 構築 (解析ワーカー) 所要＝高密度時の構築コスト確認用。 数値は言語非依存で format 済を arg 渡し。
        y = statLine(g, tr("visualizegate.pc.stat.snapBuild", String.format(java.util.Locale.ROOT, "%.1f",
                PointCloudAnalysis.get().lastBuildNanos() / 1.0e6)), x, y, maxW, bottom, GateColors.LINK_GRAY);
        y = statLine(g, tr("visualizegate.pc.stat.rebuild", String.format(java.util.Locale.ROOT, "%.2f",
                lastBuildNanos / 1.0e6)), x, y, maxW, bottom, GateColors.LINK_GRAY);
        y = statLine(g, tr("visualizegate.pc.stat.draw", String.format(java.util.Locale.ROOT, "%.2f",
                lastDrawNanos / 1.0e6), lastFillCount, mode), x, y, maxW, bottom, GateColors.LINK_GRAY);
        statLine(g, tr("visualizegate.pc.stat.help"), x, y, maxW, bottom, GateColors.LINK_GRAY);
    }

    /** translatable を現在言語で String 解決 (fitWidth/statLine は String を扱うため・既存 idiom)。 */
    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** 1 行: フッタ手前なら幅に収めて描き次の y を返す。 入るなら描かず y 据え置き (重なり防止)。 */
    private int statLine(GuiGraphicsExtractor g, String s, int x, int y, int maxW, int bottom, int color) {
        if (y + 9 > bottom) {
            return y;
        }
        g.text(this.font, Component.literal(fitWidth(s, maxW)), x, y, color);
        return y + 11;
    }

    /** 文字列を maxW(px) 以内へ。 超過は末尾を切って「…」を付す (フォント幅実測・共有 {@link TextFit})。 */
    private String fitWidth(String s, int maxW) {
        return TextFit.clip(this.font, s, maxW);
    }

    // ════════════════════════════════════════════════════════════════════
    // ㉚ タブ UI / Gates・Links 一覧 / 凡例 / 3D 番号ラベル・選択ハイライト
    // ════════════════════════════════════════════════════════════════════

    /** ㉜ GateState ordinal → 色は {@link GateColors} に一本化 (世界 UX と共有)。 ラベルは画面凡例用に保持。 */
    // ㉚ タブ名と5状態凡例ラベルの翻訳キー (en/ja 解決)。 凡例は従来 STATE_LABEL_JA で全言語日本語固定だったのを
    //    既存 state5.* (en=英語・ja=日本語) へ寄せて是正。 順序は GateState ordinal と一致 (ok/orphan/offset/will_create/conflict)。
    private static final String[] TAB_KEYS = {
            "visualizegate.pc.tab.view", "visualizegate.pc.tab.gates", "visualizegate.pc.tab.links" };
    private static final String[] STATE5_KEYS = {
            "visualizegate.state5.ok", "visualizegate.state5.orphan", "visualizegate.state5.offset",
            "visualizegate.state5.will_create", "visualizegate.state5.conflict" };

    private static int stateColor(int ord) {
        return GateColors.forStateOrdinal(ord);
    }

    /** ゲート添字 i の状態色 (gateMeta 未整列/範囲外は既定の明るい紫)。 */
    private static int gateColorAt(PointCloudSnapshot snap, int i) {
        int[] st = snap.gateMeta.gateState();
        return (i >= 0 && i < st.length) ? stateColor(st[i]) : GATE_FRAME_COLOR;
    }

    /** ㉝B ゲートの表示ラベル: ユーザー命名 (name) があればそれ、 無ければ既定 OW-n/N-n。 */
    private static String gateLabel(boolean nether, int number, int wx, int wy, int wz) {
        String name = PortalMemory.get().nameAt(
                nether ? PortalDimension.NETHER : PortalDimension.OVERWORLD, wx, wy, wz);
        return (name != null) ? name : GateLabels.defaultName(nether, number);
    }

    /** ㉝C 指定 anchor のゲートが非表示か (PortalMemory を live 参照＝トグル即反映)。 */
    private static boolean isGateHidden(boolean nether, int wx, int wy, int wz) {
        return PortalMemory.get().isHidden(
                nether ? PortalDimension.NETHER : PortalDimension.OVERWORLD, wx, wy, wz);
    }

    /** ㉝C 非表示ゲートの番号を dim 別集合へ集める (リンク端点が非表示ならリンク線を描かない判定に使う)。 */
    private static void collectHiddenGateNumbers(PointCloudSnapshot snap,
            java.util.Set<Integer> owNums, java.util.Set<Integer> nethNums) {
        GateMeta m = snap.gateMeta;
        int n = Math.min(snap.gateCount(), m.gateNumber().length);
        for (int i = 0; i < n; i++) {
            if (isGateHidden(snap.gateNether[i], m.gateWx()[i], m.gateWy()[i], m.gateWz()[i])) {
                (snap.gateNether[i] ? nethNums : owNums).add(m.gateNumber()[i]);
            }
        }
    }

    /**
     * ㉝C 行右端の目アイコン (省スペースの表示/非表示トグル)。 表示中=開いた目 (枠＋瞳)、 非表示=閉じた目 (線)。
     * 描画域は幅 9×高 6 程度。 クリック判定は {@link #inEyeIcon} と整合。
     */
    private void drawEyeIcon(GuiGraphicsExtractor g, int ex, int ey, boolean hidden) {
        int w = EYE_W - 2; // 9
        int midY = ey + 3;
        if (hidden) {
            // 閉じた目: 中央の横線 (暗色)。
            g.fill(ex, midY, ex + w, midY + 1, GateColors.LINK_GRAY);
        } else {
            // 開いた目: 上下の弧を横線で近似＋中央の瞳。
            g.fill(ex + 1, ey, ex + w - 1, ey + 1, GateColors.TEXT);          // 上まぶた
            g.fill(ex + 1, ey + 5, ex + w - 1, ey + 6, GateColors.TEXT);      // 下まぶた
            g.fill(ex, ey + 1, ex + 1, ey + 5, GateColors.TEXT);             // 左端
            g.fill(ex + w - 1, ey + 1, ex + w, ey + 5, GateColors.TEXT);     // 右端
            g.fill(ex + w / 2 - 1, midY - 1, ex + w / 2 + 1, midY + 1, GateColors.ACCENT); // 瞳
        }
    }

    /** ㉝C 行 r の目アイコンのクリック矩形か。 行 hit-test と同じ rowY0[r] 基準。 */
    private boolean inEyeIcon(int r, double mx, double my) {
        int ex = sbContentX + SIDE_PAD + (sidebarW - 2 * SIDE_PAD) - EYE_W;
        return mx >= ex && mx <= ex + EYE_W && my >= rowY0[r] && my < rowY0[r] + ROW_H;
    }

    private void drawTabBar(GuiGraphicsExtractor g) {
        int tw = sidebarW / 3;
        for (int i = 0; i < 3; i++) {
            int tx = sbContentX + i * tw;
            int tx2 = (i == 2) ? (sbContentX + sidebarW) : (tx + tw);
            boolean active = tab.ordinal() == i;
            g.fill(tx, tabBarY, tx2, tabBarY + TABBAR_H, active ? GateColors.MAIN_DIM : GateColors.PANEL);
            g.fill(tx, tabBarY, tx2, tabBarY + 1, active ? GateColors.MAIN : GateColors.MAIN_DIM);
            // ㊽ B-P2: タブ幅 (内側 -4px) に実測クリップ＝長訳が隣タブへはみ出さない。
            Component c = Component.literal(fitWidth(Component.translatable(TAB_KEYS[i]).getString(), tx2 - tx - 4));
            g.text(this.font, c, tx + (tx2 - tx) / 2 - this.font.width(c) / 2, tabBarY + 4,
                    active ? GateColors.ACCENT : GateColors.TEXT);
        }
    }

    /** ㉚ 5 状態の色キー (en/ja 解決) をサイドバー最下部に 1 行で。 初見向け。 */
    private void drawLegend(GuiGraphicsExtractor g) {
        int ly = this.height - FOOTER_H - 10;
        int x = sbContentX + SIDE_PAD;
        for (int i = 0; i < STATE5_KEYS.length; i++) {
            g.fill(x, ly + 1, x + 6, ly + 7, GateColors.forStateOrdinal(i));
            Component c = Component.translatable(STATE5_KEYS[i]);
            g.text(this.font, c, x + 8, ly, GateColors.TEXT);
            x += 8 + this.font.width(c) + 6;
        }
    }

    /** ㉚B Gates 一覧 (番号・次元・座標・状態色)。 スクロール＋行 hit-test。 行クリックで選択。 */
    private void drawGatesList(GuiGraphicsExtractor g, PointCloudSnapshot snap) {
        GateMeta m = snap.gateMeta;
        int n = m.gateNumber().length;
        int x = sbContentX + SIDE_PAD;
        int maxW = sidebarW - 2 * SIDE_PAD;
        g.text(this.font, Component.literal(fitWidth(tr("visualizegate.pc.heading.gates", n), maxW)),
                x, listTop, GateColors.TEXT); // ㉞ 幅追従省略
        int top = listTop + 12;
        int bottom = listBottom - LEGEND_RESERVE_H; // 凡例分を残す
        // ㉝A スクロール上限クランプ (空スクロール解消): 内容高がビュー高を超えた分だけ可動。
        gatesScroll = clampScroll(gatesScroll, n * ROW_H, bottom - top);
        ensureRowArrays(n);
        rowCount = 0;
        int selIdx = selectedGateIndex(snap); // ㉛ 選択は一意 anchor で 1 件だけ
        int yBase = top - gatesScroll;
        // ㉝A 一覧の可動領域に scissor クリップ (上端/下端で半端行が見出し/凡例へはみ出さない)。
        g.enableScissor(sbContentX, top, sbContentX + sidebarW, bottom);
        for (int i = 0; i < n; i++) {
            int ry = yBase + i * ROW_H;
            boolean nether = snap.gateNether[i];
            int num = m.gateNumber()[i];
            if (ry + ROW_H >= top && ry <= bottom) { // 可視行のみ描画 (scissor で端はクリップ)
                int wx = m.gateWx()[i];
                int wy = m.gateWy()[i];
                int wz = m.gateWz()[i];
                boolean hidden = isGateHidden(nether, wx, wy, wz); // ㉝C 非表示ゲートはグレーアウト
                if (i == selIdx) {
                    g.fill(x - 2, ry - 1, x + maxW, ry + ROW_H - 1, GateColors.MAIN_DIM);
                }
                int col = hidden ? GateColors.MAIN_DIM : stateColor(m.gateState()[i]);
                g.fill(x, ry + 1, x + 6, ry + 8, col);
                String label = gateLabel(nether, num, wx, wy, wz); // ㉝B name 優先
                String s = label + "  " + wx + "," + wy + "," + wz;
                int textCol = hidden ? GateColors.LINK_GRAY : GateColors.TEXT;
                g.text(this.font, Component.literal(fitWidth(s, maxW - 10 - EYE_W)), x + 9, ry, textCol);
                drawEyeIcon(g, x + maxW - EYE_W + 1, ry + 2, hidden); // ㉝C 表示/非表示トグル
            }
            rowWx[rowCount] = m.gateWx()[i];
            rowWy[rowCount] = m.gateWy()[i];
            rowWz[rowCount] = m.gateWz()[i];
            rowNether[rowCount] = nether ? 1 : 0;
            rowNumber[rowCount] = num;
            rowY0[rowCount] = ry;
            rowCount++;
        }
        g.disableScissor();
    }

    /** ㉝A スクロール量を [0, max(0, 内容高 - ビュー高)] にクランプ。 */
    private static int clampScroll(int scroll, int contentH, int viewH) {
        int max = Math.max(0, contentH - viewH);
        return Math.max(0, Math.min(scroll, max));
    }

    /** ㉛ 選択中ゲートのスナップショット添字 (一意 anchor+dim で 1 件・無ければ -1)。 */
    private int selectedGateIndex(PointCloudSnapshot snap) {
        if (!hasSel) {
            return -1;
        }
        GateMeta m = snap.gateMeta;
        int n = Math.min(snap.gateCount(), m.gateNumber().length);
        for (int i = 0; i < n; i++) {
            if (m.gateWx()[i] == selWx && m.gateWy()[i] == selWy && m.gateWz()[i] == selWz
                    && snap.gateNether[i] == selNether) {
                return i;
            }
        }
        return -1;
    }

    /** ㉚D Links/Conflicts 一覧。 コンフリクトを重大度順に＋接続ペア。 色分け＋素の日本語。 行クリックで選択。 */
    private void drawLinksList(GuiGraphicsExtractor g, PointCloudSnapshot snap) {
        GateMeta m = snap.gateMeta;
        int x = sbContentX + SIDE_PAD;
        int maxW = sidebarW - 2 * SIDE_PAD;
        List<GateConflict> conflicts = m.conflicts();
        g.text(this.font, Component.literal(fitWidth(tr("visualizegate.pc.heading.conflictsLinks",
                conflicts.size(), m.linkOwNumber().length), maxW)), x, listTop, GateColors.TEXT); // ㉞ 幅追従省略
        int top = listTop + 12;
        int bottom = listBottom - LEGEND_RESERVE_H;
        rowCount = 0; // Links タブの行クリックは未対応 (選択は Gates タブ)。 スクロールのみ。
        // ㉝A スクロール上限クランプ (内容高 = コンフリクト + 4px 区切り + 接続ペア)。
        int contentH = conflicts.size() * ROW_H + 4 + m.linkOwNumber().length * ROW_H;
        linksScroll = clampScroll(linksScroll, contentH, bottom - top);
        int y = top - linksScroll;
        // ㉝A 一覧の可動領域に scissor クリップ (見出し/凡例は領域外＝不変)。
        g.enableScissor(sbContentX, top, sbContentX + sidebarW, bottom);
        for (GateConflict c : conflicts) { // 重大度順 (解析器で sort 済)
            if (y + ROW_H >= top && y <= bottom) {
                int col = stateColor(c.state().ordinal());
                g.fill(x, y + 1, x + 6, y + 8, col);
                g.text(this.font, Component.literal(fitWidth(c.reasonJa(), maxW - 10)), x + 9, y, GateColors.TEXT);
            }
            y += ROW_H;
        }
        y += 4;
        for (int i = 0; i < m.linkOwNumber().length; i++) {
            if (y + ROW_H >= top && y <= bottom) {
                String s = GateLabels.OW_PREFIX + m.linkOwNumber()[i]
                        + " ↔ " + GateLabels.N_PREFIX + m.linkNNumber()[i];
                g.fill(x, y + 1, x + 6, y + 8, GateColors.STATE_OK);
                g.text(this.font, Component.literal(fitWidth(s, maxW - 10)), x + 9, y, GateColors.LINK_GRAY);
            }
            y += ROW_H;
        }
        g.disableScissor();
    }

    private void ensureRowArrays(int n) {
        if (rowWx.length < n) {
            rowWx = new int[n];
            rowWy = new int[n];
            rowWz = new int[n];
            rowNether = new int[n];
            rowNumber = new int[n];
            rowY0 = new int[n];
        }
    }

    // ── ㉚C 3D 番号ラベル・状態色・選択ハイライト (3D パス後の 2D オーバーレイ) ──

    /** ゲート i の<b>ビュー空間</b>位置 (gate と同一: ÷8＋表示スケール＋spacing)。 */
    private float[] gateViewPos(PointCloudSnapshot snap, int i) {
        boolean nether = snap.gateNether[i];
        float gs = nether ? PointCloudViewState.getNetherDisplayScale()
                : PointCloudViewState.getOwDisplayScale();
        float pivotY = PointCloudViewState.getDimensionSpacing() * 0.5f;
        float vx = snap.gateX[i] * gs;
        float vy = nether ? snap.gateY[i] - pivotY : snap.gateY[i] + pivotY;
        float vz = snap.gateZ[i] * gs;
        return new float[] { vx, vy, vz };
    }

    /** ビュー空間点 → 画面 px。 GPU3D は joml で render() と同一投影を再現、 texbatch は projectXY を流用。 null=可視外。 */
    private float[] projectToScreen(float vx, float vy, float vz) {
        if (gpu3dActive) {
            float aspect = (float) vpW / (float) vpH;
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), aspect, 0.1f, 8000f, true);
            // ㊽B 注視点パン: GPU3D の view (T(0,0,-d)·Rx·Ry·T(-pan)) と一致させ、 ラベルもパンに追従。
            Matrix4f view = new Matrix4f().translation(0f, 0f, -distance).rotateX(pitch).rotateY(yaw)
                    .translate(-panX, -panY, -panZ);
            Vector4f clip = proj.mul(view, new Matrix4f()).transform(new Vector4f(vx, vy, vz, 1f));
            if (clip.w() <= 1e-4f) {
                return null;
            }
            float ndcX = clip.x() / clip.w();
            float ndcY = clip.y() / clip.w();
            float sx = vpX + (ndcX * 0.5f + 0.5f) * vpW;
            float sy = vpY + (1f - (ndcY * 0.5f + 0.5f)) * vpH;
            return new float[] { sx, sy };
        }
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);
        return projectXY(vx, vy, vz, cosY, sinY, cosP, sinP, vpX + vpW * 0.5f, vpY + vpH * 0.5f);
    }

    /** 各ゲート脇に採番ラベルを描き、 選択ゲートを 2D ハイライト＋相手への線を強調 (Mixin 不使用)。 */
    private void drawGateLabels(GuiGraphicsExtractor g, PointCloudSnapshot snap) {
        GateMeta m = snap.gateMeta;
        int n = Math.min(snap.gateCount(), m.gateNumber().length);
        int selIdx = selectedGateIndex(snap); // ㉛ 選択は一意 anchor で 1 件のみ
        float selSx = Float.NaN;
        float selSy = 0;
        for (int i = 0; i < n; i++) {
            boolean nether = snap.gateNether[i];
            if (nether ? !PointCloudViewState.isShowNether() : !PointCloudViewState.isShowOverworld()) {
                continue;
            }
            if (isGateHidden(nether, m.gateWx()[i], m.gateWy()[i], m.gateWz()[i])) {
                continue; // ㉝C 非表示ゲートはラベル/選択ハイライトを描かない
            }
            float[] v = gateViewPos(snap, i);
            float[] s = projectToScreen(v[0], v[1], v[2]);
            if (s == null || s[0] < vpX || s[0] > vpX + vpW || s[1] < vpY || s[1] > vpY + vpH) {
                continue;
            }
            int sx = Math.round(s[0]);
            int sy = Math.round(s[1]);
            boolean sel = i == selIdx;
            int col = stateColor(m.gateState()[i]);
            if (sel) {
                selSx = s[0];
                selSy = s[1];
                g.fill(sx - 6, sy - 6, sx + 6, sy - 5, GateColors.ACCENT); // 選択枠 (上)
                g.fill(sx - 6, sy + 5, sx + 6, sy + 6, GateColors.ACCENT); // (下)
                g.fill(sx - 6, sy - 6, sx - 5, sy + 6, GateColors.ACCENT); // (左)
                g.fill(sx + 5, sy - 6, sx + 6, sy + 6, GateColors.ACCENT); // (右)
            }
            if (showLabels || sel) {
                String lbl = gateLabel(nether, m.gateNumber()[i],
                        m.gateWx()[i], m.gateWy()[i], m.gateWz()[i]); // ㉝B name 優先
                g.text(this.font, Component.literal(lbl), sx + 7, sy - 4, sel ? GateColors.ACCENT : col);
            }
        }
        // 選択ゲートの相手への線を強調 (2D・両経路共通)。
        if (!Float.isNaN(selSx)) {
            int partnerIdx = partnerGateIndex(snap);
            if (partnerIdx >= 0) {
                float[] pv = gateViewPos(snap, partnerIdx);
                float[] ps = projectToScreen(pv[0], pv[1], pv[2]);
                if (ps != null) {
                    drawThickLine(g, selSx, selSy, ps[0], ps[1], GateColors.ACCENT);
                }
            }
        }
    }

    /** ㉛ 選択ゲートの接続相手のゲート添字 (確定ペアから・採番ユニーク前提)。 無ければ -1。 */
    private int partnerGateIndex(PointCloudSnapshot snap) {
        int selIdx = selectedGateIndex(snap);
        if (selIdx < 0) {
            return -1;
        }
        GateMeta m = snap.gateMeta;
        int selNum = m.gateNumber()[selIdx];
        int partnerNum = -1;
        boolean partnerNether = false;
        for (int i = 0; i < m.linkOwNumber().length; i++) {
            if (!selNether && m.linkOwNumber()[i] == selNum) {
                partnerNum = m.linkNNumber()[i];
                partnerNether = true;
                break;
            }
            if (selNether && m.linkNNumber()[i] == selNum) {
                partnerNum = m.linkOwNumber()[i];
                partnerNether = false;
                break;
            }
        }
        if (partnerNum <= 0) {
            return -1;
        }
        for (int i = 0; i < snap.gateCount(); i++) {
            if (m.gateNumber()[i] == partnerNum && snap.gateNether[i] == partnerNether) {
                return i;
            }
        }
        return -1;
    }

    /** 2D の太線 (選択リンク強調・DDA で 2px)。 */
    private void drawThickLine(GuiGraphicsExtractor g, float ax, float ay, float bx, float by, int color) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = Math.max(Math.abs(dx), Math.abs(dy));
        if (len <= 0f) {
            return;
        }
        int steps = Math.min((int) len, 4096);
        float sx = dx / steps;
        float sy = dy / steps;
        float px = ax;
        float py = ay;
        for (int s = 0; s <= steps; s++) {
            int ix = Math.round(px);
            int iy = Math.round(py);
            if (ix >= vpX && ix <= vpX + vpW && iy >= vpY && iy <= vpY + vpH) {
                g.fill(ix, iy, ix + 2, iy + 2, color);
            }
            px += sx;
            py += sy;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 入力 (MouseButtonEvent: 26.1.2/1.21.11/1.21.10 同一・javap 確認済)
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        // ㉞ スプリッターの掴み (回転/一覧より先に hit-test)。
        if (event.button() == 0 && inSplitter(mx, my)) {
            drag = Drag.SPLITTER;
            lastInputNanos = System.nanoTime(); // ⑨ ドラッグ中 → SS=1 (安価な FBO リサイズ)
            return true;
        }
        // ㉚ タブバークリック → タブ切替。
        if (event.button() == 0 && my >= tabBarY && my <= tabBarY + TABBAR_H
                && mx >= sbContentX && mx <= sbContentX + sidebarW) {
            int i = (int) ((mx - sbContentX) / (sidebarW / 3));
            Tab[] tabs = Tab.values();
            tab = tabs[Math.max(0, Math.min(2, i))];
            applyTabVisibility();
            return true;
        }
        // ㉝B 一覧の押下: 短クリック=選択 / ダブルクリック=リネームポップアップ / ドラッグ=スクロール。
        if (event.button() == 0 && (tab == Tab.GATES || tab == Tab.LINKS) && inListArea(mx, my)) {
            // ㉝C 目アイコンのクリック → 表示/非表示トグル (選択・リネーム・スクロールの対象外)。
            if (tab == Tab.GATES) {
                for (int r = 0; r < rowCount; r++) {
                    if (inEyeIcon(r, mx, my)) {
                        PortalMemory.get().setHidden(
                                rowNether[r] != 0 ? PortalDimension.NETHER : PortalDimension.OVERWORLD,
                                rowWx[r], rowWy[r], rowWz[r],
                                !isGateHidden(rowNether[r] != 0, rowWx[r], rowWy[r], rowWz[r]));
                        return true;
                    }
                }
                // 行をダブルクリック → 金床式リネームポップアップを開く (目アイコン上は除外＝上で処理済)。
                if (doubleClick) {
                    for (int r = 0; r < rowCount; r++) {
                        if (my >= rowY0[r] && my < rowY0[r] + ROW_H) {
                            openRename(r);
                            drag = Drag.NONE;
                            pressRow = -1;
                            return true;
                        }
                    }
                }
            }
            drag = Drag.LIST_SCROLL;
            pressMoved = false;
            pressX = mx;
            pressY = my;
            pressScroll0 = (tab == Tab.GATES) ? gatesScroll : linksScroll;
            pressRow = -1;
            if (tab == Tab.GATES) {
                for (int r = 0; r < rowCount; r++) {
                    if (my >= rowY0[r] && my < rowY0[r] + ROW_H) {
                        pressRow = r; // 選択対象行
                        break;
                    }
                }
            }
            return true; // 一覧上の押下は吸収
        }
        // ㉚ スライダ操作は View タブのみ。
        if (tab == Tab.VIEW && event.button() == 0 && inSlider(mx, my)) {
            drag = Drag.SLIDER;
            setSpacingFromMouse(mx);
            return true;
        }
        if (tab == Tab.VIEW && event.button() == 0 && inSlider2(mx, my)) {
            drag = Drag.DETAIL;
            setDetailFromMouse(mx);
            return true;
        }
        if (tab == Tab.VIEW && event.button() == 0 && inSlider3(mx, my)) {
            drag = Drag.POINTSIZE;
            setPointSizeFromMouse(mx);
            return true;
        }
        if (tab == Tab.VIEW && event.button() == 0 && inScaleOw(mx, my)) {
            drag = Drag.SCALE_OW;
            setOwScaleFromMouse(mx);
            return true;
        }
        if (tab == Tab.VIEW && event.button() == 0 && inScaleNether(mx, my)) {
            drag = Drag.SCALE_NETHER;
            setNetherScaleFromMouse(mx);
            return true;
        }
        // 中央 [キャプチャを有効化] ボタンが出ているときは vp 内でもボタンを優先 (回転より先に widget へ委譲)。
        if (event.button() == 0 && captureEnableBtn != null && captureEnableBtn.visible
                && captureEnableBtn.isMouseOver(mx, my)) {
            return super.mouseClicked(event, doubleClick);
        }
        if (event.button() == 0 && inViewport(mx, my)) {
            drag = Drag.ROTATE;
            return true;
        }
        // ㊽B 中ボタン (index 2): ダブルクリックで recenter (pan=0)、 単押しで注視点パン開始。
        if (event.button() == 2 && inViewport(mx, my)) {
            if (doubleClick) {
                recenterPan();
            } else {
                drag = Drag.PAN;
            }
            lastInputNanos = System.nanoTime(); // ⑨ 操作中 → SS=1
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (drag == Drag.SLIDER) {
            setSpacingFromMouse(event.x());
            return true;
        }
        if (drag == Drag.DETAIL) {
            setDetailFromMouse(event.x());
            return true;
        }
        if (drag == Drag.POINTSIZE) {
            setPointSizeFromMouse(event.x());
            return true;
        }
        if (drag == Drag.SCALE_OW) {
            setOwScaleFromMouse(event.x());
            return true;
        }
        if (drag == Drag.SCALE_NETHER) {
            setNetherScaleFromMouse(event.x());
            return true;
        }
        if (drag == Drag.ROTATE) {
            yaw += (float) (dragX * DRAG_SENS);
            pitch += (float) (dragY * DRAG_SENS);
            pitch = Math.max(-1.5f, Math.min(1.5f, pitch));
            lastInputNanos = System.nanoTime(); // ⑨ 動作中 → SS=1
            return true;
        }
        // ㊽B 注視点パン: 画面 px デルタをカメラ right/up へ沿わせ look-at オフセットへ加算 (点群がカーソルに付いてくる)。
        if (drag == Drag.PAN) {
            panFromDrag(dragX, dragY);
            lastInputNanos = System.nanoTime(); // ⑨ 動作中 → SS=1
            return true;
        }
        // ㉞ スプリッタードラッグ: サイドバー幅をマウス X 追従でライブ更新＋全レイアウト再計算。
        if (drag == Drag.SPLITTER) {
            int desired = clampSidebar((int) Math.round(this.width - event.x() - MARGIN));
            PointCloudViewState.setSidebarWidth(desired);
            recomputeLayout();
            lastInputNanos = System.nanoTime(); // ⑨ ドラッグ中 → SS=1 (安価な FBO リサイズ)
            return true;
        }
        // ㉝B 一覧ドラッグ=スクロール (CLICK_SLOP 超で選択/長押しをキャンセル)。 上ドラッグ=下スクロール。
        if (drag == Drag.LIST_SCROLL) {
            if (Math.abs(event.x() - pressX) > CLICK_SLOP || Math.abs(event.y() - pressY) > CLICK_SLOP) {
                pressMoved = true;
            }
            int delta = (int) Math.round(pressY - event.y());
            if (tab == Tab.GATES) {
                gatesScroll = Math.max(0, pressScroll0 + delta);
            } else if (tab == Tab.LINKS) {
                linksScroll = Math.max(0, pressScroll0 + delta);
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (drag == Drag.SLIDER || drag == Drag.DETAIL || drag == Drag.POINTSIZE
                || drag == Drag.SCALE_OW || drag == Drag.SCALE_NETHER) {
            GateConfigManager.save();
        }
        // ㉞ スプリッター確定 → サイドバー幅を永続化。
        if (drag == Drag.SPLITTER) {
            GateConfigManager.save();
            drag = Drag.NONE;
            return true;
        }
        // ㉝B 一覧の短クリック (動かず) → ゲート選択 (ダブルクリックは mouseClicked でリネームを開き消費済)。
        if (drag == Drag.LIST_SCROLL && tab == Tab.GATES && !pressMoved && pressRow >= 0
                && pressRow < rowCount) {
            selWx = rowWx[pressRow]; // ㉛ 選択を一意 anchor で保持 (1 クリック 1 ゲート)
            selWy = rowWy[pressRow];
            selWz = rowWz[pressRow];
            selNether = rowNether[pressRow] != 0;
            hasSel = true;
        }
        pressRow = -1;
        if (drag == Drag.LIST_SCROLL) {
            drag = Drag.NONE;
            return true;
        }
        drag = Drag.NONE;
        return super.mouseReleased(event);
    }

    // ── ㊽B 注視点パン (中ボタンドラッグ) ──────────────────────────────────

    /**
     * 画面 px デルタ {@code (dx,dy)} をカメラの right/up ベクトルへ沿わせ look-at オフセットへ加算する。
     * right = (cosY, 0, sinY) / up = (sinP·sinY, cosP, -sinP·cosY) (view = T·Rx·Ry の逆基底)。 符号は
     * 「点群がカーソルに付いてくる」向き (ドラッグ右で注視点が左へ＝点群が右へ)。
     */
    private void panFromDrag(double dx, double dy) {
        float wpp = worldPerPixel();
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);
        float rX = cosY, rY = 0f, rZ = sinY;               // camera right (world)
        float uX = sinP * sinY, uY = cosP, uZ = -sinP * cosY; // camera up (world)
        float sR = (float) (-dx * wpp);
        float sU = (float) (dy * wpp);
        panX += sR * rX + sU * uX;
        panY += sR * rY + sU * uY;
        panZ += sR * rZ + sU * uZ;
    }

    /**
     * 1 画面 px あたりのワールド距離 (アクティブパスの投影に合わせ 1:1 追従)。 distance 比例＝ズーム非依存の速度。
     * GPU3D は縦 FOV 70°、 software はピンホール focal/depth。
     */
    private float worldPerPixel() {
        if (gpu3dActive) {
            return (float) (distance * 2.0 * Math.tan(Math.toRadians(35.0)) / Math.max(1, vpH));
        }
        return distance / Math.max(1f, focal);
    }

    /** recenter: 注視点パンを 0 (= fit 中心=原点) へ戻す。 yaw/pitch/distance は保持。 中ボタンダブルクリック。 */
    private void recenterPan() {
        panX = 0f;
        panY = 0f;
        panZ = 0f;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inViewport(mouseX, mouseY)) {
            distance *= (float) Math.pow(0.88, scrollY);
            // ㊽C 近接インスペクト: 最小到達距離を radius×0.2/床5 → radius×0.04/床2 へ下げ約5倍寄れる。
            //     スプラットは両パスとも小径上限ありで巨大化せず、 寄ると点間に隙間が見える(点群本来の見え方)。
            //     near クリップ(0.1)/fit 既定/recenter/maxDist は不変。
            float min = (framedFor != null) ? Math.max(framedFor.radius * 0.04f, 2f) : 2f;
            float max = (framedFor != null) ? framedFor.radius * 12f + 200f : 5000f;
            distance = Math.max(min, Math.min(max, distance));
            lastInputNanos = System.nanoTime(); // ⑨ ズーム中 → SS=1 (settle で再ネイティブ)
            return true;
        }
        // ㉚ Gates/Links 一覧上のホイール → スクロール。
        if (inListArea(mouseX, mouseY)) {
            int step = (int) (-scrollY * ROW_H * 2);
            if (tab == Tab.GATES) {
                gatesScroll = Math.max(0, gatesScroll + step);
            } else if (tab == Tab.LINKS) {
                linksScroll = Math.max(0, linksScroll + step);
            }
            return true;
        }
        // ㊿ View タブ: サイドバー帯上のホイール → トグル/スライダ群をスクロール (極小高で末段 Point size へ到達)。
        //    収まる通常域では viewContentH ≤ band ゆえ clampScroll が 0 に固定＝何も動かない (回帰ゼロ)。
        if (tab == Tab.VIEW && mouseX >= sbContentX && mouseX <= sbContentX + sidebarW
                && mouseY >= listTop && mouseY <= listBottom) {
            int step = (int) (-scrollY * ROW_H * 2);
            viewScroll = clampScroll(viewScroll + step, viewContentH, listBottom - listTop);
            recomputeLayout(); // ボタン位置/可視を新オフセットへ即同期 (hit-test と desync させない)
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ── ㉝B 行リネーム (ダブルクリック → 金床式 GateRenameScreen ポップアップ・name を anchorKey 永続) ──

    /**
     * 行 row のゲートを<b>金床式の専用ポップアップ</b> {@link GateRenameScreen} で改名する (インライン編集を置換)。
     * 子画面が init/setInitialFocus 経路で IME-aware を確立＝変換中文字がインライン表示。 確定/取消で本画面へ戻る
     * (parent 保持＝リスト/スクロール/選択は維持)。 名前は子画面が {@code setName} で永続 (本画面は読むだけ)。
     */
    private void openRename(int row) {
        PortalDimension dim = (rowNether[row] != 0) ? PortalDimension.NETHER : PortalDimension.OVERWORLD;
        //? if >=26.2 {
        /*this.minecraft.setScreenAndShow(new GateRenameScreen(
                this, dim, rowWx[row], rowWy[row], rowWz[row], rowNumber[row]));*/
        //?} else {
        this.minecraft.setScreen(new GateRenameScreen(
                this, dim, rowWx[row], rowWy[row], rowWz[row], rowNumber[row]));
        //?}
    }

    private boolean inViewport(double mx, double my) {
        return mx >= vpX && mx <= vpX + vpW && my >= vpY && my <= vpY + vpH;
    }

    /** ㉞ スプリッターのグリップ中心 X (vp 右端とサイドバー左端の MARGIN ギャップ中央)。 */
    private int splitterCenterX() {
        return sbContentX - MARGIN / 2;
    }

    /** ㉞ スプリッターの掴み帯か (vp 高さの縦帯・回転より先に hit-test される)。 */
    private boolean inSplitter(double mx, double my) {
        int cx = splitterCenterX();
        return mx >= cx - SPLITTER_GRAB_HALF && mx <= cx + SPLITTER_GRAB_HALF
                && my >= vpY && my <= vpY + vpH;
    }

    /** ㉞ 可動境界の縦グリップ (細線＋中央の掴み目印)。 ホバー/ドラッグ中は明色で強調 (カーソル変更の代替)。 */
    private void drawSplitter(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx = splitterCenterX();
        boolean active = (drag == Drag.SPLITTER) || inSplitter(mouseX, mouseY);
        int col = active ? GateColors.ACCENT : GateColors.MAIN_DIM;
        // 縦線 (vp 高さ)。
        g.fill(cx, vpY, cx + 1, vpY + vpH, col);
        // 中央の掴み目印 (3 点の短いドット＝ハンドル)。
        int my = vpY + vpH / 2;
        for (int d = -1; d <= 1; d++) {
            int dy = my + d * 4;
            g.fill(cx - 1, dy - 1, cx + 2, dy + 1, col);
        }
    }

    /** ㉚ サイドバーの一覧領域 (タブバー下〜フッタ手前)。 */
    private boolean inListArea(double mx, double my) {
        // 下端は凡例帯を除いた行の実描画下端まで＝凡例 (および下端 chrome) のダブルクリックが行に解決して
        // rename が誤って開く問題を防ぐ。 行は [listTop+12, listBottom-LEGEND_RESERVE_H] にしか描かれないので実行は不変。
        return mx >= sbContentX && mx <= sbContentX + sidebarW
                && my >= listTop && my <= listBottom - LEGEND_RESERVE_H;
    }

    /** ㊿ View 帯内か (viewScroll で帯外へ出たトラックのファントムクリック＝ヘッダ/フッタ域での誤操作を防ぐ)。 */
    private boolean inViewBand(double my) {
        return my >= listTop && my <= listBottom;
    }

    private boolean inSlider(double mx, double my) {
        return inViewBand(my) && mx >= slX && mx <= slX + slW && my >= slY - 3 && my <= slY + slH + 3;
    }

    private boolean inSlider2(double mx, double my) {
        return inViewBand(my) && mx >= slX && mx <= slX + slW && my >= sl2Y - 3 && my <= sl2Y + slH + 3;
    }

    private boolean inSlider3(double mx, double my) {
        return inViewBand(my) && mx >= slX && mx <= slX + slW && my >= sl3Y - 3 && my <= sl3Y + slH + 3;
    }

    private boolean inScaleOw(double mx, double my) {
        return inViewBand(my) && mx >= slScaleOwX && mx <= slScaleOwX + slScaleHalfW
                && my >= slScaleY - 3 && my <= slScaleY + slH + 3;
    }

    private boolean inScaleNether(double mx, double my) {
        return inViewBand(my) && mx >= slScaleNX && mx <= slScaleNX + slScaleHalfW
                && my >= slScaleY - 3 && my <= slScaleY + slH + 3;
    }

    private void setSpacingFromMouse(double mx) {
        float frac = (float) ((mx - slX) / Math.max(1, slW - 6));
        frac = Math.max(0f, Math.min(1f, frac));
        int v = PointCloudViewState.SPACING_MIN
                + Math.round(frac * (PointCloudViewState.SPACING_MAX - PointCloudViewState.SPACING_MIN));
        PointCloudViewState.setDimensionSpacing(v);
    }

    /** ⑭/㉒B GPU detail スライダ: マウス x → 1 層の最大描画点数。 実効上限は現 stock (死に区間なし)。 */
    private void setDetailFromMouse(double mx) {
        float frac = (float) ((mx - slX) / Math.max(1, slW - 6));
        frac = Math.max(0f, Math.min(1f, frac));
        int stockMax = gpuStockMax();
        int v = PointCloudViewState.DETAIL_MIN
                + Math.round(frac * (stockMax - PointCloudViewState.DETAIL_MIN));
        PointCloudViewState.setGpuDetail(v);
    }

    /** ㉒B スライダ実効上限＝現スナップショットの大きい方の層 stock (DETAIL_MIN+1..DETAIL_MAX にクランプ)。 */
    private int gpuStockMax() {
        PointCloudSnapshot s = PointCloudAnalysis.get().snapshot();
        int stock = Math.max(s.owX.length, s.nX.length);
        return Math.max(PointCloudViewState.DETAIL_MIN + 1,
                Math.min(PointCloudViewState.DETAIL_MAX, stock));
    }

    /** ⑯ 点サイズスライダ: マウス x → GL 点サイズ (px)。 lineWidth は頂点に焼くので変化で VBO 再構築。 */
    private void setPointSizeFromMouse(double mx) {
        float frac = (float) ((mx - slX) / Math.max(1, slW - 6));
        frac = Math.max(0f, Math.min(1f, frac));
        int v = PointCloudViewState.POINT_SIZE_MIN
                + Math.round(frac * (PointCloudViewState.POINT_SIZE_MAX - PointCloudViewState.POINT_SIZE_MIN));
        PointCloudViewState.setPointSize(v);
    }

    /** ㉓ OW 表示スケールスライダ (対数・ハーフトラック): マウス x → スケール。 */
    private void setOwScaleFromMouse(double mx) {
        float frac = (float) ((mx - slScaleOwX) / Math.max(1, slScaleHalfW - 6));
        PointCloudViewState.setOwDisplayScale(
                fracToScale(frac, PointCloudViewState.OW_SCALE_MIN, PointCloudViewState.OW_SCALE_MAX));
    }

    /** ㉓ ネザー表示スケールスライダ (対数・ハーフトラック): マウス x → スケール。 ㉘ 範囲 ×1/16〜×16。 */
    private void setNetherScaleFromMouse(double mx) {
        float frac = (float) ((mx - slScaleNX) / Math.max(1, slScaleHalfW - 6));
        PointCloudViewState.setNetherDisplayScale(
                fracToScale(frac, PointCloudViewState.NETHER_SCALE_MIN, PointCloudViewState.NETHER_SCALE_MAX));
    }

    @Override
    public boolean isPauseScreen() {
        return true; // ㉟ メニュー表示中は SP のゲーム進行を一時停止 (MP は統合サーバ非搭載＝進行は止まらない)。
    }

    @Override
    public void onClose() {
        GateConfigManager.save();
        //? if >=26.2 {
        /*this.minecraft.setScreenAndShow(this.parent);*/
        //?} else {
        this.minecraft.setScreen(this.parent);
        //?}
    }
}
