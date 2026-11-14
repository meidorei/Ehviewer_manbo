package com.hippo.ehviewer.download;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** File identity is independent of mutable, truncated, or duplicate gallery titles. */
public final class ArchiverTaskFilePolicy {
    private ArchiverTaskFilePolicy() {}

    public static String uniqueName(long gid, long taskId) {
        return "archiver_" + gid + "_" + taskId;
    }

    public static final class Choice {
        public final String name;
        public final boolean restartRequired;

        Choice(String name, boolean restartRequired) {
            this.name = name;
            this.restartRequired = restartRequired;
        }
    }

    public static Choice restore(long gid, long taskId, String savedName,
            String legacyName, boolean legacyExists, int legacyOwners) {
        return restore(gid, taskId, savedName, legacyName, legacyExists, legacyOwners, false);
    }

    public static Choice restore(long gid, long taskId, String savedName,
            String legacyName, boolean legacyExists, int legacyOwners, boolean knownConflict) {
        if (knownConflict && (savedName == null || savedName.isEmpty() || savedName.equals(legacyName))) {
            return new Choice(uniqueName(gid, taskId), true);
        }
        if (safeName(savedName)) return new Choice(savedName, false);
        if (legacyExists && safeName(legacyName)) {
            if (legacyOwners == 1) return new Choice(legacyName, false);
            // Its bytes cannot be attributed to either gallery. Leave the old file untouched.
            return new Choice(uniqueName(gid, taskId), true);
        }
        return new Choice(uniqueName(gid, taskId), false);
    }

    /** Persist these names as one set before changing any individual task's file identity. */
    public static Set<String> conflictingNames(Map<String, Integer> owners) {
        Set<String> conflicts = new HashSet<>();
        for (Map.Entry<String, Integer> entry : owners.entrySet()) {
            if (entry.getValue() > 1) conflicts.add(entry.getKey());
        }
        return conflicts;
    }

    public static boolean safeName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf(':') < 0;
    }

    public static boolean owns(long expectedTaskId, long currentTaskId) {
        return expectedTaskId >= 0 && expectedTaskId == currentTaskId;
    }

    public static boolean restoreImportPending(boolean savedPending, boolean restartRequired,
            long savedTotal, long fileLength) {
        return !restartRequired && (savedPending || (savedTotal > 0 && fileLength == savedTotal));
    }
}
