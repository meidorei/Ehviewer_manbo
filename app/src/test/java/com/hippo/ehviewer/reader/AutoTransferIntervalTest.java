package com.hippo.ehviewer.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoTransferIntervalTest {

    @Test
    public void boundariesAndProgressStayInSync() {
        assertEquals(500, AutoTransferInterval.progressToMillis(0));
        assertEquals(600, AutoTransferInterval.progressToMillis(1));
        assertEquals(2300, AutoTransferInterval.progressToMillis(18));
        assertEquals(5000, AutoTransferInterval.progressToMillis(45));
        assertEquals(0, AutoTransferInterval.millisToProgress(500));
        assertEquals(18, AutoTransferInterval.millisToProgress(2300));
        assertEquals(45, AutoTransferInterval.millisToProgress(5000));
    }

    @Test
    public void normalizationRoundsToNearestStepAndClamps() {
        assertEquals(500, AutoTransferInterval.normalize(0));
        assertEquals(2300, AutoTransferInterval.normalize(2349));
        assertEquals(2400, AutoTransferInterval.normalize(2350));
        assertEquals(5000, AutoTransferInterval.normalize(9000));
    }

    @Test
    public void preciseInputsAreAccepted() {
        assertEquals(500, AutoTransferInterval.parseMillis("0.5"));
        assertEquals(600, AutoTransferInterval.parseMillis("0.6"));
        assertEquals(2300, AutoTransferInterval.parseMillis("2.3"));
        assertEquals(5000, AutoTransferInterval.parseMillis("5.0"));
        assertTrue(AutoTransferInterval.hasValidFormat("2"));
    }

    @Test
    public void invalidInputsAreRejected() {
        assertEquals(-1, AutoTransferInterval.parseMillis(""));
        assertEquals(-1, AutoTransferInterval.parseMillis("0.4"));
        assertEquals(-1, AutoTransferInterval.parseMillis("5.1"));
        assertEquals(-1, AutoTransferInterval.parseMillis("2.34"));
        assertEquals(-1, AutoTransferInterval.parseMillis("fast"));
        assertFalse(AutoTransferInterval.hasValidFormat(null));
    }

    @Test
    public void legacySecondsKeepDisplayedValueAndClamp() {
        assertEquals(500, AutoTransferInterval.migrateLegacySeconds(0));
        assertEquals(2000, AutoTransferInterval.migrateLegacySeconds(2));
        assertEquals(5000, AutoTransferInterval.migrateLegacySeconds(15));
    }
}
