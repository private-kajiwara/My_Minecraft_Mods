package com.kajiwara.worldchange.modmenu;

import com.kajiwara.worldchange.ui.WorldPickerScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu エントリポイント (fabric.mod.json の "modmenu" entrypoint 経由で発見される)。
 *
 * <p>MOD 一覧の「設定」ボタンに {@link WorldPickerScreen} (vanilla ウィジェットのみのワールド一覧) を割り当てる。
 * ModMenu API ({@code com.terraformersmc.modmenu.api.*}) は ModMenu 16〜20 で共通 (= 版橋渡し不要)。
 * ModMenu 未導入時は本クラスはロードされない (= ハード依存にしない)。
 */
public final class WorldChangeModMenuApi implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            try {
                return new WorldPickerScreen(parent);
            } catch (Throwable t) {
                // 構築失敗時は null (= ModMenu は「設定なし」扱い・クラッシュしない)。
                return null;
            }
        };
    }
}
