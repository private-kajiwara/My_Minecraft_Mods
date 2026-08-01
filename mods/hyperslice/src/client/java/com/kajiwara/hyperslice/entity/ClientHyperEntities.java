package com.kajiwara.hyperslice.entity;

import java.util.List;

import com.kajiwara.hyperslice.core.CrossSection;
import com.kajiwara.hyperslice.core.HyperEntityType;
import com.kajiwara.hyperslice.core.SliceRegistry;
import com.kajiwara.hyperslice.net.HyperEntitySyncPayload;
import com.kajiwara.hyperslice.observer.ObserverW;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * クライアント側の 4 次元エンティティ保持。
 *
 * <p>サーバから届いたスナップショットをそのまま置き換えるだけ (v0.2 は差分なしの全送信)。
 * 断面半径はここでは持たず、 <b>描画のたびに現在の観測面から算出する</b>
 * ({@link #planeW()})。 プレイヤーがスライスを移動した瞬間に、 次のパケットを待たずに
 * 断面が正しく変わる。
 */
public final class ClientHyperEntities {

    /** 1 体分の描画用ビュー。 */
    public record View(HyperEntityType type, double x, double y, double z, double w) {

        public double dw(double planeW) {
            return w - planeW;
        }

        /** 観測面での断面半径 [ブロック]。 交差していなければ 0。 */
        public double radius(double planeW) {
            return CrossSection.radius(type.wThickness(), dw(planeW))
                    * HyperEntityType.RENDER_SCALE;
        }
    }

    private static volatile List<View> current = List.of();

    private ClientHyperEntities() {
    }

    /** {@code HyperSliceClient} から 1 回だけ呼ぶ。 */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                HyperEntitySyncPayload.TYPE,
                (payload, context) -> {
                    List<View> views = payload.entries().stream()
                            .map(ClientHyperEntities::toView)
                            .filter(v -> v != null)
                            .toList();
                    // ハンドラはネットワークスレッドで走り得るので、 単に差し替えるだけにする
                    // (描画スレッドは volatile な参照を読むだけ = ロック不要)。
                    current = views;
                });
    }

    private static View toView(HyperEntitySyncPayload.Entry e) {
        HyperEntityType type = HyperEntityType.byOrdinal(e.typeOrdinal());
        if (type == null) {
            // 未知の型 (バージョン不一致など) は黙って捨てる。 例外を投げない。
            return null;
        }
        return new View(type, e.x(), e.y(), e.z(), e.w());
    }

    /** 直近に受信したスナップショット。 */
    public static List<View> snapshot() {
        return current;
    }

    /** 接続断・次元移動時に消す。 */
    public static void clear() {
        current = List.of();
    }

    /**
     * 現在のクライアントの観測超平面 w。
     *
     * <p>HyperSlice のスライス内にいなければ {@link Double#NaN}。
     *
     * <p>方式B では {@code ObserverW.get()} が<b>サーバーから配られた連続 w</b> を返す
     * (地形が切り直されている w そのもの) ので、 このメソッドの形は方式A のときから
     * 変わっていない。 「方式B へ移行しても呼び出し側は無変更で通る」という設計が
     * そのとおりになった箇所。
     */
    public static double planeW() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return Double.NaN;
        }
        Identifier id = level.dimension().identifier();
        if (!SliceRegistry.NAMESPACE.equals(id.getNamespace())) {
            return Double.NaN;
        }
        int slice = SliceRegistry.wFromPath(id.getPath());
        if (slice < 0) {
            return Double.NaN;
        }
        // ObserverW.EXPERIMENT_ENABLED=false のときは下の三項が方式A の
        // observationPlane(slice) = slice + 0.5 に落ちる (= 方式B 導入前と完全一致)。
        return ObserverW.EXPERIMENT_ENABLED
                ? ObserverW.get()
                : CrossSection.observationPlane(slice);
    }
}
