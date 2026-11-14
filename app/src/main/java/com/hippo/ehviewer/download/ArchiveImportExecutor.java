package com.hippo.ehviewer.download;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Shared by both archive downloaders; a prepared import is issued only after every page copies. */
public final class ArchiveImportExecutor {
    private ArchiveImportExecutor() {}

    public interface Copier {
        void copy(File source, int index) throws Exception;
    }

    public interface Committer {
        void commit() throws Exception;
    }

    public static final class Prepared {
        public final int pages;
        private boolean committed;

        private Prepared(int pages) { this.pages = pages; }

        public synchronized void commit(BooleanSupplier current, Committer committer)
                throws Exception {
            checkCurrent(current);
            if (committed) throw new IllegalStateException("Archive already committed");
            committer.commit();
            committed = true;
        }
    }

    public static Prepared copyAll(List<File> pages, BooleanSupplier current, Copier copier)
            throws Exception {
        checkCurrent(current);
        if (pages.isEmpty()) throw new IOException("Archive has no images");
        for (int i = 0; i < pages.size(); i++) {
            checkCurrent(current);
            File source = pages.get(i);
            if (!source.isFile()) throw new IOException("Missing archive image: " + source.getName());
            copier.copy(source, i);
        }
        checkCurrent(current);
        return new Prepared(pages.size());
    }

    public static void checkCurrent(BooleanSupplier current) {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) {
            throw new CancellationException("Archive task is no longer current");
        }
    }
}
