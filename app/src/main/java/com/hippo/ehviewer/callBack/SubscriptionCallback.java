package com.hippo.ehviewer.callBack;

import com.hippo.ehviewer.client.data.userTag.UserTagList;

public interface SubscriptionCallback {
    void onSubscriptionItemClick(String name);

    void onSubscriptionRefresh();

    String getAddTagName(UserTagList userTagList);
}
