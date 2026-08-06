package com.kajiwara.omnichest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OmniChest implements ModInitializer {
    // Mod IDは定数にしておくと、他のクラスから呼び出しやすくなります
    public static final String MOD_ID = "omnichest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** メタデータが読めなかったときの表示。 バージョンを詐称しないための値。 */
    private static final String UNKNOWN_VERSION = "unknown";

    @Override
    public void onInitialize() {
        // アイテムやブロックの登録など、サーバーとクライアントの両方で必要な処理をここに書きます
        // 今回のModは主にクライアント側(GUI)の処理が中心になるため、ここはシンプルになります
        LOGGER.info("OmniChest ({}) が初期化されました！", modVersion());
    }

    /**
     * 自分自身のバージョンを Fabric Loader のモッドメタデータから取得する
     * (= {@code gradle.properties} の {@code mod_version} が fabric.mod.json 経由で入る値)。
     *
     * <p>
     * ソースに版番号を書かないので、 bump しても文字列を直す必要がない。
     * 取得に失敗しても起動を止めないよう、 例外は握って {@link #UNKNOWN_VERSION} を返す。
     *
     * <p>
     * 呼び出し位置について: 本メソッドは entrypoint の中から呼ばれる。 Loader はこの
     * entrypoint 自体を当該 ModContainer のメタデータから解決しているので、 その時点で
     * コンテナは必ず構築済み。 取得方法は client 側の {@code ModDetectionService#modVersion}
     * と同じ経路に揃えてある。
     */
    private static String modVersion() {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(ModContainer::getMetadata)
                    .map(m -> m.getVersion().getFriendlyString())
                    .orElse(UNKNOWN_VERSION);
        } catch (Throwable t) {
            return UNKNOWN_VERSION;
        }
    }
}