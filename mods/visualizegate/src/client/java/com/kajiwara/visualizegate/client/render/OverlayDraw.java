package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.client.compat.ShaderCompat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
 * 世界の線オーバーレイ共有描画ヘルパ (機能2/枠/ドーム/ホログラム金枠が共用)。
 *
 * <p><b>分岐を 1 箇所に集約</b>:
 * <ul>
 *   <li><b>非シェーダ (既定)</b>: バニラ {@code RenderTypes.lines()} ＋ {@link ShapeRenderer} ＝<b>従来どおり・挙動不変</b>。</li>
 *   <li><b>Iris シェーダ有効時</b>: Iris は {@code rendertype_lines} 経路を扱えず線が消える/化けるため、 線を
 *       <b>camera-facing の細クアッド</b>にして、 shader-safe な {@code core/position_color} 経路＝バニラ
 *       {@code debugFilledBox()} (POSITION_COLOR/QUADS・深度テスト LEQUAL) で描く。 <b>カスタム RenderType /
 *       accessor Mixin 不要</b> (OmniChest は accessor Mixin で自前 quad 型を作るが、 こちらはバニラ型で足りる)。</li>
 * </ul>
 * 非シェーダ時の出力は従来の各レンダラと同一 API・同一引数なので<b>ピクセル不変</b>。 シェーダ時のみ細クアッドに
 * なるのは許容 (OmniChest 同様の best-effort)。 検出は {@link ShaderCompat} (ソフト・Iris 非搭載でも安全)。
 */
public final class OverlayDraw {

    /** 画素線幅 → 世界クアッド幅の換算 (OmniChest と同じ係数)。 */
    private static final double WIDTH_TO_WORLD = 0.01;

    private OverlayDraw() {
    }

    public static boolean shaderActive() {
        return ShaderCompat.isShaderPackInUse();
    }

    private static VertexConsumer linesBuf(MultiBufferSource.BufferSource bs) {
        //? if >=1.21.11 {
        return bs.getBuffer(RenderTypes.lines());
        //?} else {
        /*return bs.getBuffer(RenderType.lines());*/
        //?}
    }

    private static VertexConsumer quadBuf(MultiBufferSource.BufferSource bs) {
        //? if >=1.21.11 {
        return bs.getBuffer(RenderTypes.debugFilledBox());
        //?} else {
        /*return bs.getBuffer(RenderType.debugFilledBox());*/
        //?}
    }

    /** 任意の 3D 線分 (world 座標)。 非シェーダ=lines / シェーダ= (>=26.1) HUD 2D 射影 / (旧世代) 細クアッド。 */
    public static void segment(MultiBufferSource.BufferSource bs, PoseStack matrices, Vec3 cam,
            double x1, double y1, double z1, double x2, double y2, double z2, int color, float widthPx) {
        PoseStack.Pose pose = matrices.last();
        if (shaderActive()) {
            // >=26.1: ワールドに描くと Iris に消されるため後段 HUD パスへ 2D 射影して描く (絶対表示)。 capture
            //   未了時のみ従来 quad へフォールバック。 旧世代は projection Matrix4f を取れず HUD 経路無し＝常に quad。
            //? if >=26.1 {
            if (ShaderWireOverlay.addWorldSegment(pose, cam, x1, y1, z1, x2, y2, z2, color, widthPx)) {
                return;
            }
            //?}
            quadSegment(quadBuf(bs), pose, cam, x1, y1, z1, x2, y2, z2, color, widthPx);
        } else {
            lineSegment(linesBuf(bs), pose, cam, x1, y1, z1, x2, y2, z2, color, widthPx);
        }
    }

