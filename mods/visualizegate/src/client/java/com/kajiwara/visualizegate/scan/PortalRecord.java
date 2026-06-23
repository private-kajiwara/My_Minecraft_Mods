package com.kajiwara.visualizegate.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 検出された 1 つのネザーポータル (= NETHER_PORTAL ブロックの連結成分) のスナップショット。
 *
 * <p>同一性は {@link #anchor}（連結成分の<b>グローバル最低コーナー</b>）でキー化する。
 * 近傍再スキャン / CHUNK_LOAD / 定期再検証が同じポータルを何度見ても、 anchor が同じなら
 * {@link PortalIndex} 内で 1 レコードに収束する (idempotent)。 チャンク境界跨ぎ (両側ロード済み) は
 * flood-fill がグローバル最低コーナーを 1 つだけ算出するため自然に 1 レコードへ結合する。
 */
public record PortalRecord(
        ResourceKey<Level> dimension,
        BlockPos anchor,
        AABB aabb,
        Direction.Axis axis,
        Provenance provenance,
        long lastSeenTick) {

    /** このレコードを「いつ・どの経路で」最後に確認したか (デバッグ/将来の失効ポリシー用)。 */
    public enum Provenance {
        CHUNK_LOAD,
        NEAR_RESCAN,
        REVALIDATE
    }

    /** lastSeen を更新した複製を返す (anchor/aabb/axis は不変)。 */
    public PortalRecord withSeen(Provenance prov, long tick) {
        return new PortalRecord(dimension, anchor, aabb, axis, prov, tick);
    }
}
