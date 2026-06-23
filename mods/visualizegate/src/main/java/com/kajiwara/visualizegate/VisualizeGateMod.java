package com.kajiwara.visualizegate;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 共通 (main) エントリポイント。
 *
 * <p>VisualizeGate はクライアント専用 (ネザーゲート視覚化) なので、 サーバ/クライアント
 * 双方で必要な処理はほぼ無い。 ここではロガーと MOD_ID 定数だけを公開する。
 * 実体のロジックは {@link VisualizeGateClient} 側に置く。
 */
public class VisualizeGateMod implements ModInitializer {

    public static final String MOD_ID = "visualizegate";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("VisualizeGate ({}) initialized.", version());
    }

    /** 実 mod_version を Fabric メタから取得 (ハードコード回避)。 取得不能なら "?"。 */
    private static String version() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }
}
