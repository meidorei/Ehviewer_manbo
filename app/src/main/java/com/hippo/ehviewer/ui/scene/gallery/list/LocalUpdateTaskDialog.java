package com.hippo.ehviewer.ui.scene.gallery.list;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.subscription.LocalFollowRepository;
import com.hippo.ehviewer.subscription.LocalRefreshJobStore;
import com.hippo.ehviewer.subscription.LocalRefreshStatusFormatter;
import com.hippo.ehviewer.subscription.LocalUpdateService;

/** One consistent in-app task detail and control dialog for follows and bookmarks. */
final class LocalUpdateTaskDialog {
    private LocalUpdateTaskDialog() {}

    static void show(Context context, LocalRefreshJobStore.Snapshot snapshot,
                     @Nullable Runnable resume, boolean retryFailed) {
        if (snapshot == null) return;
        int failed = snapshot.failures == null || snapshot.failures.isEmpty()
                ? 0 : snapshot.failures.split("\\n").length;
        int success = Math.max(0, snapshot.index - failed);
        int capped;
        if ("FOLLOW".equals(snapshot.type)) {
            capped = LocalFollowRepository.getInstance().countCapped(
                    LocalFollowRepository.SOURCE_FOLLOW);
        } else if ("BOOKMARK".equals(snapshot.type)) {
            capped = LocalFollowRepository.getInstance().countCapped(
                    LocalFollowRepository.SOURCE_BOOKMARK);
        } else {
            capped = LocalFollowRepository.getInstance().countCapped(
                    LocalFollowRepository.SOURCE_FOLLOW)
                    + LocalFollowRepository.getInstance().countCapped(
                    LocalFollowRepository.SOURCE_BOOKMARK);
        }
        String time = LocalRefreshStatusFormatter.formatTime(
                snapshot.updatedAt, System.currentTimeMillis());
        String method = displayMethod(snapshot);
        StringBuilder details = new StringBuilder()
                .append("状态：").append(snapshot.status)
                .append("\n类型：").append(snapshot.type)
                .append("\n方式：").append(method)
                .append("\n阶段：").append(displayPhase(snapshot))
                .append("\n来源：").append(snapshot.host)
                .append("\n请求间隔：")
                .append(String.format(java.util.Locale.US, "%.1f 秒",
                        snapshot.requestIntervalMs / 1000d))
                .append("\n进度：").append(snapshot.index).append('/').append(snapshot.total)
                .append("\n扫描：").append(snapshot.pages).append(" 页，")
                .append(snapshot.galleries).append(" 本");
        if ("BASELINE".equals(snapshot.type)) {
            details.append("\n成功细化：").append(success)
                    .append("\n使用时间基线：").append(failed);
        } else {
            details.append("\n成功：").append(success)
                    .append("\n20+：").append(capped)
                    .append("\n失败：").append(failed);
        }
        details.append("\n时间：").append(time);
        if (failed > 0) details.append("\n\n")
                .append("BASELINE".equals(snapshot.type) ? "回退项目：\n" : "失败项目：\n")
                .append(snapshot.failures);

        AlertDialog.Builder dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.update_details_title)
                .setMessage(details.toString())
                .setNegativeButton(R.string.update_action_close, null);
        if (LocalRefreshJobStore.STATUS_RUNNING.equals(snapshot.status)) {
            dialog.setNeutralButton(R.string.update_action_pause,
                    (ignored, which) -> LocalUpdateService.requestPause(context));
            dialog.setPositiveButton(R.string.update_action_stop,
                    (ignored, which) -> confirmStop(context, snapshot));
        } else if (resume != null
                && (LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status)
                || retryFailed && LocalRefreshJobStore.STATUS_FAILED.equals(snapshot.status))) {
            dialog.setPositiveButton(LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status)
                            ? R.string.update_action_continue : R.string.update_action_retry,
                    (ignored, which) -> resume.run());
        }
        if (LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status)) {
            dialog.setNeutralButton(R.string.update_action_stop,
                    (ignored, which) -> confirmStop(context, snapshot));
        }
        dialog.show();
    }

    static void show(Context context, LocalRefreshJobStore.Snapshot snapshot,
                     boolean retryFailed) {
        show(context, snapshot, resumeAction(context, snapshot), retryFailed);
    }

    @Nullable
    static Runnable resumeAction(Context context, LocalRefreshJobStore.Snapshot snapshot) {
        if (snapshot == null) return null;
        if ("FOLLOW".equals(snapshot.type)) {
            return () -> LocalUpdateService.startFollow(context, snapshot.method);
        }
        if ("BOOKMARK".equals(snapshot.type)) {
            if (snapshot.method != null && snapshot.method.startsWith("SINGLE:")) {
                try {
                    long id = Long.parseLong(
                            snapshot.method.substring("SINGLE:".length()));
                    return () -> LocalUpdateService.startBookmark(context, id);
                } catch (NumberFormatException ignored) {
                    // Fall through to checking all bookmarks.
                }
            }
            return () -> LocalUpdateService.startBookmarks(context, snapshot.method);
        }
        if ("BASELINE".equals(snapshot.type)) {
            return () -> LocalUpdateService.resumePendingBaselines(context);
        }
        return null;
    }

    private static String displayMethod(LocalRefreshJobStore.Snapshot snapshot) {
        if ("FOLLOW".equals(snapshot.type)) {
            if (LocalUpdateService.METHOD_GLOBAL.equals(snapshot.method)) {
                return "全局中文扫描";
            }
            if (LocalUpdateService.METHOD_TAGS.equals(snapshot.method)) {
                return "逐标签检查";
            }
        }
        if ("BOOKMARK".equals(snapshot.type)) {
            if (LocalUpdateService.METHOD_GLOBAL.equals(snapshot.method)) {
                return "全局中文扫描";
            }
            if (LocalUpdateService.METHOD_FIRST_PAGE.equals(snapshot.method)) {
                return "逐书签检查";
            }
        }
        return snapshot.method;
    }

    private static String displayPhase(LocalRefreshJobStore.Snapshot snapshot) {
        if (LocalRefreshJobStore.PHASE_GLOBAL_SCAN.equals(snapshot.phase)) {
            return "全局中文扫描";
        }
        if (LocalRefreshJobStore.PHASE_FOLLOW_QUEUE.equals(snapshot.phase)) {
            return "逐标签检查";
        }
        if (LocalRefreshJobStore.PHASE_BOOKMARK_QUEUE.equals(snapshot.phase)) {
            return "逐书签检查";
        }
        if (LocalRefreshJobStore.PHASE_BASELINE.equals(snapshot.phase)) {
            return "建立基线";
        }
        return "准备中";
    }

    static boolean isOpenTask(@Nullable LocalRefreshJobStore.Snapshot snapshot) {
        return snapshot != null
                && (LocalRefreshJobStore.STATUS_RUNNING.equals(snapshot.status)
                || LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status));
    }

    private static void confirmStop(Context context, LocalRefreshJobStore.Snapshot snapshot) {
        boolean global = ("FOLLOW".equals(snapshot.type)
                || "BOOKMARK".equals(snapshot.type))
                && LocalUpdateService.METHOD_GLOBAL.equals(snapshot.method);
        new AlertDialog.Builder(context)
                .setTitle(R.string.update_stop_confirm_title)
                .setMessage(global ? R.string.update_stop_global_confirm
                        : R.string.update_stop_queue_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_action_stop,
                        (ignored, which) -> LocalUpdateService.requestCancel(context))
                .show();
    }
}
