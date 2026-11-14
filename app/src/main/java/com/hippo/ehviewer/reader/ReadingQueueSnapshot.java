package com.hippo.ehviewer.reader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable queue membership and progress used by list-card binding. */
public final class ReadingQueueSnapshot {
    public static final class Entry {
        public final long gid;
        public final int currentPage;
        public final int totalPages;

        public Entry(long gid, int currentPage, int totalPages) {
            this.gid = gid;
            this.currentPage = currentPage;
            this.totalPages = totalPages;
        }
    }

    public static final class Progress {
        public final int currentPage;
        public final int totalPages;

        private Progress(int currentPage, int totalPages) {
            this.currentPage = currentPage;
            this.totalPages = totalPages;
        }

        public boolean isKnown() {
            return currentPage >= 1 && totalPages >= currentPage;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Progress)) return false;
            Progress other = (Progress) object;
            return currentPage == other.currentPage && totalPages == other.totalPages;
        }

        @Override
        public int hashCode() {
            return Objects.hash(currentPage, totalPages);
        }
    }

    private static final ReadingQueueSnapshot EMPTY =
            new ReadingQueueSnapshot(Collections.emptyMap());

    private final Map<Long, Progress> entries;

    private ReadingQueueSnapshot(Map<Long, Progress> entries) {
        this.entries = entries;
    }

    public static ReadingQueueSnapshot empty() {
        return EMPTY;
    }

    public static ReadingQueueSnapshot from(List<Entry> source) {
        if (source.isEmpty()) return EMPTY;
        Map<Long, Progress> entries = new LinkedHashMap<>();
        for (Entry entry : source) {
            entries.put(entry.gid, normalizeProgress(entry.currentPage, entry.totalPages));
        }
        return new ReadingQueueSnapshot(Collections.unmodifiableMap(entries));
    }

    static Progress normalizeProgress(int currentPage, int totalPages) {
        int total = Math.max(0, totalPages);
        int current = total == 0 ? 0 : Math.max(0, Math.min(currentPage, total));
        return new Progress(current, total);
    }

    public Progress get(long gid) {
        return entries.get(gid);
    }

    public boolean contains(long gid) {
        return entries.containsKey(gid);
    }

    public int size() {
        return entries.size();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof ReadingQueueSnapshot)) return false;
        return entries.equals(((ReadingQueueSnapshot) object).entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }
}
