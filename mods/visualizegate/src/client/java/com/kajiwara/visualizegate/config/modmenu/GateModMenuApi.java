package com.kajiwara.visualizegate.config.modmenu;

import com.kajiwara.visualizegate.config.GateConfigScreen;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu エントリポイント (fabric.mod.json の "modmenu" entrypoint 経由で発見される)。
 *
 * <p>{@link #getModConfigScreenFactory()} が MOD 一覧の「設定」ボタンに割り当てられ、
 * 押下で {@link GateConfigScreen} を開く。 ModMenu API
 * ({@code com.terraformersmc.modmenu.api.*}) は ModMenu 16/17/18 で共通 (= 版橋渡し不要)。
 * ModMenu 未導入時は本クラスはロードされない (= ハード依存にしない)。
 */
public final class GateModMenuApi implements ModMenuApi {

    @Override
    public com.terraformersmc.modmenu.api.ConfigScreenFactory<?> getModConfigScreenFactory() {
        // parent は ModMenu の MOD リスト画面。 閉じると戻る先。
        return parent -> {
            try {
                return new GateConfigScreen(parent);
            } catch (Throwable t) {
                // 構築失敗時は null (= ModMenu は「設定なし」扱い・クラッシュしない)。
                return null;
            }
        };
    }
}
