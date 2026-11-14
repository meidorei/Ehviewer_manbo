package com.hippo.ehviewer.reader;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes queue writes and destructive local-download cleanup. */
public final class ReadingQueueManager {
    public interface Callback {
        void onComplete(@NonNull ReadingQueueCleanupResult result);
    }

    public interface SnapshotCallback {
        void onComplete(@NonNull ReadingQueueSnapshot snapshot);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ReadingQueue");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<Long> PENDING_READS = ConcurrentHashMap.newKeySet();

    private ReadingQueueManager() {}

    public static boolean isEligible(@Nullable DownloadInfo info) {
        UniFile directory = info != null ? SpiderDen.getExistingGalleryDownloadDir(info) : null;
        return ReadingQueueEligibility.isEligible(info,
                directory != null && directory.isDirectory());
    }

    public static void recordSuccessfulRead(@NonNull Context context,
            @NonNull DownloadInfo info, int currentPage, int totalPages) {
        Context appContext = context.getApplicationContext();
        long gid = info.gid;
        PENDING_READS.add(gid);
        EXECUTOR.execute(() -> {
            try {
                ReadingQueueRepository.getInstance().markRead(
                        gid, currentPage, totalPages);
            } finally {
                PENDING_READS.remove(gid);
            }
            if (Settings.getReadingQueueAutoDelete()) {
                ReadingQueueCleanupResult result = trimInternal(appContext,
                        Settings.getReadingQueueCapacity());
                showAutomaticResult(appContext, result);
            }
        });
    }

    /** Updates a member's last successfully displayed page without changing queue order. */
    public static void updateProgress(long gid, int currentPage, int totalPages) {
        EXECUTOR.execute(() -> ReadingQueueRepository.getInstance().updateProgress(
                gid, currentPage, totalPages));
    }

    public static void trim(@NonNull Context context, @Nullable Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            ReadingQueueCleanupResult result = trimInternal(appContext,
                    Settings.getReadingQueueCapacity());
            postResult(callback, result);
        });
    }

    /** Loads queue membership and progress after all prior queue writes complete. */
    public static void loadSnapshot(@Nullable SnapshotCallback callback) {
        EXECUTOR.execute(() -> {
            ReadingQueueSnapshot snapshot = ReadingQueueRepository.getInstance().getSnapshot();
            if (callback != null) {
                SimpleHandler.getInstance().post(() -> callback.onComplete(snapshot));
            }
        });
    }

    public static void removeFromQueue(long gid, @Nullable Callback callback) {
        EXECUTOR.execute(() -> {
            ReadingQueueRepository.getInstance().remove(gid);
            postResult(callback, new ReadingQueueCleanupResult(0, 0));
        });
    }

    public static void deleteDownload(@NonNull Context context, long gid,
            @Nullable Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            DownloadManager downloadManager = EhApplication.getDownloadManager(appContext);
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (info == null) {
                ReadingQueueRepository.getInstance().remove(gid);
                postResult(callback, new ReadingQueueCleanupResult(0, 0));
                return;
            }
            boolean deleted = deleteFiles(info);
            if (!deleted) {
                postResult(callback, new ReadingQueueCleanupResult(0, 1));
                return;
            }
            removeDownloadMetadata(downloadManager, info);
            postResult(callback, new ReadingQueueCleanupResult(1, 0));
        });
    }

    /** Removes queue rows that no longer point at complete app-managed downloads. */
    public static void pruneInvalid(@NonNull Context context, @Nullable Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            DownloadManager downloadManager = EhApplication.getDownloadManager(appContext);
            ReadingQueueRepository repository = ReadingQueueRepository.getInstance();
            for (Long gid : repository.getNewestFirst()) {
                DownloadInfo info = downloadManager.getDownloadInfo(gid);
                if (!isEligible(info)) {
                    repository.remove(gid);
                }
            }
            postResult(callback, new ReadingQueueCleanupResult(0, 0));
        });
    }

    private static ReadingQueueCleanupResult trimInternal(Context context, int capacity) {
        ReadingQueueRepository repository = ReadingQueueRepository.getInstance();
        List<Long> initial = ReadingQueuePolicy.oldestOverflow(
                repository.getNewestFirst(), capacity);
        DownloadManager downloadManager = EhApplication.getDownloadManager(context);
        int deleted = 0;
        int failed = 0;
        for (Long gid : initial) {
            if (PENDING_READS.contains(gid)) {
                continue;
            }
            if (!repository.isOverflowCandidate(gid, capacity)) {
                continue;
            }
            DownloadInfo info = downloadManager.getDownloadInfo(gid);
            if (!isEligible(info)) {
                repository.remove(gid);
                continue;
            }
            if (!deleteFiles(info)) {
                failed++;
                continue;
            }
            removeDownloadMetadata(downloadManager, info);
            deleted++;
        }
        return new ReadingQueueCleanupResult(deleted, failed);
    }

    private static boolean deleteFiles(@NonNull DownloadInfo info) {
        UniFile directory = SpiderDen.getExistingGalleryDownloadDir(info);
        if (directory == null || !directory.exists()) {
            return true;
        }
        try {
            return directory.delete() || !directory.exists();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void removeDownloadMetadata(@NonNull DownloadManager downloadManager,
            @NonNull DownloadInfo info) {
        EhDB.removeDownloadDirname(info.gid);
        ReadingQueueRepository.getInstance().remove(info.gid);
        SimpleHandler.getInstance().post(() -> downloadManager.deleteDownload(info.gid));
    }

    private static void showAutomaticResult(Context context, ReadingQueueCleanupResult result) {
        if (result.deleted == 0 && result.failed == 0) {
            return;
        }
        SimpleHandler.getInstance().post(() -> {
            if (result.failed == 0) {
                Toast.makeText(context, context.getString(
                        R.string.reading_queue_auto_deleted, result.deleted),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, context.getString(
                        R.string.reading_queue_cleanup_result,
                        result.deleted, result.failed), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void postResult(@Nullable Callback callback,
            @NonNull ReadingQueueCleanupResult result) {
        if (callback != null) {
            SimpleHandler.getInstance().post(() -> callback.onComplete(result));
        }
    }
}
