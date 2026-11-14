package com.hippo.ehviewer.ui.scene.gallery.list;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;

import androidx.appcompat.widget.Toolbar;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.subscription.LocalRefreshJobStore;
import com.hippo.ehviewer.subscription.LocalRefreshStatusFormatter;
import com.hippo.ehviewer.subscription.LocalUpdateService;

/** Shared toolbar presentation and refresh interaction for follows and bookmarks. */
final class LocalUpdateToolbarController {
    private final Context context;
    private final Toolbar toolbar;
    private final String historyType;
    private final Runnable startAction;
    private final Runnable detailsAction;
    private final ImageButton refreshButton;
    private ObjectAnimator rotationAnimator;

    LocalUpdateToolbarController(Context context, LayoutInflater inflater, Toolbar toolbar,
                                 String historyType, Runnable startAction,
                                 Runnable detailsAction) {
        this.context = context;
        this.toolbar = toolbar;
        this.historyType = historyType;
        this.startAction = startAction;
        this.detailsAction = detailsAction;

        MenuItem refreshItem = toolbar.getMenu().findItem(R.id.action_refresh);
        View actionView = inflater.inflate(R.layout.action_subscription_refresh, toolbar, false);
        refreshButton = actionView.findViewById(R.id.subscription_refresh_button);
        refreshButton.setOnClickListener(view -> onRefreshClicked());
        refreshButton.setOnLongClickListener(view -> {
            LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
            if (snapshot == null) return false;
            detailsAction.run();
            return true;
        });
        refreshItem.setActionView(actionView);
    }

    void render(LocalRefreshJobStore.Snapshot snapshot) {
        boolean running = snapshot != null
                && LocalRefreshJobStore.STATUS_RUNNING.equals(snapshot.status);
        boolean paused = snapshot != null
                && LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status);
        setRotating(running);
        if (running || paused) {
            toolbar.setSubtitle(progressSubtitle(snapshot, paused));
        } else {
            toolbar.setSubtitle(historySubtitle(LocalRefreshJobStore.lastAttempt(historyType)));
        }
    }

    private void onRefreshClicked() {
        LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
        if (LocalUpdateService.isActive() || LocalUpdateTaskDialog.isOpenTask(snapshot)) {
            detailsAction.run();
        } else {
            startAction.run();
        }
    }

    private String progressSubtitle(LocalRefreshJobStore.Snapshot snapshot, boolean paused) {
        String prefix;
        if (paused) {
            prefix = context.getString(R.string.local_update_paused);
        } else if (LocalRefreshJobStore.TYPE_BASELINE.equals(snapshot.type)) {
            prefix = context.getString(R.string.local_update_baselining);
        } else {
            prefix = context.getString(R.string.local_update_checking);
        }
        boolean globalScan = LocalRefreshJobStore.PHASE_GLOBAL_SCAN.equals(snapshot.phase);
        if (globalScan) {
            return context.getString(R.string.local_update_global_progress, prefix,
                    snapshot.pages, snapshot.galleries);
        }
        String current = snapshot.currentKey == null || snapshot.currentKey.trim().isEmpty()
                ? context.getString(R.string.local_update_preparing) : snapshot.currentKey;
        return context.getString(R.string.local_update_item_progress, prefix,
                snapshot.index, snapshot.total, current);
    }

    private String historySubtitle(LocalRefreshJobStore.AttemptHistory history) {
        if (history == null) return context.getString(R.string.local_update_never_checked);
        String time = LocalRefreshStatusFormatter.formatTime(
                history.time, System.currentTimeMillis());
        if (LocalRefreshJobStore.RESULT_PARTIAL.equals(history.result)) {
            return context.getString(R.string.local_update_last_partial,
                    time, history.failureCount);
        }
        int resultString = LocalRefreshJobStore.RESULT_SUCCESS.equals(history.result)
                ? R.string.local_update_result_success
                : LocalRefreshJobStore.RESULT_CANCELLED.equals(history.result)
                ? R.string.local_update_result_stopped
                : R.string.local_update_result_failed;
        return context.getString(R.string.local_update_last_result,
                time, context.getString(resultString));
    }

    private void setRotating(boolean rotating) {
        if (!rotating) {
            if (rotationAnimator != null) {
                rotationAnimator.cancel();
                rotationAnimator = null;
            }
            refreshButton.setRotation(0f);
            return;
        }
        if (rotationAnimator != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ValueAnimator.areAnimatorsEnabled()) {
            refreshButton.setRotation(0f);
            return;
        }
        rotationAnimator = ObjectAnimator.ofFloat(refreshButton, View.ROTATION, 0f, 360f);
        rotationAnimator.setDuration(900L);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.start();
    }
}
