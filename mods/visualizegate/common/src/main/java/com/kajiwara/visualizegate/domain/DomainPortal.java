package com.kajiwara.visualizegate.domain;

/**
 * domain 層が扱う「既知ポータル」 の純粋表現 (MC 非依存)。
 *
 * @param dimension     どの次元のポータルか
 * @param anchor        代表座標 (連結成分のグローバル最低コーナー)
 * @param liveConfirmed 直近で実ブロックを確認済みか (false = 記憶のみ＝破壊済みの可能性)
 * @param lastSeenTick  最後に確認した tick (記憶の鮮度)
 */
public record DomainPortal(
        PortalDimension dimension,
        GridPos anchor,
        boolean liveConfirmed,
        long lastSeenTick) {
}
