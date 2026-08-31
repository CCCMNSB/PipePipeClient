package org.schabi.newpipe.subtitles;

import android.content.Context;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the "collections" (合集) tab: one row per collection, with the newest
 * member's thumbnail as cover, the collection name, and a "N videos · latest date" meta line.
 */
public final class SubtitleCollectionAdapter
        extends RecyclerView.Adapter<SubtitleCollectionAdapter.CollectionViewHolder> {

    private final List<SubtitleCollection> items = new ArrayList<>();

    // Main-thread only (RecyclerView binding), so a shared instance is safe.
    private static final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private OnClickGesture<SubtitleCollection> listener;

    public void setItems(final List<SubtitleCollection> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(final OnClickGesture<SubtitleCollection> listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public CollectionViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                    final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_subtitle_collection, parent, false);
        return new CollectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final CollectionViewHolder holder, final int position) {
        holder.bind(items.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class CollectionViewHolder extends RecyclerView.ViewHolder {
        private final ImageView cover;
        private final TextView name;
        private final TextView meta;

        CollectionViewHolder(@NonNull final View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.subtitle_collection_cover);
            name = itemView.findViewById(R.id.subtitle_collection_name);
            meta = itemView.findViewById(R.id.subtitle_collection_meta);
        }

        void bind(final SubtitleCollection c,
                  final OnClickGesture<SubtitleCollection> listener) {
            final Context ctx = itemView.getContext();
            name.setText(c.name == null || c.name.isEmpty()
                    ? ctx.getString(R.string.subtitle_collection_uncategorized)
                    : c.name);
            final String count = ctx.getString(R.string.subtitle_collection_count_format, c.size());
            meta.setText(c.latestDateMs > 0
                    ? count + " · " + dateFormat.format(new Date(c.latestDateMs))
                    : count);
            PicassoHelper.loadScaledDownThumbnail(ctx, c.coverUrl)
                    .into(cover);
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.selected(c);
                }
            });
        }
    }
}
