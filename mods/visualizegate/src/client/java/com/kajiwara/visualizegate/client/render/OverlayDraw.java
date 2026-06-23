package com.kajiwara.visualizegate.client.render;

//? if <26.2 {
import com.kajiwara.visualizegate.client.compat.ShaderCompat;
//?}

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if >=26.1 && <26.2 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?}
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;*/
//?} else {
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
//?}
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.renderer.RenderType;*/
//?}
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 世界の線オーバーレイ共有描画ヘルパ (機能2/枠/ドーム/ホログラム金枠/back-calculate が共用)。
 *
 * <p><b>全 wire 要素を 1 経路に一本化</b>: バニラ {@code RenderTypes.lines()} (legacy は {@code RenderType.lines()})
 * の<b>細い 3D ライン (深度テスト有り＝地形オクルージョン有り)</b> で描く (= バニラのブロック選択枠と同じ流儀)。
 *
 * <p><b>描き先の版差</b>:
 * <ul>
 *   <li><b>&gt;=26.2 (submit/staged)</b>: バニラ描画パイプライン破壊的変更で {@code MultiBufferSource}/
 *       {@code ShapeRenderer} は削除。 各レンダラが渡す {@code SubmitNodeCollector} (= {@code ctx.submitNodeCollector()})
 *       へ {@code submitShapeOutline}/{@code submitCustomGeometry} で線を<b>サブミット</b>し、 描画は engine が
 *       正規の順序で行う。 これは<b>バニラのブロック選択枠と同一経路</b>＝<b>Iris がネイティブに捕捉</b>するため、
 *       シェーダ時も深度オクルージョン付きの本物ワイヤーが出る (旧 frameLevelBuffer ハックは不要)。</li>
 *   <li><b>26.1.x (immediate)</b>: 各レンダラが渡す<b>自前 immediate バッファ</b>へ {@link ShapeRenderer} 等で
 *       直接描く (従来どおり・<b>ピクセル不変</b>)。 Iris シェーダ時のみ描き先をレベルバッファ {@code ctx.bufferSource()}
 *       (Iris ラップ) へ切り替え、 自前 immediate が Iris に消されるのを避ける ({@link #register()} が毎フレーム capture)。</li>
 *   <li><b>旧世代 (1.21.10/1.21.11)</b>: capture を行わず常に自前バッファ＝従来挙動 (非回帰)。</li>
 * </ul>
 */
public final class OverlayDraw {

    private OverlayDraw() {
    }

    //? if <26.2 {
    /**
     * シェーダ時に {@code lines()} を流す「レベル (Iris ラップ) バッファ」。 capture リスナが毎フレーム設定する (&gt;=26.1)。
     * {@code null} = 未捕捉 / 旧世代 → 各レンダラの自前 immediate へ従来どおり描く。 描画スレッド単独で読み書き。
     */
    private static MultiBufferSource frameLevelBuffer;

    public static boolean shaderActive() {
        return ShaderCompat.isShaderPackInUse();
    }
    //?}

    /**
     * 26.1.x: 毎フレームのレベルバッファを capture する (シェーダ時のワイヤー描き先)。 wire レンダラより前に発火させたいので、
     * {@code VisualizeGateClient} で OverlayDraw を使う各レンダラより先に register する。 旧世代/&gt;=26.2 は no-op。
     * &gt;=26.2 は submit 経路が engine 経由＝Iris ネイティブ捕捉のため capture 不要。
     */
    public static void register() {
        //? if >=26.1 && <26.2 {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> frameLevelBuffer = ctx.bufferSource());
        //?}
    }

    //? if >=26.2 {
    /*// 任意の 3D 線分 (world 座標)。 バニラ lines() ＝深度テスト有りの細線を collector へサブミット。
    public static void segment(SubmitNodeCollector col, PoseStack matrices, Vec3 cam,
            double x1, double y1, double z1, double x2, double y2, double z2, int color, float widthPx) {
        col.submitCustomGeometry(matrices, RenderTypes.lines(),
                (pose, vc) -> lineSegment(vc, pose, cam, x1, y1, z1, x2, y2, z2, color, widthPx));
    }

    // AABB ワイヤフレーム。 submitShapeOutline ＝深度テスト有りの細線。 pose を -cam 平行移動し world 形状を渡す。
    public static void box(SubmitNodeCollector col, PoseStack matrices, Vec3 cam,
            AABB b, int color, float widthPx) {
        VoxelShape shape = Shapes.create(b);
        matrices.pushPose();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        col.submitShapeOutline(matrices, shape, RenderTypes.lines(), color, widthPx, false);
        matrices.popPose();
    }*/
    //?} else {
    /** シェーダ有効かつレベルバッファ捕捉済みならそちら (Iris が拾う)、 それ以外は自前 immediate (従来・非シェーダ不変)。 */
    private static MultiBufferSource targetBuffer(MultiBufferSource.BufferSource own) {
        if (shaderActive() && frameLevelBuffer != null) {
            return frameLevelBuffer;
        }
        return own;
    }

    private static VertexConsumer linesBuf(MultiBufferSource bs) {
        //? if >=1.21.11 {
        return bs.getBuffer(RenderTypes.lines());
        //?} else {
        /*return bs.getBuffer(RenderType.lines());*/
        //?}
    }

    /** 任意の 3D 線分 (world 座標)。 バニラ {@code lines()} ＝深度テスト有りの細線。 */
    public static void segment(MultiBufferSource.BufferSource bs, PoseStack matrices, Vec3 cam,
            double x1, double y1, double z1, double x2, double y2, double z2, int color, float widthPx) {
        lineSegment(linesBuf(targetBuffer(bs)), matrices.last(), cam, x1, y1, z1, x2, y2, z2, color, widthPx);
    }

    /** AABB ワイヤフレーム。 バニラ {@link ShapeRenderer#renderShape} の {@code lines()} ＝深度テスト有りの細線。 */
    public static void box(MultiBufferSource.BufferSource bs, PoseStack matrices, Vec3 cam,
            AABB b, int color, float widthPx) {
        VoxelShape shape = Shapes.create(b);
        VertexConsumer vc = linesBuf(targetBuffer(bs));
        //? if >=1.21.11 {
        ShapeRenderer.renderShape(matrices, vc, shape, -cam.x, -cam.y, -cam.z, color, widthPx);
        //?} else {
        /*ShapeRenderer.renderShape(matrices, vc, shape, -cam.x, -cam.y, -cam.z, color);*/
        //?}
    }
    //?}

    // ── lines() 頂点 (OmniChest WireHighlightRenderer.addLine の現物)。 両版共通 (VertexConsumer/PoseStack.Pose/Mth/Vec3 は全版同一)。 ──
    private static void lineSegment(VertexConsumer c, PoseStack.Pose pose, Vec3 cam,
            double x1, double y1, double z1, double x2, double y2, double z2, int color, float lineWidth) {
        float ax = (float) (x1 - cam.x);
        float ay = (float) (y1 - cam.y);
        float az = (float) (z1 - cam.z);
        float bx = (float) (x2 - cam.x);
        float by = (float) (y2 - cam.y);
        float bz = (float) (z2 - cam.z);
        float nx = bx - ax;
        float ny = by - ay;
        float nz = bz - az;
        float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        c.addVertex(pose, ax, ay, az).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
        c.addVertex(pose, bx, by, bz).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
    }
}
