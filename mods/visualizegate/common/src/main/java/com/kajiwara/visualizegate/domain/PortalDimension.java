package com.kajiwara.visualizegate.domain;

/**
 * 座標写像に必要な範囲でのディメンション種別 (MC 非依存)。
 *
 * <p>OW↔Nether の 8:1 写像のみ対象。 End / その他は写像対象外 ({@link #OTHER})。
 * MC 側の {@code ResourceKey<Level>} からこの enum へは呼び出し側 (client) が変換する
 * (= domain は純粋に保ち、 MC 型を持ち込まない)。
 */
public enum PortalDimension {
    OVERWORLD,
    NETHER,
    END,
    OTHER
}
