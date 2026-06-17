package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.DomainPortal;
import com.kajiwara.visualizegate.domain.GridPos;
import com.kajiwara.visualizegate.domain.LinkPrediction;
import com.kajiwara.visualizegate.domain.PortalLinkResolver;
import com.kajiwara.visualizegate.domain.PredictedLinkState;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.ui.GateColors;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.renderer.RenderType;*/
//?}
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 機能1: ホログラム枠 v1 (「ズレ無し設置位置」を世界に金枠で示す・<b>Mixin 不使用</b>)。
 *
 * <p>情報カードが言う「ズレ無し設置位置」を、 {@link PortalLinkResolver#ghostBuildPosition} で算出した現次元
 * 座標に、 <b>matched ポータルの axis/サイズに合わせた</b>アウトラインのゲート枠 (黒曜石リング＋内部面の輪郭) で
 * 描く。 トリガは機能2/カードと同じ ({@link PortalGaze#resolvePlanning} ＝注視 or 火打石所持)。 <b>状態 LINKED の
 * ときだけ</b>描く (matched が在る＝実ズレがある＝意味がある; WILL_CREATE/UNKNOWN は枠を出さない)。
 *
 * <p>描画は {@link PortalBoxRenderer} と同じ<b>水後ステージ</b>＋バニラ {@code RenderTypes.lines()}/
 * {@link ShapeRenderer} を流用 (= カスタム RenderType / accessor mixin 不要)。 半透明の面塗り＋★D (legacy の
 * translucent/no-depth) は v2 へ繰延。 色は金 {@link GateColors#ACCENT} (凡例「金枠=ズレ無し設置位置」と一致)。
 */
public final class HologramFrameRenderer {

    private static final HologramFrameRenderer INSTANCE = new HologramFrameRenderer();

    /** 枠の色 (ARGB): 金 (凡例と一致)。 */
    private static final int FRAME_ARGB = GateColors.ACCENT;
    private static final float LINE_WIDTH = 2.5f;
    // 記憶が引けない異常時のフォールバック寸法 (最小ポータル内部 2×3×1・X 幅)。
    private static final double DEF_W = 2.0;
    private static final double DEF_H = 3.0;
    private static final double DEF_T = 1.0;

    private MultiBufferSource.BufferSource afterWaterBuffer;

    private HologramFrameRenderer() {
    }

    public static void register() {
        //? if >=26.1 {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> INSTANCE.onAfterWater(ctx));
        //?} else {
        /*WorldRenderEvents.END_MAIN.register(ctx -> INSTANCE.onAfterWater(ctx));*/
        //?}
    }

    private void onAfterWater(LevelRenderContext ctx) {
        try {
            if (!GateMenuState.isHologramEnabled())
                return;
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null)
                return;

            // 機能2/カードと同じ予測 (注視 or 火打石)。 LINKED のときだけ枠を出す。
            PortalGaze.Result r = PortalGaze.resolvePlanning(mc);
            if (r == null) {
                return;
            }
            LinkPrediction pred = r.prediction();
            if (pred.state() != PredictedLinkState.LINKED || pred.matched().isEmpty()) {
                return;
            }
            DomainPortal matched = pred.matched().get();

            // ズレ無し設置位置 (別dim既知ポータルを現dimへ射影) ＋ matched の寸法。
            GridPos g = PortalLinkResolver.ghostBuildPosition(matched, r.current(),
                    level.getMinY(), level.getMaxY());
            PortalMemory.FrameExtents ext = PortalMemory.get()
                    .frameExtentsAt(r.other(), matched.anchor())
                    .orElse(new PortalMemory.FrameExtents(DEF_W, DEF_H, DEF_T));

            double gx = g.x();
            double gy = g.y();
            double gz = g.z();
            double dx = ext.dx();
            double dy = ext.dy();
            double dz = ext.dz();
            // 幅軸 = 水平スパンの大きい方 (もう一方が厚み ~1)。 リングは幅軸＋Y を 1 外側へ。
            boolean axisX = dx > dz;
            AABB interior = new AABB(gx, gy, gz, gx + dx, gy + dy, gz + dz);
            AABB ring = new AABB(
                    axisX ? gx - 1 : gx, gy - 1, axisX ? gz : gz - 1,
                    axisX ? gx + dx + 1 : gx + dx, gy + dy + 1, axisX ? gz + dz : gz + dz + 1);

            CameraRenderState camState = ctx.levelState().cameraRenderState;
            if (camState == null || camState.pos == null)
                return;
            Vec3 camPos = camState.pos;
            PoseStack matrices = ctx.poseStack();
            PoseStack.Pose pose = matrices.last();

            if (afterWaterBuffer == null) {
                afterWaterBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            }
            MultiBufferSource.BufferSource bufferSource = afterWaterBuffer;

            // v2: ポータル内部面の半透明な紫クアッド。 バニラ debugFilledBox = TRANSLUCENT 混合 ＋ 深度テスト
            //     LEQUAL (書込み無し) ＝地形に正しく遮蔽される (壁裏で透けっぱなしにしない)・<b>Mixin 不要</b>。
            //     金枠より先に描き、 線を面の上へ。 型名は lines() と同じ版分岐 (RenderTypes/RenderType)。
            //? if >=1.21.11 {
            VertexConsumer fill = bufferSource.getBuffer(RenderTypes.debugFilledBox());
            //?} else {
            /*VertexConsumer fill = bufferSource.getBuffer(RenderType.debugFilledBox());*/
            //?}
            drawInteriorFace(fill, pose, gx, gy, gz, dx, dy, dz, axisX, camPos);

            // 金アウトライン (v1・線は面の上)。 共有ヘルパ (lines()・Iris 時のみ描き先をレベルバッファへ)。
            OverlayDraw.box(bufferSource, matrices, camPos, ring, FRAME_ARGB, LINE_WIDTH);     // 黒曜石リング
            OverlayDraw.box(bufferSource, matrices, camPos, interior, FRAME_ARGB, LINE_WIDTH); // 内部面の輪郭

            bufferSource.endBatch();
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] hologram render failed (continuing): {}", t.toString());
        }
    }

    /**
     * ポータル内部面の半透明クアッド (POSITION_COLOR/QUADS)。 厚み軸の中央に幅×高さの矩形を、
     * cull 対策で表裏 2 枚 (両 winding) 描く。 座標はカメラ相対。
     */
    private static void drawInteriorFace(VertexConsumer fill, PoseStack.Pose pose,
            double gx, double gy, double gz, double dx, double dy, double dz, boolean axisX, Vec3 cam) {
        double yb = gy;
        double yt = gy + dy;
        double ax;
        double az;
        double bx;
        double bz;
        if (axisX) { // 幅は X、 厚みは Z → Z 中央に X×Y 矩形
            double zc = gz + dz * 0.5;
            ax = gx;
            az = zc;
            bx = gx + dx;
            bz = zc;
        } else { // 幅は Z、 厚みは X → X 中央に Z×Y 矩形
            double xc = gx + dx * 0.5;
            ax = xc;
            az = gz;
            bx = xc;
            bz = gz + dz;
        }
        // 表 (a-bottom, b-bottom, b-top, a-top) と裏 (逆順) ＝両面可視。
        quad(fill, pose, cam, ax, yb, az, bx, yb, bz, bx, yt, bz, ax, yt, az);
        quad(fill, pose, cam, ax, yt, az, bx, yt, bz, bx, yb, bz, ax, yb, az);
    }

    private static void quad(VertexConsumer c, PoseStack.Pose pose, Vec3 cam,
            double x0, double y0, double z0, double x1, double y1, double z1,
            double x2, double y2, double z2, double x3, double y3, double z3) {
        vertex(c, pose, cam, x0, y0, z0);
        vertex(c, pose, cam, x1, y1, z1);
        vertex(c, pose, cam, x2, y2, z2);
        vertex(c, pose, cam, x3, y3, z3);
    }

    private static void vertex(VertexConsumer c, PoseStack.Pose pose, Vec3 cam,
            double x, double y, double z) {
        c.addVertex(pose, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z))
                .setColor(GateColors.HOLO_FILL);
    }

}
