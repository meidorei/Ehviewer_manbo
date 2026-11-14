package com.hippo.ehviewer.subscription;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Pure formatting helpers shared by the follow and bookmark update toolbars. */
public final class LocalRefreshStatusFormatter {
    private LocalRefreshStatusFormatter() {}

    public static String formatTime(long timestamp, long now) {
        return formatTime(timestamp, now, Locale.getDefault(), TimeZone.getDefault());
    }

    static String formatTime(long timestamp, long now, Locale locale, TimeZone timeZone) {
        Calendar value = Calendar.getInstance(timeZone, locale);
        value.setTimeInMillis(timestamp);
        Calendar current = Calendar.getInstance(timeZone, locale);
        current.setTimeInMillis(now);
        boolean chinese = Locale.CHINESE.getLanguage().equals(locale.getLanguage());
        boolean sameYear = value.get(Calendar.ERA) == current.get(Calendar.ERA)
                && value.get(Calendar.YEAR) == current.get(Calendar.YEAR);
        String pattern = chinese
                ? (sameYear ? "M月d日 HH:mm" : "yyyy年M月d日 HH:mm")
                : (sameYear ? "MMM d HH:mm" : "yyyy MMM d HH:mm");
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale);
        format.setTimeZone(timeZone);
        return format.format(timestamp);
    }
}
