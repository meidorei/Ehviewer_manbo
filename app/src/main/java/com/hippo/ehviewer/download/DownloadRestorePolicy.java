package com.hippo.ehviewer.download;

import com.hippo.ehviewer.client.exception.CancelledException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Recovery-only batching. Ordinary requests and update scans remain fail-fast. */
public final class DownloadRestorePolicy {
    private DownloadRestorePolicy() {}

    public interface BatchLoader<T> {
        void load(List<T> batch) throws Throwable;
    }

    public interface Cancellation {
        boolean isCancelled();
    }

    public interface FailureHandler {
        void failed(Throwable error);
    }

    public static void checkCancelled(Cancellation cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (cancelled.isCancelled()) throw new CancellationException();
    }

    public static <T> void fill(List<T> items, Cancellation cancelled,
                               BatchLoader<T> loader, FailureHandler failed) throws Throwable {
        checkCancelled(cancelled);
        for (int start = 0; start < items.size(); start += 25) {
            checkCancelled(cancelled);
            try {
                loader.load(new ArrayList<>(items.subList(start, Math.min(start + 25, items.size()))));
            } catch (Throwable error) {
                if (error instanceof Error || error instanceof InterruptedException
                        || error instanceof CancellationException || error instanceof CancelledException) {
                    throw error;
                }
                checkCancelled(cancelled);
                failed.failed(error);
            }
            checkCancelled(cancelled);
        }
    }

    public static boolean accept(long gid, String token, boolean alreadyDownloaded, Set<Long> seen) {
        return gid >= 0 && token != null && !token.isEmpty()
                && !alreadyDownloaded && seen.add(gid);
    }

    public static String title(String title, String directory, long gid) {
        if (title != null && !title.isEmpty()) return title;
        return directory != null && !directory.isEmpty() ? directory : Long.toString(gid);
    }
}
