package com.kajiwara.visualizegate.client.command;

import java.util.ArrayList;
import java.util.List;

import com.kajiwara.visualizegate.client.render.GateNameLabelRenderer;
import com.kajiwara.visualizegate.domain.BackCalc;
import com.kajiwara.visualizegate.domain.DomainPortal;
import com.kajiwara.visualizegate.domain.GateConflict;
import com.kajiwara.visualizegate.domain.GateConflictAnalyzer;
import com.kajiwara.visualizegate.domain.GateNode;
import com.kajiwara.visualizegate.domain.GateState;
import com.kajiwara.visualizegate.domain.GridPos;
import com.kajiwara.visualizegate.domain.PortalCoordinateMapper;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.config.GateConfigManager;
import com.kajiwara.visualizegate.state.GateMenuState;
import com.kajiwara.visualizegate.state.BackCalcStore;
import com.kajiwara.visualizegate.state.PointCloudViewState;
import com.kajiwara.visualizegate.state.VgOverlayState;
import com.kajiwara.visualizegate.ui.GateColors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;*/
//?}
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * ㉕ クライアント専用 `/vg` コマンド (Brigadier・サーバー非依存)。
 *
 * <p>サーバーに同名コマンドが無くても自分のクライアントで動く ({@link ClientCommandRegistrationCallback})。
 * 不正引数は Brigadier 標準の赤表示で弾かれる。 サブコマンド:
 * <ul>
 *   <li>{@code /vg back-calculate <x> <y> <z> [ow|nether]} — 逆算してワイヤーフレームを積む。</li>
 *   <li>{@code /vg back-calculate here [ow|nether]} — {@code <x y z>} を現在のプレイヤー座標として扱う。</li>
 *   <li>{@code /vg point-cloud} — 右下に点群 HUD ウィジェットを常時表示 (トグル・{@link VgOverlayState})。</li>
 *   <li>{@code /vg visualize} — 全ゲート関係のワイヤーフレーム (枠＋リンク線・5 状態色) を in-world 表示 (トグル)。</li>
 *   <li>{@code /vg dock} — ㊸A ドックの展開/畳みトグル (専用キーバインドと同一)。 展開＝フルメニュー
 *       (パフォーマンス [フレーム時間＋CPU の 2 スパークライン＋注記] ＋ ゲート状態 5 色 ＋ 注記 4 を常時表示)。
 *       旧 {@code /vg perf}/gpu-usage/cpu-usage は廃止＝perf はこのフルメニューに常設。</li>
 *   <li>{@code /vg clean} — 全 {@link VgOverlayState} オーバーレイ OFF ＋ {@link BackCalcStore#clear()}
 *       (どのモードにも効く一括停止・自動消滅せず意志で消す)。</li>
 *   <li>{@code /vg} (引数なし) / {@code /vg help} — ㊷B サブコマンド一覧＋現在の ON/OFF 状態を表示。</li>
 * </ul>
 *
 * <p><b>向きの定義</b>: ターゲット {@code (x,y,z)} はプレイヤーがいる次元の<b>逆側</b>に出したいゲートの到達目標。
 * 建設推奨 (緑) は<b>現在いる次元側</b>に出す (そこに建てれば逆側の T 付近に出る)。 {@code [ow|nether]} 指定時は
 * ターゲット側次元をそれで上書き。 既存ポータルが対象次元の探索半径内 → <b>赤</b> (吸い込み警告・ターゲット側)、
 * 無ければ <b>緑</b> (新規生成見込み・現在側)。
 *
 * <p>登録ビルダのみ版差 (26.1+={@code ClientCommands} / legacy={@code ClientCommandManager})。
 * source/Minecraft アクセスは全版同名 ({@code sendFeedback} / {@code Minecraft.getInstance()})。
 */
public final class VgCommands {

    // 次元境界 (Y クランプ用・バニラ datapack 値: nether 0..127 / overworld -64..319)。
    private static final int OW_MIN_Y = -64;
    private static final int OW_MAX_Y = 319;
    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;

    // 既存ポータル探索半径 (PortalForcer 現物: OVERWORLD_PORTAL_RADIUS=128 / NETHER_PORTAL_RADIUS=16)。
    private static final double OW_RADIUS = 128.0;
    private static final double NETHER_RADIUS = 16.0;

    private VgCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> build(dispatcher));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("vg");

        // /vg clean — ㉟ どのモードにも効く一括停止 (全 /vg オーバーレイ OFF ＋ 逆算ワイヤーフレーム消去)。
        root.then(literal("clean").executes(c -> {
            int n = BackCalcStore.size();
            VgOverlayState.clearAll();
            // ⑤⑥ パネルも明示 OFF＋永続 (clean=全 OFF)。 cloud-only も解除。 dock 元フラグは clearAll が畳む。
            PointCloudViewState.setPanelVisible(false);
            PointCloudViewState.setCloudOnly(false);
            GateConfigManager.save();
            BackCalcStore.clear();
            c.getSource().sendFeedback(Component.translatable("visualizegate.cmd.cleanall", n));
            return 1;
        }));

        // ㉟ オーバーレイ トグル (複数同時可・既定 OFF・切断でリセット・永続なし)。 再実行で OFF。
        LiteralArgumentBuilder<FabricClientCommandSource> pc = literal("point-cloud").executes(c -> {
            // ㊽B ドックの点群は DockRadar (ライブ局所レーダー) が自走で生成＝ここで whole-world 解析は不要。
            boolean on = VgOverlayState.togglePointCloud();
            return feedbackToggle(c, "visualizegate.cmd.pointcloud", on);
        });
        // ⑤⑤ only <detail|compact|off>: 点群ソロ表示＋密度指定／明示解除 (密度は /vg detail と同一 state を共有)。
        pc.then(literal("only")
                .then(literal("detail").executes(c -> onlyDensity(c, true)))
                .then(literal("compact").executes(c -> onlyDensity(c, false)))
                .then(literal("off").executes(VgCommands::onlyOff)));
        // ⑤⑥ show: パネル表示＋ソロ解除 (パネル＋ドック通常＝両表示に復帰)。
        pc.then(literal("show").executes(VgCommands::show));
        root.then(pc);
        root.then(literal("visualize").executes(
                c -> feedbackToggle(c, "visualizegate.cmd.visualize", VgOverlayState.toggleVisualize())));
        // ㊲ ドック展/畳トグル (専用キーバインドと同一動作)。
        root.then(literal("dock").executes(
                c -> feedbackToggle(c, "visualizegate.cmd.dock", VgOverlayState.toggleDock())));
        // ⑤④ 右下点群パネルのオーバーレイ詳細度トグル (簡略↔詳細・GateConfig 永続・既定=簡略)。
        root.then(literal("detail").executes(c -> {
            boolean on = PointCloudViewState.toggleOverlayDetail();
            GateConfigManager.save();
            return feedbackToggle(c, "visualizegate.cmd.detail", on);
        }));
        // ゲート名ラベル トグル (各実ポータル上の在世界名前表示・状態色・SEE_THROUGH・GateConfig 永続・既定 ON)。
        root.then(literal("names").executes(c -> {
            boolean on = GateMenuState.toggleGateNames();
            GateConfigManager.save();
            return feedbackToggle(c, "visualizegate.cmd.names", on);
        }));
        // 競合解決: 赤(競合)ゲートに対し、 相手を専有できる<b>安全建設位置</b>を探して在世界表示する。
        root.then(literal("resolving-conflict")
                .then(argument("name", StringArgumentType.greedyString())
                        .executes(c -> runResolveConflict(c, StringArgumentType.getString(c, "name")))));

        // ㊸A `/vg perf` は廃止 (perf はドック展開＝フルメニューで常時表示)。

        // ㊷B 一覧/状態: `/vg` (引数なし) と `/vg help` でサブコマンド一覧＋現在の ON/OFF＋dock 状態を表示。
        root.executes(VgCommands::showHelp);
        root.then(literal("help").executes(VgCommands::showHelp));

        // /vg back-calculate ...
        LiteralArgumentBuilder<FabricClientCommandSource> back = literal("back-calculate");

        // here [ow|nether]
        back.then(literal("here")
                .executes(c -> runHere(c, null))
                .then(literal("ow").executes(c -> runHere(c, PortalDimension.OVERWORLD)))
                .then(literal("nether").executes(c -> runHere(c, PortalDimension.NETHER))));

        // <x> <y> <z> [ow|nether]
        back.then(argument("x", DoubleArgumentType.doubleArg())
                .then(argument("y", DoubleArgumentType.doubleArg())
                        .then(argument("z", DoubleArgumentType.doubleArg())
                                .executes(c -> runXyz(c, null))
                                .then(literal("ow").executes(c -> runXyz(c, PortalDimension.OVERWORLD)))
                                .then(literal("nether").executes(c -> runXyz(c, PortalDimension.NETHER))))));

        root.then(back);
        dispatcher.register(root);
    }

    private static int runHere(CommandContext<FabricClientCommandSource> c, PortalDimension override) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            c.getSource().sendError(Component.translatable("visualizegate.cmd.no_player"));
            return 0;
        }
        return run(c, Math.floor(player.getX()), Math.floor(player.getY()), Math.floor(player.getZ()), override);
    }

    private static int runXyz(CommandContext<FabricClientCommandSource> c, PortalDimension override) {
        double x = DoubleArgumentType.getDouble(c, "x");
        double y = DoubleArgumentType.getDouble(c, "y");
        double z = DoubleArgumentType.getDouble(c, "z");
        return run(c, x, y, z, override);
    }

    /** 逆算本体 (向き既定・赤/緑判定・要素追加・HUD/チャット解釈表示)。 */
    private static int run(CommandContext<FabricClientCommandSource> c,
            double tx, double ty, double tz, PortalDimension override) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            c.getSource().sendError(Component.translatable("visualizegate.cmd.no_player"));
            return 0;
        }
        PortalDimension cur = PortalMemory.dimOf(level.dimension().identifier().toString());
        if (cur != PortalDimension.OVERWORLD && cur != PortalDimension.NETHER) {
            c.getSource().sendError(Component.translatable("visualizegate.cmd.wrongdim"));
            return 0;
        }
        // ターゲット側 = override か、 既定はプレイヤーの逆側。
        PortalDimension target = (override != null)
                ? override
                : (cur == PortalDimension.OVERWORLD ? PortalDimension.NETHER : PortalDimension.OVERWORLD);

        GridPos t = new GridPos((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz));
        int curMinY = (cur == PortalDimension.NETHER) ? NETHER_MIN_Y : OW_MIN_Y;
        int curMaxY = (cur == PortalDimension.NETHER) ? NETHER_MAX_Y : OW_MAX_Y;
        double radius = (target == PortalDimension.NETHER) ? NETHER_RADIUS : OW_RADIUS;

        List<DomainPortal> known = PortalMemory.get().knownInDimension(target);
        boolean observed = PortalMemory.get().isRegionObserved(target, t.x(), t.z());
        BackCalc.Result r = BackCalc.compute(t, target, cur, curMinY, curMaxY, known, radius, observed);

        // 採用解釈 (HUD/チャット): target=<dim>(x,y,z) / build in <dim>。 ㊺E 金=解釈ヘッダ。
        c.getSource().sendFeedback(colored(GateColors.ACCENT, "visualizegate.cmd.interp",
                dimName(target), t.x(), t.y(), t.z(), dimName(cur)));

        if (r.kind() == BackCalc.Kind.EXISTING_IN_TARGET) {
            // 既存ありゾーン → 吸い込み警告の赤を<b>ターゲット側次元</b>の既存ポータル位置に出す。
            GridPos a = r.existing().get().anchor();
            BackCalcStore.add(new BackCalcStore.Element(target,
                    a.x() + 0.5, a.y(), a.z() + 0.5, GateColors.LINK_RED, true));
            // ㊺E 文も赤 (吸い込み警告)＝ワイヤーフレームと同色。
            c.getSource().sendFeedback(colored(GateColors.LINK_RED, "visualizegate.cmd.existing",
                    String.format("%.0f", r.existingDistance()), dimName(target), a.x(), a.y(), a.z()));
        } else {
            // 既存なし → 新規生成見込みの緑を<b>現在次元側</b>の建設推奨ボックスに出す。
            GridPos b = r.buildPos();
            BackCalcStore.add(new BackCalcStore.Element(cur,
                    b.x() + 0.5, b.y(), b.z() + 0.5, GateColors.LINK_GREEN, false));
            // ㊺E 文も緑 (新規生成見込み)＝ワイヤーフレームと同色。 ドックの 5 状態色 (STATE_*) とは別系統の
            //     back-calculate 専用色 (LINK_GREEN) を使い、 「正常=緑」の状態語と混同させない。
            c.getSource().sendFeedback(colored(GateColors.LINK_GREEN, "visualizegate.cmd.new",
                    dimName(cur), b.x(), b.y(), b.z()));
            if (!observed) {
                // クライアント観測範囲外の既存は判定不能 → 誤断定しない注記 (㊺E 淡色)。
                c.getSource().sendFeedback(colored(GateColors.LINK_GRAY, "visualizegate.cmd.unconfirmed"));
            }
        }
        c.getSource().sendFeedback(colored(GateColors.LINK_GRAY, "visualizegate.cmd.added")); // ㊺E 淡色ヒント
        return 1;
    }

    /** 競合解決の探索リング上限 (各軸 step 数)。 OW reach=96×16=1536 / Nether reach=96×2=192 ブロック。 */
    private static final int RESOLVE_MAX_RINGS = 96;

    /**
     * `/vg resolving-conflict <name>`: 名前で引いた<b>赤(競合)ゲート</b>に対し、 相手を専有できる
     * 安全建設位置 B を探す。 B = 現次元の有効位置で、 相手次元へ写像した座標が<b>既知の全既存ポータルの
     * 探索半径外</b> (= バニラが新規生成し既存に吸われない) を満たす最近傍 ({@link BackCalc#compute} を述語に使用)。
     * 競合元=赤・取り合い相手=橙・安全位置=緑のワイヤーフレーム＋名前/座標ピンを {@link BackCalcStore} へ積む
     * (= `/vg clean` で一括消去・自動消滅なし)。 観測範囲外は判定不能なので注記する (誤断定しない)。
     */
    private static int runResolveConflict(CommandContext<FabricClientCommandSource> c, String name) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            c.getSource().sendError(Component.translatable("visualizegate.cmd.no_player"));
            return 0;
        }
        PortalDimension playerDim = PortalMemory.dimOf(level.dimension().identifier().toString());

        List<GateNode> nodes = PortalMemory.get().gateNodes();
        GateConflictAnalyzer.Result an = GateConflictAnalyzer.analyze(
                nodes, NETHER_MIN_Y, NETHER_MAX_Y, OW_MIN_Y, OW_MAX_Y);

        // 名前 → ゲート (表示名＝ユーザー命名 or 既定 OW-/N-<番号>・大小無視で照合)。
        int idx = -1;
        String trimmed = name.trim();
        for (int i = 0; i < nodes.size(); i++) {
            if (GateNameLabelRenderer.displayName(nodes.get(i)).equalsIgnoreCase(trimmed)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            c.getSource().sendError(Component.translatable("visualizegate.cmd.gatenotfound", trimmed));
            return 0;
        }
        GateNode gate = nodes.get(idx);
        if (an.states()[idx] != GateState.CONFLICT) {
            // 競合でないゲートには何もしない (誤動作防止)。 現状態を添えて通知。
            c.getSource().sendFeedback(Component.translatable("visualizegate.cmd.notconflict",
                    trimmed, Component.translatable(state5Key(an.states()[idx]))));
            return 0;
        }

        PortalDimension conflictDim = gate.dim();
        PortalDimension otherDim = (conflictDim == PortalDimension.OVERWORLD)
                ? PortalDimension.NETHER : PortalDimension.OVERWORLD;
        int cMinY = (conflictDim == PortalDimension.NETHER) ? NETHER_MIN_Y : OW_MIN_Y;
        int cMaxY = (conflictDim == PortalDimension.NETHER) ? NETHER_MAX_Y : OW_MAX_Y;
        int oMinY = (otherDim == PortalDimension.NETHER) ? NETHER_MIN_Y : OW_MIN_Y;
        int oMaxY = (otherDim == PortalDimension.NETHER) ? NETHER_MAX_Y : OW_MAX_Y;
        double otherRadius = (otherDim == PortalDimension.NETHER) ? NETHER_RADIUS : OW_RADIUS;
        List<DomainPortal> knownOther = PortalMemory.get().knownInDimension(otherDim);

        // 探索起点: プレイヤーが競合次元に居れば照準ブロック (無ければ足元)、 居なければ競合ゲート自身。
        int ox;
        int oy;
        int oz;
        if (playerDim == conflictDim) {
            BlockPos hit = crosshairBlock(mc);
            if (hit != null) {
                ox = hit.getX();
                oy = hit.getY();
                oz = hit.getZ();
            } else {
                ox = (int) Math.floor(player.getX());
                oy = (int) Math.floor(player.getY());
                oz = (int) Math.floor(player.getZ());
            }
        } else {
            ox = gate.x();
            oy = gate.y();
            oz = gate.z();
        }
        oy = Math.max(cMinY, Math.min(cMaxY, oy));
        int step = (conflictDim == PortalDimension.OVERWORLD) ? 16 : 2;

        GridPos safe = searchSafeBuildPos(ox, oy, oz, step, conflictDim, otherDim,
                cMinY, cMaxY, oMinY, oMaxY, knownOther, otherRadius);

        // 取り合い相手の特定 (当該ゲートを含む CONFLICT の他端)。
        List<GateNode> partners = findPartners(an, gate, nodes);

        // ヘッダ (金)。
        c.getSource().sendFeedback(colored(GateColors.ACCENT, "visualizegate.resolveconflict.header",
                trimmed, dimName(conflictDim)));

        // 競合元=赤・取り合い相手=橙 を常に提示 (解が無くても状況を可視化)。
        BackCalcStore.add(new BackCalcStore.Element(conflictDim,
                gate.x() + 0.5, gate.y(), gate.z() + 0.5, GateColors.STATE_CONFLICT, true,
                trimmed, GateColors.STATE_CONFLICT));
        if (!partners.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (GateNode p : partners) {
                BackCalcStore.add(new BackCalcStore.Element(p.dim(),
                        p.x() + 0.5, p.y(), p.z() + 0.5, GateColors.CROSSTALK, true,
                        GateNameLabelRenderer.displayName(p), GateColors.CROSSTALK));
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(GateNameLabelRenderer.displayName(p));
            }
            c.getSource().sendFeedback(colored(GateColors.CROSSTALK,
                    "visualizegate.resolveconflict.partners", names.toString()));
        }

        if (safe == null) {
            // ★2 探索範囲内に安全位置が無い → 明示通知 (誤動作/無反応にしない)。
            c.getSource().sendFeedback(colored(GateColors.LINK_GRAY,
                    "visualizegate.resolveconflict.none", RESOLVE_MAX_RINGS * step));
            c.getSource().sendFeedback(colored(GateColors.LINK_GRAY, "visualizegate.cmd.added"));
            return 1;
        }

        // 安全位置 = 緑ボックス + 名前&座標ピン (現次元なら在世界・逆側なら点群スタックで見える)。
        String pinLabel = trimmed + "  " + safe.x() + " " + safe.y() + " " + safe.z();
        BackCalcStore.add(new BackCalcStore.Element(conflictDim,
                safe.x() + 0.5, safe.y(), safe.z() + 0.5, GateColors.LINK_GREEN, false,
                pinLabel, GateColors.LINK_GREEN));
        c.getSource().sendFeedback(colored(GateColors.LINK_GREEN, "visualizegate.resolveconflict.safe",
                dimName(conflictDim), safe.x(), safe.y(), safe.z()));

        // 観測範囲外は既存を断定できない → 注記 (back-calculate と同原則)。
        GridPos projB = PortalCoordinateMapper.project(safe, conflictDim, otherDim, oMinY, oMaxY);
        if (!PortalMemory.get().isRegionObserved(otherDim, projB.x(), projB.z())) {
            c.getSource().sendFeedback(colored(GateColors.LINK_GRAY, "visualizegate.cmd.unconfirmed"));
        }
        c.getSource().sendFeedback(colored(GateColors.LINK_GRAY, "visualizegate.cmd.added"));
        return 1;
    }

    /**
     * 起点から同心リング状に走査し、 相手次元への写像が全既存ポータルの探索半径外になる最近傍の B を返す
     * (= {@link BackCalc.Kind#NEW_IN_CURRENT})。 見つからなければ null。
     */
    private static GridPos searchSafeBuildPos(int ox, int oy, int oz, int step,
            PortalDimension conflictDim, PortalDimension otherDim,
            int cMinY, int cMaxY, int oMinY, int oMaxY,
            List<DomainPortal> knownOther, double otherRadius) {
        int by = Math.max(cMinY, Math.min(cMaxY, oy));
        for (int r = 0; r <= RESOLVE_MAX_RINGS; r++) {
            if (r == 0) {
                GridPos hit = testCandidate(ox, by, oz, conflictDim, otherDim,
                        cMinY, cMaxY, oMinY, oMaxY, knownOther, otherRadius);
                if (hit != null) {
                    return hit;
                }
                continue;
            }
            for (int a = -r; a <= r; a++) {
                GridPos h1 = testCandidate(ox + a * step, by, oz - r * step, conflictDim, otherDim,
                        cMinY, cMaxY, oMinY, oMaxY, knownOther, otherRadius);
                if (h1 != null) {
                    return h1;
                }
                GridPos h2 = testCandidate(ox + a * step, by, oz + r * step, conflictDim, otherDim,
                        cMinY, cMaxY, oMinY, oMaxY, knownOther, otherRadius);
                if (h2 != null) {
                    return h2;
                }
            }
            for (int b = -r + 1; b <= r - 1; b++) {
                GridPos h3 = testCandidate(ox - r * step, by, oz + b * step, conflictDim, otherDim,
                        cMinY, cMaxY, oMinY, oMaxY, knownOther, otherRadius);
                if (h3 != null) {
                    return h3;
                }
                GridPos h4 = testCandidate(ox + r * step, by, oz + b * step, conflictDim, otherDim,
                        cMinY, cMaxY, oMinY, oMaxY, knownOther, otherRadius);
                if (h4 != null) {
                    return h4;
                }
            }
        }
        return null;
    }

    /** 1 候補 B を判定: 相手次元への写像近傍に既存が無ければ (新規生成見込み) B を返す。 */
    private static GridPos testCandidate(int bx, int by, int bz,
            PortalDimension conflictDim, PortalDimension otherDim,
            int cMinY, int cMaxY, int oMinY, int oMaxY,
            List<DomainPortal> knownOther, double otherRadius) {
        GridPos b = new GridPos(bx, by, bz);
        GridPos projB = PortalCoordinateMapper.project(b, conflictDim, otherDim, oMinY, oMaxY);
        boolean observed = PortalMemory.get().isRegionObserved(otherDim, projB.x(), projB.z());
        BackCalc.Result res = BackCalc.compute(projB, otherDim, conflictDim,
                cMinY, cMaxY, knownOther, otherRadius, observed);
        return (res.kind() == BackCalc.Kind.NEW_IN_CURRENT) ? b : null;
    }

    /** 当該ゲートを含む CONFLICT の他端ゲートを集める (取り合い相手・anchor 重複排除)。 */
    private static List<GateNode> findPartners(GateConflictAnalyzer.Result an, GateNode gate, List<GateNode> nodes) {
        List<GateNode> out = new ArrayList<>();
        for (GateConflict gc : an.conflicts()) {
            if (gc.state() != GateState.CONFLICT) {
                continue;
            }
            boolean contains = false;
            for (int k = 0; k < gc.gateNumbers().length; k++) {
                if (gc.gateNumbers()[k] == gate.number() && gc.dims()[k] == gate.dim()) {
                    contains = true;
                    break;
                }
            }
            if (!contains) {
                continue;
            }
            for (int k = 0; k < gc.gateNumbers().length; k++) {
                int num = gc.gateNumbers()[k];
                PortalDimension dim = gc.dims()[k];
                if (num == gate.number() && dim == gate.dim()) {
                    continue;
                }
                GateNode p = findNode(nodes, num, dim);
                if (p != null && !containsAnchor(out, p)) {
                    out.add(p);
                }
            }
            break; // 最重大の 1 件のみ (conflicts は重大度降順)
        }
        return out;
    }

    private static GateNode findNode(List<GateNode> nodes, int number, PortalDimension dim) {
        for (GateNode n : nodes) {
            if (n.number() == number && n.dim() == dim) {
                return n;
            }
        }
        return null;
    }

    private static boolean containsAnchor(List<GateNode> list, GateNode n) {
        for (GateNode e : list) {
            if (e.x() == n.x() && e.y() == n.y() && e.z() == n.z() && e.dim() == n.dim()) {
                return true;
            }
        }
        return false;
    }

    /** 照準先のブロック座標 (BLOCK ヒット時のみ・無ければ null)。 */
    private static BlockPos crosshairBlock(Minecraft mc) {
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            return bhr.getBlockPos();
        }
        return null;
    }

    /** {@link GateState} → state5 lang キー (notconflict の状態名表示用)。 */
    private static String state5Key(GateState s) {
        switch (s) {
            case OK:
                return "visualizegate.state5.ok";
            case ORPHAN:
                return "visualizegate.state5.orphan";
            case OFFSET:
                return "visualizegate.state5.offset";
            case WILL_CREATE:
                return "visualizegate.state5.will_create";
            default:
                return "visualizegate.state5.conflict";
        }
    }

    /**
     * ㊺E 意味で色分けしたチャットフィードバック。 色は ARGB 下位 24bit を {@code Style.withColor(int)} へ
     * (mixin で全ノード同一を確認済の API)。 文字列は lang・色は意味 (赤=警告/緑=新規/金=ヘッダ/淡=注記)。
     */
    private static Component colored(int argb, String key, Object... args) {
        return Component.translatable(key, args).withStyle(s -> s.withColor(argb & 0xFFFFFF));
    }

    /** ㊷B/㊸ サブコマンド一覧＋現在の ON/OFF 状態 (point-cloud/visualize) ＋dock 展開/畳みをチャット表示。 */
    private static int showHelp(CommandContext<FabricClientCommandSource> c) {
        FabricClientCommandSource src = c.getSource();
        src.sendFeedback(Component.translatable("visualizegate.help.header"));
        src.sendFeedback(Component.translatable("visualizegate.help.pointcloud", onOff(VgOverlayState.isPointCloud())));
        src.sendFeedback(Component.translatable("visualizegate.help.only", panelMode()));
        src.sendFeedback(Component.translatable("visualizegate.help.visualize", onOff(VgOverlayState.isVisualize())));
        src.sendFeedback(Component.translatable("visualizegate.help.dock", Component.translatable(
                VgOverlayState.isDockExpanded() ? "visualizegate.help.expanded" : "visualizegate.help.collapsed")));
        src.sendFeedback(Component.translatable("visualizegate.help.detail", onOff(PointCloudViewState.isOverlayDetail())));
        src.sendFeedback(Component.translatable("visualizegate.help.names", onOff(GateMenuState.isGateNamesEnabled())));
        src.sendFeedback(Component.translatable("visualizegate.help.resolveconflict"));
        src.sendFeedback(Component.translatable("visualizegate.help.clean"));
        src.sendFeedback(Component.translatable("visualizegate.help.backcalc"));
        return 1;
    }

    /** 状態 ON/OFF の翻訳コンポーネント (state.on/off を再利用)。 */
    private static Component onOff(boolean on) {
        return Component.translatable(on ? "visualizegate.state.on" : "visualizegate.state.off");
    }

    /** ⑤⑤/⑤⑥ /vg point-cloud only &lt;detail|compact&gt;: ソロ化＋密度設定。 ソロ中に同密度の再打ちで解除 (=off と同じ)。 */
    private static int onlyDensity(CommandContext<FabricClientCommandSource> c, boolean wantDetail) {
        if (VgOverlayState.isCloudSolo() && PointCloudViewState.isOverlayDetail() == wantDetail) {
            hidePanel(); // ⑤⑥ 同密度の再打ち＝off と同じ (パネル非表示＋ソロ解除・密度値は保持)
        } else {
            PointCloudViewState.setCloudOnly(true);
            showPanel();                                 // ⑤⑥ パネル表示 (永続ミラーも true・dock auto-expand なし)
            PointCloudViewState.setOverlayDetail(wantDetail);
        }
        GateConfigManager.save();
        return feedbackMode(c);
    }

    /** ⑤⑥ /vg point-cloud only off: パネル非表示＋ソロ解除 (非ソロでもパネルは消す)。 密度値は保持。 */
    private static int onlyOff(CommandContext<FabricClientCommandSource> c) {
        hidePanel();
        GateConfigManager.save();
        return feedbackMode(c);
    }

    /** ⑤⑥ /vg point-cloud show: パネル表示＋ソロ解除 (パネル＋ドック通常＝両表示に復帰)。 */
    private static int show(CommandContext<FabricClientCommandSource> c) {
        PointCloudViewState.setCloudOnly(false);
        showPanel();
        GateConfigManager.save();
        return feedbackMode(c);
    }

    /** ⑤⑥ パネル表示 (セッション実ゲート pointCloud＋永続ミラー panelVisible を true・dock 元フラグ非破壊)。 */
    private static void showPanel() {
        VgOverlayState.setPointCloud(true);
        PointCloudViewState.setPanelVisible(true);
    }

    /** ⑤⑥ パネル非表示＋ソロ解除 (pointCloud/panelVisible=false・cloudOnly=false・密度は保持)。 */
    private static void hidePanel() {
        VgOverlayState.setPointCloud(false);
        PointCloudViewState.setPanelVisible(false);
        PointCloudViewState.setCloudOnly(false);
    }

    /** ⑤⑥ 現在の点群パネル・モードをチャット表示 (off / show / only:detail / only:compact)。 */
    private static int feedbackMode(CommandContext<FabricClientCommandSource> c) {
        c.getSource().sendFeedback(Component.translatable("visualizegate.cmd.only", panelMode()));
        return 1;
    }

    /** ⑤⑥ 現在の点群パネル・モード文字列 (言語非依存・help/feedback 共用)。 off=非表示 / show=表示非ソロ / only:*=ソロ。 */
    private static String panelMode() {
        if (!VgOverlayState.isPointCloud()) {
            return "off";
        }
        return VgOverlayState.isCloudSolo()
                ? (PointCloudViewState.isOverlayDetail() ? "only:detail" : "only:compact")
                : "show";
    }

    /** ㉟ トグル結果を ON/OFF つきの短いチャットで返す (lang en/ja・key は %s に状態を取る)。 */
    private static int feedbackToggle(CommandContext<FabricClientCommandSource> c, String key, boolean on) {
        Component state = Component.translatable(on ? "visualizegate.state.on" : "visualizegate.state.off");
        c.getSource().sendFeedback(Component.translatable(key, state));
        return 1;
    }

    private static Component dimName(PortalDimension dim) {
        return Component.translatable(dim == PortalDimension.NETHER
                ? "visualizegate.dim.nether" : "visualizegate.dim.overworld");
    }

    // ── 登録ビルダ入口 (唯一の版差: 26.1+=ClientCommands / legacy=ClientCommandManager) ──

    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        //? if >=26.1 {
        return ClientCommands.literal(name);
        //?} else {
        /*return ClientCommandManager.literal(name);*/
        //?}
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(
            String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
        //? if >=26.1 {
        return ClientCommands.argument(name, type);
        //?} else {
        /*return ClientCommandManager.argument(name, type);*/
        //?}
    }
}
