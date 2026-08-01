package com.kajiwara.hyperslice.observer;

import com.kajiwara.hyperslice.core.CrossSection;
import com.kajiwara.hyperslice.core.SliceRegistry;
import com.kajiwara.hyperslice.net.WInputPayload;
import com.kajiwara.hyperslice.net.WStatePayload;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

/**
 * <b>【方式B 中核】</b> 観測超平面 w の操作 — <b>クライアント側は入力と表示だけ</b>。
 *
 * <h2>権威はサーバーにある</h2>
 * 方式B では w は<b>世界の状態</b>である (地形がその w で切り直される)。 したがって
 * この値をクライアントが持つと、 地形の w とエンティティの観測面が別々に動いて
 * 不整合になる。 このクラスがするのは 2 つだけ:
 * <ul>
 *   <li>キーの押下から<b>向き</b> ({@code -1/0/+1}) を作ってサーバーへ送る
 *       ({@link WInputPayload})</li>
 *   <li>サーバーから配られた w ({@link WStatePayload}) を受け取って保持する</li>
 * </ul>
 * 速さ ({@code BStepExperiment.W_RATE_PER_TICK}) も w の値もサーバーが持つ。
 *
 * <h2>診断実験だった頃からの変更点</h2>
 * 元はクライアント権威の診断実験 (「w を連続的に動かせたとき、 それが面白いか」の判定)
 * だった。 その判定は<b>済んでいる</b> (膨らむ球が円周を一周することを実機確認)。
 * 判定に使った割り切り — クライアント権威・サーバー側の w 絞り込み無効化・
 * クライアント側での w 積算 — は、 権威がサーバーへ移ったことで<b>すべて不要になった</b>。
 *
 * <h2>捨て方</h2>
 * {@link #EXPERIMENT_ENABLED} を {@code false} にすれば、 キーもコマンドも HUD 追加行も
 * 現れず、 観測面は方式A の {@code slice + 0.5} に戻る。 サーバー側も
 * {@code BStepExperiment.EXPERIMENT_ENABLED} を {@code false} にすれば w は動かない。
 */
public final class ObserverW {

    // =================================================================
    // ── 実験フラグ ──
    // =================================================================

    /**
     * クライアント側の w 操作・表示のスイッチ。
     *
     * <p><b>意味が変わっている。</b> 元は「クライアント権威で w を積算する診断実験」の
     * スイッチだったが、 今は「w の入力キーとサーバー w の受信を登録するか」。
     *
     * <p>{@code false} にすると観測面は方式A の {@code slice + 0.5} に戻り、
     * キーもコマンドも HUD 追加行も現れない。
     */
    public static final boolean EXPERIMENT_ENABLED = true;

    // =================================================================

