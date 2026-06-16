package com.kajiwara.visualizegate.client.render;

// シェーダ (Iris) 有効時の "絶対に出る" ワイヤーフレーム経路。 ワールド空間に lines/quad で描くと Iris の
// gbuffer パイプライン差し替えに負けて消えるため、 線分を <b>後段の HUD パス (ワールド描画後)</b> へ移し、
// 現フレームのカメラ行列で 2D スクリーン座標へ射影して g.fill の 2D 線で描く＝構造上シェーダに干渉されない。
//
// 投影に必要な projection Matrix4f が公開フィールドで取れるのは >=26.1 (CameraRenderState.projectionMatrix) のみ。
// 旧世代 (1.21.10/1.21.11) は CameraRenderState に projection が無く RenderSystem も GpuBufferSlice 化していて
// Matrix4f を綺麗に取れないため、 この経路は <b>>=26.1 限定</b>。 旧世代は OverlayDraw 側で従来のワールド quad
// 経路へフォールバックする (= 挙動不変・非回帰)。 全アクセスはバニラ公開 API のみ＝<b>Mixin 不使用</b>。
//? if >=26.1 {
import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.kajiwara.visualizegate.VisualizeGateMod;
import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * シェーダ時のワイヤーフレームを HUD パスで 2D 射影描画する保証経路 (>=26.1・Mixin 不使用)。
 *
 * <p><b>フロー</b> (1 フレーム):
 * <ol>
 *   <li><b>capture</b> ({@code AFTER_TRANSLUCENT_TERRAIN}・最初に登録＝最初に発火): シェーダ有効なら
 *       {@link CameraRenderState#projectionMatrix} とビューポート寸を捕捉し蓄積をリセット。 非シェーダなら無効化。</li>
 *   <li><b>append</b> ({@link OverlayDraw} 各 wire レンダラから・同 {@code AFTER_TRANSLUCENT_TERRAIN}):
 *       ワールド線分を <b>view 行列 (PoseStack の pose＝lines() と同じ変換) ×projection</b> でクリップ空間へ送り、
 *       <b>near 平面クリップ</b> (w&le;{@link #W_MIN} の頂点を含む線分は near でクリップ・完全に背後は捨てる) 後に
 *       スクリーン px へ射影して 2D 線分として蓄積する。</li>
 *   <li><b>flush</b> ({@code HudElementRegistry.addLast}・ワールド描画後の HUD パス): 蓄積した 2D 線分を
 *       {@code g.fill} の太線 (DDA) で描く。 シェーダのワールドパイプライン差し替えの<b>後</b>なので確実に出る。</li>
 * </ol>
 *
 * <p><b>挙動差</b>: HUD 射影経路は地形オクルージョンが効かず常に手前表示 (壁越しでも見える)。 これは
 * "絶対表示" を優先した許容トレードオフ (非シェーダ時は従来どおり深度オクルージョン有りの world lines)。
 */
public final class ShaderWireOverlay {

    /** near 平面クリップしきい値 (clip.w がこれ以下＝カメラ平面上/背後)。 反転/streak を防ぐ。 */
    private static final float W_MIN = 0.05f;
    /** 1 フレームの 2D 線分蓄積上限 (過密保護)。 超過分は捨て、 一度だけ警告。 */
    private static final int SEG_CAP = 40000;

    // ── 捕捉した現フレームのカメラ状態 ──
    private static volatile boolean active;             // シェーダ有効＋projection 捕捉済み
    private static final Matrix4f projection = new Matrix4f();
    private static int vpW;
    private static int vpH;

    // ── 蓄積した 2D 線分 (スクリーン px・near クリップ済み)。 primitive 並列配列で GC 圧を回避。 ──
    private static float[] sx1 = new float[1024];
    private static float[] sy1 = new float[1024];
    private static float[] sx2 = new float[1024];
    private static float[] sy2 = new float[1024];
    private static float[] sw = new float[1024];
    private static int[] scol = new int[1024];
    private static int segCount;
    private static boolean warnedCap;

    // 射影の一時バッファ (フレーム内単スレッド＝再利用で割当回避)。
    private static final Matrix4f mvp = new Matrix4f();
    private static final Vector4f ca = new Vector4f();
    private static final Vector4f cb = new Vector4f();

    private ShaderWireOverlay() {
    }

    public static void register() {
        // capture は wire レンダラより前に発火させたい → VisualizeGateClient で最初に register する。
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> beginFrame(ctx.levelState().cameraRenderState));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("visualizegate", "shader_wire_overlay"),
                (g, deltaTracker) -> flush(g));
    }

    /** 現フレーム capture: シェーダ有効なら projection/ビューポートを取り蓄積をリセット。 非シェーダなら無効化。 */
    private static void beginFrame(CameraRenderState cam) {
        segCount = 0;
        if (!OverlayDraw.shaderActive() || cam == null || cam.projectionMatrix == null) {
            active = false;
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            vpW = mc.getWindow().getGuiScaledWidth();
            vpH = mc.getWindow().getGuiScaledHeight();
            if (vpW <= 0 || vpH <= 0) {
                active = false;
                return;
            }
            projection.set(cam.projectionMatrix);
            active = true;
        } catch (Throwable t) {
            active = false;
        }
    }

    /** capture 済みで append 可能か (OverlayDraw が分岐判断に使う)。 */
    public static boolean captureReady() {
        return active;
    }

    /**
     * ワールド線分を view×projection でクリップ空間へ送り、 near クリップ後に 2D スクリーン線分として蓄積する。
     * {@code pose} は wire レンダラが lines() に渡すのと同じ {@code matrices.last().pose()} (= view 行列)。
     * 蓄積できたら true (=この線は HUD で描く)。 capture 未了なら false (=呼び出し側が従来 quad へフォールバック)。
     */
    public static boolean addWorldSegment(PoseStack.Pose pose, Vec3 camPos,
            double x1, double y1, double z1, double x2, double y2, double z2, int color, float widthPx) {
        if (!active) {
            return false;
        }
        // mvp = projection * pose (このフレームでは pose は実質一定だが、 呼び出しごとに合成しても安価)。
        mvp.set(projection).mul(pose.pose());
        ca.set((float) (x1 - camPos.x), (float) (y1 - camPos.y), (float) (z1 - camPos.z), 1f).mul(mvp);
        cb.set((float) (x2 - camPos.x), (float) (y2 - camPos.y), (float) (z2 - camPos.z), 1f).mul(mvp);
        storeClipped(ca, cb, color, widthPx);
        return true;
    }

    /** AABB の 12 辺を線分として蓄積 (capture 済みなら true)。 */
    public static boolean addWorldBox(PoseStack.Pose pose, Vec3 camPos, AABB b, int color, float widthPx) {
        if (!active) {
            return false;
        }
        double x0 = b.minX;
        double y0 = b.minY;
        double z0 = b.minZ;
        double x1 = b.maxX;
        double y1 = b.maxY;
        double z1 = b.maxZ;
        // 下面 4
        addWorldSegment(pose, camPos, x0, y0, z0, x1, y0, z0, color, widthPx);
        addWorldSegment(pose, camPos, x0, y0, z1, x1, y0, z1, color, widthPx);
        addWorldSegment(pose, camPos, x0, y0, z0, x0, y0, z1, color, widthPx);
        addWorldSegment(pose, camPos, x1, y0, z0, x1, y0, z1, color, widthPx);
        // 上面 4
        addWorldSegment(pose, camPos, x0, y1, z0, x1, y1, z0, color, widthPx);
        addWorldSegment(pose, camPos, x0, y1, z1, x1, y1, z1, color, widthPx);
        addWorldSegment(pose, camPos, x0, y1, z0, x0, y1, z1, color, widthPx);
        addWorldSegment(pose, camPos, x1, y1, z0, x1, y1, z1, color, widthPx);
        // 縦 4
        addWorldSegment(pose, camPos, x0, y0, z0, x0, y1, z0, color, widthPx);
        addWorldSegment(pose, camPos, x1, y0, z0, x1, y1, z0, color, widthPx);
        addWorldSegment(pose, camPos, x0, y0, z1, x0, y1, z1, color, widthPx);
        addWorldSegment(pose, camPos, x1, y0, z1, x1, y1, z1, color, widthPx);
        return true;
    }

    /** clip 空間で near (w>W_MIN) にクリップし、 透視除算してスクリーン px 線分を蓄積する。 */
    private static void storeClipped(Vector4f a, Vector4f b, int color, float widthPx) {
        float wa = a.w;
        float wb = b.w;
        if (wa <= W_MIN && wb <= W_MIN) {
            return; // 完全に背後 → 描かない
        }
        // 一端が背後なら near 平面 (w=W_MIN) との交点まで縮める (clip 空間で線形補間)。
        float ax = a.x;
        float ay = a.y;
        float aw = wa;
        float bx = b.x;
        float by = b.y;
        float bw = wb;
        if (wa <= W_MIN) {
            float t = (W_MIN - wa) / (wb - wa);
            ax = a.x + (b.x - a.x) * t;
            ay = a.y + (b.y - a.y) * t;
            aw = W_MIN;
        } else if (wb <= W_MIN) {
            float t = (W_MIN - wb) / (wa - wb);
            bx = b.x + (a.x - b.x) * t;
            by = b.y + (a.y - b.y) * t;
            bw = W_MIN;
        }
        // 透視除算 → NDC → スクリーン px (y 反転)。
        float sxa = (ax / aw * 0.5f + 0.5f) * vpW;
        float sya = (1f - (ay / aw * 0.5f + 0.5f)) * vpH;
        float sxb = (bx / bw * 0.5f + 0.5f) * vpW;
        float syb = (1f - (by / bw * 0.5f + 0.5f)) * vpH;
        // 両端が同じ画面外側 → 完全に画面外なので捨てる (安価な拒否)。
        if ((sxa < 0 && sxb < 0) || (sxa > vpW && sxb > vpW)
                || (sya < 0 && syb < 0) || (sya > vpH && syb > vpH)) {
            return;
        }
        if (segCount >= SEG_CAP) {
            if (!warnedCap) {
                warnedCap = true;
                VisualizeGateMod.LOGGER.warn(
                        "[visualizegate] shader-wire HUD overlay hit {} segment cap; extra lines dropped this frame.",
                        SEG_CAP);
            }
            return;
        }
        if (segCount >= sx1.length) {
            grow();
        }
        sx1[segCount] = sxa;
        sy1[segCount] = sya;
        sx2[segCount] = sxb;
        sy2[segCount] = syb;
        sw[segCount] = widthPx;
        scol[segCount] = color;
        segCount++;
    }

    private static void grow() {
        int n = sx1.length * 2;
        sx1 = java.util.Arrays.copyOf(sx1, n);
        sy1 = java.util.Arrays.copyOf(sy1, n);
        sx2 = java.util.Arrays.copyOf(sx2, n);
        sy2 = java.util.Arrays.copyOf(sy2, n);
        sw = java.util.Arrays.copyOf(sw, n);
        scol = java.util.Arrays.copyOf(scol, n);
    }

    /** HUD パス: 蓄積した 2D 線分を g.fill の太線で描く (ワールド描画後＝シェーダに干渉されない)。 */
    private static void flush(GuiGraphicsExtractor g) {
        if (!active || segCount == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // wire レンダラ側で既に hideGui/F3 を尊重して append しないが、 HUD 側でも二重に守る。
        if (mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()) {
            segCount = 0;
            return;
        }
        for (int i = 0; i < segCount; i++) {
            drawThickLine(g, sx1[i], sy1[i], sx2[i], sy2[i], scol[i], sw[i]);
        }
        segCount = 0;
    }

    /** 太線 (DDA・約 2px 刻みで太さ分の塗り)。 PointCloudScreen.drawSegment のドット流儀を太さ付きへ拡張。 */
    private static void drawThickLine(GuiGraphicsExtractor g, float ax, float ay, float bx, float by,
            int color, float widthPx) {
        int t = Math.max(1, Math.round(widthPx));
        int half = t / 2;
        float dx = bx - ax;
        float dy = by - ay;
        float len = Math.max(Math.abs(dx), Math.abs(dy));
        if (len <= 0f) {
            fillClamped(g, Math.round(ax) - half, Math.round(ay) - half, t, color);
            return;
        }
        int steps = Math.min((int) (len / 2f) + 1, 4096);
        float stepX = dx / steps;
        float stepY = dy / steps;
        float px = ax;
        float py = ay;
        for (int s = 0; s <= steps; s++) {
            fillClamped(g, Math.round(px) - half, Math.round(py) - half, t, color);
            px += stepX;
            py += stepY;
        }
    }

    /** ビューポートにクランプして t×t の塗り。 */
    private static void fillClamped(GuiGraphicsExtractor g, int x, int y, int t, int color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(vpW, x + t);
        int y1 = Math.min(vpH, y + t);
        if (x1 > x0 && y1 > y0) {
            g.fill(x0, y0, x1, y1, color);
        }
    }
}
//?} else {
/*public final class ShaderWireOverlay {
    private ShaderWireOverlay() {
    }

    // 旧世代 (1.21.10/1.21.11): projection Matrix4f を綺麗に取れないため HUD 射影経路は無効。
    //   OverlayDraw は従来のワールド quad 経路へフォールバックする (挙動不変)。
    public static void register() {
    }
}*/
//?}
