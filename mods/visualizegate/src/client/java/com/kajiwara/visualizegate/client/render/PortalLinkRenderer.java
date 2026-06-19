package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.GateState;
import com.kajiwara.visualizegate.domain.GridPos;
import com.kajiwara.visualizegate.domain.LinkPrediction;
import com.kajiwara.visualizegate.domain.PortalCoordinateMapper;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.ui.GateColors;

//? if <26.2 {
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//?}
import com.mojang.blaze3d.vertex.PoseStack;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;*/
//?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ㉜ 機能2: リンク状態ベクターライン (水後ステージ・<b>Mixin 不使用</b>・バニラ {@code RenderTypes.lines()})＝
 * <b>点群画面と同じ 5 状態色</b>。
 *
 * <p>トリガ/source/予測/状態は {@link PortalGaze#resolvePlanning} で一元化 (カード/凡例と同一)。 状態色は
 * {@link GateColors#forState} で 5 状態に統一:
 * <ul>
 *   <li><b>正常/ズレ</b>: source → {@code project(接続先)} に 3D ライン (長さ＝ズレ量) ＋端マーカー。</li>
 *   <li><b>競合/未接続/片側</b>: source に状態色の短マーカー (長い線は引かない)。</li>
 * </ul>
 * 全描画は現次元座標に落とす。 OW↔Nether のみ。
 */
public final class PortalLinkRenderer {

    private static final PortalLinkRenderer INSTANCE = new PortalLinkRenderer();

    private static final float LINE_WIDTH = 2.5f;
    private static final double MARKER_HALF = 0.35;

    //? if <26.2 {
    private MultiBufferSource.BufferSource afterWaterBuffer;
    //?}

    private PortalLinkRenderer() {
    }

    public static void register() {
        //? if >=26.2 {
        /*LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> INSTANCE.onAfterWater(ctx));*/
        //?}
        //? if >=26.1 && <26.2 {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> INSTANCE.onAfterWater(ctx));
        //?}
        //? if <26.1 {
        /*WorldRenderEvents.END_MAIN.register(ctx -> INSTANCE.onAfterWater(ctx));*/
        //?}
    }

    private void onAfterWater(LevelRenderContext ctx) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;
            if (level == null || player == null) {
                return;
            }
            // ㉜ 注視/火打石の source・予測・5 状態を PortalGaze で一元解決 (カード/凡例と同一・色は5状態統一)。
            PortalGaze.Result r = PortalGaze.resolvePlanning(mc);
            if (r == null || r.status() == null) {
                return; // トリガ無し or OW↔Nether 以外
            }
            PortalDimension cur = r.current();
            PortalDimension other = r.other();
            LinkPrediction pred = r.prediction();
            GateState state = r.status().state();
            int color = GateColors.forState(state); // 5 状態色 (画面/カード/凡例と一本化)
            double srcX = r.sourceX();
            double srcY = r.sourceY();
            double srcZ = r.sourceZ();

            // ─── 描画準備 (camera-relative) ───
            CameraRenderState camState = ctx.levelState().cameraRenderState;
            if (camState == null || camState.pos == null) {
                return;
            }
            Vec3 camPos = camState.pos;
            // 最大表示距離 (設定・実効描画距離でクランプ) を超える source には描かない (水平距離・枠/ドームと同基準)。
            if (!GateVisualRange.withinCap(camPos, srcX, srcZ, GateVisualRange.cap(mc))) {
                return;
            }
            PoseStack matrices = ctx.poseStack();
            // 描き先 target: >=26.2 = submit collector、 26.1.x = 自前 immediate。
            //? if >=26.2 {
            /*SubmitNodeCollector target = ctx.submitNodeCollector();*/
            //?} else {
            if (afterWaterBuffer == null) {
                afterWaterBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            }
            MultiBufferSource.BufferSource target = afterWaterBuffer;
            //?}

            // 正常/ズレ＝接続先へライン (長さ＝ズレ量) ＋端マーカー。 競合/未接続/片側＝source に状態色マーカーのみ。
            if ((state == GateState.OK || state == GateState.OFFSET)
                    && pred != null && pred.matched().isPresent()) {
                GridPos endC = PortalCoordinateMapper.project(pred.matched().get().anchor(), other, cur,
                        level.getMinY(), level.getMaxY());
                double ex = endC.x() + 0.5;
                double ey = endC.y() + 0.5;
                double ez = endC.z() + 0.5;
                OverlayDraw.segment(target, matrices, camPos,
                        srcX, srcY, srcZ, ex, ey, ez, color, LINE_WIDTH);
                drawMarker(target, matrices, ex, ey, ez, camPos, color);
            } else {
                drawMarker(target, matrices, srcX, srcY, srcZ, camPos, color);
            }

            //? if <26.2 {
            target.endBatch();
            //?}
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] link render failed (continuing): {}", t.toString());
        }
    }

    // ── 描画ヘルパ ──────────────────────────────────────────────────────

    /** world pos に小さな箱マーカーを描く (共有ヘルパ経由・lines())。 描き先型のみ版別 (body の OverlayDraw.box は版別オーバーロードで解決)。 */
    //? if >=26.2 {
    /*private static void drawMarker(SubmitNodeCollector bs, PoseStack matrices,
            double wx, double wy, double wz, Vec3 camPos, int color) {*/
    //?} else {
    private static void drawMarker(MultiBufferSource.BufferSource bs, PoseStack matrices,
            double wx, double wy, double wz, Vec3 camPos, int color) {
    //?}
        AABB box = new AABB(wx - MARKER_HALF, wy - MARKER_HALF, wz - MARKER_HALF,
                wx + MARKER_HALF, wy + MARKER_HALF, wz + MARKER_HALF);
        OverlayDraw.box(bs, matrices, camPos, box, color, LINE_WIDTH);
    }
}
