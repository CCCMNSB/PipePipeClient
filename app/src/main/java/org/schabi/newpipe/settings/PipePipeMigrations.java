package org.schabi.newpipe.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.HashSet;
import java.util.Set;

public final class PipePipeMigrations {
    public static final Migration MIGRATION_0_1 = new Migration(0, 1) {
        @Override
        protected void migrate(final Context context, final SharedPreferences preferences) {
            migrateLegacyListViewMode(context, preferences);
            migrateLegacyChannelTabs(context, preferences);
            migrateLegacyVideoTabs(context, preferences);
        }
    };

    private static final Migration[] PIPEPIPE_MIGRATIONS = {
            MIGRATION_0_1,
    };

    public static final int VERSION = 1;

    public static void initMigrations(final Context context, final boolean isFirstRun) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String versionKey = context.getString(R.string.last_used_pipepipe_preferences_version);
        final int lastVersion = preferences.getInt(versionKey, 0);

        if (isFirstRun) {
            preferences.edit().putInt(versionKey, VERSION).apply();
            return;
        } else if (lastVersion == VERSION) {
            return;
        }

        int currentVersion = lastVersion;
        for (final Migration migration : PIPEPIPE_MIGRATIONS) {
            if (migration.shouldMigrate(currentVersion)) {
                migration.migrate(context, preferences);
                currentVersion = migration.newVersion;
            }
        }

        preferences.edit().putInt(versionKey, currentVersion).apply();
    }

    public static void migrateLegacyChannelTabs(final Context context,
                                                final SharedPreferences preferences) {
        final String key = context.getString(R.string.show_channel_tabs_key);
        final Set<String> enabledTabs = preferences.getStringSet(key, null);
        if (enabledTabs == null || !enabledTabs.contains("show_channel_tabs_livestreams")) {
            return;
        }

        final Set<String> newSet = new HashSet<>(enabledTabs);
        newSet.remove("show_channel_tabs_livestreams");
        newSet.add(context.getString(R.string.show_channel_tabs_livestreams));
        preferences.edit().putStringSet(key, newSet).apply();
    }

    private static void migrateLegacyVideoTabs(final Context context,
                                               final SharedPreferences preferences) {
        final String key = context.getString(R.string.video_tabs_key);
        if (preferences.contains(key)) {
            return;
        }

        final Set<String> tabs = new HashSet<>();
        if (preferences.getBoolean(context.getString(R.string.show_comments_key), true)) {
            tabs.add("comments");
        }
        if (preferences.getBoolean(context.getString(R.string.show_next_video_key), true)) {
            tabs.add("related");
        }
        if (preferences.getBoolean(context.getString(R.string.show_description_key), true)) {
            tabs.add("description");
        }
        if (preferences.getBoolean(context.getString(R.string.sponsor_block_enable_key), false)) {
            tabs.add("sponsorblock");
        }

        preferences.edit().putStringSet(key, tabs).apply();
    }

    private static void migrateLegacyListViewMode(final Context context,
                                                  final SharedPreferences preferences) {
        final String migrationKey = context.getString(R.string.list_view_mode_migrated_key);
        if (preferences.getBoolean(migrationKey, false)) {
            return;
        }

        final String listMode = preferences.getString(context.getString(R.string.list_view_mode_key),
                context.getString(R.string.list_view_mode_value));
        final SharedPreferences.Editor editor = preferences.edit();

        final boolean isAuto = listMode.equals(context.getString(R.string.list_view_mode_auto_key));
        final boolean useGrid;
        if (listMode.equals(context.getString(R.string.list_view_mode_grid_key))) {
            useGrid = true;
            editor.putString(context.getString(R.string.grid_columns_key), "2");
            editor.putString(context.getString(R.string.grid_columns_landscape_key), "4");
        } else if (listMode.equals(context.getString(R.string.list_view_mode_large_grid_key))) {
            useGrid = true;
            editor.putString(context.getString(R.string.grid_columns_key), "1");
            editor.putString(context.getString(R.string.grid_columns_landscape_key), "2");
        } else if (listMode.equals(context.getString(R.string.list_view_mode_card_key))) {
            useGrid = false;
            editor.putBoolean(context.getString(R.string.card_mode_enabled_key), true);
        } else if (isAuto) {
            final Configuration configuration = context.getResources().getConfiguration();
            final boolean autoUseGrid = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    && configuration.isLayoutSizeAtLeast(Configuration.SCREENLAYOUT_SIZE_LARGE);
            if (autoUseGrid) {
                editor.putString(context.getString(R.string.grid_columns_key), "2");
                editor.putString(context.getString(R.string.grid_columns_landscape_key), "4");
            }
            useGrid = autoUseGrid;
        } else {
            useGrid = false;
        }

        editor.putBoolean(context.getString(R.string.grid_layout_enabled_key), useGrid);
        if (!preferences.contains(context.getString(R.string.grid_columns_key))) {
            editor.putString(context.getString(R.string.grid_columns_key), "2");
        }
        if (!preferences.contains(context.getString(R.string.grid_columns_landscape_key))) {
            editor.putString(context.getString(R.string.grid_columns_landscape_key), "4");
        }
        if (!preferences.contains(context.getString(R.string.card_mode_enabled_key))) {
            editor.putBoolean(context.getString(R.string.card_mode_enabled_key), false);
        }
        editor.putBoolean(migrationKey, true).apply();
    }

    private PipePipeMigrations() { }

    abstract static class Migration {
        public final int oldVersion;
        public final int newVersion;

        protected Migration(final int oldVersion, final int newVersion) {
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
        }

        private boolean shouldMigrate(final int currentVersion) {
            return oldVersion >= currentVersion;
        }

        protected abstract void migrate(Context context, SharedPreferences preferences);
    }
}
