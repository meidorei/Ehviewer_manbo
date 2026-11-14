package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PostedTimestampTest {
    @Test public void parsesServerDateAsUnixSeconds() {
        assertEquals(0, ParserUtils.parsePostedTimestamp(null));
        assertEquals(0, ParserUtils.parsePostedTimestamp("not a date"));
        assertTrue(ParserUtils.parsePostedTimestamp("2026-07-22 12:34") > 0);
    }
}
