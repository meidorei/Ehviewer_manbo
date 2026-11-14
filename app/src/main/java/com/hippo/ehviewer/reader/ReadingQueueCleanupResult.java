package com.hippo.ehviewer.reader;

/** Summary returned after a manual or capacity-triggered cleanup pass. */
public final class ReadingQueueCleanupResult {
    public final int deleted;
    public final int failed;

    public ReadingQueueCleanupResult(int deleted, int failed) {
        this.deleted = deleted;
        this.failed = failed;
    }
}
