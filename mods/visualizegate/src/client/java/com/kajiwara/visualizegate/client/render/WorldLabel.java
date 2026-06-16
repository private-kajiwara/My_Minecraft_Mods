package com.kajiwara.visualizegate.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * 在世界ビルボード テキストラベル (1 行) の共有描画ヘルパ。 OmniChest {@code ChestHighlighter.drawPinImmediate}
 * のピン変換を流用: {@code translate(camRel) → mulPose(camState.orientation) → scale(s,-s,s)}。
 *
 * <p>テキストは {@link Font.DisplayMode#SEE_THROUGH} (NO_DEPTH_TEST 系を内部選択するバニラ API) で描くため
 * <b>壁越しに常時可視</b>・新規 Mixin/カスタム RenderType 不要。 黒帯背景は {@code drawInBatch} の bgColor 引数で付く。
 * ゲート名ラベルと resolve-conflict のピンが共用 (= ビルボード計算を一本化)。
 */
public final class WorldLabel {

    // OmniChest ピン現物の値 (= 同一の見え方)。
    private static final float PIN_TEXT_SCALE = 0.025f;       // ワールド→画面の基準スケール
    private static final double PIN_SCALE_REF_DISTANCE = 6.0; // これ以遠は画面サイズ一定 (透視縮小を打ち消す)
    private static final int PIN_BG_ARGB = 0xE0000000;        // テキスト背景の黒帯 (drawInBatch bgColor)

    private WorldLabel() {
    }

    /**
     * {@code (cx, baseY, cz)} (絶対ワールド中心・天面想定) に 1 行ラベルをビルボード描画する。
     *
     * @param colorArgb     テキスト色 (ARGB・状態色など)
     * @param maxRenderDist far クリップ平面の内側目安 (これより遠ければカメラ方向へ引き寄せる)
     */
    public static void draw(MultiBufferSource bs, PoseStack matrices, CameraRenderState camState, Font font,
            double cx, double cz, double baseY, Vec3 camPos, String text, int colorArgb, double maxRenderDist) {
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

        Component label = Component.literal(text);
        float halfWidth = font.width(label) / 2.0f;

        matrices.pushPose();
        try {
            matrices.translate(rdx, rdy, rdz);
            matrices.mulPose(camState.orientation);
            matrices.scale(worldScale, -worldScale, worldScale);
            // 中央揃え (左へ半幅) ・ベースライン上げ (= 文字下端 ≒ 指定 baseY)。
            matrices.translate(-halfWidth, -(font.lineHeight + 1) + 1.0f, 0);
            font.drawInBatch(label.getVisualOrderText(), 0, 0,
                    colorArgb, false, matrices.last().pose(), bs,
                    Font.DisplayMode.SEE_THROUGH, PIN_BG_ARGB, 0xF000F0);
        } finally {
            matrices.popPose();
        }
    }

    /** カメラ far 平面 (≈ 描画距離 ×16m) の内側目安 (OmniChest 現物・ラベルが遠距離で消えるのを防ぐ)。 */
    public static double maxRenderDistance(Minecraft mc) {
        try {
            int chunks = mc.options.getEffectiveRenderDistance();
            return Math.max(16.0, chunks * 16.0 * 0.8);
        } catch (Throwable ignored) {
            return 128.0;
        }
    }
}
