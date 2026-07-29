package com.kajiwara.hyperslice.observer;

import com.kajiwara.hyperslice.core.CrossSection;
import com.kajiwara.hyperslice.core.SliceRegistry;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

/**
 * <b>【診断実験】</b> 観測超平面 w を連続的に動かす。
 *
 * <h2>これは出荷機能ではない</h2>
 * 「プレイヤーが w を連続的に動かせたとき、 それが面白いか」を実機で判定するためだけの
 * 実験コード。 判定結果が方式B (単一ディメンションでブロックを書き換え、 継ぎ目のない
 * w 移動) へ投資するかを決める。
 *
 * <p>したがって最優先事項は<b>差分の小ささと可逆性</b>であり、 正しさや完成度ではない。
 * 地形は整数スライスに固定されたまま、 エンティティだけが連続 w に反応するという
 * 矛盾した状態になるが、 それは<b>承知のうえで許容している</b>。
 *
 * <h2>捨て方</h2>
 * {@link #EXPERIMENT_ENABLED} を {@code false} にすれば、 挙動は実験前と完全に一致する
 * (キー登録も tick 購読もコマンド登録も行われなくなる)。
 * 完全に消すなら {@code mods/hyperslice/README.md} の「実験を捨てる手順」を見ること。
 *
 * <h2>権威</h2>
 * この値は<b>クライアント権威</b>でサーバへ送らない (新しいパケット型を足さないため)。
 * サーバ側は代わりに w による同期の絞り込みを一時的に外す
 * ({@code HyperEntityService.EXPERIMENT_NO_W_FILTER})。
 */
public final class ObserverW {

    // =================================================================
    // ── 実験フラグ ──
    // =================================================================

    /**
     * この実験全体のスイッチ。
     *
     * <p>{@code false} にすると観測面は従来どおり {@code slice + 0.5} に戻り、
     * キーもコマンドも HUD 追加行も現れない (= 実験前と完全に一致する)。
     */
    public static final boolean EXPERIMENT_ENABLED = true;

    // =================================================================
    // ── 調整用定数 (人間が必ず触るのはここ) ──
    // =================================================================

    /**
     * キーを押している間の w の増減レート [w/tick]。 <b>この実験で最も重要な摘み。</b>
     *
     * <p>速すぎると球が点滅しているようにしか見えず、 遅すぎると静止して見える。
     * ここで得た値が、 方式B における w 移動速度の設計値になる。
     *
     * <p>既定 {@code 0.02} = 0.4 w/秒。 これは
     * {@code HyperEntityType.DEFAULT_W_VELOCITY} と同じ値で、
     * 「1 体の球が通過する速さとして読める」ことが実機確認済みの数値。
     * まずここを基準に、 自分で動かしたときの感触で上下させる。
     */
    public static final double RATE_PER_TICK = 0.02;

    // =================================================================

