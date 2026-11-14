package com.hippo.ehviewer.download;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class ArchiverTaskFilePolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void equalOrTruncatedTitlesCannotShareNewTaskFiles() {
        String first = ArchiverTaskFilePolicy.uniqueName(10, 100);
        assertNotEquals(first, ArchiverTaskFilePolicy.uniqueName(11, 100));
        assertNotEquals(first, ArchiverTaskFilePolicy.uniqueName(10, 101));
        assertTrue((ArchiverTaskFilePolicy.uniqueName(Long.MAX_VALUE, Long.MAX_VALUE) + ".zip")
                .getBytes(StandardCharsets.UTF_8).length < 255);
    }

    @Test public void uniqueLegacyPausedTaskKeepsItsByteOffsetAndFile() throws Exception {
        File legacy = temporary.newFile("same-title.zip");
        Files.write(legacy.toPath(), new byte[]{1, 2, 3});
        ArchiverTaskFilePolicy.Choice result = ArchiverTaskFilePolicy.restore(
                10, 100, "", "same-title", legacy.isFile(), 1);
        assertEquals("same-title", result.name);
        assertFalse(result.restartRequired);
        assertEquals(3, new File(temporary.getRoot(), result.name + ".zip").length());
    }

    @Test public void collidingLegacyTasksRestartSeparatelyWithoutChangingSharedZip() throws Exception {
        File legacy = temporary.newFile("same-title.zip");
        byte[] original = {1, 2, 3, 4};
        Files.write(legacy.toPath(), original);
        ArchiverTaskFilePolicy.Choice first = ArchiverTaskFilePolicy.restore(10, 100, "", "same-title", true, 2);
        ArchiverTaskFilePolicy.Choice second = ArchiverTaskFilePolicy.restore(11, 101, "", "same-title", true, 2);
        assertTrue(first.restartRequired);
        assertTrue(second.restartRequired);
        assertNotEquals(first.name, second.name);
        Files.write(new File(temporary.getRoot(), first.name + ".zip").toPath(), new byte[]{5});
        assertArrayEquals(original, Files.readAllBytes(legacy.toPath()));
    }

    @Test public void savedIdentitySurvivesChangedTitleAndOtherLegacyOwners() {
        ArchiverTaskFilePolicy.Choice result = ArchiverTaskFilePolicy.restore(
                10, 100, "archiver_10_100", "a completely different title", true, 2);
        assertEquals("archiver_10_100", result.name);
        assertFalse(result.restartRequired);
    }

    @Test public void missingLegacyFileUsesNewIdentity() {
        assertEquals("archiver_10_100", ArchiverTaskFilePolicy.restore(
                10, 100, "", "same-title", false, 1).name);
    }

    @Test public void storedPathsCannotEscapeArchiveDirectory() {
        for (String path : new String[]{"../outside", "..\\outside", "C:outside", ".", ".."}) {
            assertFalse(ArchiverTaskFilePolicy.safeName(path));
            assertEquals("archiver_10_100", ArchiverTaskFilePolicy.restore(
                    10, 100, path, "missing", false, 0).name);
        }
    }

    @Test public void replacementAndCancelledTasksRejectOldCallbacks() {
        assertTrue(ArchiverTaskFilePolicy.owns(100, 100));
        assertFalse(ArchiverTaskFilePolicy.owns(100, 101));
        assertFalse(ArchiverTaskFilePolicy.owns(100, -1));
        assertFalse(ArchiverTaskFilePolicy.owns(-1, -1));
    }

    @Test public void completedUnknownLengthZipRestoresAsLocalImport() {
        assertTrue(ArchiverTaskFilePolicy.restoreImportPending(true, false, 0, 128));
        assertTrue(ArchiverTaskFilePolicy.restoreImportPending(false, false, 128, 128));
        assertFalse(ArchiverTaskFilePolicy.restoreImportPending(false, false, 128, 127));
        assertFalse(ArchiverTaskFilePolicy.restoreImportPending(false, false, 128, 129));
        assertFalse(ArchiverTaskFilePolicy.restoreImportPending(true, true, 128, 128));
    }

    @Test public void interruptedConflictMigrationCannotMakeRemainingTaskTrustSharedBytes() throws Exception {
        File legacy = temporary.newFile("same-title.zip");
        Files.write(legacy.toPath(), new byte[]{1, 2, 3});
        Map<String, Integer> owners = new HashMap<>();
        owners.put("same-title", 2);
        // Persist the complete conflict set BEFORE migrating the first task.
        Set<String> persistedConflicts = ArchiverTaskFilePolicy.conflictingNames(owners);
        ArchiverTaskFilePolicy.Choice migrated = ArchiverTaskFilePolicy.restore(
                10, 100, "", "same-title", true, 2, persistedConflicts.contains("same-title"));
        assertTrue(migrated.restartRequired);
        // Process death: A is now named uniquely; B appears to be the sole owner of the old path.
        ArchiverTaskFilePolicy.Choice afterRestart = ArchiverTaskFilePolicy.restore(
                11, 101, "", "same-title", true, 1, persistedConflicts.contains("same-title"));
        assertTrue(afterRestart.restartRequired);
        assertEquals("archiver_11_101", afterRestart.name);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(legacy.toPath()));
        // Previously selected old basenames also cannot regain trust after a conflict was recorded.
        assertTrue(ArchiverTaskFilePolicy.restore(11, 101, "same-title", "same-title",
                true, 1, true).restartRequired);
    }
}
