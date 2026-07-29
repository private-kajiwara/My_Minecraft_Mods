package com.kajiwara.hyperslice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** エンティティ層が MC 非依存のまま正しく振る舞うことを検証する。 */
class HyperEntityManagerTest {

    private static final double EPS = 1e-9;

    private static HyperEntityManager emptyWorld() {
        return new HyperEntityManager(HyperTerrainQuery.EMPTY);
    }

    @Test
    void spawnAndClear() {
        HyperEntityManager m = emptyWorld();
        assertEquals(0, m.count());

        UUID a = m.spawn(HyperEntityType.DRIFTER, new HyperVec(0, 64, 0, 0.5), HyperVec.ZERO);
        UUID b = m.spawn(HyperEntityType.DRIFTER, new HyperVec(1, 64, 0, 0.5), HyperVec.ZERO);
        assertEquals(2, m.count());
        assertTrue(m.remove(a));
        assertFalse(m.remove(a), "removing twice must be a no-op");
        assertEquals(1, m.count());

        assertEquals(1, m.clear());
        assertEquals(0, m.count());
        assertFalse(m.remove(b));
    }

    /** マイルストーン1: 等速直線運動のみ (重力なし)。 */
    @Test
    void tickAdvancesByVelocity() {
        HyperEntityManager m = emptyWorld();
        m.spawn(HyperEntityType.DRIFTER,
                new HyperVec(0, 64, 0, 0.0),
                new HyperVec(0, 0, 0, 0.02));

        for (int i = 0; i < 50; i++) {
            m.tick();
        }
        HyperEntityRecord r = m.all().iterator().next();
        assertEquals(1.0, r.position().w(), 1e-9, "w must advance at 0.02/tick");
        assertEquals(64.0, r.position().y(), EPS, "no gravity in milestone 1");
        assertEquals(0.0, r.position().x(), EPS);
    }

    /** w に漂う球が「点 → 膨張 → 収縮 → 点」を辿ること (体験の一次判定そのもの)。 */
    @Test
    void driftingEntityGrowsThenShrinks() {
        double plane = CrossSection.observationPlane(0);   // 0.5
        double half = HyperEntityType.DRIFTER.wThickness() / 2.0;

        HyperEntityManager m = emptyWorld();
        // 観測面の手前 (交差ぎりぎり外) から出発し、 観測面を通り抜ける
        m.spawn(HyperEntityType.DRIFTER,
                new HyperVec(0, 64, 0, plane - half),
                new HyperVec(0, 0, 0, HyperEntityType.DEFAULT_W_VELOCITY));

        double maxSeen = 0.0;
        boolean grew = false;
        boolean shrank = false;
        double prev = 0.0;

        int ticks = (int) Math.ceil((2 * half) / HyperEntityType.DEFAULT_W_VELOCITY) + 2;
        for (int i = 0; i < ticks; i++) {
            m.tick();
            double r = m.all().iterator().next().crossSectionRadius(plane);
            if (r > prev + 1e-12) {
                grew = true;
            }
            if (grew && r < prev - 1e-12) {
                shrank = true;
            }
            maxSeen = Math.max(maxSeen, r);
            prev = r;
        }

        assertTrue(grew, "cross-section must grow while approaching the plane");
        assertTrue(shrank, "cross-section must shrink after passing the plane");
        assertEquals(half, maxSeen, 0.02, "peak radius must reach the 4D radius");
        assertEquals(0.0, prev, EPS, "must end at zero (fully past the plane)");
    }

    // ── 同期の絞り込み ──────────────────────────────────────────

    @Test
    void visibleFromFiltersByDistance() {
        HyperEntityManager m = emptyWorld();
        double plane = CrossSection.observationPlane(0);
        m.spawn(HyperEntityType.DRIFTER, new HyperVec(0, 64, 0, plane), HyperVec.ZERO);
        m.spawn(HyperEntityType.DRIFTER, new HyperVec(100, 64, 0, plane), HyperVec.ZERO);

        assertEquals(1, m.visibleFrom(0, 64, 0, plane, 50.0, 1.0).size());
        assertEquals(2, m.visibleFrom(0, 64, 0, plane, 200.0, 1.0).size());
    }

    @Test
    void visibleFromFiltersByW() {
        HyperEntityManager m = emptyWorld();
        double plane = CrossSection.observationPlane(0);   // 0.5
        // 交差する / 遠すぎて交差しない (厚み 2.0 → 半径 1.0)
        m.spawn(HyperEntityType.DRIFTER, new HyperVec(0, 64, 0, plane), HyperVec.ZERO);
        m.spawn(HyperEntityType.DRIFTER, new HyperVec(0, 64, 0, plane + 5.0), HyperVec.ZERO);

        List<HyperEntityRecord> vis = m.visibleFrom(0, 64, 0, plane, 100.0, 0.0);
        assertEquals(1, vis.size(), "entities not crossing the plane must not be sent");
        assertEquals(0.0, vis.get(0).dw(plane), EPS);
    }

    /** マージンは「遅れて届いて突然出現する」のを防ぐために交差判定を広げる。 */
    @Test
    void visibleFromMarginIncludesSoonToBeVisible() {
        HyperEntityManager m = emptyWorld();
        double plane = CrossSection.observationPlane(0);
        m.spawn(HyperEntityType.DRIFTER, new HyperVec(0, 64, 0, plane + 1.5), HyperVec.ZERO);

        assertEquals(0, m.visibleFrom(0, 64, 0, plane, 100.0, 0.0).size());
        assertEquals(1, m.visibleFrom(0, 64, 0, plane, 100.0, 1.0).size());
    }

    // ── common の純粋性 ────────────────────────────────────────

    /** 注入された地形照会を使えること (HyperTerrain がそのまま刺さる形であること)。 */
    @Test
    void terrainQueryIsInjectable() {
        HyperTerrain terrain = new HyperTerrain(42L, 8);
        // メソッド参照でそのまま刺さる = シグネチャ互換が保たれている
        HyperEntityManager m = new HyperEntityManager(terrain::isSolid);

        int surface = terrain.surfaceY(0, 0, 0);
        assertTrue(m.terrain().isSolid(0, surface, 0, 0));
        assertFalse(m.terrain().isSolid(0, surface + 1, 0, 0));
    }

    @Test
    void nullTerrainFallsBackToEmpty() {
        HyperEntityManager m = new HyperEntityManager(null);
        assertFalse(m.terrain().isSolid(0, 0, 0, 0));
    }
}