    /** キーバインドのカテゴリ (コントロール設定に出る)。 */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(SliceRegistry.NAMESPACE, "observer"));

    public static final String KEY_DECREASE = "key.hyperslice.observer_w_decrease";
    public static final String KEY_INCREASE = "key.hyperslice.observer_w_increase";

    /**
     * 既定キー。 <b>記号キーを避けて Page Down / Page Up にしている</b>。
     *
     * <p>当初は {@code [} / {@code ]} だったが、 GLFW のキートークンは
     * <b>物理キー位置</b>に対応しレイアウト非依存 (公式 input guide:
     * 「key events relate to actual physical keyboard keys」) なので、
     * {@code GLFW_KEY_RIGHT_BRACKET} は「US 配列で {@code ]} がある物理位置」を指す。
     * JIS 配列では印字が 「」」 のキーはそこに<b>無い</b>ため、 押しても届かなかった。
     *
     * <p>Page Down / Page Up は配列を問わず独立した物理キーで、 この差が原理的に生じない。
     * バニラ既定 (26.1.2 の {@code Options} を逆アセンブルして全既定コードを実測) にも
     * 既存 mod のキーバインドにも 266/267 は無く、 非衝突。
     */
    private static final int DEFAULT_KEY_DECREASE = GLFW.GLFW_KEY_PAGE_DOWN;
    private static final int DEFAULT_KEY_INCREASE = GLFW.GLFW_KEY_PAGE_UP;

    private static KeyMapping decreaseKey;
    private static KeyMapping increaseKey;

    /**
     * 現在の観測面 w。 {@link Double#NaN} は「未初期化 (所属スライスから引き直す)」。
     * 描画スレッドからも読まれるので volatile。
     */
    private static volatile double observerW = Double.NaN;

    /** 直前 tick の所属スライス。 変化したらテレポートとみなして再同期する。 */
    private static int lastSlice = Integer.MIN_VALUE;

    private ObserverW() {
    }

    // ── 登録 ────────────────────────────────────────────────────

    /** {@code HyperSliceClient} から 1 回だけ呼ぶ。 実験が無効なら何も登録しない。 */
    public static void register() {
        if (!EXPERIMENT_ENABLED) {
            return;
        }
        decreaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                KEY_DECREASE, InputConstants.Type.KEYSYM, DEFAULT_KEY_DECREASE, CATEGORY));
        increaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                KEY_INCREASE, InputConstants.Type.KEYSYM, DEFAULT_KEY_INCREASE, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(ObserverW::onClientTick);
    }

    // ── tick ────────────────────────────────────────────────────

    private static void onClientTick(Minecraft mc) {
        int slice = currentSlice(mc);
        if (slice < 0) {
            // HyperSlice の外に出たら、 次に入ったとき所属スライスから引き直す。
            lastSlice = Integer.MIN_VALUE;
            observerW = Double.NaN;
            return;
        }

        // 初回、 またはスライスを跨ぐテレポート (/hyperslice) が起きたら再同期する。
        if (slice != lastSlice || Double.isNaN(observerW)) {
            observerW = CrossSection.observationPlane(slice);
            lastSlice = slice;
        }

        // 画面 (チャット・インベントリ等) を開いている間は動かさない。
        if (mc.screen != null) {
            return;
        }

        double delta = 0.0;
        if (decreaseKey != null && decreaseKey.isDown()) {
            delta -= RATE_PER_TICK;
        }
        if (increaseKey != null && increaseKey.isDown()) {
            delta += RATE_PER_TICK;
        }
        if (delta != 0.0) {
            // 診断用途なので 0..slice_count には制限しない (自由に動かせてよい)。
            observerW += delta;
        }
    }

    // ── 値へのアクセス ──────────────────────────────────────────

    /**
     * 現在の観測面 w。
     *
     * <p>実験が無効、 または HyperSlice の外にいるときは {@link Double#NaN}。
     * 未初期化の場合はその場で所属スライスから引く (tick より先に描画が来ても破綻しない)。
     */
    public static double get() {
        if (!EXPERIMENT_ENABLED) {
            return Double.NaN;
        }
        double v = observerW;
        if (!Double.isNaN(v)) {
            return v;
        }
        int slice = currentSlice(Minecraft.getInstance());
        return slice < 0 ? Double.NaN : CrossSection.observationPlane(slice);
    }

    /** 所属スライス本来の観測面 w ({@code slice + 0.5})。 ズレ量の表示に使う。 */
    public static double nominalPlane() {
        int slice = currentSlice(Minecraft.getInstance());
        return slice < 0 ? Double.NaN : CrossSection.observationPlane(slice);
    }

    /** 直接指定 ({@code /observerw <value>})。 */
    public static void set(double value) {
        observerW = value;
    }

    /** 所属スライス本来の観測面へ戻す ({@code /observerw reset})。 */
    public static void reset() {
        observerW = nominalPlane();
    }

    // ── 入力到達の切り分け ──────────────────────────────────────

    /**
     * <b>【一時デバッグ】</b> 2 つのキーの押下状態と、 現在の割り当てキー名。
     *
     * <p>観測面 w の計算とは<b>独立</b>に「キー入力がそもそも届いているか」を人間が読めるようにする。
     * これが無いと次の 2 つを切り分けられない:
     * <ul>
     *   <li>ON になるのに w が動かない → 計算側の問題</li>
     *   <li>押しても ON にならない → 入力側の問題 (キーボード配列など)</li>
     * </ul>
     * 割り当てキー名も出すのは、 「そもそも何に割り当たっているか」を同時に確かめるため
     * (コントロール設定で再割当した結果もここに出る)。
     *
     * <p>実験が無効なら {@code null} (HUD 側は 1 行も足さない)。
     */
    public static Component keyDebugLine() {
        if (!EXPERIMENT_ENABLED || decreaseKey == null || increaseKey == null) {
            return null;
        }
        return Component.translatable("hyperslice.hud.key_debug",
                state(decreaseKey), decreaseKey.getTranslatedKeyMessage(),
                state(increaseKey), increaseKey.getTranslatedKeyMessage());
    }

    private static Component state(KeyMapping key) {
        return Component.translatable(
                key.isDown() ? "hyperslice.hud.key_on" : "hyperslice.hud.key_off");
    }

    // ── ヘルパ ──────────────────────────────────────────────────

    /** 現在のクライアントの所属スライス。 HyperSlice の外なら {@code -1}。 */
    private static int currentSlice(Minecraft mc) {
        Level level = (mc == null) ? null : mc.level;
        if (level == null) {
            return -1;
        }
        Identifier id = level.dimension().identifier();
        if (!SliceRegistry.NAMESPACE.equals(id.getNamespace())) {
            return -1;
        }
        return SliceRegistry.wFromPath(id.getPath());
    }
}
