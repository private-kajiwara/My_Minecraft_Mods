package com.kajiwara.omnichest.client.render;

import com.kajiwara.omnichest.client.compat.SafeRenderDispatcher;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.debug.DebugLog;
import com.kajiwara.omnichest.mixin.RenderTypeAccessor;
import com.kajiwara.omnichest.search.ChestNetworkManager;
import com.kajiwara.omnichest.search.ContainerScanner;
import com.kajiwara.omnichest.search.ContainerSnapshot;
import com.kajiwara.omnichest.search.ContainerType;
import com.kajiwara.omnichest.search.EntityLocator;
import com.mojang.blaze3d.pipeline.BlendFunction;
//? if >=26.1 {
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
//?} else {
/*import com.mojang.blaze3d.platform.DepthTestFunction;*/
//?}
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.renderer.RenderType;*/
//?}
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 検索結果クリック時に「対象コンテナを世界中で目立たせる」レンダラ。
 *
 * <p>
 * 二段構成のハイライト (すべてワールド座標系):
 * <ol>
 * <li><b>X-ray ボックス</b>: チェスト本体を囲む黄色いワイヤーフレーム。
 * カスタム RenderPipeline で {@code NO_DEPTH_TEST} を指定しているため、
 * ブロック越しでも常に視認できる。</li>
 * <li><b>名前タグピン</b>: チェストの上空にビルボード描画される
 * 「アイテム名 × 数量 (距離)」テキスト。
 * バニラの {@link SubmitNodeCollector#submitNameTag} 経路を使うため
 * エンティティのネームタグと同じ自然な描画になり、画面に張り付かない。</li>
 * </ol>
 *
 * <p>
 * 描画タイミングは {@link WorldRenderEvents#BEFORE_ENTITIES}。
 * SubmitNodeCollector はエンティティ pass で集計・描画されるため、
 * その手前 = entities 開始前に submit を完了させておく必要がある。
 */
public final class ChestHighlighter {

    private static final ChestHighlighter INSTANCE = new ChestHighlighter();

    public static final long DEFAULT_HIGHLIGHT_DURATION_MS = 15_000L;
    private static final long FADE_TAIL_MS = 1500L;

    /**
     * 開いているコンテナで対象アイテムを表示している間、最低限保証するハイライト残存時間 (ms)。
     * GUI で {@link #isHighlightedItem(ItemStack)} がヒットする度に
     * {@code expiresAt} をこの時間ぶん再延長するため、 GUI を閉じた後も約 10 秒間は
     * スロット overlay と (ワールド側の) ピン/ボックスが残る。
     */
    private static final long SLOT_VIEW_REFRESH_MS = 10_000L;

    /**
     * チェスト破壊検出後、 ハイライトを残す上限時間 (ms)。
     * <p>
     * 「壊した瞬間からドロップアイテムを拾い終わるまで」 の猶予として 30 秒を確保。
     * 永続ピン設定 ({@code pinPersistUntilOpened}) で expiresAt = Long.MAX_VALUE になっていても、
     * 壊した瞬間にこの値に clamp されるため、 「壊れたチェストのハイライトが永遠に残る」 バグを防ぐ。
     */
    private static final long CHEST_BROKEN_GRACE_MS = 30_000L;

    /**
     * ピン・ボックス共通のテーマカラー (RGB) のフォールバック値。
     *
     * <p>
     * 実際の色は {@link #themeRgb()} 経由で
     * {@code ConfigManager.get().render.highlightColorRgb} を読む。
     * Config が読めない (= 起動直後 / IO 失敗 etc.) 場合のみこの値が使われる。
     */
    private static final int THEME_RGB_FALLBACK = 0xFFCC00;

    /** 線の太さ (lines shader が読む)。 */
    private static final float LINE_WIDTH = 3.5f;

    /** ボックスを実ブロックよりわずかに膨らませて z-fighting を回避する量。 */
    private static final float BOX_INFLATE = 0.0025f;

    /**
     * ピンの最下段がチェスト天面より上に何ブロック浮くか。
     *
     * <p>
     * 以前は 1.25m (= チェスト 1 個ぶんを丸ごと開けていた) で、 「ピンがブロックから浮きすぎ」
     * というレビュー指摘を受けて 0.45m に短縮。 0.45m はバニラのプレイヤーネームタグの体感に
     * 近い 「ブロックのすぐ上で読める」 距離で、 単チェスト直近 (= 距離 1m 程度) でも
     * ブロック天面と文字が重ならない (= 文字下端 = base + 1px, スケールは {@link #PIN_TEXT_SCALE}
     * × 距離係数 で十分小さい)。
     *
     * <p>
     * <b>注意</b>: 値変更時に {@link #drawPinImmediate} と {@link #pinTopWorldY} の双方で
     * 同じ baseY 計算 ({@code primary.getY() + 1.0 + PIN_BASE_HEIGHT}) を共有するため、
     * ここを変えれば両方追従する (= 食い違いが起きない設計)。
     */
    private static final double PIN_BASE_HEIGHT = 0.45;

    /** ピンテキストのワールドスケール (= 1 font-pixel あたりのワールド単位)。 */
    private static final float PIN_TEXT_SCALE = 0.025f;

    /**
     * テキストのみ追加で適用する縮小係数。 1.0 = 既存サイズ、 0.85〜0.9 で「少し小さい」。
     *
     * <p>
     * <b>適用範囲</b>: テキスト (ヘッダ「▼ 距離」 + 各エントリ行の「アイテム名 ×個数」) のみ。
     * アイコン / 行背景 / 行レイアウト / ピン位置 / アンカ位置には影響を与えない
     * (= 「Text scale のみ微調整」 要件に従う)。
     *
     * <p>
     * <b>実装</b>: テキスト submit の直前に {@link PoseStack#scale(float, float, float)} を 1 軸ぶん
     * 適用し、 直後に pop で破棄する。 これにより以後の描画に副作用を残さない。
     */
    private static final float PIN_TEXT_LOCAL_SCALE = 0.88f;

    /**
     * 画面サイズ一定化のための「基準距離」(m)。
     * <ul>
     * <li>カメラからチェストまでの距離が {@code <= 基準距離} の間は素のスケール
     *     ({@link #PIN_TEXT_SCALE}) のまま (= 近づくとそれなりに大きく見える)。</li>
     * <li>基準距離を超えたら距離に比例してワールドスケールを引き伸ばす (= 角サイズが一定 = 画面上のサイズが一定)。</li>
     * </ul>
     * 「遠くても読める」を成立させつつ、近距離で巨大化しないバランス点として 6m を選択。
     */
    private static final double PIN_SCALE_REF_DISTANCE = 6.0;

    /** ピン背景色 (ARGB)。バニラのネームタグ (alpha ~0x40) より大幅に濃くする (alpha 0xE0)。 */
    private static final int PIN_BG_ARGB = 0xE0000000;

    /** 現在有効なハイライト。 1 entry = 1 チェスト。 */
    private final Map<ContainerSnapshot.Key, ActiveHighlight> active = new ConcurrentHashMap<>();

    /**
     * 1 フレーム分の「ピンアイコン HUD 描画キュー」。
     *
     * <p>
     * <b>なぜ HUD 描画にするか</b>:
     * バニラの世界パスでのアイテム描画 ({@link ItemStackRenderState#submit}) はレイヤごとに
     * DEPTH_TEST 有効なパイプラインを使うため、 チェストの手前にあるブロック (ガラス含む) で
     * <b>必ず</b> 隠れる (= NO_DEPTH_TEST にカスタムオーバーライドする手段がレイヤ依存で実用的でない)。
     * 一方で「テキスト」 「黒帯背景」 「ワイヤーフレーム」 はもともと NO_DEPTH_TEST 系の
     * RenderType を使っているのでブロック越しに見える。
     *
     * <p>
     * 「ピンを絶対に貫通表示」 する根本対処として、 ピンの<b>アイコンだけ</b>を世界パスから
     * 切り離し、 HUD パス (= 2D 描画、 world depth とは独立) で描く。 これにより:
     * <ul>
     *   <li>不透明 / 半透明問わずどんなブロック越しでもアイコンが消えない。</li>
     *   <li>Iris / Sodium 等の shader 環境でも HUD は影響を受けない (= shader-safe)。</li>
     *   <li>ピンの座標 / レイアウト / アニメーション / アンカ / ビーコン / ワイヤー枠 など
     *       他のロジックには一切踏み込まない (= 「関係ないロジックは変更しない」 要件遵守)。</li>
     * </ul>
     */
    private final List<PendingHudIcon> pendingHudIcons = new ArrayList<>();

    /**
     * HUD パスで描画するピンアイコン 1 件の DTO。
     * 世界パスで「画面座標」 まで投影しておき、 HUD パスでは {@link GuiGraphicsExtractor#renderItem} に
     * 渡すだけ (= HUD pass で world transform を持ち越さない)。
     *
     * <p>
     * <b>座標精度</b>: 移動 / ターン中の 1-px ジッタを抑えるため、 screen X/Y は浮動小数で保持する。
     * 整数化は HUD pass 内の {@code pose.translate} に float をそのまま渡して
     * GuiGraphicsExtractor に sub-pixel オフセットを任せる (= 整数 snap によるカクツキ排除)。
     */
    private record PendingHudIcon(ItemStack stack, float screenX, float screenY, float sizePx) {
    }

    /**
     * 1 フレーム分の「水の描画後に immediate-mode で描くビーム」 キュー。
     *
     * <p>
     * {@link #onWorldRender} (= 水より前のステージ) でビーム諸元 (ワールド座標 + 色 + alpha) を積み、
     * {@link #onAfterWaterRender} (= 水より後のステージ) でまとめて描く。 これにより半透明地形 (水) が
     * ビームを上書きする不具合を解消する。 ビームの色 / 位置 / 太さ / 遮蔽 (depth=LEQUAL) は不変。
     */
    private final List<PendingBeam> pendingBeams = new ArrayList<>();

    /** ビーム 1 本ぶんの諸元 (ワールド座標系)。 camera-relative 変換は描画時に行う。 */
    private record PendingBeam(double cxWorld, double czWorld, double baseWorldY, int themeRgb, float alpha) {
    }

    /**
     * 1 フレーム分の「水の描画後に immediate-mode で描くピン (テキスト + 黒帯)」 キュー。
     *
     * <p>
     * <b>なぜ水後 immediate-mode か</b>: ピンのテキスト / 黒帯は元々 submit 収集経由で描かれており、
     * バニラが半透明地形 (水) を全 features パスより後に描くため水に上書きされていた。 そこで
     * {@link #onWorldRender} (= 水より前) では諸元を積むだけにし、 {@link #onAfterWaterRender}
     * (= 水より後) で <b>ビルボード変換・レイアウト・スケールを一切変えずに</b> immediate-mode で
     * 描き直す (= 見た目は完全不変、 描画タイミングだけ水の後にずらす)。 SEE_THROUGH のままなので
     * 固体地形へは従来どおり貫通表示 (= X-ray) を維持する。 アイコンは従来どおり HUD パス。
     */
    private final List<PendingPin> pendingPins = new ArrayList<>();

    /** ピン 1 個ぶんの諸元 (= 中心ワールド XZ + ピン下端 Y + 表示エントリ + テーマ色)。 */
    private record PendingPin(double cx, double cz, double baseY, List<HighlightEntry> entries, int themeRgb) {
    }

    /**
     * 水後ピン / ビームを描く自前の immediate {@link MultiBufferSource} (= エンジン本体の bufferSource を
     * 汚さないため独立バッファを 1 本持つ)。 描画後に {@code endBatch()} で自分の頂点だけを flush する。
     * 初回利用時に lazy 構築し、 以後フレーム間で再利用する (= 確保コストを 1 回に抑える)。
     */
    //? if <26.2 {
    private MultiBufferSource.BufferSource afterWaterBuffer;
    //?}

    /** X-ray 用 lines RenderType (初回参照時に lazy 構築)。 */
    private static volatile RenderType xrayLinesType;

    private ChestHighlighter() {
    }

    public static ChestHighlighter get() {
        return INSTANCE;
    }

    public static void register() {
        // 互換層 ({@link SafeRenderDispatcher}) を挟み、 他 MOD の shader/state 不整合が原因で
        // {@link #onWorldRender} が例外を投げてもゲーム本体をクラッシュさせないようにする。
        // 正常系では try/catch 1 段ぶんしか overhead を足さないので既存の描画挙動は変わらない。
        // 26.1: WorldRenderEvents.BEFORE_ENTITIES は廃止。 LevelRenderEvents の
        // AFTER_SOLID_FEATURES (不透明地形/エンティティ描画後) で同等のタイミングを得る。
        //? if >=26.1 {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(ctx ->
                SafeRenderDispatcher.safeRun("chest-highlight-world", () -> INSTANCE.onWorldRender(ctx)));
        //?} else {
        /*WorldRenderEvents.BEFORE_ENTITIES.register(ctx ->
                SafeRenderDispatcher.safeRun("chest-highlight-world", () -> INSTANCE.onWorldRender(ctx)));*/
        //?}

        // ─── ビームを「半透明地形 (水) より後」 に immediate-mode で描く ───
        // submit 収集の features パス (solid / translucent) は水 (translucent terrain) より前に
        // 描画されるため、 submit したビームは必ず水に上書きされる。 そこで onWorldRender では
        // ビーム諸元を {@link #pendingBeams} に積むだけにし、 水の描画後に発火するステージで
        // bufferSource (immediate-mode) を使って描く。 ビーム自身の depth test (LEQUAL) は維持する
        // ため、 固体地形への遮蔽挙動 (= バニラビーコン同様) は不変。
        // 26.1: AFTER_TRANSLUCENT_TERRAIN (= 半透明地形描画直後)。 legacy: END_MAIN (= 毎フレーム
        // 確実に水描画後に発火する終端ステージ。 BEFORE_BLOCK_OUTLINE はブロックを見ていないと
        // 発火しないため使わない)。
        //? if >=26.1 {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx ->
                SafeRenderDispatcher.safeRun("chest-highlight-beam", () -> INSTANCE.onAfterWaterRender(ctx)));
        //?} else {
        /*WorldRenderEvents.END_MAIN.register(ctx ->
                SafeRenderDispatcher.safeRun("chest-highlight-beam", () -> INSTANCE.onAfterWaterRender(ctx)));*/
        //?}

        // ─── ピンアイコンを HUD パスで貫通描画 ───
        // 世界パスで投影した画面座標 (= pendingHudIcons) を、 ここで 2D アイテム描画する。
        // HUD は world depth と無関係なので、 ブロック越しでも必ず最前面に出る。
        // 26.1: HudRenderCallback は廃止。 HudElementRegistry に HudElement を addLast する。
        //? if >=26.1 {
        HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("omnichest", "chest_highlight_icons"),
                (g, deltaTracker) -> SafeRenderDispatcher.safeRun("chest-highlight-hud-icons",
                        () -> INSTANCE.onHudRender(g)));
        //?} else {
        /*HudRenderCallback.EVENT.register((g, deltaTracker) ->
                SafeRenderDispatcher.safeRun("chest-highlight-hud-icons",
                        () -> INSTANCE.onHudRender(g)));*/
        //?}

        // ────────────────────────────────────────────────────────────
        // 「ピン永続表示 (= チェストを開くまで残す)」設定用のフック。
        //
        // {@link ContainerScanner#register()} が先に AFTER_INIT に登録しているため、
        // ここで登録するリスナはその後に走り、 {@link ContainerScanner#currentActiveKey()}
        // が「いま開いたチェスト」を返す状態になっている。
        //
        // 設定が OFF の場合は何もしない (= 既存の時間ベース消失 + GUI 中の自動延長を維持)。
        // 設定が ON の場合は:
        //   - active からは <b>消さない</b> (= スロット overlay 用に検索可能なまま残す)
        //   - {@code worldRenderSuppressed} を立ててワールド側のピン/ボックスのみ即座に消す
        //   - 永続 (Long.MAX_VALUE) なら expiresAt を有限値に下げ、 GUI を閉じた後
        //     SLOT_VIEW_REFRESH_MS 経過で自然に expire させる
        // ────────────────────────────────────────────────────────────
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            ContainerSnapshot.Key opened = ContainerScanner.currentActiveKey();
            if (opened == null) {
                return;
            }
            // エンダーチェストを開いたら、 全エンダーチェストの「ワールド側ガイド (ピン/枠/ビーム)」を
            // 抑止する。 全エンダーチェストは同一インベントリを共有するため、 どれか 1 つに到達した
            // 時点でワールド探索ガイドは不要になる (= 仕様)。 永続ピン設定に関わらず即抑止する。
            //
            // 旧実装は active から ENDER_CHEST entry を「削除」していたが、 これだと
            // 「エンダーチェスト → シュルカー → アイテム」の段階ハイライトに必要な
            // スロット overlay 用 entry (waypoint = シュルカー / leaf = 中身) まで巻き添えで消え、
            // 「開けた瞬間にハイライトが消える」不具合になっていた (= 今回の修正対象)。
            // そこで「削除」ではなく {@link #suppressAllEnderChestWorldGuides()} で
            // worldRenderSuppressed を立てるだけにし、 entry は保持してスロット overlay を維持する。
            ContainerSnapshot openedSnap = ChestNetworkManager.get().get(opened);
            if (openedSnap != null && openedSnap.type() == ContainerType.ENDER_CHEST) {
                INSTANCE.suppressAllEnderChestWorldGuides();
                return;
            }
            if (!ConfigManager.get().search.pinPersistUntilOpened) {
                return;
            }
            ActiveHighlight ah = INSTANCE.active.get(opened);
            if (ah == null) {
                return;
            }
            ah.worldRenderSuppressed = true;
            long fin = System.currentTimeMillis() + SLOT_VIEW_REFRESH_MS;
            if (ah.expiresAt > fin) {
                ah.expiresAt = fin;
            }
        });

        // ────────────────────────────────────────────────────────────
        // 切断時: ハイライト状態をワールド境界で解放する。
        //
        // {@link #active} は静的シングルトン {@link #INSTANCE} が保持するため
        // プロセス寿命まで生き残る。 通常エントリは onWorldRender 内の時間ベース
        // sweep (expiresAt) で自己消滅するが、 「ピン永続表示 (pinPersistUntilOpened)」
        // のエントリは expiresAt = Long.MAX_VALUE なので sweep で消えず、 かつ sweep 自体が
        // mc.level == null で早期 return するため、 切断時に消えた旧ワールドの
        // BlockPos / ItemStack 参照を保持し続けてしまう。
        //
        // ここで明示的に clear することで、 ChestNetworkManager / DistributionStorage /
        // SlotLockStorage と同じく「ワールド境界で解放」 のパターンに揃える。
        // ハイライトは生成元ワールドの座標に紐づくため、 別ワールドへ再参加した時点で
        // 既に無効: ここでの解放はゲーム内挙動 / UI / 検索 / ピン挙動を一切変えない。
        // ────────────────────────────────────────────────────────────
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            INSTANCE.active.clear();
            INSTANCE.pendingHudIcons.clear();
        });
    }

    /**
     * 指定 {@link ContainerSnapshot.Key} のハイライトを即座に取り除く (= 存在しなければ no-op)。
     * 主に「永続ピン設定 ON 時にチェストを開けた瞬間にピンを消す」ためのフック。
     */
    public void clearForKey(ContainerSnapshot.Key key) {
        if (key == null) return;
        active.remove(key);
    }

    /**
     * 既知の<b>全エンダーチェスト</b> (= 現在ディメンション) を対象アイテムでハイライトする。
     *
     * <p>
     * 全エンダーチェストは同一インベントリを共有するため、 検索ヒットがエンダーチェスト内に
     * あるときは 「どのエンダーチェストからでも取り出せる」 ことを示すべく全箇所をピンする。
     * 既存の {@link #highlight(ContainerSnapshot, ItemStack, int)} をそのまま再利用するので、
     * タイムアウト/フェード/開封時クリア等の挙動は通常ピンと完全に共通になる。
     */
    public void highlightAllEnderChests(ItemStack labelItem, int labelCount) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ResourceKey<Level> dim = mc.level.dimension();
        for (ContainerSnapshot snap : ChestNetworkManager.get().snapshots()) {
            if (snap.type() == ContainerType.ENDER_CHEST && dim.equals(snap.dimension())) {
                highlight(snap, labelItem, labelCount);
            }
        }
    }

    /**
     * 全エンダーチェストの<b>ワールド側ガイド (ピン / 枠 / ビーム)</b> のみを抑止する
     * (= どれか 1 つを開いた = ワールド探索ガイドが不要になったとき)。
     *
     * <p>
     * <b>なぜ「削除」ではなく「抑止 + 保持」か</b>:
     * 「エンダーチェスト → シュルカー → アイテム」の段階ハイライトでは、 leaf (中身) と
     * waypoint (経由シュルカー) の両 entry が同一の {@link ContainerSnapshot.Key}
     * (= ENDER_CHEST の snapshot) 配下に登録される ({@link NestedHighlightRenderer} 参照)。
     * 旧実装のように {@code active} から ENDER_CHEST entry を削除すると、 開いた中で本当に必要な
     * スロット overlay 用 entry まで巻き添えで消え、 「開けた瞬間にハイライトが消える」不具合になる。
     * そこで entry は残し、 {@code worldRenderSuppressed} を立ててワールド描画だけを止める。
     *
     * <p>
     * <b>expiresAt の clamp</b>:
     * 永続ピン設定 ({@code pinPersistUntilOpened}) では expiresAt = {@link Long#MAX_VALUE} に
     * なっているため、 そのまま保持すると「見えないまま永遠に残る entry」 になる。
     * {@code now + SLOT_VIEW_REFRESH_MS} を超える場合のみ有限値へ下げる (= 永続ピンの無効化)。
     * 下げた後は {@link #isHighlightedItem(ItemStack)} が ENDER_CHEST entry を
     * (worldRenderSuppressed 下でも) 毎フレーム再延長するため、 開いている間は維持される。
     * 有限値の entry には触らない (= 通常の時間ベース消失を尊重)。
     *
     * <p>
     * <b>前提依存</b>: {@code expiresAt} は entry 単位ではなく {@link ActiveHighlight} 単位で
     * 共有されている。 これにより waypoint (シュルカー) が可視で延長されている間、 同じ
     * ActiveHighlight 内の leaf (中身) も同時に延命される (= 段階ハイライト後半が早期 expire しない)。
     * <b>将来 expiresAt を entry 単位へ分割すると、 この「まとめ延命」が壊れる</b>ので注意。
     */
    private void suppressAllEnderChestWorldGuides() {
        long fin = System.currentTimeMillis() + SLOT_VIEW_REFRESH_MS;
        for (ActiveHighlight h : active.values()) {
            if (h.snapshot.type() != ContainerType.ENDER_CHEST) {
                continue;
            }
            // ワールド側のピン/枠/ビームを止める (= 開けたらワールドガイドは消える現行仕様)。
            h.worldRenderSuppressed = true;
            // 永続 (MAX_VALUE) のみ有限化。 有限 entry はそのまま (= 既存の時間ベース消失を維持)。
            if (h.expiresAt > fin) {
                h.expiresAt = fin;
            }
        }
    }

    /**
     * 「指定コンテナ × 指定アイテム」 1 件 を狙い撃ちでハイライトから外す (= ピン上の 1 行を消す)。
     *
     * <p>
     * <b>用途</b>: SearchScreen の ALT+D ショートカット (= 「カーソル下の行を選択解除 +
     * ピンからも削除」) から呼ばれる。 ユーザが「この 1 アイテムだけピンを取り消したい」 と
     * 思った時の最小単位の取消手段。
     *
     * <p>
     * <b>動作</b>:
     * <ol>
     *   <li>指定 {@code key} の {@link ActiveHighlight} を探す。 無ければ no-op。</li>
     *   <li>その entries から {@code stack} と <b>同一アイテム + 同一 Components</b> の
     *       {@link HighlightEntry} を <em>全て</em> 削除 (= 同じスタックを複数行 highlight している
     *       ケースを 1 ALT+D でクリーン)。</li>
     *   <li>削除後 entries が空 になったら、 {@link ActiveHighlight} ごと {@code active} から
     *       削除 (= 空のピンが宙に浮かないようにする)。</li>
     * </ol>
     *
     * <p>
     * <b>引数 {@code stack} の特性</b>: 「アイテム ID + Data Components」 で同一性判定する
     * ({@code ItemStack.isSameItemSameComponents})。 count は無視 (= ピンは「あるか / ないか」 の
     * 2 値で、 count フィルタは意図しない)。
     */
    public void removeItemForSnapshot(ContainerSnapshot.Key key, ItemStack stack) {
        if (key == null || stack == null || stack.isEmpty()) return;
        ActiveHighlight ah = active.get(key);
        if (ah == null) return;
        boolean removed = ah.entries.removeIf(e ->
                ItemStack.isSameItemSameComponents(e.stack, stack));
        if (!removed) return;
        if (ah.entries.isEmpty()) {
            active.remove(key);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 登録 API (SearchScreen から呼ばれる)
    // ════════════════════════════════════════════════════════════════════

    /**
     * チェストにハイライトを登録する。同じ {@link ContainerSnapshot.Key} に
     * 複数アイテムを登録すると entries に追記される。
     *
     * <p>
     * {@code durationMs} に {@link Long#MAX_VALUE} を渡すと「永続表示」になる。
     * (= 加算オーバーフローを避けるため expiresAt 計算をバイパスする)。
     */
    public void highlight(ContainerSnapshot snapshot, ItemStack labelItem, int labelCount, long durationMs) {
        addEntry(snapshot, labelItem, labelCount, durationMs, false);
    }

    /**
     * 「経由コンテナ (= シュルカーボックス)」 をハイライト対象に追加する。
     *
     * <p>
     * <b>用途</b> (= 階層型ストレージ検索のハイライト):
     * <pre>Chest A └ Blue Shulker └ Diamond</pre>
     * の Diamond を検索したとき、
     * <ol>
     *   <li>Chest A を {@link #highlight} で登録 (= ワールド枠 + ピンに Diamond を表示)。</li>
     *   <li>Blue Shulker をこのメソッドで <b>waypoint</b> として登録 → Chest A を開くと
     *       Blue Shulker のスロットが既存のスロット overlay で光る。</li>
     *   <li>Diamond 自体も {@link #highlight} の対象なので、 Blue Shulker を開くと
     *       Diamond のスロットが光る。</li>
     * </ol>
     * これにより「チェスト → シュルカー → アイテム」 の段階的ハイライトが、 既存のスロット overlay
     * 機構の再利用だけで成立する。
     *
     * <p>
     * <b>waypoint の特性</b>: スロット overlay の一致判定 ({@link #isHighlightedItem}) には効くが、
     * ワールドのピン (= 名前タグ スタック) には<b>表示しない</b> (= ピンは leaf アイテムのみで簡潔に保つ。
     * 経路はGUIの検索結果リスト側でパンくず表示する)。
     */
    public void highlightWaypoint(ContainerSnapshot snapshot, ItemStack containerStack, long durationMs) {
        addEntry(snapshot, containerStack, containerStack == null ? 0 : containerStack.getCount(),
                durationMs, true);
    }

    /**
     * ハイライトエントリ追加の共通実装。
     *
     * @param waypoint true なら経由コンテナ (= ピン非表示・スロット overlay のみ)。
     */
    private void addEntry(ContainerSnapshot snapshot, ItemStack labelItem, int labelCount,
                          long durationMs, boolean waypoint) {
        if (snapshot == null)
            return;
        long expiresAt = (durationMs == Long.MAX_VALUE)
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + Math.max(0L, durationMs);
        boolean hasLabel = labelItem != null && !labelItem.isEmpty();
        ItemStack labelCopy = hasLabel ? labelItem.copy() : ItemStack.EMPTY;

        // デバッグモード時のみ: どのチェストに何のピンを立てたかを記録する。
        DebugLog.log("Highlight registered: {} x{} at {} (durationMs={}, waypoint={})",
                hasLabel ? labelItem.getHoverName().getString() : "(no label)",
                labelCount, snapshot.pos(),
                durationMs == Long.MAX_VALUE ? "persistent" : durationMs, waypoint);

        active.compute(snapshot.key(), (key, existing) -> {
            ActiveHighlight target = (existing != null)
                    ? existing
                    : new ActiveHighlight(snapshot, new ArrayList<>(), expiresAt);
            target.expiresAt = expiresAt;

            if (hasLabel) {
                boolean dup = false;
                for (HighlightEntry e : target.entries) {
                    if (e.count == labelCount
                            && e.waypoint == waypoint
                            && ItemStack.isSameItemSameComponents(e.stack, labelCopy)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    target.entries.add(new HighlightEntry(labelCopy, labelCount, waypoint));
                }
            }
            return target;
        });
    }

    public void highlight(ContainerSnapshot snapshot, ItemStack labelItem, int labelCount) {
        highlight(snapshot, labelItem, labelCount, resolveDefaultDuration());
    }

    public void highlight(ContainerSnapshot snapshot) {
        highlight(snapshot, ItemStack.EMPTY, 0, resolveDefaultDuration());
    }

    /** 既定継続時間で経由コンテナ (= シュルカー) を waypoint 登録する。 */
    public void highlightWaypoint(ContainerSnapshot snapshot, ItemStack containerStack) {
        highlightWaypoint(snapshot, containerStack, resolveDefaultDuration());
    }

    /**
     * 設定 ({@link com.kajiwara.omnichest.config.data.SearchConfig#pinPersistUntilOpened}) を見て
     * 「永続 = {@link Long#MAX_VALUE}」 または「既定 = {@link #DEFAULT_HIGHLIGHT_DURATION_MS}」を返す。
     */
    private static long resolveDefaultDuration() {
        try {
            if (ConfigManager.get().search.pinPersistUntilOpened) {
                return Long.MAX_VALUE;
            }
        } catch (Throwable ignored) {
            // 起動初期や設定読込失敗時は既定値で fallback (= 起動を妨げない)。
        }
        return DEFAULT_HIGHLIGHT_DURATION_MS;
    }

    public void clear() {
        active.clear();
    }

    /**
     * 任意の {@link ItemStack} が、現在アクティブなハイライト対象アイテムのいずれかに
     * (同一アイテム + 同一 Components) として該当するかを返す。
     *
     * <p>
     * GUI 側 ({@code AbstractContainerScreen#renderSlot}) からスロット毎に呼ばれ、
     * 該当した場合に黄色 overlay を描画する用途。
     *
     * <p>
     * <b>副作用</b>: ヒットしたハイライトの {@code expiresAt} を
     * {@code now + SLOT_VIEW_REFRESH_MS} に延長する (= 既にそれより先ならそのまま)。
     * これにより GUI を開いている間は常に最新フレームで時計がリセットされ続け、
     * GUI を閉じた後も少なくとも 10 秒間はピン/ボックス/スロット overlay が残る。
     *
     * <p>
     * <b>例外</b>: {@link ActiveHighlight#worldRenderSuppressed} が立っているエントリは
     * 延長対象から除外する。 これは「ピン永続表示 ON + チェスト開封済み」の状態であり、
     * 開封時に {@code AFTER_INIT} listener が {@code expiresAt = now + 10s} を設定して
     * 「あと 10 秒で消える」フェーズに入っている。 ここでスロットに該当アイテムが見えている
     * 度に延長してしまうと、 アイテムを移動した後もずっとハイライトが残るバグ
     * (=「一度チェスト開けたら消えるはずなのに残り続ける」) になる。
     */
    public boolean isHighlightedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;
        if (active.isEmpty())
            return false;
        long now = System.currentTimeMillis();
        long refreshTo = now + SLOT_VIEW_REFRESH_MS;
        boolean matched = false;
        for (ActiveHighlight h : active.values()) {
            if (h.expiresAt < now)
                continue;
            boolean hitThis = false;
            for (HighlightEntry e : h.entries) {
                if (ItemStack.isSameItemSameComponents(e.stack, stack)) {
                    hitThis = true;
                    break;
                }
            }
            if (hitThis) {
                matched = true;
                // 「開封済み (worldRenderSuppressed)」 or 「チェスト破壊済み」フェーズでは
                // 原則として時計を延長しない。 そうしないと slot overlay の refresh で expiresAt が
                // 永久に再延長され、 ハイライトが消えなくなる。
                //
                // <b>ENDER_CHEST 例外</b>: エンダーチェストは開封時に
                // {@link #suppressAllEnderChestWorldGuides()} で worldRenderSuppressed を立てるが、
                // これは「ワールド側ガイドを消す」だけが目的で、 開いた中の段階ハイライト
                // (シュルカー → 中身) は維持したい。 そこで ENDER_CHEST に限り、
                // worldRenderSuppressed 下でも延長を許可する (= 通常チェストのネスト導線と同じ寿命)。
                // worldRenderSuppressed はあくまで onWorldRender の<b>描画ゲート</b>であり、 ここで
                // 延長を許可してもワールド側のピン/枠/ビームが復活することはない (= 描画と延命は別経路)。
                // 永続ピンは suppress 時に clamp 済みなので、 この延長は「見えている間だけ now+10s に
                // 保つ」働きにとどまり、 永遠に残ることはない。
                boolean isEnderChest = h.snapshot.type() == ContainerType.ENDER_CHEST;
                if ((!h.worldRenderSuppressed || isEnderChest) && !h.chestBroken
                        && h.expiresAt < refreshTo)
                    h.expiresAt = refreshTo;
            }
        }
        return matched;
    }

    /**
     * 任意の {@link ItemStack} が、 <b>チェスト破壊済み</b> のハイライトに該当するかを返す。
     *
     * <p>
     * チェストが壊れて中身がプレイヤーインベントリへ移った後も、 そのアイテムを
     * 識別できるよう player inventory スロットでも overlay を出すために使う。
     * 通常時 (= チェストがまだ存在) のプレイヤースロットは highlight しない仕様を維持する。
     */
    public boolean isHighlightedItemFromBrokenChest(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (active.isEmpty()) return false;
        long now = System.currentTimeMillis();
        for (ActiveHighlight h : active.values()) {
            if (!h.chestBroken) continue;
            if (h.expiresAt < now) continue;
            for (HighlightEntry e : h.entries) {
                if (ItemStack.isSameItemSameComponents(e.stack, stack)) return true;
            }
        }
        return false;
    }

    /**
     * スロット overlay 等で使うテーマ色 (RGB)。
     *
     * <p>
     * {@code ConfigManager.get().render.highlightColorRgb} を 1 次ソースとして読む。
     * Config 読込前 / 失敗時は {@link #THEME_RGB_FALLBACK} を返す。
     * 毎フレームの描画から呼ばれるが、 {@code ConfigManager.get()} は内部キャッシュ
     * された singleton を返すだけなので軽量。
     */
    public static int themeRgb() {
        try {
            return ConfigManager.get().render.highlightColorRgb & 0x00FFFFFF;
        } catch (Throwable ignored) {
            return THEME_RGB_FALLBACK;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ワールド描画
    // ════════════════════════════════════════════════════════════════════

    private void onWorldRender(LevelRenderContext ctx) {
        // 新フレームの開始: HUD パス / 水後ピン・ビーム用キューを空にする (= 前フレームの取り残しを残さない)。
        pendingHudIcons.clear();
        pendingBeams.clear();
        pendingPins.clear();
        // shader 時に水後ステージへ流す wire pending も毎フレームリセットする (legacy では常に空)。
        WireHighlightRenderer.clearPending();

        if (active.isEmpty())
            return;

        long now = System.currentTimeMillis();
        active.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        if (active.isEmpty())
            return;

        // 「オーバーレイ全体を OFF」 設定 (= RenderConfig.enableOverlay) を尊重する。
        // OFF なら expire 掃除だけ済ませてワールド側の枠 / ピン / ビームを描かない
        // (= GUI 内スロット overlay は isHighlightedItem 経由で別管理なので影響しない)。
        try {
            if (!ConfigManager.get().render.enableOverlay) {
                return;
            }
        } catch (Throwable ignored) {
            // 設定が読めない時は従来どおり描画する (= 安全側 / 既定 ON 相当)。
        }

        CameraRenderState camState = ctx.levelState().cameraRenderState;
        if (camState == null || camState.pos == null)
            return;
        Vec3 camPos = camState.pos;

        // 現在 dimension をフィルタするための ResourceKey。
        // worldState 経由で取りたいが API 公開されていないので Minecraft.level から拾う。
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null)
            return;
        ResourceKey<Level> currentDim = level.dimension();

        PoseStack matrices = ctx.poseStack();
        SubmitNodeCollector queue = ctx.submitNodeCollector();
        //? if >=26.2 {
        /*// 26.2: カスタム X-ray パイプライン (withUniform/VertexFormat.Mode 削除) は構築不可。 ボックスは
        //   バニラ RenderTypes.lines() で submit (深度テスト有り・Iris ネイティブ捕捉)。 ワイヤー決定と同方針。
        RenderType xray = RenderTypes.lines();*/
        //?} else {
        RenderType xray = xrayLines();
        //?}

        // 1 フレームに 1 回だけ Config を引いて使い回す (= ループ内で 重ねて get しない)。
        final int themeRgb = themeRgb();

        for (ActiveHighlight h : active.values()) {
            // 「開封したのでピン/ボックスは消したい」が、 entry 自体はスロット overlay 用に
            // active へ残しているケース (= pinPersistUntilOpened ON 時の挙動)。
            if (h.worldRenderSuppressed)
                continue;
            ContainerSnapshot snap = h.snapshot;
            if (!snap.dimension().equals(currentDim))
                continue;

            long remaining = h.expiresAt - now;
            float alphaF = (remaining < FADE_TAIL_MS)
                    ? Math.max(0.0f, remaining / (float) FADE_TAIL_MS)
                    : 1.0f;
            int color = packColor(themeRgb, alphaF);

            // ─── エンティティコンテナ (= トロッコ / ボート / モブ): 毎フレーム現在位置で追従 ───
            // ブロック専用の still-standing 検出 / ブロック箱 / ブロック中心ピンには掛けず、
            // 解決した実エンティティの AABB / position から箱・ピン・ビームを描く (= 純粋な別経路)。
            if (snap.isEntity()) {
                renderEntityHighlight(level, queue, matrices, camState, camPos,
                        h, snap, now, color, alphaF, themeRgb);
                continue;
            }

            // ─── 「チェストはまだそこにあるか」 の検出 ───
            // 壊された瞬間にワイヤー / ピンを消す。 entries は残し、 ドロップアイテムや
            // プレイヤーインベントリに移ったアイテムをハイライトし続ける (= 引き継ぎ強調)。
            boolean stillStanding = isStillContainerBlock(level, snap.pos())
                    || (snap.secondaryPos() != null
                            && isStillContainerBlock(level, snap.secondaryPos()));
            if (!stillStanding) {
                if (!h.chestBroken) {
                    h.chestBroken = true;
                    // 永続ピン設定で expiresAt = Long.MAX_VALUE になっているケースがあるため、
                    // チェスト破壊後は 30 秒以内の有限値にクランプして「永遠に残るバグ」を回避。
                    long clamp = now + CHEST_BROKEN_GRACE_MS;
                    if (h.expiresAt > clamp) {
                        h.expiresAt = clamp;
                    }
                }
                continue;
            }

            // ─── ボックス (X-ray) — ラージチェストは 1 つの長方形として描く ───
            submitMergedBox(queue, matrices, xray, snap, camPos, color);

            // ─── ピン (ネームタグ): ワールド ビルボード として常時 チェスト 真上 に固定 ───
            // waypoint (= 経由シュルカー) はピンに出さず、 検索対象アイテムだけを表示する。
            // 水対策: ここでは pendingPins に積むだけにし、 実描画は水後 ({@link #onAfterWaterRender}) で行う。
            List<HighlightEntry> pinEntries = h.pinEntries();
            double[] pc = snapBeamCenterXZ(snap);
            double pinBaseY = snap.pos().getY() + 1.0 + PIN_BASE_HEIGHT;
            pendingPins.add(new PendingPin(pc[0], pc[1], pinBaseY, pinEntries, themeRgb));

            // ─── ビーコン風ビーム (ピンの補助演出) ───
            // ピン座標 / anchor / 検索ロジックには一切触れず、 同じ中心位置から上空へ伸びる
            // 半透明ビームを「足すだけ」。
            //
            // 発射基準は「ピン (名前タグ スタック) の一番真上」。 表示行数 (= アイテム行数) と
            // 距離スケールに連動するため、 表示テキスト量が増えるほど発射位置が上がる。
            //
            // <b>水対策</b>: ここでは submit せず {@link #pendingBeams} に積むだけ。 実描画は
            // {@link #onAfterWaterRender} (= 水の描画後) で行い、 水がビームを上書きしないようにする。
            double beamBaseY = pinTopWorldY(snap, pinEntries.size(), camPos);
            double[] bc = snapBeamCenterXZ(snap);
            pendingBeams.add(new PendingBeam(bc[0], bc[1], beamBaseY, themeRgb, alphaF));
        }

        // ─── ドロップアイテム / プレイヤーインベントリ への引き継ぎハイライト ───
        // 「対象アイテムを含む ItemEntity」 をワールド上で個別にワイヤー強調する。
        // プレイヤーインベントリ側は別 Mixin ({@link com.kajiwara.omnichest.mixin.SearchMatchSlotMixin}) が
        // {@link #isHighlightedItem} を経由して既にハイライトしているため、 ここでは触らない。
        renderItemEntityHighlights(level, queue, matrices, camPos, currentDim, themeRgb);

        //? if >=26.2 {
        /*// 26.2: ビームのみここで submit する (engine が debugFilledBox を translucent フェーズ=水後に描く)。
        //   ピンは screen 座標を焼かず world データ (pendingPins) のまま残し、 onHudRender が毎フレーム
        //   live camera/GUI 寸法で投影し直す (= 初回ウィンドウ寸法 stale / ESC ポーズ復帰後の張り付きを回避)。
        flushBeams262(queue, matrices, camPos);*/
        //?}
    }

    /**
     * 水 (半透明地形) の描画<b>後</b> に発火し、 {@link #onWorldRender} で {@link #pendingBeams} に
     * 積まれたビームを immediate-mode で描く。 これにより「水がビームの上に重なる」 不具合を解消する。
     *
     * <p>
     * <b>不変条件</b>: ビームの色 / 位置 / 太さ / depth 挙動 (= LEQUAL で固体地形に遮蔽) は一切変えない。
     * X-ray ボックスは従来どおり {@link #onWorldRender} の submit のまま、 ピンラベルは HUD パスで描く。
     * {@code enableOverlay} OFF 時は pendingBeams が空、 {@code enableBeacon} OFF 時は各ビームが即 return
     * するため、 設定挙動も従来どおり。
     */
    private void onAfterWaterRender(LevelRenderContext ctx) {
        if (pendingPins.isEmpty() && pendingBeams.isEmpty() && !WireHighlightRenderer.hasPending())
            return;
        //? if <26.2 {
        CameraRenderState camState = ctx.levelState().cameraRenderState;
        if (camState == null || camState.pos == null)
            return;
        Vec3 camPos = camState.pos;
        PoseStack matrices = ctx.poseStack();
        // 自前 immediate バッファへ描く (= エンジン本体の bufferSource を汚さない)。 同一フレームの
        // matrices (camera-relative) と camera をそのまま使うため、 onWorldRender 時と同じ座標系で描ける。
        if (afterWaterBuffer == null) {
            afterWaterBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(256));
        }
        MultiBufferSource.BufferSource bufferSource = afterWaterBuffer;

        // (1) ピン (テキスト + 黒帯) — ビルボード変換 / レイアウト / スケールは onWorldRender 時と完全同一。
        for (PendingPin p : pendingPins) {
            drawPinImmediate(bufferSource, matrices, camState, p.cx(), p.cz(), p.baseY(),
                    camPos, p.entries(), p.themeRgb());
        }
        // (2) ビーム。
        for (PendingBeam b : pendingBeams) {
            BeaconEffectLayer.drawWorld(bufferSource, matrices, b.cxWorld(), b.czWorld(),
                    camPos, b.themeRgb(), b.alpha(), b.baseWorldY());
        }
        // 自前バッファの頂点だけを flush (= 水の上へ描画。 他の immediate-mode バッチには干渉しない)。
        bufferSource.endBatch();
        //?}
        // 26.2: ピン (HUD 投影テキスト/アイコン) とビーム (debugFilledBox submit) は onWorldRender 末尾の
        //   flushPinsAndBeams262 で処理済 (afterWaterBuffer immediate は撤去)。 ここでは何もしない。

        // ─── shader 時: pending ワイヤーを level バッファ (ctx.bufferSource()・Iris ラップ) へ流す ───
        // submit / 自前 immediate ではなく level バッファへ描くことで Iris の rendertype_lines に乗せる
        // (= バニラのブロック選択枠と同じ本物ワイヤー)。 フラッシュは level に委ねる (= endBatch しない)。
        // legacy (<26.1) では WireHighlightRenderer が enqueue しないため hasPending() は常に false。
        //? if >=26.1 && <26.2 {
        if (WireHighlightRenderer.hasPending()) {
            WireHighlightRenderer.flushShaderWires(ctx.bufferSource(), matrices);
        }
        //?}
    }

    /** ブロック snap のビーム中心 XZ (= ピン中心と同じ。 ラージチェストは 2 ブロックの中点)。 */
    private static double[] snapBeamCenterXZ(ContainerSnapshot snap) {
        BlockPos primary = snap.pos();
        BlockPos secondary = snap.secondaryPos();
        if (secondary != null && snap.type() != null && snap.type().isDouble()) {
            return new double[] {
                    (primary.getX() + secondary.getX()) * 0.5 + 0.5,
                    (primary.getZ() + secondary.getZ()) * 0.5 + 0.5 };
        }
        return new double[] { primary.getX() + 0.5, primary.getZ() + 0.5 };
    }

    /**
     * コンテナを持つエンティティ (= トロッコ / ボート / モブ) のハイライトを <b>毎フレーム現在位置</b> で
     * 描画する。 ブロック経路 ({@link #submitMergedBox} / {@link #isStillContainerBlock}) には一切触れず、
     * 解決した実エンティティの {@link AABB} と {@code position()} から箱・ピン・ビームを構築する。
     *
     * <p>
     * <b>解決できない場合の方針</b> (= ブロックの未ロード/破壊検出と同方針):
     * <ul>
     *   <li>捕捉位置のチャンクがロード済み かつ 解決不能 → 「消滅した」 とみなし、 ブロック破壊と同じ
     *       grace ({@link #CHEST_BROKEN_GRACE_MS}) でハイライトを失効させる (= ワールド描画は止める)。</li>
     *   <li>未ロードチャンク → 「分からない → 残す」。 捕捉時 pos を 1 ブロック箱として描画継続する。</li>
     * </ul>
     */
    private void renderEntityHighlight(net.minecraft.client.multiplayer.ClientLevel level,
            SubmitNodeCollector queue, PoseStack matrices, CameraRenderState camState, Vec3 camPos,
            ActiveHighlight h, ContainerSnapshot snap, long now, int color, float alphaF, int themeRgb) {
        EntityLocator loc = snap.entity();
        Entity e = (loc != null) ? loc.resolve(level) : null;

        double cx;
        double cz;
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;
        double pinBaseY;

        if (e != null && e.isAlive() && !e.isRemoved()) {
            // 生存エンティティ: 実 AABB / 中心で追従 (= 移動に毎フレーム追随)。
            AABB bb = e.getBoundingBox();
            Vec3 p = e.position();
            cx = p.x;
            cz = p.z;
            minX = bb.minX;
            minY = bb.minY;
            minZ = bb.minZ;
            maxX = bb.maxX;
            maxY = bb.maxY;
            maxZ = bb.maxZ;
            pinBaseY = bb.maxY + PIN_BASE_HEIGHT;
        } else {
            // 解決不能。 ロード済みなら消滅 → grace 失効、 未ロードなら最後の位置で残す。
            boolean chunkLoaded = level != null && level.isLoaded(snap.pos());
            if (chunkLoaded) {
                if (!h.chestBroken) {
                    h.chestBroken = true;
                    long clamp = now + CHEST_BROKEN_GRACE_MS;
                    if (h.expiresAt > clamp) {
                        h.expiresAt = clamp;
                    }
                }
                return;
            }
            BlockPos bp = snap.pos();
            cx = bp.getX() + 0.5;
            cz = bp.getZ() + 0.5;
            minX = bp.getX();
            minY = bp.getY();
            minZ = bp.getZ();
            maxX = bp.getX() + 1.0;
            maxY = bp.getY() + 1.0;
            maxZ = bp.getZ() + 1.0;
            pinBaseY = bp.getY() + 1.0 + PIN_BASE_HEIGHT;
        }

        // ─── ボックス (X-ray): エンティティ AABB をそのまま囲う ───
        submitEntityBox(queue, matrices, camPos, minX, minY, minZ, maxX, maxY, maxZ, color);

        // ─── ピン (ネームタグ): エンティティ中心の真上に追従。 水対策で pendingPins に積み、 水後に描く ───
        List<HighlightEntry> pinEntries = h.pinEntries();
        pendingPins.add(new PendingPin(cx, cz, pinBaseY, pinEntries, themeRgb));

        // ─── ビーコン風ビーム: ピン上端から上空へ。 水対策で pendingBeams に積み、 水後に描く ───
        double beamBaseY = pinTopWorldYCore(cx, cz, pinBaseY, pinEntries.size(), camPos);
        pendingBeams.add(new PendingBeam(cx, cz, beamBaseY, themeRgb, alphaF));
    }

    /**
     * 任意の AABB を camera-relative の wireframe box として submit する (= エンティティ追従用)。
     * ブロック用 {@link #submitMergedBox} と描画先 ({@link WireHighlightRenderer#submitWireBox}) を
     * 共有するため、 shader-safe 経路も同じく効く。
     */
    private static void submitEntityBox(SubmitNodeCollector queue, PoseStack matrices, Vec3 camPos,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int color) {
        float fx0 = (float) (minX - camPos.x) - BOX_INFLATE;
        float fy0 = (float) (minY - camPos.y) - BOX_INFLATE;
        float fz0 = (float) (minZ - camPos.z) - BOX_INFLATE;
        float fx1 = (float) (maxX - camPos.x) + BOX_INFLATE;
        float fy1 = (float) (maxY - camPos.y) + BOX_INFLATE;
        float fz1 = (float) (maxZ - camPos.z) + BOX_INFLATE;
        WireHighlightRenderer.submitWireBox(queue, matrices,
                fx0, fy0, fz0, fx1, fy1, fz1, color, LINE_WIDTH);
    }

    /**
     * {@link BlockPos} の場所がまだコンテナブロックかを確認 (= 壊された検出)。
     * 失敗時 (= level == null) は true 扱い (= 余計に消さない安全側)。
     *
     * <p>
     * <b>未ロードチャンク対応 (= 「ロード範囲外でも遠距離ピンを残す」 要件)</b>:
     * {@code level.getBlockState(pos)} は未ロード位置に対して <em>例外を投げず</em> AIR
     * (= 空気ブロック) を返す MC の仕様がある。 そのまま判定すると
     * {@code ContainerType.fromBlockState(AIR) == null} → false → 「壊された」 と誤判定し、
     * <b>未ロードチャンクのチェストはすべて pin が消える</b> バグになっていた
     * (= 「遠距離ストレージ探索」 が事実上不能)。
     *
     * <p>
     * <b>対処</b>: {@code level.isLoaded(pos)} を先に確認し、 未ロードなら <b>true</b> を返して
     * 「不明 → 安全側で残す」 と解釈する。 これにより:
     * <ul>
     *   <li>未ロードチャンクのピンはスナップショット位置 (= 既知の世界座標) で描画継続。</li>
     *   <li>チャンクを <b>強制ロードしない</b> (= isLoaded は読み取り専用クエリ、 副作用なし)。</li>
     *   <li>チャンクが再ロードされた瞬間に通常のフローへ復帰 (= ブロックが消えていれば
     *       次フレームで「壊された」 判定が走り、 ピンも適切に除去される)。</li>
     * </ul>
     */
    private static boolean isStillContainerBlock(net.minecraft.client.multiplayer.ClientLevel level,
                                                 BlockPos pos) {
        if (level == null) return true;
        // 未ロード → AIR を返す MC 仕様の罠を回避: 「分からない」 ときは「まだ存在する」 と仮定。
        if (!level.isLoaded(pos)) return true;
        try {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            return com.kajiwara.omnichest.search.ContainerType.fromBlockState(state) != null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * ワールド上の {@link net.minecraft.world.entity.item.ItemEntity} に対して、
     * 現在ハイライト中の対象アイテム (= active.entries) と一致するものを wireframe で強調する。
     *
     * <p>
     * <b>動機</b>: チェストが壊されてアイテムがドロップした瞬間、 ユーザに
     * 「どれが目的の物か」 を伝え続けるため。 アイテムがプレイヤーインベントリへ入った後は
     * スロット側の overlay ({@link com.kajiwara.omnichest.mixin.SearchMatchSlotMixin}) が
     * 引き継ぐので、 ここはあくまでワールド上にある期間だけの強調。
     */
    private void renderItemEntityHighlights(net.minecraft.client.multiplayer.ClientLevel level,
                                            SubmitNodeCollector queue, PoseStack matrices,
                                            Vec3 camPos,
                                            ResourceKey<Level> currentDim, int themeRgb) {
        if (level == null || active.isEmpty()) return;
        int color = packColor(themeRgb, 1.0f);
        //? if >=26.2 {
        /*// 26.2: カスタム X-ray パイプライン (withUniform/VertexFormat.Mode 削除) は構築不可。 ボックスは
        //   バニラ RenderTypes.lines() で submit (深度テスト有り・Iris ネイティブ捕捉)。 ワイヤー決定と同方針。
        RenderType xray = RenderTypes.lines();*/
        //?} else {
        RenderType xray = xrayLines();
        //?}
        // クライアントが描画候補にしているエンティティ群を走査 (= 視界 / chunk 範囲内)
        for (net.minecraft.world.entity.Entity e : level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity ie)) continue;
            ItemStack stack = ie.getItem();
            if (stack == null || stack.isEmpty()) continue;
            if (!matchesAnyActive(stack)) continue;

            // ItemEntity の周囲に小型のワイヤーボックスを 1 つ描く。
            // AABB から取りたいが API が版違いで不安定なので、 中心 + 半径で簡易に組む。
            Vec3 ep = ie.position();
            float r = 0.22f;
            float x0 = (float) (ep.x - r - camPos.x) - BOX_INFLATE;
            float y0 = (float) (ep.y - camPos.y) - BOX_INFLATE;
            float z0 = (float) (ep.z - r - camPos.z) - BOX_INFLATE;
            float x1 = (float) (ep.x + r - camPos.x) + BOX_INFLATE;
            float y1 = (float) (ep.y + 0.5 - camPos.y) + BOX_INFLATE;
            float z1 = (float) (ep.z + r - camPos.z) + BOX_INFLATE;
            WireHighlightRenderer.submitWireBox(queue, matrices,
                    x0, y0, z0, x1, y1, z1, color, LINE_WIDTH);
        }
        @SuppressWarnings("unused") RenderType _x = xray;
    }

    /** 任意のスタックが現在の active 群いずれかにマッチするか。 */
    private boolean matchesAnyActive(ItemStack stack) {
        for (ActiveHighlight h : active.values()) {
            for (HighlightEntry e : h.entries) {
                if (ItemStack.isSameItemSameComponents(e.stack, stack)) return true;
            }
        }
        return false;
    }

    /**
     * ラージチェストは 2 ブロックを <b>1 つの矩形</b> として描く。
     * 単体チェストは従来通り 1 ブロック AABB。
     */
    private static void submitMergedBox(SubmitNodeCollector queue, PoseStack matrices, RenderType type,
                                        ContainerSnapshot snap, Vec3 camPos, int color) {
        BlockPos primary = snap.pos();
        BlockPos secondary = snap.secondaryPos();
        int x0, y0, z0, x1, y1, z1;
        if (secondary != null && snap.type() != null && snap.type().isDouble()) {
            x0 = Math.min(primary.getX(), secondary.getX());
            y0 = Math.min(primary.getY(), secondary.getY());
            z0 = Math.min(primary.getZ(), secondary.getZ());
            x1 = Math.max(primary.getX(), secondary.getX()) + 1;
            y1 = Math.max(primary.getY(), secondary.getY()) + 1;
            z1 = Math.max(primary.getZ(), secondary.getZ()) + 1;
        } else {
            x0 = primary.getX();
            y0 = primary.getY();
            z0 = primary.getZ();
            x1 = x0 + 1;
            y1 = y0 + 1;
            z1 = z0 + 1;
        }
        float fx0 = (float) (x0 - camPos.x) - BOX_INFLATE;
        float fy0 = (float) (y0 - camPos.y) - BOX_INFLATE;
        float fz0 = (float) (z0 - camPos.z) - BOX_INFLATE;
        float fx1 = (float) (x1 - camPos.x) + BOX_INFLATE;
        float fy1 = (float) (y1 - camPos.y) + BOX_INFLATE;
        float fz1 = (float) (z1 - camPos.z) + BOX_INFLATE;
        @SuppressWarnings("unused") RenderType _t = type;
        WireHighlightRenderer.submitWireBox(queue, matrices,
                fx0, fy0, fz0, fx1, fy1, fz1, color, LINE_WIDTH);
    }

    // ════════════════════════════════════════════════════════════════════
    // X-ray ボックス
    // ════════════════════════════════════════════════════════════════════

    /**
     * 1 ブロック分の wireframe box を camera-relative 座標で submit する。
     *
     * <p>
     * 実描画は {@link WireHighlightRenderer#submitWireBox} に委譲する。
     * これにより Iris / Sodium + Iris / Complementary / BSL / SEUS 等 shader 環境では
     * 「shader-safe QUAD 経路」 が自動で選択され、 ワイヤーが消失する不具合を回避する。
     *
     * <p>
     * <b>呼び出し互換</b>: 既存呼び出し側は xrayLines() の RenderType を渡してくるが、
     * 委譲先が環境別に最適な RenderType を内部選択するため、 引数 type は無視して構わない
     * (= 既存シグネチャ温存のため受け取るのみ)。
     */
    private static void submitBox(SubmitNodeCollector queue, PoseStack matrices, RenderType type,
            BlockPos pos, Vec3 camPos, int color) {
        // camera-relative 座標 (matrices は camera 原点で来る)
        float x0 = (float) (pos.getX() - camPos.x) - BOX_INFLATE;
        float y0 = (float) (pos.getY() - camPos.y) - BOX_INFLATE;
        float z0 = (float) (pos.getZ() - camPos.z) - BOX_INFLATE;
        float x1 = x0 + 1.0f + BOX_INFLATE * 2.0f;
        float y1 = y0 + 1.0f + BOX_INFLATE * 2.0f;
        float z1 = z0 + 1.0f + BOX_INFLATE * 2.0f;

        // shader 環境を自動判定し、 安全な描画経路を選択する。
        WireHighlightRenderer.submitWireBox(queue, matrices,
                x0, y0, z0, x1, y1, z1, color, LINE_WIDTH);
    }

    // ════════════════════════════════════════════════════════════════════
    // ピン (テキスト + 黒帯 + アイコン: ワールド ビルボード)
    // ════════════════════════════════════════════════════════════════════

    /**
     * チェスト 真上 (= {@link #PIN_BASE_HEIGHT}) にビルボード ピン を 1 個 描画する。
     * バニラ プレイヤーネームタグ と同じ pose 変換 (translate → camera 向き 回転 → scale) を行うため、
     * 「ピンがチェストの真上に固定」 されているように見える (= カメラを動かしても チェスト 中心 から離れない)。
     *
     * <p>
     * <b>SEE_THROUGH モード</b>: テキスト / 黒帯 は {@link Font.DisplayMode#SEE_THROUGH} 系の
     * 描画パスを使うのでブロック越しでも常に視認できる
     * (= 「どんなブロック越しでも ピン / ラベル が表示」 要件)。
     * 黒帯はアイコン領域の背後まで覆うため、 仮にアイコン本体 (= 真の 3D アイテム描画) が
     * 後段で depth に削られても、 ユーザにはピン位置とアイテム名・個数が常時可視となる。
     * アイコンは同じ pose を共有するためテキスト と <b>1px の精度で揃って</b> 表示される。
     *
     * <p>
     * <b>描画深度</b>: テキストおよび黒帯は {@link Font.DisplayMode#SEE_THROUGH} /
     * {@link RenderTypes#textBackgroundSeeThrough()} がいずれも NO_DEPTH_TEST 系の
     * RenderType を内部で選択する。 これにより明示的に depth state を弄らずに
     * 「壁越しに見えるピン」 を実現する (= Render state restore は RenderType 側で保証される)。
     *
     * <p>
     * <b>レイアウト</b>:
     * <ul>
     * <li>最上段は「▼ 距離」(中央揃え, themeRgb 色)。</li>
     * <li>その下のエントリ行は <b>左揃え</b>: [アイテム アイコン] [アイテム名 ×個数]。</li>
     * </ul>
     *
     * <p>
     * <b>距離スケール</b>: 基準距離 ({@link #PIN_SCALE_REF_DISTANCE}) より遠いとワールドスケールを
     * 距離に比例させて、 画面上のサイズを一定に保つ (= 透視で縮小されるのを打ち消す)。
     * 近距離は素のスケール で「近付くと大きく見える」 自然な挙動。
     */
    /**
     * ピン (テキスト + 黒帯) を <b>immediate-mode</b> で描く本体。 中心 {@code (cx, cz)} とピン下端
     * {@code baseY} を受け取り、 ブロック (= チェスト中心) / エンティティ (= 追従中心) 両経路で共有する。
     *
     * <p>
     * <b>水対策の核心</b>: ビルボード変換・レイアウト・スケール・色・SEE_THROUGH (= X-ray) は従来の
     * submit 版と完全に同一で、 描画先を {@code SubmitNodeCollector} から {@code bufferSource}
     * (= {@link #onAfterWaterRender} が水描画後に flush する自前バッファ) に替えただけ。 これにより
     * 「水がピンの上に重なる」 不具合だけを解消し、 見た目・遮蔽挙動は一切変えない。 アイコンは
     * 従来どおり HUD パスへ enqueue する。
     */
    //? if <26.2 {
    private static void drawPinImmediate(MultiBufferSource bufferSource, PoseStack matrices,
            CameraRenderState camState, double cx, double cz, double baseY, Vec3 camPos,
            List<HighlightEntry> entries, int themeRgb) {

        Font font = Minecraft.getInstance().font;
        int lineHeight = font.lineHeight;        // 通常 9
        int rowSpacing = lineHeight + 1;         // 行間 1 px

        // 距離計算 (画面サイズ一定スケール + 表示テキスト両方に使う)
        double dx = cx - camPos.x;
        double dy = baseY - camPos.y;
        double dz = cz - camPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        double distM = Math.sqrt(distSq);

        // ─── 遠距離クランプ (= 「遠い (>数百m) ピンが消える」 不具合の対処) ───
        // ピン/ラベル/アイコンはワールド空間に submit されるため、 <b>カメラの far クリップ平面</b>
        // (≈ 描画距離 ×16m) より遠いチェストでは GPU の幾何クリッピングで丸ごと消える
        // (= NO_DEPTH でも far 平面クリップは効くため、 depth test とは無関係に欠落する)。
        // far 平面の内側へカメラ→チェスト方向にピンを <b>引き寄せて</b> 描画することで、 どれだけ遠くても
        // 必ず可視にする (= 一般的な waypoint ピンの手法)。 距離ラベル (= 「▼ ◯m」) は実距離
        // {@code distM} のまま出すのでガイドとしての意味は保たれる。 スケールもクランプ後距離で計算するため、
        // 画面上のサイズは従来どおり一定。 クランプは far 平面より遠いときだけ効くので、 近距離 (= 既存の
        // 見え方) には一切影響しない。
        double maxRenderDist = pinMaxRenderDistance();
        double renderDist = Math.min(distM, maxRenderDist);
        double clampFactor = (distM > 1.0e-6) ? (renderDist / distM) : 1.0;
        double rdx = dx * clampFactor;
        double rdy = dy * clampFactor;
        double rdz = dz * clampFactor;
        // ピン/アイコンの実描画基準 (= クランプ後の絶対ワールド座標)。
        double renderCx = camPos.x + rdx;
        double renderCy = camPos.y + rdy;
        double renderCz = camPos.z + rdz;

        // ─── 行レイアウト ───
        // 最上段 (rowIndex = totalRows-1) に「▼ 距離」
        // その下にエントリを上から entries[0], entries[1], ..., entries[N-1] と並べる
        //   (画面上の縦順序: 上→下 = ▼距離, 1番目, 2番目, ..., 最後)
        int totalRows = (entries.isEmpty() ? 1 : entries.size() + 1);

        Component headerComp = Component.literal(
                String.format(Locale.ROOT, "▼ %.1fm", distM))
                .withColor(themeRgb);

        // エントリ本文の Component を 1 度だけ作って配列化する。
        // 同時に「本文 + アイコン」を含む左揃えブロックの最大幅も算出する。
        // アイコンは「行の高さに合わせた正方形 (= lineHeight x lineHeight)」として扱う。
        Component[] entryTexts = new Component[entries.size()];
        int entryIconSize = lineHeight;
        int entryIconGap = 2;
        int maxBlockWidth = 0;
        for (int i = 0; i < entries.size(); i++) {
            HighlightEntry e = entries.get(i);
            Component body = Component.literal(
                    e.stack.getHoverName().getString() + " ×" + e.count).withColor(0xFFFFFF);
            entryTexts[i] = body;
            int textW = font.width(body);
            int blockW = entryIconSize + entryIconGap + textW;
            if (blockW > maxBlockWidth) maxBlockWidth = blockW;
        }

        // ─── pose 変換 (バニラ NameTagFeatureRenderer.Storage.add と等価) ───
        // 基準距離より遠ければワールドスケールを距離に比例させる (= 透視縮小を打ち消す)。
        // スケール基準は <b>クランプ後距離</b> ({@code renderDist}): クランプで近くに引き寄せた位置で
        // 描いても画面上のサイズが従来 (= 実距離一定サイズ) と一致するようにするため。
        float distScaleFactor = (float) (Math.max(renderDist, PIN_SCALE_REF_DISTANCE)
                / PIN_SCALE_REF_DISTANCE);
        float worldScale = PIN_TEXT_SCALE * distScaleFactor;

        // 本文行はブロック (アイコン + テキスト) を画面中央に置きたいので、
        // 左端 X = -maxBlockWidth / 2 とする。 行ごとに左端からアイコン → テキストを並べる。
        float entryBlockLeftX = -maxBlockWidth / 2.0f;

        // アイテム アイコン解決用 (= バニラのアイテム描画パイプラインに乗せる)。
        Minecraft mc = Minecraft.getInstance();
        ItemModelResolver itemModelResolver = mc.getItemModelResolver();

        matrices.pushPose();
        try {
            // クランプ後座標へ平行移動 (= far 平面の内側に収める)。
            matrices.translate(rdx, rdy, rdz);
            matrices.mulPose(camState.orientation);
            matrices.scale(worldScale, -worldScale, worldScale);

            // ─── (a) ヘッダ「▼ 距離」 — 中央揃え、 最上段 ───
            int headerRowIndex = totalRows - 1;
            float headerY = -(headerRowIndex + 1) * (float) rowSpacing + 1.0f;
            int headerWidth = font.width(headerComp);
            // テキストのみ追加スケールを適用 (= 「Text scale のみ微調整」 要件)。
            // 中央揃えの基準座標を残すため、 scale 前の座標で submit 位置を計算してから
            // 局所 push/pop で囲む (= 他要素への副作用なし)。
            float headerTextX = -headerWidth / 2.0f;
            matrices.pushPose();
            try {
                matrices.translate(headerTextX, headerY, 0);
                matrices.scale(PIN_TEXT_LOCAL_SCALE, PIN_TEXT_LOCAL_SCALE, 1.0f);
                // immediate-mode text。 submitText と同じ引数割り当て:
                //   color=0xFFFFFFFF, dropShadow=false, displayMode=SEE_THROUGH, bgColor=PIN_BG_ARGB, light=0xF000F0。
                font.drawInBatch(headerComp.getVisualOrderText(), 0, 0,
                        0xFFFFFFFF, false, matrices.last().pose(), bufferSource,
                        Font.DisplayMode.SEE_THROUGH, PIN_BG_ARGB, 0xF000F0);
            } finally {
                matrices.popPose();
            }

            // ─── (b) エントリ行 — 左揃え、 行ごとに [アイコン] [テキスト] ───
            for (int i = 0; i < entries.size(); i++) {
                int rowIndex = totalRows - 2 - i; // entries[0] が ▼ の直下
                float rowY = -(rowIndex + 1) * (float) rowSpacing + 1.0f;

                Component body = entryTexts[i];
                HighlightEntry e = entries.get(i);

                // 行頭 (= アイコン位置) の黒帯背景。 アイコン → テキスト が 1 本の黒帯に乗って見えるよう、
                // テキスト bg と 1px だけ重ねる (= 接合部の隙間を消す)。
                // 行レイアウト / アイコンサイズ / 黒帯サイズ は変更しない (= 既存仕様温存)。
                float textX = entryBlockLeftX + entryIconSize + entryIconGap;
                drawPinRowBgImmediate(bufferSource, matrices,
                        entryBlockLeftX - 1, rowY - 1,
                        (textX + 1) - (entryBlockLeftX - 1),
                        (float) lineHeight,
                        PIN_BG_ARGB);

                // ─── アイコン: 世界パスではなく HUD パスで 2D 描画する ───
                // 世界パスの {@link ItemStackRenderState#submit} は DEPTH_TEST 必須レイヤで
                // 構成されており、 ガラス含む手前ブロックで隠れる。 ピン要件 「絶対に貫通」 を
                // 満たすため、 アイコン中心のワールド座標を画面座標に投影して キュー へ積み、
                // HUD パスで {@code GuiGraphicsExtractor.renderItem} で描く (= world depth と無関係)。
                // クランプ後の中心 (renderCx/Cy/Cz) を基準に投影する。 これにより遠距離チェストでも
                // アイコンの投影点が far 平面の内側に収まり、 HUD 投影 (= projectPointToScreen) が
                // 退化せずに画面座標へ落ちる (= テキスト/黒帯と完全に同じ基準なので 1px もズレない)。
                INSTANCE.enqueueHudIcon(e.stack, renderCx, renderCy, renderCz,
                        entryBlockLeftX + entryIconSize / 2.0f,
                        rowY + entryIconSize / 2.0f,
                        worldScale, entryIconSize,
                        camPos, camState.orientation);
                // 既存の itemModelResolver は HUD パスのアイテム描画が GuiGraphicsExtractor 経由で
                // 自前解決するため不要だが、 引数互換のため変数だけ参照しておく (= 未使用警告抑止)。
                @SuppressWarnings("unused") ItemModelResolver _ignored = itemModelResolver;

                // テキストはアイコンの右隣 (icon size + gap だけずらす)。
                // テキストのみ局所スケールを適用 (アイコン / 行 bg はサイズ維持)。
                FormattedCharSequence seq = body.getVisualOrderText();
                matrices.pushPose();
                try {
                    matrices.translate(textX, rowY, 0);
                    matrices.scale(PIN_TEXT_LOCAL_SCALE, PIN_TEXT_LOCAL_SCALE, 1.0f);
                    font.drawInBatch(seq, 0, 0,
                            0xFFFFFFFF, false, matrices.last().pose(), bufferSource,
                            Font.DisplayMode.SEE_THROUGH, PIN_BG_ARGB, 0xF000F0);
                } finally {
                    matrices.popPose();
                }
            }
        } finally {
            matrices.popPose();
        }
    }
    //?}

    /**
     * 検索ピン (= 名前タグ スタック) の <b>最上端</b> のワールド Y を返す。
     * ビーコン ビームの「発射基準点 (= ピンの一番真上)」 として {@link BeaconEffectLayer} へ渡す。
     *
     * <p>
     * {@link #drawPinImmediate} と同じ式 (= ピン アンカ {@link #PIN_BASE_HEIGHT} +
     * 行数 × 行間 × 距離連動ワールドスケール) を再現して計算する。 これにより:
     * <ul>
     *   <li>表示テキスト量 (= ヘッダ + アイテム行数) が増えるほど発射位置が上がる
     *       (= 「文字量に合わせて発射位置を変動」 要件)。</li>
     *   <li>距離に応じてピンの見かけサイズが一定になるのと同様、 発射位置もピン上端に追従する。</li>
     * </ul>
     * ピン本体の描画には一切影響しない (= 読み取り計算のみ)。
     *
     * @param entryCount アイテム行数 ({@code ActiveHighlight#entries.size()})
     */
    /**
     * ピン (ラベル + アイコン) をワールドに描くときの <b>最大描画距離</b> (m)。
     *
     * <p>
     * カメラの far クリップ平面 (≈ 描画距離 ×16m) より遠い点はワールド描画で幾何クリップされて
     * 消えるため、 ピンはこの距離までカメラ方向へ引き寄せて描く ({@link #drawPinImmediate})。
     * far 平面に余裕を持たせるため描画距離の 0.8 倍を採り、 極端に小さい描画距離設定でも
     * 1 チャンク程度は確保する。 設定が読めない場合は控えめな既定値で安全側に倒す。
     */
    private static double pinMaxRenderDistance() {
        try {
            int chunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
            return Math.max(16.0, chunks * 16.0 * 0.8);
        } catch (Throwable ignored) {
            return 128.0;
        }
    }

    private static double pinTopWorldY(ContainerSnapshot snap, int entryCount, Vec3 camPos) {
        BlockPos primary = snap.pos();
        BlockPos secondary = snap.secondaryPos();
        double cx;
        double cz;
        if (secondary != null && snap.type() != null && snap.type().isDouble()) {
            cx = (primary.getX() + secondary.getX()) * 0.5 + 0.5;
            cz = (primary.getZ() + secondary.getZ()) * 0.5 + 0.5;
        } else {
            cx = primary.getX() + 0.5;
            cz = primary.getZ() + 0.5;
        }
        // ピン アンカ (= テキスト スタック最下段) の世界 Y。
        double baseY = primary.getY() + 1.0 + PIN_BASE_HEIGHT;
        return pinTopWorldYCore(cx, cz, baseY, entryCount, camPos);
    }

    /**
     * ビーム発射基準 (= ピン上端) の計算本体。 中心 {@code (cx, cz)} とピン下端 {@code baseY} を
     * 明示的に受け取り、 ブロック / エンティティ両経路で共有する。
     */
    private static double pinTopWorldYCore(double cx, double cz, double baseY, int entryCount, Vec3 camPos) {
        // ワールドスケールは drawPinImmediate と同じく「アンカまでの距離」 で決まる。
        double dx = cx - camPos.x;
        double dy = baseY - camPos.y;
        double dz = cz - camPos.z;
        double distM = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float distScaleFactor = (float) (Math.max(distM, PIN_SCALE_REF_DISTANCE)
                / PIN_SCALE_REF_DISTANCE);
        float worldScale = PIN_TEXT_SCALE * distScaleFactor;

        // テキスト スタックの行数 (= ヘッダ「▼ 距離」 + アイテム行)。 entries が空でもヘッダ 1 行分はある。
        int rowSpacing = Minecraft.getInstance().font.lineHeight + 1;
        int totalRows = (entryCount <= 0 ? 1 : entryCount + 1);
        // スタックの世界高さ + わずかな余白 (= ビームが最上段の文字に被らないように 2px ぶん上へ)。
        double stackWorldHeight = (totalRows * rowSpacing + 2) * worldScale;
        return baseY + stackWorldHeight;
    }

    // ════════════════════════════════════════════════════════════════════
    // HUD パス アイコン: 世界パス DEPTH_TEST を回避してピンを完全貫通させる
    // ════════════════════════════════════════════════════════════════════

    /**
     * HUD パス用アイコンキューに 1 件積む。 世界パスで「アイコン中心のワールド座標」 を計算し、
     * 即座に画面座標へ投影しておく (= HUD パスでは GuiGraphicsExtractor に渡すだけにする)。
     *
     * @param chestX, chestY, chestZ   ピンが立っているチェスト中心のワールド座標
     * @param pinLocalX, pinLocalY     ピン内ローカル座標 (= フォント px 単位)
     * @param worldScale               ピンのワールドスケール (= フォント px → ワールド単位)
     * @param iconSizeFontPx           アイコン サイズ (= フォント px)
     * @param camPos                   カメラ位置 (ワールド)
     * @param camOrientation           カメラ回転 (= ローカル軸を ワールド軸へ写すクォータニオン)
     */
    private void enqueueHudIcon(ItemStack stack,
            double chestX, double chestY, double chestZ,
            float pinLocalX, float pinLocalY,
            float worldScale, int iconSizeFontPx,
            Vec3 camPos, Quaternionf camOrientation) {
        if (stack == null || stack.isEmpty()) return;

        // ─── ピンローカル座標 → ワールドオフセット ───
        // 元のポーズスタック (= drawPinImmediate 内) の順序は:
        //   translate(dx,dy,dz) → mulPose(orientation) → scale(s, -s, s)
        // 従って、 ローカル (pinLocalX, pinLocalY, 0) は
        //   1) scale で (px*s, -py*s, 0)
        //   2) orientation で ワールド軸 ベクトル に rotate
        // となる。
        Vector3f offset = new Vector3f(
                pinLocalX * worldScale,
                -pinLocalY * worldScale,
                0.0f);
        camOrientation.transform(offset);

        double iconWorldX = chestX + offset.x;
        double iconWorldY = chestY + offset.y;
        double iconWorldZ = chestZ + offset.z;

        float[] center = worldToScreen(iconWorldX, iconWorldY, iconWorldZ, camPos, camOrientation);
        if (center == null) return; // カメラ背後 / 投影 W ≤ 0

        // ─── アイコン スクリーンサイズ ───
        // 世界パスで表示されていたサイズに揃えるため、 「ワールドで iconSizeFontPx*worldScale ぶんの
        // ベクトル」 を投影して 画面 px に変換する (= 距離 / GUI スケール / FOV に自動追従)。
        float sizePx = computeIconScreenSize(iconWorldX, iconWorldY, iconWorldZ,
                iconSizeFontPx * worldScale, camPos, camOrientation);

        pendingHudIcons.add(new PendingHudIcon(stack.copy(), center[0], center[1], sizePx));
    }

    /**
     * HUD パス: キューに溜まったピンアイコンを 2D 描画する。
     * GuiGraphicsExtractor の通常描画なので世界 depth とは無関係 = 必ず最前面に出る (= 貫通保証)。
     */
    private void onHudRender(GuiGraphicsExtractor g) {
        //? if >=26.2 {
        /*// 26.2: ピン (背景+アイコン+テキスト) は HUD パスで毎フレーム live 投影して描く (= stale 防止)。
        //   pendingPins は world データのみを保持し、 ここで現在フレームの camera/GUI 寸法で投影し直すため、
        //   初回ウィンドウ寸法の stale や ESC ポーズ復帰後の「画面張り付き (カメラ非追従)」が起きない。
        drawPinsHudLive(g);
        return;*/
        //?}
        //? if <26.2 {
        if (pendingHudIcons.isEmpty()) return;
        // スナップショットを取って描画 (= HUD 中に世界パスが再 enqueue する場合の保険)。
        // 描画後は clear せず、 次フレームの onWorldRender が冒頭で clear する。
        List<PendingHudIcon> snapshot = new ArrayList<>(pendingHudIcons);
        for (PendingHudIcon icon : snapshot) {
            // 整数 snap を避けるため pose.translate に float をそのまま渡す。
            // renderItem(stack, 0, 0) の (0,0) は整数だが、 pose の sub-pixel オフセットが
            // そのまま反映されるため、 1-px ジッタは発生しない。
            float scale = icon.sizePx / 16.0f;
            float topLeftX = icon.screenX - icon.sizePx * 0.5f;
            float topLeftY = icon.screenY - icon.sizePx * 0.5f;
            var pose = g.pose();
            pose.pushMatrix();
            try {
                pose.translate(topLeftX, topLeftY);
                if (Math.abs(scale - 1.0f) > 1.0e-4f) {
                    pose.scale(scale, scale);
                }
                g.item(icon.stack, 0, 0);
            } finally {
                pose.popMatrix();
            }
        }
        //?}
    }

    //? if >=26.2 {
    /*// ════════════════════════════════════════════════════════════════════
    // 26.2 HUD 投影パス (毎フレーム live・stateless): ビームを submit、 ピン本体 (背景+アイコン+テキスト) を
    //   onHudRender で毎フレーム live 投影して描く。
    //
    //   <b>なぜ submit でなく HUD か</b>: submitText/submitNameTag は Iris シェーダ下でグリフが落ちる
    //   (VG 実証)。 そこで在世界テキストは world/Iris パス後の HUD で GUI 描画する (= VG WorldLabel.drawHud
    //   と同方針)。
    //
    //   <b>なぜ world パスで screen 座標を焼かないか (= staleness 回避)</b>:
    //   旧 26.2 実装は onWorldRender で screen 座標を pendingHudLabels/Icons に焼き onHudRender が blit して
    //   いたため、 (1) 初回フレームのウィンドウ/GUI 寸法が stale だとレイアウトがずれ (F11 リサイズで初めて
    //   直る)、 (2) ESC ポーズメニューへ入ると onHudRender が発火せず焼いた座標が古いまま残り、 戻ると
    //   ピンが画面に張り付く (カメラ非追従) という不具合になった。 そこで pendingPins (= world 座標のみ) を
    //   保持し、 onHudRender が毎フレーム live camera (mainCamera) と live GUI 寸法で投影し直す。 これにより
    //   「今フレームの正しい投影で今フレームのピンだけ」を描く (= VG GateName と同じ stateless)。
    // ════════════════════════════════════════════════════════════════════

    // onWorldRender 末尾 (= submit collector が有効なフェーズ) から呼ぶ: pending のビームのみ submit する
    //   (engine が debugFilledBox を translucent フェーズ=水後に描く)。 ピンは HUD パスへ委ねる。
    private void flushBeams262(SubmitNodeCollector queue, PoseStack matrices, Vec3 camPos) {
        for (PendingBeam b : pendingBeams) {
            BeaconEffectLayer.drawWorldSubmit(queue, matrices, b.cxWorld(), b.czWorld(),
                    camPos, b.themeRgb(), b.alpha(), b.baseWorldY());
        }
    }

    // HUD パス本体: pendingPins を毎フレーム live 投影してピンを描く。 Screen 表示中 / F1 はピン非描画
    //   (= ポーズ復帰時の張り付き回避・HUD 抑制と整合・VG GateName と同方針)。
    private void drawPinsHudLive(GuiGraphicsExtractor g) {
        if (pendingPins.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.gui.hud.isHidden()) {
            return;
        }
        net.minecraft.client.Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null) return;
        Vec3 camPos = cam.position();
        Quaternionf camOri = cam.rotation();
        Font font = mc.font;
        for (PendingPin p : new ArrayList<>(pendingPins)) {
            drawPinHudLive(g, font, camPos, camOri, p);
        }
    }

    // ピン 1 個を live 投影して描く。 レイアウト式 (距離クランプ/行/スケール/中央揃え) は旧 drawPinImmediate と
    //   同一。 billboard はカメラ正対なので「ピンローカル font-px → screen」はアフィン: 行原点を 1 度だけ投影し、
    //   pxPerFontPx (= 1 font-px あたりの screen px) で残りの要素を screen 空間オフセットで配置する (= 各要素が
    //   必ず整列)。 各行は 背景 (g.fill) → アイコン (g.item) → テキスト (g.text) の背面順で描く。
    private void drawPinHudLive(GuiGraphicsExtractor g, Font font, Vec3 camPos, Quaternionf camOri,
            PendingPin p) {
        double cx = p.cx();
        double cz = p.cz();
        double baseY = p.baseY();
        List<HighlightEntry> entries = p.entries();
        int themeRgb = p.themeRgb();

        int lineHeight = font.lineHeight;
        int rowSpacing = lineHeight + 1;

        double dx = cx - camPos.x;
        double dy = baseY - camPos.y;
        double dz = cz - camPos.z;
        double distM = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double maxRenderDist = pinMaxRenderDistance();
        double renderDist = Math.min(distM, maxRenderDist);
        double clampFactor = (distM > 1.0e-6) ? (renderDist / distM) : 1.0;
        double renderCx = camPos.x + dx * clampFactor;
        double renderCy = camPos.y + dy * clampFactor;
        double renderCz = camPos.z + dz * clampFactor;

        int totalRows = (entries.isEmpty() ? 1 : entries.size() + 1);
        Component headerComp = Component.literal(
                String.format(Locale.ROOT, "▼ %.1fm", distM)).withColor(themeRgb);

        Component[] entryTexts = new Component[entries.size()];
        int entryIconSize = lineHeight;
        int entryIconGap = 2;
        int maxBlockWidth = 0;
        for (int i = 0; i < entries.size(); i++) {
            HighlightEntry e = entries.get(i);
            Component body = Component.literal(
                    e.stack.getHoverName().getString() + " ×" + e.count).withColor(0xFFFFFF);
            entryTexts[i] = body;
            int blockW = entryIconSize + entryIconGap + font.width(body);
            if (blockW > maxBlockWidth) maxBlockWidth = blockW;
        }

        float distScaleFactor = (float) (Math.max(renderDist, PIN_SCALE_REF_DISTANCE)
                / PIN_SCALE_REF_DISTANCE);
        float worldScale = PIN_TEXT_SCALE * distScaleFactor;
        float entryBlockLeftX = -maxBlockWidth / 2.0f;

        // ─── ヘッダ「▼ 距離」 (中央揃え・最上段) ───
        int headerRowIndex = totalRows - 1;
        float headerY = -(headerRowIndex + 1) * (float) rowSpacing + 1.0f;
        int headerWidth = font.width(headerComp);
        float headerTextX = -headerWidth / 2.0f;
        float[] hOrigin = projectPinLocal(renderCx, renderCy, renderCz, headerTextX, headerY,
                worldScale, camPos, camOri);
        if (hOrigin != null) {
            float pp = hOrigin[2];
            // 背景: ヘッダ文字背後の黒帯 (= 旧 drawInBatch bg と同色 PIN_BG_ARGB)。
            fillPinBg(g, hOrigin[0] - pp, hOrigin[1] - pp, (headerWidth + 2) * pp, lineHeight * pp);
            drawPinText(g, font, headerComp.getVisualOrderText(), hOrigin[0], hOrigin[1],
                    pp * PIN_TEXT_LOCAL_SCALE, 0xFF000000 | (themeRgb & 0xFFFFFF));
        }

        // ─── エントリ行 [背景][アイコン][テキスト] ───
        for (int i = 0; i < entries.size(); i++) {
            int rowIndex = totalRows - 2 - i;
            float rowY = -(rowIndex + 1) * (float) rowSpacing + 1.0f;
            HighlightEntry e = entries.get(i);
            int textW = font.width(entryTexts[i]);
            float[] origin = projectPinLocal(renderCx, renderCy, renderCz, entryBlockLeftX, rowY,
                    worldScale, camPos, camOri);
            if (origin == null) continue;
            float ox = origin[0];
            float oy = origin[1];
            float pp = origin[2];
            // 背景: 行全体 (アイコン + gap + テキスト) を覆う黒帯 (= 旧 PIN_BG_ARGB・行全体)。
            fillPinBg(g, ox - pp, oy - pp,
                    (entryIconSize + entryIconGap + textW + 2) * pp, lineHeight * pp);
            // アイコン: 行頭。 画面サイズ = entryIconSize * pp (= 旧 computeIconScreenSize 経路と同値)。
            float iconSizePx = entryIconSize * pp;
            float iconCx = ox + (entryIconSize / 2.0f) * pp;
            float iconCy = oy + (entryIconSize / 2.0f) * pp;
            drawPinIcon(g, e.stack, iconCx - iconSizePx / 2.0f, iconCy - iconSizePx / 2.0f, iconSizePx);
            // テキスト: アイコンの右隣。 テキストのみ局所縮小 (PIN_TEXT_LOCAL_SCALE)。
            float textX = ox + (entryIconSize + entryIconGap) * pp;
            drawPinText(g, font, entryTexts[i].getVisualOrderText(), textX, oy,
                    pp * PIN_TEXT_LOCAL_SCALE, 0xFFFFFFFF);
        }
    }

    // ピンローカル (font-px) 座標 → screen。 戻り値 {screenX, screenY, pxPerFontPx}。 背後/画面外は null。
    //   pxPerFontPx は computeIconScreenSize (= アイコンと同一採寸) を lineHeight で割って算出 (= 文字/アイコン/
    //   背景が同一基準で揃う・26.1.x と同じ相対サイズ)。
    private float[] projectPinLocal(double rcx, double rcy, double rcz, float localX, float localY,
            float worldScale, Vec3 camPos, Quaternionf camOri) {
        Vector3f offset = new Vector3f(localX * worldScale, -localY * worldScale, 0.0f);
        camOri.transform(offset);
        double wx = rcx + offset.x;
        double wy = rcy + offset.y;
        double wz = rcz + offset.z;
        float[] screen = worldToScreen(wx, wy, wz, camPos, camOri);
        if (screen == null) return null;
        int lineHeight = Minecraft.getInstance().font.lineHeight;
        float rowScreenPx = computeIconScreenSize(wx, wy, wz, lineHeight * worldScale, camPos, camOri);
        return new float[] { screen[0], screen[1], rowScreenPx / lineHeight };
    }

    // 背景矩形 (= 旧 drawPinRowBgImmediate / drawInBatch bg と同色 PIN_BG_ARGB)。 GUI 空間 (identity pose) で塗る。
    private void fillPinBg(GuiGraphicsExtractor g, float left, float top, float width, float height) {
        g.fill(Math.round(left), Math.round(top), Math.round(left + width), Math.round(top + height),
                PIN_BG_ARGB);
    }

    // アイコン 1 個を screen 左上 + サイズ px で描く (sub-pixel は pose.translate に委ねる)。
    private void drawPinIcon(GuiGraphicsExtractor g, ItemStack stack, float topLeftX, float topLeftY,
            float sizePx) {
        if (stack == null || stack.isEmpty()) return;
        float scale = sizePx / 16.0f;
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate(topLeftX, topLeftY);
            if (Math.abs(scale - 1.0f) > 1.0e-4f) {
                pose.scale(scale, scale);
            }
            g.item(stack, 0, 0);
        } finally {
            pose.popMatrix();
        }
    }

    // テキスト 1 行を screen 左上 + scale で描く (sub-pixel は pose.translate に委ねる)。
    private void drawPinText(GuiGraphicsExtractor g, Font font,
            net.minecraft.util.FormattedCharSequence seq, float x, float y, float scale, int colorArgb) {
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate(x, y);
            if (Math.abs(scale - 1.0f) > 1.0e-4f) {
                pose.scale(scale, scale);
            }
            g.text(font, seq, 0, 0, colorArgb);
        } finally {
            pose.popMatrix();
        }
    }*/
    //?}

    /**
     * ワールド座標 → GUI スケール後のスクリーン座標 (= GuiGraphicsExtractor 用 px、 sub-pixel float) に投影する。
     * カメラ背後など投影不能なら null。
     *
     * <p>
     * <b>ジッタ対策の要</b>: 1.21.11 で {@code RenderSystem.getProjectionMatrix()} が消えた後、
     * 旧実装は {@code GameRenderer.getProjectionMatrix(options.fov())} を再構築していたが、
     * これは <b>設定 FOV</b> (= ズーム / 走り FOV ブースト / コンジット / 暗視 等の効果を無視した素値)
     * しか取れない。 一方、 ワールドレンダラ本体は内部で
     * {@code GameRenderer#getFov(camera, 0F, true)} (= effective FOV) を使うので、 走り出した瞬間の
     * 1.15× FOV ブースト 中など、 「テキストは effective FOV、 アイコンは設定 FOV」 でズレが出て
     * 1-px 単位のジッタ (= 「歩くと文字横アイコンが震える」 バグ) になっていた。
     *
     * <p>
     * 1.21.11 から {@link net.minecraft.client.renderer.GameRenderer#projectPointToScreen(Vec3)} が
     * 追加され、 内部で <b>そのフレームと同一</b> の (effective FOV + main camera) で proj * invRot を
     * 組んだ上で {@code Matrix4f.transformProject} (= NDC = clip/w) を返す。 これに乗り換えれば、
     * テキスト / 黒帯 / アイコン の 3 経路がすべて同じフレーム投影行列を使う = 1-px ジッタが消える。
     *
     * <p>
     * <b>背後判定</b>: {@code projectPointToScreen} 自体は w≤0 を null で弾かないので、 ここで
     * 「ビュー空間 Z &gt; 0 (= 背後 / 退化)」 を別途チェックして false-positive を防ぐ。
     *
     * @return {@code {screenX, screenY}} の sub-pixel float 配列。 背後なら null。
     */
    private static float[] worldToScreen(double worldX, double worldY, double worldZ,
                                         Vec3 camPos, Quaternionf camOrientation) {
        Minecraft mc = Minecraft.getInstance();

        // ─── 背後 / 退化 判定 (ビュー空間 Z で行う) ───
        // ビルボード描画と同じ inverse-orientation を使い、 ビュー空間 Z を見る。
        // バニラ projectPointToScreen は w 補正 (transformProject) で背後だと結果が反転する仕様だが、
        // 反転を許すと「カメラ真後ろの ピン」 が画面端に張り付くので、 ここで明示的に弾く。
        Vector3f camSpace = new Vector3f(
                (float) (worldX - camPos.x),
                (float) (worldY - camPos.y),
                (float) (worldZ - camPos.z));
        Quaternionf invOrient = new Quaternionf(camOrientation).conjugate();
        invOrient.transform(camSpace);
        // MC のカメラは -Z 方向を向くため、 ビュー空間で「前方」 は Z &lt; 0。
        // 0.05m より近い or 背後の点は描画対象外 (= 退化投影の暴れを回避)。
        if (camSpace.z > -0.05f) return null;

        // ─── effective FOV を使った投影 (フレームと一致) ───
        Vec3 ndc = mc.gameRenderer.projectPointToScreen(new Vec3(worldX, worldY, worldZ));
        if (ndc == null) return null;
        double nx = ndc.x;
        double ny = ndc.y;
        if (!Double.isFinite(nx) || !Double.isFinite(ny)) return null;

        // ─── NDC ([-1, 1]) → GUI スケール後の screen px (top-down 座標で Y 反転) ───
        var window = mc.getWindow();
        int sw = window.getGuiScaledWidth();
        int sh = window.getGuiScaledHeight();
        float screenX = (float) ((nx * 0.5 + 0.5) * sw);
        float screenY = (float) ((1.0 - (ny * 0.5 + 0.5)) * sh);
        return new float[]{screenX, screenY};
    }

    /**
     * ワールドの長さ {@code worldSpaceSize} (m) が画面上で何 px になるかを動的に算出する。
     * カメラ右方向に伸ばした端点を 2 度投影し、 画面距離を測ることで FOV / GUI スケール /
     * 距離 すべてに正しく追従する (= 世界パスのアイコン サイズと視覚的に揃う)。
     *
     * <p>
     * 戻り値は sub-pixel float。 ジッタ対策のため、 ピン位置と同じ精度で保持する
     * (= 投影 1px に達しない 揺らぎが整数 snap で 1-px 化するのを避ける)。
     */
    private static float computeIconScreenSize(double wx, double wy, double wz,
                                               float worldSpaceSize,
                                               Vec3 camPos, Quaternionf camOrientation) {
        float[] center = worldToScreen(wx, wy, wz, camPos, camOrientation);
        if (center == null) return 16.0f;
        Vector3f camRight = new Vector3f(1.0f, 0.0f, 0.0f);
        camOrientation.transform(camRight);
        float[] edge = worldToScreen(
                wx + camRight.x * worldSpaceSize,
                wy + camRight.y * worldSpaceSize,
                wz + camRight.z * worldSpaceSize,
                camPos, camOrientation);
        if (edge == null) return 16.0f;
        float dx = edge[0] - center[0];
        float dy = edge[1] - center[1];
        float size = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
        // クランプ: 極端な近距離で巨大化、 極遠で消えないようにする。
        if (size < 8.0f) size = 8.0f;
        if (size > 64.0f) size = 64.0f;
        return size;
    }

    /**
     * ピン 1 行ぶんの位置にアイテムアイコンをビルボード描画する。
     *
     * <p>
     * 呼び出し時点で matrices は「ピン billboard + worldScale (= font ピクセル単位, Y 下向き)」まで
     * 適用済み。 ここではバニラ {@code GuiGraphicsExtractor.renderItem} と同じ追加変換を行う:
     * <ol>
     *   <li>アイコンの中心へ translate (アイテム ジオメトリは中心原点)。</li>
     *   <li>Y 軸を反転 + iconSize に scale (アイテム ジオメトリ 1 単位幅 → iconSize 幅)。</li>
     * </ol>
     *
     * <p>
     * Z 方向には微小に手前 (+Z) へ寄せて、 同じ pose 内でテキスト背景と Z-fight するのを避ける。
     *
     * <p>
     * <b>解決メソッド</b>: バニラ {@code GuiGraphicsExtractor.renderItem} と同じ
     * {@code ItemModelResolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, level, player, 0)}
     * を呼ぶ (= インベントリ と同じ 平面アイコン に揃える)。
     *
     * <p>
     * <b>ライト</b>: {@code 0xF000F0} (= 天空 / ブロック 光 共に最大) を渡して、 夜 / 洞窟 でも
     * アイコンが暗くならないようにする (= 「常に最大輝度」)。
     */
    private static void submitPinIcon(PoseStack matrices, SubmitNodeCollector queue,
            ItemModelResolver itemModelResolver,
            ItemStack stack, float leftX, float topY, int iconSize) {
        if (stack == null || stack.isEmpty() || itemModelResolver == null) return;
        ItemStackRenderState state = new ItemStackRenderState();
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        net.minecraft.world.level.Level level = player.level();
        if (level == null) return;
        itemModelResolver.updateForTopItem(state, stack, ItemDisplayContext.GUI,
                level, player, 0);
        if (state.isEmpty()) return;

        matrices.pushPose();
        try {
            matrices.translate(
                    leftX + iconSize / 2.0f,
                    topY + iconSize / 2.0f,
                    0.5f);
            matrices.scale((float) iconSize, -(float) iconSize, (float) iconSize);
            // submit 第5引数 = outlineColor。 非 0 を渡すと 「縁取りシルエット」 パスが走り
            // アイテム本体を覆ってしまうので、 通常描画 (= 縁取りなし) は 0 を渡す。
            // 第3引数 = packed light: 0xF000F0 で天空/ブロック光 共に最大 (= 夜でも明るく描画)。
            state.submit(matrices, queue, 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
        } finally {
            matrices.popPose();
        }
    }

    /**
     * ピン行頭の黒帯背景 (= アイコン領域の後ろに「テキスト bg と同色 / 同じ blend モード」 の四角を出す)。
     *
     * <p>
     * {@link SubmitNodeCollector#submitText} の bg はテキスト rect しか覆わないので、
     * 左に並ぶアイテム アイコン領域の後ろは素通しになる。 ここを補うため、
     * {@link RenderTypes#textBackgroundSeeThrough()} で カスタム ジオメトリの四角を直接 submit する。
     * <ul>
     *   <li>テキスト bg と同じ shader = blend / depth の振る舞いが揃う (= SEE_THROUGH)。</li>
     *   <li>頂点フォーマットは {@code POSITION_COLOR_LIGHTMAP} ({@code .setColor + .setLight}) で十分。</li>
     * </ul>
     */
    //? if <26.2 {
    private static void drawPinRowBgImmediate(MultiBufferSource bufferSource, PoseStack matrices,
            float x, float y, float width, float height, int argb) {
        final float x1 = x;
        final float y1 = y;
        final float x2 = x + width;
        final float y2 = y + height;
        // submitCustomGeometry → bufferSource.getBuffer の immediate-mode 版 (= 頂点 / RenderType は同一)。
        VertexConsumer consumer = bufferSource.getBuffer(RenderTypes.textBackgroundSeeThrough());
        var pose = matrices.last();
        consumer.addVertex(pose, x1, y1, 0).setColor(argb).setLight(0xF000F0);
        consumer.addVertex(pose, x1, y2, 0).setColor(argb).setLight(0xF000F0);
        consumer.addVertex(pose, x2, y2, 0).setColor(argb).setLight(0xF000F0);
        consumer.addVertex(pose, x2, y1, 0).setColor(argb).setLight(0xF000F0);
    }
    //?}

    // ════════════════════════════════════════════════════════════════════
    // X-ray RenderType (lines, NO_DEPTH_TEST)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 「ブロック越しでも見える」線描画用の RenderType を構築する。
     * バニラの {@code core/rendertype_lines} シェーダをそのまま使い、
     * pipeline 設定のみ depth test を {@code NO_DEPTH_TEST} に差し替える。
     */
    //? if <26.2 {
    private static RenderType xrayLines() {
        RenderType cached = xrayLinesType;
        if (cached != null)
            return cached;
        synchronized (ChestHighlighter.class) {
            if (xrayLinesType != null)
                return xrayLinesType;

            // lines シェーダが要求する uniform バッファ群を snippet として束ねる。
            RenderPipeline.Snippet uniformsSnippet = RenderPipeline.builder()
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                    .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .buildSnippet();

            RenderPipeline pipeline = RenderPipeline.builder(uniformsSnippet)
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            "omnichest", "pipeline/xray_lines"))
                    .withVertexShader("core/rendertype_lines")
                    .withFragmentShader("core/rendertype_lines")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .withVertexFormat(
                            DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH,
                            VertexFormat.Mode.LINES)
                    .build();

            //? if >=1.21.11 {
            RenderSetup setup = RenderSetup.builder(pipeline).createRenderSetup();
            RenderType rt = RenderTypeAccessor.omnichest$create("omnichest_xray_lines", setup);
            //?} else {
            /*net.minecraft.client.renderer.RenderType.CompositeState.CompositeStateBuilder csb =
                    RenderType.CompositeState.builder();
            ((com.kajiwara.omnichest.mixin.CompositeStateBuilderAccessor) (Object) csb).omnichest$setLineState(
                    new net.minecraft.client.renderer.RenderStateShard.LineStateShard(java.util.OptionalDouble.of(LINE_WIDTH)));
            RenderType rt = RenderTypeAccessor.omnichest$create("omnichest_xray_lines", 1536, pipeline,
                    ((com.kajiwara.omnichest.mixin.CompositeStateBuilderAccessor) (Object) csb).omnichest$createCompositeState(false));*/
            //?}
            xrayLinesType = rt;
            return rt;
        }
    }
    //?}

    // ════════════════════════════════════════════════════════════════════
    // ヘルパ
    // ════════════════════════════════════════════════════════════════════

    private static int packColor(int rgb, float alphaF) {
        int a = Mth.clamp(Math.round(alphaF * 255), 0, 255);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    /**
     * 1 つのハイライト対象。
     *
     * @param waypoint true = 経由コンテナ (シュルカー)。 スロット overlay 一致には使うが、
     *                 ワールドのピン (名前タグ) には表示しない。 false = 通常の検索対象アイテム。
     */
    private record HighlightEntry(ItemStack stack, int count, boolean waypoint) {
    }

    private static final class ActiveHighlight {
        final ContainerSnapshot snapshot;
        final List<HighlightEntry> entries;
        long expiresAt;
        /**
         * true なら {@link ChestHighlighter#onWorldRender(WorldRenderContext)} で
         * このエントリのワールド側描画 (ピン + X-ray ボックス) をスキップする。
         * 「対象チェストを開いた瞬間にピンを消す (= pinPersistUntilOpened)」用フラグ。
         */
        boolean worldRenderSuppressed;
        /**
         * true なら 「ハイライト対象チェストが破壊された」 フェーズ。
         * <ul>
         *   <li>ワイヤー/ピン描画は停止される</li>
         *   <li>{@link #expiresAt} は {@link #CHEST_BROKEN_GRACE_MS} で clamp 済</li>
         *   <li>ドロップアイテム ({@link net.minecraft.world.entity.item.ItemEntity}) と
         *       プレイヤーインベントリのスロットで引き継ぎハイライトが出る</li>
         * </ul>
         */
        boolean chestBroken;

        ActiveHighlight(ContainerSnapshot snapshot, List<HighlightEntry> entries, long expiresAt) {
            this.snapshot = snapshot;
            this.entries = entries;
            this.expiresAt = expiresAt;
            this.worldRenderSuppressed = false;
            this.chestBroken = false;
        }

        /**
         * ワールドのピン (名前タグ スタック) に表示すべきエントリ (= waypoint を除いた検索対象アイテム)。
         * waypoint (= 経由シュルカー) はスロット overlay 専用なのでピンには出さない。
         */
        List<HighlightEntry> pinEntries() {
            List<HighlightEntry> out = new ArrayList<>(entries.size());
            for (HighlightEntry e : entries) {
                if (!e.waypoint) {
                    out.add(e);
                }
            }
            return out;
        }
    }
}
