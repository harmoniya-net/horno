package net.harmoniya.horno.install;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokensTest {
    private static final Map<String, String> DATA = new HashMap<>();

    static {
        DATA.put("ROOT", "/games/pack");
        DATA.put("SIDE", "client");
    }

    @Test
    void replacesAKey() {
        assertEquals("/games/pack/run.sh", Tokens.replace(DATA, "{ROOT}/run.sh"));
    }

    @Test
    void replacesSeveral() {
        assertEquals("/games/pack-client", Tokens.replace(DATA, "{ROOT}-{SIDE}"));
    }

    /**
     * The reason a sha1 is written `'de86…'` in a profile: quoted, it is a
     * literal, and nothing in it can be mistaken for something to substitute.
     */
    @Test
    void unquotesALiteral() {
        assertEquals("de86b035d2da", Tokens.replace(DATA, "'de86b035d2da'"));
        assertEquals("{ROOT}", Tokens.replace(DATA, "'{ROOT}'"));
    }

    @Test
    void honoursAnEscape() {
        assertEquals("{ROOT}", Tokens.replace(DATA, "\\{ROOT\\}"));
    }

    /**
     * An unknown key must not resolve to empty: a processor handed an empty
     * path writes somewhere else entirely, and says nothing about it.
     */
    @Test
    void refusesAKeyItDoesNotHave() {
        assertThrows(IllegalArgumentException.class, () -> Tokens.replace(DATA, "{NOPE}/x"));
    }

    @Test
    void refusesAnUnclosedToken() {
        assertThrows(IllegalArgumentException.class, () -> Tokens.replace(DATA, "{ROOT"));
        assertThrows(IllegalArgumentException.class, () -> Tokens.replace(DATA, "'oops"));
    }

    @Test
    void leavesAPlainStringAlone() {
        assertEquals("--task", Tokens.replace(Collections.<String, String>emptyMap(), "--task"));
    }
}
