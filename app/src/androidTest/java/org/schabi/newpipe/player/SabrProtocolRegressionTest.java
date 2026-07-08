package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrDecodedResponse;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrResponseDecoder;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrStreamingResponseReader;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class SabrProtocolRegressionTest {
    @Test
    public void malformedControlPartDoesNotDiscardResponse() throws Exception {
        // NEXT_REQUEST_POLICY containing protobuf tag 1 / invalid wire type 7.
        final SabrDecodedResponse decoded = SabrResponseDecoder.decode(
                new byte[]{35, 1, 0x0f, 30, 0});

        assertEquals(1, decoded.getMalformedParts().size());
        assertTrue(decoded.getMalformedParts().get(0).startsWith("35:1:"));
    }

    @Test
    public void malformedMediaHeaderBecomesRecoverableIntegrityFailure() throws Exception {
        final ByteArrayOutputStream ump = new ByteArrayOutputStream();
        writePart(ump, 20, new byte[]{0x0f});
        writePart(ump, 21, new byte[]{1, 10, 11});
        writePart(ump, 22, new byte[]{1});

        final SabrStreamingResponseReader.Result result = SabrStreamingResponseReader.read(
                new ByteArrayInputStream(ump.toByteArray()));

        assertEquals(1, result.getDecodedResponse().getMalformedParts().size());
        assertTrue(result.getDecodedResponse().getIntegrityIssues()
                .contains("media-without-header:1"));
        final Method recoverable = YoutubeSabrSession.class.getDeclaredMethod(
                "isRecoverableIncompleteMediaResponse", List.class);
        recoverable.setAccessible(true);
        assertTrue("malformed media header would terminate playback instead of retrying",
                (Boolean) recoverable.invoke(null,
                        result.getDecodedResponse().getIntegrityIssues()));
    }

    @Test
    public void streamingConsumerDoesNotRetainCompletedBatch() throws Exception {
        final ByteArrayOutputStream ump = new ByteArrayOutputStream();
        for (int id = 1; id <= 3; id++) {
            writePart(ump, 20, mediaHeader(id, id));
            writeMedia(ump, id);
            writePart(ump, 22, new byte[]{(byte) id});
        }
        final AtomicInteger delivered = new AtomicInteger();

        final SabrStreamingResponseReader.Result result = SabrStreamingResponseReader.read(
                new ByteArrayInputStream(ump.toByteArray()), segment -> delivered.incrementAndGet());

        assertEquals(3, delivered.get());
        assertEquals(3, result.getSegmentCount());
        assertTrue("streaming production path retained the response batch",
                result.getSegments().isEmpty());
        assertTrue(result.getDecodedResponse().getIntegrityIssues().isEmpty());
    }

    @Test
    public void stoppingAtTargetAfterNextHeaderLeavesRecoverableIncompleteResponse()
            throws Exception {
        final ByteArrayOutputStream ump = new ByteArrayOutputStream();
        writePart(ump, 20, mediaHeader(1, 1));
        writePart(ump, 20, mediaHeader(2, 2));
        writeMedia(ump, 1);
        writePart(ump, 22, new byte[]{1});
        writeMedia(ump, 2);
        writePart(ump, 22, new byte[]{2});

        final SabrStreamingResponseReader.Result full = SabrStreamingResponseReader.read(
                new ByteArrayInputStream(ump.toByteArray()));
        assertEquals(2, full.getSegmentCount());
        assertTrue(full.getDecodedResponse().getIntegrityIssues().isEmpty());

        final AtomicInteger delivered = new AtomicInteger();
        final SabrStreamingResponseReader.Result stopped = SabrStreamingResponseReader.readUntil(
                new ByteArrayInputStream(ump.toByteArray()), segment -> {
                    delivered.incrementAndGet();
                    return false;
                });

        assertEquals(1, delivered.get());
        assertTrue(stopped.getDecodedResponse().getIntegrityIssues()
                .contains("missing-media:2"));
        assertTrue(stopped.getDecodedResponse().getIntegrityIssues()
                .contains("missing-media-end:2"));
        final Method recoverable = YoutubeSabrSession.class.getDeclaredMethod(
                "isRecoverableIncompleteMediaResponse", List.class);
        recoverable.setAccessible(true);
        assertTrue("early target stop must remain a recoverable incomplete response",
                (Boolean) recoverable.invoke(null,
                        stopped.getDecodedResponse().getIntegrityIssues()));
    }

    @Test
    public void policyOnlyBackoffResponseIsRecognizedAsNoMedia() throws Exception {
        final ByteArrayOutputStream ump = new ByteArrayOutputStream();
        writePart(ump, 35, new byte[]{0x20, (byte) 0x88, 0x27});

        final SabrStreamingResponseReader.Result result = SabrStreamingResponseReader.read(
                new ByteArrayInputStream(ump.toByteArray()));

        assertEquals(0, result.getSegmentCount());
        assertTrue(result.getDecodedResponse().isNoMediaResponse());
        assertTrue(result.getDecodedResponse().isPolicyOnlyResponse());
        assertEquals(5_000, result.getDecodedResponse().getBackoffTimeMs());
        assertTrue(result.getDecodedResponse().getIntegrityIssues().isEmpty());
    }

    private static byte[] mediaHeader(final int headerId, final int sequence) {
        return new byte[]{
                0x08, (byte) headerId,             // header id
                0x18, (byte) 0x8c, 0x01,           // itag 140
                0x48, (byte) sequence,             // sequence
                0x70, 0x04                         // content length
        };
    }

    private static void writeMedia(final ByteArrayOutputStream output, final int headerId) {
        writePart(output, 21, new byte[]{(byte) headerId, 10, 11, 12, 13});
    }

    private static void writePart(final ByteArrayOutputStream output,
                                  final int type,
                                  final byte[] payload) {
        if (type >= 128 || payload.length >= 128) {
            throw new IllegalArgumentException("test helper only supports one-byte UMP integers");
        }
        output.write(type);
        output.write(payload.length);
        output.write(payload, 0, payload.length);
    }
}
