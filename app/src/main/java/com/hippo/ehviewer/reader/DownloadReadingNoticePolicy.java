package com.hippo.ehviewer.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DownloadReadingNoticePolicy {

    public static final int MAX_TITLE_CODE_POINTS = 40;

    public enum Notice {
        SKIPPED,
        OPENING,
        END
    }

    private DownloadReadingNoticePolicy() {
    }

    @NonNull
    public static List<Notice> buildAdvanceNotices(int skippedCount, boolean hasNext) {
        ArrayList<Notice> notices = new ArrayList<>(2);
        if (skippedCount > 0) {
            notices.add(Notice.SKIPPED);
        }
        notices.add(hasNext ? Notice.OPENING : Notice.END);
        return Collections.unmodifiableList(notices);
    }

    @NonNull
    public static String ellipsizeTitle(@Nullable String title, long fallbackGid) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            return Long.toString(fallbackGid);
        }
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= MAX_TITLE_CODE_POINTS) {
            return value;
        }
        int contentCodePoints = MAX_TITLE_CODE_POINTS - 1;
        int endIndex = value.offsetByCodePoints(0, contentCodePoints);
        return value.substring(0, endIndex) + '\u2026';
    }

    public static boolean shouldShowProviderError(boolean queuedTransition,
            boolean errorAlreadyShown, boolean providerFailed) {
        return queuedTransition && !errorAlreadyShown && providerFailed;
    }
}
