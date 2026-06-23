package com.kajiwara.visualizegate.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ㉚ ゲート群の<b>コンフリクト解析</b> (MC 非依存・純粋・ワーカー安全)。 各ゲートを色分け状態へ分類し、
 * 要対処の項目を素の日本語の {@link GateConflict} として重大度順に出す ("smart" の核)。
 *
 * <p>判定根拠 (バニラのポータル探索ヒューリスティックに準拠):
 * <ul>
 *   <li><b>交差 (CONFLICT)</b>: 複数の OW ゲートを ÷8 写像した理想ターゲットの最近傍が<b>同一ネザーゲート</b>
 *       (半径 {@value #OW_TO_NETHER_RADIUS} 内)。 先に通った側が繋がり他方はズレ/新規＝競合。</li>
 *   <li><b>非対称 (CONFLICT)</b>: OW-o の最近傍ネザーが N-n なのに、 N-n を ×8 写像した最近傍 OW が o'≠o。
 *       往復で別ゲートに出る。</li>
 *   <li><b>ズレ (OFFSET)</b>: リンクはあるが理想ターゲットから {@value #OK_OFFSET_THRESHOLD} ブロック超。</li>
 *   <li><b>未接続 (WILL_CREATE)</b>: 対応ネザーが半径内に無い＝通ると新規生成。</li>
 *   <li><b>片側 (ORPHAN)</b>: どの OW からもリンクされないネザーゲート等、 手掛かりの無い片側。</li>
 *   <li><b>正常 (OK)</b>: 整合リンク (コンフリクト一覧には出さない)。</li>
 * </ul>
 */
public final class GateConflictAnalyzer {

    /** OW→ネザー 理想ターゲットの一致半径 (ネザーブロック)。 */
    public static final int OW_TO_NETHER_RADIUS = 16;
    /** ネザー→OW 理想ターゲットの一致半径 (OW ブロック・バニラ既定)。 */
    public static final int NETHER_TO_OW_RADIUS = 128;
    /** これ以下なら OK、 超えたら OFFSET (ネザーブロック)。 */
    public static final int OK_OFFSET_THRESHOLD = 8;

    public record Result(GateState[] states, List<GateConflict> conflicts) {
    }

    private GateConflictAnalyzer() {
    }

    public static Result analyze(List<GateNode> gates,
            int netherMinY, int netherMaxY, int owMinY, int owMaxY) {
        int n = gates.size();
        GateState[] states = new GateState[n];
        Arrays.fill(states, GateState.OK);
        List<GateConflict> conflicts = new ArrayList<>();
        if (n == 0) {
            return new Result(states, conflicts);
        }

        List<Integer> ow = new ArrayList<>();
        List<Integer> nether = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PortalDimension d = gates.get(i).dim();
            if (d == PortalDimension.OVERWORLD) {
                ow.add(i);
            } else if (d == PortalDimension.NETHER) {
                nether.add(i);
            } else {
                states[i] = GateState.ORPHAN; // OW/ネザー以外は対象外
            }
        }

        // OW ゲート → 最近傍ネザー (÷8 写像・半径内)。
        int[] owNearestN = new int[n];
        double[] owOffset = new double[n];
        Arrays.fill(owNearestN, -1);
        for (int oi : ow) {
            GridPos ideal = PortalCoordinateMapper.project(gates.get(oi).pos(),
                    PortalDimension.OVERWORLD, PortalDimension.NETHER, netherMinY, netherMaxY);
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int ni : nether) {
                double d = ideal.horizontalDistanceTo(gates.get(ni).pos());
                if (d <= OW_TO_NETHER_RADIUS && d < bd) {
                    bd = d;
                    best = ni;
                }
            }
            owNearestN[oi] = best;
            owOffset[oi] = bd;
        }
        // ネザーゲート → 最近傍 OW (×8 写像・半径内)。 非対称判定用。
        int[] nNearestOw = new int[n];
        Arrays.fill(nNearestOw, -1);
        for (int ni : nether) {
            GridPos ideal = PortalCoordinateMapper.project(gates.get(ni).pos(),
                    PortalDimension.NETHER, PortalDimension.OVERWORLD, owMinY, owMaxY);
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int oi : ow) {
                double d = ideal.horizontalDistanceTo(gates.get(oi).pos());
                if (d <= NETHER_TO_OW_RADIUS && d < bd) {
                    bd = d;
                    best = oi;
                }
            }
            nNearestOw[ni] = best;
        }

        // 交差: 同一ネザーを最近傍とする OW が 2 つ以上。
        Map<Integer, List<Integer>> byTarget = new HashMap<>();
        for (int oi : ow) {
            if (owNearestN[oi] >= 0) {
                byTarget.computeIfAbsent(owNearestN[oi], k -> new ArrayList<>()).add(oi);
            }
        }
        Set<Integer> crossing = new HashSet<>();
        for (Map.Entry<Integer, List<Integer>> e : byTarget.entrySet()) {
            if (e.getValue().size() >= 2) {
                int ni = e.getKey();
                crossing.addAll(e.getValue());
                states[ni] = GateState.CONFLICT;
                for (int oi : e.getValue()) {
                    states[oi] = GateState.CONFLICT;
                }
                conflicts.add(crossConflict(gates, e.getValue(), ni));
            }
        }

        // OW ゲート分類 (交差は確定済)。
        for (int oi : ow) {
            if (crossing.contains(oi)) {
                continue;
            }
            int ni = owNearestN[oi];
            if (ni < 0) {
                states[oi] = GateState.WILL_CREATE;
                conflicts.add(new GateConflict(GateState.WILL_CREATE,
                        new int[] { gates.get(oi).number() }, new PortalDimension[] { PortalDimension.OVERWORLD },
                        "OW-" + gates.get(oi).number() + ": 対応するネザーゲートが無い＝通ると新しいゲートが生成される見込み。"));
                continue;
            }
            int back = nNearestOw[ni];
            if (back >= 0 && back != oi) {
                states[oi] = GateState.CONFLICT;
                states[ni] = GateState.CONFLICT;
                conflicts.add(new GateConflict(GateState.CONFLICT,
                        new int[] { gates.get(oi).number(), gates.get(ni).number(), gates.get(back).number() },
                        new PortalDimension[] { PortalDimension.OVERWORLD, PortalDimension.NETHER,
                                PortalDimension.OVERWORLD },
                        "非対称: OW-" + gates.get(oi).number() + " は N-" + gates.get(ni).number()
                                + " へ向かうが、 N-" + gates.get(ni).number() + " から戻ると OW-"
                                + gates.get(back).number() + " ＝往復で別ゲートに出る。"));
                continue;
            }
            if (owOffset[oi] > OK_OFFSET_THRESHOLD) {
                states[oi] = GateState.OFFSET;
                if (states[ni] == GateState.OK) {
                    states[ni] = GateState.OFFSET;
                }
                conflicts.add(new GateConflict(GateState.OFFSET,
                        new int[] { gates.get(oi).number(), gates.get(ni).number() },
                        new PortalDimension[] { PortalDimension.OVERWORLD, PortalDimension.NETHER },
                        "ズレ: OW-" + gates.get(oi).number() + " と N-" + gates.get(ni).number()
                                + " はリンクするが約 " + Math.round(owOffset[oi]) + " ブロックずれている。"));
            }
        }

        // ネザーゲート分類: どの OW からも狙われていない＝片側 (ORPHAN)。
        for (int ni : nether) {
            if (states[ni] == GateState.CONFLICT || states[ni] == GateState.OFFSET) {
                continue; // 既に分類済
            }
            if (!byTarget.containsKey(ni)) {
                states[ni] = GateState.ORPHAN;
                conflicts.add(new GateConflict(GateState.ORPHAN,
                        new int[] { gates.get(ni).number() }, new PortalDimension[] { PortalDimension.NETHER },
                        "片側: N-" + gates.get(ni).number() + " はどの OW ゲートからもリンクされていない。"));
            }
        }

        // 重大度の高い順 (要対処順)。
        conflicts.sort((a, b) -> Integer.compare(b.severity(), a.severity()));
        return new Result(states, conflicts);
    }

    private static GateConflict crossConflict(List<GateNode> gates, List<Integer> owIdx, int ni) {
        int[] nums = new int[owIdx.size() + 1];
        PortalDimension[] dims = new PortalDimension[owIdx.size() + 1];
        StringBuilder owList = new StringBuilder();
        for (int k = 0; k < owIdx.size(); k++) {
            nums[k] = gates.get(owIdx.get(k)).number();
            dims[k] = PortalDimension.OVERWORLD;
            if (k > 0) {
                owList.append(", ");
            }
            owList.append("OW-").append(nums[k]);
        }
        nums[owIdx.size()] = gates.get(ni).number();
        dims[owIdx.size()] = PortalDimension.NETHER;
        return new GateConflict(GateState.CONFLICT, nums, dims,
                "交差: " + owList + " が同じ N-" + gates.get(ni).number()
                        + " へ向かう＝先に通った側が繋がり、 他方はズレ/新規生成になる。");
    }
}
