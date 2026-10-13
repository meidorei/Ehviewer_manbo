package com.hippo.ehviewer.ui.scene.download.part;

/** Shared bounds for the download adapter, indicator and visible-page snapshots. */
public final class DownloadPaginationPolicy {
    public static final int THRESHOLD = 500;

    private DownloadPaginationPolicy() {}

    public static boolean isPaged(int size, boolean enabled) {
        return enabled && size > THRESHOLD;
    }

    public static int clampPage(int size, int pageSize, int page, boolean enabled) {
        if (!isPaged(size, enabled)) {
            return 1;
        }
        int lastPage = (size - 1) / Math.max(1, pageSize) + 1;
        return Math.max(1, Math.min(page, lastPage));
    }

    public static int pageStart(int size, int pageSize, int page, boolean enabled) {
        if (!isPaged(size, enabled)) {
            return 0;
        }
        return (clampPage(size, pageSize, page, enabled) - 1) * Math.max(1, pageSize);
    }

    public static int itemCount(int size, int pageSize, int page, boolean enabled) {
        int count = Math.max(0, size);
        return isPaged(size, enabled)
                ? Math.min(Math.max(1, pageSize), count - pageStart(size, pageSize, page, enabled))
                : count;
    }

    /** Returns -1 for an item outside the visible page, rather than wrapping its index. */
    public static int indexInPage(int position, int size, int pageSize, int page,
            boolean enabled) {
        int index = position - pageStart(size, pageSize, page, enabled);
        return index >= 0 && index < itemCount(size, pageSize, page, enabled) ? index : -1;
    }

    public static int positionInList(int position, int size, int pageSize, int page,
            boolean enabled) {
        return position >= 0 && position < itemCount(size, pageSize, page, enabled)
                ? pageStart(size, pageSize, page, enabled) + position : -1;
    }
}
