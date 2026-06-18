package com.kajiwara.omnichest.client.render;

import com.kajiwara.omnichest.mixin.RenderTypeAccessor;
import com.mojang.blaze3d.pipeline.BlendFunction;
//? if >=26.1 {
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
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
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;*/
//?}
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
//?} else {
/*import net.minecraft.client.renderer.RenderType;*/
//?}
import net.minecraft.util.Mth;

/**
 * 検索ピンの「Minecraft ビーコン風ビーム」 を描く <b>shader-safe</b> な低レベル描画ユーティリティ。
 *
 * <p>
 * ピンの位置から上空へ伸びる半透明の縦ビームを、 内側コア柱 + 外側グロー柱の 2 重構造で描く。
 * 上にいくほど alpha が薄くなる縦グラデーション (= alpha fade) を頂点カラーで表現する。
 *
 * <p>
 * <b>shader / Sodium / Iris 互換方針</b> ({@link WireHighlightRenderer} と同じ思想):
 * <ul>
 *   <li>頂点フォーマットを {@code POSITION_COLOR} に縮退し、 {@code core/position_color} シェーダを使う。
 *       lineWidth 等の特殊 uniform に依存しないため、 ほぼ全 shader pack を素通りできる。</li>
 *   <li>blend は {@link BlendFunction#TRANSLUCENT} (= 加算ではなく標準アルファ合成)。
 *       Iris / Sodium の translucent pass と相性が良く、 日中でも白飛びしない。
 *       「additive feeling」 は明るいテーマ色 + 上方フェードで近似する。</li>
 *   <li>depth test は通常 (LEQUAL) のまま = 地形に自然に遮蔽される (= バニラビーコンと同じ挙動)。
 *       遠距離では上空のビーム先端が地形を越えて見えるため視認性は確保される。
 *       {@code depthWrite=false} で半透明同士の z-fighting を避ける。</li>
 *   <li>raw GL を叩かず VertexConsumer / RenderType / PoseStack のみ使用 = render state は RenderType が復元保証。</li>
 * </ul>
 *
 * <p>
 * 既存のピン / X-ray ボックス描画 ({@link ChestHighlighter}) には一切干渉せず、 補助演出として
 * 独立した RenderType を 1 本持つだけ。 OFF 時 (config) は本クラスが呼ばれない。
 */
public final class SearchBeaconRenderer {

    /** 外側グロー柱の幅 = コア幅 × この倍率。 */
    private static final float GLOW_WIDTH_MULT = 2.2f;
    /** 外側グローの alpha = コア alpha × この倍率 (= ぼんやりした裾野)。 */
    private static final float GLOW_ALPHA_MULT = 0.35f;

    private static volatile RenderType beamType;

    private SearchBeaconRenderer() {
    }

