package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.*;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.subscription.FeedBoundary;
import com.hippo.ehviewer.subscription.FeedSourceContext;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class FeedNavigationPolicyTest {
    private static final Object OWNER = new Object();
    @Test public void openingFollowExitsCombinedSearchAndPreservesFullNamespace() {
        FeedNavigationPolicy.Entry entry = FeedNavigationPolicy.tag(
                "Artist: Example Author", true, true, Arrays.asList("glasses", "school"));
        ListUrlBuilder query = new ListUrlBuilder();
        query.set(entry.keyword, entry.mode);
        assertEquals(FeedSourceContext.Type.SUBSCRIPTION_TAG, entry.type);
        assertEquals("artist: example author", entry.sourceKey);
        assertEquals(entry.sourceKey, query.getKeyword());
        assertEquals(ListUrlBuilder.MODE_TAG, query.getMode());
        assertFalse(entry.combine);
        assertTrue(entry.filters.isEmpty());
    }

    @Test public void ordinaryChipAlwaysStartsTemporarySearchWithoutUnreadSideEffects() {
        for (boolean combine : Arrays.asList(false, true)) {
            FeedNavigationPolicy.Entry entry = FeedNavigationPolicy.tag(
                    "female:glasses", false, combine, Collections.singletonList("school"));
            FeedSourceContext source = context(entry.type);
            assertEquals(FeedSourceContext.Type.TEMP_SEARCH, source.type);
            assertEquals("", entry.sourceKey);
            assertFalse(source.showsMarker());
            assertFalse(request(source, true).canComplete(OWNER, source, false, false, true));
            assertEquals(combine ? "school  glasses" : "female:glasses", entry.keyword);
        }
    }

    @Test public void fixedChineseSourcesKeepTheirExistingEntryOnGlobalToggle() {
        assertTrue(context(FeedSourceContext.Type.QUICK_SEARCH).usesFixedChineseQuery());
        assertTrue(context(FeedSourceContext.Type.SUBSCRIPTION_TAG).usesFixedChineseQuery());
        assertFalse(context(FeedSourceContext.Type.HOME).usesFixedChineseQuery());
        assertFalse(context(FeedSourceContext.Type.TEMP_SEARCH).usesFixedChineseQuery());
        assertFalse(context(FeedSourceContext.Type.SUBSCRIPTION_AGGREGATE).usesFixedChineseQuery());
    }

    @Test public void staleRequestCannotClearEvenWhenReenteringTheSameSavedQuery() {
        FeedSourceContext before = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedNavigationPolicy.Request pending = request(before, true);
        FeedSourceContext after = context(FeedSourceContext.Type.QUICK_SEARCH);
        assertTrue(pending.canComplete(OWNER, before, false, false, true));
        assertFalse(pending.isCurrent(OWNER, after));
        assertFalse(pending.canComplete(OWNER, after, false, false, true));
        assertEquals(42L, pending.unreadRowId);
    }

    @Test public void uncertainTopDoesNotClearUnreadAndSuccessfulRetryCanComplete() {
        for (FeedSourceContext.Type type : Arrays.asList(
                FeedSourceContext.Type.QUICK_SEARCH, FeedSourceContext.Type.SUBSCRIPTION_TAG)) {
            FeedSourceContext source = context(type);
            FeedNavigationPolicy.Request pending = request(source, true);
            assertFalse(pending.canComplete(OWNER, source, false, false, false));
            assertTrue(pending.canComplete(OWNER, source, false, false, true));
            assertFalse(pending.canComplete(OWNER, source, true, false, true));
            assertFalse(request(source, false).canComplete(OWNER, source, false, false, true));
        }
    }

    @Test public void recreatedViewRejectsOldResponseWithSameSourceAndTaskNumber() {
        FeedSourceContext source = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedNavigationPolicy.Request pending = request(source, true);
        Object recreatedHelper = new Object();
        assertFalse(pending.isCurrent(recreatedHelper, source));
        assertFalse(pending.canComplete(recreatedHelper, source, false, false, true));
        assertTrue(pending.isCurrent(OWNER, source));
    }

    @Test public void acceptedResponseCanCommitAfterViewRecreationButNotAfterReentry() {
        FeedSourceContext source = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedNavigationPolicy.Request accepted = request(source, true);
        assertTrue(accepted.canComplete(OWNER, source, false, false, true));
        assertFalse(accepted.isCurrent(new Object(), source));
        assertTrue(accepted.belongsTo(source));
        assertFalse(accepted.belongsTo(context(FeedSourceContext.Type.QUICK_SEARCH)));
    }

    @Test public void queuedOpenIsNeverPersistedAsCommittedAndCanRetryAfterProcessRestore() {
        FeedSourceContext source = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedNavigationPolicy.OpenCompletion completion = new FeedNavigationPolicy.OpenCompletion();
        completion.reset(source, false);
        assertTrue(completion.begin(source));
        assertTrue(completion.isBusy());
        boolean saved = completion.savedCommitted();
        assertFalse(saved);

        // The process can disappear before the queued transaction has ever run.
        FeedSourceContext restoredSource = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedNavigationPolicy.OpenCompletion restored = new FeedNavigationPolicy.OpenCompletion();
        restored.reset(restoredSource, saved);
        assertFalse(restored.isBusy());
        assertTrue(restored.begin(restoredSource));
    }

    @Test public void onlySuccessfulDatabaseCompletionIsSavedAndBlocksFurtherOpens() {
        FeedSourceContext source = context(FeedSourceContext.Type.SUBSCRIPTION_TAG);
        FeedNavigationPolicy.OpenCompletion completion = new FeedNavigationPolicy.OpenCompletion();
        completion.reset(source, false);
        assertTrue(completion.begin(source));
        completion.finish(source, true);
        assertTrue(completion.savedCommitted());
        assertTrue(completion.isBusy());
        assertFalse(completion.begin(source));

        FeedSourceContext restoredSource = context(FeedSourceContext.Type.SUBSCRIPTION_TAG);
        FeedNavigationPolicy.OpenCompletion restored = new FeedNavigationPolicy.OpenCompletion();
        restored.reset(restoredSource, completion.savedCommitted());
        assertTrue(restored.isBusy());
        assertFalse(restored.begin(restoredSource));
    }

    @Test public void failedDatabaseCompletionClearsPendingAndAllowsRetry() {
        FeedSourceContext source = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedNavigationPolicy.OpenCompletion completion = new FeedNavigationPolicy.OpenCompletion();
        completion.reset(source, false);
        assertTrue(completion.begin(source));
        completion.finish(source, false);
        assertFalse(completion.savedCommitted());
        assertFalse(completion.isBusy());
        assertTrue(completion.begin(source));
        completion.finish(source, true);
        assertTrue(completion.savedCommitted());
    }

    @Test public void viewRecreationCannotQueueDuplicateTransactionForPendingEntry() {
        FeedSourceContext source = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedNavigationPolicy.OpenCompletion completion = new FeedNavigationPolicy.OpenCompletion();
        completion.reset(source, false);
        FeedNavigationPolicy.Request accepted = request(source, true);
        assertTrue(accepted.canComplete(OWNER, source, completion.isBusy(), false, true));
        assertTrue(completion.begin(source));
        Object recreatedOwner = new Object();
        FeedNavigationPolicy.Request recreated = new FeedNavigationPolicy.Request(
                recreatedOwner, source, FeedBoundary.EMPTY, 42L, true);
        assertFalse(recreated.canComplete(recreatedOwner, source, completion.isBusy(), false, true));
        assertTrue(accepted.belongsTo(source));
        completion.finish(accepted.source, true);
        assertFalse(recreated.canComplete(recreatedOwner, source, completion.isBusy(), false, true));
    }

    @Test public void completionFromPreviousEntryCannotFinishOrUnblockNewEntry() {
        FeedSourceContext previous = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedSourceContext current = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedNavigationPolicy.OpenCompletion completion = new FeedNavigationPolicy.OpenCompletion();
        completion.reset(previous, false);
        assertTrue(completion.begin(previous));
        completion.reset(current, false);
        assertFalse(completion.isBusy());
        assertTrue(completion.begin(current));
        completion.finish(previous, true);
        assertFalse(completion.savedCommitted());
        assertTrue(completion.isBusy());
        completion.finish(previous, false);
        assertTrue(completion.isBusy());
        completion.finish(current, true);
        assertTrue(completion.savedCommitted());
    }

    @Test public void legitimateEmptyBookmarkClearsButEmptyFollowDoesNot() {
        FeedSourceContext bookmark = context(FeedSourceContext.Type.QUICK_SEARCH);
        FeedSourceContext follow = context(FeedSourceContext.Type.SUBSCRIPTION_TAG);
        assertTrue(request(bookmark, true).canComplete(OWNER, bookmark, false, true, false));
        assertFalse(request(follow, true).canComplete(OWNER, follow, false, true, false));
    }

    @Test public void homepageResetRequiresTheRealHomepageQuery() {
        FeedSourceContext home = context(FeedSourceContext.Type.HOME);
        ListUrlBuilder query = new ListUrlBuilder();
        assertTrue(FeedNavigationPolicy.canResetHome(home, query));
        query.set("artist:example");
        assertFalse(FeedNavigationPolicy.canResetHome(home, query));
        query.reset();
        query.setKeyword("keyword");
        assertFalse(FeedNavigationPolicy.canResetHome(home, query));
        query.reset();
        query.setCategory(1);
        assertFalse(FeedNavigationPolicy.canResetHome(home, query));
        query.reset();
        assertFalse(FeedNavigationPolicy.canResetHome(context(FeedSourceContext.Type.TEMP_SEARCH), query));
    }

    @Test public void artistEntryRetainsGidMarkerIdentity() {
        FeedNavigationPolicy.Entry entry = FeedNavigationPolicy.tag(
                "artist:example", true, false, Collections.emptyList());
        FeedSourceContext source = new FeedSourceContext(entry.type, entry.sourceKey, "fixed");
        assertTrue(source.isFollowedArtist());
        assertTrue(source.usesFixedChineseQuery());
    }

    private static FeedSourceContext context(FeedSourceContext.Type type) {
        return new FeedSourceContext(type, "saved-source", "saved-signature");
    }

    private static FeedNavigationPolicy.Request request(FeedSourceContext source, boolean first) {
        return new FeedNavigationPolicy.Request(OWNER, source, FeedBoundary.EMPTY, 42L, first);
    }
}
