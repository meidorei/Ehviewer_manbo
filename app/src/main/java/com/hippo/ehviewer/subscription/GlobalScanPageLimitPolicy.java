package com.hippo.ehviewer.subscription;

/** Parsing and bounds for the shared global-scan page limit preference. */
public final class GlobalScanPageLimitPolicy {
    public static final int DEFAULT_PAGES = 30;
    public static final int MIN_PAGES = 1;
    public static final int MAX_PAGES = 300;

    private GlobalScanPageLimitPolicy() {}

    public static int parsePages(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("empty page limit");
        }
        String normalized = value.trim();
        if (!normalized.matches("[0-9]+")) {
            throw new IllegalArgumentException("page limit must be a whole number");
        }
        final int pages;
        try {
            pages = Integer.parseInt(normalized);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid page limit", error);
        }
        if (pages < MIN_PAGES || pages > MAX_PAGES) {
            throw new IllegalArgumentException("page limit out of range");
        }
        return pages;
    }
}
