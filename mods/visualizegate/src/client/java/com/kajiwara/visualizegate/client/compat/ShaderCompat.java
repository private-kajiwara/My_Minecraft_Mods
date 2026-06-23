package com.kajiwara.visualizegate.client.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.kajiwara.visualizegate.VisualizeGateMod;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Iris シェーダが現在<b>有効</b>かどうかを推定するソフトコンパチ層 (OmniChest ShaderCompatManager の軽量踏襲)。
 *
 * <p><b>ソフト依存</b>: Iris API クラスへの<b>直接 import を持たない</b> (非搭載環境で {@link NoClassDefFoundError}
 * を出さないため全アクセスを reflection 経由)。 まず {@code FabricLoader.isModLoaded("iris")} で存在を確認し、
 * 在る場合のみ {@code IrisApi.getInstance().isShaderPackInUse()} を MethodHandle で解決・キャッシュして呼ぶ。
 * 取得失敗 / 例外 / 非搭載は全て<b>「shader 未有効」</b>＝false (= 既存の通常描画パスを使う安全側)。
 *
 * <p>Iris/Sodium への Gradle 依存は<b>持たない</b> (= 配布物に含めない・dev でも必須化しない)。 検出が
 * ソフトなので、 Iris の無いノードでも常に通常パスで動く。
 */
public final class ShaderCompat {

    private static volatile boolean probed;
    private static volatile MethodHandle shaderInUseHandle; // null = 解決失敗 / 未搭載
    private static volatile boolean loggedOnce;

    private ShaderCompat() {
    }

    /** Iris が「shader pack 使用中」と報告するなら true。 非搭載/失敗時は false。 */
    public static boolean isShaderPackInUse() {
        MethodHandle mh = resolve();
        if (mh == null) {
            return false;
        }
        try {
            return (boolean) mh.invoke();
        } catch (Throwable t) {
            return false; // Iris の API 例外でこちらが落ちないように broad catch。
        }
    }

    private static MethodHandle resolve() {
        if (probed) {
            return shaderInUseHandle;
        }
        synchronized (ShaderCompat.class) {
            if (probed) {
                return shaderInUseHandle;
            }
            probed = true;
            if (!FabricLoader.getInstance().isModLoaded("iris")) {
                return null; // ソフト: Iris 不在なら reflection も試さない。
            }
            // Iris は API バージョンで class 名を変えた経緯がある → 候補を順に試す。
            String[] candidates = {
                    "net.irisshaders.iris.api.v0.IrisApi",
                    "net.coderbot.iris.api.v0.IrisApi"
            };
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            for (String fqcn : candidates) {
                try {
                    Class<?> apiClass = Class.forName(fqcn);
                    MethodHandle getInstance = lookup.findStatic(apiClass, "getInstance",
                            MethodType.methodType(apiClass));
                    Object instance = getInstance.invoke();
                    if (instance == null) {
                        continue;
                    }
                    MethodHandle isInUse = lookup.findVirtual(apiClass, "isShaderPackInUse",
                            MethodType.methodType(boolean.class));
                    shaderInUseHandle = isInUse.bindTo(instance);
                    if (!loggedOnce) {
                        loggedOnce = true;
                        VisualizeGateMod.LOGGER.info(
                                "[visualizegate][compat] Iris detected — shader-safe quad overlay path enabled.");
                    }
                    return shaderInUseHandle;
                } catch (Throwable ignored) {
                    // 次の候補へ。
                }
            }
            return null;
        }
    }
}
