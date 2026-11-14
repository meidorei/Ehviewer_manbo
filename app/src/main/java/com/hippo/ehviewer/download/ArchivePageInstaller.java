package com.hippo.ehviewer.download;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Replaces one page across every supported extension without destroying its previous copies. */
public final class ArchivePageInstaller {
    private ArchivePageInstaller() {}

    public interface Store {
        boolean exists(String name) throws IOException;
        long length(String name) throws IOException;
        void copy(File source, String name, BooleanSupplier current) throws Exception;
        void createMarker(String name) throws IOException;
        void move(String from, String to) throws IOException;
        void delete(String name) throws IOException;
    }

    /** Returns backup filenames whose optional post-success cleanup should be retried later. */
    public static List<String> install(File source, String target, List<String> pageNames,
                                       String taskPrefix, Store store, BooleanSupplier current)
            throws Exception {
        ArchiveImportExecutor.checkCurrent(current);
        if (!source.isFile()) throw new IOException("Missing archive image: " + source.getName());
        LinkedHashSet<String> names = new LinkedHashSet<>(pageNames);
        names.add(target);
        String staging = taskPrefix + "_" + target + ".part";
        String receipt = taskPrefix + "_" + target + ".installed";
        boolean previousInstalled = store.exists(receipt) && store.exists(target);
        // A prior process may have stopped between moving a previous page and installing its replacement.
        for (String name : names) {
            String backup = backupName(taskPrefix, name);
            if (!store.exists(backup)) continue;
            if (previousInstalled || store.exists(name)) store.delete(backup);
            else store.move(backup, name);
        }
        if (store.exists(receipt)) store.delete(receipt);
        if (store.exists(staging)) store.delete(staging);
        List<String> moved = new ArrayList<>();
        boolean installed = false;
        Exception failure = null;
        try {
            store.copy(source, staging, current);
            if (!store.exists(staging) || store.length(staging) != source.length()) {
                throw new IOException("Incomplete image: " + target);
            }
            ArchiveImportExecutor.checkCurrent(current);
            for (String name : names) {
                ArchiveImportExecutor.checkCurrent(current);
                if (!store.exists(name)) continue;
                store.move(name, backupName(taskPrefix, name));
                moved.add(name);
            }
            ArchiveImportExecutor.checkCurrent(current);
            store.move(staging, target);
            installed = true;
            ArchiveImportExecutor.checkCurrent(current);
            // Distinguishes a successful install with leftover backups from an interrupted move.
            store.createMarker(receipt);
        } catch (Exception error) {
            failure = error;
            try { if (store.exists(receipt)) store.delete(receipt); }
            catch (IOException rollback) { error.addSuppressed(rollback); }
            if (installed) {
                try { store.delete(target); }
                catch (IOException rollback) { error.addSuppressed(rollback); }
            }
            Collections.reverse(moved);
            for (String name : moved) {
                try {
                    // Never overwrite another file if a provider failed to remove the new page.
                    if (store.exists(name)) throw new IOException("Cannot restore previous image: " + name);
                    store.move(backupName(taskPrefix, name), name);
                } catch (IOException rollback) {
                    error.addSuppressed(rollback);
                }
            }
            throw error;
        } finally {
            try {
                if (store.exists(staging)) store.delete(staging);
            } catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
        // The installed page is complete. Failure to remove a hidden backup cannot make it unreadable.
        List<String> retained = new ArrayList<>();
        for (String name : moved) {
            String backup = backupName(taskPrefix, name);
            try { store.delete(backup); }
            catch (IOException cleanup) { retained.add(backup); }
        }
        if (retained.isEmpty()) {
            try { store.delete(receipt); }
            catch (IOException cleanup) { retained.add(receipt); }
        }
        return Collections.unmodifiableList(retained);
    }

    private static String backupName(String prefix, String name) { return prefix + "_" + name + ".old"; }
}
