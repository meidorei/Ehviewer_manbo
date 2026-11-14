package com.hippo.ehviewer.download;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ArchiveImportExecutorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private List<File> pages;
    private File zip;
    private File destination;

    @Before public void createSource() throws Exception {
        zip = temporary.newFile("archive.zip");
        Files.write(zip.toPath(), new byte[]{9, 8, 7});
        File first = temporary.newFile("0001.jpg");
        File second = temporary.newFile("0002.jpg");
        Files.write(first.toPath(), new byte[]{1, 2});
        Files.write(second.toPath(), new byte[]{3, 4});
        pages = Arrays.asList(first, second);
        destination = temporary.newFolder("download");
    }

    private void copy(File source, int index) throws IOException {
        Files.copy(source.toPath(), new File(destination, index + ".jpg").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Test public void partialCopyCannotCommitAndRetryKeepsAllPages() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        assertThrows(IOException.class, () -> {
            ArchiveImportExecutor.Prepared prepared = ArchiveImportExecutor.copyAll(pages, () -> true,
                    (source, index) -> {
                        if (index == 1) throw new IOException("No space left");
                        copy(source, index);
                    });
            prepared.commit(() -> true, commits::incrementAndGet);
        });
        assertEquals(0, commits.get());
        assertEquals(1, destination.listFiles().length);
        assertTrue(zip.isFile());
        assertTrue(pages.get(0).isFile());
        assertTrue(pages.get(1).isFile());
        ArchiveImportExecutor.Prepared retried = ArchiveImportExecutor.copyAll(pages, () -> true, this::copy);
        assertEquals(2, retried.pages);
        retried.commit(() -> true, commits::incrementAndGet);
        assertEquals(1, commits.get());
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(new File(destination, "1.jpg").toPath()));
    }

    @Test public void creationAndReplacementFailuresDoNotCommit() {
        for (String failure : new String[]{"create", "replace", "read"}) {
            AtomicBoolean committed = new AtomicBoolean();
            assertThrows(IOException.class, () -> {
                ArchiveImportExecutor.Prepared prepared = ArchiveImportExecutor.copyAll(pages,
                        () -> true, (source, index) -> { throw new IOException(failure); });
                prepared.commit(() -> true, () -> committed.set(true));
            });
            assertFalse(committed.get());
            assertTrue(zip.isFile());
        }
    }

    @Test public void missingSourceIsNotSilentlySkipped() {
        assertTrue(pages.get(1).delete());
        assertThrows(IOException.class, () -> ArchiveImportExecutor.copyAll(pages, () -> true, this::copy));
    }

    @Test public void emptyArchiveCannotBePrepared() {
        assertThrows(IOException.class, () -> ArchiveImportExecutor.copyAll(
                Collections.emptyList(), () -> true, (source, index) -> fail()));
    }

    @Test public void taskReplacementDuringCopyStopsBeforeNextPage() {
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger copied = new AtomicInteger();
        assertThrows(CancellationException.class, () -> ArchiveImportExecutor.copyAll(
                pages, current::get, (source, index) -> {
                    copy(source, index);
                    copied.incrementAndGet();
                    current.set(false);
                }));
        assertEquals(1, copied.get());
        assertTrue(zip.isFile());
    }

    @Test public void staleMainThreadCompletionCannotWriteMetadata() throws Exception {
        ArchiveImportExecutor.Prepared prepared = ArchiveImportExecutor.copyAll(pages, () -> true, this::copy);
        assertThrows(CancellationException.class, () -> prepared.commit(() -> false, () -> fail()));
    }

    @Test public void databaseFailureRemainsRetryableAndDoesNotFinish() throws Exception {
        ArchiveImportExecutor.Prepared prepared = ArchiveImportExecutor.copyAll(pages, () -> true, this::copy);
        assertThrows(IOException.class, () -> prepared.commit(() -> true,
                () -> { throw new IOException("Database full"); }));
        assertTrue(zip.isFile());
        assertTrue(pages.get(0).isFile());
        AtomicInteger commits = new AtomicInteger();
        prepared.commit(() -> true, commits::incrementAndGet);
        assertEquals(1, commits.get());
        assertThrows(IllegalStateException.class, () -> prepared.commit(() -> true, commits::incrementAndGet));
        assertEquals(1, commits.get());
    }
}
