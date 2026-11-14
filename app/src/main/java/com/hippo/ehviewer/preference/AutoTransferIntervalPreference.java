package com.hippo.ehviewer.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.reader.AutoTransferIntervalController;

public class AutoTransferIntervalPreference extends Preference {

    @Nullable
    private AutoTransferIntervalController mController;

    public AutoTransferIntervalPreference(@NonNull Context context) {
        this(context, null);
    }

    public AutoTransferIntervalPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.preferenceStyle);
    }

    public AutoTransferIntervalPreference(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutResource(R.layout.preference_auto_transfer_interval);
        setPersistent(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View control = holder.findViewById(R.id.auto_transfer_interval_control);
        mController = new AutoTransferIntervalController(control,
                Settings.getAutoTransferIntervalMillis(), millis -> {
                    if (callChangeListener(millis)) {
                        Settings.putAutoTransferIntervalMillis(millis);
                    }
                });
    }
}
