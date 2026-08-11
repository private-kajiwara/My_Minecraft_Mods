package com.kajiwara.omnichest.client.gui.search.preview;

import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.search.nested.RecursiveContainerHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 「いまこのフレーム、 バニラ Tooltip を抑制すべきか」 の判定ロジックを 1 か所に集約する。
 *
 * <p>
 * <b>仕様遵守</b>:
 * <ul>
 *   <li><b>グローバル無効化なし</b>: 抑制は条件が揃ったフレームのみ。 ALT を離したり、
 *       ホバー対象がコンテナでなくなったりすると即座に false を返し、 バニラ Tooltip が復帰する。</li>
 *   <li><b>状態破壊なし</b>: バニラ Tooltip キューや state に触れない。 cancel するのは
 *       「render パスの 1 呼び出しのみ」 で、 mixin 側が CallbackInfo.cancel() するだけ。</li>
 *   <li><b>他 Screen に波及しない</b>: 適用は {@code AbstractContainerScreen.renderTooltip}
 *       のみ (= プレイヤー インベントリ + チェスト系)。 プレビュー側も同じ範囲を担当するため、
 *       抑制だけが効く画面は無い。 REI/EMI 専用 Screen / 設定画面は影響を受けない。</li>
 *   <li><b>Tooltip suppression のみ</b>: アイテム ロジック / インベントリ操作 / 検索ロジックは
 *       一切変更しない (= 純粋な描画キャンセル)。</li>
 * </ul>
 */
public final class TooltipVisibilityController {

    private TooltipVisibilityController() {
    }

    /**
     * 「いま、 バニラ Tooltip を 1 フレームだけ抑制すべきか」 を返す。 全条件を満たす場合のみ true:
     * <ol>
     *   <li>{@code search.enableAltPreview} 設定が ON。</li>
     *   <li>ALT (左右いずれか) が押されている。</li>
     *   <li>ホバー中のスタックが「中身を持つコンテナ」 (= プレビューが実際に出る対象)。
     *       これにより、 ALT を押していてもシュルカー <em>以外</em> のアイテム上では
     *       バニラ Tooltip がそのまま出る (= ALT で全 Tooltip を消す副作用を回避)。</li>
     * </ol>
     */
    public static boolean shouldSuppress(@Nullable ItemStack hoveredStack) {
        if (hoveredStack == null || hoveredStack.isEmpty()) {
            return false;
        }
        try {
            if (!ConfigManager.get().search.enableAltPreview) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        if (!isAltDown()) {
            return false;
        }
        return RecursiveContainerHelper.isContainerItem(hoveredStack);
    }

    /** ALT (左右いずれか) が押されているか。 既存コード (Shift 判定) と同じ方式。 */
    private static boolean isAltDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RALT);
    }
}
