package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.userTag.UserTagList;

import java.util.ArrayList;
import java.util.List;

public final class SubscriptionScanResult {
    public UserTagList tags;
    public final List<GalleryInfo> galleries = new ArrayList<>();
    public int pagesScanned;
    public boolean reachedBoundary;
}
