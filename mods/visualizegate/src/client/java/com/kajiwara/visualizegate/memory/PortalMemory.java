package com.kajiwara.visualizegate.memory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kajiwara.visualizegate.VisualizeGateMod;
import com.kajiwara.visualizegate.domain.DomainPortal;
import com.kajiwara.visualizegate.domain.GateNode;
import com.kajiwara.visualizegate.domain.GridPos;
import com.kajiwara.visualizegate.domain.LinkPrediction;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.domain.PortalLinkResolver;
import com.kajiwara.visualizegate.domain.PredictedLinkState;
import com.kajiwara.visualizegate.scan.PortalIndex;
import com.kajiwara.visualizegate.scan.PortalRecord;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 世代横断のポータル記憶 (機能2 の緑/赤判定の前提)。 {@link PortalIndex} はディメンションを去ると
 * 当該ポータルを失う (CHUNK_UNLOAD 削除) ため、 別ファイルに world-id × ディメンション別で<b>永続</b>する。
 *
 * <p><b>整合 (reconcile)</b>: 在ディメンション中、 記憶位置がライブでロード済みなのに実ポータルが無ければ
 * その記憶を除去する (= 破壊済みポータルで緑が嘘にならない)。 記憶レコードは {@code lastSeenTick} と
 * {@code liveConfirmed} を保持し「ライブ確認済み」と「記憶 (古い可能性)」をデータ上区別する。
 *
 * <p><b>world-id</b>: SP=セーブ名 / MP=サーバアドレス の<b>ヒューリスティック</b> (非一意)。 別 world の
 * 記憶を誤用しないよう常に現 world-id で分離する。
 */
public final class PortalMemory {

    private static final String FILE_NAME = "visualizegate-portals.json";
    private static final int PERIODIC_INTERVAL = 20; // tick
    /**
     * ⑤⑦B reconcile の game-time grace (ワールド tick)。 最後にライブ確認した {@code lastConfirmedGameTime} から
     * この game-tick 数以内なら、 たとえ成分不在でも除去しない (再観測待ち)。 復元/入場直後は join seed で full grace。
     * 200t≒10s。 game-time 基準なのでゲームを閉じている間は進まない (= 実時間放置で勝手に期限切れしない)。
     */
    private static final long RECONCILE_GRACE_GAMETIME = 200;
    /**
     * ⑤⑦B 除去抑止 (settle) ウィンドウ (game-tick)。 join / dimension 切替の直後、 チャンクが確定するまで
     * <b>reconcile の除去自体を停止</b>する (= 入場過渡の「ロード済みだがブロック未同期」で復元ポータルを nuke しない)。 100t≒5s。
     */
    private static final long SETTLE_GAMETIME = 100;
    /**
     * ⑤⑦B 除去に必要な「ロード済み＋成分不在」連続確認回数 (reconcile サイクル)。 単発の過渡的不在では除去せず、
     * 実ロード状態で N 回連続して成分が無い時だけ除去＝誤除去ゼロと「破壊済みポータルの正規除去」を両立。
     */
    private static final int ABSENT_STREAK_REQUIRED = 3;
    /** 観測リージョンセルのサイズ (チャンク四方)。 4ch=64ブロック。 */
    private static final int CELL_CHUNKS = 4;
    /** ㉙ 確定接続ペアの解決パラメタ (点群 buildLinks と同値・OW→ネザー 1:8 写像の水平半径/Y境界)。 */
    private static final double NETHER_SEARCH_RADIUS = 16.0;
    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final PortalMemory INSTANCE = new PortalMemory();

    private MemoryFile file;          // 全 world 分 (lazy load)
    private String currentWorldId;    // 現接続の world-id
    private long tickCounter = 0;

    // ⑤⑦B reconcile 堅牢化の状態。 settle ウィンドウ終端 (game-time)・直近 reconcile dim・join seed 待ち。
    private long removalSuppressedUntilGameTime = Long.MIN_VALUE;
    private String reconcileLastDim = null;
    private boolean joinSeedPending = false;

