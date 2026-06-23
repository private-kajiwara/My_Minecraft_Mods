package com.kajiwara.visualizegate.client.render;

import java.util.List;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.GateConflictAnalyzer;
import com.kajiwara.visualizegate.domain.GateNode;
import com.kajiwara.visualizegate.domain.GridPos;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.domain.PortalCoordinateMapper;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.state.VgOverlayState;
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
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;*/
//?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ㉟ `/vg visualize`: <b>全ゲートの関係性ワイヤーフレーム</b> (水後ステージ・<b>Mixin 不使用</b>・バニラ
 * {@code RenderTypes.lines()} 経由 {@link OverlayDraw})。
 *
 * <p>{@link PortalMemory#gateNodes()} の全ゲートを {@link GateConflictAnalyzer} の<b>5 状態</b>で色分けし、
 * 各ゲートに枠 (現次元はそのまま・別次元は {@link PortalCoordinateMapper#project} で現次元へ射影した「写し」)、
 * {@link PortalMemory#confirmedLinks()} の確定ペアにリンク線を引く。 色は {@link GateColors#forStateOrdinal}
 * (点群画面/カード/凡例と一本化)。
 *
 * <p><b>性能予算</b>: カメラから {@value #MAX_DIST} ブロックを超えるゲートは描かない (距離カリング)。 距離の
 * 後半で <b>距離フェード</b> (alpha 減衰)。 1 フレームの描画上限 {@value #FRAME_CAP} 枠 (過密保護)。 既定 OFF
 * ({@link VgOverlayState})・F1(hideGui)/F3 で非表示・入力非干渉。 OW↔Nether のみ。 {@code /vg clean} でオフ。
 */
public final class GateGraphRenderer {

    private static final GateGraphRenderer INSTANCE = new GateGraphRenderer();

    private static final float FRAME_WIDTH = 2.5f;
    private static final float LINK_WIDTH = 2.0f;
    private static final double MAX_DIST = 512.0;      // 距離カリング上限 (ブロック)
    private static final double FADE_START = 320.0;    // ここから alpha フェード開始
    private static final int FRAME_CAP = 256;          // 1 フレームの最大描画ゲート数 (過密保護)
    // フォールバック枠寸法 (記憶に extents が無い/別次元射影時の最小ポータル内部 2×3×1)。
    private static final double DEF_W = 2.0;
    private static final double DEF_H = 3.0;
    private static final double DEF_T = 1.0;

    // バニラ標準の次元境界 (Y クランプ用・PortalLinkRenderer / PointCloudAnalysis と同一前提)。
    private static final int OW_MIN_Y = -64;
    private static final int OW_MAX_Y = 319;
    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;

    //? if <26.2 {
    private MultiBufferSource.BufferSource afterWaterBuffer;
    //?}

    private GateGraphRenderer() {
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
            if (!VgOverlayState.isVisualize()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                return;
            }
            //? if >=26.2 {
            /*if (mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen()) {*/
            //?} else {
            if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            //?}
                return; // F1 / F3 尊重
            }
            PortalDimension cur = PortalMemory.dimOf(level.dimension().identifier().toString());
            if (cur != PortalDimension.OVERWORLD && cur != PortalDimension.NETHER) {
                return; // OW↔Nether のみ
            }

            List<GateNode> nodes = PortalMemory.get().gateNodes();
            if (nodes.isEmpty()) {
                return;
            }
            GateConflictAnalyzer.Result analysis = GateConflictAnalyzer.analyze(
                    nodes, NETHER_MIN_Y, NETHER_MAX_Y, OW_MIN_Y, OW_MAX_Y);

            CameraRenderState camState = ctx.levelState().cameraRenderState;
            if (camState == null || camState.pos == null) {
                return;
            }
            Vec3 camPos = camState.pos;
            PoseStack matrices = ctx.poseStack();
            // 描き先 target: >=26.2 = submit collector、 26.1.x = 自前 immediate。
            //? if >=26.2 {
            /*SubmitNodeCollector target = ctx.submitNodeCollector();*/
            //?} else {
            if (afterWaterBuffer == null) {
                afterWaterBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(4096));
            }
            MultiBufferSource.BufferSource target = afterWaterBuffer;
            //?}

            int curMinY = (cur == PortalDimension.NETHER) ? NETHER_MIN_Y : OW_MIN_Y;
            int curMaxY = (cur == PortalDimension.NETHER) ? NETHER_MAX_Y : OW_MAX_Y;

            // ── ゲート枠 (現次元へ射影・5 状態色・距離カリング/フェード/上限) ──
            int drawn = 0;
            for (int i = 0; i < nodes.size() && drawn < FRAME_CAP; i++) {
                GateNode node = nodes.get(i);
                GridPos here = PortalCoordinateMapper.project(node.pos(), node.dim(), cur, curMinY, curMaxY);
                double cx = here.x() + 0.5;
                double cy = here.y() + 1.5;
                double cz = here.z() + 0.5;
                double dist = horiz(camPos, cx, cz);
                if (dist > MAX_DIST) {
                    continue;
                }
                int baseColor = GateColors.forStateOrdinal(analysis.states()[i].ordinal());
                int color = fade(baseColor, dist);
                // 現次元の実ゲートは記憶の実寸を、 別次元の射影は既定寸法を使う。
                double w;
                double h;
                double t;
                if (node.dim() == cur) {
                    PortalMemory.FrameExtents ext = PortalMemory.get()
                            .frameExtentsAt(cur, node.pos())
                            .orElse(new PortalMemory.FrameExtents(DEF_W, DEF_H, DEF_T));
                    w = ext.dx();
                    h = ext.dy();
                    t = ext.dz();
                } else {
                    w = DEF_W;
                    h = DEF_H;
                    t = DEF_T;
                }
                boolean axisX = w >= t;
                double hw = axisX ? w * 0.5 : t * 0.5;
                double ht = axisX ? t * 0.5 : w * 0.5;
                AABB box = new AABB(cx - hw, here.y(), cz - ht, cx + hw, here.y() + h, cz + ht);
                OverlayDraw.box(target, matrices, camPos, box, color, FRAME_WIDTH);
                drawn++;
            }

            // ── 確定リンク線 (両端を現次元へ射影・紫・距離カリング/フェード) ──
            List<int[]> links = PortalMemory.get().confirmedLinks();
            for (int[] l : links) {
                // l = {owX,owY,owZ, nX,nY,nZ}
                GridPos a = PortalCoordinateMapper.project(new GridPos(l[0], l[1], l[2]),
                        PortalDimension.OVERWORLD, cur, curMinY, curMaxY);
                GridPos b = PortalCoordinateMapper.project(new GridPos(l[3], l[4], l[5]),
                        PortalDimension.NETHER, cur, curMinY, curMaxY);
                double ax = a.x() + 0.5;
                double ay = a.y() + 1.5;
                double az = a.z() + 0.5;
                double bx = b.x() + 0.5;
                double by = b.y() + 1.5;
                double bz = b.z() + 0.5;
                double mid = horiz(camPos, (ax + bx) * 0.5, (az + bz) * 0.5);
                if (mid > MAX_DIST) {
                    continue;
                }
                int color = fade(0xFF000000 | (GateColors.PC_LINK & 0xFFFFFF), mid);
                OverlayDraw.segment(target, matrices, camPos, ax, ay, az, bx, by, bz, color, LINK_WIDTH);
            }

            //? if <26.2 {
            target.endBatch();
            //?}
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] visualize render failed (continuing): {}", t.toString());
        }
    }

    private static double horiz(Vec3 cam, double x, double z) {
        double dx = x - cam.x;
        double dz = z - cam.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 距離後半で alpha を線形フェード (最小 0.25)。 RGB は不変。 */
    private static int fade(int argb, double dist) {
        if (dist <= FADE_START) {
            return argb;
        }
        double f = 1.0 - (dist - FADE_START) / (MAX_DIST - FADE_START);
        f = Math.max(0.25, Math.min(1.0, f));
        int a = (int) (((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
