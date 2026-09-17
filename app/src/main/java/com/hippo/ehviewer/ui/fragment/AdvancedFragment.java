/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.subscription.GlobalScanPageLimitPolicy;
import com.hippo.ehviewer.subscription.LocalFollowJson;
import com.hippo.ehviewer.subscription.LocalFollowRepository;
import com.hippo.ehviewer.subscription.LocalUpdateService;
import com.hippo.ehviewer.subscription.SearchIntervalPolicy;
import com.hippo.ehviewer.ui.wifi.WiFiClientActivity;
import com.hippo.ehviewer.ui.wifi.WiFiServerActivity;
import com.hippo.ehviewer.widget.ProgressHelper;
import com.hippo.util.LogCat;
import com.hippo.util.ReadableTime;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

public class AdvancedFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {
    public static final int DB_LOADING = 0;
    public static final int DB_LOAD_FINISH = 1;

    public static final String LOADING_STATUS = "loading_status";
    public static final String LOADING_PROGRESS = "loading_progress";

    private static final String KEY_DUMP_LOGCAT = "dump_logcat";
    private static final String KEY_CLEAR_MEMORY_CACHE = "clear_memory_cache";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_IMPORT_DATA = "import_data";
    private static final String KEY_EXPORT_LOCAL_FOLLOWS = "export_local_follows";
    private static final String KEY_IMPORT_LOCAL_FOLLOWS = "import_local_follows";
    private static final String KEY_LOCAL_UPDATE_SEARCH_INTERVAL =
            Settings.KEY_LOCAL_UPDATE_SEARCH_INTERVAL;
    private static final String KEY_GLOBAL_SCAN_PAGE_LIMIT =
            Settings.KEY_GLOBAL_SCAN_PAGE_LIMIT;
    private static final String KEY_RESET_BOOKMARK_BASELINES = "reset_bookmark_baselines";
    private static final String KEY_RESET_FOLLOW_BASELINES = "reset_follow_baselines";
    private boolean baselineResetPending;
    private static final String KEY_WIFI_SERVER = "wifi_server";
    private static final String KEY_WIFI_CLIENT = "wifi_client";
    private static final int REQUEST_EXPORT_LOCAL_FOLLOWS = 4101;
    private static final int REQUEST_IMPORT_LOCAL_FOLLOWS = 4102;

    private final DbSyncHandle dbSyncHandle = new DbSyncHandle(Looper.getMainLooper());

    private Context context;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        context = getContext();
        addPreferencesFromResource(R.xml.advanced_settings);

        Preference dumpLogcat = findPreference(KEY_DUMP_LOGCAT);
        Preference clearMemoryCache = findPreference(KEY_CLEAR_MEMORY_CACHE);
        Preference appLanguage = findPreference(KEY_APP_LANGUAGE);
        Preference importData = findPreference(KEY_IMPORT_DATA);
        Preference exportLocalFollows = findPreference(KEY_EXPORT_LOCAL_FOLLOWS);
        Preference importLocalFollows = findPreference(KEY_IMPORT_LOCAL_FOLLOWS);
        EditTextPreference localUpdateInterval =
                findPreference(KEY_LOCAL_UPDATE_SEARCH_INTERVAL);
        EditTextPreference globalScanPageLimit =
                findPreference(KEY_GLOBAL_SCAN_PAGE_LIMIT);
        Preference resetBookmarkBaselines = findPreference(KEY_RESET_BOOKMARK_BASELINES);
        resetBookmarkBaselines.setOnPreferenceClickListener(this);
        findPreference(KEY_RESET_FOLLOW_BASELINES).setOnPreferenceClickListener(this);
        Preference socketData = findPreference(KEY_WIFI_SERVER);
        Preference clientData = findPreference(KEY_WIFI_CLIENT);

        dumpLogcat.setOnPreferenceClickListener(this);
        clearMemoryCache.setOnPreferenceClickListener(this);
        importData.setOnPreferenceClickListener(this);
        exportLocalFollows.setOnPreferenceClickListener(this);
        importLocalFollows.setOnPreferenceClickListener(this);
        socketData.setOnPreferenceClickListener(this);
        clientData.setOnPreferenceClickListener(this);

