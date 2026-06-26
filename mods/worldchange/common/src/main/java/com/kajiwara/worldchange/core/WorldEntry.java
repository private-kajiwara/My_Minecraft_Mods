package com.kajiwara.worldchange.core;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * saves 配下の 1 ワールドを表す MC 非依存のプレーンデータ。
 *
 * <p>MC 固有の {@code LevelSummary} / {@code LevelStorageAccess} から {@code WorldCatalog} (client 層) が
 * この record へ写し替える。 純粋ロジック ({@link WorldMatcher}) はこの record だけに依存する
 * (= Forge 等への移植時も再利用できる)。
 *
 * @param folderId    saves 内のフォルダ名 (MC が自動で一意化する識別子)。
 * @param displayName level.dat の表示名 (重複し得る)。
 * @param seed        既知ならワールドのシード。 未読込/不明なら {@link OptionalLong#empty()}。
 * @param locked      他プロセスがセッションロック中か (切替不可)。
 * @param compatible  現在の MC バージョンで読み込めるか (非互換は切替不可)。
 */
public record WorldEntry(String folderId, String displayName, OptionalLong seed,
                         boolean locked, boolean compatible) {

    public WorldEntry {
        Objects.requireNonNull(folderId, "folderId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(seed, "seed");
    }

    /** シードだけ差し替えた複製 (catalog がシードを遅延読込した後に使う)。 */
    public WorldEntry withSeed(OptionalLong newSeed) {
        return new WorldEntry(folderId, displayName, newSeed, locked, compatible);
    }

    /** 与えた名前が フォルダ名 と一致するか (trim・大小無視)。 */
    public boolean matchesFolder(String name) {
        return folderId.equalsIgnoreCase(name.trim());
    }

    /** 与えた名前が 表示名 と一致するか (trim・大小無視)。 */
    public boolean matchesDisplay(String name) {
        return displayName.trim().equalsIgnoreCase(name.trim());
    }
}