    // 速さの摘み (RATE_PER_TICK) はここには無い。 権威と一緒にサーバー側
    // BStepExperiment.W_RATE_PER_TICK へ移設してある (2 箇所に散らさないため)。

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
     * サーバーから配られた今の観測面 w。 {@link Double#NaN} は「まだ届いていない」。
     *
     * <p>ネットワークスレッドから書かれ描画スレッドから読まれるので volatile。
     */
    private static volatile double serverW = Double.NaN;

    /** 直前 tick にサーバーへ送った向き。 変化を検出して「離した」速報を出すために持つ。 */
    private static int lastSentDirection;

    private ObserverW() {
    }

    // ── 登録 ────────────────────────────────────────────────────

    /** {@code HyperSliceClient} から 1 回だけ呼ぶ。 無効なら何も登録しない。 */
    public static void register() {
        if (!EXPERIMENT_ENABLED) {
            return;
        }
        decreaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                KEY_DECREASE, InputConstants.Type.KEYSYM, DEFAULT_KEY_DECREASE, CATEGORY));
        increaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                KEY_INCREASE, InputConstants.Type.KEYSYM, DEFAULT_KEY_INCREASE, CATEGORY));

        ClientPlayNetworking.registerGlobalReceiver(WStatePayload.TYPE,
                (payload, context) -> serverW = payload.w());

        ClientTickEvents.END_CLIENT_TICK.register(ObserverW::onClientTick);
    }

    // ── tick ────────────────────────────────────────────────────

    /**
     * <b>スライスを跨いだときに受信済みの値を捨ててはならない。</b>
     *
     * <p>サーバーは所属ディメンションが変わった時点で新しい w を送る
     * ({@code WStateSync} は最後に送った値を<b>ディメンションつき</b>で覚えている) ので、
     * テレポート直後の値は<b>既に届いている</b>。 クライアント tick はパケット処理の
     * <b>後</b>に走るため、 ここで「スライスが変わったから捨てる」と、 いま届いたばかりの
     * 正しい値を消してしまう。 そのディメンションの w が本来の整数から動いていた場合、
     * 次に w が変わるまで誤った観測面を表示し続けることになる。
     *
     * <p>捨てるのは HyperSlice の外へ出たときだけ。
     */
    private static void onClientTick(Minecraft mc) {
        int slice = currentSlice(mc);
        if (slice < 0) {
            // HyperSlice の外。 値も送信状態も持ち越さない。
            serverW = Double.NaN;
            lastSentDirection = 0;
            return;
        }

        // 画面 (チャット・インベントリ等) を開いている間は動かさない。
        int direction = 0;
        if (mc.screen == null) {
            if (decreaseKey != null && decreaseKey.isDown()) {
                direction -= 1;
            }
            if (increaseKey != null && increaseKey.isDown()) {
                direction += 1;
            }
        }

        send(direction);
    }

    /**
     * 向きをサーバーへ送る。
     *
     * <p>押している間は<b>毎ティック</b>送る (サーバー側が期限切れで自動的に止まれるように。
     * {@code WInputPayload} の javadoc 参照)。 離した瞬間は {@code 0} を 1 回だけ送り、
     * 以降は無音 — 押していないプレイヤーが常時パケットを出さないようにする。
     */
    private static void send(int direction) {
        if (direction == 0 && lastSentDirection == 0) {
            return;
        }
        lastSentDirection = direction;
        if (ClientPlayNetworking.canSend(WInputPayload.TYPE)) {
            ClientPlayNetworking.send(new WInputPayload(direction));
        }
    }

    // ── 値へのアクセス ──────────────────────────────────────────

    /**
     * 今の観測面 w。
     *
     * <p>実験が無効、 HyperSlice の外、 またはサーバーからまだ届いていないときは
     * <b>そのスライス本来の観測面</b>に倒す (未受信のあいだ描画が消えないように)。
     * HyperSlice の外なら {@link Double#NaN}。
     */
    public static double get() {
        if (!EXPERIMENT_ENABLED) {
            return Double.NaN;
        }
        double v = serverW;
        if (!Double.isNaN(v)) {
            return v;
        }
        return nominalPlane();
    }

    /**
     * 所属スライス本来の観測面 w。 ズレ量の表示に使う。
     *
     * <p>方式B では地形 w に一致する規約なので<b>整数 {@code slice} そのもの</b>
     * ({@code slice + 0.5} ではない)。 理由は
     * {@link CrossSection#planeForTerrainW} の javadoc にある。
     */
    public static double nominalPlane() {
        int slice = currentSlice(Minecraft.getInstance());
        return slice < 0 ? Double.NaN : CrossSection.planeForTerrainW(slice);
    }

    // ── 入力到達の切り分け ──────────────────────────────────────

    /**
     * <b>【一時デバッグ】</b> 2 つのキーの押下状態と、 現在の割り当てキー名。
     *
     * <p>観測面 w の変化とは<b>独立</b>に「キー入力がそもそも届いているか」を人間が読めるようにする。
     * これが無いと次の 2 つを切り分けられない:
     * <ul>
     *   <li>ON になるのに w が動かない → 送信 / サーバー側の問題</li>
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
