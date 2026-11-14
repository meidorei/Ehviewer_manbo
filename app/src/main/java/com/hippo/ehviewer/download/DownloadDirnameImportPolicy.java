package com.hippo.ehviewer.download;

/** A backup may fill a missing mapping, but cannot replace a directory owned by this device. */
public final class DownloadDirnameImportPolicy {
    private DownloadDirnameImportPolicy() {}

    public static boolean shouldImport(String current, String incoming) {
        return (current == null || current.isEmpty())
                && incoming != null && !incoming.isEmpty();
    }
}
