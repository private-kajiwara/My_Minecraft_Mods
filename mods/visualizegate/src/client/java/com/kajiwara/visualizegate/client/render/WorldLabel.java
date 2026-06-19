package com.kajiwara.visualizegate.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;*/
//?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
//? if >=26.2 {
/*// HUD 2D 投影パス用 (シェーダ下でもテキストを出すための world/Iris 後段 GUI 描画・GateName/BackCalc 共用)。
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix4f;
import org.joml.Vector4f;*/
//?}

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
    //? if >=26.2 {
    /*public static void draw(SubmitNodeCollector bs, PoseStack matrices, CameraRenderState camState, Font font,
            double cx, double cz, double baseY, Vec3 camPos, String text, int colorArgb, double maxRenderDist) {*/
    //?} else {
    public static void draw(MultiBufferSource bs, PoseStack matrices, CameraRenderState camState, Font font,
            double cx, double cz, double baseY, Vec3 camPos, String text, int colorArgb, double maxRenderDist) {
    //?}
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
            //? if >=26.2 {
            /*// submitText の int 引数順 = (packedLight, color, backgroundColor, outlineColor)。 ただし engine の texts
            //   フェーズへ submit するため、 Iris シェーダ有効時はグリフが捕捉されず消える (sans-shader は可・user 実機確認)。
            //   nameTags フェーズ(submitNameTag)も mod 提出だと同様にグリフが落ちる＝submit 系で shader 下テキストは不可。
            bs.submitText(matrices, 0, 0, label.getVisualOrderText(), false,
                    Font.DisplayMode.SEE_THROUGH, 0xF000F0, colorArgb, PIN_BG_ARGB, 0);*/
            //?} else {
            font.drawInBatch(label.getVisualOrderText(), 0, 0,
                    colorArgb, false, matrices.last().pose(), bs,
                    Font.DisplayMode.SEE_THROUGH, PIN_BG_ARGB, 0xF000F0);
            //?}
        } finally {
            matrices.popPose();
        }
    }

    //? if >=26.2 {
    /*// HUD 2D 投影でラベル 1 件を描く共有ヘルパ (>=26.2・シェーダ下対応)。 GateName/BackCalc の各 HUD コールバックが
    //   camera VP/camPos/gui 寸法を一度用意し、要素ごとに本メソッドを呼ぶ。 world 座標→screen 投影し g.text で描画
    //   (world/Iris パス後の HUD ＝Iris に消されない・箱なし)。 背後/画面外/距離でカリング。 深度遮蔽なし＝壁越し常時可視。
    //   vp = camera.getViewRotationProjectionMatrix (平行移動含まず) なので cam 相対座標を渡す。 reverse-Z でも X/Y は不変。
    public static void drawHud(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, int guiW, int guiH, Font font,
            double wx, double wy, double wz, String text, int colorArgb, double maxDist) {
        float rx = (float) (wx - camPos.x);
        float ry = (float) (wy - camPos.y);
        float rz = (float) (wz - camPos.z);
        if (rx * rx + rz * rz > maxDist * maxDist) {
            return; // 水平距離カリング
        }
        Vector4f clip = vp.transform(new Vector4f(rx, ry, rz, 1.0f));
        if (clip.w <= 1.0e-4f) {
            return; // カメラ背後
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        if (ndcX < -1.1f || ndcX > 1.1f || ndcY < -1.1f || ndcY > 1.1f) {
            return; // 画面外 (僅かな余白で端のラベルは残す)
        }
        int sx = Math.round((ndcX * 0.5f + 0.5f) * guiW);
        int sy = Math.round((0.5f - ndcY * 0.5f) * guiH);
        int tw = font.width(text);
        g.text(font, text, sx - tw / 2, sy - font.lineHeight / 2, colorArgb);
    }*/
    //?}

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
