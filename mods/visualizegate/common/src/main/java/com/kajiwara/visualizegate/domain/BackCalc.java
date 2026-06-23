package com.kajiwara.visualizegate.domain;

import java.util.Collection;
import java.util.Optional;

/**
 * ㉕ ポータル<b>逆算</b> (MC 非依存・純粋)。
 *
 * <p>ターゲット座標 {@code T} (= 出したいゲートの<b>到達目標</b>・対象次元) から、
 * <b>現在いる次元</b>に建てるべき「建設推奨位置」 {@code B} を求め、 さらに対象次元 {@code T} 近傍に
 * <b>既存ポータル</b>があるか (= 新規が吸い込まれる危険) を判定する。
 *
 * <p>座標写像はバニラ準拠 ({@link PortalCoordinateMapper}・XZ 8:1・Y は次元境界クランプ)。
 * 既存判定の探索半径は呼び出し側がバニラ定数 ({@code PortalForcer.OVERWORLD_PORTAL_RADIUS=128} /
 * {@code NETHER_PORTAL_RADIUS=16}) を渡す (= domain は MC 依存値を持たない)。
 */
public final class BackCalc {

    /** 逆算結果の種別。 */
    public enum Kind {
        /** 対象次元 {@code T} の探索半径内に既存ポータルあり → 新規はそこへ吸い込まれる (赤・対象次元側に表示)。 */
        EXISTING_IN_TARGET,
        /** 既存なし → 指定どおり新規生成が見込める (緑・現在次元側の建設推奨ボックス)。 */
        NEW_IN_CURRENT
    }

    /**
     * @param kind                  種別 (赤=既存 / 緑=新規)
     * @param buildPos              現在次元の建設推奨位置 (T を逆写像し境界クランプ)
     * @param targetPos             対象次元のターゲット座標 (= 入力 T)
     * @param targetRegionObserved  対象領域が観測済みか (未観測の緑は「断定不可」 注記用)
     * @param existing              一致した既存ポータル (EXISTING のみ present)
     * @param existingDistance      既存までの水平距離 (EXISTING のみ有効・他は NaN)
     */
    public record Result(Kind kind, GridPos buildPos, GridPos targetPos,
            boolean targetRegionObserved, Optional<DomainPortal> existing, double existingDistance) {
    }

    private BackCalc() {
    }

    /**
     * 逆算を実行する。
     *
     * @param target           対象次元のターゲット座標 (到達目標)
     * @param targetDim        対象次元 (OW か Nether)
     * @param currentDim       プレイヤーが今いる次元 (OW か Nether)
     * @param curMinY          現在次元の最低 Y (建設位置 Y クランプ)
     * @param curMaxY          現在次元の最高 Y
     * @param knownInTarget    対象次元の既知ポータル (PortalMemory 由来)
     * @param targetRadius     対象次元の既存探索半径 (OW=128 / Nether=16)
     * @param regionObserved   対象領域 (T 周辺) が観測済みか
     */
    public static Result compute(GridPos target, PortalDimension targetDim, PortalDimension currentDim,
            int curMinY, int curMaxY, Collection<DomainPortal> knownInTarget,
            double targetRadius, boolean regionObserved) {

        // 建設推奨 B = ターゲット T を現在次元へ逆写像 (OW→Nether は ÷8 / Nether→OW は ×8・Y クランプ)。
        GridPos build = PortalCoordinateMapper.project(target, targetDim, currentDim, curMinY, curMaxY);

        // 対象次元の T 中心・探索半径内の最近既存ポータル (水平距離・バニラ近似)。
        DomainPortal nearest = null;
        double best = Double.MAX_VALUE;
        for (DomainPortal p : knownInTarget) {
            if (p.dimension() != targetDim) {
                continue;
            }
            double d = target.horizontalDistanceTo(p.anchor());
            if (d <= targetRadius && d < best) {
                best = d;
                nearest = p;
            }
        }

        if (nearest != null) {
            return new Result(Kind.EXISTING_IN_TARGET, build, target, regionObserved,
                    Optional.of(nearest), best);
        }
        return new Result(Kind.NEW_IN_CURRENT, build, target, regionObserved,
                Optional.empty(), Double.NaN);
    }
}
