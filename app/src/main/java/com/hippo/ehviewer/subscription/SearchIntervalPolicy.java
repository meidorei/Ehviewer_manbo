package com.hippo.ehviewer.subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Parsing and bounds for the local-update search request interval preference. */
public final class SearchIntervalPolicy {
    public static final int DEFAULT_MS = 3200;
    public static final int MIN_MS = 1000;
    public static final int MAX_MS = 10000;
    public static final int WARNING_BELOW_MS = 3000;

    private SearchIntervalPolicy() {}

    public static int parseMillis(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("empty interval");
        }
        try {
            BigDecimal seconds = new BigDecimal(value.trim());
            if (seconds.scale() > 1) {
                throw new IllegalArgumentException("only one decimal place is allowed");
            }
            int millis = seconds.multiply(BigDecimal.valueOf(1000))
                    .setScale(0, RoundingMode.UNNECESSARY).intValueExact();
            if (millis < MIN_MS || millis > MAX_MS) {
                throw new IllegalArgumentException("interval out of range");
            }
            return millis;
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("invalid interval", error);
        }
    }

    public static String formatSeconds(int millis) {
        int clamped = Math.max(MIN_MS, Math.min(MAX_MS, millis));
        return String.format(Locale.US, "%.1f", clamped / 1000d);
    }
}
