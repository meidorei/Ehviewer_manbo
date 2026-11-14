package com.hippo.ehviewer.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.lib.yorozuya.FileUtils as YorozuyaFileUtils
import com.hippo.lib.yorozuya.StringUtils
import com.hippo.lib.yorozuya.Utilities
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 归档 zip 下载完成后的解压与导入处理。
 */
class ArchiverDownloadCompleter private constructor(appContext: Context) {

    private val appContext = appContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handlingIds = HashSet<Long>()
    // Serialize file replacement, including an old task being superseded for the same GID.
    private val imports = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun interface Callback {
        fun onComplete(error: Exception?)
    }

    fun importDownloadedZip(zipFile: File?, galleryInfo: GalleryInfo, taskId: Long,
                            callback: Callback) {
        synchronized(handlingIds) {
            if (!handlingIds.add(taskId)) return
        }
        var manager: DownloadManager? = null
        var ownsFiles = false
        var completed = false
        fun complete(error: Exception?) {
            if (completed) return
            completed = true
            try {
                callback.onComplete(error)
            } finally {
                synchronized(handlingIds) { handlingIds.remove(taskId) }
                if (ownsFiles) manager?.endArchiveImport(galleryInfo.gid, taskId)
            }
        }
        val current = java.util.function.BooleanSupplier {
            ArchiverTaskFilePolicy.owns(taskId, Settings.getArchiverDownloadId(galleryInfo.gid)) &&
                (manager == null || manager!!.ownsArchiveImport(galleryInfo.gid, taskId))
        }
        // DownloadManager belongs to the main thread. Reject a competing ordinary downloader.
        mainHandler.post {
            try {
                ArchiveImportExecutor.checkCurrent(current)
                manager = EhApplication.getDownloadManager(appContext)
                ownsFiles = manager!!.beginArchiveImport(galleryInfo.gid, taskId)
                if (!ownsFiles) throw java.io.IOException("Gallery is downloading or already importing")
                imports.execute {
                    try {
                        ArchiveImportExecutor.checkCurrent(current)
                        if (zipFile == null || !zipFile.isFile) throw java.io.IOException("Missing archive ZIP")
                        val tempRoot = AppConfig.getExternalTempDir()
                            ?: throw java.io.IOException("Cannot resolve temporary directory")
                        val temp = File(tempRoot, ArchiverTaskFilePolicy.uniqueName(galleryInfo.gid, taskId))
                        if (!unzipArchive(zipFile, temp)) throw java.io.IOException("Cannot extract archive ZIP")
                        val pictures = collectImageFiles(temp).sortedBy { it.name }
                        val spiderDen = SpiderDen(galleryInfo)
                        spiderDen.setMode(SpiderQueen.MODE_DOWNLOAD)
                        if (!spiderDen.prepareDownloadStorage()) throw java.io.IOException("Cannot prepare download directory")
                        val directory = spiderDen.getDownloadDir()
                            ?: throw java.io.IOException("Missing download directory")
                        val prepared = ArchiveImportExecutor.copyAll(pictures, current) { source, index ->
                            copyPage(source, index, directory, taskId, current)
                        }
                        mainHandler.post {
                            try {
                                prepared.commit(current) {
                                    manager!!.completeArchiveImport(galleryInfo, taskId,
                                            appContext.getString(R.string.download_label_archiver))
                                }
                                // The database commit is part of success. Failure retains the ZIP and source pages.
                                imports.execute { YorozuyaFileUtils.delete(temp) }
                                Toast.makeText(appContext, appContext.getString(
                                    R.string.stat_download_done_line_succeeded, galleryInfo.title),
                                    Toast.LENGTH_LONG).show()
                                complete(null)
                            } catch (e: Exception) {
                                complete(e)
                            }
                        }
                    } catch (e: Exception) {
                        // Do not remove recoverable source data after partial copy or a database failure.
                        mainHandler.post { complete(e) }
                    }
                }
            } catch (e: Exception) {
                complete(e)
            }
        }
    }

