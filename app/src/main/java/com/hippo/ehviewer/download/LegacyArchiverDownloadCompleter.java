package com.hippo.ehviewer.download;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.unifile.UniFile;
import com.hippo.util.FileUtils;

import java.io.File;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;

/**
 * 归档系统 DownloadManager 任务的完成处理：Application 级广播、enqueue 后补查、轮询兜底、进程重启恢复。
 */
public class LegacyArchiverDownloadCompleter {

    private static final String TAG = "LegacyArchiverDownloadCompleter";

    @Nullable
    private static LegacyArchiverDownloadCompleter sInstance;

    private final Context appContext;
    private final Handler mainHandler;
    private final Set<Long> handlingIds = new HashSet<>();

    private boolean receiverRegistered;
    @Nullable
    private BroadcastReceiver downloadReceiver;

    private LegacyArchiverDownloadCompleter(Context appContext) {
        this.appContext = appContext;
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized LegacyArchiverDownloadCompleter getInstance(@Nullable Context context) {
        if (sInstance == null && context != null) {
            sInstance = new LegacyArchiverDownloadCompleter(context.getApplicationContext());
        }
        return sInstance;
    }

    public static void resumePendingDownloads(Context context) {
        LegacyArchiverDownloadCompleter completer = getInstance(context);
        if (completer == null) {
            return;
        }
        for (long downloadId : Settings.getPendingArchiverDownloadIds()) {
            if (Settings.getArchiverDownloadUrl(downloadId).isEmpty()) {
                completer.ensureReceiverRegistered();
                completer.checkAndHandleStatus(downloadId);
            }
        }
    }

    public synchronized void ensureReceiverRegistered() {
        if (receiverRegistered) {
            return;
        }
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    return;
                }
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (downloadId < 0) {
                    return;
                }
                checkAndHandleStatus(downloadId);
            }
        };
        ContextCompat.registerReceiver(
                appContext,
                downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private synchronized void unregisterReceiverIfIdle() {
        if (!receiverRegistered || Settings.hasPendingArchiverDownloads()) {
            return;
        }
        if (downloadReceiver != null) {
            try {
                appContext.unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver already unregistered", e);
            }
            downloadReceiver = null;
        }
        receiverRegistered = false;
    }

    /**
     * enqueue 之后调用，捕获「注册前已完成」的竞态。
     */
    public void retryImport(long downloadId) {
        GalleryInfo info = Settings.getArchiverDownload(downloadId);
        if (info == null || !Settings.getArchiverDownloadUrl(downloadId).isEmpty()) return;
        Settings.updateArchiverPhase(info.gid, downloadId, true, false);
        checkAndHandleStatus(downloadId);
    }

    public void checkAndHandleStatus(long downloadId) {
        if (Settings.getArchiverImportPending(downloadId) && Settings.getArchiverDownloadPaused(downloadId)) return;
        if (downloadId < 0 || !Settings.getArchiverDownloadUrl(downloadId).isEmpty() || Settings.getArchiverDownload(downloadId) == null) {
            return;
        }
        DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return;
        }
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor c = dm.query(query)) {
            if (c == null || !c.moveToFirst()) {
                return;
            }
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            switch (status) {
                case DownloadManager.STATUS_SUCCESSFUL:
                    Log.i(TAG, "Download successful, id=" + downloadId);
                    handleSuccessfulDownload(c);
                    break;
                case DownloadManager.STATUS_FAILED:
                    Log.i(TAG, "Download failed, id=" + downloadId);
                    handleFailedDownload(downloadId);
                    break;
                default:
                    break;
            }
        }
    }

    private boolean tryBeginHandling(long downloadId) {
        synchronized (handlingIds) {
            if (handlingIds.contains(downloadId)) {
                return false;
            }
            handlingIds.add(downloadId);
            return true;
        }
    }

    private void endHandling(long downloadId) {
        synchronized (handlingIds) {
            handlingIds.remove(downloadId);
        }
    }

    private void handleFailedDownload(long downloadId) {
        GalleryInfo info = Settings.getArchiverDownload(downloadId);
        if (info == null) {
            return;
        }
        Settings.clearArchiverTask(info.gid, downloadId);
        mainHandler.post(() ->
                Toast.makeText(appContext, R.string.download_state_failed, Toast.LENGTH_LONG).show());
        unregisterReceiverIfIdle();
    }

    private void handleSuccessfulDownload(Cursor cursor) {
        long downloadId = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
        if (!tryBeginHandling(downloadId)) {
            return;
        }
        GalleryInfo galleryInfo = Settings.getArchiverDownload(downloadId);
        if (galleryInfo == null) {
            endHandling(downloadId);
            return;
        }
        try {
            unzipAndImportFile(cursor, galleryInfo, downloadId);
        } catch (IllegalArgumentException | URISyntaxException e) {
            Log.e(TAG, e.getMessage(), e);
            postImportFailed(downloadId);
        }
    }

    private void unzipAndImportFile(Cursor cursor, GalleryInfo galleryInfo, long downloadId)
            throws IllegalArgumentException, URISyntaxException {
        String path = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
        Uri uri = Uri.parse(path);
        File tempDir = AppConfig.getExternalTempDir();
        if (tempDir == null) {
            postImportFailed(downloadId);
            return;
        }
        String fileName = "legacy_" + ArchiverTaskFilePolicy.uniqueName(galleryInfo.gid, downloadId);
        if (!Settings.updateArchiverPhase(galleryInfo.gid, downloadId, true, false)) {
            endHandling(downloadId);
            return;
        }
        ArchiverDownloader.legacyImportChanged(appContext, galleryInfo.gid, downloadId, null, null, false);
        new Thread(() -> {
            try {
                File zip;
                if ("file".equals(uri.getScheme())) {
                    zip = new File(uri.getPath());
                } else {
                    zip = new File(tempDir, fileName + ".zip");
                    UniFile sourceFile = UniFile.fromUri(appContext, uri);
                    UniFile destFile = UniFile.fromFile(zip);
                    if (sourceFile == null || destFile == null || !FileUtils.copyFile(sourceFile, destFile, false)) {
                        throw new java.io.IOException("Cannot copy system archive ZIP");
                    }
                }
                File importZip = zip;
                ArchiverDownloadCompleter.getInstance(appContext).importDownloadedZip(
                        importZip, galleryInfo, downloadId, error -> {
                            endHandling(downloadId);
                            if (!ArchiverTaskFilePolicy.owns(downloadId,
                                    Settings.getArchiverDownloadId(galleryInfo.gid))) return;
                            if (error == null && Settings.clearArchiverTask(galleryInfo.gid, downloadId)) {
                                if (!"file".equals(uri.getScheme())) importZip.delete();
                                ArchiverDownloader.legacyImportChanged(appContext, galleryInfo.gid,
                                        downloadId, importZip, null, true);
                                unregisterReceiverIfIdle();
                            } else {
                                Settings.updateArchiverPhase(galleryInfo.gid, downloadId, true, true);
                                ArchiverDownloader.legacyImportChanged(appContext, galleryInfo.gid,
                                        downloadId, importZip, error != null ? error :
                                                new java.io.IOException("Cannot clear completed task"), false);
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Error preparing system archive", e);
                postImportFailed(downloadId);
            }
        }, "LegacyArchiveImport").start();
    }

    private void postImportFailed(long downloadId) {
        endHandling(downloadId);
        GalleryInfo info = Settings.getArchiverDownload(downloadId);
        if (info == null || !Settings.updateArchiverPhase(info.gid, downloadId, true, true)) return;
        mainHandler.post(() -> {
            ArchiverDownloader.legacyImportChanged(appContext, info.gid, downloadId, null,
                    new java.io.IOException("System archive import failed"), false);
            Toast.makeText(appContext, R.string.archiver_import_failed, Toast.LENGTH_LONG).show();
        });
    }

    public static String createFileName(@Nullable String name, long gid) {
        return ArchiverDownloadCompleter.createFileName(name, gid);
    }
}
