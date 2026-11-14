package com.hippo.ehviewer.reader;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.R;

public final class AutoTransferIntervalController {

    public interface Listener {
        void onIntervalChanged(int millis);
    }

    private final SeekBar mSeekBar;
    private final TextView mValue;
    private final TextInputLayout mInputLayout;
    private final EditText mInput;
    @Nullable
    private final Listener mListener;

    private int mMillis;
    private boolean mUpdating;

    public AutoTransferIntervalController(@NonNull View root, int initialMillis,
            @Nullable Listener listener) {
        mSeekBar = root.findViewById(R.id.auto_transfer_interval_seekbar);
        mValue = root.findViewById(R.id.auto_transfer_interval_value);
        mInputLayout = root.findViewById(R.id.auto_transfer_interval_input_layout);
        mInput = root.findViewById(R.id.auto_transfer_interval_input);
        mListener = listener;

        mSeekBar.setMax(AutoTransferInterval.MAX_PROGRESS);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !mUpdating) {
                    setMillisInternal(AutoTransferInterval.progressToMillis(progress), true, true);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (mUpdating) {
                    return;
                }
                String text = editable.toString().trim();
                if (text.isEmpty()) {
                    mInputLayout.setError(mInputLayout.getResources().getString(
                            R.string.auto_transfer_interval_error_empty));
                    return;
                }
                int millis = AutoTransferInterval.parseMillis(text);
                if (millis >= 0) {
                    mInputLayout.setError(null);
                    setMillisInternal(millis, false, true);
                } else {
                    mInputLayout.setError(mInputLayout.getResources().getString(
                            R.string.auto_transfer_interval_error_range));
                }
            }
        });
        mInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                validate();
            }
        });
        setMillisInternal(AutoTransferInterval.clamp(initialMillis), true, false);
    }

    public int getMillis() {
        return mMillis;
    }

    public boolean validate() {
        String text = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (text.isEmpty()) {
            mInputLayout.setError(mInputLayout.getResources().getString(
                    R.string.auto_transfer_interval_error_empty));
            return false;
        }
        int millis = AutoTransferInterval.parseMillis(text);
        if (millis < 0) {
            mInputLayout.setError(mInputLayout.getResources().getString(
                    R.string.auto_transfer_interval_error_range));
            return false;
        }
        mInputLayout.setError(null);
        setMillisInternal(millis, false, true);
        return true;
    }

    private void setMillisInternal(int millis, boolean updateInput, boolean notify) {
        mMillis = AutoTransferInterval.normalize(millis);
        String seconds = AutoTransferInterval.formatSeconds(mMillis);
        mUpdating = true;
        mSeekBar.setProgress(AutoTransferInterval.millisToProgress(mMillis));
        mSeekBar.setContentDescription(mSeekBar.getResources().getString(
                R.string.auto_transfer_interval_accessibility, seconds));
        mValue.setText(mValue.getResources().getString(
                R.string.auto_transfer_interval_value, seconds));
        if (updateInput) {
            mInputLayout.setError(null);
            mInput.setText(seconds);
            mInput.setSelection(mInput.length());
        }
        mUpdating = false;
        if (notify && mListener != null) {
            mListener.onIntervalChanged(mMillis);
        }
    }
}
