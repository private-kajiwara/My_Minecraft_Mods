package com.kajiwara.omnichest.client.render;

import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.config.data.SearchConfig;
import com.mojang.blaze3d.vertex.PoseStack;
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;*/
//?}
import net.minecraft.world.phys.Vec3;

/**
 * 検索ハイライトのビーコン演出を「いつ・どのくらいの強さで」 出すかを決める統制レイヤ。
 *
 * <p>
 * {@link ChestHighlighter} から各ハイライト 1 件ごとに呼ばれ、 以下を行う:
 * <ol>
 *   <li>Config ({@link SearchConfig}) を見て beacon が有効か判定 (= OFF なら即 return)。</li>
 *   <li>ピンと同じ中心座標を <b>読み取り専用で</b> 再計算する (= ピン座標・anchor は変更しない)。</li>
 *   <li>カメラ距離で <b>distance culling</b> (遠すぎるビームは描かない)。</li>
 *   <li>基準不透明度 × ハイライトのフェード量 × 明滅パルス × 距離フェード で alpha を合成。</li>
 *   <li>上方へ向けて alpha を 0 に落とす縦グラデーション (= alpha fade) を指定して
 *       {@link SearchBeaconRenderer#drawBeamImmediate} に委譲。</li>
 * </ol>
 *
 * <p>
 * <b>不変条件</b>: 本レイヤはハイライトの中心座標・alpha を読むだけで、
 * ピン座標・Overlay anchor・検索ロジック・Tracking system のいずれも書き換えない。
 * あくまで「ピンの補助演出」 として独立に描画を足すだけ。
 */
public final class BeaconEffectLayer {

    /** ビーム上端の、 チェスト天面からの高さ (ブロック)。 通常ビルド限界を越えて上空へ抜ける値。 */
    private static final double BEAM_HEIGHT = 320.0;

    /** distance culling 距離 (m)。 これより遠いハイライトのビームは描かない (= 描画最適化)。 */
    private static final double CULL_DISTANCE = 256.0;

    /** 距離フェード開始距離 (m)。 これを超えると徐々に薄くする。 */
    private static final double FADE_NEAR = 48.0;
    /** 距離フェードで最終的に残す alpha 倍率の下限 (= 遠くでも完全には消さない)。 */
    private static final float FADE_FLOOR = 0.45f;

    /** 明滅パルスの周期 (ms)。 ゆっくりした呼吸 (slow pulse)。 */
    private static final long PULSE_PERIOD_MS = 2600L;
    /** パルスの最小 alpha 倍率 (= 一番暗いとき)。 */
    private static final float PULSE_MIN = 0.72f;

    private BeaconEffectLayer() {
    }

    /**
     * 1 つのハイライトに対してビームを <b>immediate-mode</b> で描く (= 描画条件を満たさなければ何もしない)。
     *
     * <p>
     * <b>なぜ immediate-mode か</b>: submit 収集 ({@code SubmitNodeCollector}) のビームは、 バニラが
     * 半透明地形 (水) を全 features パスより後に描くため必ず水に上書きされる。 そこで水描画後の
     * ステージ ({@link ChestHighlighter#onAfterWaterRender}) で {@code bufferSource} に直接描き、
     * その場で flush することで水の上に出す。 ビームの depth test (LEQUAL) は不変なので固体地形へは
     * 従来どおり遮蔽される (= バニラビーコン挙動を維持)。
     *
     * @param bufferSource   immediate-mode 描画先 (= 水描画後ステージの bufferSource / consumers)
     * @param matrices       camera-relative の PoseStack
     * @param cxWorld,czWorld ビーム中心のワールド XZ
     * @param camPos         カメラのワールド座標
     * @param themeRgb       テーマ色 (= ピン / ボックスと共通の 0xRRGGBB)
     * @param highlightAlpha ハイライト全体のフェード量 (0..1、 = 消滅直前は小さくなる)
     * @param baseWorldY     ビーム下端のワールド Y (= ピンの一番真上)
     */
    //? if <26.2 {
    public static void drawWorld(net.minecraft.client.renderer.MultiBufferSource.BufferSource bufferSource,
            PoseStack matrices, double cxWorld, double czWorld, Vec3 camPos, int themeRgb,
            float highlightAlpha, double baseWorldY) {
        if (highlightAlpha <= 0.001f) return;

        SearchConfig cfg;
        try {
            cfg = ConfigManager.get().search;
        } catch (Throwable t) {
            return; // 設定が読めない時は描かない (= 安全側)。
        }
        if (cfg == null || !cfg.enableBeacon) return;

        // baseWorldY = ピンの一番真上 (引数で受け取る)。 ここでチェスト天面からは立ち上げない。

        // ─── distance culling ───
        double dx = cxWorld - camPos.x;
        double dyToBase = baseWorldY - camPos.y;
        double dz = czWorld - camPos.z;
        double dist = Math.sqrt(dx * dx + dyToBase * dyToBase + dz * dz);
        if (dist > CULL_DISTANCE) return;

        // ─── alpha 合成 ───
        float baseAlpha = clamp01(cfg.beaconOpacity / 100.0f) * clamp01(highlightAlpha);
        if (cfg.beaconAnimation) {
            baseAlpha *= pulse();
        }
        if (cfg.beaconDistanceFade) {
            baseAlpha *= distanceFade(dist);
        }
        if (baseAlpha <= 0.004f) return;

        // 上端は透明へ (= alpha fade で「ぼんやり消える」 ビーコン感)。
        float bottomAlpha = baseAlpha;
        float topAlpha = 0.0f;

        float width = (float) Math.max(0.05, Math.min(1.0, cfg.beaconWidth));

        // camera-relative 座標へ変換して描く。
        float cx = (float) (cxWorld - camPos.x);
        float cz = (float) (czWorld - camPos.z);
        float y0 = (float) (baseWorldY - camPos.y);
        float y1 = (float) (baseWorldY + BEAM_HEIGHT - camPos.y);

        SearchBeaconRenderer.drawBeamImmediate(bufferSource, matrices, cx, cz, y0, y1,
                themeRgb, bottomAlpha, topAlpha, width);
    }
    //?}

