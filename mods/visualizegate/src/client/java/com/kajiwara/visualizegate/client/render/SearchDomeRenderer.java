package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.scan.PortalRecord;
import com.kajiwara.visualizegate.scan.PortalIndex;
import com.kajiwara.visualizegate.state.GateMenuState;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 機能3: 探索ドーム v1 (リンク検索範囲の可視化＋混線検出・<b>Mixin 不使用</b>)。
 *
 * <p>注視/計画中のポータル P の周りに、 <b>現dim 依存</b>の検索半径 (OW=128 / Nether=16) の<b>ワイヤフレーム
 * ドーム (半球)</b> を描く。 リンクが現dim に到着する際この範囲が探索されるため、 半径は現dim 側の値で、 現dim の
 * P 周辺に描く (プレイヤーが見えるのは現dim のみ)。 <b>赤道リング (P の Y・半径 R) が真の境界</b>＝バニラの実探索は
 * 水平距離判定 (XZ)。 ドーム上半分は体積を直感的に読ませるための近似 (厳密 AABB 表示は将来トグル)。
 *
 * <p><b>混線検出</b>: その水平範囲内にある<b>他の</b>既知ポータル ({@link PortalIndex} 現dim) を警告色 (オレンジ) の
 * 箱で強調する (＝入ってくるリンクが意図しないゲートを掴む可能性; 範囲内に複数＝混線リスク)。
 *
 * <p>トリガは {@link PortalGaze#resolvePlanning} (注視 or 火打石所持) ＝カード/機能2/ホログラムと共有し、
 * <b>注視/計画中の 1 ポータル分だけ</b>描く (FPS/視界保護)。 描画は {@link PortalBoxRenderer}/機能2 と同じ水後
 * ステージ＋バニラ {@code RenderTypes.lines()} を流用 (半透明面塗り＋★D は次ステップで解決＝今回は線のみ)。
 */
public final class SearchDomeRenderer {

    private static final SearchDomeRenderer INSTANCE = new SearchDomeRenderer();

    private static final int DOME_ARGB = GateColors.DOME;
    private static final int CROSSTALK_ARGB = GateColors.CROSSTALK;
    private static final float DOME_WIDTH = 1.5f;      // ドームは細め (巨大でも視界を塞がない)
    private static final float CROSS_WIDTH = 2.5f;      // 混線箱は強調
    private static final int CIRCLE_SEG = 48;          // 緯度円の分割数 (丸み)
    private static final double CROSS_INFLATE = 0.2;    // 既存マゼンタ枠の外側に出すための膨張

    //? if <26.2 {
    private MultiBufferSource.BufferSource afterWaterBuffer;
    //?}

    private SearchDomeRenderer() {
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
            if (!GateMenuState.isDomeEnabled())
                return;
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null)
                return;

            // 注視/計画中の 1 ポータルだけ (機能2/カード/ホログラムと同トリガ・同 source)。
            PortalGaze.Result r = PortalGaze.resolvePlanning(mc);
            if (r == null) {
                return;
            }
            double cx = r.sourceX();
            double cy = r.sourceY();
            double cz = r.sourceZ();
            boolean nether = (r.current() == PortalDimension.NETHER);
            double radius = nether ? 16.0 : 128.0;

            CameraRenderState camState = ctx.levelState().cameraRenderState;
            if (camState == null || camState.pos == null)
                return;
            Vec3 camPos = camState.pos;
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

            // 描画は共有ヘルパへ委譲 (lines()・深度オクルージョン有り)。
            drawDome(target, matrices, cx, cy, cz, radius, camPos, nether);
            highlightCrosstalk(target, matrices, level, r, cx, cz, radius, camPos);

            //? if <26.2 {
            target.endBatch();
            //?}
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] dome render failed (continuing): {}", t.toString());
        }
    }

    // ── ドーム (半球ワイヤフレーム) ──────────────────────────────────────

    //? if >=26.2 {
    /*private void drawDome(SubmitNodeCollector bs, PoseStack matrices,
            double cx, double cy, double cz, double radius, Vec3 cam, boolean nether) {*/
    //?} else {
    private void drawDome(MultiBufferSource.BufferSource bs, PoseStack matrices,
            double cx, double cy, double cz, double radius, Vec3 cam, boolean nether) {
    //?}
        // 128 は巨大 → 薄く疎 (緯度リング少なめ); Nether=16 は密め。
        int latRings = nether ? 5 : 3;   // 赤道 (i=0) から上へ
        int meridians = nether ? 6 : 4;
        int meridianSeg = nether ? 8 : 10;

        // 緯度リング (水平円)。 i=0 = 赤道 (半径 R・真の境界)。
        for (int i = 0; i < latRings; i++) {
            double phi = (Math.PI / 2.0) * i / latRings;
            double ringY = cy + radius * Math.sin(phi);
            double ringR = radius * Math.cos(phi);
            drawHorizontalCircle(bs, matrices, cx, ringY, cz, ringR, cam);
        }
        // 経度リング (赤道→頂点の四分円)。
        for (int m = 0; m < meridians; m++) {
            double th = (Math.PI * 2.0) * m / meridians;
            double cosT = Math.cos(th);
            double sinT = Math.sin(th);
            double px = cx + radius * cosT;
            double py = cy;
            double pz = cz + radius * sinT;
            for (int s = 1; s <= meridianSeg; s++) {
                double phi = (Math.PI / 2.0) * s / meridianSeg;
                double rr = radius * Math.cos(phi);
                double nx = cx + rr * cosT;
                double ny = cy + radius * Math.sin(phi);
                double nz = cz + rr * sinT;
                OverlayDraw.segment(bs, matrices, cam, px, py, pz, nx, ny, nz, DOME_ARGB, DOME_WIDTH);
                px = nx;
                py = ny;
                pz = nz;
            }
        }
    }

    //? if >=26.2 {
    /*private void drawHorizontalCircle(SubmitNodeCollector bs, PoseStack matrices,
            double cx, double cy, double cz, double r, Vec3 cam) {*/
    //?} else {
    private void drawHorizontalCircle(MultiBufferSource.BufferSource bs, PoseStack matrices,
            double cx, double cy, double cz, double r, Vec3 cam) {
    //?}
        double prevX = cx + r;
        double prevZ = cz;
        for (int s = 1; s <= CIRCLE_SEG; s++) {
            double th = (Math.PI * 2.0) * s / CIRCLE_SEG;
            double x = cx + r * Math.cos(th);
            double z = cz + r * Math.sin(th);
            OverlayDraw.segment(bs, matrices, cam, prevX, cy, prevZ, x, cy, z, DOME_ARGB, DOME_WIDTH);
            prevX = x;
            prevZ = z;
        }
    }

    // ── 混線検出 (範囲内の他ゲートを強調) ────────────────────────────────

    //? if >=26.2 {
    /*private void highlightCrosstalk(SubmitNodeCollector bs, PoseStack matrices, ClientLevel level,
            PortalGaze.Result r, double cx, double cz, double radius, Vec3 camPos) {*/
    //?} else {
    private void highlightCrosstalk(MultiBufferSource.BufferSource bs, PoseStack matrices, ClientLevel level,
            PortalGaze.Result r, double cx, double cz, double radius, Vec3 camPos) {
    //?}
        // 注視ポータル P 自身は除外 (火打石計画時は looked==null＝全件が「他ゲート」)。
        BlockPos selfAnchor = (r.portal() != null) ? r.portal().anchor() : null;
        double r2 = radius * radius;
        for (PortalRecord rec : PortalIndex.get().recordsFor(level.dimension())) {
            if (selfAnchor != null && rec.anchor().equals(selfAnchor)) {
                continue;
            }
            AABB bb = rec.aabb();
            double rxc = (bb.minX + bb.maxX) * 0.5;
            double rzc = (bb.minZ + bb.maxZ) * 0.5;
            double dxh = rxc - cx;
            double dzh = rzc - cz;
            if (dxh * dxh + dzh * dzh <= r2) { // 水平距離 (実探索メトリックに一致)
                OverlayDraw.box(bs, matrices, camPos, bb.inflate(CROSS_INFLATE), CROSSTALK_ARGB, CROSS_WIDTH);
            }
        }
    }
}
