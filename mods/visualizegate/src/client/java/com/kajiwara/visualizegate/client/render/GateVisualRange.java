package com.kajiwara.visualizegate.client.render;

import com.kajiwara.visualizegate.state.GateMenuState;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * ゲート可視化 (枠/線/ドーム) の<b>最大表示距離</b>判定 (水平・block)。
 *
 * <p>設定値 ({@link GateMenuState#getGateRenderDistanceM()}) を<b>実効描画距離</b>でクランプした上限を返す。
 * バニラの描画距離を超えてゲート枠だけ描いても地形が無く無意味なため、 m 換算した実効描画距離を天井にする
 * (= ユーザが大きな値を選んでもチャンク描画の外までは描かない)。 設定 < 実効描画距離なら設定値が効く。
 *
 * <p>判定は<b>水平距離</b> (XZ)＝既存の {@code GateGraphRenderer} 等の距離カリングと同じメトリック。
 * 各レンダラは {@link #cap(Minecraft)} を 1 フレーム 1 回求め、 ポータルごとに {@link #withinCap} で間引く。
 */
public final class GateVisualRange {

    private GateVisualRange() {
    }

    /** この frame の最大表示距離 (block・水平)。 設定値と実効描画距離(m) の小さい方。 */
    public static double cap(Minecraft mc) {
        double cfg = GateMenuState.getGateRenderDistanceM();
        double eff = effectiveMeters(mc);
        return Math.min(cfg, eff);
    }

    /** world (x,z) がカメラから {@code cap} 以内か (水平距離・二乗比較で sqrt 回避)。 */
    public static boolean withinCap(Vec3 cam, double x, double z, double cap) {
        double dx = x - cam.x;
        double dz = z - cam.z;
        return dx * dx + dz * dz <= cap * cap;
    }

    /** 実効描画距離の m 換算 (chunks × 16)。 取得不可は穏当な既定。 */
    private static double effectiveMeters(Minecraft mc) {
        try {
            return Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0);
        } catch (Throwable ignored) {
            return 256.0;
        }
    }
}
