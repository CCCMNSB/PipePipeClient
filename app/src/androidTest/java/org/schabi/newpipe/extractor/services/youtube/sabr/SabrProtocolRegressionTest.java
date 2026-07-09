package org.schabi.newpipe.extractor.services.youtube.sabr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class SabrProtocolRegressionTest {
    private static final int AUDIO_ITAG = 140;
    private static final int VIDEO_ITAG = 248;
    private static final byte[] USTREAMER_CONFIG = new byte[]{1, 2, 3, 4};

    @Test
    public void malformedControlPartDoesNotDiscardResponse() throws Exception {
        final UmpFixture fixture = new UmpFixture()
                // NEXT_REQUEST_POLICY containing protobuf tag 1 / invalid wire type 7.
                .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, new byte[]{0x0f})
                .part(SabrResponseDecoder.CONFIG, new byte[0]);

        final SabrDecodedResponse decoded = SabrResponseDecoder.decode(fixture.bytes());

        assertEquals(1, decoded.getMalformedParts().size());
        assertTrue(decoded.getMalformedParts().get(0).startsWith("35:1:"));
        assertTrue(decoded.getGenericPartDescriptions()
                .containsKey(SabrResponseDecoder.CONFIG));
    }

    @Test
    public void malformedControlPartDoesNotDiscardMediaSegments() throws Exception {
        final UmpFixture fixture = new UmpFixture()
                .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, new byte[]{0x0f})
                .segment(1, AUDIO_ITAG, 1);

        final SabrStreamingResponseReader.Result result = read(fixture);

        assertEquals(1, result.getDecodedResponse().getMalformedParts().size());
        assertEquals(1, result.getSegmentCount());
        assertTrue(result.getDecodedResponse().getIntegrityIssues().isEmpty());
        assertEquals(AUDIO_ITAG, result.getSegments().get(0).getHeader().getItag());
    }

    @Test
    public void malformedMediaHeaderBecomesRecoverableIntegrityFailure() throws Exception {
        final UmpFixture fixture = new UmpFixture()
                .part(SabrResponseDecoder.MEDIA_HEADER, new byte[]{0x0f})
                .media(1, new byte[]{10, 11})
                .mediaEnd(1);

        final SabrStreamingResponseReader.Result result = read(fixture);

        assertEquals(1, result.getDecodedResponse().getMalformedParts().size());
        assertTrue(result.getDecodedResponse().getIntegrityIssues()
                .contains("media-without-header:1"));
        assertTrue("malformed media header would terminate playback instead of retrying",
                isRecoverableIncomplete(result.getDecodedResponse().getIntegrityIssues()));
    }

    @Test
    public void streamingConsumerDoesNotRetainCompletedBatch() throws Exception {
        final UmpFixture fixture = new UmpFixture();
        for (int id = 1; id <= 3; id++) {
            fixture.segment(id, AUDIO_ITAG, id);
        }
        final AtomicInteger delivered = new AtomicInteger();

        final SabrStreamingResponseReader.Result result = SabrStreamingResponseReader.read(
                new ByteArrayInputStream(fixture.bytes()), segment -> delivered.incrementAndGet());

        assertEquals(3, delivered.get());
        assertEquals(3, result.getSegmentCount());
        assertTrue("streaming production path retained the response batch",
                result.getSegments().isEmpty());
        assertTrue(result.getDecodedResponse().getIntegrityIssues().isEmpty());
    }

    @Test
    public void stoppingAtTargetAfterNextHeaderLeavesRecoverableIncompleteResponse()
            throws Exception {
        final UmpFixture fixture = new UmpFixture()
                .mediaHeader(1, AUDIO_ITAG, 1)
                .mediaHeader(2, AUDIO_ITAG, 2)
                .media(1)
                .mediaEnd(1)
                .media(2)
                .mediaEnd(2);

        final SabrStreamingResponseReader.Result full = read(fixture);
        assertEquals(2, full.getSegmentCount());
        assertTrue(full.getDecodedResponse().getIntegrityIssues().isEmpty());

        final AtomicInteger delivered = new AtomicInteger();
        final SabrStreamingResponseReader.Result stopped = SabrStreamingResponseReader.readUntil(
                new ByteArrayInputStream(fixture.bytes()), segment -> {
                    delivered.incrementAndGet();
                    return false;
                });

        assertEquals(1, delivered.get());
        assertTrue(stopped.getDecodedResponse().getIntegrityIssues()
                .contains("missing-media:2"));
        assertTrue(stopped.getDecodedResponse().getIntegrityIssues()
                .contains("missing-media-end:2"));
        assertTrue("early target stop must remain a recoverable incomplete response",
                isRecoverableIncomplete(stopped.getDecodedResponse().getIntegrityIssues()));
    }

    @Test
    public void allMediaIntegrityShapesAreRecoverableExceptDuplicateHeader()
            throws Exception {
        assertRecoverableIntegrity(new UmpFixture()
                .mediaHeader(1, AUDIO_ITAG, 1)
                .media(1, new byte[]{1, 2})
                .mediaEnd(1), "length-mismatch:1:expected=4:actual=2");
        assertRecoverableIntegrity(new UmpFixture()
                .mediaHeader(2, AUDIO_ITAG, 2), "missing-media:2");
        assertRecoverableIntegrity(new UmpFixture()
                .mediaHeader(3, AUDIO_ITAG, 3)
                .media(3), "missing-media-end:3");
        assertRecoverableIntegrity(new UmpFixture()
                .media(4)
                .mediaEnd(4), "media-without-header:4");
        assertRecoverableIntegrity(new UmpFixture()
                .mediaEnd(5), "media-end-without-header:5");

        final SabrDecodedResponse duplicate = SabrResponseDecoder.decode(new UmpFixture()
                .mediaHeader(6, AUDIO_ITAG, 6)
                .mediaHeader(6, AUDIO_ITAG, 7)
                .bytes());
        assertTrue(duplicate.getIntegrityIssues().contains("duplicate-media-header:6"));
        assertFalse(isRecoverableIncomplete(duplicate.getIntegrityIssues()));
    }

    @Test
    public void mediaLengthOverflowIsRecoverableDuringStreamingRead() throws Exception {
        try {
            read(new UmpFixture()
                    .mediaHeader(1, AUDIO_ITAG, 1)
                    .media(1, new byte[]{1, 2, 3, 4, 5}));
        } catch (final SabrRecoverableException e) {
            assertTrue(e.getMessage().contains("SABR media length overflow"));
            return;
        }
        throw new AssertionError("Expected media overflow to be recoverable");
    }

    @Test
    public void policyOnlyBackoffResponseIsRecognizedAsNoMedia() throws Exception {
        final SabrDecodedResponse decoded = read(new UmpFixture()
                .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(5_000)))
                .getDecodedResponse();

        assertTrue(decoded.isNoMediaResponse());
        assertTrue(decoded.isPolicyOnlyResponse());
        assertFalse(decoded.isProtectionBoundaryNoMediaResponse());
        assertEquals(5_000, decoded.getBackoffTimeMs());
        assertTrue(decoded.getIntegrityIssues().isEmpty());
    }

    @Test
    public void protectedNoMediaResponseCarriesBackoffAndRetryStatus() throws Exception {
        final SabrDecodedResponse decoded = read(new UmpFixture()
                .part(SabrResponseDecoder.STREAM_PROTECTION_STATUS, streamProtection(3, 7))
                .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(1_250)))
                .getDecodedResponse();

        assertTrue(decoded.isNoMediaResponse());
        assertTrue(decoded.isPolicyOnlyResponse());
        assertTrue(decoded.isProtectionBoundaryNoMediaResponse());
        assertTrue(decoded.isProtectedNoMediaResponse());
        assertEquals(3, decoded.getStreamProtectionStatus());
        assertEquals(7, decoded.getStreamProtectionMaxRetries());
        assertEquals(1_250, decoded.getBackoffTimeMs());
    }

    @Test
    public void protectionBoundaryStatusTwoIsNotTreatedAsFinalProtectedFailure()
            throws Exception {
        final SabrDecodedResponse decoded = read(new UmpFixture()
                .part(SabrResponseDecoder.STREAM_PROTECTION_STATUS, streamProtection(2, 2)))
                .getDecodedResponse();

        assertTrue(decoded.isNoMediaResponse());
        assertTrue(decoded.isProtectionBoundaryNoMediaResponse());
        assertFalse(decoded.isProtectedNoMediaResponse());
    }

    @Test
    public void mediaResponseWithPolicyIsNotClassifiedAsNoMedia() throws Exception {
        final SabrDecodedResponse decoded = read(new UmpFixture()
                .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(500))
                .segment(1, AUDIO_ITAG, 1))
                .getDecodedResponse();

        assertFalse(decoded.isNoMediaResponse());
        assertFalse(decoded.isPolicyOnlyResponse());
        assertEquals(500, decoded.getBackoffTimeMs());
        assertTrue(decoded.getIntegrityIssues().isEmpty());
    }

    @Test
    public void redirectReloadErrorAndSnackbarControlsDecodeIndependently() throws Exception {
        final SabrDecodedResponse decoded = read(new UmpFixture()
                .part(SabrResponseDecoder.SABR_REDIRECT, redirect("https://redirect.test/sabr"))
                .part(SabrResponseDecoder.RELOAD_PLAYER_RESPONSE, reload("reload-token"))
                .part(SabrResponseDecoder.SABR_ERROR, sabrError("blocked", 403))
                .part(SabrResponseDecoder.SNACKBAR_MESSAGE, snackbar(12))
                .part(SabrResponseDecoder.REQUEST_IDENTIFIER, requestIdentifier("request-token")))
                .getDecodedResponse();

        assertEquals("https://redirect.test/sabr", decoded.getRedirectUrl());
        assertTrue(decoded.isReloadRequested());
        assertEquals("reload-token",
                decoded.getReloadPlayerResponse().getReloadPlaybackParamsToken());
        assertEquals("blocked", decoded.getSabrErrorDetails().getType());
        assertEquals(403, decoded.getSabrErrorDetails().getCode());
        assertEquals(12, decoded.getSnackbarMessage().getId());
        assertEquals("request-token", decoded.getRequestIdentifier().getToken());
    }

    @Test
    public void unknownAndGenericPartsRemainDiagnosticOnly() throws Exception {
        final SabrStreamingResponseReader.Result result = read(new UmpFixture()
                .part(99, proto().u64(1, 7).bytes())
                .part(SabrResponseDecoder.CONFIG, proto().u64(2, 9).bytes())
                .segment(1, AUDIO_ITAG, 1));
        final SabrDecodedResponse decoded = result.getDecodedResponse();

        assertTrue(decoded.getUnknownPartTypes().contains(99));
        assertTrue(decoded.getGenericPartDescriptions()
                .containsKey(SabrResponseDecoder.CONFIG));
        assertEquals(1, result.getSegmentCount());
        assertTrue(decoded.getIntegrityIssues().isEmpty());
    }

    @Test
    public void nextRequestPolicyUpdatesBackoffCookieAndVideoId() throws Exception {
        final byte[] cookie = playbackCookie();
        final SabrDecodedResponse decoded = read(new UmpFixture()
                .part(SabrResponseDecoder.NEXT_REQUEST_POLICY,
                        nextRequestPolicy(2_000, cookie, "video-id")))
                .getDecodedResponse();
        final YoutubeSabrStreamState state = newState();

        state.ingest(decoded);
        assertEquals(2_000, state.getNextRequestPolicy().getBackoffTimeMs());
        assertEquals("video-id", state.getNextRequestPolicy().getVideoId());
        assertArrayEquals(cookie, state.getPlaybackCookie());
    }

    @Test
    public void contextUpdateAndSendingPolicyDriveActiveAndUnsentContexts()
            throws Exception {
        final YoutubeSabrStreamState state = newState();

        state.ingest(read(new UmpFixture()
                .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                        contextUpdate(10, new byte[]{1}, true,
                                SabrContextUpdate.WRITE_POLICY_OVERWRITE))
                .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                        contextUpdate(20, new byte[]{2}, false,
                                SabrContextUpdate.WRITE_POLICY_OVERWRITE)))
                .getDecodedResponse());
        assertEquals(1, state.getActiveSabrContexts().size());
        assertEquals(1, state.getUnsentSabrContextTypes().size());
        assertTrue(state.getUnsentSabrContextTypes().contains(20));

        state.ingest(read(new UmpFixture()
                .part(SabrResponseDecoder.SABR_CONTEXT_SENDING_POLICY,
                        contextPolicy(new int[]{20}, new int[]{10}, new int[0])))
                .getDecodedResponse());
        assertEquals(1, state.getActiveSabrContexts().size());
        assertEquals(20, state.getActiveSabrContexts().iterator().next().getType());
        assertTrue(state.getUnsentSabrContextTypes().contains(10));

        state.ingest(read(new UmpFixture()
                .part(SabrResponseDecoder.SABR_CONTEXT_SENDING_POLICY,
                        contextPolicy(new int[0], new int[0], new int[]{20})))
                .getDecodedResponse());
        assertTrue(state.getActiveSabrContexts().isEmpty());
        assertFalse(state.getUnsentSabrContextTypes().contains(20));
    }

    @Test
    public void contextKeepExistingDoesNotOverwriteExistingValue() throws Exception {
        final YoutubeSabrStreamState state = newState();

        state.ingest(read(new UmpFixture()
                .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                        contextUpdate(10, new byte[]{1, 2, 3}, true,
                                SabrContextUpdate.WRITE_POLICY_OVERWRITE)))
                .getDecodedResponse());
        state.ingest(read(new UmpFixture()
                .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                        contextUpdate(10, new byte[]{9}, true,
                                SabrContextUpdate.WRITE_POLICY_KEEP_EXISTING)))
                .getDecodedResponse());

        assertArrayEquals(new byte[]{1, 2, 3},
                state.getActiveSabrContexts().iterator().next().getValue());
    }

    @Test
    public void liveMetadataTracksHeadAndLiveEdge() throws Exception {
        final YoutubeSabrStreamState state = newState();
        state.setVideoOnlyRequestMode();

        state.ingest(read(new UmpFixture()
                .part(SabrResponseDecoder.LIVE_METADATA,
                        liveMetadata(40, 200_000, true)))
                .getDecodedResponse());
        assertTrue(state.isLive());
        assertTrue(state.isPostLiveDvr());
        assertEquals(40, state.getLiveHeadSequenceNumber());
        assertEquals(200_000, state.getLiveHeadTimeMs());
        assertFalse(state.isAtLiveEdge(audioFormat(), videoFormat()));

        state.ingest(read(new UmpFixture()
                .mediaHeader(1, VIDEO_ITAG, 38, 185_000, 5_000)
                .media(1)
                .mediaEnd(1))
                .getDecodedResponse());
        assertFalse("live edge is the slower enabled track; audio is still behind",
                state.isAtLiveEdge(audioFormat(), videoFormat()));

        state.ingest(read(new UmpFixture()
                .mediaHeader(2, AUDIO_ITAG, 38, 185_000, 5_000)
                .media(2)
                .mediaEnd(2))
                .getDecodedResponse());
        assertTrue(state.isAtLiveEdge(audioFormat(), videoFormat()));
    }

    @Test
    public void initializationMetadataDerivesTimingFromDurationAndEndSegment()
            throws Exception {
        final YoutubeSabrStreamState state = newState();

        state.ingest(read(new UmpFixture()
                .part(SabrResponseDecoder.FORMAT_INITIALIZATION_METADATA,
                        initializationMetadata(AUDIO_ITAG, 60, 0, 0, 300_000, "audio/mp4")))
                .getDecodedResponse());

        assertEquals(60, state.getEndSegment(audioFormat()));
        assertEquals(50_000, state.getSegmentStartMs(audioFormat(), 11));
        assertEquals(51, state.getSegmentNumberAtOrAfterTimeMs(audioFormat(), 250_000));
    }

    @Test
    public void bufferedRangeFallsBackToContiguousEndWhenSegmentsHaveAHole()
            throws Exception {
        final YoutubeSabrStreamState state = newState();
        state.setAudioOnlyRequestMode();

        state.ingest(read(new UmpFixture()
                .mediaHeader(9, AUDIO_ITAG, 0, 0, 0, true)
                .media(9)
                .mediaEnd(9)
                .mediaHeader(1, AUDIO_ITAG, 1, 0, 5_000)
                .media(1)
                .mediaEnd(1)
                .mediaHeader(3, AUDIO_ITAG, 3, 10_000, 5_000)
                .media(3)
                .mediaEnd(3))
                .getDecodedResponse());

        assertEquals(3, state.getMaxSegment(audioFormat()));
        assertEquals(5_000, state.getBufferedEndMs(audioFormat()));
        final String ranges = state.summarizeBufferedRanges();
        assertTrue(ranges, ranges.contains("seq=1-1"));
        assertTrue(ranges, ranges.contains("time=0+5000"));
    }

    @Test
    public void contiguousBufferedRangeUsesObservedTiming() throws Exception {
        final YoutubeSabrStreamState state = newState();
        state.setAudioOnlyRequestMode();

        state.ingest(read(new UmpFixture()
                .mediaHeader(9, AUDIO_ITAG, 0, 0, 0, true)
                .media(9)
                .mediaEnd(9)
                .mediaHeader(1, AUDIO_ITAG, 1, 0, 5_000)
                .media(1)
                .mediaEnd(1)
                .mediaHeader(2, AUDIO_ITAG, 2, 5_000, 5_000)
                .media(2)
                .mediaEnd(2))
                .getDecodedResponse());

        final String ranges = state.summarizeBufferedRanges();
        assertTrue(ranges, ranges.contains("seq=1-2"));
        assertTrue(ranges, ranges.contains("time=0+10000"));
    }

    @Test
    public void rewindAndForwardJumpResetBufferedRangesAroundTarget()
            throws Exception {
        final YoutubeSabrStreamState state = newState();
        state.setAudioOnlyRequestMode();
        state.ingest(read(new UmpFixture()
                .mediaHeader(9, AUDIO_ITAG, 0, 0, 0, true)
                .media(9)
                .mediaEnd(9)
                .mediaHeader(1, AUDIO_ITAG, 1, 0, 5_000)
                .media(1)
                .mediaEnd(1)
                .mediaHeader(2, AUDIO_ITAG, 2, 5_000, 5_000)
                .media(2)
                .mediaEnd(2)
                .mediaHeader(3, AUDIO_ITAG, 3, 10_000, 5_000)
                .media(3)
                .mediaEnd(3))
                .getDecodedResponse());

        state.rewindBufferedTo(audioFormat(), 2);
        assertEquals(1, state.getMaxSegment(audioFormat()));
        assertEquals(5_000, state.getBufferedEndMs(audioFormat()));
        assertTrue(state.summarizeBufferedRanges().contains("seq=1-1"));

        state.jumpBufferedTo(audioFormat(), 8);
        assertEquals(7, state.getMaxSegment(audioFormat()));
        assertEquals(35_000, state.getBufferedEndMs(audioFormat()));
        assertTrue(state.summarizeBufferedRanges().contains("seq=1-7"));
    }

    @Test
    public void firstRequestIncludesInitialPlaybackStateOnlyAfterInitialization()
            throws Exception {
        final YoutubeSabrInfo info = sabrInfo();
        final YoutubeSabrStreamState state = newState();
        state.setPlayerTimeMs(50_000);

        final List<SabrProto.Field> cold = SabrProto.readFields(
                YoutubeSabrRequestBuilder.buildFirstMediaRequest(
                        info, audioFormat(), videoFormat(), state));
        assertEquals(0, repeatedFields(cold, 2));
        assertEquals(0, repeatedFields(cold, 3));
        assertEquals(0L, clientAbrState(cold).get(28L).longValue());
        assertEquals(0, repeatedFields(cold, 4));

        state.ingest(read(new UmpFixture()
                .mediaHeader(1, AUDIO_ITAG, 0, 0, 0, true)
                .media(1)
                .mediaEnd(1)
                .mediaHeader(2, VIDEO_ITAG, 0, 0, 0, true)
                .media(2)
                .mediaEnd(2)
                .mediaHeader(3, AUDIO_ITAG, 10, 45_000, 5_000)
                .media(3)
                .mediaEnd(3)
                .mediaHeader(4, VIDEO_ITAG, 10, 45_000, 5_000)
                .media(4)
                .mediaEnd(4))
                .getDecodedResponse());

        final List<SabrProto.Field> warm = SabrProto.readFields(
                YoutubeSabrRequestBuilder.buildFirstMediaRequest(
                        info, audioFormat(), videoFormat(), state));
        assertEquals(2, repeatedFields(warm, 2));
        assertEquals(2, repeatedFields(warm, 3));
        assertEquals(50_000L, clientAbrState(warm).get(28L).longValue());
        assertEquals(50_000L, firstVarint(warm, 4));
    }

    @Test
    public void firstRequestHonorsVideoFirstAndTrackSelectionState()
            throws Exception {
        final YoutubeSabrStreamState state = initializedState();
        state.setVideoOnlyRequestMode();
        state.setSelectVideoFormatBeforeAudio(true);
        state.setPlayerTimeMs(25_000);

        final List<SabrProto.Field> fields = SabrProto.readFields(
                YoutubeSabrRequestBuilder.buildFirstMediaRequest(
                        sabrInfo(), audioFormat(), videoFormat(), state));

        assertEquals(1, repeatedFields(fields, 2));
        assertEquals(VIDEO_ITAG, decodeFormatItag(fieldsWithNumber(fields, 2).get(0).getBytes()));
        assertEquals(0, repeatedFields(fields, 16));
        assertEquals(1, repeatedFields(fields, 17));
        assertEquals(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY,
                clientAbrState(fields).get(40L).intValue());
    }

    @Test
    public void streamerContextCarriesPoTokenPlaybackCookieAndContexts()
            throws Exception {
        final YoutubeSabrStreamState state = initializedState();
        state.setPoToken(new byte[]{1, 2, 3});
        final byte[] cookie = playbackCookie();
        state.ingest(read(new UmpFixture()
                .part(SabrResponseDecoder.NEXT_REQUEST_POLICY,
                        nextRequestPolicy(0, cookie, "video-id"))
                .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                        contextUpdate(77, new byte[]{7, 7}, true,
                                SabrContextUpdate.WRITE_POLICY_OVERWRITE))
                .part(SabrResponseDecoder.SABR_CONTEXT_UPDATE,
                        contextUpdate(88, new byte[]{8}, false,
                                SabrContextUpdate.WRITE_POLICY_OVERWRITE)))
                .getDecodedResponse());

        final List<SabrProto.Field> fields = SabrProto.readFields(
                YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
                        sabrInfo(), audioFormat(), videoFormat(), state));
        final List<SabrProto.Field> streamerContext = SabrProto.readFields(firstBytes(fields, 19));

        assertArrayEquals(new byte[]{1, 2, 3}, firstBytes(streamerContext, 2));
        assertArrayEquals(cookie, firstBytes(streamerContext, 3));
        assertEquals(1, repeatedFields(streamerContext, 5));
        assertEquals(1, repeatedFields(streamerContext, 6));
        assertEquals(88L, firstVarint(streamerContext, 6));
    }

    @Test
    public void requestCancellationPolicyAndPrewarmAreDecodedForDiagnostics()
            throws Exception {
        final SabrDecodedResponse decoded = read(new UmpFixture()
                .part(SabrResponseDecoder.REQUEST_CANCELLATION_POLICY,
                        cancellationPolicy())
                .part(SabrResponseDecoder.PREWARM_CONNECTION,
                        proto().message(1, proto().string(1, "cdn").u64(2, 1).bytes()).bytes()))
                .getDecodedResponse();

        assertNotNull(decoded.getRequestCancellationPolicy());
        assertEquals(1, decoded.getRequestCancellationPolicy().getItems().size());
        assertEquals(1500, decoded.getRequestCancellationPolicy()
                .getItems().get(0).getMinReadaheadMs());
        assertNotNull(decoded.getPrewarmConnection());
        assertEquals(1, decoded.getPrewarmConnection().getConnections().size());
    }

    @Test
    public void mediaHeaderFormatIdAndTimeRangeFallbackDecode() throws Exception {
        final byte[] formatId = proto().u64(1, VIDEO_ITAG).u64(2, 1234)
                .string(3, "xtags").bytes();
        final byte[] timeRange = proto().u64(1, 9_000).u64(2, 3_000).u64(3, 1000).bytes();
        final byte[] headerBytes = proto()
                .u64(1, 9)
                .message(13, formatId)
                .message(15, timeRange)
                .u64(14, 4)
                .bytes();

        final SabrMediaHeader header = SabrMediaHeader.decode(headerBytes);

        assertEquals(9, header.getHeaderId());
        assertEquals(VIDEO_ITAG, header.getItag());
        assertEquals(1234, header.getLastModified());
        assertEquals("xtags", header.getXtags());
        assertEquals(9_000, header.getStartMs());
        assertEquals(3_000, header.getDurationMs());
    }

    private static void assertRecoverableIntegrity(final UmpFixture fixture,
                                                   final String expectedIssue) throws Exception {
        final SabrDecodedResponse decoded = SabrResponseDecoder.decode(fixture.bytes());
        assertTrue(decoded.getIntegrityIssues().contains(expectedIssue));
        assertTrue(expectedIssue + " must be recoverable",
                isRecoverableIncomplete(decoded.getIntegrityIssues()));
    }

    private static boolean isRecoverableIncomplete(final List<String> issues) throws Exception {
        final Method recoverable = YoutubeSabrSession.class.getDeclaredMethod(
                "isRecoverableIncompleteMediaResponse", List.class);
        recoverable.setAccessible(true);
        return (Boolean) recoverable.invoke(null, issues);
    }

    private static SabrStreamingResponseReader.Result read(final UmpFixture fixture)
            throws Exception {
        return SabrStreamingResponseReader.read(new ByteArrayInputStream(fixture.bytes()));
    }

    private static YoutubeSabrStreamState newState() throws Exception {
        return new YoutubeSabrStreamState(audioFormat(), videoFormat());
    }

    private static YoutubeSabrStreamState initializedState() throws Exception {
        final YoutubeSabrStreamState state = newState();
        state.ingest(read(new UmpFixture()
                .mediaHeader(1, AUDIO_ITAG, 0, 0, 0, true)
                .media(1)
                .mediaEnd(1)
                .mediaHeader(2, VIDEO_ITAG, 0, 0, 0, true)
                .media(2)
                .mediaEnd(2))
                .getDecodedResponse());
        return state;
    }

    private static YoutubeSabrInfo sabrInfo() throws Exception {
        return new YoutubeSabrInfo(YoutubeSabrClientProfile.MWEB, "video-id", "cpn",
                "2.20250122.04.00", "visitor", "https://sabr.test",
                base64(USTREAMER_CONFIG),
                Arrays.asList(audioFormat(), videoFormat()));
    }

    private static YoutubeSabrFormat audioFormat() throws Exception {
        return format(AUDIO_ITAG, "audio/mp4", -1, -1, 128_000);
    }

    private static YoutubeSabrFormat videoFormat() throws Exception {
        return format(VIDEO_ITAG, "video/webm", 1920, 1080, 2_000_000);
    }

    private static YoutubeSabrFormat format(final int itag,
                                            final String mimeType,
                                            final int width,
                                            final int height,
                                            final int bitrate) throws Exception {
        final JsonObject object = new JsonObject();
        object.put("itag", itag);
        object.put("lastModified", "123456");
        object.put("xtags", itag == AUDIO_ITAG ? "audio-xtags" : "video-xtags");
        object.put("mimeType", mimeType);
        object.put("bitrate", bitrate);
        object.put("contentLength", "100000");
        object.put("approxDurationMs", "300000");
        object.put("url", "https://media.test/" + itag);
        if (width > 0) {
            object.put("width", width);
            object.put("height", height);
            object.put("qualityLabel", height + "p");
        } else {
            final JsonObject audioTrack = new JsonObject();
            audioTrack.put("id", "audio-track");
            audioTrack.put("displayName", "English original");
            audioTrack.put("audioIsDefault", true);
            object.put("audioQuality", "AUDIO_QUALITY_MEDIUM");
            object.put("audioTrack", audioTrack);
        }
        final JsonArray array = new JsonArray();
        array.add(object);
        return YoutubeSabrFormat.fromAdaptiveFormats("video-id", array).get(0);
    }

    private static Map<Long, Long> clientAbrState(final List<SabrProto.Field> requestFields)
            throws Exception {
        return varintMap(SabrProto.readFields(firstBytes(requestFields, 1)));
    }

    private static Map<Long, Long> varintMap(final List<SabrProto.Field> fields) {
        final java.util.LinkedHashMap<Long, Long> values = new java.util.LinkedHashMap<>();
        for (final SabrProto.Field field : fields) {
            if (field.getWireType() == SabrProto.WIRE_VARINT) {
                values.put((long) field.getNumber(), field.getVarint());
            }
        }
        return values;
    }

    private static List<SabrProto.Field> fieldsWithNumber(final List<SabrProto.Field> fields,
                                                          final int number) {
        final java.util.ArrayList<SabrProto.Field> matches = new java.util.ArrayList<>();
        for (final SabrProto.Field field : fields) {
            if (field.getNumber() == number) {
                matches.add(field);
            }
        }
        return matches;
    }

    private static int repeatedFields(final List<SabrProto.Field> fields, final int number) {
        return fieldsWithNumber(fields, number).size();
    }

    private static byte[] firstBytes(final List<SabrProto.Field> fields, final int number)
            throws Exception {
        final List<SabrProto.Field> matches = fieldsWithNumber(fields, number);
        assertFalse("Missing field " + number, matches.isEmpty());
        return matches.get(0).getBytes();
    }

    private static long firstVarint(final List<SabrProto.Field> fields, final int number) {
        final List<SabrProto.Field> matches = fieldsWithNumber(fields, number);
        assertFalse("Missing field " + number, matches.isEmpty());
        return matches.get(0).getVarint();
    }

    private static int decodeFormatItag(final byte[] formatId) throws Exception {
        return (int) firstVarint(SabrProto.readFields(formatId), 1);
    }

    private static byte[] playbackCookie() {
        return proto()
                .u64(1, 1080)
                .u64(2, 1)
                .message(7, formatId(VIDEO_ITAG))
                .message(8, formatId(AUDIO_ITAG))
                .bytes();
    }

    private static byte[] formatId(final int itag) {
        return proto().u64(1, itag).u64(2, 123456).bytes();
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
        return proto().u64(1, status).u64(2, maxRetries).bytes();
    }

    private static byte[] redirect(final String url) {
        return proto().string(1, url).bytes();
    }

    private static byte[] reload(final String token) {
        return proto().message(1, proto().message(1, proto().string(1, token).bytes()).bytes())
                .bytes();
    }

    private static byte[] sabrError(final String type, final int code) {
        return proto().string(1, type).u64(2, code).bytes();
    }

    private static byte[] snackbar(final int id) {
        return proto().u64(1, id).bytes();
    }

    private static byte[] requestIdentifier(final String token) {
        return proto().string(1, token).bytes();
    }

    private static byte[] contextUpdate(final int type,
                                        final byte[] value,
                                        final boolean sendByDefault,
                                        final int writePolicy) {
        return proto().u64(1, type)
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

    private static byte[] liveMetadata(final long headSeq,
                                       final long headTimeMs,
                                       final boolean postLiveDvr) {
        return proto()
                .string(1, "broadcast")
                .u64(3, headSeq)
                .u64(4, headTimeMs)
                .u64(5, headTimeMs + 1000)
                .string(6, "video-id")
                .u64(8, postLiveDvr ? 1 : 0)
                .u64(12, 0)
                .u64(13, 1000)
                .u64(14, headTimeMs)
                .u64(15, 1000)
                .bytes();
    }

    private static byte[] initializationMetadata(final int itag,
                                                 final long endSegment,
                                                 final long durationUnits,
                                                 final long durationTimescale,
                                                 final long endTimeMs,
                                                 final String mimeType) {
        return proto()
                .message(2, proto().u64(1, itag).u64(2, 123456).bytes())
                .u64(3, endTimeMs)
                .u64(4, endSegment)
                .string(5, mimeType)
                .u64(9, durationUnits)
                .u64(10, durationTimescale)
                .bytes();
    }

    private static byte[] cancellationPolicy() {
        return proto()
                .u64(1, 1)
                .message(2, proto().u64(1, 2).u64(2, 3).u64(3, 1500).bytes())
                .u64(3, 4)
                .bytes();
    }

    private static Proto proto() {
        return new Proto();
    }

    private static String base64(final byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    private static final class UmpFixture {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private UmpFixture segment(final int headerId, final int itag, final int sequence) {
            return mediaHeader(headerId, itag, sequence).media(headerId).mediaEnd(headerId);
        }

        private UmpFixture mediaHeader(final int headerId, final int itag, final int sequence) {
            return mediaHeader(headerId, itag, sequence, (long) (sequence - 1) * 5_000L,
                    5_000L);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs, false);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs,
                                       final boolean init) {
            final Proto header = proto()
                    .u64(1, headerId)
                    .u64(3, itag)
                    .u64(4, 123456)
                    .u64(8, init ? 1 : 0)
                    .u64(9, sequence)
                    .u64(11, Math.max(0, startMs))
                    .u64(12, Math.max(0, durationMs))
                    .u64(14, 4);
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
            writeTag(field, SabrProto.WIRE_VARINT);
            writeVarint(output, value);
            return this;
        }

        private Proto string(final int field, final String value) {
            return message(field, value.getBytes(StandardCharsets.UTF_8));
        }

        private Proto message(final int field, final byte[] value) {
            writeTag(field, SabrProto.WIRE_LENGTH_DELIMITED);
            writeVarint(output, value.length);
            output.write(value, 0, value.length);
            return this;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }

        private void writeTag(final int field, final int wireType) {
            writeVarint(output, ((long) field << 3) | wireType);
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
}
