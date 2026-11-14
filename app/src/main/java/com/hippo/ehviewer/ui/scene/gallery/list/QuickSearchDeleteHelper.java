package com.hippo.ehviewer.ui.scene.gallery.list;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.subscription.LocalFollowRepository;
import com.hippo.ehviewer.subscription.SubscriptionRepository;

/** Keeps every quick-search deletion entry point on the same cleanup path. */
final class QuickSearchDeleteHelper {
    private QuickSearchDeleteHelper() {}

    static void delete(QuickSearch quickSearch) {
        if (quickSearch == null) return;
        Long id = quickSearch.getId();
        EhDB.deleteQuickSearch(quickSearch);
        if (id == null) return;

        SubscriptionRepository repository = SubscriptionRepository.getInstance();
        repository.execute(() -> {
            repository.deleteQuickSearchCheckpoints(repository.getAccountKey(), id);
            LocalFollowRepository.getInstance().deleteBookmarkState(id);
        });
    }
}
