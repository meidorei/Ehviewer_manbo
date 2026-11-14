package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GlobalScanPageLimitPolicyTest {
    @Test public void acceptsBoundsAndDefault() {
        assertEquals(1, GlobalScanPageLimitPolicy.parsePages("1"));
        assertEquals(30, GlobalScanPageLimitPolicy.parsePages("30"));
        assertEquals(300, GlobalScanPageLimitPolicy.parsePages("300"));
        assertEquals(30, GlobalScanPageLimitPolicy.parsePages(" 30 "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZero() {
        GlobalScanPageLimitPolicy.parsePages("0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAboveMaximum() {
        GlobalScanPageLimitPolicy.parsePages("301");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeValue() {
        GlobalScanPageLimitPolicy.parsePages("-1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDecimalValue() {
        GlobalScanPageLimitPolicy.parsePages("30.0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyValue() {
        GlobalScanPageLimitPolicy.parsePages("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOverflow() {
        GlobalScanPageLimitPolicy.parsePages("999999999999999999999999");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonNumericValue() {
        GlobalScanPageLimitPolicy.parsePages("many");
    }
}
