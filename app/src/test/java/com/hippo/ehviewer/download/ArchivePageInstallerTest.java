package com.hippo.ehviewer.download;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Executes the production replacement/rollback algorithm on real temporary filesystem entries. */
public class ArchivePageInstallerTest {
    private static final String JPG = "00000001.jpg";
    private static final String PNG = "00000001.png";
    private static final String PREFIX = ".archiver_7";
    private static final List<String> NAMES = List.of(JPG, "00000001.jpeg", PNG, "00000001.gif", "00000001.webp");
    private static final byte[] OLD_JPG = {1, 2};
    private static final byte[] OLD_PNG = {3, 4};
    private static final byte[] NEW_PNG = {9, 8, 7};

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File source;
    private LocalStore store;
    private final AtomicBoolean current = new AtomicBoolean(true);

    @Before public void createFiles() throws Exception {
        source = temporary.newFile("source.png");
        Files.write(source.toPath(), NEW_PNG);
        store = new LocalStore(temporary.newFolder("download").toPath());
        Files.write(store.path(JPG), OLD_JPG);
        Files.write(store.path(PNG), OLD_PNG);
    }

    @Test public void crossExtensionInstallRemovesEveryOldReaderCandidate() throws Exception {
        assertTrue(install().isEmpty());
        assertFalse(store.exists(JPG));
        assertArrayEquals(NEW_PNG, Files.readAllBytes(store.path(PNG)));
        try (java.util.stream.Stream<Path> files = Files.list(store.root)) {
            assertEquals(List.of(PNG), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertArrayEquals(NEW_PNG, Files.readAllBytes(source.toPath()));
    }

    @Test public void partialWriteDoesNotTouchPreviousImages() {
        store.failCopy = true;
        assertThrows(IOException.class, this::install);
        assertOldPages();
        assertFalse(Files.exists(store.path(PREFIX + "_" + PNG + ".part")));
    }

    @Test public void shortWriteCannotBeginReplacement() {
        store.shortCopy = true;
        assertThrows(IOException.class, this::install);
        assertOldPages();
    }

    @Test public void failureMovingSecondOldExtensionRollsBackTheFirst() {
        store.failBackup = PNG;
        assertThrows(IOException.class, this::install);
        assertOldPages();
        assertNoBackups();
    }

    @Test public void installationFailureRestoresAllOldExtensions() {
        store.failInstall = true;
        assertThrows(IOException.class, this::install);
        assertOldPages();
        assertNoBackups();
    }

    @Test public void cancellationImmediatelyAfterInstallationRestoresOldImages() {
        store.cancelAfterInstall = current;
        assertThrows(CancellationException.class, this::install);
        assertOldPages();
        assertNoBackups();
    }

    @Test public void receiptFailureAlsoRestoresOldImages() {
        store.failReceipt = true;
        assertThrows(IOException.class, this::install);
        assertOldPages();
        assertNoBackups();
    }

    @Test public void leftoverBackupNeverBecomesAReaderPageOnFailedRetry() throws Exception {
        store.failBackupDelete = true;
        List<String> retained = install();
        assertEquals(2, retained.size());
        assertFalse(store.exists(JPG));
        assertArrayEquals(NEW_PNG, Files.readAllBytes(store.path(PNG)));
        assertTrue(store.exists(PREFIX + "_" + PNG + ".installed"));
        store.failBackupDelete = false;
        store.failCopy = true;
        assertThrows(IOException.class, this::install);
        assertFalse(store.exists(JPG));
        assertArrayEquals(NEW_PNG, Files.readAllBytes(store.path(PNG)));
        assertNoBackups();
    }

    @Test public void interruptedReplacementRestoresPreviousPageBeforeReadingSource() throws Exception {
        Files.move(store.path(JPG), store.path(PREFIX + "_" + JPG + ".old"));
        Files.write(store.path(PREFIX + "_" + PNG + ".part"), new byte[]{9});
        store.failCopy = true;
        assertThrows(IOException.class, this::install);
        assertOldPages();
        assertNoBackups();
    }

    @Test public void failedRollbackRetainsBackupAndNextAttemptCanRecoverIt() throws Exception {
        store.failInstall = true;
        store.failRestore = JPG;
        IOException failure = assertThrows(IOException.class, this::install);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(store.exists(PREFIX + "_" + JPG + ".old"));
        assertArrayEquals(OLD_JPG, Files.readAllBytes(store.path(PREFIX + "_" + JPG + ".old")));
        store.failInstall = false;
        store.failRestore = null;
        assertTrue(install().isEmpty());
        assertFalse(store.exists(JPG));
        assertArrayEquals(NEW_PNG, Files.readAllBytes(store.path(PNG)));
    }

    private List<String> install() throws Exception {
        return ArchivePageInstaller.install(source, PNG, NAMES, PREFIX, store, current::get);
    }

    private void assertOldPages() {
        try {
            assertArrayEquals(OLD_JPG, Files.readAllBytes(store.path(JPG)));
            assertArrayEquals(OLD_PNG, Files.readAllBytes(store.path(PNG)));
            assertArrayEquals(NEW_PNG, Files.readAllBytes(source.toPath()));
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private void assertNoBackups() {
        try {
            for (String name : NAMES) assertFalse(store.exists(PREFIX + "_" + name + ".old"));
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static final class LocalStore implements ArchivePageInstaller.Store {
        final Path root;
        boolean failCopy, shortCopy, failInstall, failReceipt, failBackupDelete;
        String failBackup, failRestore;
        AtomicBoolean cancelAfterInstall;

        LocalStore(Path root) { this.root = root; }
        Path path(String name) { return root.resolve(name); }
        @Override public boolean exists(String name) throws IOException { return Files.exists(path(name)); }
        @Override public long length(String name) throws IOException { return Files.size(path(name)); }
        @Override public void copy(File from, String to, BooleanSupplier current) throws Exception {
            ArchiveImportExecutor.checkCurrent(current);
            if (failCopy || shortCopy) {
                Files.write(path(to), new byte[]{9});
                if (failCopy) throw new IOException("injected partial copy");
            } else Files.copy(from.toPath(), path(to));
        }
        @Override public void move(String from, String to) throws IOException {
            if (from.equals(failBackup) && to.endsWith(".old")) throw new IOException("injected backup failure");
            if (from.endsWith(".part") && failInstall) throw new IOException("injected install failure");
            if (from.endsWith(".old") && to.equals(failRestore)) throw new IOException("injected restore failure");
            Files.move(path(from), path(to));
            if (from.endsWith(".part") && cancelAfterInstall != null) cancelAfterInstall.set(false);
        }
        @Override public void createMarker(String name) throws IOException {
            Files.createFile(path(name));
            if (failReceipt) throw new IOException("injected receipt failure");
        }
        @Override public void delete(String name) throws IOException {
            if (name.endsWith(".old") && failBackupDelete) throw new IOException("injected cleanup failure");
            Files.deleteIfExists(path(name));
        }
    }
}
