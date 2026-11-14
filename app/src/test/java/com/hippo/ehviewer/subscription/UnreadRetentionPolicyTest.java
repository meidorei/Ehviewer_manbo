package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UnreadRetentionPolicyTest {
    @Test public void bookmarksKeepEveryUnreadGid() {
        assertEquals(UnreadRetentionPolicy.UNLIMITED,
                UnreadRetentionPolicy.maxRows(LocalFollowRepository.SOURCE_BOOKMARK));
    }

    @Test public void localFollowsKeepLegacyTwentyOneRows() {
        assertEquals(UnreadRetentionPolicy.LOCAL_FOLLOW_LIMIT,
                UnreadRetentionPolicy.maxRows(LocalFollowRepository.SOURCE_FOLLOW));
    }
}
