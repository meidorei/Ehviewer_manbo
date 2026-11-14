package com.hippo.ehviewer.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.download.ArchiverDownloader
import com.hippo.ehviewer.download.LegacyArchiverDownloadCompleter
import com.hippo.lib.yorozuya.FileUtils
import java.io.File
import java.util.Locale

class ArchiverDownloadProgress : LinearLayout {
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var pauseResumeButton: ImageButton

    private var showing = false
    private var paused = false
    private var boundGid = -1L
    private var boundTaskId = -1L
    private var downloadListener: ArchiverDownloader.Listener? = null

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init(context)
    }

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init(context)
    }

    private fun init(context: Context) {
        LayoutInflater.from(context).inflate(R.layout.widget_archiver_progress, this)
        statusText = findViewById(R.id.archiver_downloading)
        progressBar = findViewById(R.id.archiver_progress)
        pauseResumeButton = findViewById(R.id.archiver_pause_resume)
        pauseResumeButton.setOnClickListener { onPauseResumeClicked() }
    }

    fun initThread(galleryInfo: GalleryInfo?) {
        if (galleryInfo == null) {
            return
        }
        val taskId = Settings.getArchiverDownloadId(galleryInfo.gid)
        if (showing && boundGid == galleryInfo.gid && boundTaskId == taskId) return
        if (taskId == -1L) {
            return
        }
        val downloader = ArchiverDownloader.getInstance(context) ?: return
        unbindListener(downloader)
        showing = true
        boundGid = galleryInfo.gid
        boundTaskId = taskId
        visibility = VISIBLE
        updateProgressUi(0, 0, 0, Settings.getArchiverDownloadPaused(taskId))

        val targetGid = galleryInfo.gid
        downloadListener = object : ArchiverDownloader.Listener {
            override fun onProgress(
                gid: Long,
                taskId: Long,
                downloaded: Long,
                total: Long,
                speed: Long,
                paused: Boolean
            ) {
                if (gid != targetGid) {
                    return
                }
                post {
                    if (boundTaskId == taskId && Settings.getArchiverDownloadId(gid) == taskId) {
                        updateProgressUi(downloaded, total, speed, paused)
                    }
                }
            }

            override fun onSuccess(gid: Long, taskId: Long, zipFile: File) {
                if (gid != targetGid) {
                    return
                }
                postDone(taskId)
            }

            override fun onFailure(gid: Long, taskId: Long, e: Exception?) {
                if (gid != targetGid) {
                    return
                }
                post {
                    if (boundTaskId != taskId) return@post
                    if (Settings.getArchiverDownloadId(gid) == taskId) {
                        val progress = downloader.getProgress(gid)
                        updateProgressUi(progress?.downloaded ?: 0, progress?.total ?: 0, 0, true)
                    } else postDone(taskId)
                }
            }

            override fun onCancel(gid: Long, taskId: Long) {
                if (gid != targetGid) {
                    return
                }
                postDone(taskId)
            }
        }
        downloader.addListener(downloadListener!!)

        val progress = downloader.getProgress(galleryInfo.gid)
        if (progress != null) {
            updateProgressUi(progress.downloaded, progress.total, progress.speed, progress.paused)
        }
    }

    private fun onPauseResumeClicked() {
        val downloader = ArchiverDownloader.getInstance(context)
        if (downloader == null || boundGid < 0) {
            return
        }
        if (paused) {
            if (Settings.getArchiverDownloadUrl(boundTaskId).isEmpty()) {
                updateProgressUi(0, 0, 0, false)
                LegacyArchiverDownloadCompleter.getInstance(context)?.retryImport(boundTaskId)
            } else downloader.resume(boundGid)
        } else {
            downloader.pause(boundGid)
        }
    }

    private fun updateProgressUi(downloaded: Long, total: Long, speed: Long, paused: Boolean) {
        this.paused = paused
        val progress = if (total > 0) downloaded * 100.0 / total else 0.0
        val percent = String.format(Locale.getDefault(), "%.2f", progress) + "%"
        val importing = Settings.getArchiverImportPending(boundTaskId)
        val restart = Settings.getArchiverRestartRequired(boundTaskId)
        pauseResumeButton.isEnabled = !importing || paused
        if (importing && !paused) {
            statusText.setText(R.string.archiver_importing)
            pauseResumeButton.contentDescription = context.getString(R.string.archiver_importing)
        } else if (paused) {
            statusText.text = when {
                importing -> context.getString(R.string.archiver_import_failed)
                restart -> context.getString(R.string.archiver_file_conflict)
                else -> context.getString(R.string.archiver_download_paused) + " " + percent
            }
            pauseResumeButton.setImageResource(R.drawable.v_play_x24)
            pauseResumeButton.contentDescription =
                context.getString(if (importing) R.string.archiver_import_retry else R.string.archiver_download_resume)
        } else {
            val safeSpeed = if (speed < 0) 0 else speed
            val speedText = FileUtils.humanReadableByteCount(safeSpeed, false) + "/S"
            statusText.text = context.getString(
                R.string.archiver_downloading,
                "$percent  $speedText"
            )
            pauseResumeButton.setImageResource(R.drawable.v_pause_x24)
            pauseResumeButton.contentDescription =
                context.getString(R.string.archiver_download_pause)
        }
        progressBar.progress = progress.toInt()
    }

    private fun postDone(taskId: Long) {
        post {
            if (boundTaskId != taskId) return@post
            unbindListener(ArchiverDownloader.getInstance(context))
            visibility = GONE
            showing = false
            boundGid = -1L
            boundTaskId = -1L
        }
    }

    private fun unbindListener(downloader: ArchiverDownloader?) {
        val listener = downloadListener
        if (downloader != null && listener != null) {
            downloader.removeListener(listener)
        }
        downloadListener = null
    }

    override fun onDetachedFromWindow() {
        unbindListener(ArchiverDownloader.getInstance(context))
        showing = false
        boundGid = -1L
        boundTaskId = -1L
        super.onDetachedFromWindow()
    }
}