        appLanguage.setOnPreferenceChangeListener(this);
        if (localUpdateInterval != null) {
            localUpdateInterval.setOnBindEditTextListener(editText -> {
                editText.setSingleLine(true);
                editText.setInputType(InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            });
            localUpdateInterval.setOnPreferenceChangeListener(this);
            updateIntervalSummary(localUpdateInterval,
                    Settings.getLocalUpdateSearchIntervalMs());
        }
        if (globalScanPageLimit != null) {
            globalScanPageLimit.setOnBindEditTextListener(editText -> {
                editText.setSingleLine(true);
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            });
            globalScanPageLimit.setOnPreferenceChangeListener(this);
            updateGlobalScanPageLimitSummary(globalScanPageLimit,
                    Settings.getGlobalScanPageLimit());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case KEY_DUMP_LOGCAT:
                return dumpLogcat();
            case KEY_CLEAR_MEMORY_CACHE:
                return clearMemoryCache();
            case KEY_IMPORT_DATA:
                importData(getActivity());
                getActivity().setResult(Activity.RESULT_OK);
                return true;
            case KEY_EXPORT_LOCAL_FOLLOWS:
                chooseLocalFollowExportTarget();
                return true;
            case KEY_IMPORT_LOCAL_FOLLOWS:
                chooseLocalFollowImportSource();
                return true;
            case KEY_RESET_BOOKMARK_BASELINES:
                showBaselineReset(false);
                return true;
            case KEY_RESET_FOLLOW_BASELINES:
                showBaselineReset(true);
                return true;
            case KEY_WIFI_SERVER:
                return gotoWiFiServerActivity();
            case KEY_WIFI_CLIENT:
                return gotoWiFiClientActivity();
            default:
                return false;
        }
    }

    private void showBaselineReset(boolean follows) {
        Context current = getContext();
        if (current == null || baselineResetPending) return;
        if (LocalUpdateService.isActive()) {
            Toast.makeText(current, R.string.bookmark_baseline_reset_busy, Toast.LENGTH_LONG).show();
            return;
        }
        baselineResetPending = true;
        new AlertDialog.Builder(current)
                .setTitle(follows ? R.string.settings_advanced_reset_follow_baselines
                        : R.string.settings_advanced_reset_bookmark_baselines)
                .setMessage(follows ? R.string.follow_baseline_reset_confirm
                        : R.string.bookmark_baseline_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.bookmark_baseline_reset_action,
                        (dialog, which) -> resetBaselines(current, follows))
                .setOnDismissListener(dialog -> {
                    Preference preference = findPreference(KEY_RESET_BOOKMARK_BASELINES);
                    if (preference == null || preference.isEnabled()) baselineResetPending = false;
                })
                .show();
    }

    private void resetBaselines(Context current, boolean follows) {
        Preference bookmarks = findPreference(KEY_RESET_BOOKMARK_BASELINES);
        Preference tags = findPreference(KEY_RESET_FOLLOW_BASELINES);
        Preference target = follows ? tags : bookmarks;
        if (bookmarks != null) bookmarks.setEnabled(false);
        if (tags != null) tags.setEnabled(false);
        if (target != null) target.setSummary(follows ? R.string.follow_baseline_reset_running
                : R.string.bookmark_baseline_reset_running);
        new Thread(() -> {
            String message;
            try {
                int count = follows ? LocalUpdateService.resetFollowBaselines()
                        : LocalUpdateService.resetBookmarkBaselines();
                if (count < 0) {
                    message = current.getString(R.string.bookmark_baseline_reset_busy);
                } else if (count == 0) {
                    message = current.getString(follows ? R.string.follow_baseline_reset_empty
                            : R.string.bookmark_baseline_reset_empty);
                } else {
                    message = current.getString(follows ? R.string.follow_baseline_reset_success
                            : R.string.bookmark_baseline_reset_success, count);
                }
            } catch (RuntimeException error) {
                android.util.Log.e("AdvancedFragment", "Local baseline reset failed", error);
                message = current.getString(follows ? R.string.follow_baseline_reset_failed
                        : R.string.bookmark_baseline_reset_failed);
            }
            String result = message;
            dbSyncHandle.post(() -> {
                baselineResetPending = false;
                if (bookmarks != null) bookmarks.setEnabled(true);
                if (tags != null) tags.setEnabled(true);
                if (target != null) target.setSummary(follows
                        ? R.string.settings_advanced_reset_follow_baselines_summary
                        : R.string.settings_advanced_reset_bookmark_baselines_summary);
                Toast.makeText(current, result, Toast.LENGTH_LONG).show();
            });
        }, "local-baseline-reset").start();
    }

