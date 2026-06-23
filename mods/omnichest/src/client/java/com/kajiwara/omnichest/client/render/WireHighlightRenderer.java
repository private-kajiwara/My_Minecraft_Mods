package com.kajiwara.omnichest.client.render;

import com.kajiwara.omnichest.client.compat.ShaderCompatManager;
import com.kajiwara.omnichest.mixin.RenderTypeAccessor;
import com.mojang.blaze3d.pipeline.BlendFunction;
//? if >=26.1 {
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
//?} else {
/*import com.mojang.blaze3d.platform.DepthTestFunction;*/
//?}
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.renderer.RenderType;*/
//?}
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 「倉庫検索ハイライト」 ワイヤー (= X-ray ボックス) 描画の <b>shader-safe</b> ラッパ。
 *
 * <p>
 * <b>背景</b>:
 * <ul>
 *   <li>非 shader 環境では {@code core/rendertype_lines} シェーダ + {@code setLineWidth}
 *       による線描画 + {@link DepthTestFunction#NO_DEPTH_TEST} で
 *       ブロック越しのワイヤー表示 (= X-ray) が成立していた。</li>
 *   <li>Iris / Sodium + Iris / Complementary / BSL / SEUS 等の shader pack を入れると、
 *       カスタム pipeline が shader pack の rendering path に乗らない (= Iris キャプチャ外で
 *       上書きされる) ため、 ワイヤー (= 線) が <b>完全に表示されない</b> 現象が報告される。</li>
 * </ul>
 *
 * <p>
 * <b>方針</b> (= VisualizeGate {@code OverlayDraw} で動作確認済みの経路を踏襲):
 * <ol>
 *   <li>{@link ShaderCompatManager#isShaderPackInUse()} で shader 環境をソフト判定する
 *       (= Iris 非搭載でも安全・false 側に倒す)。</li>
 *   <li><b>非 shader (および legacy 全環境)</b>: 既存の {@code rendertype_lines} ベース pipeline
 *       ({@code NO_DEPTH_TEST} = X-ray) を {@link SubmitNodeCollector} へ submit する
 *       (= 既存挙動の<b>ピクセル不変</b>な温存)。</li>
 *   <li><b>shader 時 (&gt;=26.1)</b>: カスタム pipeline を一切使わず、 バニラ {@code RenderTypes.lines()}
 *       を<b>レベルレンダラ自身のバッファ ({@code ctx.bufferSource()}・Iris がラップする)</b> へ流す。
 *       submit/自前 immediate ではなく level バッファへ描く点だけが非 shader と違い、 これにより
 *       Iris の {@code rendertype_lines} プログラムに乗ってバニラのブロック選択枠と同様の<b>本物
 *       ワイヤー</b>として出る (= 深度オクルージョン有り)。 描画は呼び出しフレームの中で即時 submit
 *       せず、 {@link #enqueueShaderWire} で pending に積み、 水後ステージ
 *       ({@code AFTER_TRANSLUCENT_TERRAIN}) の {@link #flushShaderWires} で level バッファへ流す
 *       (= フラッシュは level に委ねる。 自前 endBatch すると Iris キャプチャ外で消えるため)。</li>
 * </ol>
 *
 * <p>
 * <b>挙動差</b>: shader 時のワイヤーは<b>深度テスト有り</b> (= 地形に遮蔽される) になる
 * (バニラ {@code lines()} の本質的帰結)。 現状はそもそも shader 時に完全に消えていたため、
 * 「消える」 → 「地形オクルージョン有りで確実に出る」 への前進。 非 shader 時は従来どおり X-ray を維持する。
 *
 * <p>
 * <b>禁止事項適合</b>:
 * <ul>
 *   <li>{@code glBegin / glEnd} は使わない (= raw GL 直叩き禁止)。</li>
 *   <li>deprecated immediate rendering を使わない。</li>
 *   <li>VertexConsumer / RenderType / PoseStack 経由の Minecraft 標準描画 API のみ使用。</li>
 *   <li>新規描画 Mixin を増やさない (= 既存の {@link RenderTypeAccessor} のみ使用)。</li>
 * </ul>
 */
public final class WireHighlightRenderer {

    /** 共有 uniforms snippet (= lines pipeline が要求する基本 uniform セット)。 */
    private static volatile RenderPipeline.Snippet uniformsSnippetCache;

    /** Lines 版 RenderType (非 shader 用; 既存挙動の継承)。 */
    private static volatile RenderType linesType;

    /**
     * shader 時に water 後ステージで level バッファへ流すための pending ワイヤーボックス
     * (camera-relative 座標)。 描画スレッド単独で読み書き (= onWorldRender で積み onAfterWaterRender で flush)。
     * legacy (&lt;26.1) では {@link #submitWireBox} が enqueue しないため常に空。
     */
    private static final List<PendingWire> pendingShaderWires = new ArrayList<>();

    /** pending ワイヤーボックス 1 件 (= camera-relative AABB + 色 + 線幅)。 */
    private record PendingWire(float x0, float y0, float z0, float x1, float y1, float z1,
            int color, float lineWidth) {
    }

    private WireHighlightRenderer() {
    }

    /**
     * 12 辺のワイヤー (= 1 ブロックぶんの AABB ボックス) を、 環境に応じて最適なパスで描画する。
     *
     * <p>
     * 呼び出し側は (x0,y0,z0)〜(x1,y1,z1) の AABB を camera-relative 座標で渡す。
     *
     * @param lineWidth 線の太さ (lines shader が読む値)
     */
    public static void submitWireBox(SubmitNodeCollector queue, PoseStack matrices,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            int color, float lineWidth) {

        //? if >=26.2 {
        /*// 26.2: カスタム X-ray パイプライン (withUniform/VertexFormat.Mode) は API 削除で構築不可。 バニラ
        //   RenderTypes.lines() を submit へ一本化する。 深度テスト有り (= 壁越し X-ray は失われる) になるが、
        //   submit 経路は Iris がネイティブ捕捉する (= バニラのブロック選択枠と同経路) ので、 シェーダ ON でも
        //   ワイヤーが出る。 シェーダ判定/pending/level バッファ flush は不要 (= 共通 1 経路)。
        queue.submitCustomGeometry(matrices, RenderTypes.lines(),
                (pose, consumer) -> addBoxEdges(consumer, pose, x0, y0, z0, x1, y1, z1, color, lineWidth));*/
        //?} else {
        //? if >=26.1 {
        if (ShaderCompatManager.isShaderPackInUse()) {
            // shader 環境: カスタム pipeline は Iris キャプチャ外で消えるため使わない。 バニラ lines() を
            // level バッファへ流すべく pending に積み、 水後ステージ (flushShaderWires) で描く。
            enqueueShaderWire(x0, y0, z0, x1, y1, z1, color, lineWidth);
            return;
        }
        //?}
        // 非 shader (および legacy 全環境): 既存の rendertype_lines (NO_DEPTH_TEST = X-ray) を submit。
        submitLineWireBox(queue, matrices, x0, y0, z0, x1, y1, z1, color, lineWidth);
        //?}
    }

    // ════════════════════════════════════════════════════════════════════
    // (a) 非 shader 経路: 既存挙動と同一の rendertype_lines + setLineWidth
    // ════════════════════════════════════════════════════════════════════

    //? if <26.2 {
    private static void submitLineWireBox(SubmitNodeCollector queue, PoseStack matrices,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            int color, float lineWidth) {
        RenderType type = linesRenderType();
        queue.submitCustomGeometry(matrices, type, (pose, consumer) ->
                addBoxEdges(consumer, pose, x0, y0, z0, x1, y1, z1, color, lineWidth));
    }
    //?}

    /** AABB の 12 辺を {@code lines()} 頂点として {@code consumer} へ流す (submit / level バッファ共通)。 */
    private static void addBoxEdges(VertexConsumer consumer, PoseStack.Pose pose,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            int color, float lineWidth) {
        // 底面 4 辺
        addLine(consumer, pose, x0, y0, z0, x1, y0, z0, color, lineWidth);
        addLine(consumer, pose, x1, y0, z0, x1, y0, z1, color, lineWidth);
        addLine(consumer, pose, x1, y0, z1, x0, y0, z1, color, lineWidth);
        addLine(consumer, pose, x0, y0, z1, x0, y0, z0, color, lineWidth);
        // 上面 4 辺
        addLine(consumer, pose, x0, y1, z0, x1, y1, z0, color, lineWidth);
        addLine(consumer, pose, x1, y1, z0, x1, y1, z1, color, lineWidth);
        addLine(consumer, pose, x1, y1, z1, x0, y1, z1, color, lineWidth);
        addLine(consumer, pose, x0, y1, z1, x0, y1, z0, color, lineWidth);
        // 垂直 4 辺
        addLine(consumer, pose, x0, y0, z0, x0, y1, z0, color, lineWidth);
        addLine(consumer, pose, x1, y0, z0, x1, y1, z0, color, lineWidth);
        addLine(consumer, pose, x1, y0, z1, x1, y1, z1, color, lineWidth);
        addLine(consumer, pose, x0, y0, z1, x0, y1, z1, color, lineWidth);
    }

    private static void addLine(VertexConsumer c, PoseStack.Pose pose,
            float x1, float y1, float z1, float x2, float y2, float z2,
            int color, float lineWidth) {
        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
        float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        c.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
        c.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
    }

    // ════════════════════════════════════════════════════════════════════
    // (b) shader 経路: バニラ lines() を level バッファ (Iris ラップ) へ流す
    // ════════════════════════════════════════════════════════════════════

    /** shader 時の pending ワイヤーボックスを積む (camera-relative 座標)。 */
    private static void enqueueShaderWire(float x0, float y0, float z0,
            float x1, float y1, float z1, int color, float lineWidth) {
        pendingShaderWires.add(new PendingWire(x0, y0, z0, x1, y1, z1, color, lineWidth));
    }

    /** 新フレーム開始時に pending を空にする (= 前フレームの取り残しを残さない)。 */
    public static void clearPending() {
        pendingShaderWires.clear();
    }

    /** shader 時に flush すべき pending ワイヤーがあるか。 legacy では常に false。 */
    public static boolean hasPending() {
        return !pendingShaderWires.isEmpty();
    }

    /**
     * pending ワイヤーボックスを、 渡された level バッファ ({@code ctx.bufferSource()}・Iris ラップ) へ
     * バニラ {@code lines()} で流す。 <b>endBatch しない</b> (= フラッシュは level レンダラに委ねる。
     * 自前 endBatch すると Iris キャプチャ外で消えるため)。 水後ステージ ({@code AFTER_TRANSLUCENT_TERRAIN})
     * から呼ぶ。
     */
    //? if <26.2 {
    public static void flushShaderWires(MultiBufferSource target, PoseStack matrices) {
        if (pendingShaderWires.isEmpty()) {
            return;
        }
        //? if >=1.21.11 {
        VertexConsumer c = target.getBuffer(RenderTypes.lines());
        //?} else {
        /*VertexConsumer c = target.getBuffer(RenderType.lines());*/
        //?}
        PoseStack.Pose pose = matrices.last();
        for (PendingWire w : pendingShaderWires) {
            addBoxEdges(c, pose, w.x0(), w.y0(), w.z0(), w.x1(), w.y1(), w.z1(), w.color(), w.lineWidth());
        }
    }
    //?}

    // ════════════════════════════════════════════════════════════════════
    // RenderType 構築 (lazy + double-checked locking)
    // ════════════════════════════════════════════════════════════════════

    //? if <26.2 {
    private static RenderPipeline.Snippet uniformsSnippet() {
        RenderPipeline.Snippet cached = uniformsSnippetCache;
        if (cached != null) return cached;
        synchronized (WireHighlightRenderer.class) {
            if (uniformsSnippetCache != null) return uniformsSnippetCache;
            uniformsSnippetCache = RenderPipeline.builder()
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                    .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .buildSnippet();
            return uniformsSnippetCache;
        }
    }
    //?}

    /** 非 shader 用: rendertype_lines + NO_DEPTH_TEST。 既存 xrayLines と同一。 */
    //? if <26.2 {
    private static RenderType linesRenderType() {
        RenderType cached = linesType;
        if (cached != null) return cached;
        synchronized (WireHighlightRenderer.class) {
            if (linesType != null) return linesType;

            RenderPipeline pipeline = RenderPipeline.builder(uniformsSnippet())
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            "omnichest", "pipeline/xray_lines_v2"))
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
            linesType = RenderTypeAccessor.omnichest$create("omnichest_xray_lines_v2", setup);
            //?} else {
            /*net.minecraft.client.renderer.RenderType.CompositeState.CompositeStateBuilder csb =
                    RenderType.CompositeState.builder();
            ((com.kajiwara.omnichest.mixin.CompositeStateBuilderAccessor) (Object) csb).omnichest$setLineState(
                    new net.minecraft.client.renderer.RenderStateShard.LineStateShard(java.util.OptionalDouble.of(3.5)));
            linesType = RenderTypeAccessor.omnichest$create("omnichest_xray_lines_v2", 1536, pipeline,
                    ((com.kajiwara.omnichest.mixin.CompositeStateBuilderAccessor) (Object) csb).omnichest$createCompositeState(false));*/
            //?}
            return linesType;
        }
    }
    //?}
}
