package org.schabi.newpipe.subtitles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A subtitle "collection" (合集): the group of manifest entries that share the same {@code list}
 * name. Collections are derived client-side from the manifest — the repository does not publish a
 * separate collection index.
 *
 * <p>Entries without a {@code list} field are grouped under {@link #UNCATEGORIZED_KEY}; that group
 * is displayed last and with the localized "uncategorized" name.</p>
 */
public final class SubtitleCollection {
    /** Grouping key for manifest entries that carry no {@code list} field. */
    public static final String UNCATEGORIZED_KEY = "";

    /** Collection name; {@link #UNCATEGORIZED_KEY} for entries without a {@code list} field. */
    public final String name;
    /** Member videos, in manifest order (newest first). */
    public final List<SubtitleVideoItem> items;
    /** Latest publish date among members, or -1 if none. */
    public final long latestDateMs;
    /** Cover: thumbnail of the newest member that has one, or null. */
    public final String coverUrl;

    public SubtitleCollection(final String name, final List<SubtitleVideoItem> items) {
        this.name = name;
        this.items = items;
        long latest = -1L;
        String cover = null;
        for (final SubtitleVideoItem item : items) {
            if (item.dateMs > latest) {
                latest = item.dateMs;
            }
            if (cover == null && item.thumbnailUrl != null) {
                cover = item.thumbnailUrl;
            }
        }
        this.latestDateMs = latest;
        this.coverUrl = cover;
    }

    /** Count of member videos. */
    public int size() {
        return items.size();
    }

    /** Case-insensitive search: matches the collection name or any member's title/id. */
    public boolean matches(final String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        if (name != null && !name.isEmpty()
                && name.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (final SubtitleVideoItem item : items) {
            if (item.matches(query)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Group manifest items by their {@code list} field.
     *
     * <p>Order: newest-updated collection first (latest member date), uncategorized last. The
     * input is assumed to already be sorted newest-first (the manifest order).</p>
     */
    public static List<SubtitleCollection> groupByCollection(final List<SubtitleVideoItem> items) {
        final Map<String, List<SubtitleVideoItem>> map = new LinkedHashMap<>();
        for (final SubtitleVideoItem item : items) {
            final String key = item.listName == null ? UNCATEGORIZED_KEY : item.listName;
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        final List<SubtitleCollection> out = new ArrayList<>(map.size());
        for (final Map.Entry<String, List<SubtitleVideoItem>> e : map.entrySet()) {
            out.add(new SubtitleCollection(e.getKey(), e.getValue()));
        }
        out.sort((a, b) -> {
            final boolean ua = a.name == null || a.name.isEmpty();
            final boolean ub = b.name == null || b.name.isEmpty();
            if (ua != ub) {
                return ua ? 1 : -1; // uncategorized always last
            }
            return Long.compare(b.latestDateMs, a.latestDateMs);
        });
        return out;
    }
}
