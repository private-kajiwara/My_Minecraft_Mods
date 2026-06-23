package com.kajiwara.visualizegate.client.render;

// ⑬ 真の GPU3D 点群レンダラ。 <b>>=1.21.11</b> (1.21.11 へ移植済・2026-06-20)。 1.21.10 のみスタブ (usable()=false →
// Screen が texbatch)。 合成は版で分岐: >=26.1 は colorView()+GpuTextureView 直 blit (実績不変)、 <26.1(=1.21.11) は
// FBO color を {@link #ensureCompositeTexture()} で登録テクスチャ化し Screen 側が Identifier-blit (下記)。
//
// 【1.21.11 への移植根拠 (javap+decompile+実コンパイル+runtime probe 実証・2026-06-20・layered Mojmap jar)】
//   GPU パイプライン自体は 1.21.11 にほぼ全部在る:
//   GpuSampler/SamplerCache・TextureTarget(色+深度)・RenderPipelines.DEBUG_POINTS/DEBUG_QUADS・RenderPass・
//   CommandEncoder(5引数 createRenderPass)・GpuBuffer.USAGE_VERTEX・DynamicUniforms.writeTransform・
//   BufferBuilder.setLineWidth・Tesselator・getSequentialBuffer・ProjectionType は<b>同シグネチャで存在</b>。
//   名前差も橋渡し可: ProjectionMatrixBuffer → PerspectiveProjectionMatrixBuffer(String)+getBuffer(Matrix4f) の
//   1:1 替え玉、 DepthStencilState.DEFAULT/withDepthStencilState → withDepthTestFunction()+withDepthWrite()。
//   ＝<b>オフスクリーン FBO 描画だけなら 1.21.11 でも実現可能</b>。
//   合成の版差 (Path A・runtime probe 実証): g.blit(GpuTextureView, GpuSampler,…) は 26.1 GuiGraphicsExtractor では public
//   だが 1.21.11 GuiGraphics では private (submitBlit) ＝ public 同等無し。 そこで <26.1 は FBO color を public
//   {@link net.minecraft.client.renderer.texture.AbstractTexture} 派生 (textureView/sampler を FBO へ向け close() no-op)
//   に包んで {@code textureManager.register(Identifier,…)} し、 全版 public な Identifier-blit (g.blit(GUI_TEXTURED 経由・
//   v0=1,v1=0 で V 反転)) で合成する ({@link #ensureCompositeTexture()})。 CPU readback 無し・Mixin 無し・on-GPU。
//   ※ 旧コメントの「GpuSampler/SamplerCache/ProjectionMatrixBuffer が無い」は<b>事実誤り</b>だった (javap で否定)。
//? if >=1.21.11 {
import java.util.OptionalDouble;
//? if >=26.2 {
/*import java.util.Optional;*/
//?} else {
import java.util.OptionalInt;
//?}

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
//? if >=26.2 {
/*import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;*/
//?} else {
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.shaders.UniformType;
//?}
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
//? if <26.2 {
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
//?}

import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
//? if <26.1 {
/*// <26.1 (1.21.11) のみ: Path A 合成 (FBO color を登録テクスチャ化して Identifier-blit) 用。
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;*/
//?}

/**
 * ⑬ 点群ポップアップを<b>真の GPU3D</b> で描く (Mixin 不使用・<b>>=1.21.11</b>)。
 *
 * <p>オフスクリーン {@link TextureTarget} (色+<b>深度</b>) へ、 <b>カメラ非依存の頂点バッファ</b>を自前オービット
 * 投影＋<b>GPU 深度テスト/書込み</b>で描き、 FBO 色を GUI ビューポートへ合成する。
 *
 * <p><b>パイプライン</b> ({@link #ensurePipelines}): ⑯ 点群は vanilla <b>{@code RenderPipelines.DEBUG_POINTS}</b>
 * (GL 点・1 頂点/点・深度 {@code DEFAULT}=test+write・shader が {@code LineWidth} 頂点属性を {@code gl_PointSize}
 * にする)＝<b>1 点 1 頂点</b>でキューブ (8〜24 頂点) 比 桁違いに軽く<b>数十万〜百万点</b>が現実的 (重なりは GPU 深度で
 * 解決のまま)。 以前 "Missing LineWidth" で落ちたのは {@code POSITION_COLOR} で begin したため＝今回は
 * <b>{@code DEBUG_POINTS} の {@code POSITION_COLOR_LINE_WIDTH} で begin し各頂点に {@code setLineWidth(点サイズpx)}</b>
 * を書く。 リンク線/ゲート/現在地マーカーは {@link RenderPipeline#builder} 製の {@code POSITION_COLOR}/{@code QUADS}/
 * 深度 {@code DEFAULT} 自前パイプライン ({@code quadPipeline}) で<b>太さを持つ 3D ボックス/キューブ/十字</b>として描く
 * (数が少なくコスト無視・生 1px GL ラインは 4K で細すぎ＝不採用)。
 *
 * <p>頂点バッファはデータ/トグル/spacing/点サイズ/detail 変化時だけ {@link #uploadPoints}/{@link #uploadOverlay} で
 * 再構築し、 <b>回転/ズームは行列更新のみ</b> ({@link #render})＝再ラスタライズ無し。 GPU 深度で同層内も 2 層間も
 * 正しく遮蔽。 失敗時は {@link #failed} を立て、 呼び出し側が texbatch へ戻る。
 */
