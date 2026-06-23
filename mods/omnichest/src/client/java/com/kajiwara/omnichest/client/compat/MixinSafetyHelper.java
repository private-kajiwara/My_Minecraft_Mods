package com.kajiwara.omnichest.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Mixin の inject 内で頻出する <b>null 安全 / 例外安全</b> パターンをまとめたヘルパ。
 *
 * <p>
 * <b>狙い</b>:
 * <ul>
 *   <li>同じ mixin 対象クラスに <i>他 MOD</i> も inject していて、 先行 inject が
 *       想定外の状態 (= null 化された field, 空 menu, ChunkLoad 直後の null player) を
 *       残しているケースで本 MOD の inject 側で NPE を起こさない。</li>
 *   <li>{@code @Inject} の挙動を「副作用がある場合だけ続行 / それ以外は早期 return」 という形に統一し、
 *       「mixin 内で複雑な分岐を書かない」 ことで他 MOD との inject 順序依存を減らす。</li>
 * </ul>
 *
 * <p>
 * <b>禁止事項に対する適合</b>:
 * <ul>
 *   <li>{@code @Overwrite} は使わない (= 本ヘルパに頼る側も同様の方針)。</li>
 *   <li>cancellable inject を増やさない (= 本ヘルパはどれも void / non-cancellable)。</li>
 * </ul>
 */
public final class MixinSafetyHelper {

    private MixinSafetyHelper() {
    }

    /**
     * 「{@link Minecraft#getInstance()} → player / level / screen が全部 non-null」 が満たされたら body を実行。
     * いずれかが null なら何もしない (= 例外を投げない)。
     */
    public static void ifClientReady(Runnable body) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            LocalPlayer player = mc.player;
            if (player == null) return;
            ClientLevel level = mc.level;
            if (level == null) return;
            //? if >=26.2 {
            /*Screen screen = mc.gui.screen();*/
            //?} else {
            Screen screen = mc.screen;
            //?}
            if (screen == null) return;
            body.run();
        } catch (Throwable ignored) {
            // mixin の inject 中に他 MOD が状態を壊しても本 MOD はクラッシュしない。
        }
    }

    /**
     * Screen が {@link AbstractContainerScreen} で、 menu と slot list が non-null のときに body を実行。
     * Slot 単位の inject 内で「menu の状態が破壊されていた」 ケースをガードする。
     */
    public static void ifContainerScreen(@Nullable Screen screen,
            Consumer<AbstractContainerScreen<?>> body) {
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
        try {
            AbstractContainerMenu menu = acs.getMenu();
            if (menu == null) return;
            if (menu.slots == null) return;
            body.accept(acs);
        } catch (Throwable ignored) {
            // 描画パスでの例外は無視 (= 1 フレーム描画スキップ)。
        }
    }

    /**
     * Slot が「container を持ち、 中身が non-null」 のときだけ body を実行する null safety wrapper。
     */
    public static void ifSlotPresent(@Nullable Slot slot, Consumer<Slot> body) {
        if (slot == null) return;
        try {
            if (slot.container == null) return;
            if (slot.getItem() == null) return; // ItemStack.EMPTY は許容 (= 中身チェックは呼び出し側)
            body.accept(slot);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 例外を握って fallback 値を返す「reflection 失敗 / null 失敗時のフォールバック」。
     *
     * <p>
     * Mixin 内で {@code @Shadow field} 経由のアクセスが他 MOD によって変更され、
     * 想定外の class cast 等が起きるケースに使う。
     */
    public static <T, R> R safeOrDefault(T input, Function<T, R> fn, R fallback) {
        if (input == null) return fallback;
        try {
            R r = fn.apply(input);
            return r != null ? r : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
