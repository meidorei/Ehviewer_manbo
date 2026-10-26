package com.hippo.ehviewer.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.dao.DownloadInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DownloadLocalImportFilterTest {

    @Test
    public void includesArchivesAndAlbumsWithoutDependingOnCategoryOrState() {
        DownloadInfo archive = info(3, "content://downloads/archive.zip");
        archive.category = 2;
        archive.state = DownloadInfo.STATE_FINISH;
        DownloadInfo album = info(1, "local-album:content://pictures/album");
        album.category = 8;
        album.state = DownloadInfo.STATE_NONE;
        DownloadInfo download = info(2, null);
        DownloadInfo emptyMarker = info(4, "");

        assertEquals(Arrays.asList(archive, album),
                DownloadListInfosExecutor.filterLocalImport(
                        Arrays.asList(download, archive, emptyMarker, album)));
    }

    @Test
    public void preservesSourceOrderAndReturnsIndependentList() {
        DownloadInfo album = info(30, "local-album:content://pictures/album");
        DownloadInfo download = info(20, null);
        DownloadInfo archive = info(10, "content://downloads/archive.zip");
        List<DownloadInfo> source = Collections.unmodifiableList(
                Arrays.asList(album, download, archive));

        List<DownloadInfo> filtered = DownloadListInfosExecutor.filterLocalImport(source);

        assertEquals(Arrays.asList(album, archive), filtered);
        assertEquals(Arrays.asList(album, download, archive), source);
        filtered.clear();
        assertEquals(Arrays.asList(album, download, archive), source);
        assertEquals("local-album:content://pictures/album", album.archiveUri);
        assertEquals("content://downloads/archive.zip", archive.archiveUri);
    }

    @Test
    public void missingEmptyAndOrdinaryDownloadListsReturnEmptyResults() {
        assertTrue(DownloadListInfosExecutor.filterLocalImport(null).isEmpty());
        assertTrue(DownloadListInfosExecutor.filterLocalImport(Collections.emptyList()).isEmpty());
        assertTrue(DownloadListInfosExecutor.filterLocalImport(
                Arrays.asList(info(1, null), info(2, ""))).isEmpty());
    }

    private static DownloadInfo info(long gid, String archiveUri) {
        DownloadInfo info = new DownloadInfo(gid);
        info.archiveUri = archiveUri;
        return info;
    }
}
