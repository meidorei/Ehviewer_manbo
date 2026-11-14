package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateBadgeFormatterTest {
    @Test public void neverCheckedHasNoBadge() {
        assertEquals("bookmark", UpdateBadgeFormatter.format("bookmark", null));
    }

    @Test public void zeroBaselineRemainsVisible() {
        assertEquals("0  ·  bookmark", UpdateBadgeFormatter.format("bookmark", "0"));
    }

    @Test public void countAndReasonRemainVisible() {
        assertEquals("20+  ●  bookmark", UpdateBadgeFormatter.format("bookmark", "20+"));
        assertEquals("1  ●  bookmark", UpdateBadgeFormatter.format("bookmark", "1"));
        assertEquals("语言条件冲突  ·  bookmark",
                UpdateBadgeFormatter.format("bookmark", "语言条件冲突"));
    }

    @Test public void presentationKeepsColumnsForEveryNormalState() {
        assertPresentation(null, "", "", false);
        assertPresentation("0", "0", "·", false);
        assertPresentation("1", "1", "●", true);
        assertPresentation("10", "10", "●", true);
        assertPresentation("20", "20", "●", true);
        assertPresentation("20+", "20+", "●", true);
    }

    @Test public void diagnosticPresentationRemainsComplete() {
        UpdateBadgeFormatter.Presentation presentation =
                UpdateBadgeFormatter.present("bookmark", "语言条件冲突");
        assertEquals("语言条件冲突", presentation.count);
        assertEquals("·", presentation.indicator);
        assertEquals("bookmark", presentation.name);
        assertFalse(presentation.hasNewContent);
        assertTrue(presentation.diagnostic);
    }

    @Test public void presentationKeepsOriginalQueryDetail() {
        UpdateBadgeFormatter.Presentation presentation =
                UpdateBadgeFormatter.present("女性:口交", "20", "female:oral");
        assertEquals("女性:口交", presentation.name);
        assertEquals("female:oral", presentation.detail);
    }

    private static void assertPresentation(String badge, String count, String indicator,
                                           boolean hasNewContent) {
        UpdateBadgeFormatter.Presentation presentation =
                UpdateBadgeFormatter.present("bookmark", badge);
        assertEquals(count, presentation.count);
        assertEquals(indicator, presentation.indicator);
        assertEquals("bookmark", presentation.name);
        if (hasNewContent) {
            assertTrue(presentation.hasNewContent);
        } else {
            assertFalse(presentation.hasNewContent);
        }
    }
}
