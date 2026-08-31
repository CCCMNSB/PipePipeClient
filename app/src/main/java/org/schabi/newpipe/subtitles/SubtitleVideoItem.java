package org.schabi.newpipe.subtitles;

import java.util.Locale;

/**
 * A single subtitle-manifest entry, identified by its YouTube video id.
 *
 * <p>The {@code title} comes from the manifest (falling back to the video id) and is used both for
 * display and for the client-side search filter. {@code listName} is the optional collection/series
 * name the entry belongs to (manifest field {@code list}); {@code author} is the optional uploader
 * name (manifest field {@code author}); either may be {@code null}.</p>
 *
 * <p>Lowercase forms of searchable fields are precomputed once in the constructor so repeated search
 * keystrokes don't re-run {@link String#toLowerCase()} per item (matching the PC plugin's approach).</p>
 */
public final class SubtitleVideoItem {
    public final String videoId;
    public final String thumbnailUrl;
    public final String title;
    /** Publish date in epoch millis, or -1 if absent/unparseable. */
    public final long dateMs;
    /** Collection/series name from the manifest, or null if the entry has none. */
    public final String listName;
    /** Uploader/author name from the manifest, or null if the entry has none. */
    public final String author;

    // Precomputed lowercase fields: reuse across every search, never re-lowercase per keystroke.
    private final String lowerTitle;
    private final String lowerId;
    private final String lowerAuthor;

    public SubtitleVideoItem(final String videoId, final String title,
                              final String thumbnailUrl) {
        this(videoId, title, thumbnailUrl, -1L, null, null);
    }

    public SubtitleVideoItem(final String videoId, final String title,
                              final String thumbnailUrl, final long dateMs) {
        this(videoId, title, thumbnailUrl, dateMs, null, null);
    }

    public SubtitleVideoItem(final String videoId, final String title,
                              final String thumbnailUrl, final long dateMs,
                              final String listName) {
        this(videoId, title, thumbnailUrl, dateMs, listName, null);
    }

    public SubtitleVideoItem(final String videoId, final String title,
                              final String thumbnailUrl, final long dateMs,
                              final String listName, final String author) {
        this.videoId = videoId;
        this.title = title;
        this.thumbnailUrl = thumbnailUrl;
        this.dateMs = dateMs;
        this.listName = listName == null || listName.isEmpty() ? null : listName.trim();
        this.author = author == null || author.trim().isEmpty() ? null : author.trim();
        this.lowerTitle = (title == null ? "" : title).toLowerCase(Locale.ROOT);
        this.lowerId = (videoId == null ? "" : videoId).toLowerCase(Locale.ROOT);
        this.lowerAuthor = this.author == null ? "" : this.author.toLowerCase(Locale.ROOT);
    }

    /** True if a real publish date is available. */
    public boolean hasDate() {
        return dateMs > 0;
    }

    /** True if a real author name is available. */
    public boolean hasAuthor() {
        return author != null;
    }

    /**
     * Case-insensitive search: title/author substring, video id exact. ID is matched exactly
     * (not a substring prefix) to avoid a single random character hitting many unrelated entries.
     */
    public boolean matches(final String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        final String q = query.trim().toLowerCase(Locale.ROOT);
        return lowerTitle.contains(q)
                || (!lowerAuthor.isEmpty() && lowerAuthor.contains(q))
                || lowerId.equals(q);
    }
}
