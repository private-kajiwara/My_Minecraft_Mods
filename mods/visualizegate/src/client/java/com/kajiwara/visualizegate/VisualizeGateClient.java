package com.kajiwara.visualizegate;

import com.kajiwara.visualizegate.client.command.VgCommands;
import com.kajiwara.visualizegate.client.keybind.GateKeyBindings;
import com.kajiwara.visualizegate.client.render.BackCalcRenderer;
import com.kajiwara.visualizegate.client.render.CornerIconRenderer;
import com.kajiwara.visualizegate.client.render.GateGraphRenderer;
import com.kajiwara.visualizegate.client.render.GateNameLabelRenderer;
import com.kajiwara.visualizegate.client.render.HologramFrameRenderer;
import com.kajiwara.visualizegate.client.render.OverlayDraw;
import com.kajiwara.visualizegate.client.render.PortalBoxRenderer;
import com.kajiwara.visualizegate.client.render.PortalInfoCardRenderer;
import com.kajiwara.visualizegate.client.render.PointCloudHudRenderer;
import com.kajiwara.visualizegate.client.render.PortalLinkRenderer;
import com.kajiwara.visualizegate.client.render.SearchDomeRenderer;
import com.kajiwara.visualizegate.client.render.VgDockRenderer;
import com.kajiwara.visualizegate.config.GateConfigManager;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.scan.PortalIndex;
import com.kajiwara.visualizegate.state.VgOverlayState;
import com.kajiwara.visualizegate.terrain.TerrainStore;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * クライアント側エントリポイント。
 *
 * <p>サブシステムを登録する:
 * <ul>
 *   <li>{@link PortalIndex} — ClientChunkEvents.CHUNK_LOAD/UNLOAD で増分更新 + 定期再検証/近傍再スキャン
 *       (内部で {@code ClientPortalScanner} を呼ぶ)。</li>
 *   <li>{@link PortalBoxRenderer} — PortalIndex の各ポータル AABB に枠を描画 (水後ステージ)。</li>
 *   <li>{@link GateKeyBindings} — メニュー起動キー (既定 V) の登録と tick 監視。</li>
 *   <li>{@link CornerIconRenderer} — 画面右下の小アイコン (HUD パス・目印のみ)。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class VisualizeGateClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 設定をディスクからロードして GateMenuState へ反映 (= 描画/HUD が正しい初期値で始まる)。
        GateConfigManager.load();
        PortalIndex.register();
        // 世代横断のポータル記憶 (機能2/1 の前提)。 PortalIndex の後に登録し、 在ディメンション中に
        // 確定レコードを昇格保存・整合する (描画はまだ無し＝記憶基盤のみ)。
        PortalMemory.register();
        // 地形カラム代表点の蓄積 (点群ポップアップの地形素材)。 PortalMemory の後に登録し、
        // world-id 確定後に CHUNK_LOAD でサンプリングする (描画はまだ無し＝蓄積基盤のみ)。
        TerrainStore.register();
        // シェーダ (Iris) 時のワイヤー描き先＝レベル (Iris ラップ) バッファを毎フレーム capture する (>=26.1)。
        //   OverlayDraw を使う各 wire レンダラより前に発火させたいので、 それらより先に register する。
        OverlayDraw.register();
        PortalBoxRenderer.register();
        // 機能2: リンク状態ベクターライン (記憶された別次元ポータルへズレ線・緑/赤/灰)。
        PortalLinkRenderer.register();
        // 機能1: ホログラム枠 v1 (LINKED の「ズレ無し設置位置」に金枠・水後ステージ・Mixin 0)。
        HologramFrameRenderer.register();
        // 機能3: 探索ドーム v1 (リンク検索範囲のワイヤフレーム＋範囲内の他ゲート混線強調・水後ステージ・Mixin 0)。
        SearchDomeRenderer.register();
        // 機能㉕: `/vg back-calculate` の予測ワイヤーフレーム (現在ディメンション要素のみ・水後ステージ・Mixin 0)。
        BackCalcRenderer.register();
        GateKeyBindings.register();
        // ㉟ `/vg` オーバーレイ状態 (既定 OFF・切断で全リセット・永続なし)。 コマンド/HUD/in-world が参照。
        VgOverlayState.register();
        // ㉕/㉟/㊷ クライアント専用 `/vg` コマンド (back-calculate / clean / point-cloud / visualize /
        //      perf / dock / help・サーバー非依存)。
        VgCommands.register();
        CornerIconRenderer.register();
        // UX 層 (純追加・HUD パス): 自動インフォカード (注視/所持トリガで表示)。
        // ㊸B 枠付き凡例ボックス (LegendOverlayRenderer) は撤去＝凡例はドック展開 (フルメニュー) に一本化。
        PortalInfoCardRenderer.register();
        // ㉟C `/vg visualize` 全ゲート関係 in-world ワイヤーフレーム (既定 OFF・5 状態色・距離カリング・据置)。
        GateGraphRenderer.register();
        // ゲート名ラベル (各実ポータル上に名前を在世界表示・SEE_THROUGH 壁越し・状態色・既定 ON・/vg names)。
        GateNameLabelRenderer.register();
        // ㊲ B-F3 集約ドック (左上・畳/展・パフォ/状態/注記)。 ⑤④ 点群は別パネルへ移設したのでドックからは外れた。
        VgDockRenderer.register();
        // ⑤④ 案C 点群オーバーレイ (右下・[V] の真上・独立 HUD パネル)。 /vg point-cloud ON で表示。
        PointCloudHudRenderer.register();
        VisualizeGateMod.LOGGER.info("VisualizeGate client initialized (portal scan + box renderer + menu UI).");
    }
}
