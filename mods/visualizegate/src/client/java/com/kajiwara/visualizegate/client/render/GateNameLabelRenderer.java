package com.kajiwara.visualizegate.client.render;

import java.util.List;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.GateConflictAnalyzer;
import com.kajiwara.visualizegate.domain.GateNode;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.state.GateMenuState;
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
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * ゲート名ラベル: 現次元に<b>実在する各ポータルの真上</b>に<b>ゲート名のみ</b>を在世界表示する
 * (水後ステージ・<b>Mixin 不使用</b>・OmniChest のピン流儀を流用)。
 *
 * <p>テキストは {@link Font.DisplayMode#SEE_THROUGH} ＝ NO_DEPTH_TEST 系の RenderType を内部選択する
 * バニラ API で描くため、 <b>壁越しに常時可視</b>・新規 Mixin/カスタム RenderType 不要。 ビルボード変換は
 * {@code translate → mulPose(camState.orientation) → scale(s,-s,s)} (OmniChest {@code drawPinImmediate} 現物)。
 *
 * <p>名前は {@link PortalMemory#nameAt} (ユーザー命名) > 既定名 {@code OW-/N-<番号>} (点群画面と同ルール)。
 * 色は {@link GateConflictAnalyzer} の<b>5 状態色</b> ({@link GateColors#forStateOrdinal}・ドック/カード/
 * グラフと一本化)。 {@code isHidden} のゲートは描かない。 既定 ON・トグル {@code /vg names} ・{@link GateMenuState}
 * 永続。 F1(hideGui)/F3 で非表示・OW↔Nether のみ。 ⑤⑦ の記憶は<b>読みだけ</b> (永続/reconcile 不変)。
 */
public final class GateNameLabelRenderer {

    private static final GateNameLabelRenderer INSTANCE = new GateNameLabelRenderer();

    // OmniChest ピン現物の値 (= 同一の見え方)。
    private static final float PIN_TEXT_SCALE = 0.025f;       // ワールド→画面の基準スケール
    private static final double PIN_SCALE_REF_DISTANCE = 6.0; // これ以遠は画面サイズ一定 (透視縮小を打ち消す)
    private static final double PIN_BASE_HEIGHT = 0.45;       // ポータル天面からラベル下端までの余白
    private static final int PIN_BG_ARGB = 0xE0000000;        // テキスト背景の黒帯 (drawInBatch bgColor)

    private static final double MAX_DIST = 512.0; // 距離カリング上限 (ブロック・GateGraphRenderer と同値)

    // バニラ標準の次元境界 (Y クランプ用・GateGraphRenderer と同一前提)。
    private static final int OW_MIN_Y = -64;
    private static final int OW_MAX_Y = 319;
    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;

    private MultiBufferSource.BufferSource textBuffer;

    private GateNameLabelRenderer() {
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
            if (!GateMenuState.isGateNamesEnabled()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                return;
            }
            if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
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
            if (textBuffer == null) {
                textBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            }
            MultiBufferSource.BufferSource bs = textBuffer;
            Font font = mc.font;
            double maxRenderDist = pinMaxRenderDistance(mc);

            boolean drewAny = false;
            for (int i = 0; i < nodes.size(); i++) {
                GateNode node = nodes.get(i);
                // 現次元に実在するゲートのみ (= 実ポータルの上に置く。 別次元の写しには出さない)。
                if (node.dim() != cur) {
                    continue;
                }
                if (PortalMemory.get().isHidden(cur, node.x(), node.y(), node.z())) {
                    continue;
                }
                // 実ポータルの天面 (記憶の実寸・無ければ既定高 3)。
                PortalMemory.FrameExtents ext = PortalMemory.get()
                        .frameExtentsAt(cur, node.pos())
                        .orElse(new PortalMemory.FrameExtents(2.0, 3.0, 1.0));
                double cx = node.x() + 0.5;
                double cz = node.z() + 0.5;
                double baseY = node.y() + ext.dy() + PIN_BASE_HEIGHT;

                double dx = cx - camPos.x;
                double dz = cz - camPos.z;
                if (Math.sqrt(dx * dx + dz * dz) > MAX_DIST) {
                    continue;
                }

                int color = 0xFF000000 | (GateColors.forStateOrdinal(analysis.states()[i].ordinal()) & 0xFFFFFF);
                String name = displayName(node);
                drawLabel(bs, matrices, camState, font, cx, cz, baseY, camPos, name, color, maxRenderDist);
                drewAny = true;
            }
            if (drewAny) {
                bs.endBatch();
            }
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] gate-name render failed (continuing): {}", t.toString());
        }
    }

    /** ㉝B/点群画面と同ルール: ユーザー命名 > 既定名 {@code OW-/N-<番号>}。 */
    private static String displayName(GateNode node) {
        String name = PortalMemory.get().nameAt(node.dim(), node.x(), node.y(), node.z());
        if (name != null) {
            return name;
        }
        return (node.dim() == PortalDimension.NETHER ? "N-" : "OW-") + node.number();
    }

    /**
     * 1 ゲートのビルボード名前ラベル (中央揃え・SEE_THROUGH・far クリップ引き寄せ)。 OmniChest
     * {@code drawPinImmediate} のヘッダ描画と等価の変換 (アイコン/距離行は無し＝名前 1 行のみ)。
     */
    private void drawLabel(MultiBufferSource bs, PoseStack matrices, CameraRenderState camState, Font font,
            double cx, double cz, double baseY, Vec3 camPos, String name, int color, double maxRenderDist) {
        double dx = cx - camPos.x;
        double dy = baseY - camPos.y;
        double dz = cz - camPos.z;
        double distM = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // far クリップ平面の内側へ引き寄せ (= 遠距離でラベルが丸ごと消える GPU クリップ対策・OmniChest 現物)。
        double renderDist = Math.min(distM, maxRenderDist);
        double clampFactor = (distM > 1.0e-6) ? (renderDist / distM) : 1.0;
        double rdx = dx * clampFactor;
        double rdy = dy * clampFactor;
        double rdz = dz * clampFactor;
        // 基準距離より遠ければスケールを距離比例 (= 画面サイズ一定)。
        float distScaleFactor = (float) (Math.max(renderDist, PIN_SCALE_REF_DISTANCE) / PIN_SCALE_REF_DISTANCE);
        float worldScale = PIN_TEXT_SCALE * distScaleFactor;

        Component label = Component.literal(name);
        float halfWidth = font.width(label) / 2.0f;

        matrices.pushPose();
        try {
            matrices.translate(rdx, rdy, rdz);
            matrices.mulPose(camState.orientation);
            matrices.scale(worldScale, -worldScale, worldScale);
            // 中央揃え (左へ半幅) ・ベースライン上げ (= 文字下端 ≒ ポータル天面 + PIN_BASE_HEIGHT)。
            matrices.translate(-halfWidth, -(font.lineHeight + 1) + 1.0f, 0);
            font.drawInBatch(label.getVisualOrderText(), 0, 0,
                    color, false, matrices.last().pose(), bs,
                    Font.DisplayMode.SEE_THROUGH, PIN_BG_ARGB, 0xF000F0);
        } finally {
            matrices.popPose();
        }
    }

    /** カメラ far 平面 (≈ 描画距離 ×16m) の内側目安 (OmniChest 現物)。 */
    private static double pinMaxRenderDistance(Minecraft mc) {
        try {
            int chunks = mc.options.getEffectiveRenderDistance();
            return Math.max(16.0, chunks * 16.0 * 0.8);
        } catch (Throwable ignored) {
            return 128.0;
        }
    }
}
