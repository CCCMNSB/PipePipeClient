package org.schabi.newpipe.player.subtitles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives the {@link Intent#ACTION_DELETE} broadcast (sent when this app is
 * being uninstalled) and clears all cached subtitle/danmaku files so they don't
 * linger in external storage or get restored by backup tools.
 */
public class SubtitleCacheCleanupReceiver extends BroadcastReceiver {
    private static final String TAG = "SubtitleCacheCleanup";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (Intent.ACTION_DELETE.equals(intent.getAction())) {
            Log.d(TAG, "App uninstalled — clearing subtitle cache");
            SubtitleCache.clearAll(context);
        }
    }
}
