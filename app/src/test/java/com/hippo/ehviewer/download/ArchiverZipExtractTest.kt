package com.hippo.ehviewer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiverZipExtractTest {

    @Test
    fun unzipLongDirectorySortsOriginalNamesAndSkipsNonImages() {
        val root = Files.createTempDirectory("archiver-unzip").toFile()
        val zipFile = File(root, "archive.zip")
        val longDir = "画".repeat(120)
        assertTrue(longDir.toByteArray(StandardCharsets.UTF_8).size > 255)
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("$longDir/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$longDir/0002.jpg"))
            zip.write("second".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$longDir/note.txt"))
            zip.write("skip".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$longDir/../escaped.png"))
            zip.write("escape".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$longDir/0001.PNG"))
            zip.write("first".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        val dest = File(root, "archiver_1")
        File(dest, "stale.txt").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }

        assertTrue(ArchiverDownloadCompleter.unzipArchive(zipFile, dest))
        assertEquals("first", File(dest, "00000001.png").readText())
        assertEquals("second", File(dest, "00000002.jpg").readText())
        assertFalse(File(dest, "stale.txt").exists())
        assertEquals(2, dest.listFiles()?.size)
        root.deleteRecursively()
    }

    @Test
    fun reversedZipEntriesStillReadInFilenameOrder() {
        val root = Files.createTempDirectory("archiver-order").toFile()
        try {
            val zipFile = File(root, "archive.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                for (name in listOf("002.jpg", "001.jpg")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(name.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
            val dest = File(root, "images")
            assertTrue(ArchiverDownloadCompleter.unzipArchive(zipFile, dest))
            assertEquals("001.jpg", File(dest, "00000001.jpg").readText())
            assertEquals("002.jpg", File(dest, "00000002.jpg").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateBasenamesRemainStableAndLongBasenamesNeverReachFilesystem() {
        val root = Files.createTempDirectory("archiver-duplicates").toFile()
        try {
            val zipFile = File(root, "archive.zip")
            val longName = "画".repeat(120) + ".jpg"
            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                for ((name, content) in listOf("z/001.jpg" to "first duplicate",
                    longName to "long name", "a/001.jpg" to "second duplicate")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
            val dest = File(root, "images")
            assertTrue(ArchiverDownloadCompleter.unzipArchive(zipFile, dest))
            assertEquals("first duplicate", File(dest, "00000001.jpg").readText())
            assertEquals("second duplicate", File(dest, "00000002.jpg").readText())
            assertEquals("long name", File(dest, "00000003.jpg").readText())
            assertEquals(3, dest.listFiles()?.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unzipFailureDeletesTempDir() {
        val root = Files.createTempDirectory("archiver-unzip-fail").toFile()
        val zipFile = File(root, "broken.zip")
        zipFile.writeText("not a zip")
        val dest = File(root, "archiver_2")

        // 本地单元测试里 android.util.Log 会抛出未模拟异常，目录应已先被删除。
        try {
            assertFalse(ArchiverDownloadCompleter.unzipArchive(zipFile, dest))
        } catch (e: RuntimeException) {
            assertTrue(e.message.orEmpty().contains("not mocked"))
        }
        assertFalse(dest.exists())
        root.deleteRecursively()
    }
}
