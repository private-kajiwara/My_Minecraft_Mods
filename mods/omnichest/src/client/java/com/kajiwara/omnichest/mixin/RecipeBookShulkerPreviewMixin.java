package com.kajiwara.omnichest.mixin;

import com.kajiwara.omnichest.client.compat.SafeRenderDispatcher;
import com.kajiwara.omnichest.client.gui.search.preview.AltPreviewTooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link AbstractRecipeBookScreen} 系の画面で ALT ホバー シュルカー プレビューを描く Mixin。
 *
 * <p>
 * <b>なぜ {@link ShulkerPreviewScreenMixin} だけでは足りないのか</b>:
 * {@code AbstractRecipeBookScreen} は {@code extractRenderState} を <b>オーバーライドしたうえで
 * {@code super} を呼ばず</b>、 {@code extractContents} → レシピ本 → {@code extractCarriedItem} →
 * {@code extractTooltip} → レシピ本 tooltip を自前で並べ直している (26.1.2 / 1.21.11 とも実測)。
 * そのため {@code AbstractContainerScreen.extractRenderState} は<b>一度も実行されず</b>、
 * そこへ TAIL 注入している {@link ShulkerPreviewScreenMixin} はこの系統の画面では発火しない。
 *
 * <p>
 * <b>対象になる画面</b> (= {@code AbstractRecipeBookScreen} の全サブクラス):
 * <ul>
 *   <li>{@code InventoryScreen} — E キーで開く素のプレイヤー インベントリ</li>
 *   <li>{@code CraftingScreen} — 作業台</li>
 *   <li>{@code AbstractFurnaceScreen} 系 — かまど / 燻製器 / 高炉</li>
 * </ul>
 * それ以外の {@code AbstractContainerScreen} 派生 (チェスト / 樽 / シュルカー / クリエイティブ /
 * 村人取引 / エンチャント台 等) は {@code super.extractRenderState} を呼ぶため、
 * 従来どおり {@link ShulkerPreviewScreenMixin} 側が担当する。
 *
 * <p>
 * <b>二重描画しない</b>: 上記のとおり両者の注入点は <b>排他</b> である。
 * {@code AbstractRecipeBookScreen} 系では ACS 版が呼ばれず、 それ以外の画面では
 * ARBS 版がそもそも存在しない。 どの画面でも ALT プレビューは 1 枚だけ描かれる。
 *
 * <p>
 * <b>ロジックの共有</b>: 表示条件 (設定 / ALT 判定 / コンテナ判定) ・配置クランプ・描画は
 * すべて {@link AltPreviewTooltip#tryRender} に集約されており、 本クラスは
 * {@link ShulkerPreviewScreenMixin} と <b>同一の入口を同一の引数で呼ぶだけ</b>。
 * 判定式を複製していないため、 チェスト GUI 側の見た目・挙動と構造的にズレない。
 *
 * <p>
 * 同 class への他 Mixin (SlotLock / Generic / SearchMatch は {@code AbstractContainerScreen} が
 * ターゲット) とはターゲット class が異なるため衝突しない。 例外時は
 * {@link SafeRenderDispatcher} で握り潰してゲーム本体を巻き込まない。
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookShulkerPreviewMixin extends Screen {

    protected RecipeBookShulkerPreviewMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
    private void omnichest$altShulkerPreviewRecipeBook(GuiGraphicsExtractor g, int mouseX, int mouseY,
            float partialTick, CallbackInfo ci) {
        SafeRenderDispatcher.safeRun("alt-shulker-preview-recipebook", () -> {
            Slot hovered = ((AbstractContainerScreenAccessor) (Object) this).cits$getHoveredSlot();
            if (hovered == null) {
                return;
            }
            ItemStack stack = hovered.getItem();
            AltPreviewTooltip.tryRender(g, mouseX, mouseY, stack, this.width, this.height);
        });
    }
}