    private void chooseLocalFollowExportTarget() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "ehviewer-local-follows.json");
        startActivityForResult(intent, REQUEST_EXPORT_LOCAL_FOLLOWS);
    }

    private void chooseLocalFollowImportSource() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_LOCAL_FOLLOWS);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_LOCAL_FOLLOWS) {
            exportLocalFollows(uri);
        } else if (requestCode == REQUEST_IMPORT_LOCAL_FOLLOWS) {
            importLocalFollows(uri);
        }
    }

    private void exportLocalFollows(Uri uri) {
        Context current = getContext();
        if (current == null) return;
        new Thread(() -> {
            String message;
            try (OutputStream output = current.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("无法打开导出文件");
                List<String> tags = LocalFollowRepository.getInstance().getAll();
                LocalFollowJson.write(output, tags);
                message = getString(R.string.local_follow_export_success, tags.size());
            } catch (Exception e) {
                message = getString(R.string.local_follow_export_failed, e.getMessage());
            }
            String finalMessage = message;
            dbSyncHandle.post(() -> Toast.makeText(current, finalMessage, Toast.LENGTH_LONG).show());
        }, "local-follow-export").start();
    }

    private void importLocalFollows(Uri uri) {
        Context current = getContext();
        if (current == null) return;
        new Thread(() -> {
            try (InputStream input = current.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("无法打开导入文件");
                LocalFollowJson.ParseResult parsed = LocalFollowJson.read(input);
                dbSyncHandle.post(() -> showLocalFollowImportMode(current, parsed));
            } catch (Exception e) {
                String message = getString(R.string.local_follow_import_failed, e.getMessage());
                dbSyncHandle.post(() -> Toast.makeText(current, message, Toast.LENGTH_LONG).show());
            }
        }, "local-follow-import").start();
    }

    private void showLocalFollowImportMode(Context current, LocalFollowJson.ParseResult parsed) {
        new AlertDialog.Builder(current)
                .setTitle(R.string.settings_advanced_import_local_follows)
                .setMessage(getString(R.string.local_follow_import_preview,
                        parsed.tags.size(), parsed.invalid, parsed.duplicates))
                .setPositiveButton(R.string.local_follow_import_merge,
                        (dialog, which) -> commitLocalFollowImport(current, parsed, false))
                .setNeutralButton(R.string.local_follow_import_replace,
                        (dialog, which) -> new AlertDialog.Builder(current)
                                .setTitle(R.string.local_follow_import_replace)
                                .setMessage(R.string.local_follow_import_replace_confirm)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(android.R.string.ok,
                                        (confirm, ignored) ->
                                                commitLocalFollowImport(current, parsed, true))
                                .show())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void commitLocalFollowImport(Context current, LocalFollowJson.ParseResult parsed,
                                         boolean replace) {
        new Thread(() -> {
            LocalFollowRepository.ImportResult result =
                    LocalFollowRepository.getInstance().importTags(parsed.tags, replace);
            LocalUpdateService.startPendingBaselines(current);
            String message = getString(R.string.local_follow_import_success, result.after,
                    parsed.invalid, parsed.duplicates);
            dbSyncHandle.post(() -> Toast.makeText(current, message, Toast.LENGTH_LONG).show());
        }, "local-follow-import-commit").start();
    }

    private boolean gotoWiFiClientActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, WiFiClientActivity.class);
        activity.startActivity(intent);
        return false;
    }

    private boolean gotoWiFiServerActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, WiFiServerActivity.class);
        activity.startActivity(intent);
        return false;
    }

    private boolean clearMemoryCache() {
        ((EhApplication) getActivity().getApplication()).clearMemoryCache();
        Runtime.getRuntime().gc();
        return false;
    }

    private boolean dumpLogcat() {
        boolean ok;
        File file = null;
        File dir = AppConfig.getExternalLogcatDir();
        if (dir != null) {
            file = new File(dir, "logcat-" + ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".txt");
            ok = LogCat.save(file);
        } else {
            ok = false;
        }
        Resources resources = getResources();
        Toast.makeText(getActivity(),
                ok ? resources.getString(R.string.settings_advanced_dump_logcat_to, file.getPath()) :
                        resources.getString(R.string.settings_advanced_dump_logcat_failed), Toast.LENGTH_SHORT).show();
        return true;
    }

    private boolean importData(final Context context) {
        final File dir = AppConfig.getExternalDataDir();
        if (null == dir) {
            Toast.makeText(context, R.string.cant_get_data_dir, Toast.LENGTH_SHORT).show();
            return false;
        }
        final String[] files = dir.list();
        if (null == files || files.length <= 0) {
            Toast.makeText(context, R.string.cant_find_any_data, Toast.LENGTH_SHORT).show();
            return false;
        }
        Arrays.sort(files);
        new AlertDialog.Builder(context).setItems(files, (dialog, which) -> {
            dialog.dismiss();
            showProgress(context, dir, files, which);
        }).show();
        return false;
    }

    private void showProgress(final Context context, File dir, String[] files, int which) {

        File file = new File(dir, files[which]);
        ProgressHelper.showDialog(context, context.getString(R.string.loading_db_file));
        new Thread(
                () -> {
                    String error = EhDB.importDB(context, file, dbSyncHandle);
                    Message message = new Message();
                    Bundle bundle = new Bundle();
                    bundle.putString("error", error);
                    bundle.putInt(LOADING_STATUS, DB_LOAD_FINISH);
                    message.setData(bundle);
                    dbSyncHandle.sendMessage(message);
                }
        ).start();


    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (KEY_APP_LANGUAGE.equals(key)) {
            ((EhApplication) getActivity().getApplication()).recreate();
            return true;
        }
        if (KEY_LOCAL_UPDATE_SEARCH_INTERVAL.equals(key)) {
            EditTextPreference edit = (EditTextPreference) preference;
            final int millis;
            try {
                millis = SearchIntervalPolicy.parseMillis(String.valueOf(newValue));
            } catch (IllegalArgumentException error) {
                Toast.makeText(requireContext(),
                        R.string.settings_advanced_local_update_interval_invalid,
                        Toast.LENGTH_LONG).show();
                return false;
            }
            String normalized = SearchIntervalPolicy.formatSeconds(millis);
            if (millis < SearchIntervalPolicy.WARNING_BELOW_MS) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_advanced_local_update_interval_warning_title)
                        .setMessage(getString(
                                R.string.settings_advanced_local_update_interval_warning,
                                normalized))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            edit.setText(normalized);
                            updateIntervalSummary(edit, millis);
                        })
                        .show();
                return false;
            }
            updateIntervalSummary(edit, millis);
            return true;
        }
        if (KEY_GLOBAL_SCAN_PAGE_LIMIT.equals(key)) {
            EditTextPreference edit = (EditTextPreference) preference;
            final int pages;
            try {
                pages = GlobalScanPageLimitPolicy.parsePages(String.valueOf(newValue));
            } catch (IllegalArgumentException error) {
                Toast.makeText(requireContext(),
                        R.string.settings_advanced_global_scan_page_limit_invalid,
                        Toast.LENGTH_LONG).show();
                return false;
            }
            String normalized = Integer.toString(pages);
            updateGlobalScanPageLimitSummary(edit, pages);
            if (!normalized.equals(String.valueOf(newValue).trim())) {
                edit.setText(normalized);
                return false;
            }
            return true;
        }
        return false;
    }

    private void updateIntervalSummary(Preference preference, int millis) {
        preference.setSummary(getString(
                R.string.settings_advanced_local_update_interval_summary,
                SearchIntervalPolicy.formatSeconds(millis)));
    }

    private void updateGlobalScanPageLimitSummary(Preference preference, int pages) {
        preference.setSummary(getString(
                R.string.settings_advanced_global_scan_page_limit_summary, pages));
    }

    private class DbSyncHandle extends Handler {
        public DbSyncHandle(Looper mainLooper) {
            super(mainLooper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Bundle data = msg.getData();
            int state = data.getInt(LOADING_STATUS);
            if (state == DB_LOAD_FINISH){
                ProgressHelper.dismissDialog();
                String error = data.getString("error");
                if (context == null) {
                    return;
                }
                if (null == error) {
                    error = context.getString(R.string.settings_advanced_import_data_successfully);
                }

                Toast.makeText(context, error, Toast.LENGTH_SHORT).show();
            } else if (state == DB_LOADING) {
                ProgressHelper.setProgress(data.getInt(LOADING_PROGRESS,0));
            }

        }
    }
}
