package com.hippo.ehviewer.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.EhDB;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class DownloadDirnameImportPolicyTest {
    @Test public void backupCannotReplaceAnExistingLocalDirectory() {
        assertFalse(DownloadDirnameImportPolicy.shouldImport("local-directory", "backup-directory"));
        assertFalse(DownloadDirnameImportPolicy.shouldImport("local-directory", "local-directory"));
        assertFalse(DownloadDirnameImportPolicy.shouldImport("local-directory", null));
        assertFalse(DownloadDirnameImportPolicy.shouldImport("local-directory", ""));
    }

    @Test public void missingAndEmptyLocalMappingsAcceptTheBackup() {
        assertTrue(DownloadDirnameImportPolicy.shouldImport(null, "backup-directory"));
        assertTrue(DownloadDirnameImportPolicy.shouldImport("", "backup-directory"));
    }

    @Test public void emptyBackupCannotCreateOrEraseAMapping() {
        for (String current : new String[]{null, "", "local-directory"}) {
            assertFalse(DownloadDirnameImportPolicy.shouldImport(current, null));
            assertFalse(DownloadDirnameImportPolicy.shouldImport(current, ""));
        }
    }

    @Test public void nonEmptyLocalNamesArePreservedWithoutFilesystemAssumptions() {
        assertFalse(DownloadDirnameImportPolicy.shouldImport(" ", "backup-directory"));
        assertFalse(DownloadDirnameImportPolicy.shouldImport("missing-on-this-device", "backup-directory"));
        assertFalse(DownloadDirnameImportPolicy.shouldImport("本机作品目录", "backup-directory"));
    }

    @Test public void importCheckAndWriteShareTheExistingDirectoryWriteLock() throws Exception {
        Method importer = EhDB.class.getDeclaredMethod("importDownloadDirname", long.class, String.class);
        Method writer = EhDB.class.getDeclaredMethod("putDownloadDirname", long.class, String.class);
        assertTrue(Modifier.isStatic(importer.getModifiers()));
        assertTrue(Modifier.isSynchronized(importer.getModifiers()));
        assertTrue(Modifier.isStatic(writer.getModifiers()));
        assertTrue(Modifier.isSynchronized(writer.getModifiers()));
    }
}
