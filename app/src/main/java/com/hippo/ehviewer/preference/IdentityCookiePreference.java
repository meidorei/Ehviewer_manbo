/*
 * Copyright 2018 Hippo Seven
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

package com.hippo.ehviewer.preference;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.IgneousUtils;
import com.hippo.preference.MessagePreference;
import com.hippo.text.Html;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class IdentityCookiePreference extends MessagePreference {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String message;
    private CharSequence dialogMessage;
    private Call refreshCall;
    private boolean attached;

    public IdentityCookiePreference(Context context) {
        super(context);
        init();
    }

    public IdentityCookiePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public IdentityCookiePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        EhCookieStore store = EhApplication.getEhCookieStore(getContext());
        List<Cookie> eCookies = store.getCookies(HttpUrl.get(EhUrl.HOST_E));
        List<Cookie> exCookies = store.getCookies(HttpUrl.get(EhUrl.HOST_EX));
        List<Cookie> cookies = new LinkedList<>(eCookies);
        cookies.addAll(exCookies);

        String ipbMemberId = null;
        String ipbPassHash = null;
        String igneous = null;

        for (int i = 0, n = cookies.size(); i < n; i++) {
            Cookie cookie = cookies.get(i);
            switch (cookie.name()) {
                case EhCookieStore.KEY_IPD_MEMBER_ID:
                    ipbMemberId = cookie.value();
                    break;
                case EhCookieStore.KEY_IPD_PASS_HASH:
                    ipbPassHash = cookie.value();
                    break;
                case EhCookieStore.KEY_IGNEOUS:
                    igneous = cookie.value();
                    break;
            }
        }

        if (ipbMemberId != null || ipbPassHash != null || igneous != null) {
            message = EhCookieStore.KEY_IPD_MEMBER_ID + ": " + ipbMemberId + "<br>"
                    + EhCookieStore.KEY_IPD_PASS_HASH + ": " + ipbPassHash + "<br>"
                    + EhCookieStore.KEY_IGNEOUS + ": " + igneous;
            dialogMessage = Html.fromHtml(
                    getContext().getString(R.string.settings_eh_identity_cookies_signed, message));
            setDialogMessage(dialogMessage);
            message = message.replace("<br>", "\n");
        } else {
            message = null;
            dialogMessage = getContext().getString(R.string.settings_eh_identity_cookies_tourist);
            setDialogMessage(dialogMessage);
        }
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        if (message != null) {
            builder.setPositiveButton(R.string.settings_eh_identity_cookies_copy, (dialog, which) -> {
                ClipboardManager cmb = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cmb.setPrimaryClip(ClipData.newPlainText(null, message));
                Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();

                IdentityCookiePreference.this.onClick(dialog, which);
            });
            builder.setNeutralButton(R.string.settings_eh_refresh_igneous, null);
        }
    }

    @Override
    protected void onDialogCreated(AlertDialog dialog) {
        super.onDialogCreated(dialog);
        Button refreshButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (refreshButton != null) {
            refreshButton.setOnClickListener(view -> refreshIgneous(refreshButton));
        }
    }

    private void refreshIgneous(Button refreshButton) {
        if (refreshCall != null) {
            return;
        }

        Request request;
        try {
            request = IgneousUtils.buildRefreshRequest(
                    Settings.getCfAutoRetry(), Settings.getCfConnectingIp());
        } catch (IllegalArgumentException e) {
            Toast.makeText(getContext(),
                    R.string.settings_advanced_cf_connecting_ip_invalid,
                    Toast.LENGTH_LONG).show();
            return;
        }

        refreshButton.setEnabled(false);
        refreshButton.setText(R.string.settings_eh_refreshing_igneous);
        EhCookieStore cookieStore = EhApplication.getEhCookieStore(getContext());
        cookieStore.removeIgneous();
        clearWebViewIgneous();

        Call call = EhApplication.getOkHttpClient(getContext()).newCall(request);
        refreshCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call failedCall, IOException e) {
                cookieStore.removeIgneous();
                postRefreshResult(failedCall, false, -1);
            }

            @Override
            public void onResponse(Call completedCall, Response response) {
                int responseCode = response.code();
                response.close();
                String igneous = cookieStore.getIgneous();
                boolean refreshed = IgneousUtils.isUsableIgneous(igneous);
                if (!refreshed) {
                    cookieStore.removeIgneous();
                }
                postRefreshResult(completedCall, refreshed, responseCode);
            }
        });
    }

    private void postRefreshResult(Call call, boolean refreshed, int responseCode) {
        mainHandler.post(() -> {
            if (!attached || refreshCall != call) {
                return;
            }
            refreshCall = null;
            init();
            if (getDialog() instanceof AlertDialog) {
                AlertDialog dialog = (AlertDialog) getDialog();
                if (dialog.isShowing()) {
                    dialog.setMessage(dialogMessage);
                    Button button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                    if (button != null) {
                        button.setEnabled(true);
                        button.setText(R.string.settings_eh_refresh_igneous);
                    }
                }
            }

            int messageRes;
            if (refreshed) {
                messageRes = R.string.settings_eh_refresh_igneous_success;
            } else if (responseCode >= 200 && responseCode < 300) {
                messageRes = R.string.settings_eh_refresh_igneous_invalid_cookie;
            } else if (responseCode >= 0) {
                Toast.makeText(getContext(),
                        getContext().getString(
                                R.string.settings_eh_refresh_igneous_http_error, responseCode),
                        Toast.LENGTH_LONG).show();
                return;
            } else {
                messageRes = R.string.settings_eh_refresh_igneous_network_error;
            }
            Toast.makeText(getContext(), messageRes, Toast.LENGTH_LONG).show();
        });
    }

    private void clearWebViewIgneous() {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setCookie(EhUrl.HOST_EX,
                    EhCookieStore.KEY_IGNEOUS + "=; Max-Age=0; Path=/; Secure");
            cookieManager.flush();
        } catch (Throwable ignored) {
            // WebView can be unavailable; OkHttp remains the source of truth for this request.
        }
    }

    @Override
    public void onAttached() {
        super.onAttached();
        attached = true;
    }

    @Override
    public void onDetached() {
        attached = false;
        Call call = refreshCall;
        refreshCall = null;
        if (call != null) {
            call.cancel();
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDetached();
    }
}
