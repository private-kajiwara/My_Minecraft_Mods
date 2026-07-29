package com.kajiwara.hyperslice.entity;

import java.util.List;

import com.kajiwara.hyperslice.core.HyperEntityType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * 4 次元エンティティの断面を、 ワールド空間に実体のある球として描く。
 *
 * <h2>アルファブレンドを使わない</h2>
 * 断面半径は {@code dw} が端に近づくと 0 に<b>収束する</b>ので、 消滅はスケールだけで
 * 表現できる。 半透明を混ぜると「透けた物体」に見えて「4 次元物体の断面」という読みが
 * 壊れるため、 <b>色のアルファは常に 255 固定</b>。 交差していないもの (半径 0) は
 * そもそもサーバから送られてこない。
 *
 * <h2>描画経路</h2>
 * {@code LevelRenderEvents.AFTER_SOLID_FEATURES} で {@code ctx.bufferSource()} に
 * 直接頂点を積む (VisualizeGate の HologramFrameRenderer と同じ経路)。
 * {@code RenderTypes.debugFilledBox()} は POSITION_COLOR / QUADS でテクスチャ不要
 * (独自テクスチャを作らない方針に適合)。
 *
 * <p>この RenderType はカリングが有効なので、 各面を<b>両 winding</b> で出している。
 * こうするとカリングが外向きの面だけを残すため、 winding 規約を取り違えても
 * 「見えない」事故が起きない。 球は凸なので、 残った外向き面同士は重ならない。
 */
public final class HyperEntityRenderer {

    // ─── 描画の調整用定数 (見た目の分割数。 値の意味は HyperEntityType 側に集約) ───

    /** 緯度方向の分割数。 */
    private static final int RINGS = 12;

    /** 経度方向の分割数。 */
    private static final int SEGMENTS = 16;

    // ─── 頂点ライティング ───────────────────────────────────────
    //   RenderType の shader (core/position_color) には照明が無いため、 単色のままだと
    //   球がどの角度から見ても「平面的な円盤」に見えてしまう。 複数体を同時に見ると
    //   平面性は特に目立つ。 そこで Lambert を頂点色に焼き込んで立体感を出す。
    //
    //   UV 球は「中心からの正規化オフセット」がそのまま法線なので、 追加の計算は要らない。
    //   これは陰影であってアルファではない (原則2: 消滅はスケールのみで表現する)。

    /** 環境光の割合 (陰になる面もこの分は明るい)。 */
    private static final double AMBIENT = 0.45;

    /** 拡散光の割合。 {@code AMBIENT + DIFFUSE} が最大の明るさ。 */
    private static final double DIFFUSE = 0.55;

    /** 光源方向 (正規化済み)。 やや上・手前から当てる。 */
    private static final double LIGHT_X;
    private static final double LIGHT_Y;
    private static final double LIGHT_Z;

    static {
        double lx = 0.3;
        double ly = 0.9;
        double lz = 0.25;
        double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
        LIGHT_X = lx / len;
        LIGHT_Y = ly / len;
        LIGHT_Z = lz / len;
    }

    // ──────────────────────────────────────────────────────────

    private HyperEntityRenderer() {
    }

    /** {@code HyperSliceClient} から 1 回だけ呼ぶ。 */
    public static void register() {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(HyperEntityRenderer::onRender);
    }

    private static void onRender(LevelRenderContext ctx) {
        List<ClientHyperEntities.View> views = ClientHyperEntities.snapshot();
        if (views.isEmpty()) {
            return;
        }
        double planeW = ClientHyperEntities.planeW();
        if (Double.isNaN(planeW)) {
            return;   // HyperSlice の外では 4 次元エンティティは存在しない
        }

        CameraRenderState cam = ctx.levelState().cameraRenderState;
        if (cam == null || cam.pos == null) {
            return;
        }
        Vec3 camPos = cam.pos;

        MultiBufferSource.BufferSource buffers = ctx.bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderTypes.debugFilledBox());
        PoseStack matrices = ctx.poseStack();

