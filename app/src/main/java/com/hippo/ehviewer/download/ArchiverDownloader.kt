package com.hippo.ehviewer.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** Application-owned ZIP download and import lifecycle. */
class ArchiverDownloader private constructor(appContext: Context) {
    private val appContext = appContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val taskIdGenerator = AtomicLong(maxOf(System.currentTimeMillis(),
        Settings.getPendingArchiverDownloadIds().maxOrNull() ?: 0L))
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val activeTasks = ConcurrentHashMap<Long, ActiveTask>()

    interface Listener {
        fun onProgress(gid: Long, taskId: Long, downloaded: Long, total: Long, speed: Long, paused: Boolean)
        fun onSuccess(gid: Long, taskId: Long, zipFile: File)
        fun onFailure(gid: Long, taskId: Long, e: Exception?)
        fun onCancel(gid: Long, taskId: Long)
    }

    class Progress(
        @JvmField val gid: Long, @JvmField val taskId: Long,
        @JvmField val downloaded: Long, @JvmField val total: Long,
        @JvmField val speed: Long, @JvmField val paused: Boolean,
        @JvmField val importPending: Boolean, @JvmField val restartRequired: Boolean
    )

    private class ActiveTask(val taskId: Long, val galleryInfo: GalleryInfo,
                             val url: String, val fileName: String, val zipFile: File) {
        val gid = galleryInfo.gid
        val fileLock = Any()
        @Volatile var call: Call? = null
        @Volatile var downloaded = 0L
        @Volatile var total = 0L
        @Volatile var speed = 0L
        @Volatile var paused = false
        @Volatile var importPending = false
        @Volatile var importing = false
        @Volatile var restartRequired = false
        var lastNotifyAt = 0L
        var lastSpeedAt = 0L
        var bytesSinceSpeed = 0L
    }

    private class PauseSignal : IOException("Paused or superseded")

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun getProgress(gid: Long): Progress? = activeTasks[gid]?.let {
        Progress(it.gid, it.taskId, it.downloaded, it.total, it.speed, it.paused,
            it.importPending, it.restartRequired)
    }

    @Synchronized
    fun start(context: Context, info: GalleryInfo, url: String, fileName: String): Long {
        cancel(info.gid)
        val taskId = taskIdGenerator.incrementAndGet()
        // fileName is retained in the Java API for callers; titles are display names only.
        val storageName = ArchiverTaskFilePolicy.uniqueName(info.gid, taskId)
        if (!Settings.saveArchiverTask(taskId, info, url, storageName)) {
            Settings.clearArchiverTask(info.gid, taskId)
            notifyFailure(info.gid, taskId, IOException("Cannot save archive task"))
            toastOnMain(R.string.download_state_failed)
            return -1L
        }
        val zip = resolveZipFile(storageName)
        if (zip == null) {
            Settings.clearArchiverTask(info.gid, taskId)
            notifyFailure(info.gid, taskId, IOException("Cannot resolve archiver directory"))
            return taskId
        }
        val task = ActiveTask(taskId, info, url, storageName, zip)
        activeTasks[info.gid] = task
        startNetwork(task)
        return taskId
    }

    @Synchronized
    fun pause(gid: Long) {
        val task = activeTasks[gid] ?: return
        if (task.paused || task.importPending) return
        task.paused = true
        task.speed = 0
        Settings.updateArchiverPhase(gid, task.taskId, false, true)
        task.call?.cancel()
        publishProgress(task, true)
    }

    @Synchronized
    fun resume(gid: Long) {
        val task = activeTasks[gid] ?: return
        if (!task.paused || !isCurrent(task)) return
        if (!Settings.updateArchiverPhase(gid, task.taskId, task.importPending, false)) return
        task.paused = false
        task.restartRequired = false
        if (task.importPending) beginImport(task) else startNetwork(task)
    }

    @Synchronized
    fun cancel(gid: Long) {
        val task = activeTasks.remove(gid) ?: return
        task.call?.cancel()
        Settings.clearArchiverTask(gid, task.taskId)
        // An importer may still have this file open. Retaining its ZIP is safer than deleting a source in use.
        if (!task.importPending) deleteZipFile(task.zipFile)
        for (listener in listeners) listener.onCancel(gid, task.taskId)
        stopServiceIfIdle()
    }

    private fun isCurrent(task: ActiveTask): Boolean = activeTasks[task.gid] === task &&
        ArchiverTaskFilePolicy.owns(task.taskId, Settings.getArchiverDownloadId(task.gid))

    private fun isCurrentCall(task: ActiveTask, call: Call): Boolean =
        isCurrent(task) && task.call === call