    private fun copyPage(source: File, index: Int, directory: UniFile, taskId: Long,
                         current: java.util.function.BooleanSupplier) {
        val name = SpiderDen.generateImageFilename(index, getImageExtension(source.name))
        val pageNames = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS.map {
            SpiderDen.generateImageFilename(index, it)
        }
        val store = object : ArchivePageInstaller.Store {
            override fun exists(filename: String) = directory.findFile(filename)?.exists() == true
            override fun length(filename: String) = directory.findFile(filename)?.length() ?: -1L
            override fun copy(file: File, filename: String, owned: java.util.function.BooleanSupplier) {
                val staging = directory.createFile(filename)
                    ?: throw java.io.IOException("Cannot create staging image: $filename")
                FileInputStream(file).use { input ->
                    staging.openOutputStream().use { output ->
                        val buffer = ByteArray(EXTRACT_BUFFER_SIZE)
                        while (true) {
                            ArchiveImportExecutor.checkCurrent(owned)
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            }
            override fun move(from: String, to: String) {
                val file = directory.findFile(from)
                    ?: throw java.io.IOException("Missing image to rename: $from")
                if (directory.findFile(to) != null || !file.renameTo(to)) {
                    throw java.io.IOException("Cannot rename image: $from -> $to")
                }
            }
            override fun createMarker(filename: String) {
                if (directory.createFile(filename) == null) {
                    throw java.io.IOException("Cannot record installed image: $filename")
                }
            }
            override fun delete(filename: String) {
                val file = directory.findFile(filename) ?: return
                if (!file.delete()) throw java.io.IOException("Cannot delete image: $filename")
            }
        }
        val retained = ArchivePageInstaller.install(source, name, pageNames,
            ".archiver_$taskId", store, current)
        if (retained.isNotEmpty()) {
            Log.w(TAG, "Retained archive page backups: ${retained.joinToString()}")
        }
    }

    companion object {
        private const val TAG = "ArchiverDownloadCompleter"
        private const val EXTRACT_BUFFER_SIZE = 8192

        private val MAX_ARCHIVER_BASENAME_UTF8_BYTES =
            255 - ".zip".toByteArray(StandardCharsets.UTF_8).size

        @Volatile
        private var sInstance: ArchiverDownloadCompleter? = null

        @JvmStatic @Synchronized
        fun getInstance(context: Context?): ArchiverDownloadCompleter? {
            if (sInstance == null && context != null) {
                sInstance = ArchiverDownloadCompleter(context.applicationContext)
            }
            return sInstance
        }

        @JvmStatic
        fun createFileName(name: String?, gid: Long): String {
            var result = name?.let { YorozuyaFileUtils.sanitizeFilename(it) } ?: ""
            result = truncateUtf8ToMaxBytes(result, MAX_ARCHIVER_BASENAME_UTF8_BYTES)
            if (result.isEmpty()) {
                result = if (gid > 0) "archiver_$gid" else "archiver"
            }
            return result
        }

        /**
         * 把档案解压到短文件名，避免 zip 内的长标题目录超过单级路径 255 字节。
         * 图片按原始文件名稳定排序后写成 %08d + 扩展名，非图片、目录和包含 ".." 的条目跳过。
         */
        @JvmStatic
        internal fun unzipArchive(zipFile: File, destDir: File): Boolean {
            if (destDir.exists() && !YorozuyaFileUtils.delete(destDir)) {
                Log.e(TAG, "Failed to delete leftover temp dir: ${destDir.path}")
                return false
            }
            if (!destDir.mkdirs() && !destDir.isDirectory) {
                Log.e(TAG, "Failed to create temp dir: ${destDir.path}")
                return false
            }
            var imageIndex = 0
            val images = ArrayList<Triple<String, Int, File>>()
            try {
                ZipInputStream(FileInputStream(zipFile)).use { inZip ->
                    while (true) {
                        val entry = inZip.nextEntry ?: break
                        val name = entry.name ?: continue
                        if (entry.isDirectory || name.contains("..")) {
                            continue
                        }
                        val baseName = entryBaseName(name)
                        if (baseName.isEmpty() || !isSupportedImageName(baseName)) {
                            continue
                        }
                        // Keep original names only in memory: long or nested ZIP names never become paths.
                        val outFile = File(destDir,
                            String.format(Locale.US, "entry_%08d.part", imageIndex + 1))
                        try {
                            FileOutputStream(outFile).use { output ->
                                val buffer = ByteArray(EXTRACT_BUFFER_SIZE)
                                while (true) {
                                    val read = inZip.read(buffer)
                                    if (read == -1) {
                                        break
                                    }
                                    output.write(buffer, 0, read)
                                }
                                output.flush()
                            }
                        } catch (e: Exception) {
                            deleteTempDir(destDir)
                            Log.e(TAG, "Failed to extract archive entry to: ${outFile.path}", e)
                            return false
                        }
                        images.add(Triple(baseName, imageIndex, outFile))
                        imageIndex++
                    }
                }
            } catch (e: Exception) {
                deleteTempDir(destDir)
                Log.e(TAG, "Failed to unzip archive: ${zipFile.path}", e)
                return false
            }
            if (imageIndex == 0) {
                deleteTempDir(destDir)
                Log.e(TAG, "No image files found in archive: ${zipFile.path}")
                return false
            }
            // Legacy archive import orders pages by their original basename, not ZIP insertion order.
            // The entry ordinal makes equal basenames deterministic without overwriting either image.
            val ordered = images.sortedWith(compareBy<Triple<String, Int, File>> { it.first }.thenBy { it.second })
            for ((index, item) in ordered.withIndex()) {
                val target = File(destDir,
                    String.format(Locale.US, "%08d%s", index + 1, getImageExtension(item.first)))
                if (!item.third.renameTo(target)) {
                    deleteTempDir(destDir)
                    Log.e(TAG, "Failed to order extracted image: ${item.third.path}")
                    return false
                }
            }
            return true
        }

        private fun deleteTempDir(destDir: File) {
            if (destDir.exists() && !YorozuyaFileUtils.delete(destDir)) {
                Log.w(TAG, "Failed to delete temp dir: ${destDir.path}")
            }
        }

        private fun entryBaseName(name: String): String {
            val slash = maxOf(name.lastIndexOf('/'), name.lastIndexOf('\\'))
            return if (slash >= 0) name.substring(slash + 1) else name
        }

        private fun isSupportedImageName(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return StringUtils.endsWith(lower, GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS)
        }

        private fun resolveImportRoot(dir: File): File? {
            if (!dir.isDirectory) {
                return null
            }
            val children = dir.listFiles() ?: return null
            var hasImageAtLevel = false
            var onlySubdir: File? = null
            var subdirCount = 0
            for (child in children) {
                if (child.isFile && isImageFile(child)) {
                    hasImageAtLevel = true
                } else if (child.isDirectory) {
                    onlySubdir = child
                    subdirCount++
                }
            }
            if (hasImageAtLevel) {
                return dir
            }
            if (subdirCount == 1 && onlySubdir != null) {
                return resolveImportRoot(onlySubdir)
            }
            return dir
        }

        private fun collectImageFiles(root: File): MutableList<File> {
            val images = ArrayList<File>()
            collectImageFilesRecursive(root, images)
            return images
        }

        private fun collectImageFilesRecursive(dir: File, images: MutableList<File>) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    collectImageFilesRecursive(child, images)
                } else if (isImageFile(child)) {
                    images.add(child)
                }
            }
        }

        private fun isImageFile(file: File): Boolean {
            if (!file.isFile) {
                return false
            }
            val lower = file.name.lowercase(Locale.ROOT)
            return StringUtils.endsWith(lower, GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS)
        }

        private fun getImageExtension(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            val extension = if (dot >= 0) fileName.substring(dot).lowercase(Locale.ROOT) else ""
            return if (Utilities.contain(GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS, extension)) {
                extension
            } else {
                GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0]
            }
        }

        private fun truncateUtf8ToMaxBytes(s: String?, maxBytes: Int): String {
            if (s.isNullOrEmpty() || maxBytes <= 0) {
                return s ?: ""
            }
            var byteCount = 0
            var cutCharEnd = 0
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                val charUtf8Bytes: Int
                val charWidth: Int
                when {
                    ch.code <= 0x7F -> {
                        charUtf8Bytes = 1
                        charWidth = 1
                    }
                    ch.code <= 0x7FF -> {
                        charUtf8Bytes = 2
                        charWidth = 1
                    }
                    Character.isHighSurrogate(ch) -> {
                        charUtf8Bytes = 4
                        charWidth = 2
                        if (i + 1 >= s.length) {
                            break
                        }
                    }
                    else -> {
                        charUtf8Bytes = 3
                        charWidth = 1
                    }
                }
                if (byteCount + charUtf8Bytes > maxBytes) {
                    break
                }
                byteCount += charUtf8Bytes
                i += charWidth
                cutCharEnd = i
            }
            return if (cutCharEnd < s.length) s.substring(0, cutCharEnd) else s
        }
    }
}
