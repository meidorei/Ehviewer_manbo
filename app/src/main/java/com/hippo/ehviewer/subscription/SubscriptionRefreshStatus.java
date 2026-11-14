package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.Settings;

/** Preference-backed result shown next to the manual follow-update action. */
public final class SubscriptionRefreshStatus {
    private static final String KEY_ACCOUNT = "subscription_refresh_status_account";
    private static final String KEY_RESULT = "subscription_refresh_status_result";
    private static final String KEY_TIME = "subscription_refresh_status_time";
    private static final String KEY_ERROR = "subscription_refresh_status_error";

    public enum Result { SUCCESS, FAILURE, CANCELLED }

    public final Result result;
    public final long time;

    private SubscriptionRefreshStatus(Result result, long time) {
        this.result = result;
        this.time = time;
    }

    public static void save(String accountKey, Result result, long time) {
        Settings.putString(KEY_ACCOUNT, accountKey);
        Settings.putString(KEY_RESULT, result.name());
        Settings.putLong(KEY_TIME, time);
        if (result == Result.SUCCESS) Settings.putString(KEY_ERROR, "");
    }

    public static void saveError(String error) {
        Settings.putString(KEY_ERROR, error == null ? "" : error);
    }

    public static SubscriptionRefreshStatus read(String accountKey) {
        if (!accountKey.equals(Settings.getString(KEY_ACCOUNT, ""))) return null;
        long time = Settings.getLong(KEY_TIME, 0L);
        if (time <= 0) return null;
        try {
            return new SubscriptionRefreshStatus(Result.valueOf(
                    Settings.getString(KEY_RESULT, "")), time);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
