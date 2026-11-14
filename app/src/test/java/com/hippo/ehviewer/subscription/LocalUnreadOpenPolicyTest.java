package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalUnreadOpenPolicyTest {
    @Test public void emptyBookmarkResultStillCompletesUnreadClear() {
        assertTrue(LocalUnreadOpenPolicy.shouldComplete(
                FeedSourceContext.Type.QUICK_SEARCH, true));
    }

    @Test public void emptyFollowResultKeepsLegacyBehavior() {
        assertFalse(LocalUnreadOpenPolicy.shouldComplete(
                FeedSourceContext.Type.SUBSCRIPTION_TAG, true));
    }

    @Test public void unrelatedFeedsNeverClearBookmarkUnread() {
        assertFalse(LocalUnreadOpenPolicy.shouldComplete(
                FeedSourceContext.Type.HOME, false));
    }
}
