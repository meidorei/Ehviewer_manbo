package com.hippo.ehviewer.reader;

import com.hippo.ehviewer.dao.DownloadInfo;

/** Eligibility rules kept separate from reader and storage code. */
public final class ReadingQueueEligibility {
    private ReadingQueueEligibility() {}

    public static boolean isEligible(DownloadInfo info, boolean hasLocalDirectory) {
        return info != null && isEligible(info.state, info.archiveUri, hasLocalDirectory);
    }

    static boolean isEligible(int state, String archiveUri, boolean hasLocalDirectory) {
        return state == DownloadInfo.STATE_FINISH
                && (archiveUri == null || archiveUri.isEmpty())
                && hasLocalDirectory;
    }
}
