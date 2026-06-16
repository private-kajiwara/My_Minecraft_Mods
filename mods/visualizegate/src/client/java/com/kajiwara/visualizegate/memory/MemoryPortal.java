package com.kajiwara.visualizegate.memory;

/**
 * 永続化されるポータル記憶の 1 件 (GSON シリアライズ用 POJO・プリミティブのみ)。
 *
 * <p>{@code liveConfirmed} は「直近で実ブロックを確認済み」か。 false = 記憶のみ＝破壊済みの可能性
 * (将来 UI で「ライブ確認済み」と区別できるようデータ上保持する)。
 */
public final class MemoryPortal {

    public String dimensionId;     // 例: "minecraft:the_nether"
    public int ax;                 // anchor (グローバル最低コーナー)
    public int ay;
    public int az;
    public double minX;            // world-space AABB
    public double minY;
    public double minZ;
    public double maxX;
    public double maxY;
    public double maxZ;
    public String axis;            // "X" / "Z"
    public int number;             // ㉚ 安定採番 (次元別連番・発見時に割当て・以後リシャッフルしない・0=未割当)
    public String name;            // ㉝B 任意のユーザー命名 (null/空=既定 OW-n/N-n を使う・表示専用)
    public boolean hidden;         // ㉝C 表示フラグ (true=3D マーカー/ラベル/リンクを描かない・採番/解析は不変)
    public long lastSeenTick;
    public boolean liveConfirmed;
    /**
     * ⑤⑦B 最後にライブ確認 (flood-fill 成分検出一致) した<b>ワールド game-time</b> ({@code level.getLevelData().getGameTime()})。
     * <b>永続</b>＝ワールドの経過 tick 基準なのでセッションを跨いでも意味を持つ (閉じている実時間では進まない＝
     * 期限切れにならない)。 reconcile の game-time grace に使う。 復元/join 直後は現 game-time で seed され full grace。
     * 旧 tick ベース値 (schemaVersion≤1) は基準が違うため join seed で上書きされる (= 移行不要)。
     */
    public long lastConfirmedGameTime;
    /**
     * ⑤⑦B 「ロード済み＋成分不在」を連続で確認した回数 (reconcile サイクル)。 <b>transient</b>＝永続しない。
     * N 回連続でのみ除去＝部分ロードの過渡的不在で誤除去しない。 存在確認/未ロードで 0 にリセット。
     */
    public transient int absentStreak;

    public MemoryPortal() {
        // GSON 用 no-arg ctor
    }

    /** anchor を一意キー化 (記憶内の upsert/照合用)。 */
    public String anchorKey() {
        return ax + "," + ay + "," + az;
    }
}