    // ⑤⑦C デバウンス定期保存 (unclean exit 対策・別コミット)。 構造変化時に dirty を立て、 在ワールド中に間引いて保存。
    private static final long SAVE_DEBOUNCE_TICKS = 600; // 30s (= I/O スラッシュ回避・小ファイル)
    private boolean dirty = false;
    private long lastSaveTick = 0;

    private PortalMemory() {
    }

    public static PortalMemory get() {
        return INSTANCE;
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> INSTANCE.onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.onLeave());
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> INSTANCE.onChunkLoad(level, chunk));
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onClientTick);
    }

    // ── 接続ライフサイクル ──────────────────────────────────────────────

    private void onJoin(Minecraft mc) {
        try {
            ensureLoaded();
            currentWorldId = worldId(mc); // null 可 (= 取得不能なら記憶を触らない)
            VisualizeGateMod.LOGGER.info("[visualizegate] portal-memory JOIN worldId={}", currentWorldId);
            // ⑤⑦B 復元直後は最初の tick で lastConfirmed を現 game-time に seed＋settle 抑止 (= rejoin nuke 防止)。
            joinSeedPending = true;
            reconcileLastDim = null;
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] portal-memory join failed: {}", t.toString());
        }
    }

    private void onLeave() {
        // ⑰ currentWorldId は<b>あえて null にしない</b> (保持)。 万一ディメンション移動で DISCONNECT が
        // 発火しても記憶クエリ (knownInDimension=点群 Links の素) が空にならない。 別 world へ実接続する時は
        // onJoin が先に上書きし、 メニュー中 (world 無し) は解析が走らないので stale でも無害。
        try {
            save();
            VisualizeGateMod.LOGGER.info("[visualizegate] portal-memory LEAVE (saved; worldId kept={})",
                    currentWorldId);
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] portal-memory save-on-leave failed: {}", t.toString());
        }
    }

    /** CHUNK_LOAD: そのチャンクが属するリージョンセルを「観測済み」に印す (安価)。 */
    private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        try {
            ensureLoaded();
            Minecraft mc = Minecraft.getInstance();
            if (currentWorldId == null) {
                currentWorldId = worldId(mc);
                if (currentWorldId == null) {
                    return;
                }
            }
            String dimId = dimensionId(level);
            ChunkPos cp = chunk.getPos();
            file.observedCells(currentWorldId, dimId).add(cellKey(cp.getMinBlockX() >> 4, cp.getMinBlockZ() >> 4));
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] portal-memory observe failed: {}", t.toString());
        }
    }

    private void onClientTick(Minecraft mc) {
        if (++tickCounter % PERIODIC_INTERVAL != 0) {
            return;
        }
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        try {
            ensureLoaded();
            if (currentWorldId == null) {
                currentWorldId = worldId(mc);
                if (currentWorldId == null) {
                    return;
                }
            }
            String dimId = dimensionId(level);
            long gameTime = level.getLevelData().getGameTime(); // ⑤⑦B 永続 game-time 基準 (両世代共通 API)
            // ⑤⑦B 復元直後の seed: join 後の最初の tick で全ポータルの lastConfirmed を現 game-time へ＝復元直後は full grace。
            if (joinSeedPending) {
                seedLastConfirmed(gameTime);
                removalSuppressedUntilGameTime = gameTime + SETTLE_GAMETIME; // join settle
                joinSeedPending = false;
            }
            // ⑤⑦B dimension 切替の settle: 入場直後はチャンク確定まで除去抑止 (OW↔ネザー往復もカバー)。
            if (!dimId.equals(reconcileLastDim)) {
                reconcileLastDim = dimId;
                removalSuppressedUntilGameTime = Math.max(removalSuppressedUntilGameTime, gameTime + SETTLE_GAMETIME);
            }
            markVisited(dimId);
            promoteLiveRecords(level, dimId, gameTime);
            reconcile(level, dimId, gameTime);
            ensureNumbered(); // ㉛ 全 dim の number==0 を一意化 (別 dim の N-0 残りを根治)
            confirmLinks(); // ㉙ 開いて繋がった OW↔ネザーの確定ペアを永続記録
            maybePeriodicSave(); // ⑤⑦C 構造変化があればデバウンス保存 (unclean exit 対策)
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] portal-memory tick failed (continuing): {}", t.toString());
        }
    }

    // ── 蓄積 / 整合 ─────────────────────────────────────────────────────

    /**
     * ㉛ 現 world の<b>全ディメンションの全ポータル</b>に、 dim 別ユニーク連番を保証する (number==0 のものだけ
     * 既存 max の続きから割当て・以後リシャッフルしない)。 ㉚ の {@link #promoteLiveRecords} は<b>現 dim の
     * ライブ確認分のみ</b>遡及採番していたため、 別 dim (例 OW に居る間のネザー) のポータルが number==0 のまま
     * 残り「N-0」が量産されていた。 ここはロード済み/現在地に依存せず全件を一意化する (= 選択キー衝突の根治)。
     */
    private void ensureNumbered() {
        if (file == null || currentWorldId == null) {
            return;
        }
        for (Map.Entry<String, List<MemoryPortal>> de : file.worldPortals(currentWorldId).entrySet()) {
            String dimId = de.getKey();
            List<MemoryPortal> list = de.getValue();
            int max = 0;
            for (MemoryPortal mp : list) {
                max = Math.max(max, mp.number);
            }
            boolean changed = false;
            for (MemoryPortal mp : list) {
                if (mp.number == 0) {
                    mp.number = ++max; // 既存 max の続き＝既採番と衝突しない
                    changed = true;
                }
            }
            // 採番カウンタを max の先へ進める (次の新規が衝突しないように)。
            Map<String, Integer> byDim = file.nextGateNumber.computeIfAbsent(currentWorldId, k -> new java.util.HashMap<>());
            byDim.put(dimId, Math.max(byDim.getOrDefault(dimId, 1), max + 1));
            if (changed) {
                markDirty(); // ⑤⑦C 採番変化＝構造変化
                VisualizeGateMod.LOGGER.info(
                        "[visualizegate] renumbered zero-numbered gates in dim={} (now unique, max={})", dimId, max);
            }
        }
    }

    /** 現ディメンションを「観測済み」に印す (UNKNOWN/WILL_CREATE 区別の被覆)。 */
    private void markVisited(String dimId) {
        List<String> v = file.visitedDims(currentWorldId);
        if (!v.contains(dimId)) {
            v.add(dimId);
        }
    }

    /** PortalIndex の現ディメンション確定レコードを記憶へ昇格 (liveConfirmed=true・⑤⑦B lastConfirmed を game-time で更新)。 */
    private void promoteLiveRecords(ClientLevel level, String dimId, long gameTime) {
        List<MemoryPortal> mem = file.dimPortals(currentWorldId, dimId);
        for (PortalRecord r : PortalIndex.get().recordsFor(level.dimension())) {
            BlockPos a = r.anchor();
            MemoryPortal mp = findByAnchor(mem, a.getX(), a.getY(), a.getZ());
            if (mp == null) {
                mp = new MemoryPortal();
                mp.dimensionId = dimId;
                mp.ax = a.getX();
                mp.ay = a.getY();
                mp.az = a.getZ();
                mp.number = file.allocateGateNumber(currentWorldId, dimId); // ㉚ 発見時に安定採番
                mem.add(mp);
                markDirty(); // ⑤⑦C 新規ゲート＝構造変化
            } else if (mp.number == 0) {
                mp.number = file.allocateGateNumber(currentWorldId, dimId); // 旧データ (番号無し) に遡及採番
                markDirty();
            }
            mp.minX = r.aabb().minX;
            mp.minY = r.aabb().minY;
            mp.minZ = r.aabb().minZ;
            mp.maxX = r.aabb().maxX;
            mp.maxY = r.aabb().maxY;
            mp.maxZ = r.aabb().maxZ;
            mp.axis = r.axis().name();
            mp.lastSeenTick = tickCounter;
            mp.lastConfirmedGameTime = gameTime; // ⑤⑦B reconcile grace の基準 (game-time・永続)
            mp.absentStreak = 0;                 // ⑤⑦B ライブ確認＝不在連続をリセット
            mp.liveConfirmed = true;
        }
    }

    /** ⑤⑦C 構造変化 (ゲート/リンクの追加・失効・採番) を記録＝次のデバウンス窓で保存。 */
    private void markDirty() {
        dirty = true;
    }

    /** ⑤⑦C 在ワールド中、 構造変化があり前回保存から {@link #SAVE_DEBOUNCE_TICKS} 経過していれば保存 (I/O 間引き)。 */
    private void maybePeriodicSave() {
        if (!dirty || tickCounter - lastSaveTick < SAVE_DEBOUNCE_TICKS) {
            return;
        }
        try {
            save(); // 成功で dirty=false (save 内)
            lastSaveTick = tickCounter;
            VisualizeGateMod.LOGGER.info("[visualizegate] portal-memory periodic save (debounced)");
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] portal-memory periodic save failed: {}", t.toString());
        }
    }

    /** ⑤⑦B 復元/join 直後に全ポータルの lastConfirmed を現 game-time へ seed (= 復元直後は full grace・nuke 防止)。 */
    private void seedLastConfirmed(long gameTime) {
        if (file == null || currentWorldId == null) {
            return;
        }
        for (List<MemoryPortal> list : file.worldPortals(currentWorldId).values()) {
            for (MemoryPortal mp : list) {
                mp.lastConfirmedGameTime = gameTime;
                mp.absentStreak = 0;
            }
        }
    }

    /**
     * ⑤⑦B 記憶ポータルの整合 (破壊済みポータルで緑が嘘にならない／復元ポータルを誤除去しない、 の両立)。
     * 除去は<b>厳密ロード済み (FULL チャンク) AND 成分 flood-fill で不在 AND game-time grace 超過 AND N 回連続不在</b>の
     * 時だけ。 settle ウィンドウ中 (join/dim 切替直後) は除去自体を停止。 未ロード/過渡は保持＝nuke しない。
     */
    private void reconcile(ClientLevel level, String dimId, long gameTime) {
        if (gameTime < removalSuppressedUntilGameTime) {
            return; // ④ settle: 入場過渡では除去しない (チャンク確定待ち)
        }
        List<MemoryPortal> mem = file.dimPortals(currentWorldId, dimId);
        for (Iterator<MemoryPortal> it = mem.iterator(); it.hasNext();) {
            MemoryPortal mp = it.next();
            // ① strict loaded: FULL ロード済みチャンクのみ判定。 未ロード/未完は「分からない」→保持＋streak リセット。
            if (!isStrictlyLoaded(level, mp.ax, mp.az)) {
                mp.absentStreak = 0;
                continue;
            }
            // ② 成分検出: 記憶 anchor を作ったのと同じ flood-fill 規律で実ポータルの存在を確認 (単ブロック読みの誤判定を排除)。
            if (portalComponentPresent(level, mp)) {
                mp.lastConfirmedGameTime = gameTime;
                mp.absentStreak = 0;
                continue; // 実在 → 保持
            }
            // ⑤ game-time grace: 最近ライブ確認済み (復元/入場 seed 含む) なら保持 (再観測待ち)。
            if (gameTime - mp.lastConfirmedGameTime <= RECONCILE_GRACE_GAMETIME) {
                continue;
            }
            // ③ 実ロード＋成分不在を N 回連続で確認した時だけ除去 (= 破壊済みの正規除去・誤除去ゼロ)。
            if (++mp.absentStreak < ABSENT_STREAK_REQUIRED) {
                continue;
            }
            it.remove(); // FULL ロード済み・成分不在・grace 超過・N 連続 → 記憶を失効 (★B-1 維持)
            pruneLinksReferencing(mp.ax, mp.ay, mp.az); // ㉙ この端を含む確定ペアも剪定 (線が嘘にならない)
            markDirty(); // ⑤⑦C 失効＝構造変化
            VisualizeGateMod.LOGGER.info(
                    "[visualizegate] reconcile removed portal dim={} anchor=({},{},{}) (absent x{} + grace passed)",
                    dimId, mp.ax, mp.ay, mp.az, ABSENT_STREAK_REQUIRED);
        }
    }

    /**
     * ⑤⑦B ① 厳密ロード判定: チャンクが<b>FULL ステータス</b>で取得できる時だけ true (= 入場過渡の「hasChunk は true
     * だがブロック未同期」を除外＝過渡の空気読みで誤除去しない)。 未ロード/未完は false＝reconcile は保持。
     */
    private boolean isStrictlyLoaded(ClientLevel level, int blockX, int blockZ) {
        ChunkAccess ca = level.getChunk(blockX >> 4, blockZ >> 4, ChunkStatus.FULL, false);
        return ca instanceof LevelChunk;
    }

    /**
     * ⑤⑦B ② 成分検出: 記憶ポータルの位置に実 NETHER_PORTAL があるかを<b>記憶 anchor を作ったのと同じ flood-fill 規律</b>で
     * 確認する。 anchor 単ブロックが空気でも、 記憶 AABB 内に portal ブロックが 1 つでもあれば存在とみなす (anchor ±1
     * ドリフト/部分ロード耐性)。 未ロード隣接は読みに行かない。 異常サイズの AABB は走査せず不在扱い (暴走ガード)。
     */
    private boolean portalComponentPresent(ClientLevel level, MemoryPortal mp) {
        if (level.getBlockState(new BlockPos(mp.ax, mp.ay, mp.az)).getBlock() == Blocks.NETHER_PORTAL) {
            return true; // fast path: anchor がそのまま portal
        }
        int x0 = (int) Math.floor(mp.minX);
        int y0 = (int) Math.floor(mp.minY);
        int z0 = (int) Math.floor(mp.minZ);
        int x1 = (int) Math.floor(mp.maxX);
        int y1 = (int) Math.floor(mp.maxY);
        int z1 = (int) Math.floor(mp.maxZ);
        if (x1 - x0 > 16 || y1 - y0 > 32 || z1 - z0 > 16 || x1 < x0 || y1 < y0 || z1 < z0) {
            return false; // 異常/未記録 AABB は走査しない
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue; // 未ロード隣接は読まない (force-load/NPE 回避)
                }
                for (int y = y0; y <= y1; y++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.NETHER_PORTAL) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** ㉙ 指定 anchor をどちらかの端に持つ確定ペアを除去 (ポータル失効に追従)。 */
    private void pruneLinksReferencing(int x, int y, int z) {
        List<MemoryLink> ls = file.worldLinks(currentWorldId);
        ls.removeIf(l -> l.referencesAnchor(x, y, z));
    }

    /**
     * ㉙ 現 world の OW ポータルを NETHER へ予測し、 LINKED (半径内一致＝開いて繋がった) ペアを確定記録へ upsert。
     * 両端は記憶済み (= 検出済み活性) ポータルなので「開いて繋がった」を満たす。 既存は lastConfirmedTick 更新、
     * 新規は追加。 記録から点群の接続線を再描画＝セッションをまたいで残る。
     */
    private void confirmLinks() {
        List<DomainPortal> ow = knownInDimension(PortalDimension.OVERWORLD);
        List<DomainPortal> nether = knownInDimension(PortalDimension.NETHER);
        if (ow.isEmpty() || nether.isEmpty()) {
            return;
        }
        List<MemoryLink> ls = file.worldLinks(currentWorldId);
        for (DomainPortal o : ow) {
            LinkPrediction pred = PortalLinkResolver.predict(o.anchor(), PortalDimension.OVERWORLD,
                    PortalDimension.NETHER, NETHER_MIN_Y, NETHER_MAX_Y, nether,
                    NETHER_SEARCH_RADIUS, ideal -> false);
            if (pred.state() != PredictedLinkState.LINKED || pred.matched().isEmpty()) {
                continue;
            }
            GridPos n = pred.matched().get().anchor();
            MemoryLink link = new MemoryLink(o.anchor().x(), o.anchor().y(), o.anchor().z(),
                    n.x(), n.y(), n.z(), tickCounter);
            MemoryLink existing = findLink(ls, link.key());
            if (existing != null) {
                existing.lastConfirmedTick = tickCounter;
            } else {
                ls.add(link);
                markDirty(); // ⑤⑦C 新規確定リンク＝構造変化
                VisualizeGateMod.LOGGER.info("[visualizegate] confirmed gate link {} (persisted)", link.key());
            }
        }
    }

    private static MemoryLink findLink(List<MemoryLink> ls, String key) {
        for (MemoryLink l : ls) {
            if (l.key().equals(key)) {
                return l;
            }
        }
        return null;
    }

    private static MemoryPortal findByAnchor(List<MemoryPortal> mem, int x, int y, int z) {
        for (MemoryPortal mp : mem) {
            if (mp.ax == x && mp.ay == y && mp.az == z) {
                return mp;
            }
        }
        return null;
    }

    // ── 機能2/1 へのクエリ ──────────────────────────────────────────────

    /** 指定ディメンションの記憶済みポータルを domain 型で返す (Resolver 入力)。 */
    public List<DomainPortal> knownInDimension(PortalDimension dim) {
        List<DomainPortal> out = new ArrayList<>();
        if (file == null || currentWorldId == null) {
            return out;
        }
        for (List<MemoryPortal> list : file.worldPortals(currentWorldId).values()) {
            for (MemoryPortal mp : list) {
                if (dimOf(mp.dimensionId) == dim) {
                    out.add(new DomainPortal(dim, new GridPos(mp.ax, mp.ay, mp.az),
                            mp.liveConfirmed, mp.lastSeenTick));
                }
            }
        }
        return out;
    }

    /**
     * ㉙ 現 world の確定接続ペアを {@code int[]{owX,owY,owZ,nX,nY,nZ}} のリストで返す (capture 用不変コピー)。
     * 点群の接続線はここから引かれ、 セッションをまたいで残る (毎セッションの再解決に依存しない)。
     */
    public List<int[]> confirmedLinks() {
        List<int[]> out = new ArrayList<>();
        if (file == null || currentWorldId == null) {
            return out;
        }
        for (MemoryLink l : file.worldLinks(currentWorldId)) {
            out.add(new int[] { l.owX, l.owY, l.owZ, l.nX, l.nY, l.nZ });
        }
        return out;
    }

    /**
     * ㉚ 現 world の全ゲートを採番付き {@link GateNode} で返す (<b>OW 先・ネザー後</b>の順＝点群ゲート配列と一致)。
     * コンフリクト解析と点群スナップショットのゲート列の素。 capture 用不変コピー。
     */
    public List<GateNode> gateNodes() {
        List<GateNode> ow = new ArrayList<>();
        List<GateNode> nether = new ArrayList<>();
        if (file == null || currentWorldId == null) {
            return ow;
        }
        ensureNumbered(); // ㉛ 出力前に全ゲートをユニーク採番 (capture 経路でも N-0 を出さない)
        for (List<MemoryPortal> list : file.worldPortals(currentWorldId).values()) {
            for (MemoryPortal mp : list) {
                PortalDimension d = dimOf(mp.dimensionId);
                if (d == PortalDimension.OVERWORLD) {
                    ow.add(new GateNode(mp.number, d, mp.ax, mp.ay, mp.az));
                } else if (d == PortalDimension.NETHER) {
                    nether.add(new GateNode(mp.number, d, mp.ax, mp.ay, mp.az));
                }
            }
        }
        ow.addAll(nether); // OW 先・ネザー後
        return ow;
    }

    /** ㉙ capture 直前に確定ペアを最新化 (両 dim の記憶が揃っていれば即記録)。 メインスレッドで呼ぶこと。 */
    public void refreshConfirmedLinks() {
        try {
            ensureLoaded();
            if (currentWorldId != null) {
                confirmLinks();
            }
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] confirmLinks refresh failed: {}", t.toString());
        }
    }

    /** ポータル枠の寸法 (ブロック単位の各軸スパン)。 厚み軸は ~1.0、 幅軸=横幅、 dy=高さ。 */
    public record FrameExtents(double dx, double dy, double dz) {
    }

    /**
     * 指定ディメンションの指定アンカー (グローバル最低コーナー) を持つ記憶ポータルの寸法を返す。
     * 機能1 ホログラム枠が matched ポータルの axis/サイズを再現するために使う (anchor は予測の matched 由来)。
     */
    public java.util.Optional<FrameExtents> frameExtentsAt(PortalDimension dim, GridPos anchor) {
        if (file == null || currentWorldId == null) {
            return java.util.Optional.empty();
        }
        for (List<MemoryPortal> list : file.worldPortals(currentWorldId).values()) {
            for (MemoryPortal mp : list) {
                if (dimOf(mp.dimensionId) == dim
                        && mp.ax == anchor.x() && mp.ay == anchor.y() && mp.az == anchor.z()) {
                    return java.util.Optional.of(
                            new FrameExtents(mp.maxX - mp.minX, mp.maxY - mp.minY, mp.maxZ - mp.minZ));
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** 対象ディメンションを観測済みか (未観測なら Resolver は UNKNOWN を返す・被覆ヒューリスティック)。 */
    public boolean targetRegionObserved(PortalDimension dim) {
        if (file == null || currentWorldId == null) {
            return false;
        }
        for (String dimId : file.visitedDims(currentWorldId)) {
            if (dimOf(dimId) == dim) {
                return true;
            }
        }
        return false;
    }

    public String currentWorldId() {
        return currentWorldId;
    }

    // ── ㉝ 表示用メタ (name / hidden)・anchor 単位・即永続 ──────────────────

    private MemoryPortal findAt(PortalDimension dim, int x, int y, int z) {
        if (file == null || currentWorldId == null) {
            return null;
        }
        for (List<MemoryPortal> list : file.worldPortals(currentWorldId).values()) {
            for (MemoryPortal mp : list) {
                if (dimOf(mp.dimensionId) == dim && mp.ax == x && mp.ay == y && mp.az == z) {
                    return mp;
                }
            }
        }
        return null;
    }

    /**
     * ㉝B 指定 anchor のゲートのユーザー命名 (null/空＝既定名に戻す)。 最大長は呼び出し側 (EditBox) でクランプ。
     * anchorKey 単位で {@code visualizegate-portals.json} へ即永続 (再起動後も残る)。
     */
    public void setName(PortalDimension dim, int x, int y, int z, String name) {
        try {
            ensureLoaded();
            MemoryPortal mp = findAt(dim, x, y, z);
            if (mp == null) {
                return;
            }
            mp.name = (name == null || name.isBlank()) ? null : name.trim();
            save();
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] setName failed: {}", t.toString());
        }
    }

    /** ㉝B 指定 anchor のゲートのユーザー命名 (無ければ null)。 一覧/3D ラベルが既定名より優先表示する。 */
    public String nameAt(PortalDimension dim, int x, int y, int z) {
        MemoryPortal mp = findAt(dim, x, y, z);
        return (mp == null || mp.name == null || mp.name.isBlank()) ? null : mp.name;
    }

    /**
     * ㉝C 表示版カウンタ。 {@link #setHidden} で +1。 描画側 (点群の GPU3D/texbatch ジオメトリ署名) がこれを
     * 含めることで、 hidden トグルが Re-analyze なしで即座に再構築/反映される (= 表示専用・解析/採番は不変)。
     */
    private static int displayVersion = 0;

    public static int displayVersion() {
        return displayVersion;
    }

    /** ㉝C 指定 anchor のゲートの表示/非表示を設定 (anchorKey 単位で即永続)。 表示版カウンタを進める。 */
    public void setHidden(PortalDimension dim, int x, int y, int z, boolean hidden) {
        try {
            ensureLoaded();
            MemoryPortal mp = findAt(dim, x, y, z);
            if (mp == null || mp.hidden == hidden) {
                return;
            }
            mp.hidden = hidden;
            displayVersion++;
            save();
        } catch (Throwable t) {
            VisualizeGateMod.LOGGER.warn("[visualizegate] setHidden failed: {}", t.toString());
        }
    }

    /** ㉝C 指定 anchor のゲートが非表示か (見つからなければ false)。 3D 描画 (marker/label/link) が skip 判定に使う。 */
    public boolean isHidden(PortalDimension dim, int x, int y, int z) {
        MemoryPortal mp = findAt(dim, x, y, z);
        return mp != null && mp.hidden;
    }

    // ── world-id / dim-id ───────────────────────────────────────────────

    /** SP=セーブ名 / MP=サーバアドレス。 取得不能なら null。 */
    private static String worldId(Minecraft mc) {
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        ServerData sd = mc.getCurrentServer();
        if (sd != null && sd.ip != null) {
            return "mp:" + sd.ip;
        }
        return null;
    }

    /** 現ディメンションの id 文字列 (例 "minecraft:the_nether")。 */
    private static String dimensionId(ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    /** dim id 文字列 → domain enum。 */
    public static PortalDimension dimOf(String dimId) {
        if (dimId == null) {
            return PortalDimension.OTHER;
        }
        switch (dimId) {
            case "minecraft:overworld":
                return PortalDimension.OVERWORLD;
            case "minecraft:the_nether":
                return PortalDimension.NETHER;
            case "minecraft:the_end":
                return PortalDimension.END;
            default:
                return PortalDimension.OTHER;
        }
    }

    /** domain enum → 正規 dim id (観測セット照合用)。 OW↔Nether のみ (それ以外は null)。 */
    public static String canonicalDimId(PortalDimension dim) {
        switch (dim) {
            case OVERWORLD:
                return "minecraft:overworld";
            case NETHER:
                return "minecraft:the_nether";
            case END:
                return "minecraft:the_end";
            default:
                return null;
        }
    }

    /**
     * 指定ディメンションの指定ブロック XZ が属するリージョンセルを観測済みか。
     * Resolver の「理想ターゲット周辺が観測済みか」 判定に使う (未観測なら UNKNOWN=灰)。
     */
    public boolean isRegionObserved(PortalDimension dim, int blockX, int blockZ) {
        if (file == null || currentWorldId == null) {
            return false;
        }
        String dimId = canonicalDimId(dim);
        if (dimId == null) {
            return false;
        }
        long key = cellKey(blockX >> 4, blockZ >> 4);
        Map<String, java.util.Set<Long>> byDim = file.observed.get(currentWorldId);
        if (byDim == null) {
            return false;
        }
        java.util.Set<Long> cells = byDim.get(dimId);
        return cells != null && cells.contains(key);
    }

    /** チャンク座標 → リージョンセルキー (CELL_CHUNKS 四方で集約)。 */
    private static long cellKey(int chunkX, int chunkZ) {
        long cx = Math.floorDiv(chunkX, CELL_CHUNKS);
        long cz = Math.floorDiv(chunkZ, CELL_CHUNKS);
        return (cx & 0xFFFFFFFFL) << 32 | (cz & 0xFFFFFFFFL);
    }

    // ── 永続化 (GSON atomic) ────────────────────────────────────────────

    private void ensureLoaded() {
        if (file != null) {
            return;
        }
        Path f = path();
        if (!Files.exists(f)) {
            file = new MemoryFile();
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            MemoryFile loaded = GSON.fromJson(r, MemoryFile.class);
            file = (loaded != null) ? loaded : new MemoryFile();
        } catch (Exception ex) {
            VisualizeGateMod.LOGGER.warn(
                    "[visualizegate] portal-memory load failed ({}), starting empty", ex.toString());
            file = new MemoryFile();
        }
    }

    private void save() throws IOException {
        if (file == null) {
            return;
        }
        Path f = path();
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(GSON.toJson(file));
        }
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false; // ⑤⑦C 保存成功＝未保存変更なし (定期保存/leave/setName/setHidden 全経路で共通)
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
