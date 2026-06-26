package com.kajiwara.worldchange.world;

import java.util.Optional;

import com.kajiwara.worldchange.WorldChange;
import com.kajiwara.worldchange.core.WorldEntry;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 対象ワールドへの切替オーケストレーション (Minecraft API グルー)。
 *
 * <p>バニラの「選択ワールドで遊ぶ」と同一経路 {@code Minecraft.createWorldOpenFlows().openWorld(id, ..)} を
 * 再利用し、 そのロード遷移ごと継承する。 統合サーバー起動中に openWorld は二重起動できないため、
 * <b>先に現セッションを save 込みで離脱 ({@code disconnect}) → サーバー停止を確認してから openWorld</b> する
 * 二段構え。 停止確認のため END_CLIENT_TICK で「サーバー/レベルとも null」になった瞬間に openWorld する。
 */
public final class WorldSwitcher {

    /** 切替開始の判定結果 (実行はせず呼び出し側がフィードバックを出す)。 */
    public enum Outcome {
        STARTED,
        LOCKED,
        INCOMPATIBLE,
        ALREADY_THERE
    }

    // 次に開くワールドのフォルダ id (teardown 完了を tick で待つ)。 client thread からのみ触る。
    private static String pendingOpen;

    private WorldSwitcher() {
    }

    /** client 初期化時に teardown 完了監視 tick を登録する。 */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WorldSwitcher::onClientTick);
    }

    /**
     * 切替を判定し、 可能なら開始する。 ロック中/非互換/同一ワールドは開始せず該当 {@link Outcome} を返す。
     * 実際の openWorld は現セッション teardown 後に tick から行う。
     */
    public static Outcome begin(WorldEntry target) {
        Minecraft mc = Minecraft.getInstance();
        if (target.locked()) {
            return Outcome.LOCKED;
        }
        if (!target.compatible()) {
            return Outcome.INCOMPATIBLE;
        }
        Optional<String> current = currentWorldId();
        if (current.isPresent() && current.get().equalsIgnoreCase(target.folderId())) {
            return Outcome.ALREADY_THERE;
        }

        final String folderId = target.folderId();
        mc.execute(() -> {
            pendingOpen = folderId;
            if (mc.level != null) {
                // save 込みで現ワールド/接続を離脱。 タイトルを挟まないよう中継メッセージ画面を渡す。
                mc.disconnect(new GenericMessageScreen(
                        Component.translatable("worldchange.switching", folderId)), false);
            }
            // teardown 完了 (server/level とも null) は onClientTick が検出して openWorld する。
        });
        return Outcome.STARTED;
    }

    private static void onClientTick(Minecraft mc) {
        if (pendingOpen == null) {
            return;
        }
        // 統合サーバーとクライアントレベルの両方が落ちきってから新ワールドを開く (二重起動回避)。
        if (mc.getSingleplayerServer() != null || mc.level != null) {
            return;
        }
        String folderId = pendingOpen;
        pendingOpen = null;
        WorldChange.LOGGER.info("Opening world '{}'", folderId);
        // 失敗/中断時のフォールバックはタイトルへ (成功時はロード画面→ワールドへ遷移)。
        mc.createWorldOpenFlows().openWorld(folderId, () -> showTitle(mc));
    }

    /** タイトルへ戻す (26.2 で setScreen→setScreenAndShow に改名・stonecutter 一方向の代わりに条件分岐)。 */
    private static void showTitle(Minecraft mc) {
        //? if >=26.2 {
        /*mc.setScreenAndShow(new TitleScreen());*/
        //?} else {
        mc.setScreen(new TitleScreen());
        //?}
    }

    /** 現在ロード中のシングルプレイワールドのフォルダ id (= saves フォルダ名)。 SP でなければ空。 */
    public static Optional<String> currentWorldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mc.getSingleplayerServer()
                    .getWorldPath(LevelResource.ROOT).getFileName().toString());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
