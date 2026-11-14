package com.hippo.ehviewer.subscription;

import org.junit.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class LocalRefreshStatusFormatterTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long NOW = Instant.parse("2026-07-26T20:00:00Z").toEpochMilli();

    @Test
    public void formatsTodayWithMonthAndDay() {
        assertEquals("7月26日 18:20", format("2026-07-26T18:20:00Z"));
    }

    @Test
    public void formatsEarlierThisYearWithoutYear() {
        assertEquals("7月25日 18:20", format("2026-07-25T18:20:00Z"));
    }

    @Test
    public void formatsAnotherYearWithFullDate() {
        assertEquals("2025年12月31日 23:59", format("2025-12-31T23:59:00Z"));
    }

    private static String format(String value) {
        return LocalRefreshStatusFormatter.formatTime(
                Instant.parse(value).toEpochMilli(), NOW, Locale.SIMPLIFIED_CHINESE, UTC);
    }
}