        boolean drewAny = false;
        for (ClientHyperEntities.View v : views) {
            double radius = v.radius(planeW);
            if (radius < HyperEntityType.MIN_RENDER_RADIUS) {
                continue;   // 極小の点はチラつくだけなので描かない
            }
            drawSphere(vc, matrices.last(),
                    v.x() - camPos.x, v.y() - camPos.y, v.z() - camPos.z,
                    radius, v.type().argb());
            drewAny = true;
        }

        if (drewAny) {
            buffers.endBatch(RenderTypes.debugFilledBox());
        }
    }

    /**
     * UV 球を QUADS で積む。 座標は<b>カメラ相対</b>。
     *
     * <p>極では四角形が縮退して三角形になるが、 QUADS のまま同じ頂点を 2 回出せば
     * そのまま扱える (専用の三角形パスを持たない)。
     */
    private static void drawSphere(VertexConsumer vc, PoseStack.Pose pose,
                                   double cx, double cy, double cz,
                                   double radius, int argb) {
        int a = 0xFF;                       // 常に不透明 (断面はスケールのみで表現する)
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        // 四隅の単位球上の位置 (= そのまま法線)。 [0]=x [1]=y [2]=z
        double[] nA = new double[3];
        double[] nB = new double[3];
        double[] nC = new double[3];
        double[] nD = new double[3];

        for (int i = 0; i < RINGS; i++) {
            double t0 = Math.PI * i / RINGS;
            double t1 = Math.PI * (i + 1) / RINGS;
            double y0 = Math.cos(t0);
            double y1 = Math.cos(t1);
            double s0 = Math.sin(t0);
            double s1 = Math.sin(t1);

            for (int j = 0; j < SEGMENTS; j++) {
                double p0 = 2.0 * Math.PI * j / SEGMENTS;
                double p1 = 2.0 * Math.PI * (j + 1) / SEGMENTS;
                double c0 = Math.cos(p0);
                double n0 = Math.sin(p0);
                double c1 = Math.cos(p1);
                double n1 = Math.sin(p1);

                set(nA, s0 * c0, y0, s0 * n0);
                set(nB, s1 * c0, y1, s1 * n0);
                set(nC, s1 * c1, y1, s1 * n1);
                set(nD, s0 * c1, y0, s0 * n1);

                // 表 winding
                emit(vc, pose, cx, cy, cz, radius, nA, r, g, b, a);
                emit(vc, pose, cx, cy, cz, radius, nB, r, g, b, a);
                emit(vc, pose, cx, cy, cz, radius, nC, r, g, b, a);
                emit(vc, pose, cx, cy, cz, radius, nD, r, g, b, a);
                // 裏 winding (カリングが外向きだけを残すので、 規約を取り違えても消えない)
                emit(vc, pose, cx, cy, cz, radius, nD, r, g, b, a);
                emit(vc, pose, cx, cy, cz, radius, nC, r, g, b, a);
                emit(vc, pose, cx, cy, cz, radius, nB, r, g, b, a);
                emit(vc, pose, cx, cy, cz, radius, nA, r, g, b, a);
            }
        }
    }

    private static void set(double[] v, double x, double y, double z) {
        v[0] = x;
        v[1] = y;
        v[2] = z;
    }

    /**
     * 単位球上の法線 {@code n} の頂点を 1 つ積む。
     *
     * <p>位置は {@code 中心 + 半径 * n}、 色は基本色に Lambert 陰影を掛けたもの。
     * <b>アルファは引数のまま (常に 255)</b> — 陰影は明度だけで、 透明度には触れない。
     */
    private static void emit(VertexConsumer vc, PoseStack.Pose pose,
                             double cx, double cy, double cz, double radius,
                             double[] n, int r, int g, int b, int a) {
        double dot = n[0] * LIGHT_X + n[1] * LIGHT_Y + n[2] * LIGHT_Z;
        double shade = AMBIENT + DIFFUSE * Math.max(0.0, dot);

        vc.addVertex(pose,
                        (float) (cx + radius * n[0]),
                        (float) (cy + radius * n[1]),
                        (float) (cz + radius * n[2]))
                .setColor(shaded(r, shade), shaded(g, shade), shaded(b, shade), a);
    }

    private static int shaded(int channel, double shade) {
        int v = (int) Math.round(channel * shade);
        return Math.max(0, Math.min(255, v));
    }
}
