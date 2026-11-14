package com.hippo.ehviewer.download;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/** Keeps the normal download scheduler out of a gallery while an archive owns its files. */
public final class ArchiveImportGate {
    private final ConcurrentHashMap<Long, Long> owners = new ConcurrentHashMap<>();

    public boolean acquire(long gid, long taskId) {
        return owners.putIfAbsent(gid, taskId) == null;
    }

    public boolean owns(long gid, long taskId) {
        Long owner = owners.get(gid);
        return owner != null && owner == taskId;
    }

    public boolean release(long gid, long taskId) {
        return owners.remove(gid, taskId);
    }

    /** A successful archive satisfies requests queued during its import; failed commits keep them. */
    public <T> void commitAndRemoveWaiting(List<T> waiting, long completedGid,
            ToLongFunction<T> gid, Runnable commit) {
        commit.run();
        waiting.removeIf(item -> gid.applyAsLong(item) == completedGid);
    }

    public <T> T takeNext(List<T> waiting, ToLongFunction<T> gid) {
        for (Iterator<T> iterator = waiting.iterator(); iterator.hasNext();) {
            T item = iterator.next();
            if (!owners.containsKey(gid.applyAsLong(item))) {
                iterator.remove();
                return item;
            }
        }
        return null;
    }
}
