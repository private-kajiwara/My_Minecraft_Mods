package com.kajiwara.worldchange.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class WorldMatcherTest {

    private static WorldEntry entry(String folder, String display, Long seed) {
        return new WorldEntry(folder, display,
                seed == null ? OptionalLong.empty() : OptionalLong.of(seed), false, true);
    }

    @Test
    void notFound() {
        List<WorldEntry> all = List.of(entry("Alpha", "Alpha", 1L));
        WorldMatch m = WorldMatcher.match(WorldQuery.ofName("Zeta"), all);
        assertEquals(WorldMatch.Status.NOT_FOUND, m.status());
        assertNull(m.selected());
    }

    @Test
    void uniqueByName_noSeed() {
        List<WorldEntry> all = List.of(entry("Alpha", "Alpha", 1L), entry("Beta", "Beta", 2L));
        WorldMatch m = WorldMatcher.match(WorldQuery.ofName("beta"), all);
        assertEquals(WorldMatch.Status.MATCHED, m.status());
        assertEquals("Beta", m.selected().folderId());
    }

    @Test
    void folderNameTakesPriorityOverDisplay() {
        // フォルダ "World" と、 別フォルダだが表示名 "World" の 2 件 → フォルダ一致を優先し一意。
        List<WorldEntry> all = List.of(
                entry("World", "Renamed", 1L),
                entry("World (1)", "World", 2L));
        WorldMatch m = WorldMatcher.match(WorldQuery.ofName("World"), all);
        assertEquals(WorldMatch.Status.MATCHED, m.status());
        assertEquals("World", m.selected().folderId());
    }

    @Test
    void matchesByDisplayWhenNoFolderMatch() {
        List<WorldEntry> all = List.of(entry("save-123", "My Base", 5L));
        WorldMatch m = WorldMatcher.match(WorldQuery.ofName("My Base"), all);
        assertEquals(WorldMatch.Status.MATCHED, m.status());
        assertEquals("save-123", m.selected().folderId());
    }

    @Test
    void exactFolderNameIsAlwaysUnique() {
        // フォルダ名は MC が一意化する。 入力がフォルダ名と完全一致するなら、 別ワールドが同じ
        // 表示名を持っていても一意に決まる (フォルダ名優先)。
        List<WorldEntry> all = List.of(
                entry("World", "World", 100L),
                entry("World (1)", "World", 200L));
        WorldMatch m = WorldMatcher.match(WorldQuery.ofName("World"), all);
        assertEquals(WorldMatch.Status.MATCHED, m.status());
        assertEquals("World", m.selected().folderId());
    }

    @Test
    void seedMatchDisambiguatesDuplicateDisplayNames() {
        // フォルダ名は入力と一致しない (save-a/save-b)。 表示名 "World" が重複 → シードで一意化。
        List<WorldEntry> all = List.of(
                entry("save-a", "World", 100L),
                entry("save-b", "World", 200L));
        WorldMatch m = WorldMatcher.match(new WorldQuery("World", OptionalLong.of(200L)), all);
        assertEquals(WorldMatch.Status.MATCHED, m.status());
        assertEquals("save-b", m.selected().folderId());
    }

    @Test
    void ambiguousNeedsSeed() {
        List<WorldEntry> all = List.of(
                entry("save-a", "World", 100L),
                entry("save-b", "World", 200L));
        WorldMatch m = WorldMatcher.match(WorldQuery.ofName("World"), all);
        assertEquals(WorldMatch.Status.AMBIGUOUS_NEED_SEED, m.status());
        assertEquals(2, m.candidates().size());
    }

    @Test
    void ambiguousSeedNoMatch() {
        List<WorldEntry> all = List.of(
                entry("save-a", "World", 100L),
                entry("save-b", "World", 200L));
        WorldMatch m = WorldMatcher.match(new WorldQuery("World", OptionalLong.of(999L)), all);
        assertEquals(WorldMatch.Status.AMBIGUOUS_SEED_UNRESOLVED, m.status());
    }

    @Test
    void singleMatch_seedMismatch_isSoftWarning() {
        List<WorldEntry> all = List.of(entry("Alpha", "Alpha", 1L));
        WorldMatch m = WorldMatcher.match(new WorldQuery("Alpha", OptionalLong.of(2L)), all);
        assertEquals(WorldMatch.Status.SEED_MISMATCH, m.status());
        assertEquals("Alpha", m.selected().folderId()); // 採用はする (緩い検証)
    }

    @Test
    void singleMatch_unknownSeed_treatedAsMatch() {
        List<WorldEntry> all = List.of(entry("Alpha", "Alpha", null)); // seed 未読込
        WorldMatch m = WorldMatcher.match(new WorldQuery("Alpha", OptionalLong.of(2L)), all);
        assertEquals(WorldMatch.Status.MATCHED, m.status());
    }
}
