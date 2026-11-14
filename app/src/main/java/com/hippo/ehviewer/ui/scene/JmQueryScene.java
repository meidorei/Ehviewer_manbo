package com.hippo.ehviewer.ui.scene;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.UrlOpener;
import com.hippo.ehviewer.jm.JmAlbumInfo;
import com.hippo.ehviewer.jm.JmClient;
import com.hippo.ehviewer.jm.JmIdParser;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class JmQueryScene extends ToolbarScene implements View.OnClickListener,
        TextView.OnEditorActionListener {
    private static final String STATE_INPUT = "jm_input";
    private static final String STATE_RESULT = "jm_result";

    private final AtomicInteger generation = new AtomicInteger();
    private ExecutorService executor;
    private Future<?> queryFuture;
    private Future<?> coverFuture;
    private Future<?> webFuture;
    private JmClient queryClient;
    private JmClient coverClient;
    private JmClient webClient;

    private String savedInput = "";
    private JmAlbumInfo albumInfo;

    private EditText input;
    private Button queryButton;
    private ProgressBar progress;
    private TextView error;
    private View result;
    private ImageView cover;
    private TextView title;
    private TextView number;
    private TextView metadata;
    private TextView description;
    private TextView chapters;
    private Button copy;
    private Button open;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getEHContext();
        if (context != null) {
            executor = EhApplication.getExecutorService(context);
        }
        if (savedInstanceState != null) {
            savedInput = savedInstanceState.getString(STATE_INPUT, "");
            Object saved = savedInstanceState.getSerializable(STATE_RESULT);
            if (saved instanceof JmAlbumInfo) {
                albumInfo = (JmAlbumInfo) saved;
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_jm_query, container, false);
        input = view.findViewById(R.id.jm_input);
        queryButton = view.findViewById(R.id.jm_query_button);
        progress = view.findViewById(R.id.jm_progress);
        error = view.findViewById(R.id.jm_error);
        result = view.findViewById(R.id.jm_result);
        cover = view.findViewById(R.id.jm_cover);
        title = view.findViewById(R.id.jm_title);
        number = view.findViewById(R.id.jm_number);
        metadata = view.findViewById(R.id.jm_metadata);
        description = view.findViewById(R.id.jm_description);
        chapters = view.findViewById(R.id.jm_chapters);
        copy = view.findViewById(R.id.jm_copy);
        open = view.findViewById(R.id.jm_open);

        input.setText(savedInput);
        input.setSelection(input.length());
        input.setOnEditorActionListener(this);
        queryButton.setOnClickListener(this);
        copy.setOnClickListener(this);
        open.setOnClickListener(this);
        if (albumInfo != null) {
            bindResult(albumInfo);
            loadCover(albumInfo.albumId);
        }
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.jm_query_title);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_INPUT, input == null ? savedInput : input.getText().toString());
        outState.putSerializable(STATE_RESULT, albumInfo);
    }

    @Override
    public void onDestroyView() {
        savedInput = input == null ? savedInput : input.getText().toString();
        cancelRequests();
        input = null;
        queryButton = null;
        progress = null;
        error = null;
        result = null;
        cover = null;
        title = null;
        number = null;
        metadata = null;
        description = null;
        chapters = null;
        copy = null;
        open = null;
        super.onDestroyView();
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public void onClick(View view) {
        if (view == queryButton) {
            query();
        } else if (view == copy) {
            copyTitle();
        } else if (view == open) {
            openWeb();
        }
    }

    @Override
    public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_SEARCH
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            query();
            return true;
        }
        return false;
    }

    private void query() {
        Context context = getEHContext();
        if (context == null || input == null || executor == null) {
            return;
        }
        String albumId;
        try {
            albumId = JmIdParser.parse(input.getText().toString());
        } catch (IllegalArgumentException e) {
            input.setError(getString(R.string.jm_invalid_number));
            return;
        }
        input.setError(null);
        cancelRequests();
        int requestGeneration = generation.incrementAndGet();
        setLoading(true);
        albumInfo = null;
        result.setVisibility(View.GONE);
        error.setVisibility(View.GONE);

        queryClient = JmClient.create(context);
        queryFuture = executor.submit(() -> {
            try {
                JmAlbumInfo info = queryClient.query(albumId);
                postIfCurrent(requestGeneration, () -> {
                    albumInfo = info;
                    setLoading(false);
                    bindResult(info);
                    loadCover(info.albumId);
                });
            } catch (Exception throwable) {
                postIfCurrent(requestGeneration, () -> showError(throwable));
            }
        });
    }

    private void loadCover(String albumId) {
        Context context = getEHContext();
        if (context == null || executor == null || cover == null) {
            return;
        }
        if (coverFuture != null) {
            coverFuture.cancel(true);
        }
        if (coverClient != null) {
            coverClient.cancel();
        }
        cover.setImageResource(R.drawable.image_failed);
        int requestGeneration = generation.get();
        coverClient = JmClient.create(context);
        coverFuture = executor.submit(() -> {
            try {
                Bitmap bitmap = coverClient.loadCover(albumId);
                postIfCurrent(requestGeneration, () -> {
                    if (cover != null) {
                        cover.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception ignored) {
                // Keep the placeholder when every image CDN fails.
            }
        });
    }

    private void bindResult(JmAlbumInfo info) {
        if (result == null) {
            return;
        }
        result.setVisibility(View.VISIBLE);
        title.setText(info.name);
        number.setText(getString(R.string.jm_number_format, info.albumId));

        StringBuilder details = new StringBuilder();
        append(details, getString(R.string.jm_authors), info.authors);
        append(details, getString(R.string.jm_tags), info.tags);
        append(details, getString(R.string.jm_works), info.works);
        append(details, getString(R.string.jm_actors), info.actors);
        append(details, getString(R.string.jm_page_count), info.pageCount);
        append(details, getString(R.string.jm_views), info.views);
        append(details, getString(R.string.jm_likes), info.likes);
        append(details, getString(R.string.jm_comments), info.commentCount);
        if (info.partial) {
            append(details, getString(R.string.jm_data_source),
                    getString(R.string.jm_partial_result));
        }
        metadata.setText(details);

        description.setText(info.description);
        description.setVisibility(TextUtils.isEmpty(info.description)
                ? View.GONE : View.VISIBLE);
        chapters.setText(getString(R.string.jm_chapters_format,
                join(info.chapters, "\n")));
    }

    private void copyTitle() {
        Context context = getEHContext();
        if (context == null || albumInfo == null) {
            return;
        }
        ClipboardManager manager =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.jm_query_title), albumInfo.name));
            Toast.makeText(context, R.string.jm_title_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void openWeb() {
        Context context = getEHContext();
        if (context == null || albumInfo == null || executor == null) {
            return;
        }
        if (!TextUtils.isEmpty(albumInfo.webUrl)) {
            UrlOpener.openUrl(context, albumInfo.webUrl, false);
            return;
        }
        if (webFuture != null && !webFuture.isDone()) {
            return;
        }
        open.setEnabled(false);
        int requestGeneration = generation.get();
        webClient = JmClient.create(context);
        String albumId = albumInfo.albumId;
        webFuture = executor.submit(() -> {
            try {
                String url = webClient.discoverAlbumWebUrl(albumId);
                postIfCurrent(requestGeneration, () -> {
                    if (open != null) {
                        open.setEnabled(true);
                    }
                    Context currentContext = getEHContext();
                    if (currentContext != null) {
                        UrlOpener.openUrl(currentContext, url, false);
                    }
                });
            } catch (Exception throwable) {
                postIfCurrent(requestGeneration, () -> {
                    if (open != null) {
                        open.setEnabled(true);
                    }
                    showTip(getString(R.string.jm_web_unavailable), LENGTH_SHORT);
                });
            }
        });
    }

    private void showError(Throwable throwable) {
        setLoading(false);
        if (error == null) {
            return;
        }
        String message;
        if (throwable instanceof JmClient.NotFoundException) {
            message = getString(R.string.jm_not_found);
        } else if (throwable instanceof InterruptedIOException
                || throwable instanceof InterruptedException) {
            return;
        } else {
            message = getString(R.string.jm_service_unavailable);
        }
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        if (progress != null) {
            progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (queryButton != null) {
            queryButton.setEnabled(!loading);
        }
    }

    private void postIfCurrent(int requestGeneration, Runnable runnable) {
        FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (generation.get() == requestGeneration && getView() != null) {
                runnable.run();
            }
        });
    }

    private void cancelRequests() {
        generation.incrementAndGet();
        cancel(queryFuture, queryClient);
        cancel(coverFuture, coverClient);
        cancel(webFuture, webClient);
        queryFuture = null;
        coverFuture = null;
        webFuture = null;
        queryClient = null;
        coverClient = null;
        webClient = null;
    }

    private static void cancel(Future<?> future, JmClient client) {
        if (client != null) {
            client.cancel();
        }
        if (future != null) {
            future.cancel(true);
        }
    }

    private static void append(StringBuilder builder, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            append(builder, label, join(values, "、"));
        }
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(label).append("：").append(value);
    }

    private static String join(List<String> values, String separator) {
        return TextUtils.join(separator, values);
    }
}
