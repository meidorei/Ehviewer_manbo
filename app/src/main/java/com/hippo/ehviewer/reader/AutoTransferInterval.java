package com.hippo.ehviewer.reader;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

public final class AutoTransferInterval {

    public static final int MIN_MILLIS = 500;
    public static final int MAX_MILLIS = 5000;
    public static final int STEP_MILLIS = 100;
    public static final int DEFAULT_MILLIS = 2000;
    public static final int MAX_PROGRESS = (MAX_MILLIS - MIN_MILLIS) / STEP_MILLIS;

    private static final Pattern INPUT_PATTERN = Pattern.compile("\\d(?:\\.\\d)?");

    private AutoTransferInterval() {
    }

    public static int clamp(int millis) {
        return Math.max(MIN_MILLIS, Math.min(MAX_MILLIS, millis));
    }

    public static int normalize(int millis) {
        return progressToMillis(millisToProgress(millis));
    }

    public static int migrateLegacySeconds(int seconds) {
        return clamp(seconds * 1000);
    }

    public static int progressToMillis(int progress) {
        int safeProgress = Math.max(0, Math.min(MAX_PROGRESS, progress));
        return MIN_MILLIS + safeProgress * STEP_MILLIS;
    }

    public static int millisToProgress(int millis) {
        return Math.round((clamp(millis) - MIN_MILLIS) / (float) STEP_MILLIS);
    }

    public static String formatSeconds(int millis) {
        return String.format(Locale.US, "%.1f", normalize(millis) / 1000f);
    }

    public static boolean hasValidFormat(@Nullable String text) {
        return text != null && INPUT_PATTERN.matcher(text.trim()).matches();
    }

    public static int parseMillis(@Nullable String text) {
        if (!hasValidFormat(text)) {
            return -1;
        }
        try {
            int millis = Math.round(Float.parseFloat(text.trim()) * 1000f);
            if (millis < MIN_MILLIS || millis > MAX_MILLIS || millis % STEP_MILLIS != 0) {
                return -1;
            }
            return millis;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
