package org.schabi.newpipe.subtitles;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter for the subtitle-jump list. */
public final class SubtitleRecyclerAdapter
        extends RecyclerView.Adapter<SubtitleRecyclerAdapter.SubtitleViewHolder> {

    private final List<SubtitleVideoItem> items = new ArrayList<>();
    private OnClickGesture<SubtitleVideoItem> listener;

    public void setItems(final List<SubtitleVideoItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public List<SubtitleVideoItem> getItems() {
        return items;
    }

    public void setOnItemClickListener(final OnClickGesture<SubtitleVideoItem> listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public SubtitleViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                 final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_subtitle_jump, parent, false);
        return new SubtitleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final SubtitleViewHolder holder, final int position) {
        holder.bind(items.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class SubtitleViewHolder extends RecyclerView.ViewHolder {
        private final ImageView thumbnail;
        private final TextView title;

        SubtitleViewHolder(@NonNull final View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.subtitle_jump_thumbnail);
            title = itemView.findViewById(R.id.subtitle_jump_title);
        }

        void bind(final SubtitleVideoItem item, final OnClickGesture<SubtitleVideoItem> listener) {
            title.setText(item.title == null ? item.videoId : item.title);
            PicassoHelper.loadScaledDownThumbnail(itemView.getContext(), item.thumbnailUrl)
                    .into(thumbnail);
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.selected(item);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.held(item);
                    return true;
                }
                return false;
            });
        }
    }
}
