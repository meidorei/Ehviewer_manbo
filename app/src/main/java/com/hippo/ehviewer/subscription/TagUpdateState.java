package com.hippo.ehviewer.subscription;

public final class TagUpdateState {
    public static final int DISPLAY_CAP = 20;
    public enum State { EXACT, LOWER_BOUND, UNKNOWN }

    public final String tagName;
    public final int count;
    public final State state;
    public final long checkedAt;

    public TagUpdateState(String tagName, int count, State state, long checkedAt) {
        this.tagName = SubscriptionRepository.normalizeTagName(tagName);
        int normalized = Math.max(0, count);
        this.count = Math.min(DISPLAY_CAP, normalized);
        this.state = normalized > DISPLAY_CAP ? State.LOWER_BOUND : state;
        this.checkedAt = checkedAt;
    }

    public String displayCount() {
        if (state == State.UNKNOWN) return "?";
        return state == State.LOWER_BOUND ? DISPLAY_CAP + "+" : Integer.toString(count);
    }
}
