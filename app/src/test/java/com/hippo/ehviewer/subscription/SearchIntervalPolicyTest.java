package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SearchIntervalPolicyTest {
    @Test public void acceptsBoundsAndOneDecimalPlace() {
        assertEquals(1000, SearchIntervalPolicy.parseMillis("1"));
        assertEquals(1000, SearchIntervalPolicy.parseMillis("1.0"));
        assertEquals(2000, SearchIntervalPolicy.parseMillis("2.0"));
        assertEquals(3200, SearchIntervalPolicy.parseMillis("3.2"));
        assertEquals(10000, SearchIntervalPolicy.parseMillis("10.0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBelowMinimum() {
        SearchIntervalPolicy.parseMillis("0.9");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooManyDecimals() {
        SearchIntervalPolicy.parseMillis("3.25");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidValue() {
        SearchIntervalPolicy.parseMillis("fast");
    }

    @Test public void formatsPersistedValueConsistently() {
        assertEquals("1.0", SearchIntervalPolicy.formatSeconds(1000));
        assertEquals("3.2", SearchIntervalPolicy.formatSeconds(3200));
    }
}
