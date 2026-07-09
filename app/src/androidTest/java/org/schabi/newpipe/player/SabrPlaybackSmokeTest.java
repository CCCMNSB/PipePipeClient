package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.accessibility.AccessibilityEvent;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.App;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.downloader.StreamingResponse;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrRequestDumper;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrResponseDecoder;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.datasource.SabrDashMediaSource;
import org.schabi.newpipe.player.datasource.SabrSegmentDataSource;
import org.schabi.newpipe.player.helper.LegacySubtitleRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.resolver.QualityResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.player.datasource.SabrSessionStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/**
 * Online smoke test for the production Extractor -> SABR MediaSource -> Media3 pipeline.
 *
 * <p>Run only this test with:</p>
 * <pre>
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * org.schabi.newpipe.player.SabrPlaybackSmokeTest \
 *   -Pandroid.testInstrumentationRunnerArguments.url=\
 * https://www.youtube.com/watch?v=G-eNlqqkn1w
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class SabrPlaybackSmokeTest {
    private static final int SMOKE_AUDIO_ITAG = 140;
    private static final int SMOKE_VIDEO_ITAG = 248;
    private static final int PROTO_WIRE_VARINT = 0;
    private static final int PROTO_WIRE_LENGTH_DELIMITED = 2;
    private static final String DEFAULT_URL =
            "https://www.youtube.com/watch?v=G-eNlqqkn1w";
    private static final int DEFAULT_MAX_VIDEO_HEIGHT = 720;
    private static final long DEFAULT_SEEK_POSITION_MS = (49 * 60 + 55) * 1000L;
    private static final long DEFAULT_LINEAR_PLAYBACK_MS = 3_000;
    private static final long DEFAULT_POST_SEEK_PLAYBACK_MS = 30_000;
    private static final long DEFAULT_POST_REWIND_PLAYBACK_MS = 30_000;
    private static final long PREPARE_TIMEOUT_SECONDS = 150;
    private static final long PLAYBACK_TIMEOUT_SECONDS = 75;

    @Test
    public void extractorToMedia3PlaysAndSeeks() throws Exception {
        runSmokeCase(SmokeCase.playback());
    }

    @Test
    public void recoversMissingInitializationFromPump() throws Exception {
        runSmokeCase(SmokeCase.missingInitialization());
    }

    @Test
    public void recoversEvictedSegmentRewind() throws Exception {
        runSmokeCase(SmokeCase.evictedRewind());
    }

    @Test
    public void boundsReadAheadForStalledReader() throws Exception {
        runSmokeCase(SmokeCase.stalledReader());
    }

    @Test
    public void rewindClearsBufferedStateAndCookie() throws Exception {
        runSmokeCase(SmokeCase.rewindState());
    }

    @Test
    public void seekToStreamEndReachesEnded() throws Exception {
        runSmokeCase(SmokeCase.endSeek());
    }

    @Test
    public void demandBackoffDoesNotBlockLoaderUntilServerDelay() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(30_000))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final long elapsedMs = harness.openMediaSegment(
                    SabrSegmentRequest.media(harness.videoFormat, 2), 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Demand path did not request the target segment: " + trace,
                    trace.contains("pump_demand itag=" + SMOKE_VIDEO_ITAG + " seq=2"));
            assertTrue("Demand path honored a long SABR backoff while loader waited: " + trace,
                    trace.contains("skip_backoff waitTarget backoffMs=30000"));
            assertTrue("Loader waited too long for demand retry: elapsedMs=" + elapsedMs
                    + " trace=" + trace, elapsedMs < 5_000);
        }
    }

    @Test
    public void initializationPumpKeepsMidStartTarget() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.setPlayerTimeMs(300_000);
            harness.downloader.enqueue(new UmpFixture()
                    .initSegment(1, SMOKE_VIDEO_ITAG)
                    .bytes());

            final SabrSegmentRequest request =
                    SabrSegmentRequest.initialization(harness.videoFormat);
            harness.openSegment(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Initialization pump did not anchor the target: " + trace,
                    trace.contains("pump_initialization_target itag=" + SMOKE_VIDEO_ITAG));
            assertTrue("No SABR request body was captured",
                    !harness.downloader.requestBodies.isEmpty());
            final String requestSummary = SabrRequestDumper.summarize(
                    harness.downloader.requestBodies.get(0));
            assertTrue("Initial SABR request did not keep player time: " + requestSummary,
                    requestSummary.contains("playerTimeMs=300000"));
            assertTrue("Initial SABR request did not report target time: " + requestSummary,
                    requestSummary.contains("topPlayerTimeMs=300000"));
        }
    }

    @Test
    public void initializationPrefetchMissingUrlDoesNotFailSourceCreation() throws Exception {
        final YoutubeSabrFormat audioFormat = smokeFormat(
                SMOKE_AUDIO_ITAG, true, null, -1, -1);
        final YoutubeSabrFormat videoFormat = smokeFormat(
                SMOKE_VIDEO_ITAG, false, null, -1, -1);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create(audioFormat, videoFormat)) {
            new SabrDashMediaSource(new MediaItem.Builder()
                    .setUri(Uri.parse("sabr://smoke-video"))
                    .build(), harness.holder, new Localization("en", "US"));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Missing init URL did not fall back for audio: " + trace,
                    trace.contains("initialization_prefetch_skip itag=" + SMOKE_AUDIO_ITAG));
            assertTrue("Missing init URL did not fall back for video: " + trace,
                    trace.contains("initialization_prefetch_skip itag=" + SMOKE_VIDEO_ITAG));
        }
    }

    @Test
    public void demandIncompleteMediaResponseRetriesThroughPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .media(2)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(3, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegment(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Incomplete media response was not exercised: " + trace,
                    trace.contains("missing-media-end:2"));
            assertNotNull("Demand retry did not fetch the target segment: " + trace,
                    harness.holder.session.getCachedSegment(request));
        }
    }

    @Test
    public void demandRecoverableIntegrityShapesRetryThroughPump() throws Exception {
        verifyDemandIntegrityRetry("length-mismatch:2", new UmpFixture()
                .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000, 4)
                .media(2, new byte[]{10, 11})
                .mediaEnd(2));
        verifyDemandIntegrityRetry("missing-media:2", new UmpFixture()
                .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                .mediaEnd(2));
        verifyDemandIntegrityRetry("media-without-header:2", new UmpFixture()
                .media(2)
                .mediaEnd(2));
        verifyDemandIntegrityRetry("media-end-without-header:2", new UmpFixture()
                .mediaEnd(2));
    }

    @Test
    public void malformedControlPartDoesNotDropMediaInPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 1);
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, new byte[]{0x0f})
                    .segment(1, SMOKE_VIDEO_ITAG, 1)
                    .bytes());

            assertEquals(1, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertNotNull("Malformed control part caused media to be dropped: " + trace,
                    harness.holder.session.getCachedSegment(request));
            assertTrue("Malformed control part was not exercised: " + trace,
                    trace.contains("malformedParts=[35:1:"));
        }
    }

    @Test
    public void malformedMediaHeaderRetriesThroughDemandPump() throws Exception {
        verifyDemandIntegrityRetry("media-without-header:2", new UmpFixture()
                .part(SabrResponseDecoder.MEDIA_HEADER, new byte[]{0x0f})
                .media(2)
                .mediaEnd(2));
    }

    @Test
    public void duplicateMediaHeaderFailsThroughDemandPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 3, 35_000, 5_000)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegmentExpectFailure(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Duplicate media header was not exercised: " + trace,
                    trace.contains("duplicate-media-header:2"));
        }
    }

    @Test
    public void demandProtectedNoMediaBackoffDoesNotBlockLoader() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.STREAM_PROTECTION_STATUS, streamProtection(3, 7))
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(30_000))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final long elapsedMs = harness.openMediaSegment(
                    SabrSegmentRequest.media(harness.videoFormat, 2), 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Protected no-media response was not exercised: " + trace,
                    trace.contains("protection=3/7"));
            assertTrue("Protected no-media backoff blocked demand retry: " + trace,
                    trace.contains("skip_backoff waitTarget backoffMs=30000"));
            assertTrue("Loader waited too long after protected no-media: elapsedMs=" + elapsedMs,
                    elapsedMs < 5_000);
        }
    }

    @Test
    public void requestPolicyLiveAndInitializationMetadataUpdateSessionState()
            throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY,
                            nextRequestPolicy(2_000, playbackCookie(), "smoke-video"))
                    .part(SabrResponseDecoder.LIVE_METADATA,
                            liveMetadata(40, 200_000, true))
                    .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                            initializationMetadata(SMOKE_VIDEO_ITAG, 60, 300_000, "video/webm"))
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertNotNull("Next request policy was not applied: " + trace,
                    harness.holder.session.getStreamState().getNextRequestPolicy());
            assertEquals("Policy backoff was not applied", 2_000,
                    harness.holder.session.getStreamState()
                            .getNextRequestPolicy().getBackoffTimeMs());
            assertNotNull("Playback cookie was not applied: " + trace,
                    harness.holder.session.getStreamState().getPlaybackCookie());
            assertTrue("Live metadata was not applied: " + trace,
                    harness.holder.session.getStreamState().isLive());
            assertTrue("Post-live DVR flag was not applied: " + trace,
                    harness.holder.session.getStreamState().isPostLiveDvr());
            assertEquals("Initialization metadata did not set end segment",
                    60, harness.holder.session.getStreamState()
                            .getEndSegment(harness.videoFormat));
            assertEquals("Initialization metadata did not derive segment time",
                    50_000, harness.holder.session.getStreamState()
                            .getSegmentStartMs(harness.videoFormat, 11));
        }
    }

    @Test
    public void redirectUpdatesFollowUpSabrStreamingUrl() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_REDIRECT, redirect("https://redirect.test/sabr"))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1)
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));
            assertEquals(1, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            assertTrue("First SABR request did not use original URL: "
                            + harness.downloader.requestedUrls,
                    harness.downloader.requestedUrls.get(0).contains("https://sabr.test"));
            assertTrue("Follow-up SABR request did not use redirect URL: "
                            + harness.downloader.requestedUrls,
                    harness.downloader.requestedUrls.get(1).contains("https://redirect.test/sabr"));
        }
    }

    @Test
    public void sabrErrorFailsThroughPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_ERROR, sabrError("blocked", 403))
                    .bytes());

            try {
                harness.holder.session.pumpOnceStreaming(new Localization("en", "US"));
            } catch (final Exception expected) {
                final String trace = harness.holder.session.getDiagnosticTrace();
                assertTrue("SABR error details were not decoded: " + trace,
                        trace.contains("type=blocked, code=403"));
                return;
            }
            throw new AssertionError("SABR error response did not fail the pump");
        }
    }

    @Test
    public void reloadPlayerResponseFailsBoundedThroughPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.RELOAD_PLAYER_RESPONSE,
                            reloadPlayerResponse("reload-token"))
                    .bytes());

            try {
                harness.holder.session.pumpOnceStreaming(new Localization("en", "US"));
            } catch (final Exception expected) {
                final String trace = harness.holder.session.getDiagnosticTrace();
                assertTrue("Reload player response was not decoded: " + trace,
                        trace.contains("46=[reloadPlaybackParamsTokenLength=12]"));
                assertTrue("Reload response did not mark no-media reload state: " + trace,
                        trace.contains("reload=true"));
                return;
            }
            throw new AssertionError("SABR reload response unexpectedly succeeded");
        }
    }

    @Test
    public void unknownAndGenericControlsRemainDiagnosticsInPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(99, proto().u64(1, 7).bytes())
                    .part(SabrResponseDecoder.CONFIG, proto().u64(2, 9).bytes())
                    .part(SabrResponseDecoder.REQUEST_IDENTIFIER,
                            requestIdentifier("request-token"))
                    .part(SabrResponseDecoder.SNACKBAR_MESSAGE, snackbar(12))
                    .part(SabrResponseDecoder.REQUEST_CANCELLATION_POLICY, cancellationPolicy())
                    .part(SabrResponseDecoder.PREWARM_CONNECTION, prewarmConnection())
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Unknown part was not retained for diagnostics: " + trace,
                    trace.contains("unknownParts=[99]"));
            assertTrue("CONFIG control was not summarized: " + trace,
                    trace.contains("30=[2=9]"));
            assertTrue("Request identifier was not summarized: " + trace,
                    trace.contains("52=[tokenLength=13]"));
            assertTrue("Snackbar was not summarized: " + trace,
                    trace.contains("67=[id=12]"));
            assertTrue("Cancellation policy was not summarized: " + trace,
                    trace.contains("53=[field1=1"));
            assertTrue("Prewarm connection was not summarized: " + trace,
                    trace.contains("65=[connections=1["));
        }
    }

    @Test
    public void advancedControlsRemainDiagnosticsInPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_SEEK, sabrSeek(45_000, 1000, 2))
                    .part(SabrResponseDecoder.PLAYBACK_START_POLICY, playbackStartPolicy())
                    .part(SabrResponseDecoder.FORMAT_SELECTION_CONFIG, formatSelectionConfig())
                    .part(SabrResponseDecoder.SELECTABLE_FORMATS, selectableFormats())
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("SABR seek control was not summarized: " + trace,
                    trace.contains("45=[seek=45000/1000, source=2]"));
            assertTrue("Playback start policy was not summarized: " + trace,
                    trace.contains("47=[start=1[1500ms/100000Bps]"));
            assertTrue("Format selection config was not summarized: " + trace,
                    trace.contains("37=[itags=2[248,140]"));
            assertTrue("Selectable formats were not summarized: " + trace,
                    trace.contains("51=[video=1[itag:248+lm+xtags]"));
        }
    }

    @Test
    public void onesieControlsRemainDiagnosticsInPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.ONESIE_HEADER, onesieHeader(0, 1, false))
                    .part(SabrResponseDecoder.ONESIE_DATA, onesieInnertubeResponse())
                    .part(SabrResponseDecoder.ONESIE_HEADER, onesieHeader(25, 2, true))
                    .part(SabrResponseDecoder.ONESIE_ENCRYPTED_MEDIA, new byte[]{1, 2, 3})
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Clear onesie header was not summarized: " + trace,
                    trace.contains("10=[type=0/ONESIE_PLAYER_RESPONSE"));
            assertTrue("Clear onesie data was not associated with the header: " + trace,
                    trace.contains("11=[encrypted=false, payloadBytes="));
            assertTrue("Innertube payload was not decoded: " + trace,
                    trace.contains("innertubeResponse=[proxyStatus=1, httpStatus=200"));
            assertTrue("Encrypted onesie data was not summarized: " + trace,
                    trace.contains("12=[encrypted=true, payloadBytes=3"));
        }
    }

    @Test
    public void contextKeepExistingAndDiscardUpdateSessionState() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(30, new byte[]{1}, true, 1))
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(30, new byte[]{2}, false, 2))
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(40, new byte[]{3}, true, 1))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_CONTEXT_SENDING_POLICY,
                            contextPolicy(new int[0], new int[0], new int[]{40}))
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));
            assertTrue("Context 30 should be active after first update",
                    activeContextTypes(harness).contains(30));
            assertTrue("KEEP_EXISTING should not make context 30 unsent",
                    !unsentContextTypes(harness).contains(30));

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Discard policy was not decoded: " + trace,
                    trace.contains("59=[start=[], stop=[], discard=[40]]"));
            assertTrue("Context 40 was not discarded",
                    !activeContextTypes(harness).contains(40)
                            && !unsentContextTypes(harness).contains(40));
        }
    }

    @Test
    public void compressedMediaSegmentCachesDecompressedBytesThroughDemandPump()
            throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            final byte[] raw = new byte[]{40, 41, 42, 43, 44};
            final byte[] gzipped = gzip(raw);
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000,
                            gzipped.length, 1)
                    .media(2, gzipped)
                    .mediaEnd(2)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegment(request, 5_000);

            final String trace = waitForTrace(harness, "compression=1", 2_000);
            assertTrue("Compressed media header was not exercised: " + trace,
                    trace.contains("compression=1"));
            assertEquals("Demand path did not cache decompressed media length",
                    raw.length, harness.holder.session.getCachedSegment(request).getLength());
        }
    }

    @Test
    public void recoverableCompressedAndOverflowMediaRetryThroughDemandPump()
            throws Exception {
        verifyDemandIntegrityRetry("Could not decompress gzip SABR media segment",
                new UmpFixture()
                        .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000,
                                4, 1)
                        .media(2, new byte[]{1, 2, 3, 4})
                        .mediaEnd(2));
        verifyDemandIntegrityRetry("SABR media length overflow: headerId=2",
                new UmpFixture()
                        .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000, 1)
                        .media(2, new byte[]{1, 2})
                        .mediaEnd(2));
    }

    @Test
    public void terminalMediaCollectorErrorsFailThroughDemandPump() throws Exception {
        verifyDemandIntegrityFailure("SABR media segment too large: headerId=2",
                new UmpFixture()
                        .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000,
                                (long) Integer.MAX_VALUE + 1L)
                        .mediaEnd(2));
        verifyDemandIntegrityFailure("Unsupported SABR media compression: 99",
                new UmpFixture()
                        .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000,
                                4, 99)
                        .media(2)
                        .mediaEnd(2));
    }

    @Test
    public void generatedLargeMediaPartStaysOffHeap() throws Exception {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String mediaBytesArgument = arguments.getString("sabrStressMediaBytes");
        assumeTrue("Set sabrStressMediaBytes to run the SABR heap pressure regression test",
                mediaBytesArgument != null);
        final int mediaBytes = Integer.parseInt(mediaBytesArgument);
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new GeneratedLargeMediaResponse(
                    2, SMOKE_VIDEO_ITAG, 1, 0, 5_000, mediaBytes));

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 1);
            final long beforeUsed = usedHeapBytes();
            harness.openMediaSegment(request, 30_000);

            final long peakCached = harness.holder.session.getPeakCachedBytes();
            final SabrMediaSegment segment = harness.holder.session.getCachedSegment(request);
            assertNotNull("Large SABR media segment was not cached", segment);
            System.out.println("SABR_OOM_REGRESSION mediaBytes=" + mediaBytes
                    + " beforeUsed=" + beforeUsed
                    + " afterUsed=" + usedHeapBytes()
                    + " peakCachedBytes=" + peakCached
                    + " diskBacked=" + segment.isDiskBacked()
                    + " trace=" + harness.holder.session.getDiagnosticTrace());
            assertEquals("Large SABR segment cache accounting changed", mediaBytes, peakCached);
            assertTrue("Large SABR media segment must be disk-backed", segment.isDiskBacked());
        }
    }

    @Test
    public void generatedSabrCachePressureStaysOffHeap()
            throws Exception {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String segmentBytesArgument = arguments.getString("sabrStressSegmentBytes");
        assumeTrue("Set sabrStressSegmentBytes to run the accessibility OOM regression test",
                segmentBytesArgument != null);
        final int segmentBytes = Integer.parseInt(segmentBytesArgument);
        final int segmentCount = Integer.parseInt(arguments.getString(
                "sabrStressSegmentCount", "7"));
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            for (int i = 1; i <= segmentCount; i++) {
                harness.downloader.enqueue(new GeneratedLargeMediaResponse(
                        i, SMOKE_VIDEO_ITAG, i, (i - 1L) * 5_000L, 5_000L,
                        segmentBytes));
            }

            final long beforeUsed = usedHeapBytes();
            for (int i = 1; i <= segmentCount; i++) {
                harness.holder.session.pumpOnceStreaming(new Localization("en", "US"));
                final SabrMediaSegment segment = harness.holder.session.getCachedSegment(
                        SabrSegmentRequest.media(harness.videoFormat, i));
                assertNotNull("Generated SABR segment was not cached: " + i, segment);
                assertTrue("Generated SABR media segment must be disk-backed: " + i,
                        segment.isDiskBacked());
                System.out.println("SABR_ACCESSIBILITY_OOM_REGRESSION cachedSegment=" + i
                        + " used=" + usedHeapBytes()
                        + " peakCachedBytes=" + harness.holder.session.getPeakCachedBytes());
            }

            final AtomicReference<Throwable> allocationFailure = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    AccessibilityEvent.obtain().recycle();
                } catch (final Throwable e) {
                    allocationFailure.set(e);
                }
            });

            final Throwable thrown = allocationFailure.get();
            final long expectedCachedBytes = (long) segmentBytes * segmentCount;
            System.out.println("SABR_ACCESSIBILITY_OOM_REGRESSION beforeUsed=" + beforeUsed
                    + " afterUsed=" + usedHeapBytes()
                    + " segmentBytes=" + segmentBytes
                    + " segmentCount=" + segmentCount
                    + " peakCachedBytes=" + harness.holder.session.getPeakCachedBytes()
                    + " allocationFailure=" + (thrown == null ? "" : messageChain(thrown)));
            assertNull("Accessibility small allocation failed after SABR cache pressure",
                    thrown);
            assertEquals("SABR cache accounting did not include generated media",
                    expectedCachedBytes, harness.holder.session.getPeakCachedBytes());
        }
    }

    @Test
    public void contextUpdateAndSendingPolicyUpdateSessionState() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(10, new byte[]{1}, true, 1))
                    .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                            contextUpdate(20, new byte[]{2}, false, 1))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.SABR_CONTEXT_SENDING_POLICY,
                            contextPolicy(new int[]{20}, new int[]{10}, new int[0]))
                    .bytes());

            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));
            assertEquals(0, harness.holder.session.pumpOnceStreaming(new Localization("en", "US")));

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Context update was not decoded: " + trace,
                    trace.contains("57=[type=10"));
            assertTrue("Context sending policy was not decoded: " + trace,
                    trace.contains("59=[start=[20], stop=[10], discard=[]]"));
            assertTrue("Context 20 was not activated by sending policy",
                    activeContextTypes(harness).contains(20));
            assertTrue("Context 10 was not made unsent by sending policy",
                    unsentContextTypes(harness).contains(10));
        }
    }

    private static void runSmokeCase(final SmokeCase smokeCase) throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        assertTrue("The target process must use PipePipe's App initialization",
                context instanceof App);

        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String url = arguments.getString("url", DEFAULT_URL);
        final String client = arguments.getString("youtubeClient", "mweb");
        NewPipe.setYoutubePlayerClient(client);

        // This is intentionally live extraction: the test should detect upstream protocol changes.
        final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
        assertTrue("Extractor returned no SABR video stream for client=" + client,
                info.getVideoStreams().stream().anyMatch(SabrPlaybackSmokeTest::isSabr)
                        || info.getVideoOnlyStreams().stream()
                        .anyMatch(SabrPlaybackSmokeTest::isSabr));

        final int maxVideoHeight = Integer.parseInt(arguments.getString("maxVideoHeight",
                String.valueOf(DEFAULT_MAX_VIDEO_HEIGHT)));
        final String targetCodec = arguments.getString("targetCodec", "");
        final PlayerDataSource dataSource = new PlayerDataSource(context,
                DownloaderImpl.USER_AGENT, new DefaultBandwidthMeter.Builder(context).build());
        final VideoPlaybackResolver resolver = new VideoPlaybackResolver(context, dataSource,
                new BoundedQualityResolver(maxVideoHeight, targetCodec));
        final MediaSource mediaSource = resolver.resolve(info);
        assertNotNull("VideoPlaybackResolver returned no MediaSource", mediaSource);
        if (smokeCase.kind == SmokeCase.Kind.STALLED_READER) {
            try {
                verifyStalledReaderReadAhead(getHolder(info.getId()));
            } finally {
                SabrSessionStore.evict(info.getId());
            }
            return;
        }
        if (smokeCase.kind == SmokeCase.Kind.REWIND_STATE) {
            try {
                verifyRewindResetsSabrState(getHolder(info.getId()));
            } finally {
                SabrSessionStore.evict(info.getId());
            }
            return;
        }
        final boolean simulateEvictedRewind = smokeCase.kind == SmokeCase.Kind.EVICTED_REWIND;
        final SabrSessionStore.Holder injectedHolder =
                smokeCase.kind == SmokeCase.Kind.MISSING_INITIALIZATION
                        ? discardSabrInitialization(info.getId())
                        : null;

        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch firstVideoFrame = new CountDownLatch(1);
        final CountDownLatch audioStarted = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicReference<CountDownLatch> seekProcessed =
                new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<PlaybackException> playerError = new AtomicReference<>();
        final AtomicReference<Long> seekPositionReported = new AtomicReference<>();
        final AtomicBoolean endedEarly = new AtomicBoolean();
        final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        final AtomicReference<SurfaceTexture> textureRef = new AtomicReference<>();
        final AtomicReference<Surface> surfaceRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final SurfaceTexture texture = new SurfaceTexture(0);
            final Surface surface = new Surface(texture);
            final LegacySubtitleRenderersFactory renderersFactory =
                    new LegacySubtitleRenderersFactory(context);
            renderersFactory.setEnableDecoderFallback(true);
            final ExoPlayer player = new ExoPlayer.Builder(context, renderersFactory)
                    .setTrackSelector(new DefaultTrackSelector(context))
                    .setLoadControl(new LoadController())
                    .build();
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(final int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        ready.countDown();
                    } else if (playbackState == Player.STATE_ENDED) {
                        endedEarly.set(true);
                        ended.countDown();
                    }
                }

                @Override
                public void onPlayerError(final PlaybackException error) {
                    playerError.compareAndSet(null, error);
                    ready.countDown();
                    firstVideoFrame.countDown();
                    audioStarted.countDown();
                }

                @Override
                public void onPositionDiscontinuity(final Player.PositionInfo oldPosition,
                                                    final Player.PositionInfo newPosition,
                                                    final int reason) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        seekPositionReported.set(newPosition.positionMs);
                        seekProcessed.get().countDown();
                    }
                }
            });
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onRenderedFirstFrame(final EventTime eventTime,
                                                 final Object output,
                                                 final long renderTimeMs) {
                    firstVideoFrame.countDown();
                }

                @Override
                public void onAudioPositionAdvancing(final EventTime eventTime,
                                                     final long playoutStartSystemTimeMs) {
                    audioStarted.countDown();
                }
            });
            player.setVideoSurface(surface);
            player.setVolume(0f);
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
            textureRef.set(texture);
            surfaceRef.set(surface);
            playerRef.set(player);
        });

        try {
            assertTrue("Player did not reach READY within " + PREPARE_TIMEOUT_SECONDS + "s",
                    ready.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while preparing", playerError.get());
            assertTrue("MediaCodec did not render a video frame",
                    firstVideoFrame.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while starting video", playerError.get());
            assertTrue("Audio output did not start",
                    audioStarted.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while starting audio", playerError.get());
            if (injectedHolder != null) {
                verifyInitializationRecovery(injectedHolder);
            }
            if (smokeCase.kind == SmokeCase.Kind.END_SEEK) {
                verifyEndSeekReachesEnded(playerRef.get(), seekProcessed, seekPositionReported,
                        playerError, ended);
                return;
            }

            final long linearPlaybackMs = Long.parseLong(arguments.getString(
                    "linearPlaybackMs", String.valueOf(DEFAULT_LINEAR_PLAYBACK_MS)));
            final long initialPositionMs = positionOf(playerRef.get());
            waitForPosition(playerRef.get(), initialPositionMs + linearPlaybackMs,
                    PLAYBACK_TIMEOUT_SECONDS);
            assertNull("Player failed during linear playback", playerError.get());

            final long postSeekPlaybackMs = Long.parseLong(arguments.getString(
                    "postSeekPlaybackMs", String.valueOf(DEFAULT_POST_SEEK_PLAYBACK_MS)));
            final long durationMs = durationOf(playerRef.get());
            final long seekPositionMs = seekPositionMs(arguments, durationMs,
                    simulateEvictedRewind ? Math.max(20_000, postSeekPlaybackMs)
                            : postSeekPlaybackMs);
            if (simulateEvictedRewind) {
                assertTrue("Video is too short for an eviction/rewind test: " + durationMs,
                        seekPositionMs >= 60_000);
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> playerRef.get().seekTo(seekPositionMs));
            assertTrue("Player did not report processing the seek",
                    seekProcessed.get().await(10, TimeUnit.SECONDS));
            assertNotNull("Seek discontinuity did not report a new position",
                    seekPositionReported.get());
            assertTrue("Seek landed outside the expected position: requested=" + seekPositionMs
                            + " reported=" + seekPositionReported.get(),
                    Math.abs(seekPositionReported.get() - seekPositionMs) <= 1_000);
            waitForPosition(playerRef.get(), seekPositionMs + postSeekPlaybackMs,
                    PLAYBACK_TIMEOUT_SECONDS);
            assertNull("Player failed after seek", playerError.get());
            if (simulateEvictedRewind) {
                final SabrSessionStore.Holder holder = getHolder(info.getId());
                final long rewindPositionMs = 10_000;
                discardCachedWindow(holder, holder.audioFormat, rewindPositionMs);
                discardCachedWindow(holder, holder.videoFormat, rewindPositionMs);
                final long edgeBeforeRewindMs = holder.session.getStreamState()
                        .getMinBufferedEndMs();
                assertTrue("Rewind target is not behind the SABR edge: target="
                                + rewindPositionMs + " edge=" + edgeBeforeRewindMs,
                        rewindPositionMs < edgeBeforeRewindMs);

                final CountDownLatch rewindProcessed = new CountDownLatch(1);
                seekProcessed.set(rewindProcessed);
                seekPositionReported.set(null);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> playerRef.get().seekTo(rewindPositionMs));
                assertTrue("Player did not process the backward seek",
                        rewindProcessed.await(10, TimeUnit.SECONDS));
                assertNotNull("Backward seek did not report a new position",
                        seekPositionReported.get());
                assertTrue("Backward seek landed outside the expected position: requested="
                                + rewindPositionMs + " reported=" + seekPositionReported.get(),
                        Math.abs(seekPositionReported.get() - rewindPositionMs) <= 1_000);
                final long postRewindPlaybackMs = Long.parseLong(arguments.getString(
                        "postRewindPlaybackMs",
                        String.valueOf(DEFAULT_POST_REWIND_PLAYBACK_MS)));
                waitForPosition(playerRef.get(), rewindPositionMs + postRewindPlaybackMs,
                        PLAYBACK_TIMEOUT_SECONDS);
                assertNull("Player failed after evicted-segment rewind", playerError.get());
                final String trace = holder.session.getDiagnosticTrace();
                // MediaPeriod now asks the pump to rewind as soon as it sees an out-of-buffer seek.
                // The old data-source timeout path ("recovery type=rewind") is only a fallback.
                assertTrue("SABR pump did not execute rewind recovery: " + trace,
                        trace.contains("pump_rewind"));
            }
            assertTrue("Content ended before playback and seek checks completed",
                    !endedEarly.get() || durationMs < 8_000);
            final String maxCachedBytesArgument = arguments.getString("maxCachedBytes");
            if (maxCachedBytesArgument != null) {
                final long maximum = Long.parseLong(maxCachedBytesArgument);
                final SabrSessionStore.Holder holder = getHolder(info.getId());
                final long observed = holder.session.getPeakCachedBytes();
                System.out.println("SABR_MEMORY height=" + holder.videoFormat.getHeight()
                        + " itag=" + holder.videoFormat.getItag()
                        + " peakCachedBytes=" + observed
                        + " maxCachedBytes=" + maximum);
                assertTrue("SABR cache exceeded bound: observed=" + observed
                        + " maximum=" + maximum, observed <= maximum);
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                final ExoPlayer player = playerRef.get();
                if (player != null) {
                    player.release();
                }
                final Surface surface = surfaceRef.get();
                if (surface != null) {
                    surface.release();
                }
                final SurfaceTexture texture = textureRef.get();
                if (texture != null) {
                    texture.release();
                }
            });
            SabrSessionStore.evict(info.getId());
        }
    }

    private static boolean isSabr(final VideoStream stream) {
        return stream.getDeliveryMethod() == DeliveryMethod.SABR;
    }

    private static SabrSessionStore.Holder getHolder(final String videoId) throws Exception {
        final Field sessionsField = SabrSessionStore.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<String, SabrSessionStore.Holder> sessions =
                (Map<String, SabrSessionStore.Holder>) sessionsField.get(null);
        final SabrSessionStore.Holder holder = sessions.get(videoId);
        assertNotNull("SABR session was not created", holder);
        return holder;
    }

    private static void verifyStalledReaderReadAhead(
            final SabrSessionStore.Holder holder) throws Exception {
        final Object readerOwner = new Object();
        final Method setActiveTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                "setActiveTracks", Object.class, boolean.class, boolean.class);
        final Method releaseTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                "releaseTracks", Object.class);
        final Method getPump = SabrSessionStore.Holder.class.getDeclaredMethod(
                "getPump", Localization.class);
        setActiveTracks.setAccessible(true);
        releaseTracks.setAccessible(true);
        getPump.setAccessible(true);
        setActiveTracks.invoke(holder, readerOwner, true, true);
        try {
            assertTrue("The test must begin with an unstarted active reader",
                    holder.hasUnstartedActiveReader());
            assertEquals("Reader head must remain at startup", 0, holder.getReaderHeadMs());
            assertEquals("Reader tail must remain at startup", 0, holder.getReaderTailMs());

            final Object pump = getPump.invoke(holder, new Localization("en", "US"));
            final Method ensureStarted = pump.getClass().getDeclaredMethod("ensureStarted");
            ensureStarted.setAccessible(true);
            ensureStarted.invoke(pump);
            final long deadlineNs = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(PREPARE_TIMEOUT_SECONDS);
            String trace = holder.session.getDiagnosticTrace();
            while (!trace.contains("pump_throttled ") && System.nanoTime() < deadlineNs) {
                Thread.sleep(250);
                trace = holder.session.getDiagnosticTrace();
            }
            assertTrue("Pump did not apply the startup read-ahead bound: " + trace,
                    trace.contains("pump_throttled ")
                            && trace.contains("unstartedReader=true"));

            final int requestNumber = holder.session.getRequestNumber();
            final long edgeMs = holder.session.getStreamState().getMinBufferedEndMs();
            final long cachedBytes = holder.session.getCachedBytes();
            Thread.sleep(1_500);
            assertEquals("Pump continued making SABR requests while the reader was stalled",
                    requestNumber, holder.session.getRequestNumber());
            assertEquals("Buffered edge advanced while the reader was stalled",
                    edgeMs, holder.session.getStreamState().getMinBufferedEndMs());
            assertEquals("Cache grew while the reader was stalled",
                    cachedBytes, holder.session.getCachedBytes());
            assertEquals("Reader head unexpectedly advanced", 0, holder.getReaderHeadMs());
            assertEquals("Reader tail unexpectedly advanced", 0, holder.getReaderTailMs());
        } finally {
            releaseTracks.invoke(holder, readerOwner);
        }
    }

    private static void discardCachedWindow(final SabrSessionStore.Holder holder,
                                            final YoutubeSabrFormat format,
                                            final long positionMs) {
        final int centerSequence = holder.session.getStreamState()
                .getSegmentNumberAtOrAfterTimeMs(format, positionMs);
        for (int sequence = Math.max(1, centerSequence - 1);
             sequence <= centerSequence + 2; sequence++) {
            holder.session.discardCachedSegment(SabrSegmentRequest.media(format, sequence));
        }
        assertNull("Fault injection did not evict target segment for itag=" + format.getItag(),
                holder.session.getCachedSegment(SabrSegmentRequest.media(format, centerSequence)));
    }

    private static void verifyInitializationRecovery(final SabrSessionStore.Holder holder) {
        final String trace = holder.session.getDiagnosticTrace();
        final String audioSabrMarker = holder.audioFormat.getItag() + ":init";
        final String videoSabrMarker = holder.videoFormat.getItag() + ":init";
        assertTrue("Audio initialization was not returned: " + trace,
                trace.contains(audioSabrMarker));
        assertTrue("Video initialization was not returned: " + trace,
                trace.contains(videoSabrMarker));
    }

    private static void verifyRewindResetsSabrState(
            final SabrSessionStore.Holder holder) throws Exception {
        final Localization localization = new Localization("en", "US");
        holder.session.pumpOnce(localization);
        final YoutubeSabrFormat format = holder.videoFormat;
        final int maxSegmentBefore = holder.session.getStreamState().getMaxSegment(format);
        assertTrue("Initial SABR response did not advance media state", maxSegmentBefore > 1);

        final Field playbackCookie = holder.session.getStreamState().getClass()
                .getDeclaredField("playbackCookie");
        playbackCookie.setAccessible(true);
        playbackCookie.set(holder.session.getStreamState(), new byte[]{1, 2, 3, 4});
        assertNotNull("Fault injection did not install a stale playback cookie",
                holder.session.getStreamState().getPlaybackCookie());

        holder.session.prepareForRewind(SabrSegmentRequest.media(format, 1));
        assertEquals("Rewind did not move the buffered range before the target", 0,
                holder.session.getStreamState().getMaxSegment(format));
        assertNull("Rewind retained the stale SABR playback cookie",
                holder.session.getStreamState().getPlaybackCookie());
    }

    private static void verifyEndSeekReachesEnded(
            final ExoPlayer player,
            final AtomicReference<CountDownLatch> seekProcessed,
            final AtomicReference<Long> seekPositionReported,
            final AtomicReference<PlaybackException> playerError,
            final CountDownLatch ended) throws Exception {
        final long durationMs = durationOf(player);
        assertTrue("Cannot seek to stream end when duration is unset",
                durationMs != C.TIME_UNSET);
        assertTrue("Video is too short for an end-seek smoke test: " + durationMs,
                durationMs > 10_000);

        seekProcessed.set(new CountDownLatch(1));
        seekPositionReported.set(null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> player.seekTo(durationMs));
        assertTrue("Player did not report processing the end seek",
                seekProcessed.get().await(10, TimeUnit.SECONDS));
        assertNotNull("End seek did not report a new position", seekPositionReported.get());
        assertTrue("End seek landed outside the expected tail: duration=" + durationMs
                        + " reported=" + seekPositionReported.get(),
                seekPositionReported.get() >= durationMs - 1_000
                        && seekPositionReported.get() <= durationMs);
        assertTrue("Player did not reach ENDED after seeking to stream end",
                ended.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull("Player failed after seeking to stream end", playerError.get());
    }

    private static SabrSessionStore.Holder discardSabrInitialization(
            final String videoId) throws Exception {
        final SabrSessionStore.Holder holder = getHolder(videoId);
        holder.session.pumpOnce(new Localization("en", "US"));
        final SabrSegmentRequest audioInit =
                SabrSegmentRequest.initialization(holder.audioFormat);
        final SabrSegmentRequest videoInit =
                SabrSegmentRequest.initialization(holder.videoFormat);
        assertNotNull("Initial SABR response had no audio init",
                holder.session.getCachedSegment(audioInit));
        assertNotNull("Initial SABR response had no video init",
                holder.session.getCachedSegment(videoInit));
        holder.session.discardCachedSegment(audioInit);
        holder.session.discardCachedSegment(videoInit);
        clearStoredInitializationData(holder);
        holder.session.prepareForInitialization(holder.audioFormat);
        holder.session.prepareForInitialization(holder.videoFormat);
        assertNull(holder.session.getCachedSegment(audioInit));
        assertNull(holder.session.getCachedSegment(videoInit));
        return holder;
    }

    private static void clearStoredInitializationData(
            final SabrSessionStore.Holder holder) throws Exception {
        final Field initializationData =
                SabrSessionStore.Holder.class.getDeclaredField("initializationData");
        initializationData.setAccessible(true);
        @SuppressWarnings("unchecked") final Map<Integer, byte[]> values =
                (Map<Integer, byte[]>) initializationData.get(holder);
        values.remove(holder.audioFormat.getItag());
        values.remove(holder.videoFormat.getItag());
    }

    private static long positionOf(final ExoPlayer player) {
        final AtomicReference<Long> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(player.getCurrentPosition()));
        return result.get();
    }

    private static long durationOf(final ExoPlayer player) {
        final AtomicReference<Long> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(player.getDuration()));
        return result.get();
    }

    private static long seekPositionMs(final Bundle arguments, final long durationMs,
                                       final long requiredTailMs) {
        assertTrue("Cannot use fixed seek position when duration is unset",
                durationMs != C.TIME_UNSET);
        final long seekPositionMs = Long.parseLong(arguments.getString("seekPositionMs",
                String.valueOf(DEFAULT_SEEK_POSITION_MS)));
        assertTrue("seekPositionMs must be positive: " + seekPositionMs, seekPositionMs > 0);
        assertTrue("Video is too short for seek target: duration=" + durationMs
                        + " target=" + seekPositionMs + " requiredTail=" + requiredTailMs,
                durationMs > seekPositionMs + requiredTailMs);
        return seekPositionMs;
    }

    private static void waitForPosition(final ExoPlayer player, final long targetMs,
                                        final long timeoutSeconds) throws Exception {
        final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNs) {
            if (positionOf(player) >= targetMs) {
                return;
            }
            Thread.sleep(250);
        }
        assertEquals("Playback position did not reach target", targetMs, positionOf(player));
    }

    private static void verifyDemandIntegrityRetry(final String expectedIssue,
                                                   final UmpFixture brokenResponse)
            throws Exception {
        final String expectedTrace = expectedIssue.startsWith("length-mismatch:")
                ? "SABR media length mismatch: headerId="
                        + expectedIssue.substring("length-mismatch:".length())
                : expectedIssue.startsWith("missing-media:")
                ? "SABR media length mismatch: headerId="
                        + expectedIssue.substring("missing-media:".length())
                : expectedIssue;
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(brokenResponse.bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(3, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegment(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Integrity issue was not exercised: expected=" + expectedIssue
                    + " trace=" + trace, trace.contains(expectedTrace));
            assertNotNull("Demand retry did not fetch target after " + expectedIssue
                            + ": " + trace,
                    harness.holder.session.getCachedSegment(request));
        }
    }

    private static void verifyDemandIntegrityFailure(final String expectedTrace,
                                                     final UmpFixture brokenResponse)
            throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(brokenResponse.bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegmentExpectFailure(request, 5_000);

            final String trace = waitForTrace(harness, expectedTrace, 2_000);
            assertTrue("Terminal integrity issue was not exercised: expected=" + expectedTrace
                    + " trace=" + trace, trace.contains(expectedTrace));
        }
    }

    private static String waitForTrace(final SabrSmokeHarness harness,
                                       final String expected,
                                       final long timeoutMs) throws Exception {
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String trace = harness.holder.session.getDiagnosticTrace();
        while (!trace.contains(expected) && System.nanoTime() < deadlineNs) {
            Thread.sleep(25);
            trace = harness.holder.session.getDiagnosticTrace();
        }
        return trace;
    }

    private static long usedHeapBytes() {
        final Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String messageChain(final Throwable throwable) {
        final StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(current.getClass().getSimpleName())
                    .append(':')
                    .append(current.getMessage());
            current = current.getCause();
        }
        return builder.toString();
    }

    private static YoutubeSabrFormat smokeFormat(final int itag, final boolean audio)
            throws Exception {
        return smokeFormat(itag, audio, "https://media.test/" + itag, -1L, -1L);
    }

    private static YoutubeSabrFormat smokeFormat(final int itag,
                                                 final boolean audio,
                                                 final String initializationUrl,
                                                 final long initRangeStart,
                                                 final long initRangeEnd)
            throws Exception {
        final Constructor<YoutubeSabrFormat> constructor =
                YoutubeSabrFormat.class.getDeclaredConstructor(int.class, long.class,
                        String.class, String.class, String.class, String.class, boolean.class,
                        String.class, String.class, boolean.class, int.class, int.class,
                        int.class, long.class, long.class, String.class, long.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                itag,
                123456L,
                audio ? "audio-xtags" : "video-xtags",
                audio ? "audio/mp4" : "video/webm",
                audio ? "audio-track" : null,
                audio ? "English original" : null,
                audio,
                audio ? null : "1080p",
                audio ? "AUDIO_QUALITY_MEDIUM" : null,
                false,
                audio ? -1 : 1920,
                audio ? -1 : 1080,
                audio ? 128_000 : 2_000_000,
                100_000L,
                300_000L,
                initializationUrl,
                initRangeStart,
                initRangeEnd);
    }

    private static YoutubeSabrInfo smokeInfo(final YoutubeSabrFormat audioFormat,
                                             final YoutubeSabrFormat videoFormat)
            throws Exception {
        final Constructor<YoutubeSabrInfo> constructor =
                YoutubeSabrInfo.class.getDeclaredConstructor(YoutubeSabrClientProfile.class,
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(YoutubeSabrClientProfile.MWEB, "smoke-video", "cpn",
                "2.20250122.04.00", "visitor", "https://sabr.test",
                base64(new byte[]{1, 2, 3, 4}), Arrays.asList(audioFormat, videoFormat));
    }

    private static byte[] nextRequestPolicy(final int backoffMs) {
        return nextRequestPolicy(backoffMs, null, null);
    }

    private static byte[] nextRequestPolicy(final int backoffMs,
                                            final byte[] playbackCookie,
                                            final String videoId) {
        final Proto proto = proto()
                .u64(1, 3_000)
                .u64(2, 4_000)
                .u64(3, 1_000)
                .u64(4, backoffMs)
                .u64(5, 500)
                .u64(6, 600);
        if (playbackCookie != null) {
            proto.message(7, playbackCookie);
        }
        if (videoId != null) {
            proto.string(8, videoId);
        }
        return proto.bytes();
    }

    private static byte[] streamProtection(final int status, final int maxRetries) {
        return proto()
                .u64(1, status)
                .u64(2, maxRetries)
                .bytes();
    }

    private static byte[] playbackCookie() {
        return proto()
                .u64(1, 1080)
                .u64(2, 1)
                .message(7, formatId(SMOKE_VIDEO_ITAG))
                .message(8, formatId(SMOKE_AUDIO_ITAG))
                .bytes();
    }

    private static byte[] formatId(final int itag) {
        return proto().u64(1, itag).u64(2, 123456).bytes();
    }

    private static byte[] liveMetadata(final long headSeq,
                                       final long headTimeMs,
                                       final boolean postLiveDvr) {
        return proto()
                .string(1, "broadcast")
                .u64(3, headSeq)
                .u64(4, headTimeMs)
                .u64(5, headTimeMs + 1000)
                .string(6, "smoke-video")
                .u64(8, postLiveDvr ? 1 : 0)
                .u64(12, 0)
                .u64(13, 1000)
                .u64(14, headTimeMs)
                .u64(15, 1000)
                .bytes();
    }

    private static byte[] initializationMetadata(final int itag,
                                                 final long endSegment,
                                                 final long endTimeMs,
                                                 final String mimeType) {
        return proto()
                .message(2, formatId(itag))
                .u64(3, endTimeMs)
                .u64(4, endSegment)
                .string(5, mimeType)
                .bytes();
    }

    private static byte[] redirect(final String url) {
        return proto().string(1, url).bytes();
    }

    private static byte[] sabrError(final String type, final int code) {
        return proto().string(1, type).u64(2, code).bytes();
    }

    private static byte[] reloadPlayerResponse(final String token) {
        return proto()
                .message(1, proto()
                        .message(1, proto()
                                .string(1, token)
                                .bytes())
                        .bytes())
                .bytes();
    }

    private static byte[] sabrSeek(final long mediaTime,
                                   final int timescale,
                                   final int source) {
        return proto()
                .u64(1, mediaTime)
                .u64(2, timescale)
                .u64(3, source)
                .bytes();
    }

    private static byte[] playbackStartPolicy() {
        return proto()
                .message(1, proto().u64(1, 100_000).u64(2, 1_500).bytes())
                .message(2, proto().u64(1, 120_000).u64(2, 2_500).bytes())
                .u64(5, 9)
                .bytes();
    }

    private static byte[] formatSelectionConfig() {
        return proto()
                .packedU64(2, SMOKE_VIDEO_ITAG, SMOKE_AUDIO_ITAG)
                .string(3, "smoke-video")
                .u64(4, 1080)
                .bytes();
    }

    private static byte[] selectableFormats() {
        return proto()
                .message(1, formatIdWithXtags(SMOKE_VIDEO_ITAG, "vxtags"))
                .message(2, formatIdWithXtags(SMOKE_AUDIO_ITAG, "axtags"))
                .message(4, proto().message(1, formatIdWithXtags(399, "wv")).bytes())
                .message(5, proto().message(1, formatIdWithXtags(251, "wa")).bytes())
                .u64(9, 1)
                .bytes();
    }

    private static byte[] onesieHeader(final int type,
                                       final long sequence,
                                       final boolean encrypted) {
        final Proto crypto = proto().u64(6, 0);
        if (encrypted) {
            crypto.message(4, new byte[]{1, 2, 3});
            crypto.message(5, new byte[]{4, 5});
        }
        return proto()
                .u64(1, type)
                .string(2, "smoke-video")
                .string(3, String.valueOf(SMOKE_VIDEO_ITAG))
                .message(4, crypto.bytes())
                .u64(5, 123456)
                .u64(7, 11)
                .message(11, proto().u64(1, SMOKE_VIDEO_ITAG).bytes())
                .string(15, "onesie-xtags")
                .u64(18, sequence)
                .message(23, proto().string(2, "smoke-video").bytes())
                .message(34, proto().u64(1, SMOKE_AUDIO_ITAG).bytes())
                .bytes();
    }

    private static byte[] onesieInnertubeResponse() {
        return proto()
                .u64(1, 1)
                .u64(2, 200)
                .message(3, proto().string(1, "x-smoke").string(2, "ok").bytes())
                .message(4, new byte[]{1, 2, 3, 4})
                .bytes();
    }

    private static byte[] requestIdentifier(final String token) {
        return proto().string(1, token).bytes();
    }

    private static byte[] snackbar(final int id) {
        return proto().u64(1, id).bytes();
    }

    private static byte[] cancellationPolicy() {
        return proto()
                .u64(1, 1)
                .message(2, proto().u64(1, 2).u64(2, 3).u64(3, 1500).bytes())
                .u64(3, 4)
                .bytes();
    }

    private static byte[] prewarmConnection() {
        return proto()
                .message(1, proto().string(1, "cdn").u64(2, 1).bytes())
                .bytes();
    }

    private static byte[] contextUpdate(final int type,
                                        final byte[] value,
                                        final boolean sendByDefault,
                                        final int writePolicy) {
        return proto()
                .u64(1, type)
                .u64(2, 1)
                .message(3, value)
                .u64(4, sendByDefault ? 1 : 0)
                .u64(5, writePolicy)
                .bytes();
    }

    private static byte[] contextPolicy(final int[] start,
                                        final int[] stop,
                                        final int[] discard) {
        final Proto proto = proto();
        for (final int value : start) {
            proto.u64(1, value);
        }
        for (final int value : stop) {
            proto.u64(2, value);
        }
        for (final int value : discard) {
            proto.u64(3, value);
        }
        return proto.bytes();
    }

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        }
        return output.toByteArray();
    }

    private static List<Integer> activeContextTypes(final SabrSmokeHarness harness)
            throws Exception {
        final Method getActiveSabrContexts = harness.holder.session.getStreamState().getClass()
                .getDeclaredMethod("getActiveSabrContexts");
        getActiveSabrContexts.setAccessible(true);
        @SuppressWarnings("unchecked") final Collection<Object> contexts =
                (Collection<Object>) getActiveSabrContexts.invoke(
                        harness.holder.session.getStreamState());
        final List<Integer> types = new ArrayList<>();
        for (final Object context : contexts) {
            final Method getType = context.getClass().getDeclaredMethod("getType");
            getType.setAccessible(true);
            types.add((Integer) getType.invoke(context));
        }
        return types;
    }

    private static List<Integer> unsentContextTypes(final SabrSmokeHarness harness)
            throws Exception {
        final Method getUnsentSabrContextTypes =
                harness.holder.session.getStreamState().getClass()
                        .getDeclaredMethod("getUnsentSabrContextTypes");
        getUnsentSabrContextTypes.setAccessible(true);
        @SuppressWarnings("unchecked") final Collection<Integer> types =
                (Collection<Integer>) getUnsentSabrContextTypes.invoke(
                        harness.holder.session.getStreamState());
        return new ArrayList<>(types);
    }

    private static Proto proto() {
        return new Proto();
    }

    private static String base64(final byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    private static byte[] formatIdWithXtags(final int itag, final String xtags) {
        return proto()
                .u64(1, itag)
                .u64(2, 123456)
                .string(3, xtags)
                .bytes();
    }

    private static final class SmokeCase {
        private enum Kind {
            PLAYBACK,
            MISSING_INITIALIZATION,
            EVICTED_REWIND,
            STALLED_READER,
            REWIND_STATE,
            END_SEEK
        }

        private final Kind kind;

        private SmokeCase(final Kind kind) {
            this.kind = kind;
        }

        private static SmokeCase playback() {
            return new SmokeCase(Kind.PLAYBACK);
        }

        private static SmokeCase missingInitialization() {
            return new SmokeCase(Kind.MISSING_INITIALIZATION);
        }

        private static SmokeCase evictedRewind() {
            return new SmokeCase(Kind.EVICTED_REWIND);
        }

        private static SmokeCase stalledReader() {
            return new SmokeCase(Kind.STALLED_READER);
        }

        private static SmokeCase rewindState() {
            return new SmokeCase(Kind.REWIND_STATE);
        }

        private static SmokeCase endSeek() {
            return new SmokeCase(Kind.END_SEEK);
        }
    }

    private static final class SabrSmokeHarness implements AutoCloseable {
        private final Downloader previousDownloader;
        private final Localization previousLocalization;
        private final ContentCountry previousContentCountry;
        private final FakeSabrDownloader downloader;
        private final SabrSessionStore.Holder holder;
        private final YoutubeSabrFormat videoFormat;
        private final Object readerOwner;

        private SabrSmokeHarness(final Downloader previousDownloader,
                                 final Localization previousLocalization,
                                 final ContentCountry previousContentCountry,
                                 final FakeSabrDownloader downloader,
                                 final SabrSessionStore.Holder holder,
                                 final YoutubeSabrFormat videoFormat,
                                 final Object readerOwner) {
            this.previousDownloader = previousDownloader;
            this.previousLocalization = previousLocalization;
            this.previousContentCountry = previousContentCountry;
            this.downloader = downloader;
            this.holder = holder;
            this.videoFormat = videoFormat;
            this.readerOwner = readerOwner;
        }

        private static SabrSmokeHarness create() throws Exception {
            return create(smokeFormat(SMOKE_AUDIO_ITAG, true),
                    smokeFormat(SMOKE_VIDEO_ITAG, false));
        }

        private static SabrSmokeHarness create(final YoutubeSabrFormat audioFormat,
                                               final YoutubeSabrFormat videoFormat)
                throws Exception {
            final Downloader previousDownloader = NewPipe.getDownloader();
            final Localization previousLocalization = NewPipe.getPreferredLocalization();
            final ContentCountry previousContentCountry = NewPipe.getPreferredContentCountry();
            final FakeSabrDownloader downloader = new FakeSabrDownloader();
            NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT);
            final YoutubeSabrInfo info = smokeInfo(audioFormat, videoFormat);
            final File spoolDirectory = new File(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                    "sabr-smoke-" + System.nanoTime());
            final YoutubeSabrSession session =
                    new YoutubeSabrSession(info, audioFormat, videoFormat, null, spoolDirectory);
            session.getStreamState().setVideoOnlyRequestMode();
            final Constructor<SabrSessionStore.Holder> constructor =
                    SabrSessionStore.Holder.class.getDeclaredConstructor(Context.class,
                            String.class, YoutubeSabrInfo.class, YoutubeSabrSession.class,
                            YoutubeSabrFormat.class, YoutubeSabrFormat.class);
            constructor.setAccessible(true);
            final SabrSessionStore.Holder holder = constructor.newInstance(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    "smoke-video", info, session, audioFormat, videoFormat);
            final Object readerOwner = new Object();
            final Method setActiveTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                    "setActiveTracks", Object.class, boolean.class, boolean.class);
            setActiveTracks.setAccessible(true);
            setActiveTracks.invoke(holder, readerOwner, true, false);
            return new SabrSmokeHarness(previousDownloader, previousLocalization,
                    previousContentCountry, downloader, holder, videoFormat, readerOwner);
        }

        private void setPlayerTimeMs(final long playerTimeMs) throws Exception {
            final Method setPlayerTimeMs = SabrSessionStore.Holder.class.getDeclaredMethod(
                    "setPlayerTimeMs", long.class);
            setPlayerTimeMs.setAccessible(true);
            setPlayerTimeMs.invoke(holder, playerTimeMs);
        }

        private long openMediaSegment(final SabrSegmentRequest request,
                                      final long timeoutMs) throws Exception {
            return openSegment(request, timeoutMs);
        }

        private long openSegment(final SabrSegmentRequest request,
                                 final long timeoutMs) throws Exception {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final long startMs = System.currentTimeMillis();
            final Thread thread = new Thread(() -> {
                final SabrSegmentDataSource dataSource = new SabrSegmentDataSource(
                        holder, readerOwner, request.getFormat(), new Localization("en", "US"),
                        false);
                try {
                    dataSource.open(new DataSpec(segmentUri(request)));
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    dataSource.close();
                    done.countDown();
                }
            }, "SabrSmokeDemandOpen");
            thread.start();
            assertTrue("SABR smoke demand open timed out, trace="
                            + holder.session.getDiagnosticTrace(),
                    done.await(timeoutMs, TimeUnit.MILLISECONDS));
            if (failure.get() != null) {
                throw new AssertionError("SABR smoke demand open failed, trace="
                        + holder.session.getDiagnosticTrace(), failure.get());
            }
            return System.currentTimeMillis() - startMs;
        }

        private Uri segmentUri(final SabrSegmentRequest request) {
            return Uri.parse("sabr://" + request.getFormat().getItag() + '/'
                    + (request.isInitializationSegment()
                    ? "init" : String.valueOf(request.getSequenceNumber())));
        }

        private void openMediaSegmentExpectFailure(final SabrSegmentRequest request,
                                                   final long timeoutMs) throws Exception {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final Thread thread = new Thread(() -> {
                final SabrSegmentDataSource dataSource = new SabrSegmentDataSource(
                        holder, readerOwner, request.getFormat(), new Localization("en", "US"),
                        false);
                try {
                    dataSource.open(new DataSpec(Uri.parse("sabr://"
                            + request.getFormat().getItag() + '/'
                            + request.getSequenceNumber())));
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    dataSource.close();
                    done.countDown();
                }
            }, "SabrSmokeDemandOpenFailure");
            thread.start();
            assertTrue("SABR smoke demand failure did not complete, trace="
                            + holder.session.getDiagnosticTrace(),
                    done.await(timeoutMs, TimeUnit.MILLISECONDS));
            assertNotNull("SABR smoke demand unexpectedly succeeded, trace="
                    + holder.session.getDiagnosticTrace(), failure.get());
        }

        @Override
        public void close() throws Exception {
            final Method stop = SabrSessionStore.Holder.class.getDeclaredMethod(
                    "stop", String.class);
            stop.setAccessible(true);
            stop.invoke(holder, "smoke_harness_close");
            NewPipe.init(previousDownloader, previousLocalization, previousContentCountry);
        }
    }

    private static final class FakeSabrDownloader extends Downloader {
        private final LinkedBlockingQueue<QueuedStreamingBody> responses =
                new LinkedBlockingQueue<>();
        private final List<String> requestedUrls = new ArrayList<>();
        private final List<byte[]> requestBodies = new ArrayList<>();

        private void enqueue(final byte[] body) {
            responses.add(() -> new ByteArrayInputStream(body));
        }

        private void enqueue(final QueuedStreamingBody body) {
            responses.add(body);
        }

        @Override
        public Response execute(final Request request) throws IOException {
            throw new IOException("Unexpected buffered request in SABR smoke: "
                    + request.httpMethod() + " " + request.url());
        }

        @Override
        public StreamingResponse postStreaming(final String url,
                                               final Map<String, List<String>> headers,
                                               final byte[] dataToSend,
                                               final Localization localization)
                throws IOException {
            requestedUrls.add(url);
            requestBodies.add(dataToSend.clone());
            final QueuedStreamingBody body = responses.poll();
            if (body == null) {
                throw new IOException("No queued SABR smoke response for " + url);
            }
            final Map<String, List<String>> responseHeaders = new HashMap<>();
            responseHeaders.put("Content-Type",
                    Collections.singletonList("application/vnd.yt-ump"));
            return new StreamingResponse(200, responseHeaders, body.open());
        }

        @Override
        public CancellableCall executeAsync(final Request request,
                                            final AsyncCallback callback)
                throws IOException, ReCaptchaException {
            throw new IOException("Unexpected async request in SABR smoke: "
                    + request.httpMethod() + " " + request.url());
        }
    }

    @FunctionalInterface
    private interface QueuedStreamingBody {
        InputStream open() throws IOException;
    }

    private static final class GeneratedLargeMediaResponse implements QueuedStreamingBody {
        private final int headerId;
        private final int itag;
        private final int sequence;
        private final long startMs;
        private final long durationMs;
        private final int mediaBytes;

        private GeneratedLargeMediaResponse(final int headerId,
                                            final int itag,
                                            final int sequence,
                                            final long startMs,
                                            final long durationMs,
                                            final int mediaBytes) {
            this.headerId = headerId;
            this.itag = itag;
            this.sequence = sequence;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.mediaBytes = mediaBytes;
        }

        @Override
        public InputStream open() {
            final byte[] mediaHeader = proto()
                    .u64(1, headerId)
                    .u64(3, itag)
                    .u64(4, 123456)
                    .u64(7, 0)
                    .u64(8, 0)
                    .u64(9, sequence)
                    .u64(11, Math.max(0, startMs))
                    .u64(12, Math.max(0, durationMs))
                    .u64(14, Math.max(0, mediaBytes))
                    .bytes();
            final byte[] headerPartPrefix = umpPartPrefix(
                    SabrResponseDecoder.MEDIA_HEADER, mediaHeader.length);
            final byte[] mediaPartPrefix = umpPartPrefix(
                    SabrResponseDecoder.MEDIA, mediaBytes + 1);
            final byte[] mediaEndPart = new UmpFixture().mediaEnd(headerId).bytes();
            return new GeneratedLargeMediaInputStream(
                    headerPartPrefix, mediaHeader, mediaPartPrefix,
                    (byte) headerId, mediaBytes, mediaEndPart);
        }
    }

    private static final class GeneratedLargeMediaInputStream extends InputStream {
        private final byte[] headerPartPrefix;
        private final byte[] mediaHeader;
        private final byte[] mediaPartPrefix;
        private final byte headerId;
        private final int mediaBytes;
        private final byte[] mediaEndPart;
        private int phase;
        private int offset;
        private int generatedMediaBytes;
        private boolean mediaHeaderIdSent;

        private GeneratedLargeMediaInputStream(final byte[] headerPartPrefix,
                                               final byte[] mediaHeader,
                                               final byte[] mediaPartPrefix,
                                               final byte headerId,
                                               final int mediaBytes,
                                               final byte[] mediaEndPart) {
            this.headerPartPrefix = headerPartPrefix;
            this.mediaHeader = mediaHeader;
            this.mediaPartPrefix = mediaPartPrefix;
            this.headerId = headerId;
            this.mediaBytes = mediaBytes;
            this.mediaEndPart = mediaEndPart;
        }

        @Override
        public int read() {
            final byte[] one = new byte[1];
            final int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(final byte[] buffer, final int off, final int len) {
            if (len <= 0) {
                return 0;
            }
            int written = 0;
            while (written < len) {
                final int value = nextByte();
                if (value < 0) {
                    return written == 0 ? -1 : written;
                }
                buffer[off + written] = (byte) value;
                written++;
            }
            return written;
        }

        private int nextByte() {
            while (true) {
                switch (phase) {
                    case 0:
                        return byteFrom(headerPartPrefix);
                    case 1:
                        return byteFrom(mediaHeader);
                    case 2:
                        return byteFrom(mediaPartPrefix);
                    case 3:
                        if (!mediaHeaderIdSent) {
                            mediaHeaderIdSent = true;
                            return headerId & 0xff;
                        }
                        if (generatedMediaBytes < mediaBytes) {
                            generatedMediaBytes++;
                            return 0;
                        }
                        phase++;
                        offset = 0;
                        break;
                    case 4:
                        return byteFrom(mediaEndPart);
                    default:
                        return -1;
                }
            }
        }

        private int byteFrom(final byte[] bytes) {
            if (offset < bytes.length) {
                return bytes[offset++] & 0xff;
            }
            phase++;
            offset = 0;
            return nextByte();
        }
    }

    private static final class UmpFixture {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private UmpFixture segment(final int headerId, final int itag, final int sequence) {
            return mediaHeader(headerId, itag, sequence).media(headerId).mediaEnd(headerId);
        }

        private UmpFixture initSegment(final int headerId, final int itag) {
            return mediaHeader(headerId, itag, 0, 0, 0, 4, 0, true)
                    .media(headerId)
                    .mediaEnd(headerId);
        }

        private UmpFixture segment(final int headerId,
                                   final int itag,
                                   final int sequence,
                                   final long startMs,
                                   final long durationMs) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs)
                    .media(headerId)
                    .mediaEnd(headerId);
        }

        private UmpFixture mediaHeader(final int headerId, final int itag, final int sequence) {
            return mediaHeader(headerId, itag, sequence, (sequence - 1) * 5_000L, 5_000L);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs, 4);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs,
                                       final long contentLength) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs, contentLength, 0);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs,
                                       final long contentLength,
                                       final int compressionAlgorithm) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs, contentLength,
                    compressionAlgorithm, false);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs,
                                       final long contentLength,
                                       final int compressionAlgorithm,
                                       final boolean initialization) {
            final Proto header = proto()
                    .u64(1, headerId)
                    .u64(3, itag)
                    .u64(4, 123456)
                    .u64(7, compressionAlgorithm)
                    .u64(8, initialization ? 1 : 0)
                    .u64(9, sequence)
                    .u64(11, Math.max(0, startMs))
                    .u64(12, Math.max(0, durationMs))
                    .u64(14, Math.max(0, contentLength));
            return part(SabrResponseDecoder.MEDIA_HEADER, header.bytes());
        }

        private UmpFixture media(final int headerId) {
            return media(headerId, new byte[]{10, 11, 12, 13});
        }

        private UmpFixture media(final int headerId, final byte[] payload) {
            final byte[] part = new byte[payload.length + 1];
            part[0] = (byte) headerId;
            System.arraycopy(payload, 0, part, 1, payload.length);
            return part(SabrResponseDecoder.MEDIA, part);
        }

        private UmpFixture mediaEnd(final int headerId) {
            return part(SabrResponseDecoder.MEDIA_END, new byte[]{(byte) headerId});
        }

        private UmpFixture part(final int type, final byte[] payload) {
            writeVarint(output, type);
            writeVarint(output, payload.length);
            output.write(payload, 0, payload.length);
            return this;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }

    private static final class Proto {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private Proto u64(final int field, final long value) {
            writeVarint(output, ((long) field << 3) | PROTO_WIRE_VARINT);
            writeVarint(output, value);
            return this;
        }

        private Proto string(final int field, final String value) {
            return message(field, value.getBytes(StandardCharsets.UTF_8));
        }

        private Proto message(final int field, final byte[] value) {
            writeVarint(output, ((long) field << 3) | PROTO_WIRE_LENGTH_DELIMITED);
            writeVarint(output, value.length);
            output.write(value, 0, value.length);
            return this;
        }

        private Proto packedU64(final int field, final long... values) {
            final ByteArrayOutputStream packed = new ByteArrayOutputStream();
            for (final long value : values) {
                writeVarint(packed, value);
            }
            return message(field, packed.toByteArray());
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }

    private static void writeVarint(final ByteArrayOutputStream output, final long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write((int) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }

    private static byte[] umpPartPrefix(final int type, final int size) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUmpInt(output, type);
        writeUmpInt(output, size);
        return output.toByteArray();
    }

    private static void writeUmpInt(final ByteArrayOutputStream output, final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("UMP integer must be non-negative");
        }
        if (value < 128) {
            output.write(value);
            return;
        }
        output.write(240);
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static final class BoundedQualityResolver implements QualityResolver {
        private final int maxHeight;
        private final String targetCodec;

        private BoundedQualityResolver(final int maxHeight) {
            this(maxHeight, "");
        }

        private BoundedQualityResolver(final int maxHeight, final String targetCodec) {
            this.maxHeight = maxHeight;
            this.targetCodec = targetCodec == null ? "" : targetCodec.toLowerCase(Locale.ROOT);
        }

        @Override
        public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
            int lowestIndex = -1;
            int lowestHeight = Integer.MAX_VALUE;
            int preferredIndex = -1;
            int preferredHeight = -1;
            for (int i = 0; i < sortedVideos.size(); i++) {
                final VideoStream stream = sortedVideos.get(i);
                if (!isSabr(stream)) {
                    continue;
                }
                final String codec = stream.getCodec() == null
                        ? "" : stream.getCodec().toLowerCase(Locale.ROOT);
                if (!targetCodec.isEmpty() && !codec.isEmpty() && !codec.contains(targetCodec)) {
                    continue;
                }
                final int height = stream.getHeight();
                if (height > 0 && height < lowestHeight) {
                    lowestHeight = height;
                    lowestIndex = i;
                }
                if (height > preferredHeight && height <= maxHeight) {
                    preferredHeight = height;
                    preferredIndex = i;
                }
            }
            if (lowestIndex < 0) {
                throw new AssertionError("Resolver has no selectable SABR video stream");
            }
            return preferredIndex >= 0 ? preferredIndex : lowestIndex;
        }

        @Override
        public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                              final int selectedIndex) {
            return selectedIndex;
        }

        @Override
        public int getCurrentAudioQualityIndex(final List<AudioStream> audioStreams) {
            return 0;
        }
    }
}
