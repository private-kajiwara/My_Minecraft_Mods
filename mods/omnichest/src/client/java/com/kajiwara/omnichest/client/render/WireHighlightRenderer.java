package com.kajiwara.omnichest.client.render;

import com.kajiwara.omnichest.client.compat.IrisPipelineBridge;
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
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.renderer.RenderType;*/
//?}
import net.minecraft.util.Mth;

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
 * <b>方針</b> (= shader ON/OFF 単一経路): shader 判定で経路を分けず、 常に {@code rendertype_lines}
 * カスタム pipeline ({@code NO_DEPTH_TEST} = X-ray) を {@link SubmitNodeCollector} へ submit する。
 * shader 対応は「別経路への載せ替え」 ではなく、 pipeline 構築時に
 * {@link IrisPipelineBridge#assignLines(com.mojang.blaze3d.pipeline.RenderPipeline)} で
 * {@code IrisApi.assignPipeline(pipeline, IrisProgram.LINES)} を呼び、 <b>このカスタム pipeline を Iris の
 * LINES プログラムへ登録</b>して実現する (reflection・Iris 非搭載時は no-op)。 これにより:
 * <ul>
 *   <li>shader ON でも Iris がこの pipeline を捕捉して描画する (= 表示される)。</li>
 *   <li>pipeline が {@code NO_DEPTH_TEST} を保持するため、 Iris が深度状態を尊重すれば shader 下でも
 *       壁抜け (= X-ray) になる。 非 shader 時は従来どおり X-ray。</li>
 * </ul>
 * ピン / ビームの描画経路・遮蔽挙動には一切干渉しない (= 貫通化はワイヤー限定)。
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

    /** X-ray ワイヤー用 RenderType (rendertype_lines カスタム pipeline; shader ON/OFF 共通)。 */
    private static volatile RenderType linesType;

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
        // shader ON/OFF を問わず単一経路: rendertype_lines (NO_DEPTH_TEST = X-ray) カスタム pipeline を submit。
        // shader 時は {@link #linesRenderType()} 構築時に IrisApi.assignPipeline(pipeline, LINES) で Iris へ
        // 登録済みのため、 Iris がこのカスタム pipeline を LINES プログラムで捕捉して描く (= shader 下でも表示)。
        // pipeline が NO_DEPTH_TEST を保持するので、 Iris が深度状態を尊重すれば shader 下でも壁抜け (x-ray)。
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

            // shader 対応: このカスタム pipeline を Iris の LINES プログラムへ登録する (reflection・Iris 無しは no-op)。
            // これで shader ON 時も Iris が捕捉して描画し、 NO_DEPTH_TEST が尊重されれば壁抜け (x-ray) になる。
            IrisPipelineBridge.assignLines(pipeline);

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
