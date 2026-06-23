package com.kajiwara.visualizegate.client.render;

import java.util.ArrayList;
import java.util.List;

import com.kajiwara.visualizegate.domain.DomainPortal;
import com.kajiwara.visualizegate.domain.GateConflict;
import com.kajiwara.visualizegate.domain.GateConflictAnalyzer;
import com.kajiwara.visualizegate.domain.GateNode;
import com.kajiwara.visualizegate.domain.GateState;
import com.kajiwara.visualizegate.domain.GridPos;
import com.kajiwara.visualizegate.domain.LinkPrediction;
import com.kajiwara.visualizegate.domain.PortalDimension;
import com.kajiwara.visualizegate.domain.PortalLinkResolver;
import com.kajiwara.visualizegate.memory.PortalMemory;
import com.kajiwara.visualizegate.scan.PortalIndex;
import com.kajiwara.visualizegate.scan.PortalRecord;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 「照準ポータル」の共有解決＋予測キャッシュ (UX 層の自動インフォカード/凡例が参照)。
 *
 * <p>機能2 ({@link PortalLinkRenderer}) と<b>同じ予測ロジック</b>
 * ({@link PortalLinkResolver#predict}) と<b>同じデータ</b> ({@link PortalMemory}/{@link PortalIndex})
 * を使い、 同条件なら同じ三値結果を出す。 機能2 の内部フィールドには触れない (=既存挙動不変)。
 * 予測はブロック移動しきい値でキャッシュするので毎フレームはほぼ無コスト。
 *
 * <p>機能2 のトリガが「F&S 所持 or 黒曜石 frame 注視」なのに対し、 カードは「ネザーポータルブロック
 * <b>or</b> 黒曜石 frame 注視」を source とする (= ポータルを見ている時だけ・常駐しない)。
 */
public final class PortalGaze {

    // 対象次元の Y クランプ用バニラ標準境界 (機能2 と同値)。
    private static final int OW_MIN_Y = -64;
    private static final int OW_MAX_Y = 319;
    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;

    // 予測キャッシュ (source ブロック移動 or 次元変化時のみ再計算)。 注視カード用スロット。
    private static GridPos cachedSource;
    private static PortalDimension cachedDim;
    private static LinkPrediction cachedPrediction;
    private static Status cachedStatus;

    // 計画 (resolvePlanning) 用の独立キャッシュスロット (注視と source が別になり得るので分離＝相互 thrash 防止)。
    private static GridPos planSource;
    private static PortalDimension planDim;
    private static LinkPrediction planPrediction;
    private static Status planStatus;

    private PortalGaze() {
    }

    /**
     * 照準ポータルの解決結果 (現次元 cur → 対象次元 other への予測込み)。
     *
     * @param portal     注視している既知ポータル (block/ frame いずれか)
     * @param sourceX/Y/Z 描画/表示用の連続中心座標 (現次元)
     * @param prediction 機能2 と同じ三値予測 (キャッシュ済み・<b>線/枠の幾何</b>に使う)
     * @param status     ㉜ 点群画面と同じ {@link GateConflictAnalyzer} 由来の<b>5 状態</b>＋番号 (色・文言に使う)
     * @param current    現次元 (OW or Nether)
     * @param other      対象次元
     */
    public record Result(PortalRecord portal, double sourceX, double sourceY, double sourceZ,
            LinkPrediction prediction, Status status, PortalDimension current, PortalDimension other) {
    }

    /**
     * ㉜ 注視/設置予定ゲートの<b>5 状態</b>解決結果 (点群画面と同じ {@link GateConflictAnalyzer}・同じ採番)。
     * 色・状態語・相手番号は<b>解析器が正</b>。 {@link LinkPrediction} は相手番号/ズレの命名にのみ併用する。
     *
     * @param state        5 状態 (OK/ORPHAN/OFFSET/WILL_CREATE/CONFLICT)
     * @param number       このゲートの安定採番 (0 = 仮想/新規＝「ここ」)
     * @param relNumbers   相手番号 (正常/ズレ=matched 1 件 / 競合=関係番号群 / 未接続/片側=空)
     * @param relNether    {@code relNumbers} と並ぶ「相手はネザーか」 (接頭 N-/OW- 用)
     * @param offset       正常/ズレ時の水平ズレ量 (それ以外 NaN)
     * @param offsetValid  offset が有効か
     */
    public record Status(GateState state, int number, int[] relNumbers, boolean[] relNether,
            double offset, boolean offsetValid) {
    }

    /** メインハンド/オフハンドに火打石と打金を持っているか (凡例の表示条件・機能2 と同判定)。 */
    public static boolean isHoldingFlint(LocalPlayer player) {
        return player.getMainHandItem().getItem() == Items.FLINT_AND_STEEL
                || player.getOffhandItem().getItem() == Items.FLINT_AND_STEEL;
    }

    /**
     * 照準が「ネザーポータルブロック or 既知ポータルの黒曜石 frame」に当たっていれば、
     * その既知ポータルレコードを返す (= カード/凡例の表示トリガ)。 外れていれば null。
     */
    public static PortalRecord lookedPortal(Minecraft mc, ClientLevel level) {
        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() != HitResult.Type.BLOCK || !(hr instanceof BlockHitResult bhr)) {
            return null;
        }
        BlockPos bp = bhr.getBlockPos();
        var block = level.getBlockState(bp).getBlock();
        if (block != Blocks.NETHER_PORTAL && block != Blocks.OBSIDIAN) {
            return null;
        }
        // ブロック中心を内包/近傍 (frame 許容で 1 膨張) する既知ポータルを探す。
        double x = bp.getX() + 0.5;
        double y = bp.getY() + 0.5;
        double z = bp.getZ() + 0.5;
        for (PortalRecord r : PortalIndex.get().recordsFor(level.dimension())) {
            if (r.aabb().inflate(1.0).contains(x, y, z)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 照準ポータルを source に機能2 と同じ予測を読む (キャッシュ)。 ポータルを見ていない、
     * または OW↔Nether 以外なら null。
     */
    public static Result resolve(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return null;
        }
        PortalDimension cur = PortalMemory.dimOf(level.dimension().identifier().toString());
        if (cur != PortalDimension.OVERWORLD && cur != PortalDimension.NETHER) {
            return null; // OW↔Nether のみ
        }
        PortalRecord looked = lookedPortal(mc, level);
        if (looked == null) {
            return null;
        }
        PortalDimension other = (cur == PortalDimension.OVERWORLD)
                ? PortalDimension.NETHER : PortalDimension.OVERWORLD;

        double sx = (looked.aabb().minX + looked.aabb().maxX) * 0.5;
        double sy = (looked.aabb().minY + looked.aabb().maxY) * 0.5;
        double sz = (looked.aabb().minZ + looked.aabb().maxZ) * 0.5;
        GridPos source = new GridPos((int) Math.floor(sx), (int) Math.floor(sy), (int) Math.floor(sz));

        if (cachedPrediction == null || !source.equals(cachedSource) || cur != cachedDim) {
            cachedPrediction = predict(source, cur, other);
            cachedStatus = computeStatus(source, cur, other, looked.anchor(), cachedPrediction);
            cachedSource = source;
            cachedDim = cur;
        }
        return new Result(looked, sx, sy, sz, cachedPrediction, cachedStatus, cur, other);
    }

    /**
     * 機能1 ホログラム用の「計画」解決 (機能2 と<b>同じトリガ</b>＝注視 or 火打石所持)。 注視ポータルがあれば
     * その中心、 無く F&S 所持ならプレイヤー補間位置を仮想 source として予測を読む (独立キャッシュ)。 OW↔Nether
     * 以外/トリガ無しは null。 機能2 (PortalLinkRenderer) の内部状態には一切触れない (=既存挙動不変)。
     */
    public static Result resolvePlanning(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return null;
        }
        PortalDimension cur = PortalMemory.dimOf(level.dimension().identifier().toString());
        if (cur != PortalDimension.OVERWORLD && cur != PortalDimension.NETHER) {
            return null;
        }
        PortalDimension other = (cur == PortalDimension.OVERWORLD)
                ? PortalDimension.NETHER : PortalDimension.OVERWORLD;

        PortalRecord looked = lookedPortal(mc, level);
        double sx;
        double sy;
        double sz;
        if (looked != null) {
            sx = (looked.aabb().minX + looked.aabb().maxX) * 0.5;
            sy = (looked.aabb().minY + looked.aabb().maxY) * 0.5;
            sz = (looked.aabb().minZ + looked.aabb().maxZ) * 0.5;
        } else if (isHoldingFlint(player)) {
            // 機能2 と同じ仮想 source: カメラと同じ partial-tick 補間位置 (足元→胴中心へ +1.0Y)。
            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            net.minecraft.world.phys.Vec3 p = player.getPosition(partialTick);
            sx = p.x;
            sy = p.y + 1.0;
            sz = p.z;
        } else {
            return null; // トリガ無し
        }

        GridPos source = new GridPos((int) Math.floor(sx), (int) Math.floor(sy), (int) Math.floor(sz));
        if (planPrediction == null || !source.equals(planSource) || cur != planDim) {
            planPrediction = predict(source, cur, other);
            // 注視ゲートがあれば既知ゲートの状態、 無ければ「ここに置くと」の仮想配置を解析。
            planStatus = computeStatus(source, cur, other,
                    looked != null ? looked.anchor() : null, planPrediction);
            planSource = source;
            planDim = cur;
        }
        return new Result(looked, sx, sy, sz, planPrediction, planStatus, cur, other);
    }

    /** ㉜ 仮想ゲートの採番 (実ゲートは 1.. なので衝突しない番兵)。 */
    private static final int VIRTUAL_NUMBER = Integer.MIN_VALUE;

    /**
     * ㉜ 注視/設置予定ゲートの 5 状態を<b>点群画面と同じ {@link GateConflictAnalyzer}</b> で解く。
     * {@code lookedAnchor != null} なら既知ゲート (記憶の anchor で索引)、 null なら source に仮想ゲートを
     * 追加して「ここに置くとどうなる」を解析する。 相手番号は<b>状態と整合</b>させる: 正常/ズレは
     * {@code pred.matched()} の番号＋ズレ、 競合は解析器 {@link GateConflict} の関係番号、 未接続/片側は無し。
     */
    private static Status computeStatus(GridPos source, PortalDimension cur, PortalDimension other,
            BlockPos lookedAnchor, LinkPrediction pred) {
        List<GateNode> nodes = new ArrayList<>(PortalMemory.get().gateNodes());
        int idx = -1;
        if (lookedAnchor != null) {
            for (int i = 0; i < nodes.size(); i++) {
                GateNode g = nodes.get(i);
                if (g.dim() == cur && g.x() == lookedAnchor.getX()
                        && g.y() == lookedAnchor.getY() && g.z() == lookedAnchor.getZ()) {
                    idx = i;
                    break;
                }
            }
        }
        boolean virtual = idx < 0;
        if (virtual) {
            nodes.add(new GateNode(VIRTUAL_NUMBER, cur, source.x(), source.y(), source.z()));
            idx = nodes.size() - 1;
        }
        int myNumber = virtual ? 0 : nodes.get(idx).number();
        int myKey = nodes.get(idx).number(); // 解析器の番号 (仮想は VIRTUAL_NUMBER)

        GateConflictAnalyzer.Result r = GateConflictAnalyzer.analyze(nodes,
                NETHER_MIN_Y, NETHER_MAX_Y, OW_MIN_Y, OW_MAX_Y);
        GateState st = r.states()[idx];

        if (st == GateState.CONFLICT) {
            // 競合: 解析器の関係番号 (自分以外) を集める。 predict.matched は使わない (矛盾回避)。
            List<Integer> nums = new ArrayList<>();
            List<Boolean> neth = new ArrayList<>();
            for (GateConflict c : r.conflicts()) {
                if (!involvesKey(c, myKey, cur)) {
                    continue;
                }
                for (int k = 0; k < c.gateNumbers().length; k++) {
                    boolean self = c.gateNumbers()[k] == myKey && c.dims()[k] == cur;
                    if (self) {
                        continue;
                    }
                    int num = c.gateNumbers()[k];
                    boolean isNeth = c.dims()[k] == PortalDimension.NETHER;
                    if (!contains(nums, neth, num, isNeth)) {
                        nums.add(num);
                        neth.add(isNeth);
                    }
                }
            }
            return new Status(st, myNumber, toIntArray(nums), toBoolArray(neth), Double.NaN, false);
        }
        if ((st == GateState.OK || st == GateState.OFFSET) && pred.matched().isPresent()) {
            // 正常/ズレ: matched の番号＋ズレ量 (相手は other 次元)。
            GridPos a = pred.matched().get().anchor();
            int pnum = numberAtAnchor(nodes, other, a);
            boolean isNeth = other == PortalDimension.NETHER;
            return new Status(st, myNumber, new int[] { pnum }, new boolean[] { isNeth },
                    pred.offsetDistance(), true);
        }
        // 未接続(新規)/片側: 相手なし。
        return new Status(st, myNumber, new int[0], new boolean[0], Double.NaN, false);
    }

    private static boolean involvesKey(GateConflict c, int key, PortalDimension dim) {
        for (int k = 0; k < c.gateNumbers().length; k++) {
            if (c.gateNumbers()[k] == key && c.dims()[k] == dim) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(List<Integer> nums, List<Boolean> neth, int num, boolean isNeth) {
        for (int i = 0; i < nums.size(); i++) {
            if (nums.get(i) == num && neth.get(i) == isNeth) {
                return true;
            }
        }
        return false;
    }

    private static int numberAtAnchor(List<GateNode> nodes, PortalDimension dim, GridPos a) {
        for (GateNode g : nodes) {
            if (g.dim() == dim && g.x() == a.x() && g.y() == a.y() && g.z() == a.z()) {
                return g.number();
            }
        }
        return 0;
    }

    private static int[] toIntArray(List<Integer> l) {
        int[] out = new int[l.size()];
        for (int i = 0; i < l.size(); i++) {
            out[i] = l.get(i);
        }
        return out;
    }

    private static boolean[] toBoolArray(List<Boolean> l) {
        boolean[] out = new boolean[l.size()];
        for (int i = 0; i < l.size(); i++) {
            out[i] = l.get(i);
        }
        return out;
    }

    /** 機能2 と同一パラメータ (半径/Y境界/既知集合/観測述語) で予測する (キャッシュは呼び出し側スロット)。 */
    private static LinkPrediction predict(GridPos source, PortalDimension cur, PortalDimension other) {
        int otherMinY = (other == PortalDimension.NETHER) ? NETHER_MIN_Y : OW_MIN_Y;
        int otherMaxY = (other == PortalDimension.NETHER) ? NETHER_MAX_Y : OW_MAX_Y;
        double radius = (other == PortalDimension.NETHER) ? 16.0 : 128.0;
        List<DomainPortal> known = PortalMemory.get().knownInDimension(other);
        return PortalLinkResolver.predict(source, cur, other, otherMinY, otherMaxY,
                known, radius, ideal -> PortalMemory.get().isRegionObserved(other, ideal.x(), ideal.z()));
    }
}
