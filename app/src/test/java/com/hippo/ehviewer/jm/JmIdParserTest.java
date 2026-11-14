package com.hippo.ehviewer.jm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class JmIdParserTest {
    @Test
    public void parsesSupportedInputs() {
        assertEquals("350234", JmIdParser.parse("350234"));
        assertEquals("350234", JmIdParser.parse("JM350234"));
        assertEquals("350234", JmIdParser.parse("jm350234"));
        assertEquals("350234", JmIdParser.parse("https://example.test/album/350234"));
        assertEquals("350234", JmIdParser.parse("https://example.test/photo/350234"));
        assertEquals("350234", JmIdParser.parse("https://example.test/album/?id=350234"));
        assertEquals("350234", JmIdParser.parse("https://example.test/a?x=1&id=350234"));
        assertEquals("350234", JmIdParser.parse("JM000350234"));
    }

    @Test
    public void rejectsAmbiguousOrInvalidInputs() {
        rejects("");
        rejects("JM");
        rejects("updated 2026-07-23");
        rejects("350 who has not read 234");
        rejects("https://example.test/page/350234");
        rejects("0");
    }

    private static void rejects(String value) {
        try {
            JmIdParser.parse(value);
            fail("Expected parser to reject: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