public final class PointCloudGpuRenderer {

    private static TextureTarget fbo;
    private static int fboW;
    private static int fboH;
    private static ProjectionMatrixBuffer projBuf;
    private static boolean failed = false;
    private static String lastError = "(none)";

    /** マーカー類=POSITION_COLOR / QUADS / 深度 DEFAULT / cull off。 角柱/キューブ/十字 (展開済み quad)。 */
    private static RenderPipeline quadPipeline;

    private static GpuBuffer pointsVbo;
    private static int pointsCount;        // ⑯ 点群 GL 点の頂点数 (= 点数・非索引描画)
    private static GpuBuffer overlayVbo;
    private static int overlayIndexCount;  // マーカー類 (QUADS) 索引数 (= quad 数×6)

    private PointCloudGpuRenderer() {
    }

    /** マーカー用自前パイプライン (POSITION_COLOR・深度 test+write) を遅延生成。 点群は vanilla DEBUG_POINTS。 */
    private static void ensurePipelines() {
        if (quadPipeline == null) {
            //? if >=26.2 {
            /*// 26.2: 自前パイプライン構築 API は BindGroupLayout/snippet 系へ全面刷新 (withUniform/withVertexFormat 削除)。
            //   vanilla RenderPipelines.DEBUG_QUADS (POSITION_COLOR / QUADS / BindGroupLayouts.MATRICES_PROJECTION
            //   ＝DynamicTransforms+Projection を name で束縛) を再利用＝自前パイプラインビルド不要。 深度/カラーターゲット
            //   の実挙動は user runClient で要確認 (不一致時は usable()=false で texbatch フォールバック)。
            quadPipeline = RenderPipelines.DEBUG_QUADS;*/
            //?} else {
            quadPipeline = RenderPipeline.builder()
                    .withLocation("visualizegate/pipeline/pc_quads")
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .withCull(false)
                    .build();
            //?}
        }
    }

    public static boolean usable() {
        return !failed;
    }

    public static String lastError() {
        return lastError;
    }

    public static GpuTextureView colorView() {
        return (fbo != null && !failed) ? fbo.getColorTextureView() : null;
    }

