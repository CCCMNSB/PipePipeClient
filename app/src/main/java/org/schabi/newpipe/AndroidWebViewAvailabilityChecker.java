package org.schabi.newpipe;

import android.content.Context;
import android.content.pm.PackageInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewCompat;

import org.schabi.newpipe.extractor.WebViewAvailabilityChecker;
import org.schabi.newpipe.extractor.exceptions.WebViewUnavailableException;

public final class AndroidWebViewAvailabilityChecker implements WebViewAvailabilityChecker {
    private static final long PROBE_TIMEOUT_MS = 30_000L;
    private static final String JS_CAPABILITY_PROBE = "(function () {"
            + "try {"
            + "const checks = ["
            + "typeof Promise === 'function',"
            + "typeof Uint8Array === 'function',"
            + "typeof Function === 'function',"
            + "typeof setTimeout === 'function',"
            + "typeof globalThis === 'object',"
            + "new Function(\"return ({a:{b:1}}?.a?.b ?? 0) === 1\")()"
            + "];"
            + "return checks.every(Boolean) ? 'ok' : 'missing';"
            + "} catch (error) {"
            + "return 'error:' + error;"
            + "}"
            + "})()";

    private final SharedWebViewRuntime runtime;
    @Nullable
    private final PackageInfo packageInfo;
    @Nullable
    private volatile WebViewUnavailableException unavailableException;
    private volatile boolean runtimeProbed;

    public AndroidWebViewAvailabilityChecker(@NonNull final Context context) {
        final Context appContext = context.getApplicationContext();
        runtime = SharedWebViewRuntime.get(appContext);

        final PackageInfo currentPackageInfo = WebViewCompat.getCurrentWebViewPackage(appContext);
        packageInfo = currentPackageInfo;
        if (currentPackageInfo == null) {
            unavailableException =
                    new WebViewUnavailableException("No Android WebView provider is available");
        }
    }

    public void warmUp() {
        if (unavailableException == null) {
            runtime.warmUp(this::onInitializationFailure);
        }
    }

    @Override
    public void checkWebViewAvailable() throws WebViewUnavailableException {
        WebViewUnavailableException exception = unavailableException;
        if (exception == null && !runtimeProbed) {
            probeRuntime();
            exception = unavailableException;
        }
        if (exception != null) {
            throw exception;
        }
    }

    private void onInitializationFailure(@NonNull final Throwable throwable) {
        final PackageInfo info = packageInfo;
        if (info == null) {
            unavailableException = new WebViewUnavailableException(
                    "Android WebView provider failed to initialize",
                    throwable);
            return;
        }
        unavailableException = new WebViewUnavailableException(
                providerDescription(info) + " failed to initialize",
                throwable);
    }

    private void probeRuntime() {
        if (unavailableException != null) {
            return;
        }
        try {
            final String result = runtime.evaluateJavascriptBlocking(
                    JS_CAPABILITY_PROBE,
                    PROBE_TIMEOUT_MS,
                    "WebView JavaScript capability probe");
            if (!"\"ok\"".equals(result)) {
                unavailableException = new WebViewUnavailableException(
                        providerDescription(packageInfo)
                                + " failed JavaScript capability probe: " + result);
                return;
            }
            runtimeProbed = true;
        } catch (final Throwable throwable) {
            unavailableException = new WebViewUnavailableException(
                    providerDescription(packageInfo)
                            + " failed JavaScript capability probe",
                    throwable);
        }
    }

    @NonNull
    private static String providerDescription(@Nullable final PackageInfo info) {
        if (info == null) {
            return "Android WebView provider";
        }
        return "Android WebView provider " + info.packageName + " version " + info.versionName;
    }
}
