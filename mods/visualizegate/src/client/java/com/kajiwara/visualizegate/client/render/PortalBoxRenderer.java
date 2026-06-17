package com.kajiwara.visualizegate.client.render;

import java.util.List;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.scan.PortalIndex;
import com.kajiwara.visualizegate.scan.PortalRecord;
import com.kajiwara.visualizegate.state.GateMenuState;

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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * {@link PortalIndex} の各ポータルの AABB に枠 (ワイヤーボックス) を描画する。
 *
 * <p><b>水後ステージ</b>: OmniChest のビーム描画と同じく、 水 (半透明地形) の描画<b>後</b>に発火する
 * ステージ ({@code AFTER_TRANSLUCENT_TERRAIN} / legacy {@code END_MAIN}) で自前 immediate バッファへ描く。
 * これにより枠が水の後ろに回らない。 カメラ/行列の取得は OmniChest の {@code onAfterWaterRender} を
 * 現物どおりに踏襲する (= 記憶ではなくソース由来)。
 *
 * <p><b>Mixin 不使用</b>: バニラの {@code RenderTypes.lines()}（legacy は {@code RenderType.lines()}) と
 * {@link ShapeRenderer#renderShape} で描く (= カスタム RenderType / accessor mixin を必要としない)。
 * バニラ lines は深度テスト有りのため、 「水越しで枠が水の後ろに回らない」 保証は<b>ステージのタイミング</b>
 * に依存する。 runClient で水ケースを観測し、 不足する場合は no-depth (= mixin) の要否を設計判断へ戻す。
 */
public final class PortalBoxRenderer {

    private static final PortalBoxRenderer INSTANCE = new PortalBoxRenderer();

    /** 枠の色 (ARGB): ネザーポータル色に寄せた明るいマゼンタ・不透明。 */
    private static final int BOX_ARGB = 0xFFC040FF;
    /** 線幅 (>=1.21.11 の renderShape が取る per-call line width)。 */
    private static final float LINE_WIDTH = 2.5f;

    /** 水後ステージ用の自前 immediate バッファ (初回 lazy 構築・以後フレーム間で再利用)。 */
    private MultiBufferSource.BufferSource afterWaterBuffer;

    private PortalBoxRenderer() {
    }

    public static void register() {
        // 水 (半透明地形) の描画後に発火するステージに登録する (= 枠が水に上書きされない)。
        //? if >=26.1 {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> INSTANCE.onAfterWater(ctx));
        //?} else {
        /*WorldRenderEvents.END_MAIN.register(ctx -> INSTANCE.onAfterWater(ctx));*/
        //?}
    }

    private void onAfterWater(LevelRenderContext ctx) {
        try {
            // メニューの「ゲート枠表示」トグル。 OFF なら即 return (未操作の既定は ON = 従来挙動)。
            if (!GateMenuState.isBoxOverlayEnabled())
                return;
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null)
                return;
            ResourceKey<Level> dim = level.dimension();
            List<PortalRecord> records = PortalIndex.get().recordsFor(dim);
            if (records.isEmpty())
                return;

            // カメラ/行列の取得は OmniChest onAfterWaterRender の現物どおり。
            CameraRenderState camState = ctx.levelState().cameraRenderState;
            if (camState == null || camState.pos == null)
                return;
            Vec3 camPos = camState.pos;
            PoseStack matrices = ctx.poseStack();

            if (afterWaterBuffer == null) {
                afterWaterBuffer = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            }
            MultiBufferSource.BufferSource bufferSource = afterWaterBuffer;

            // 描画は共有ヘルパへ委譲 (lines()・深度オクルージョン有り。 Iris 時のみ描き先をレベルバッファへ)。
            for (PortalRecord rec : records) {
                OverlayDraw.box(bufferSource, matrices, camPos, rec.aabb(), BOX_ARGB, LINE_WIDTH);
            }

            bufferSource.endBatch();
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] portal box render failed (continuing): {}", t.toString());
        }
    }
}
