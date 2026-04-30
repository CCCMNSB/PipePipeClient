package org.schabi.newpipe.local.holder;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.local.LocalItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;

import java.time.format.DateTimeFormatter;

public class RemoteBookmarkPlaylistItemHolder extends RemotePlaylistItemHolder {
    public RemoteBookmarkPlaylistItemHolder(final LocalItemBuilder infoItemBuilder,
                                            final ViewGroup parent) {
        this(infoItemBuilder, R.layout.list_playlist_item, parent);
    }

    RemoteBookmarkPlaylistItemHolder(final LocalItemBuilder infoItemBuilder, final int layoutId,
                                     final ViewGroup parent) {
        super(infoItemBuilder, layoutId, parent);
    }

    @Override
    public void updateFromItem(final LocalItem localItem,
                               final HistoryRecordManager historyRecordManager,
                               final DateTimeFormatter dateTimeFormatter) {
        if (!(localItem instanceof PlaylistRemoteEntity)) {
            return;
        }
        final PlaylistRemoteEntity item = (PlaylistRemoteEntity) localItem;

        itemHandleView.setVisibility(View.VISIBLE);
        itemHandleView.setOnTouchListener(getOnTouchListener(item));

        super.updateFromItem(localItem, historyRecordManager, dateTimeFormatter);
    }

    private View.OnTouchListener getOnTouchListener(final PlaylistRemoteEntity item) {
        return (view, motionEvent) -> {
            view.performClick();
            if (itemBuilder != null && itemBuilder.getOnItemSelectedListener() != null
                    && motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                itemBuilder.getOnItemSelectedListener().drag(item,
                        RemoteBookmarkPlaylistItemHolder.this);
            }
            return false;
        };
    }
}
