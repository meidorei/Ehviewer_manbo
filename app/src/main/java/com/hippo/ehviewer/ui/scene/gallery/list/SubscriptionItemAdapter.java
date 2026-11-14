package com.hippo.ehviewer.ui.scene.gallery.list;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.data.userTag.UserTag;
import com.hippo.ehviewer.client.data.userTag.UserTagList;
import com.hippo.ehviewer.subscription.UpdateBadgeFormatter;

public class SubscriptionItemAdapter extends BaseAdapter {

    private final UserTagList userTagList;

    private final LayoutInflater inflater;

    private final EhTagDatabase ehTags;

    public SubscriptionItemAdapter(Context context,UserTagList userTagList,EhTagDatabase ehTags){
        this.userTagList = userTagList;
        if (ehTags==null){
            this.ehTags = EhTagDatabase.getInstance(context);
        }else {
            this.ehTags = ehTags;
        }
        inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return userTagList.userTags.size();
    }

    @Override
    public UserTag getItem(int position) {
        return userTagList.userTags.get(position);
    }

    @Override
    public long getItemId(int position) {
        return Long.decode(getItem(position).userTagId.substring(8));
    }

    @SuppressLint("InflateParams")
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        UserTag userTag = getItem(position);
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_update_badge_list, parent, false);
        }
        TextView count = view.findViewById(R.id.update_badge_count);
        TextView indicator = view.findViewById(R.id.update_badge_indicator);
        TextView label = view.findViewById(R.id.update_badge_label);
        TextView detail = view.findViewById(R.id.update_badge_detail);

        EhTagDatabase database = ehTags != null
                ? ehTags : EhTagDatabase.getInstance(label.getContext());
        FollowLabelFormatter.Presentation presentation = FollowLabelFormatter.present(
                userTag.tagName, database,
                getNamespaceFallback(label.getContext(), userTag.tagName));
        UpdateBadgeFormatter.bind(label.getContext(), count, indicator, label, detail,
                presentation.displayName, userTag.followCount, presentation.rawQuery);
        return view;
    }

    private static String getNamespaceFallback(Context context, String rawQuery) {
        if (rawQuery == null) return null;
        int separator = rawQuery.indexOf(':');
        if (separator <= 0) return null;
        switch (rawQuery.substring(0, separator)) {
            case "artist": return context.getString(R.string.follow_namespace_artist);
            case "group": return context.getString(R.string.follow_namespace_group);
            case "parody": return context.getString(R.string.follow_namespace_parody);
            case "character": return context.getString(R.string.follow_namespace_character);
            case "female": return context.getString(R.string.follow_namespace_female);
            case "male": return context.getString(R.string.follow_namespace_male);
            case "misc": return context.getString(R.string.follow_namespace_misc);
            case "language": return context.getString(R.string.follow_namespace_language);
            case "cosplayer": return context.getString(R.string.follow_namespace_cosplayer);
            case "mixed": return context.getString(R.string.follow_namespace_mixed);
            case "other": return context.getString(R.string.follow_namespace_other);
            case "reclass": return context.getString(R.string.follow_namespace_reclass);
            default: return null;
        }
    }
}