    //? if >=26.2 {
    /*// 26.2: immediate (MultiBufferSource) 撤去版。 alpha/culling ロジックは drawWorld と同一で、
    //   最終描画だけ SubmitNodeCollector への submit (バニラ beacon beam = Iris-safe) に替える。 engine が
    //   translucent フェーズ (= 水後) に描くため、 旧「水後 immediate」 ステージは不要。
    public static void drawWorldSubmit(SubmitNodeCollector queue,
            PoseStack matrices, double cxWorld, double czWorld, Vec3 camPos, int themeRgb,
            float highlightAlpha, double baseWorldY) {
        if (highlightAlpha <= 0.001f) return;

        SearchConfig cfg;
        try {
            cfg = ConfigManager.get().search;
        } catch (Throwable t) {
            return;
        }
        if (cfg == null || !cfg.enableBeacon) return;

        double dx = cxWorld - camPos.x;
        double dyToBase = baseWorldY - camPos.y;
        double dz = czWorld - camPos.z;
        double dist = Math.sqrt(dx * dx + dyToBase * dyToBase + dz * dz);
        if (dist > CULL_DISTANCE) return;

        float baseAlpha = clamp01(cfg.beaconOpacity / 100.0f) * clamp01(highlightAlpha);
        if (cfg.beaconAnimation) {
            baseAlpha *= pulse();
        }
        if (cfg.beaconDistanceFade) {
            baseAlpha *= distanceFade(dist);
        }
        if (baseAlpha <= 0.004f) return;

        float bottomAlpha = baseAlpha;
        float topAlpha = 0.0f;
        float width = (float) Math.max(0.05, Math.min(1.0, cfg.beaconWidth));

        float cx = (float) (cxWorld - camPos.x);
        float cz = (float) (czWorld - camPos.z);
        float y0 = (float) (baseWorldY - camPos.y);
        float y1 = (float) (baseWorldY + BEAM_HEIGHT - camPos.y);

        SearchBeaconRenderer.submitBeam(queue, matrices, cx, cz, y0, y1,
                themeRgb, bottomAlpha, topAlpha, width);
    }*/
    //?}

    /** ゆっくりした明滅倍率 (PULSE_MIN..1.0)。 時間ベースなので描画負荷に依らず一定速度。 */
    private static float pulse() {
        double t = (System.currentTimeMillis() % PULSE_PERIOD_MS) / (double) PULSE_PERIOD_MS;
        // 0..1 の滑らかな山 (sin)。
        float wave = (float) (0.5 * (1.0 + Math.sin(t * 2.0 * Math.PI)));
        return PULSE_MIN + (1.0f - PULSE_MIN) * wave;
    }

    /** 距離フェード倍率 (FADE_FLOOR..1.0)。 近距離は 1.0、 遠ざかるほど下限へ漸近。 */
    private static float distanceFade(double dist) {
        if (dist <= FADE_NEAR) return 1.0f;
        double span = CULL_DISTANCE - FADE_NEAR;
        double t = Math.min(1.0, (dist - FADE_NEAR) / Math.max(1.0, span));
        return (float) (1.0 - (1.0 - FADE_FLOOR) * t);
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }
}
