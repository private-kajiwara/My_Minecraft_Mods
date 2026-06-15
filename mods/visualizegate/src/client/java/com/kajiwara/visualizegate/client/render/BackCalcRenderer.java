package com.kajiwara.visualizegate.client.render;

import java.util.List;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.state.BackCalcStore;

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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ㉕ `/vg back-calculate` の予測ワイヤーフレームを<b>在世界</b>描画する (水後ステージ・<b>Mixin 不使用</b>)。
 *
 * <p>{@link BackCalcStore} の要素のうち<b>現在ディメンションに属するもののみ</b>を、 既存ゲートマーカーと
 * 同じ {@link OverlayDraw#box} (非シェーダ=vanilla lines / Iris=細クアッド)・同じ線太さで描く。
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

    private MultiBufferSource.BufferSource afterWaterBuffer;

    private BackCalcRenderer() {
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

            if (afterWaterBuffer == null) {
                afterWaterBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            }
            MultiBufferSource.BufferSource bufferSource = afterWaterBuffer;

            List<BackCalcStore.Element> elements = BackCalcStore.all();
            Font font = mc.font;
            double maxRenderDist = WorldLabel.maxRenderDistance(mc);
            boolean drewAny = false;
            for (BackCalcStore.Element e : elements) {
                // 現在ディメンションに属する要素のみ在世界に描く (逆側要素は点群スタックビューで見せる)。
                if (e.dim != cur) {
                    continue;
                }
                AABB box = new AABB(e.x - HALF_W, e.y, e.z - HALF_W,
                        e.x + HALF_W, e.y + HEIGHT, e.z + HALF_W);
                OverlayDraw.box(bufferSource, matrices, camPos, box, e.colorArgb, LINE_WIDTH);
                // resolve-conflict 等のラベル付き要素はボックス天面にピン (名前＋座標・SEE_THROUGH 壁越し)。
                if (e.label != null) {
                    WorldLabel.draw(bufferSource, matrices, camState, font,
                            e.x, e.z, e.y + HEIGHT + 0.45, camPos, e.label, e.labelColorArgb, maxRenderDist);
                }
                drewAny = true;
            }
            if (drewAny) {
                bufferSource.endBatch();
            }
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] back-calc render failed (continuing): {}", t.toString());
        }
    }
}