    private fun startNetwork(task: ActiveTask) {
        if (!isCurrent(task)) return
        task.speed = 0
        task.bytesSinceSpeed = 0
        task.lastSpeedAt = SystemClock.elapsedRealtime()
        task.lastNotifyAt = 0
        ArchiverDownloadService.start(appContext, task.galleryInfo.title, task.gid, task.galleryInfo.token)
        // Wait for a cancelled writer before taking the new byte offset or opening the same file.
        val offset = synchronized(task.fileLock) { task.zipFile.takeIf { it.isFile }?.length() ?: 0L }
        task.downloaded = offset
        val request = Request.Builder().url(task.url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val call = EhApplication.getOkHttpClient(appContext).newCall(request)
        task.call = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isCurrentCall(task, call)) return
                if (task.paused) publishProgress(task, true) else failDownload(task, e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!isCurrentCall(task, call)) return
                    try {
                        if (task.paused) throw PauseSignal()
                        if (it.code() == 416) {
                            if (task.total > 0 && task.zipFile.length() == task.total) {
                                downloadComplete(task)
                                return
                            }
                            throw IOException("HTTP 416")
                        }
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code()}")
                        val body = it.body() ?: throw IOException("Empty response body")
                        synchronized(task.fileLock) {
                            if (!isCurrentCall(task, call) || task.paused) throw PauseSignal()
                            val append = offset > 0 && it.code() == 206
                            val writeOffset = if (append) offset else 0L
                            task.downloaded = writeOffset
                            if (offset > 0 && !append) toastOnMain(R.string.archiver_download_restart_no_range)
                            if (append) {
                                val range = it.header("Content-Range").orEmpty()
                                if (!range.startsWith("bytes $offset-")) throw IOException("Unexpected Content-Range")
                            }
                            task.total = resolveTotal(it, writeOffset, body.contentLength())
                            if (task.total > 0) Settings.putArchiverDownloadTotal(task.taskId, task.total)
                            body.byteStream().use { input ->
                                FileOutputStream(task.zipFile, append).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var downloaded = writeOffset
                                    while (true) {
                                        if (!isCurrentCall(task, call) || task.paused || call.isCanceled) throw PauseSignal()
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        if (!isCurrentCall(task, call) || task.paused) throw PauseSignal()
                                        output.write(buffer, 0, read)
                                        downloaded += read
                                        task.downloaded = downloaded
                                        task.bytesSinceSpeed += read
                                        publishProgress(task, false)
                                    }
                                    output.flush()
                                }
                            }
                            if (task.total > 0 && task.downloaded != task.total) throw IOException("Incomplete archive ZIP")
                        }
                        if (isCurrentCall(task, call) && !task.paused) downloadComplete(task)
                    } catch (e: Exception) {
                        if (!isCurrentCall(task, call)) return
                        if (task.paused || e is PauseSignal) publishProgress(task, true)
                        else failDownload(task, e)
                    }
                }
            }
        })
    }

    @Synchronized
    private fun downloadComplete(task: ActiveTask) {
        if (!isCurrent(task)) return
        // Persist even when the server supplied no content length.
        if (!Settings.updateArchiverPhase(task.gid, task.taskId, true, false)) {
            failDownload(task, IOException("Cannot save archive import state"))
            return
        }
        task.importPending = true
        task.speed = 0
        beginImport(task)
    }

    @Synchronized
    private fun beginImport(task: ActiveTask) {
        if (!isCurrent(task) || task.importing) return
        task.importing = true
        task.paused = false
        publishProgress(task, true)
        ArchiverDownloadCompleter.getInstance(appContext)!!.importDownloadedZip(
            task.zipFile, task.galleryInfo, task.taskId) { error ->
            synchronized(this) {
                task.importing = false
                if (!isCurrent(task)) return@importDownloadedZip
                if (error == null) {
                    if (Settings.clearArchiverTask(task.gid, task.taskId)) {
                        activeTasks.remove(task.gid, task)
                        for (listener in listeners) listener.onSuccess(task.gid, task.taskId, task.zipFile)
                    } else {
                        retainImportFailure(task, IOException("Cannot clear completed archive task"))
                    }
                } else retainImportFailure(task, error)
                stopServiceIfIdle()
            }
        }
    }

    private fun retainImportFailure(task: ActiveTask, error: Exception) {
        task.paused = true
        task.speed = 0
        Settings.updateArchiverPhase(task.gid, task.taskId, true, true)
        publishProgress(task, true)
        notifyFailure(task.gid, task.taskId, error)
    }

    @Synchronized
    private fun failDownload(task: ActiveTask, error: Exception) {
        if (!isCurrent(task)) return
        // Network errors also keep a valid partial ZIP available to the existing Resume action.
        task.paused = true
        task.speed = 0
        Settings.updateArchiverPhase(task.gid, task.taskId, false, true)
        publishProgress(task, true)
        notifyFailure(task.gid, task.taskId, error)
        stopServiceIfIdle()
    }

    private fun publishProgress(task: ActiveTask, force: Boolean) {
        if (!isCurrent(task)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - task.lastNotifyAt < NOTIFY_INTERVAL_MS) return
        val elapsed = now - task.lastSpeedAt
        if (elapsed > 0) task.speed = if (task.paused || task.importPending) 0 else task.bytesSinceSpeed * 1000L / elapsed
        task.bytesSinceSpeed = 0
        task.lastSpeedAt = now
        task.lastNotifyAt = now
        for (listener in listeners) listener.onProgress(task.gid, task.taskId, task.downloaded, task.total, task.speed, task.paused)
        val notice = if (task.paused) activeTasks.values.firstOrNull { !it.paused } ?: task else task
        ArchiverDownloadService.updateProgress(appContext, notice.galleryInfo.title, notice.gid,
            notice.galleryInfo.token, notice.downloaded, notice.total, notice.speed,
            if (notice.speed > 0 && notice.total > notice.downloaded) (notice.total - notice.downloaded) * 1000L / notice.speed else -1L,
            notice.paused)
    }

    private fun stopServiceIfIdle() {
        if (activeTasks.values.none { !it.paused }) ArchiverDownloadService.stop(appContext)
    }

    private fun resolveZipFile(fileName: String): File? {
        if (!ArchiverTaskFilePolicy.safeName(fileName)) return null
        val dir = AppConfig.getExternalArchiverDir() ?: AppConfig.getArchiverDir() ?: return null
        return File(dir, "$fileName.zip")
    }

    private fun notifyFailure(gid: Long, taskId: Long, e: Exception) {
        for (listener in listeners) listener.onFailure(gid, taskId, e)
    }

    private fun toastOnMain(resId: Int) {
        mainHandler.post { Toast.makeText(appContext, resId, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "ArchiverDownloader"
        private const val BUFFER_SIZE = 8192
        private const val NOTIFY_INTERVAL_MS = 500L
        @Volatile private var sInstance: ArchiverDownloader? = null

        @JvmStatic @Synchronized
        fun getInstance(context: Context?): ArchiverDownloader? {
            if (sInstance == null && context != null) sInstance = ArchiverDownloader(context.applicationContext)
            return sInstance
        }

        @JvmStatic
        fun resumePending(context: Context) {
            val downloader = getInstance(context) ?: return
            val pending = Settings.getPendingArchiverDownloadIds().toList().mapNotNull { id ->
                Settings.getArchiverDownload(id)?.let { id to it }
            }
            // Snapshot all owners before persisting any migration decision, including system tasks.
            val oldOwners = pending.groupingBy { (id, info) ->
                Settings.getArchiverDownloadFileName(id).ifEmpty {
                    ArchiverDownloadCompleter.createFileName(info.title, info.gid)
                }
            }.eachCount()
            // Commit the whole group's quarantine first, so partial migration + process death
            // cannot make the remaining task mistake the shared ZIP for its own partial download.
            if (!Settings.recordArchiverFileConflicts(ArchiverTaskFilePolicy.conflictingNames(oldOwners))) return
            val conflictedFiles = Settings.getArchiverFileConflicts()
            LegacyArchiverDownloadCompleter.resumePendingDownloads(context)
            for ((id, info) in pending) {
                if (downloader.activeTasks.containsKey(info.gid)) continue
                val url = Settings.getArchiverDownloadUrl(id)
                if (url.isEmpty()) continue
                val legacy = ArchiverDownloadCompleter.createFileName(info.title, info.gid)
                val choice = ArchiverTaskFilePolicy.restore(info.gid, id,
                    Settings.getArchiverDownloadFileName(id), legacy,
                    downloader.resolveZipFile(legacy)?.isFile == true, oldOwners[legacy] ?: 0,
                    conflictedFiles.contains(legacy))
                val restart = choice.restartRequired || Settings.getArchiverRestartRequired(id)
                if (!Settings.restoreArchiverFile(info.gid, id, choice.name, restart)) continue
                val zip = downloader.resolveZipFile(choice.name) ?: continue
                val task = ActiveTask(id, info, url, choice.name, zip)
                task.total = Settings.getArchiverDownloadTotal(id).coerceAtLeast(0L)
                task.downloaded = zip.takeIf { it.isFile }?.length() ?: 0L
                task.importPending = ArchiverTaskFilePolicy.restoreImportPending(
                    Settings.getArchiverImportPending(id), restart, task.total, task.downloaded)
                task.paused = Settings.getArchiverDownloadPaused(id)
                task.restartRequired = restart
                downloader.activeTasks[info.gid] = task
                if (task.paused) downloader.publishProgress(task, true)
                else if (task.importPending) downloader.downloadComplete(task)
                else downloader.startNetwork(task)
            }
        }

        @JvmStatic
        fun legacyImportChanged(context: Context, gid: Long, taskId: Long, zipFile: File?,
                                error: Exception?, complete: Boolean) {
            val downloader = getInstance(context) ?: return
            for (listener in downloader.listeners) {
                if (complete && zipFile != null) listener.onSuccess(gid, taskId, zipFile)
                else if (error != null) listener.onFailure(gid, taskId, error)
                else listener.onProgress(gid, taskId, 0, 0, 0, false)
            }
        }

        private fun resolveTotal(response: Response, offset: Long, contentLength: Long): Long {
            val value = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            return value?.takeIf { it > 0 } ?: if (contentLength >= 0) offset + contentLength else 0L
        }

        private fun deleteZipFile(file: File) {
            if (file.exists() && !file.delete()) Log.w(TAG, "Failed to delete ZIP: ${file.path}")
        }
    }
}