    /**
     * 1 本のビームを <b>immediate-mode</b> で {@code bufferSource} に描く。 座標はすべて
     * <b>camera-relative</b> (= matrices がカメラ原点)。 実際の GPU 反映は {@link #flush} で行う。
     *
     * <p>
     * 旧 submit 版 (= {@code SubmitNodeCollector}) と頂点 / RenderType / 色は完全に同一。 描画タイミング
     * だけを「水の描画後」 に移すための immediate-mode 版 (= {@code submitCustomGeometry} を
     * {@code bufferSource.getBuffer} + 直接頂点積みに置換しただけ)。
     *
     * @param centerX     ビーム中心の camera-relative X
     * @param centerZ     ビーム中心の camera-relative Z
     * @param baseY       ビーム下端の camera-relative Y (= チェスト天面)
     * @param topY        ビーム上端の camera-relative Y (= ワールド上空)
     * @param rgb         ビーム色 (0xRRGGBB、 テーマのハイライト色)
     * @param bottomAlpha 下端の不透明度 (0..1)
     * @param topAlpha    上端の不透明度 (0..1、 通常 0 付近にして上方フェード)
     * @param coreWidth   コア柱の幅 (ブロック)
     */
    //? if <26.2 {
    public static void drawBeamImmediate(MultiBufferSource bufferSource, PoseStack matrices,
            float centerX, float centerZ, float baseY, float topY,
            int rgb, float bottomAlpha, float topAlpha, float coreWidth) {
        if (topY <= baseY) return;
        if (bottomAlpha <= 0.001f && topAlpha <= 0.001f) return;

        float coreHalf = Math.max(0.01f, coreWidth) * 0.5f;
        float glowHalf = coreHalf * GLOW_WIDTH_MULT;

        int bottomCore = packColor(rgb, bottomAlpha);
        int topCore = packColor(rgb, topAlpha);
        int bottomGlow = packColor(rgb, bottomAlpha * GLOW_ALPHA_MULT);
        int topGlow = packColor(rgb, topAlpha * GLOW_ALPHA_MULT);

        VertexConsumer consumer = bufferSource.getBuffer(beamRenderType());
        PoseStack.Pose pose = matrices.last();
        // 外側グロー柱 (先に描いて内側コアを上に重ねる)。
        column(consumer, pose, centerX, centerZ, baseY, topY, glowHalf, bottomGlow, topGlow);
        // 内側コア柱。
        column(consumer, pose, centerX, centerZ, baseY, topY, coreHalf, bottomCore, topCore);
    }
    //?}

    //? if >=26.2 {
    /*// 26.2: 自前 position_color filled submit (旧 debugFilledBox 版) は Iris シェーダ下で捕捉されず消える
    //   (= rendertype_lines のワイヤー/ボックスのみ生き残るのと同類)。 そこでビームは <b>バニラの本物の
    //   ビーコンビーム</b> ({@link net.minecraft.client.renderer.blockentity.BeaconRenderer#submitBeaconBeam})
    //   を同じ SubmitNodeCollector へ submit する。 実ビーコンはシェーダ下でも描画されるため Iris-safe が
    //   保証され、 本機能が意図する「Minecraft ビーコン風ビーム」そのものになる。 色 (テーマ) / 高さ / 太さ
    //   (コア+グロー半径) / 不透明度 (= bottomAlpha を color の alpha バイトへ) を移植する。 深度遮蔽・
    //   テクスチャアニメはバニラ beacon 準拠 (= 旧 custom の上端 alpha フェードは無くなる)。 頂点組み立て
    //   (column/sideQuad/packColor) は <26.2 の drawBeamImmediate 専用となる。
    public static void submitBeam(SubmitNodeCollector queue, PoseStack matrices,
            float centerX, float centerZ, float baseY, float topY,
            int rgb, float bottomAlpha, float topAlpha, float coreWidth) {
        if (topY <= baseY) return;
        if (bottomAlpha <= 0.001f) return;

        // 色: テーマ RGB + bottomAlpha (= pulse/距離フェード合成済) を alpha バイトへ。
        int color = (Mth.clamp(Math.round(bottomAlpha * 255f), 0, 255) << 24) | (rgb & 0x00FFFFFF);
        // 高さ (ブロック): カメラ相対 baseY→topY の差。
        int height = Math.max(1, Math.round(topY - baseY));
        // 太さ: コア半径 = config 幅の半分、 グロー半径 = ×GLOW_WIDTH_MULT (旧 immediate と同比率)。
        float solidRadius = Math.max(0.05f, coreWidth * 0.5f);
        float glowRadius = solidRadius * GLOW_WIDTH_MULT;
        // バニラのテクスチャ・スクロール/回転用ゲーム時間 (= 実ビーコンと同じ自然なアニメ)。
        float gameTime = 0.0f;
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            gameTime = (float) (level.getGameTime() % 24000L);
        }

        matrices.pushPose();
        try {
            // ビーム中心 (centerX, centerZ) へ。 submitBeaconBeam が内部で translate(0.5,0,0.5) する分を相殺。
            matrices.translate(centerX - 0.5, baseY, centerZ - 0.5);
            net.minecraft.client.renderer.blockentity.BeaconRenderer.submitBeaconBeam(
                    matrices, queue,
                    net.minecraft.client.renderer.blockentity.BeaconRenderer.BEAM_LOCATION,
                    1.0f, gameTime, 0, height, color, solidRadius, glowRadius);
        } finally {
            matrices.popPose();
        }
    }*/
    //?}

