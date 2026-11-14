package com.hippo.ehviewer.subscription;

/** Real progress for a subscription scan whose final page count is not known in advance. */
public final class SubscriptionScanProgress {
    public enum Stage { SYNCING_TAGS, SCANNING_PAGES }

    public final Stage stage;
    public final int pagesScanned;

    private SubscriptionScanProgress(Stage stage, int pagesScanned) {
        this.stage = stage;
        this.pagesScanned = Math.max(0, pagesScanned);
    }

    public static SubscriptionScanProgress syncingTags() {
        return new SubscriptionScanProgress(Stage.SYNCING_TAGS, 0);
    }

    public static SubscriptionScanProgress scanningPages(int pagesScanned) {
        return new SubscriptionScanProgress(Stage.SCANNING_PAGES, pagesScanned);
    }
}
