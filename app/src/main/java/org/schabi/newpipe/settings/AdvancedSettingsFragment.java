package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.PicassoHelper;
import org.schabi.newpipe.util.ServiceHelper;

import java.io.IOException;

public class AdvancedSettingsFragment extends BasePreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
        initializeAndroidAutoPreference();
        requirePreference(R.string.download_thumbnail_key).setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    PicassoHelper.setShouldLoadImages((Boolean) newValue);
                    try {
                        PicassoHelper.clearCache(preference.getContext());
                        Toast.makeText(preference.getContext(),
                                R.string.thumbnail_cache_wipe_complete_notice, Toast.LENGTH_SHORT)
                                .show();
                    } catch (final IOException e) {
                        Log.e(TAG, "Unable to clear Picasso cache", e);
                    }
                    return true;
                });
    }
    
    private void initializeAndroidAutoPreference() {
        final SwitchPreferenceCompat androidAutoPref = findPreference(getString(R.string.disable_android_auto_key));
        if (androidAutoPref != null) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            final String key = getString(R.string.disable_android_auto_key);
            final String initKey = key + "_initialized";

            if (!prefs.contains(initKey)) {
                final boolean defaultValue = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
                prefs.edit()
                    .putBoolean(key, defaultValue)
                    .putBoolean(initKey, true)
                    .apply();
                androidAutoPref.setChecked(defaultValue);
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(getString(R.string.loading_timeout_key))) {
            ServiceHelper.initServices(this.getContext());
        } else if (key.equals(getString(R.string.disable_android_auto_key))) {
            DeviceUtils.updateAndroidAutoComponentState(requireContext());
        } else if (key.equals(getString(R.string.fetch_full_playlist_key))) {
            ServiceHelper.initServices(this.getContext());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }
}