    /**
     * 正方形断面の縦柱 (= 4 側面 quad) を描く。 各 quad は下端 {@code bottomColor}・
     * 上端 {@code topColor} の縦グラデーション。 cull off なので winding は問わない。
     */
    private static void column(VertexConsumer c, PoseStack.Pose pose,
            float cx, float cz, float y0, float y1, float half,
            int bottomColor, int topColor) {
        float xa = cx - half, xb = cx + half;
        float za = cz - half, zb = cz + half;
        // -Z 面
        sideQuad(c, pose, xa, za, xb, za, y0, y1, bottomColor, topColor);
        // +X 面
        sideQuad(c, pose, xb, za, xb, zb, y0, y1, bottomColor, topColor);
        // +Z 面
        sideQuad(c, pose, xb, zb, xa, zb, y0, y1, bottomColor, topColor);
        // -X 面
        sideQuad(c, pose, xa, zb, xa, za, y0, y1, bottomColor, topColor);
    }

    /** 1 枚の縦 quad: (x1,z1)〜(x2,z2) を底辺とし、 y0→y1 まで立ち上げる。 */
    private static void sideQuad(VertexConsumer c, PoseStack.Pose pose,
            float x1, float z1, float x2, float z2, float y0, float y1,
            int bottomColor, int topColor) {
        c.addVertex(pose, x1, y0, z1).setColor(bottomColor);
        c.addVertex(pose, x2, y0, z2).setColor(bottomColor);
        c.addVertex(pose, x2, y1, z2).setColor(topColor);
        c.addVertex(pose, x1, y1, z1).setColor(topColor);
    }

    private static int packColor(int rgb, float alphaF) {
        int a = Mth.clamp(Math.round(alphaF * 255f), 0, 255);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    // ════════════════════════════════════════════════════════════════════
    // RenderType (lazy + double-checked locking)
    // ════════════════════════════════════════════════════════════════════

    /** position_color + QUADS + TRANSLUCENT + 通常 depth test。 lineWidth 非依存で shader 安全。 */
    //? if <26.2 {
    private static RenderType beamRenderType() {
        RenderType cached = beamType;
        if (cached != null) return cached;
        synchronized (SearchBeaconRenderer.class) {
            if (beamType != null) return beamType;

            RenderPipeline.Snippet uniforms = RenderPipeline.builder()
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                    .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .buildSnippet();

            RenderPipeline pipeline = RenderPipeline.builder(uniforms)
                    .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            "omnichest", "pipeline/search_beacon_beam"))
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withCull(false)
                    // depth test は既定 (LEQUAL) のまま = 地形に遮蔽される自然なビーコン挙動。
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                    .withVertexFormat(
                            DefaultVertexFormat.POSITION_COLOR,
                            VertexFormat.Mode.QUADS)
                    .build();

            //? if >=1.21.11 {
            RenderSetup setup = RenderSetup.builder(pipeline).createRenderSetup();
            beamType = RenderTypeAccessor.omnichest$create("omnichest_search_beacon_beam", setup);
            //?} else {
            /*beamType = RenderTypeAccessor.omnichest$create("omnichest_search_beacon_beam", 1536, pipeline,
                    ((com.kajiwara.omnichest.mixin.CompositeStateBuilderAccessor) (Object)
                            RenderType.CompositeState.builder()).omnichest$createCompositeState(false));*/
            //?}
            return beamType;
        }
    }
    //?}
}