    /** AABB ワイヤフレーム。 非シェーダ=ShapeRenderer / シェーダ= (>=26.1) HUD 2D 射影 / (旧世代) 12 辺クアッド。 */
    public static void box(MultiBufferSource.BufferSource bs, PoseStack matrices, Vec3 cam,
            AABB b, int color, float widthPx) {
        if (shaderActive()) {
            // >=26.1: 12 辺を後段 HUD パスへ 2D 射影 (絶対表示)。 capture 未了時のみ従来 quad。 旧世代は常に quad。
            //? if >=26.1 {
            if (ShaderWireOverlay.addWorldBox(matrices.last(), cam, b, color, widthPx)) {
                return;
            }
            //?}
            VertexConsumer vc = quadBuf(bs);
            PoseStack.Pose pose = matrices.last();
            boxEdges(vc, pose, cam, b, color, widthPx);
        } else {
            VoxelShape shape = Shapes.create(b);
            VertexConsumer vc = linesBuf(bs);
            //? if >=1.21.11 {
            ShapeRenderer.renderShape(matrices, vc, shape, -cam.x, -cam.y, -cam.z, color, widthPx);
            //?} else {
            /*ShapeRenderer.renderShape(matrices, vc, shape, -cam.x, -cam.y, -cam.z, color);*/
            //?}
        }
    }

    // ── 非シェーダ: lines() 頂点 (OmniChest WireHighlightRenderer.addLine の現物) ──
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

    // ── シェーダ: 線分を camera-facing の細クアッド (POSITION_COLOR/QUADS) ──
    private static void quadSegment(VertexConsumer c, PoseStack.Pose pose, Vec3 cam,
            double x1, double y1, double z1, double x2, double y2, double z2, int color, float widthPx) {
        double ax = x1 - cam.x;
        double ay = y1 - cam.y;
        double az = z1 - cam.z;
        double bx = x2 - cam.x;
        double by = y2 - cam.y;
        double bz = z2 - cam.z;
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        // カメラは cam-relative 空間の原点 → 中点ベクトルが視線。 perp = cross(dir, view)。
        double mx = (ax + bx) * 0.5;
        double my = (ay + by) * 0.5;
        double mz = (az + bz) * 0.5;
        double px = dy * mz - dz * my;
        double py = dz * mx - dx * mz;
        double pz = dx * my - dy * mx;
        double plen = Math.sqrt(px * px + py * py + pz * pz);
        if (plen < 1e-6) {
            return; // 線分を真正面から見た退化ケース (極小・省略)。
        }
        double h = Math.max(0.01, widthPx * WIDTH_TO_WORLD) * 0.5;
        px = px / plen * h;
        py = py / plen * h;
        pz = pz / plen * h;
        vertex(c, pose, ax - px, ay - py, az - pz, color);
        vertex(c, pose, ax + px, ay + py, az + pz, color);
        vertex(c, pose, bx + px, by + py, bz + pz, color);
        vertex(c, pose, bx - px, by - py, bz - pz, color);
    }

    private static void vertex(VertexConsumer c, PoseStack.Pose pose, double x, double y, double z, int color) {
        c.addVertex(pose, (float) x, (float) y, (float) z).setColor(color);
    }

    // ── シェーダ: AABB の 12 辺をクアッドで ──
    private static void boxEdges(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, AABB b, int color, float w) {
        double x0 = b.minX;
        double y0 = b.minY;
        double z0 = b.minZ;
        double x1 = b.maxX;
        double y1 = b.maxY;
        double z1 = b.maxZ;
        // 下面 4
        quadSegment(vc, pose, cam, x0, y0, z0, x1, y0, z0, color, w);
        quadSegment(vc, pose, cam, x0, y0, z1, x1, y0, z1, color, w);
        quadSegment(vc, pose, cam, x0, y0, z0, x0, y0, z1, color, w);
        quadSegment(vc, pose, cam, x1, y0, z0, x1, y0, z1, color, w);
        // 上面 4
        quadSegment(vc, pose, cam, x0, y1, z0, x1, y1, z0, color, w);
        quadSegment(vc, pose, cam, x0, y1, z1, x1, y1, z1, color, w);
        quadSegment(vc, pose, cam, x0, y1, z0, x0, y1, z1, color, w);
        quadSegment(vc, pose, cam, x1, y1, z0, x1, y1, z1, color, w);
        // 縦 4
        quadSegment(vc, pose, cam, x0, y0, z0, x0, y1, z0, color, w);
        quadSegment(vc, pose, cam, x1, y0, z0, x1, y1, z0, color, w);
        quadSegment(vc, pose, cam, x0, y0, z1, x0, y1, z1, color, w);
        quadSegment(vc, pose, cam, x1, y0, z1, x1, y1, z1, color, w);
    }
}
