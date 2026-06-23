package com.kajiwara.visualizegate.client.render;

import java.util.List;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.state.BackCalcStore;

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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
//? if >=26.2 {
/*// 26.2 ラベルピンの HUD 2D 投影パス用 (シェーダ下では submitText のグリフが落ち黒箱だけ残るため・GateName と同経路)。
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix4f;*/
//?}

/**
 * ㉕ `/vg back-calculate` の予測ワイヤーフレームを<b>在世界</b>描画する (水後ステージ・<b>Mixin 不使用</b>)。
 *
 * <p>{@link BackCalcStore} の要素のうち<b>現在ディメンションに属するもののみ</b>を、 既存ゲートマーカーと
 * 同じ {@link OverlayDraw#box} (vanilla lines()・Iris 時のみ描き先をレベルバッファへ)・同じ線太さで描く。
 * 緑=建設推奨 / 赤=吸い込み警告 (色は要素が保持)。 自動消滅せず `/vg clean` でのみ消える。
 *
 * <p>フック・カメラ/行列取得は {@link PortalBoxRenderer} と同一 (現物踏襲)。
 */
public final class BackCalcRenderer {

    private static final BackCalcRenderer INSTANCE = new BackCalcRenderer();

    /** 既存ゲートマーカーと同じ線太さ (黒曜石枠流儀)。 */
    private static final float LINE_WIDTH = 2.5f;
    /** 建設推奨/警告ボックスの footprint 半幅 (ポータル枠 4 幅相当) と高さ (枠 5 高相当)。 */
    private static final double HALF_W = 2.0;
    private static final double HEIGHT = 5.0;
    /** 排他ゾーン (平たい正方形リング) の薄い高さと線幅。 */
    private static final double ZONE_HEIGHT = 0.4;
    private static final float ZONE_WIDTH = 2.0f;

    //? if <26.2 {
    private MultiBufferSource.BufferSource afterWaterBuffer;
    //?}

    private BackCalcRenderer() {
    }

    public static void register() {
        //? if >=26.2 {
        /*// 26.2: ボックス/ゾーン (geometry) は world で描く (Iris 捕捉)。 ラベルピンは Iris が submitText グリフを落とし
        //   黒箱だけ残るため、 GateName と同じ HUD 2D 投影パスへ分離する。
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> INSTANCE.onAfterWater(ctx));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("visualizegate", "backcalc_names"),
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
            if (BackCalcStore.isEmpty()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                return;
            }
            PortalDimension cur = PortalMemory.dimOf(level.dimension().identifier().toString());

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
                afterWaterBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            }
            MultiBufferSource.BufferSource target = afterWaterBuffer;
            //?}

            List<BackCalcStore.Element> elements = BackCalcStore.all();
            //? if <26.2 {
            Font font = mc.font;
            double maxRenderDist = WorldLabel.maxRenderDistance(mc);
            //?}
            boolean drewAny = false;
            for (BackCalcStore.Element e : elements) {
                // 現在ディメンションに属する要素のみ在世界に描く (逆側要素は点群スタックビューで見せる)。
                if (e.dim != cur) {
                    continue;
                }
                if (e.squareHalf > 0) {
                    // 排他ゾーン: 平たい正方形リング (footprint・吸い込み=赤 / 取り合い=橙)。
                    AABB zone = new AABB(e.x - e.squareHalf, e.y, e.z - e.squareHalf,
                            e.x + e.squareHalf, e.y + ZONE_HEIGHT, e.z + e.squareHalf);
                    OverlayDraw.box(target, matrices, camPos, zone, e.colorArgb, ZONE_WIDTH);
                    drewAny = true;
                    continue;
                }
                AABB box = new AABB(e.x - HALF_W, e.y, e.z - HALF_W,
                        e.x + HALF_W, e.y + HEIGHT, e.z + HALF_W);
                OverlayDraw.box(target, matrices, camPos, box, e.colorArgb, LINE_WIDTH);
                // resolve-conflict 等のラベル付き要素はボックス天面にピン (名前＋座標)。
                //? if <26.2 {
                // 26.1.x: 在世界 immediate テキスト (SEE_THROUGH・壁越し・Iris 素通りで生存)。
                if (e.label != null) {
                    WorldLabel.draw(target, matrices, camState, font,
                            e.x, e.z, e.y + HEIGHT + 0.45, camPos, e.label, e.labelColorArgb, maxRenderDist);
                }
                //?}
                //? if >=26.2 {
                /*// 26.2: ラベルは onHudRender (HUD 2D 投影) で描く＝Iris シェーダ下でも可視・黒箱無し (ここでは描かない)。*/
                //?}
                drewAny = true;
            }
            //? if <26.2 {
            if (drewAny) {
                target.endBatch();
            }
            //?}
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] back-calc render failed (continuing): {}", t.toString());
        }
    }

    //? if >=26.2 {
    /*// 26.2 ラベルピンの HUD 2D 投影レンダラ (シェーダ下対応・GateName と同経路)。 ボックス/ゾーンは onAfterWater が
    //   world で描く (geometry=Iris 捕捉)。 ここは label 付き要素の名前だけを world→screen 投影し GUI テキストで描く
    //   ＝Iris シェーダ下でも可視・黒箱無し。 ゾーン (squareHalf>0) はラベル無しなので除外。
    private void onHudRender(GuiGraphicsExtractor g) {
        try {
            if (BackCalcStore.isEmpty()) {
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
            Camera cam = mc.gameRenderer.mainCamera();
            if (cam == null) {
                return;
            }
            Vec3 camPos = cam.position();
            Matrix4f vp = cam.getViewRotationProjectionMatrix(new Matrix4f());
            int gw = g.guiWidth();
            int gh = g.guiHeight();
            Font font = mc.font;
            double maxDist = WorldLabel.maxRenderDistance(mc);
            for (BackCalcStore.Element e : BackCalcStore.all()) {
                if (e.dim != cur || e.label == null || e.squareHalf > 0) {
                    continue; // 現次元のラベル付き要素のみ (ゾーンはラベル無し)
                }
                WorldLabel.drawHud(g, vp, camPos, gw, gh, font,
                        e.x, e.y + HEIGHT + 0.45, e.z, e.label, e.labelColorArgb, maxDist);
            }
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] back-calc HUD label render failed (continuing): {}", t.toString());
        }
    }*/
    //?}
}
