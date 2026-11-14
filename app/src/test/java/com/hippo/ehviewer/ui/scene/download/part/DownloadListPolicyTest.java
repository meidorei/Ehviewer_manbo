package com.hippo.ehviewer.ui.scene.download.part;

import static org.junit.Assert.*;
import com.hippo.ehviewer.dao.DownloadInfo;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class DownloadListPolicyTest {
    private DownloadInfo info(long gid) {
        return new DownloadInfo(gid);
    }

    @Test public void fixedSizeFilteredListRetainsOrderAndDoesNotMutateSource() {
        DownloadInfo first = info(3), second = info(1), third = info(2);
        List<DownloadInfo> source = Arrays.asList(first, second, third);
        List<DownloadInfo> retained = DownloadListPolicy.retainAvailable(source, gid -> gid != 1);
        assertEquals(Arrays.asList(first, third), retained);
        assertEquals(3, source.size());
        retained.clear();
        assertEquals(3, source.size());
    }

    @Test public void delayedSearchResultCannotRestoreDeletedGids() {
        List<DownloadInfo> source = Arrays.asList(info(3), info(1), info(2));
        Set<Long> available = new HashSet<>(Arrays.asList(1L, 2L, 3L));
        available.remove(1L);
        available.remove(3L);
        List<DownloadInfo> retained = DownloadListPolicy.retainAvailable(source, available::contains);
        assertEquals(1, retained.size());
        assertEquals(2, retained.get(0).gid);
    }

    @Test public void nullAndDeletedOnlyResultsAreEmpty() {
        assertTrue(DownloadListPolicy.retainAvailable(null, gid -> true).isEmpty());
        assertTrue(DownloadListPolicy.retainAvailable(
                Arrays.asList(null, info(1)), gid -> false).isEmpty());
    }
}
