package com.kajiwara.visualizegate.client.render;

import java.util.List;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.GateConflictAnalyzer;
import com.kajiwara.visualizegate.domain.GateNode;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.ui.GateColors;
import com.kajiwara.visualizegate.ui.GateLabels;

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
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;*/
//?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
//? if >=26.2 {
/*// 26.2 HUD 2D 投影パス用 (シェーダ下でもテキストを出すため・world/Iris 後段の GUI 描画)。
import com.kajiwara.visualizegate.state.BackCalcStore;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix4f;*/
//?}

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

    private static final double PIN_BASE_HEIGHT = 0.45; // ポータル天面からラベル下端までの余白 (OmniChest 現物)
    private static final double MAX_DIST = 512.0;       // 距離カリング上限 (ブロック・GateGraphRenderer と同値)

    // バニラ標準の次元境界 (Y クランプ用・GateGraphRenderer と同一前提)。
    private static final int OW_MIN_Y = -64;
    private static final int OW_MAX_Y = 319;
    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;

    //? if <26.2 {
    private MultiBufferSource.BufferSource textBuffer;
    //?}

    private GateNameLabelRenderer() {
    }

    public static void register() {
        //? if >=26.2 {
        /*// 26.2: Iris シェーダ下では engine の feature dispatcher 経由テキスト (submitText/submitNameTag) のグリフが
        //   捕捉されず消える (geometry=枠/リンク と entity 名札は生きる)。 そこでゲート名は HUD パス (world/Iris 後段) で
        //   world→screen 投影し GUI テキストで描く＝シェーダ ON/OFF どちらでも可視 (点群ミニマップと同じ HUD 経路)。
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("visualizegate", "gate_names"),
                (g, deltaTracker) -> INSTANCE.onHudRender(g));*/
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
            if (!GateMenuState.isGateNamesEnabled()) {
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
            if (textBuffer == null) {
                textBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            }
            MultiBufferSource.BufferSource target = textBuffer;
            //?}
            Font font = mc.font;
            double maxRenderDist = WorldLabel.maxRenderDistance(mc);

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
                WorldLabel.draw(target, matrices, camState, font, cx, cz, baseY, camPos, name, color, maxRenderDist);
                drewAny = true;
            }
            //? if <26.2 {
            if (drewAny) {
                target.endBatch();
            }
            //?}
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] gate-name render failed (continuing): {}", t.toString());
        }
    }

    //? if >=26.2 {
    /*// HUD 2D 投影レンダラ (>=26.2・シェーダ下対応)。 各ゲートの world 座標を camera の view×projection 行列で screen
    //   座標へ投影し、GUI テキストで名前を描く (world/Iris パス後の HUD なので Iris に消されない)。 深度遮蔽は無し＝
    //   従来 SEE_THROUGH の「壁越し常時可視」と同じ意図。 GUI 固定サイズ＝旧来の「画面サイズ一定」と同等。 距離/背後/画面外でカリング。
    private void onHudRender(GuiGraphicsExtractor g) {
        try {
            if (!GateMenuState.isGateNamesEnabled()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                return;
            }
            if (mc.gui.hud.isHidden() || mc.gui.screen() != null || mc.getDebugOverlay().showDebugScreen()) {
                return; // F1 / 他 Screen 表示中 / F3
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

            Camera cam = mc.gameRenderer.mainCamera();
            if (cam == null) {
                return;
            }
            Vec3 camPos = cam.position();
            // view 回転×projection (カメラ平行移動は含まない＝カメラ相対座標を渡す)。 reverse-Z でも screen X/Y は不変。
            Matrix4f vp = cam.getViewRotationProjectionMatrix(new Matrix4f());
            int gw = g.guiWidth();
            int gh = g.guiHeight();
            Font font = mc.font;

            for (int i = 0; i < nodes.size(); i++) {
                GateNode node = nodes.get(i);
                if (node.dim() != cur) {
                    continue; // 現次元の実ポータルのみ
                }
                if (PortalMemory.get().isHidden(cur, node.x(), node.y(), node.z())) {
                    continue;
                }
                PortalMemory.FrameExtents ext = PortalMemory.get()
                        .frameExtentsAt(cur, node.pos())
                        .orElse(new PortalMemory.FrameExtents(2.0, 3.0, 1.0));
                double cx = node.x() + 0.5;
                double cz = node.z() + 0.5;
                if (BackCalcStore.hasLabelPinAt(cur, cx, cz)) {
                    continue; // resolve-conflict ピンが同じゲートの名前を出すので、二重表示を避けて常時ラベルは省略
                }
                double baseY = node.y() + ext.dy() + PIN_BASE_HEIGHT;
                int color = 0xFF000000 | (GateColors.forStateOrdinal(analysis.states()[i].ordinal()) & 0xFFFFFF);
                WorldLabel.drawHud(g, vp, camPos, gw, gh, font, cx, baseY, cz, displayName(node), color, MAX_DIST);
            }
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] gate-name HUD render failed (continuing): {}", t.toString());
        }
    }*/
    //?}

    /** ㉝B/点群画面と同ルール: ユーザー命名 > 既定名 {@code OW-/N-<番号>}。 resolve-conflict コマンドと共用。 */
    public static String displayName(GateNode node) {
        String name = PortalMemory.get().nameAt(node.dim(), node.x(), node.y(), node.z());
        if (name != null) {
            return name;
        }
        return GateLabels.defaultName(node.dim() == PortalDimension.NETHER, node.number());
    }
}
