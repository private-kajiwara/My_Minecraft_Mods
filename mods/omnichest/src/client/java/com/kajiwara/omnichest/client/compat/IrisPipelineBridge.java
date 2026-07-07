package com.kajiwara.omnichest.client.compat;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Iris の {@code IrisApi.assignPipeline(RenderPipeline, IrisProgram)} を <b>reflection 経由</b>で呼ぶブリッジ。
 *
 * <p>
 * <b>目的</b>: OmniChest の x-ray ワイヤー ({@link com.kajiwara.omnichest.client.render.WireHighlightRenderer})
 * は自前の {@code core/rendertype_lines} カスタム {@link RenderPipeline} (NO_DEPTH_TEST) で線を描く。
 * これは Iris が知らないパイプラインなので、 shader pack 有効時は Iris の描画パスに乗らず<b>消える</b>。
 * {@code assignPipeline(pipeline, IrisProgram.LINES)} でこのカスタムパイプラインを Iris の LINES プログラムに
 * 割り当てておくと、 Iris が shader 下でも捕捉して描く (= バニラの線と同じ扱い)。 パイプラインが
 * NO_DEPTH_TEST を保持しているため、 Iris が深度状態を尊重すれば shader 下でも x-ray になる。
 *
 * <p>
 * <b>方針</b> ({@link ShaderCompatManager} と同じ):
 * <ul>
 *   <li>Iris API クラスへの<b>直接 import を持たない</b> (= Iris 非搭載環境で {@link NoClassDefFoundError} を
 *       出さないため全て reflection)。 配布 jar / コンパイル classpath には Iris が一切混入しない。</li>
 *   <li>class 名の版差 (irisshaders / coderbot) を候補で試す。 取得失敗 / 例外時は全て<b>no-op</b>
 *       (= shader 無し扱い = 既存の非 shader 描画がそのまま出る)。</li>
 *   <li>解決結果は 1 度だけ probe してキャッシュ。</li>
 * </ul>
 */
public final class IrisPipelineBridge {

    private IrisPipelineBridge() {
    }

    private static volatile boolean probed;
    private static volatile @Nullable Object apiInstance;
    /** {@code IrisApi#assignPipeline(RenderPipeline, IrisProgram)}。 */
    private static volatile @Nullable Method assignMethod;
    /** {@code IrisProgram.LINES} の enum 値。 */
    private static volatile @Nullable Object linesProgram;

    /**
     * カスタム lines パイプラインを Iris の {@code LINES} プログラムへ割り当てる。
     * Iris 非搭載 / API 差異 / 例外時は no-op (= 既存挙動を一切壊さない)。
     * パイプライン構築時に 1 度呼べばよい。
     */
    public static void assignLines(RenderPipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        resolve();
        Object api = apiInstance;
        Method assign = assignMethod;
        Object lines = linesProgram;
        if (api == null || assign == null || lines == null) {
            return;
        }
        try {
            assign.invoke(api, pipeline, lines);
        } catch (Throwable ignored) {
            // Iris 側の例外でこちらを落とさない (= 安全側)。
        }
    }

    private static void resolve() {
        if (probed) {
            return;
        }
        synchronized (IrisPipelineBridge.class) {
            if (probed) {
                return;
            }
            probed = true;

            String[] apiClasses = {
                    "net.irisshaders.iris.api.v0.IrisApi",
                    "net.coderbot.iris.api.v0.IrisApi",
            };
            String[] programClasses = {
                    "net.irisshaders.iris.api.v0.IrisProgram",
                    "net.coderbot.iris.api.v0.IrisProgram",
            };
            for (int i = 0; i < apiClasses.length; i++) {
                try {
                    Class<?> apiClass = Class.forName(apiClasses[i]);
                    Class<?> programClass = Class.forName(programClasses[i]);
                    Object instance = apiClass.getMethod("getInstance").invoke(null);
                    if (instance == null) {
                        continue;
                    }
                    Object lines = programClass.getField("LINES").get(null);
                    Method assign = apiClass.getMethod("assignPipeline", RenderPipeline.class, programClass);
                    apiInstance = instance;
                    linesProgram = lines;
                    assignMethod = assign;
                    return;
                } catch (Throwable ignored) {
                    // 次の候補を試す。
                }
            }
        }
    }
}
