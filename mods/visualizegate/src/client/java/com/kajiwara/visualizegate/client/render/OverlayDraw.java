package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.client.compat.ShaderCompat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?}
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
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
 * ＋ {@link ShapeRenderer} の<b>細い 3D ライン (深度テスト有り＝地形オクルージョン有り)</b> で描く。 シェーダ/非
 * シェーダで<b>線の作り方は同一</b>＝塗りクアッドや HUD 2D 射影には分岐しない (= バニラのブロック選択枠と同じ流儀)。
 *
 * <p><b>シェーダ (Iris) 時の経路だけが違う — 描き先バッファ</b>:
 * <ul>
 *   <li><b>非シェーダ</b>: 各レンダラが渡す<b>自前 immediate バッファ</b>へそのまま描く (従来どおり・<b>ピクセル不変</b>)。</li>
 *   <li><b>Iris シェーダ時 (&gt;=26.1)</b>: 同じ {@code lines()} を<b>レベルレンダラ自身のバッファ
 *       {@code ctx.bufferSource()} (Iris がラップする)</b> へ流し、 フラッシュをレベルに委ねる。 これにより
 *       Iris の {@code rendertype_lines} プログラムに乗り、 バニラのブロック選択枠と同様に<b>深度オクルージョン付き
 *       の本物ワイヤー</b>として出る。 自前 immediate を手動 endBatch するとこのキャプチャ外＝Iris に消されるため、
 *       描き先だけをレベルバッファへ切り替える。 レベルバッファは {@link #register()} の capture リスナが毎フレーム
 *       取得する (wire レンダラより前に発火するよう {@code VisualizeGateClient} で最初に register)。</li>
 * </ul>
 * シェーダ検出 {@link ShaderCompat} は<b>ソフト</b> (Iris 非搭載でも安全)。 旧世代 (1.21.10/1.21.11) は capture を
 * 行わず常に自前バッファ＝従来挙動 (非回帰)。
 */
public final class OverlayDraw {

    private OverlayDraw() {
    }

    /**
     * シェーダ時に {@code lines()} を流す「レベル (Iris ラップ) バッファ」。 capture リスナが毎フレーム設定する。
     * {@code null} = 未捕捉 / 旧世代 → 各レンダラの自前 immediate へ従来どおり描く。 描画スレッド単独で読み書き。
     */
    private static MultiBufferSource frameLevelBuffer;

    public static boolean shaderActive() {
        return ShaderCompat.isShaderPackInUse();
    }

    /**
     * 毎フレームのレベルバッファを capture する (シェーダ時のワイヤー描き先)。 wire レンダラより前に発火させたいので、
     * {@code VisualizeGateClient} で OverlayDraw を使う各レンダラより先に register する。 旧世代は capture せず no-op。
     */
    public static void register() {
        //? if >=26.1 {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> frameLevelBuffer = ctx.bufferSource());
        //?}
    }

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

    // ── lines() 頂点 (OmniChest WireHighlightRenderer.addLine の現物) ──
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
