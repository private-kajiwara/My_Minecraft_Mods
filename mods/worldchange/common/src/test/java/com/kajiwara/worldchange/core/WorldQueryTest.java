package com.kajiwara.worldchange.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class WorldQueryTest {

    @Test
    void nameOnly_noSeed() {
        WorldQuery q = WorldQuery.parse("My World");
        assertEquals("My World", q.name());
        assertTrue(q.seed().isEmpty());
    }

    @Test
    void nameAndNumericSeed() {
        WorldQuery q = WorldQuery.parse("World:12345");
        assertEquals("World", q.name());
        assertEquals(OptionalLong.of(12345L), q.seed());
    }

    @Test
    void negativeSeed() {
        WorldQuery q = WorldQuery.parse("World:-987654321");
        assertEquals(OptionalLong.of(-987654321L), q.seed());
    }

    @Test
    void textSeed_hashesLikeVanilla() {
        WorldQuery q = WorldQuery.parse("World:gimme diamonds");
        assertEquals(OptionalLong.of("gimme diamonds".hashCode()), q.seed());
    }

    @Test
    void trailingColon_isNoSeed() {
        WorldQuery q = WorldQuery.parse("World:");
        assertEquals("World", q.name());
        assertTrue(q.seed().isEmpty());
    }

    @Test
    void firstColonSeparates() {
        // 最初の ':' が区切り。 後続はシード token としてまとめて扱う。
        WorldQuery q = WorldQuery.parse("World:a:b");
        assertEquals("World", q.name());
        assertEquals(OptionalLong.of("a:b".hashCode()), q.seed());
    }

    @Test
    void trimsWhitespace() {
        WorldQuery q = WorldQuery.parse("  World  :  42  ");
        assertEquals("World", q.name());
        assertEquals(OptionalLong.of(42L), q.seed());
    }

    @Test
    void blankIsBlank() {
        assertTrue(WorldQuery.parse("   ").isBlank());
        assertFalse(WorldQuery.parse("x").isBlank());
    }
}
