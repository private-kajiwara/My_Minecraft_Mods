package com.kajiwara.visualizegate.domain;

/**
 * OW↔Nether の座標写像 (XZ 8:1・Y は対象ディメンション境界にクランプ・往復・MC 非依存)。
 *
 * <p><b>End は対象外</b>。 {@code from}/{@code to} の一方でも {@link PortalDimension#END}/
 * {@link PortalDimension#OTHER} を含む場合は例外。 これはバニラのポータルリンク座標規則の
 * <b>幾何写像</b>であり、 実際の探索 (直方体・POI・既存ポータル選択) の完全再現ではない。
 */
public final class PortalCoordinateMapper {

    /** OW→Nether の XZ 除数 (Nether→OW は乗数)。 */
    public static final int OVERWORLD_TO_NETHER_DIVISOR = 8;

    private PortalCoordinateMapper() {
    }

    /**
     * {@code pos} ({@code from} 次元) を {@code to} 次元の対応座標へ写像する。
     * Y は写像せず {@code [toMinY, toMaxY]} にクランプ (= バニラ準拠)。
     *
     * @throws IllegalArgumentException OW↔Nether 以外 (End/OTHER を含む) の組み合わせ
     */
    public static GridPos project(GridPos pos, PortalDimension from, PortalDimension to,
            int toMinY, int toMaxY) {
        if (from == to) {
            return new GridPos(pos.x(), clamp(pos.y(), toMinY, toMaxY), pos.z());
        }
        int x;
        int z;
        if (from == PortalDimension.OVERWORLD && to == PortalDimension.NETHER) {
            x = Math.floorDiv(pos.x(), OVERWORLD_TO_NETHER_DIVISOR);
            z = Math.floorDiv(pos.z(), OVERWORLD_TO_NETHER_DIVISOR);
        } else if (from == PortalDimension.NETHER && to == PortalDimension.OVERWORLD) {
            x = pos.x() * OVERWORLD_TO_NETHER_DIVISOR;
            z = pos.z() * OVERWORLD_TO_NETHER_DIVISOR;
        } else {
            throw new IllegalArgumentException(
                    "PortalCoordinateMapper supports only OVERWORLD<->NETHER, got " + from + " -> " + to);
        }
        return new GridPos(x, clamp(pos.y(), toMinY, toMaxY), z);
    }

    /** 当該ペアが写像対象 (OW↔Nether) か。 */
    public static boolean isSupported(PortalDimension from, PortalDimension to) {
        return (from == PortalDimension.OVERWORLD && to == PortalDimension.NETHER)
                || (from == PortalDimension.NETHER && to == PortalDimension.OVERWORLD)
                || from == to;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
