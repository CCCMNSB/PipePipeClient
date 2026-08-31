package org.schabi.newpipe.subtitles;

import java.util.Locale;

/**
 * A single subtitle-manifest entry, identified by its YouTube video id.
 *
 * <p>The {@code title} comes from the manifest (falling back to the video id) and is used both for
 * display and for the client-side search filter. {@code listName} is the optional collection/series
 * name the entry belongs to (manifest field {@code list}); {@code null} means "not part of any
 * collection".</p>
 */
public final class SubtitleVideoItem {
    public final String videoId;
    public final String thumbnailUrl;
    public final String title;
    /** Publish date in epoch millis, or -1 if absent/unparseable. */
    public final long dateMs;
    /** Collection/series name from the manifest, or null if the entry has none. */
    public final String listName;

    public SubtitleVideoItem(final String videoId, final String title,
                              final String thumbnailUrl) {
        this(videoId, title, thumbnailUrl, -1L, null);
    }

    public SubtitleVideoItem(final String videoId, final String title,
                              final String thumbnailUrl, final long dateMs) {
        this(videoId, title, thumbnailUrl, dateMs, null);
    }

    public SubtitleVideoItem(final String videoId, final String title,
                              final String thumbnailUrl, final long dateMs,
                              final String listName) {
        this.videoId = videoId;
        this.title = title;
        this.thumbnailUrl = thumbnailUrl;
        this.dateMs = dateMs;
        this.listName = listName == null || listName.isEmpty() ? null : listName.trim();
    }

    /** True if a real publish date is available. */
    public boolean hasDate() {
        return dateMs > 0;
    }

    /** Case-insensitive match against the title; also matches the raw video id. */
    public boolean matches(final String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        final String q = query.trim().toLowerCase(Locale.ROOT);
        return (title != null && title.toLowerCase(Locale.ROOT).contains(q))
                || videoId.toLowerCase(Locale.ROOT).contains(q);
    }
}
