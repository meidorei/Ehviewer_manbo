package com.hippo.ehviewer.subscription;

public final class FeedCheckpoint {
    public final FeedBoundary previous;
    public final FeedBoundary current;
    public final long updatedAt;

    public FeedCheckpoint(FeedBoundary previous, FeedBoundary current, long updatedAt) {
        this.previous = previous;
        this.current = current;
        this.updatedAt = updatedAt;
    }
}
