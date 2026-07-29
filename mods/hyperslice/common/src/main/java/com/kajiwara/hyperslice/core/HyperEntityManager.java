package com.kajiwara.hyperslice.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4 次元エンティティの保持と tick。 <b>純粋 Java</b> (Minecraft / Fabric を一切参照しない)。
 *
 * <p>地形照会は {@link HyperTerrainQuery} を注入して受け取るので、 この層は
 * {@code ServerLevel} に依存しない。 方式A → 方式B の移行でこのクラスは変更不要になる。
 *
 * <p>4 次元エンティティはどの {@code ServerLevel} にも属さない (4 次元世界に 1 つ存在する)
 * ため、 このマネージャは<b>サーバ全体で 1 個</b>であってレベル毎ではない。
 *
 * <p>スレッド安全ではない。 サーバの tick スレッドからのみ触ること。
 */
public final class HyperEntityManager {

    /** 挿入順を保つ (list の出力順が安定してデバッグしやすい)。 */
    private final Map<UUID, HyperEntityRecord> entities = new LinkedHashMap<>();

    private final HyperTerrainQuery terrain;

    public HyperEntityManager(HyperTerrainQuery terrain) {
        this.terrain = (terrain != null) ? terrain : HyperTerrainQuery.EMPTY;
    }

    /** 注入された地形照会 (マイルストーン2 の衝突判定で使う)。 */
    public HyperTerrainQuery terrain() {
        return terrain;
    }

    // ── 生成・削除 ──────────────────────────────────────────────

    /** 新規生成し、 割り当てた UUID を返す。 */
    public UUID spawn(HyperEntityType type, HyperVec position, HyperVec velocity) {
        UUID id = UUID.randomUUID();
        entities.put(id, new HyperEntityRecord(id, type, position, velocity));
        return id;
    }

    /** 既存レコードをそのまま登録する (永続化からの復元用・マイルストーン2)。 */
    public void put(HyperEntityRecord record) {
        entities.put(record.id(), record);
    }

    public boolean remove(UUID id) {
        return entities.remove(id) != null;
    }

    /** 全削除し、 消した件数を返す (試行の高速化に必須)。 */
    public int clear() {
        int n = entities.size();
        entities.clear();
        return n;
    }

    public int count() {
        return entities.size();
    }

    /** 全レコード (順序は挿入順)。 */
    public Collection<HyperEntityRecord> all() {
        return entities.values();
    }

    // ── tick ────────────────────────────────────────────────────

    /**
     * 1 tick 進める。
     *
     * <p>マイルストーン1 は<b>等速直線運動のみ</b> (重力なし・地形衝突なし・AI なし)。
     * 速度の単位はバニラに合わせて「1 tick あたりの移動量」。
     *
     * <p>マイルストーン2 で重力と地形衝突をここに足す。 その際も
     * {@link #terrain} 経由でしか地形を見ないこと ({@code ServerLevel} を持ち込まない)。
     */
    public void tick() {
        if (entities.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, HyperEntityRecord> e : entities.entrySet()) {
            HyperEntityRecord r = e.getValue();
            e.setValue(r.withPosition(r.position().add(r.velocity())));
        }
    }

    // ── 同期用の絞り込み ────────────────────────────────────────

    /**
     * 観測者に送るべきレコードを絞り込む。
     *
     * <p>「(x,y,z) が半径内」かつ「観測超平面と w 範囲が交差する」もののみ。
     * 交差しないものは断面半径が 0 = 描画対象が存在しないので、 そもそも送らない
     * (「隣スライスのゴースト表示」のような機構は要らない)。
     *
     * @param planeW    観測超平面の w ({@link CrossSection#observationPlane})
     * @param radiusXZ  3 次元距離の上限 [ブロック]
     * @param wMargin   w 交差判定の余裕。 ネットワーク遅延で到着が遅れると突然出現して
     *                  見えるため、 同期では正の値を与える
     */
    public List<HyperEntityRecord> visibleFrom(double x, double y, double z,
                                               double planeW, double radiusXZ, double wMargin) {
        List<HyperEntityRecord> out = new ArrayList<>();
        double radiusSq = radiusXZ * radiusXZ;
        for (HyperEntityRecord r : entities.values()) {
            if (r.position().distanceSq3(x, y, z) > radiusSq) {
                continue;
            }
            if (!CrossSection.intersects(r.wThickness(), r.dw(planeW), wMargin)) {
                continue;
            }
            out.add(r);
        }
        return out;
    }
}
