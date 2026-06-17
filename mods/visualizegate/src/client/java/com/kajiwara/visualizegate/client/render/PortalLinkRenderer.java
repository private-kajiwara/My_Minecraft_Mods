package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.GateState;
import com.kajiwara.visualizegate.domain.GridPos;
import com.kajiwara.visualizegate.domain.LinkPrediction;
import com.kajiwara.visualizegate.domain.PortalCoordinateMapper;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.ui.GateColors;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
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
import net.minecraft.client.renderer.MultiBufferSource;
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

    private MultiBufferSource.BufferSource afterWaterBuffer;

    private PortalLinkRenderer() {
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
            PoseStack matrices = ctx.poseStack();
            if (afterWaterBuffer == null) {
                afterWaterBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            }
            MultiBufferSource.BufferSource bufferSource = afterWaterBuffer;

            // 正常/ズレ＝接続先へライン (長さ＝ズレ量) ＋端マーカー。 競合/未接続/片側＝source に状態色マーカーのみ。
            if ((state == GateState.OK || state == GateState.OFFSET)
                    && pred != null && pred.matched().isPresent()) {
                GridPos endC = PortalCoordinateMapper.project(pred.matched().get().anchor(), other, cur,
                        level.getMinY(), level.getMaxY());
                double ex = endC.x() + 0.5;
                double ey = endC.y() + 0.5;
                double ez = endC.z() + 0.5;
                OverlayDraw.segment(bufferSource, matrices, camPos,
                        srcX, srcY, srcZ, ex, ey, ez, color, LINE_WIDTH);
                drawMarker(bufferSource, matrices, ex, ey, ez, camPos, color);
            } else {
                drawMarker(bufferSource, matrices, srcX, srcY, srcZ, camPos, color);
            }

            bufferSource.endBatch();
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] link render failed (continuing): {}", t.toString());
        }
    }

    // ── 描画ヘルパ ──────────────────────────────────────────────────────

    /** world pos に小さな箱マーカーを描く (共有ヘルパ経由・lines()・Iris 時のみ描き先をレベルバッファへ)。 */
    private static void drawMarker(MultiBufferSource.BufferSource bs, PoseStack matrices,
            double wx, double wy, double wz, Vec3 camPos, int color) {
        AABB box = new AABB(wx - MARKER_HALF, wy - MARKER_HALF, wz - MARKER_HALF,
                wx + MARKER_HALF, wy + MARKER_HALF, wz + MARKER_HALF);
        OverlayDraw.box(bs, matrices, camPos, box, color, LINE_WIDTH);
    }
}
