package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.SeekBarPreference;
import org.schabi.newpipe.R;

import java.util.List;

public class BulletCommentsSettingsFragment extends BasePreferenceFragment {

    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        listener = (sharedPreferences, s) -> {
            // add listeners to show the current float duration of regular bullet comments and top_bottom bullet comments
            if (s.equals(getString(R.string.top_bottom_bullet_comments_duration_key))){
                final int newSetting = sharedPreferences.getInt(s, 8);
                final SeekBarPreference topBottomBulletCommentsDuration = findPreference(s);
                assert topBottomBulletCommentsDuration != null;
                topBottomBulletCommentsDuration.setSummary(newSetting + " seconds");
            }
            else if (s.equals(getString(R.string.regular_bullet_comments_duration_key))){
                final int newSetting = sharedPreferences.getInt(s, 8);
                final SeekBarPreference regularBulletCommentsDuration = findPreference(s);
                assert regularBulletCommentsDuration != null;
                regularBulletCommentsDuration.setSummary(newSetting + " seconds");
            }
            // Add listeners for the rows preferences
            else if (s.equals(getString(R.string.max_bullet_comments_rows_top_key)) ||
                    s.equals(getString(R.string.max_bullet_comments_rows_bottom_key)) ||
                    s.equals(getString(R.string.max_bullet_comments_rows_regular_key))) {
                final int newSetting = sharedPreferences.getInt(s, 15);
                final SeekBarPreference rowsPref = findPreference(s);
                if (rowsPref != null) {
                    rowsPref.setSummary(String.valueOf(newSetting));
                }
            }
        };

        // Initialize min values for duration preferences
        final SeekBarPreference regularBulletCommentsDuration = findPreference(getString(R.string.regular_bullet_comments_duration_key));
        assert regularBulletCommentsDuration != null;
        regularBulletCommentsDuration.setMin(5);
        final SeekBarPreference topBottomBulletCommentsDuration = findPreference(getString(R.string.top_bottom_bullet_comments_duration_key));
        assert topBottomBulletCommentsDuration != null;
        topBottomBulletCommentsDuration.setMin(5);

        // Initialize summaries for rows preferences
        SeekBarPreference topRowsPref = findPreference(getString(R.string.max_bullet_comments_rows_top_key));
        SeekBarPreference bottomRowsPref = findPreference(getString(R.string.max_bullet_comments_rows_bottom_key));
        SeekBarPreference regularRowsPref = findPreference(getString(R.string.max_bullet_comments_rows_regular_key));

        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (topRowsPref != null) {
            topRowsPref.setSummary(String.valueOf(sharedPreferences.getInt(
                    getString(R.string.max_bullet_comments_rows_top_key), 15)));
        }
        if (bottomRowsPref != null) {
            bottomRowsPref.setSummary(String.valueOf(sharedPreferences.getInt(
                    getString(R.string.max_bullet_comments_rows_bottom_key), 15)));
        }
        if (regularRowsPref != null) {
            regularRowsPref.setSummary(String.valueOf(sharedPreferences.getInt(
                    getString(R.string.max_bullet_comments_rows_regular_key), 15)));
        }

        // Danmaku cache management: keep the summary fresh and open the manager dialog on click.
        refreshCacheSummary();
        final androidx.preference.Preference cachePref =
                findPreference(getString(R.string.danmaku_cache_key));
        if (cachePref != null) {
            cachePref.setOnPreferenceClickListener(preference -> {
                openCacheManager();
                return true;
            });
        }
    }

    private void refreshCacheSummary() {
        final androidx.preference.Preference cachePref =
                findPreference(getString(R.string.danmaku_cache_key));
        if (cachePref == null) {
            return;
        }
        final List<org.schabi.newpipe.player.bulletComments.DanmakuCache.CacheEntry> entries =
                org.schabi.newpipe.player.bulletComments.DanmakuCache.list(getContext());
        final long total = org.schabi.newpipe.player.bulletComments.DanmakuCache.totalSize(getContext());
        cachePref.setSummary(entries.isEmpty()
                ? getString(R.string.danmaku_cache_manage_empty)
                : getString(R.string.danmaku_cache_summary_count,
                entries.size(), humanSize(total)));
    }

    private void openCacheManager() {
        final List<org.schabi.newpipe.player.bulletComments.DanmakuCache.CacheEntry> entries =
                org.schabi.newpipe.player.bulletComments.DanmakuCache.list(getContext());
        if (entries == null || entries.isEmpty()) {
            new androidx.appcompat.app.AlertDialog.Builder(getContext())
                    .setTitle(R.string.danmaku_cache_manage_title)
                    .setMessage(R.string.danmaku_cache_manage_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            final org.schabi.newpipe.player.bulletComments.DanmakuCache.CacheEntry e = entries.get(i);
            labels[i] = shortUrl(e.url) + " · " + e.count + " 条 · " + humanSize(e.sizeBytes);
        }
        final boolean[] checked = new boolean[entries.size()];
        final android.content.Context ctx = getContext();
        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.danmaku_cache_manage_title)
                        + " (" + humanSize(org.schabi.newpipe.player.bulletComments.DanmakuCache.totalSize(ctx)) + ")")
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.danmaku_cache_delete_selected, (d, w) -> {
                    for (int i = 0; i < entries.size(); i++) {
                        if (checked[i]) {
                            org.schabi.newpipe.player.bulletComments.DanmakuCache.delete(ctx, entries.get(i).key);
                        }
                    }
                    refreshCacheSummary();
                })
                .setNegativeButton(R.string.danmaku_cache_clear_all, (d, w) -> {
                    org.schabi.newpipe.player.bulletComments.DanmakuCache.clearAll(ctx);
                    refreshCacheSummary();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private static String humanSize(final long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static String shortUrl(final String url) {
        if (url == null || url.isEmpty()) {
            return "(unknown)";
        }
        final int v = url.indexOf("v=");
        if (v >= 0) {
            final String id = url.substring(v + 2);
            final int amp = id.indexOf('&');
            return amp >= 0 ? id.substring(0, amp) : id;
        }
        final int slash = url.lastIndexOf('/');
        if (slash >= 0 && slash < url.length() - 1) {
            return url.substring(slash + 1);
        }
        return url.length() > 40 ? url.substring(0, 40) + "…" : url;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCacheSummary();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(listener);
    }
}