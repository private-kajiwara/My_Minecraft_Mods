package com.kajiwara.omnichest.client.render;

import com.kajiwara.omnichest.client.ClientKeyBindings;
import com.kajiwara.omnichest.client.compat.SafeRenderDispatcher;
import com.kajiwara.omnichest.client.gui.search.layout.ThemeColorResolver;
import com.kajiwara.omnichest.client.gui.search.preview.AltPreviewPopupRenderer;
import com.kajiwara.omnichest.client.gui.search.preview.PopupThemeResolver;
import com.kajiwara.omnichest.client.gui.search.preview.UnifiedPanelRenderer;
import com.kajiwara.omnichest.config.ConfigManager;
import com.kajiwara.omnichest.i18n.OmniChestLocale;
import com.kajiwara.omnichest.peek.ContainerPeekFit;
import com.kajiwara.omnichest.peek.PeekFreshness;
import com.kajiwara.omnichest.peek.PeekSummary;
import com.kajiwara.omnichest.search.ChestNetworkManager;
import com.kajiwara.omnichest.search.ContainerSnapshot;
import com.kajiwara.omnichest.search.ContainerType;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「コンテナ ピーク」 — 設置済みコンテナに照準を合わせ、 ピークキーを<b>押している間だけ</b>
 * その中身をポップアップ表示する<b>読み取り専用</b>ビュー。 既定 <b>OFF</b>。
 *
 * <p>
 * <b>★この機能が何をしているか (誤解しやすいので明記)</b>:
 * バニラはコンテナのブロックエンティティの中身をクライアントへ同期しない
 * ({@code BlockEntity#getUpdatePacket} は {@code null} を返し、 チェスト / シュルカーは
 * それを override していない = バイトコード実測)。 したがって 「開かずに今の中身を見る」 ことは
 * <b>原理的に不可能</b>で、 本機能が出しているのは
 * <b>{@link ChestNetworkManager} に既に記録済みのスナップショット</b> —
 * すなわち 「最後にそのコンテナを開いたときの中身」 である。 だからこそ
 * <ul>
 *   <li><b>鮮度 (= いつの情報か) を必ず併記する</b> ({@link PeekFreshness})</li>
 *   <li><b>未記録のコンテナには 「未登録」 と正直に出す</b> (中身を推測して埋めない)</li>
 *   <li><b>サーバへは一切問い合わせない</b> (新規パケットなし・サーバ側実装なし)</li>
 *   <li><b>キャッシュを書き換えない</b> ({@code get} のみ。 {@code put} / {@code capture} /
 *       {@code remove} は呼ばない)</li>
 * </ul>
 *
 * <p>
 * <b>性能 (= 既存ユーザーへの影響ゼロを構造で保証)</b>:
 * <ul>
 *   <li>設定 OFF のときは {@link #onHudRender} の<b>最初の 1 行</b>で return する。
 *       {@code mc.hitResult} にもキャッシュにも一切触れない。</li>
 *   <li>ON でもキーを押していなければ 2 番目の判定で return する
 *       (= {@code KeyMapping#isDown()} の boolean 読み出し 1 回)。</li>
 *   <li>視線先が変わったとき、 または {@link #RESOLVE_TTL_MS} 経過時にだけ再解決する
 *       (= 毎フレームのキャッシュ検索をしない)。</li>
 * </ul>
 *
 * <p>
 * <b>入力規律</b>: 押下判定は {@link KeyMapping#isDown()} <b>のみ</b>。 バニラは Screen を開く瞬間に
 * {@code KeyMapping#releaseAll} を呼び、 かつ Screen 表示中は {@code KeyMapping.set} を呼ばないため、
 * <b>いずれかの Screen (チャット / インベントリ / 本 MOD の画面) が開いている間 {@code isDown()} は
 * 必ず false</b> になる。 = チャット入力中の暴発は構造的に起こらない。 F1 (hideGui) 中は
 * エンジンが HUD 描画自体をスキップするため、 本メソッドは呼ばれない。
 *
 * <p>
 * <b>見た目</b>: 既存の ALT プレビュー Popup ({@link AltPreviewPopupRenderer#renderSlots})
 * を<b>無改造で</b>呼ぶ。 新しいデザインは発明せず、 配色も {@link PopupThemeResolver} /
 * {@link ThemeColorResolver} のトークンのみを使う。 背景は {@code g.fill} 系だけで敷き、
 * {@code renderBackground} / blur 経路には<b>触れない</b>
 * (= 1.21.5+ の 「Can only blur once per frame」 クラッシュを構造的に回避)。
 *
 * <p>
 * 描画は既存の HUD 登録 API に相乗りする (= <b>新規 Mixin なし</b>)。
 */
public final class ContainerPeekRenderer {

    private static final ContainerPeekRenderer INSTANCE = new ContainerPeekRenderer();

    /**
     * 再解決の最短間隔 (ms)。 視線先が同じでも、 この間隔でだけキャッシュを引き直す。
     * <p>
     * 「視線先が変わったときのみ再解決」 だけだと、 チェストを開いて中身を変えて閉じ、 <b>同じ</b>
     * チェストを見続けた場合に古い内容が残ってしまう。 短い TTL を併用することで
     * 「毎フレーム検索しない」 と 「内容変化に追従する」 を両立する。
     */
    private static final long RESOLVE_TTL_MS = 250L;

    /** タイトルとサマリの区切り (= 既存 Popup サマリの "·" と同じ慣習)。 */
    private static final String TITLE_SEPARATOR = " · ";

    // ─── メモ化 (= 視線先が変わったとき / TTL 経過時のみ再解決) ───
    @Nullable
    private ResourceKey<Level> cachedDim;
    @Nullable
    private BlockPos cachedPos;
    @Nullable
    private ContainerType cachedType;
    @Nullable
    private Resolved cachedResolved;
    private long lastResolveMs = Long.MIN_VALUE;

    /**
     * フェード継続判定に使うトークン。 <b>視線先が変わったときだけ</b> 差し替える
     * (= TTL による再解決ではフェードを巻き戻さない)。
     */
    private Object fadeToken = new Object();

    private ContainerPeekRenderer() {
    }

    public static ContainerPeekRenderer get() {
        return INSTANCE;
    }

    /**
     * HUD 登録。 {@link com.kajiwara.omnichest.OmniChestClient} から 1 回だけ呼ぶ。
     * 既存の {@link SelectedItemHudRenderer} / {@link ChestHighlighter} と同じ HUD パスへ載せる
     * (= 新規 Mixin を増やさない)。
     *
     * <p>
     * <b>★なぜ {@code addLast} ではなく {@link VanillaHudElements#CROSSHAIR} の「前」なのか</b>:
     * ポップアップは配置計算 ({@link ContainerPeekFit#placeY}) で下部 HUD を必ず避け、
     * 可能な限りクロスヘアも避ける。 しかし <b>54 スロット (高さ 154px) を縦の狭い論理画面
     * (例: 1920x1080 の GUI スケール 4 = 480x270) に置く場合、 クロスヘアの上にも下にも
     * 入らない</b> — これは幾何的にどうにもならない。 そこで描画順を 1 段前へ動かし、
     * <b>バニラのクロスヘア (とホットバー等) が常にポップアップの上に描かれる</b>ようにする。
     * これで重なっても照準は見えたままになり、 対象ブロックを狙い続けられる。
     * 下部 HUD に対しては配置計算との二重の保険にもなる。
     *
     * <p>
     * {@code VanillaHudElements.CROSSHAIR} と {@code attachElementBefore} は本 MOD が対応する
     * 全 Fabric API 版 (rendering-v1 16.2.x / 23.x / 25.x) に存在することを jar で確認済み。
     */
    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CROSSHAIR,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("omnichest", "container_peek"),
                (g, deltaTracker) -> SafeRenderDispatcher.safeRun("container-peek",
                        () -> INSTANCE.onHudRender(g)));
    }

    // ════════════════════════════════════════════════════════════════════
    // フレーム入口
    // ════════════════════════════════════════════════════════════════════

    private void onHudRender(GuiGraphicsExtractor g) {
        // ★ 機能 OFF: ここで終わり。 hitResult もキャッシュも触らない (= 既存ユーザーへの影響ゼロ)。
        if (!peekEnabled()) {
            return;
        }
        // キーを押していない (or 未割当 / Screen 表示中) → 何もしない。
        KeyMapping key = ClientKeyBindings.containerPeekMapping();
        if (key == null || !key.isDown()) {
            invalidate();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            invalidate();
            return;
        }

        // ─── 視線先の解決 (距離 / 遮蔽はバニラのレイキャストが済ませている) ───
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            invalidate();
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        ContainerType type = ContainerType.fromBlockState(state);
        if (type == null || !isPeekable(type)) {
            // 対象外ブロック (= ただの石 / ホッパー / 作業台 等) では何も出さない。
            invalidate();
            return;
        }

        ResourceKey<Level> dim = mc.level.dimension();
        Resolved resolved = resolve(mc, dim, pos, state, type);
        if (resolved == null) {
            return;
        }
        draw(g, mc, type, resolved);
    }

    /** 設定を読む唯一の場所。 読めない初期化中などは安全側 = 非表示。 */
    private static boolean peekEnabled() {
        try {
            return ConfigManager.get().render.enableOverlay
                    && ConfigManager.get().render.enableContainerPeek;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * ピーク対象の {@link ContainerType} か。
     *
     * <p>
     * <b>対象</b> (= 「保管」 のためのブロック): チェスト / トラップチェスト / ラージチェスト
     * (両種) / 樽 / 設置済みシュルカーボックス (全 16 色は同一ブロッククラス) / エンダーチェスト。
     *
     * <p>
     * <b>非対象</b>: ホッパー / ディスペンサー / ドロッパー / クラフターは 「保管」 ではなく
     * 「機構」 であり、 レッドストーン作業中に不要なポップアップが出るため除外する。
     * エンティティコンテナ (= トロッコ / ボート / モブ) は {@code hitResult} がブロックのみを
     * 返すのでそもそも到達しない。
     */
    private static boolean isPeekable(ContainerType type) {
        switch (type) {
            case CHEST:
            case TRAPPED_CHEST:
            case DOUBLE_CHEST:
            case DOUBLE_TRAPPED_CHEST:
            case BARREL:
            case SHULKER_BOX:
            case ENDER_CHEST:
                return true;
            default:
                return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 解決 (= 読み取り専用のキャッシュ参照)
    // ════════════════════════════════════════════════════════════════════

    /** 解決結果。 {@code snapshot} は {@code status == AVAILABLE} のときのみ非 null。 */
    private record Resolved(ContainerPeekFit.Status status, @Nullable ContainerSnapshot snapshot) {
    }

    /**
     * 視線先に対応するスナップショットを引く。 視線先が同じで TTL 内ならメモ化した結果を返す。
     *
     * <p>
     * <b>キャッシュへの書き込みは一切しない</b>。 {@link ChestNetworkManager#get} と
     * {@link ChestNetworkManager#snapshots} (= 読み出しのみ) しか呼ばない。
     */
    @Nullable
    private Resolved resolve(Minecraft mc, ResourceKey<Level> dim, BlockPos pos,
            BlockState state, ContainerType type) {
        boolean sameTarget = type == cachedType
                && pos.equals(cachedPos)
                && dim.equals(cachedDim);
        if (!sameTarget) {
            // 視線先が変わった = フェードをやり直す (= 既存プレビューと同じ体感)。
            fadeToken = new Object();
        }
        long now = System.currentTimeMillis();
        if (sameTarget && cachedResolved != null && (now - lastResolveMs) < RESOLVE_TTL_MS) {
            return cachedResolved;
        }

        Resolved resolved = lookup(mc, dim, pos, state, type);
        cachedDim = dim;
        cachedPos = pos.immutable();
        cachedType = type;
        cachedResolved = resolved;
        lastResolveMs = now;
        return resolved;
    }

    private Resolved lookup(Minecraft mc, ResourceKey<Level> dim, BlockPos pos,
            BlockState state, ContainerType type) {
        if (type == ContainerType.ENDER_CHEST) {
            boolean enabled = enderSearchEnabled();
            ContainerSnapshot snap = enabled ? latestEnderSnapshot() : null;
            return new Resolved(ContainerPeekFit.status(true, enabled, snap != null), snap);
        }
        ContainerSnapshot snap = ChestNetworkManager.get().get(keyFor(mc, dim, pos, state));
        return new Resolved(ContainerPeekFit.status(false, true, snap != null), snap);
    }

    /**
     * 視線先ブロックから {@link ContainerSnapshot.Key} を作る。
     *
     * <p>
     * ラージチェストのキーは 2 つの {@link BlockPos} を {@code normalize} した値なので、
     * <b>右半分を見ていても</b> 相方を求めて正規化しないと引けない。 相方の算出は既存の
     * {@link ContainerType#otherHalfOrNull} ({@code ChestBlock.getConnectedDirection} 由来) に委ねる。
     * これにより左右どちらの半分を見ても同じエントリ (= 54 スロット) に到達する。
     */
    private static ContainerSnapshot.Key keyFor(Minecraft mc, ResourceKey<Level> dim,
            BlockPos pos, BlockState state) {
        BlockPos other = ContainerType.otherHalfOrNull(mc.level, pos, state);
        BlockPos normalized = (other == null) ? pos : ContainerSnapshot.normalize(pos, other);
        return new ContainerSnapshot.Key(dim, normalized);
    }

    /**
     * エンダーチェストの中身は<b>プレイヤー単位で共有</b>され、 どのブロックから開いても同一。
     * 一方キャッシュは 「開いたブロック座標ごと」 に持つため、 座標どおりに引くと
     * 「A は見えるが B は未登録」 というバニラ仕様と食い違う表示になる。 そこで
     * {@link ContainerType#ENDER_CHEST} のスナップショット群から
     * {@code lastSeenMillis} が最大のものを選ぶ (= 座標非依存)。
     *
     * <p>
     * 選択規則を変えているだけで、 <b>キャッシュは書き換えない</b>。
     */
    @Nullable
    private static ContainerSnapshot latestEnderSnapshot() {
        List<ContainerSnapshot> enders = new ArrayList<>();
        for (ContainerSnapshot snap : ChestNetworkManager.get().snapshots()) {
            if (snap.type() == ContainerType.ENDER_CHEST) {
                enders.add(snap);
            }
        }
        if (enders.isEmpty()) {
            return null;
        }
        long[] seen = new long[enders.size()];
        for (int i = 0; i < enders.size(); i++) {
            seen[i] = enders.get(i).lastSeenMillis();
        }
        int idx = ContainerPeekFit.pickLatestIndex(seen);
        return idx < 0 ? null : enders.get(idx);
    }

    private static boolean enderSearchEnabled() {
        try {
            return ConfigManager.get().search.enableEnderChestSearch;
        } catch (Throwable ignored) {
            // 設定が読めない初期化中は収集側 (EnderChestStorageBridge.shouldTrack) と同じく
            // 「収集する」 側に倒す (= 案内文を誤って出さない)。
            return true;
        }
    }

    private void invalidate() {
        cachedDim = null;
        cachedPos = null;
        cachedType = null;
        cachedResolved = null;
        lastResolveMs = Long.MIN_VALUE;
    }

    // ════════════════════════════════════════════════════════════════════
    // 描画
    // ════════════════════════════════════════════════════════════════════

    private void draw(GuiGraphicsExtractor g, Minecraft mc, ContainerType type, Resolved resolved) {
        if (resolved.status() == ContainerPeekFit.Status.AVAILABLE && resolved.snapshot() != null) {
            drawContents(g, mc, type, resolved.snapshot());
            return;
        }
        drawNotice(g, mc, type, resolved.status());
    }

    /**
     * 記録済み。 グリッドが置ければ既存の ALT プレビュー Popup をそのまま呼び、
     * 置けなければ要約リスト (= コンパクトモード) へ落とす。
     *
     * <p>
     * <b>どこに置くか</b>は {@link ContainerPeekFit#layout} が決める。 下 → 上 → 右 → 左 の順に試し、
     * どれも駄目ならコンパクトで同じ順を試す。 いずれの配置でも<b>クロスヘアの矩形と 1px も
     * 交差しない</b> (= 照準がグリッド内のアイテムに重なって読めなくなる不具合への対処)。
     *
     * <p>
     * 鮮度は <b>タイトル行</b> に淡色で併記する。 グリッドのサマリ行 ({@code "M / N · ×T"}) は
     * {@link AltPreviewPopupRenderer#renderSlots} が内部で描くもので、 そこへ鮮度を差し込むには
     * 既存レンダラの改造が要る。 既存プレビューの見た目を 1 ピクセルも動かさないほうを優先し、
     * こちら側で足せるタイトルに寄せている。
     */
    private void drawContents(GuiGraphicsExtractor g, Minecraft mc, ContainerType type,
            ContainerSnapshot snapshot) {
        List<ItemStack> slots = snapshot.items();
        int slotCount = Math.max(slots.size(), defaultSlotCount(type));
        int columns = ContainerPeekFit.gridColumns(slotCount);
        int gridW = AltPreviewPopupRenderer.panelWidth(columns);
        int gridH = AltPreviewPopupRenderer.panelHeight(columns, slotCount);

        Component title = titleWithAge(type, snapshot);
        Compact compact = buildCompact(mc.font, title, slots, slotCount);

        ContainerPeekFit.Layout layout = ContainerPeekFit.layout(
                g.guiWidth(), g.guiHeight(), g.guiWidth() / 2, g.guiHeight() / 2,
                gridW, gridH, compact.width(), compact.height());

        if (!layout.compact()) {
            AltPreviewPopupRenderer.renderSlots(g, mc.font, title,
                    slots, slotCount, layout.x(), layout.y(), columns, false, fadeToken);
            return;
        }
        drawCompact(g, mc.font, title, compact, layout.x(), layout.y());
    }

    // ════════════════════════════════════════════════════════════════════
    // コンパクトモード (= グリッドがどこにも置けないときのフォールバック)
    // ════════════════════════════════════════════════════════════════════

    /**
     * コンパクト表示の幅の上限 (論理 px)。 9 列グリッドと同じ値にしてあるので、
     * 横へ逃がせるかどうかの判定がグリッドと同条件になる (= 予測しやすい)。
     */
    private static final int COMPACT_MAX_WIDTH = 174;

    /** アイコンと行の高さは {@code font.lineHeight}。 既存のピン行 ({@code ChestHighlighter}) と同じ流儀。 */
    private static int compactRowHeight(Font font) {
        return font.lineHeight;
    }

    /** 描画前に確定させたコンパクト表示の内容と寸法。 */
    private record Compact(List<CompactRow> rows, Component summary, int width, int height) {
    }

    /** 1 行ぶん。 {@code icon} が null なら 「他 M 種」 の行。 */
    private record CompactRow(ItemStack icon, Component text) {
    }

    /**
     * コンパクト表示を組み立てる (= 描画せず、 内容と寸法だけ決める)。
     *
     * <p>
     * 同種スタックの判定は Minecraft 側でしかできない ({@code isSameItemSameComponents}) ので
     * ここで採番し、 <b>合算 / 並べ替え / 上位 N / 「他 M 種」 の算出は {@link PeekSummary}</b>
     * (= 単体テスト済みの純関数) に委ねる。
     */
    private static Compact buildCompact(Font font, Component title,
            List<ItemStack> slots, int slotCount) {
        // (1) 同種スタックへ id を採番する。 スナップショットは高々 54 スロットなので素朴な比較で足りる。
        List<ItemStack> representatives = new ArrayList<>();
        List<PeekSummary.Slot> summarySlots = new ArrayList<>();
        for (ItemStack stack : slots) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int id = -1;
            for (int i = 0; i < representatives.size(); i++) {
                if (ItemStack.isSameItemSameComponents(representatives.get(i), stack)) {
                    id = i;
                    break;
                }
            }
            if (id < 0) {
                id = representatives.size();
                representatives.add(stack);
            }
            summarySlots.add(new PeekSummary.Slot(id, stack.getCount()));
        }
        PeekSummary.Summary summary = PeekSummary.summarize(summarySlots);

        // (2) 行を作る。 名前は上限幅まで切り詰めてパネル幅の暴発を防ぐ。
        int rowH = compactRowHeight(font);
        int contentMax = COMPACT_MAX_WIDTH - PopupThemeResolver.PANEL_PADDING * 2;
        List<CompactRow> rows = new ArrayList<>();
        int contentW = Math.min(font.width(title), contentMax);
        for (PeekSummary.Line line : summary.top()) {
            ItemStack icon = representatives.get(line.id());
            String count = " ×" + line.count();
            int countW = font.width(count);
            int nameMax = Math.max(0, contentMax - rowH - COMPACT_ICON_GAP - countW);
            String name = clip(font, icon.getHoverName().getString(), nameMax);
            Component text = Component.empty()
                    .append(Component.literal(name).withColor(PopupThemeResolver.TEXT_PRIMARY & 0xFFFFFF))
                    .append(Component.literal(count)
                            .withColor(PopupThemeResolver.TEXT_SECONDARY & 0xFFFFFF));
            rows.add(new CompactRow(icon, text));
            contentW = Math.max(contentW, rowH + COMPACT_ICON_GAP + font.width(text));
        }
        if (summary.otherKinds() > 0) {
            // 上限を超えた種類数。 0 のときは行そのものを出さない (= 「他 0 種」 を描かない)。
            Component more = OmniChestLocale.get("omnichest.peek.more_kinds",
                    "+%1$d more kinds", summary.otherKinds());
            rows.add(new CompactRow(null, more.copy()
                    .withColor(ThemeColorResolver.TEXT_DIM & 0xFFFFFF)));
            contentW = Math.max(contentW, font.width(more));
        }

        // (3) サマリはグリッドと同じ書式 (= "M / N · ×T") にして見た目を揃える。
        Component summaryText = Component.literal(String.format(Locale.ROOT, "%d / %d · ×%d",
                summary.usedSlots(), slotCount, summary.totalCount()));
        contentW = Math.max(contentW, font.width(summaryText));
        contentW = Math.min(contentW, contentMax);

        int pad = PopupThemeResolver.PANEL_PADDING;
        int gap = PopupThemeResolver.SEPARATOR_GAP;
        int width = pad * 2 + contentW;
        int height = pad * 2 + PopupThemeResolver.TITLE_HEIGHT
                + (gap + 1 + gap) + rows.size() * rowH + (gap + 1 + gap)
                + PopupThemeResolver.SUMMARY_HEIGHT;
        return new Compact(rows, summaryText, width, height);
    }

    /** アイコンと文字の間隔 (px)。 既存ピン行と同値。 */
    private static final int COMPACT_ICON_GAP = 2;

    /**
     * コンパクト表示を描く。 パネル / 区切り / 配色は既存の {@link UnifiedPanelRenderer} と
     * {@link PopupThemeResolver} を<b>呼ぶだけ</b>で、 新しいデザインや配色は作らない。
     */
    private void drawCompact(GuiGraphicsExtractor g, Font font, Component title,
            Compact compact, int x, int y) {
        int pad = PopupThemeResolver.PANEL_PADDING;
        int gap = PopupThemeResolver.SEPARATOR_GAP;
        int rowH = compactRowHeight(font);
        int contentW = compact.width() - pad * 2;

        UnifiedPanelRenderer.drawPanel(g, x, y, compact.width(), compact.height(), 1.0f);

        int left = x + pad;
        g.text(font, title, left, y + pad - 1, PopupThemeResolver.TEXT_PRIMARY, false);

        int sepY = y + pad + PopupThemeResolver.TITLE_HEIGHT + gap;
        UnifiedPanelRenderer.drawSeparator(g, left, sepY, contentW, 1.0f);

        int rowY = sepY + 1 + gap;
        for (CompactRow row : compact.rows()) {
            if (row.icon() != null) {
                drawSmallIcon(g, row.icon(), left, rowY, rowH);
                g.text(font, row.text(), left + rowH + COMPACT_ICON_GAP, rowY,
                        PopupThemeResolver.TEXT_PRIMARY, false);
            } else {
                g.text(font, row.text(), left, rowY, ThemeColorResolver.TEXT_DIM, false);
            }
            rowY += rowH;
        }

        int sep2Y = rowY + gap;
        UnifiedPanelRenderer.drawSeparator(g, left, sep2Y, contentW, 1.0f);
        g.text(font, compact.summary(), left, sep2Y + 1 + gap,
                PopupThemeResolver.TEXT_SECONDARY, false);
    }

    /** 16px のアイテムを {@code size} px へ縮めて描く (= 既存ピン行と同じ pose スケール方式)。 */
    private static void drawSmallIcon(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int size) {
        float scale = size / 16.0f;
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) x, (float) y);
            pose.scale(scale, scale);
            g.item(stack, 0, 0);
        } finally {
            pose.popMatrix();
        }
    }

    /** 文字列を最大幅で切り詰める (超過時のみ末尾を省略記号化)。 */
    private static String clip(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) {
            return s;
        }
        int ellipsisW = font.width("…");
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - ellipsisW)) + "…";
    }

    /**
     * 未記録 / 設定 OFF: グリッドの代わりに 「タイトル + 区切り + 状況 + ヒント」 の小さなパネルを出す。
     *
     * <p>
     * パネル / 区切り / 配色は既存の {@link UnifiedPanelRenderer} と {@link PopupThemeResolver} を
     * そのまま使う (= 独自のデザイン・配色を発明しない)。 中身は<b>一切描かない</b>
     * (= 推測で埋めない)。
     */
    private void drawNotice(GuiGraphicsExtractor g, Minecraft mc, ContainerType type,
            ContainerPeekFit.Status status) {
        Font font = mc.font;
        Component title = type.displayComponent();
        Component message;
        Component hint;
        if (status == ContainerPeekFit.Status.ENDER_SEARCH_DISABLED) {
            // 「一度開くと記録します」 は設定 OFF の間は嘘になるので、 設定の場所を案内する。
            message = OmniChestLocale.get("omnichest.peek.ender_disabled",
                    "— ender chest search is off —");
            hint = OmniChestLocale.get("omnichest.peek.ender_disabled.hint",
                    "Turn on \"Enable Ender Chest Search\" in Chest Network Search.");
        } else {
            message = OmniChestLocale.get("omnichest.peek.not_recorded", "— not recorded —");
            hint = OmniChestLocale.get("omnichest.peek.not_recorded.hint",
                    "Open it once and the contents are remembered.");
        }

        int pad = PopupThemeResolver.PANEL_PADDING;
        int gap = PopupThemeResolver.SEPARATOR_GAP;
        int lh = font.lineHeight;
        int contentW = Math.max(font.width(title), Math.max(font.width(message), font.width(hint)));
        int panelW = pad * 2 + contentW;
        int panelH = pad * 2 + PopupThemeResolver.TITLE_HEIGHT + gap + 1 + gap + lh + gap + lh;

        // お知らせパネルは小さいので、 グリッドと同じ配置規則にそのまま乗る
        // (= グリッド寸法とコンパクト寸法に同じ値を渡す)。
        ContainerPeekFit.Layout layout = ContainerPeekFit.layout(
                g.guiWidth(), g.guiHeight(), g.guiWidth() / 2, g.guiHeight() / 2,
                panelW, panelH, panelW, panelH);
        int x = layout.x();
        int y = layout.y();

        UnifiedPanelRenderer.drawPanel(g, x, y, panelW, panelH, 1.0f);

        int left = x + pad;
        g.text(font, title, left, y + pad - 1, PopupThemeResolver.TEXT_PRIMARY, false);

        int sepY = y + pad + PopupThemeResolver.TITLE_HEIGHT + gap;
        UnifiedPanelRenderer.drawSeparator(g, left, sepY, contentW, 1.0f);

        int rowY = sepY + 1 + gap;
        g.text(font, message, left, rowY, PopupThemeResolver.TEXT_SECONDARY, false);
        g.text(font, hint, left, rowY + lh + gap, ThemeColorResolver.TEXT_DIM, false);
    }

    /**
     * タイトル = 「コンテナ種別 · 鮮度」。 鮮度だけ淡色にして、 これが
     * <b>「いつの情報か」</b> であることを一目で分かるようにする。
     */
    private static Component titleWithAge(ContainerType type, ContainerSnapshot snapshot) {
        PeekFreshness.Label age = PeekFreshness.labelFor(
                System.currentTimeMillis(), snapshot.lastSeenMillis());
        Component ageText = age.hasAmount()
                ? OmniChestLocale.get(age.key(), age.enFallback(), age.amount())
                : OmniChestLocale.get(age.key(), age.enFallback());
        int dim = PopupThemeResolver.TEXT_SECONDARY & 0xFFFFFF;
        return Component.empty()
                .append(type.displayComponent())
                .append(Component.literal(TITLE_SEPARATOR).withColor(dim))
                .append(ageText.copy().withColor(dim));
    }

    /**
     * スナップショットの items が短い (= 旧キャッシュ等) 場合に補うグリッドの既定スロット数。
     * {@code ChestCacheStorage} の復元時と同じ規則。
     */
    private static int defaultSlotCount(ContainerType type) {
        return type.isDouble() ? 54 : 27;
    }
}
