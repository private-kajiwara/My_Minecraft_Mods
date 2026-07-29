package com.kajiwara.hyperslice;

import com.kajiwara.hyperslice.entity.ClientHyperEntities;
import com.kajiwara.hyperslice.entity.HyperEntityRenderer;
import com.kajiwara.hyperslice.hud.SliceHudRenderer;
import com.kajiwara.hyperslice.observer.ObserverW;
import com.kajiwara.hyperslice.observer.ObserverWCommands;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * クライアント側エントリポイント。
 *
 * <p>クライアントが持つのは「現在の w を表示する HUD」と
 * 「4 次元エンティティの断面描画」だけ。 生成・移動・物理はすべてサーバ側
 * ({@link HyperSlice}) の仕事。
 */
public class HyperSliceClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SliceHudRenderer.register();

        // 4 次元エンティティ: 受信 → 保持 → 断面描画
        ClientHyperEntities.register();
        HyperEntityRenderer.register();

        // 【診断実験】観測面 w の連続移動 (キー + /observerw)。
        // ObserverW.EXPERIMENT_ENABLED=false のときは両方とも何も登録しない。
        ObserverW.register();
        ObserverWCommands.register();

        // 切断時に持ち越さない (別ワールドへ入ったときに古い球が残るのを防ぐ)。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientHyperEntities.clear());
    }
}
