package com.kajiwara.visualizegate.state;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * ㉟ `/vg` オーバーレイのトグル状態 (client・インメモリ・<b>永続なし</b>)。
 *
 * <p>{@link GateMenuState} (メニュー UI のトグル) とは別系統で、 コマンド `/vg <sub>` で点けるゲームプレイ中の
 * オーバーレイ群 (右下点群 HUD / 全ゲート関係ワイヤーフレーム / GPU・CPU グラフ) の on/off を集約する。
 * <b>全フラグ既定 OFF</b>＝未操作なら何も出ない。 複数同時 ON 可。 切断で全 OFF にリセット (セッション跨ぎで残さない)。
 *
 * <p>{@code /vg clean} と切断時 ({@link ClientPlayConnectionEvents#DISCONNECT}) が {@link #clearAll()} を呼ぶ＝
 * どのモードにも効く一括停止。 各オーバーレイのレンダラはここを参照し、 OFF なら即コストが消える。
 */
public final class VgOverlayState {

    private static boolean pointCloud = false; // 点群サムネ (ドック内サブセクション・/vg point-cloud)
    private static boolean visualize = false;  // 全ゲート関係ワイヤーフレーム (in-world・凡例はドック展開で)

    // ㊲ B-F3 ドックの展開状態 (true=展開フルメニュー / false=畳スリムバー)。 専用キーバインド + `/vg dock` で切替。
    // ㊸A 展開＝常にフルメニュー (perf [フレーム+CPU 両グラフ+注記] ＋ ゲート状態 5 色 ＋ 注記 4・フラグ非依存)。
    //     点群サムネのみ pointCloud 連動で追加。 CpuSampler はこの展開中 (perf 表示中) だけ稼働する。
    private static boolean dockExpanded = false;

    private VgOverlayState() {
    }

    /** 切断で全 OFF (永続なし・既定 OFF へ戻す)。 {@code VisualizeGateClient} から登録。 */
    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearAll());
    }

    public static boolean isPointCloud() {
        return pointCloud;
    }

    /**
     * ⑤⑤ 点群パネルの表示フラグを直接設定 (ドック auto-expand なし)。 {@code only} 系がソロ化時に使う＝
     * {@code togglePointCloud()} の副作用 (dockExpanded 書換) を避けてパネルだけ点ける。
     */
    public static void setPointCloud(boolean v) {
        pointCloud = v;
    }

    /**
     * ⑤⑤ 実効「点群ソロ」(cloud-only)。 パネルが出ている (pointCloud) かつ cloud-only 設定 ON のときだけ true。
     * 点群パネル以外の VG HUD レンダラがこれを見て early-return する (単一の抑止述語・dockExpanded は不変)。
     */
    public static boolean isCloudSolo() {
        return pointCloud && com.kajiwara.visualizegate.state.PointCloudViewState.isCloudOnly();
    }

    public static boolean togglePointCloud() {
        pointCloud = !pointCloud;
        // ㊸A 点群はドック内サブセクション＝有効化したら展開して見せる (自動展開)。 OFF はフルメニューを残す (畳まない)。
        if (pointCloud) {
            setDockExpanded(true);
        }
        return pointCloud;
    }

    public static boolean isVisualize() {
        return visualize;
    }

    public static boolean toggleVisualize() {
        // ㊸A visualize はワイヤーフレーム＝in-world。 ドックは可視 (anyActive) でスリムバー、 凡例は手動展開で。 自動展開しない。
        visualize = !visualize;
        return visualize;
    }

    // ── ㊲/㊸ ドック展開状態 (展開＝フルメニュー)。 CpuSampler は展開中だけ稼働。 ──
    public static boolean isDockExpanded() {
        return dockExpanded;
    }

    public static boolean toggleDock() {
        setDockExpanded(!dockExpanded);
        return dockExpanded;
    }

    /**
     * ㊸A 展開状態を設定し、 CpuSampler を連動させる (展開中＝perf 表示中だけ 1Hz 稼働・畳み/clean で停止)。
     * フレームグラフは描画ループ由来で軽いので常時 (展開時のみ描画)。 描画スレッドでは CPU を同期取得しない (㊱A)。
     */
    private static void setDockExpanded(boolean v) {
        if (dockExpanded == v) {
            return;
        }
        dockExpanded = v;
        if (v) {
            CpuSampler.get().start();
        } else {
            CpuSampler.get().stop();
        }
    }

    /** いずれかの `/vg` セクションが有効か (ドックの表示可否＝何か点いていれば畳バーを出す)。 ㊸A perf は廃止。 */
    public static boolean anyActive() {
        return pointCloud || visualize;
    }

    /** ドックを描くか (展開済み or 何か有効)。 全 OFF かつ未展開＝何も出ない (既定で静か)。 */
    public static boolean dockVisible() {
        return dockExpanded || anyActive();
    }

    /** 全 `/vg` オーバーレイを OFF (`/vg clean`・切断で呼ぶ)。 1 つでも点いていたら true。 ㊲E ドックも畳んで非表示化。 */
    public static boolean clearAll() {
        boolean any = pointCloud || visualize || dockExpanded;
        pointCloud = false;
        visualize = false; // ㊲E in-world visualize も停止 (GateGraphRenderer がこのフラグで即 early-return)
        setDockExpanded(false); // ㊸A 畳む＝CpuSampler 停止 ＋ 全 OFF なら dockVisible() が false ＝ドック非表示
        CpuSampler.get().stop(); // ㊱A 念のため確実に停止 (idempotent)。
        return any;
    }
}
