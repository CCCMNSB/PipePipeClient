package org.schabi.newpipe.subtitles;

/**
 * A single subtitle-manifest entry, identified by its YouTube video id.
 *
 * <p>The {@code title} comes from the manifest (falling back to the video id) and is used both for
 * display and for the client-side search filter.</p>
 */
public final class SubtitleVideoItem {
    public final String videoId;
    public final String thumbnailUrl;
    public final String title;
    /** Publish date in epoch millis, or -1 if absent/unparseable. */
    public final long dateMs;

    public SubtitleVideoItem(final String videoId, final String title,
                             final String thumbnailUrl) {
        this(videoId, title, thumbnailUrl, -1L);
    }

    public SubtitleVideoItem(final String videoId, final String title,
                             final String thumbnailUrl, final long dateMs) {
        this.videoId = videoId;
        this.title = title;
        this.thumbnailUrl = thumbnailUrl;
        this.dateMs = dateMs;
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
        final String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        return (title != null && title.toLowerCase(java.util.Locale.ROOT).contains(q))
                || videoId.toLowerCase(java.util.Locale.ROOT).contains(q);
    }
}