    public static GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }

    /**
     * ⑯ 点群 (xyz の 3 連結 + argb) を<b>GL 点</b> (1 頂点/点) として VBO 化 (データ変化時のみ)。 vanilla
     * {@code DEBUG_POINTS} の {@code POSITION_COLOR_LINE_WIDTH} で begin し、 各頂点へ position+color+
     * <b>{@code setLineWidth(pointSizePx)}</b> を書く (shader が LineWidth を {@code gl_PointSize} に使う)＝
     * キューブ比 桁違いに軽く高密度可。 深度テスト/書込みは DEBUG_POINTS の {@code DEFAULT} で効く。
     */
    public static void uploadPoints(float[] xyz, int[] argb, int n, float pointSizePx) {
        pointsCount = 0;
        if (failed || n <= 0) {
            return;
        }
        try {
            //? if >=26.2 {
            /*// 26.2: Tesselator 削除 → 自前 ByteBufferBuilder + BufferBuilder。 topology/format は vanilla DEBUG_POINTS から。
            ByteBufferBuilder bbb = new ByteBufferBuilder(Math.max(256, n * 24));
            try {
                BufferBuilder bb = new BufferBuilder(bbb,
                        RenderPipelines.DEBUG_POINTS.getPrimitiveTopology(),
                        RenderPipelines.DEBUG_POINTS.getVertexFormatBinding(0));
                for (int i = 0; i < n; i++) {
                    bb.addVertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
                            .setColor(argb[i])
                            .setLineWidth(pointSizePx);
                }
                MeshData mesh = bb.build();
                if (mesh != null) {
                    try (mesh) {
                        if (pointsVbo != null) {
                            pointsVbo.close();
                        }
                        pointsVbo = RenderSystem.getDevice().createBuffer(() -> "visualizegate-pc-points",
                                GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                        pointsCount = mesh.drawState().vertexCount();
                    }
                }
            } finally {
                bbb.close();
            }*/
            //?} else {
            BufferBuilder bb = Tesselator.getInstance()
                    .begin(RenderPipelines.DEBUG_POINTS.getVertexFormatMode(),
                            RenderPipelines.DEBUG_POINTS.getVertexFormat());
            for (int i = 0; i < n; i++) {
                bb.addVertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
                        .setColor(argb[i])
                        .setLineWidth(pointSizePx);
            }
            MeshData mesh = bb.build();
            if (mesh == null) {
                return;
            }
            try (mesh) {
                if (pointsVbo != null) {
                    pointsVbo.close();
                }
                pointsVbo = RenderSystem.getDevice().createBuffer(() -> "visualizegate-pc-points",
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                pointsCount = mesh.drawState().vertexCount();
            }
            //?}
        } catch (Throwable t) {
            fail("uploadPoints", t);
        }
    }

    /**
     * マーカー類 (リンク線ボックス + ゲートキューブ + 現在地十字) の<b>展開済み QUADS 頂点</b>
     * (xyz の 3 連結 + argb・4 頂点=1 quad・{@code vertCount} は 4 の倍数) を VBO 化。 呼び出し側が
     * 太さを持つ 3D ジオメトリへ展開済み＝ここは点群と同じ QUADS 経路に載せるだけ (深度/色一貫)。
     */
    public static void uploadOverlay(float[] xyz, int[] argb, int vertCount) {
        overlayIndexCount = 0;
        if (failed || vertCount <= 0) {
            return;
        }
        try {
            ensurePipelines();
            //? if >=26.2 {
            /*ByteBufferBuilder bbb = new ByteBufferBuilder(Math.max(256, vertCount * 16));
            try {
                BufferBuilder bb = new BufferBuilder(bbb,
                        PrimitiveTopology.QUADS, quadPipeline.getVertexFormatBinding(0));
                for (int i = 0; i < vertCount; i++) {
                    bb.addVertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).setColor(argb[i]);
                }
                MeshData mesh = bb.build();
                if (mesh != null) {
                    try (mesh) {
                        if (overlayVbo != null) {
                            overlayVbo.close();
                        }
                        overlayVbo = RenderSystem.getDevice().createBuffer(() -> "visualizegate-pc-overlay",
                                GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                        overlayIndexCount = mesh.drawState().indexCount();
                    }
                }
            } finally {
                bbb.close();
            }*/
            //?} else {
            BufferBuilder bb = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, quadPipeline.getVertexFormat());
            for (int i = 0; i < vertCount; i++) {
                bb.addVertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).setColor(argb[i]);
            }
            MeshData mesh = bb.build();
            if (mesh == null) {
                return;
            }
            try (mesh) {
                if (overlayVbo != null) {
                    overlayVbo.close();
                }
                overlayVbo = RenderSystem.getDevice().createBuffer(() -> "visualizegate-pc-overlay",
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                overlayIndexCount = mesh.drawState().indexCount();
            }
            //?}
        } catch (Throwable t) {
            fail("uploadOverlay", t);
        }
    }


    /**
     * キャッシュ済み点/線バッファを FBO へ自前オービット投影＋GPU 深度で描く (回転/ズーム=これだけ)。 成功で true。
     * オービット中心は原点 (= 頂点バッファの重心)。 フル画面 PointCloudScreen はこの版を使う (中心オフセット 0)。
     */
    public static boolean render(int w, int h, float yaw, float pitch, float distance, int clearArgb) {
        return render(w, h, yaw, pitch, distance, 0f, 0f, 0f, clearArgb);
    }

    /**
     * ㊽A 中心オフセット ({@code cx,cy,cz}・頂点バッファ空間) を指定してオービット＝<b>任意点を画面中心</b>にできる。
     * ドックのライブ局所レーダーが「プレイヤー現在地」を中心に毎フレーム追従させるために使う (geometry は 3Hz・
     * カメラ中心は毎フレーム＝滑らかに寄る)。 フル画面は 6 引数版 (中心 0) ＝挙動不変。
     */
    public static boolean render(int w, int h, float yaw, float pitch, float distance,
            float cx, float cy, float cz, int clearArgb) {
        if (failed || w <= 0 || h <= 0) {
            return false;
        }
        if (pointsCount == 0 && overlayIndexCount == 0) {
            return false; // 描く物が無い
        }
        boolean projSet = false;
        try {
            ensurePipelines();
            ensureFbo(w, h);
            GpuDevice device = RenderSystem.getDevice();

            float aspect = (float) w / (float) h;
            //? if >=26.2 {
            /*// 26.2 は reverse-Z 深度 (DepthStencilState.DEFAULT=GREATER_OR_EQUAL・near→1/far→0)。 vanilla Projection と
            //   同じく setPerspective の near/far を入れ替えて reverse-Z 投影にする (深度クリアも 0.0=far へ)。 標準順だと
            //   全点が GREATER_OR_EQUAL 深度テストに落ちて FBO が空＝点群が出ない (26.1.x は LESS_OR_EQUAL=標準 Z)。
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), aspect, 8000f, 0.1f, true);*/
            //?} else {
            // 26.1.x は標準 Z (DEFAULT=LESS_OR_EQUAL・深度 [0,1]・near→0/far→1)。
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), aspect, 0.1f, 8000f, true);
            //?}
            // ㊽A view = T(0,0,-d)·Rx·Ry·T(-center)＝center を原点へ寄せてからオービット (center=0 で従来と同一)。
            Matrix4f view = new Matrix4f().translation(0f, 0f, -distance).rotateX(pitch).rotateY(yaw)
                    .translate(-cx, -cy, -cz);

            GpuBufferSlice projSlice = projBuf.getBuffer(proj);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(projSlice, ProjectionType.PERSPECTIVE);
            projSet = true;
            GpuBufferSlice dyn = RenderSystem.getDynamicUniforms()
                    .writeTransform(view, new Vector4f(1f, 1f, 1f, 1f), new Vector3f(), new Matrix4f());

            CommandEncoder enc = device.createCommandEncoder();
            //? if >=26.2 {
            /*// 26.2: クリア色は OptionalInt(ARGB) → Optional<Vector4fc>(RGBA float)。
            Vector4f clearRgba = new Vector4f(((clearArgb >> 16) & 0xFF) / 255f, ((clearArgb >> 8) & 0xFF) / 255f,
                    (clearArgb & 0xFF) / 255f, ((clearArgb >>> 24) & 0xFF) / 255f);
            // 深度クリアは reverse-Z の far=0.0 (GREATER_OR_EQUAL で全点が通る基準値)。 標準 Z の 1.0 だと全点が落ちる。
            try (RenderPass pass = enc.createRenderPass(() -> "visualizegate-pointcloud",
                    fbo.getColorTextureView(), Optional.of(clearRgba),
                    fbo.getDepthTextureView(), OptionalDouble.of(0.0))) {*/
            //?} else {
            try (RenderPass pass = enc.createRenderPass(() -> "visualizegate-pointcloud",
                    fbo.getColorTextureView(), OptionalInt.of(clearArgb),
                    fbo.getDepthTextureView(), OptionalDouble.of(1.0))) {
            //?}
                // ⑯ 点群= GL 点 (DEBUG_POINTS)・1 頂点/点・非索引 draw。
                // 26.1.x: DEBUG_POINTS/自前 quadPipeline は Projection/DynamicTransforms の 2 本宣言＝手動束縛で足りる。
                // >=26.2: DEBUG_POINTS/DEBUG_QUADS は BindGroupLayouts.GLOBALS(Globals)+MATRICES_PROJECTION
                //   (Projection/DynamicTransforms) を要求＝Globals も束ねないと bind group 生成失敗で draw が落ちる
                //   (→texbatch 転落＝点群が全く出ない)。 vanilla CloudRenderer 同様 bindDefaultUniforms で束ねる。
                if (pointsCount > 0 && pointsVbo != null) {
                    pass.setPipeline(RenderPipelines.DEBUG_POINTS);
                    //? if >=26.2 {
                    /*// bindDefaultUniforms = Projection(setProjectionMatrix 済みの自前オービット投影)/Fog/Globals/Lighting。
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", dyn);*/
                    //?} else {
                    pass.setUniform("Projection", projSlice);
                    pass.setUniform("DynamicTransforms", dyn);
                    //?}
                    //? if >=26.2 {
                    /*// draw 引数: (vertexCount, instanceCount, firstVertex, firstInstance)。
                    pass.setVertexBuffer(0, pointsVbo.slice());
                    pass.draw(pointsCount, 1, 0, 0);*/
                    //?} else {
                    pass.setVertexBuffer(0, pointsVbo);
                    pass.draw(0, pointsCount);
                    //?}
                }
                // マーカー類= QUADS + 共有 sequential quad index。
                if (overlayIndexCount > 0 && overlayVbo != null) {
                    pass.setPipeline(quadPipeline);
                    //? if >=26.2 {
                    /*// quadPipeline = DEBUG_QUADS も GLOBALS+MATRICES_PROJECTION 要求＝Globals 必須 (上の点群と同じ)。
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", dyn);*/
                    //?} else {
                    pass.setUniform("Projection", projSlice);
                    pass.setUniform("DynamicTransforms", dyn);
                    //?}
                    //? if >=26.2 {
                    /*// drawIndexed 引数: (indexCount, instanceCount, firstIndex, baseVertex, firstInstance)。
                    pass.setVertexBuffer(0, overlayVbo.slice());
                    RenderSystem.AutoStorageIndexBuffer idx =
                            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                    pass.setIndexBuffer(idx.getBuffer(overlayIndexCount), idx.type());
                    pass.drawIndexed(overlayIndexCount, 1, 0, 0, 0);*/
                    //?} else {
                    pass.setVertexBuffer(0, overlayVbo);
                    RenderSystem.AutoStorageIndexBuffer idx =
                            RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                    pass.setIndexBuffer(idx.getBuffer(overlayIndexCount), idx.type());
                    pass.drawIndexed(0, 0, overlayIndexCount, 1);
                    //?}
                }
            }
            return true;
        } catch (Throwable t) {
            fail("render", t);
            return false;
        } finally {
            if (projSet) {
                try {
                    RenderSystem.restoreProjectionMatrix();
                } catch (Throwable ignored) {
                    // 復元失敗は無視。
                }
            }
        }
    }

    private static void fail(String where, Throwable t) {
        failed = true;
        lastError = where + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
        VisualizeGateMod.LOGGER.warn(
                "[visualizegate] GPU3D point-cloud {} FAILED → texbatch fallback. cause:", where, t);
    }

    private static void ensureFbo(int w, int h) {
        if (projBuf == null) {
            projBuf = new ProjectionMatrixBuffer("visualizegate-pc");
        }
        if (fbo == null) {
            //? if >=26.2 {
            /*fbo = new TextureTarget("visualizegate-pointcloud", w, h, true, GpuFormat.RGBA8_UNORM);*/
            //?} else {
            fbo = new TextureTarget("visualizegate-pointcloud", w, h, true);
            //?}
            fboW = w;
            fboH = h;
        } else if (fboW != w || fboH != h) {
            fbo.resize(w, h);
            fboW = w;
            fboH = h;
        }
    }

    //? if <26.1 {
    /*// ── <26.1 (1.21.11) 専用: Path A 合成 (FBO color → 登録テクスチャ → Identifier-blit)。 ──────────────
    // 26.1 の g.blit(GpuTextureView,GpuSampler,…) が 1.21.11 GuiGraphics で private のため、 FBO color を public
    // AbstractTexture 派生に包んで TextureManager へ登録し、 全版 public な Identifier-blit で合成する (runtime probe 実証)。
    public static final Identifier COMPOSITE_ID =
            Identifier.fromNamespaceAndPath("visualizegate", "pointcloud_gpu_fbo");

    // FBO color を指す登録テクスチャ。 protected フィールド代入は subclass 内のみ可。 close() は no-op (FBO が資源所有)。
    private static final class FboColorTexture extends AbstractTexture {
        void point() {
            this.texture = fbo.getColorTexture();
            this.textureView = fbo.getColorTextureView();
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }

        @Override
        public void close() {
            // no-op: FBO(TextureTarget) が GPU 資源を所有。 登録テクスチャ破棄で FBO を閉じない。
        }
    }

    private static FboColorTexture compositeTex;

    // 現 FBO color を登録テクスチャへ毎フレーム向け直し (resize で view が変わるため)、 初回のみ register、 ID を返す。
    // render() 成功後に呼ぶこと。 fbo 未生成/失敗時は null (呼び出し側が texbatch へ)。
    public static Identifier ensureCompositeTexture() {
        if (failed || fbo == null) {
            return null;
        }
        if (compositeTex == null) {
            compositeTex = new FboColorTexture();
            Minecraft.getInstance().getTextureManager().register(COMPOSITE_ID, compositeTex);
        }
        compositeTex.point();
        return COMPOSITE_ID;
    }*/
    //?}
}
//?} else {
/*public final class PointCloudGpuRenderer {
    private PointCloudGpuRenderer() {
    }

    public static boolean usable() {
        return false; // 1.21.10 は GPU3D 未移植 (texbatch 据え置き・別フォローアップ。 1.21.11+ は移植済) → texbatch
    }

    public static String lastError() {
        return "legacy stub (GPU3D not ported to 1.21.10)";
    }
}*/
//?}
