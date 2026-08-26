package org.schabi.newpipe.player.bulletComments;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Standalone YouTube live-chat-replay danmaku downloader (what yt-dlp does). Reads the initial
 * replay continuation from the page, then paginates {@code live_chat/get_live_chat_replay} to the
 * end. Returns items whose {@code duration} = the video position (ms). Bypasses the bullet-comment
 * extractor, which treats a live archive as a live stream and cannot pull the whole replay.
 */
public final class YouTubeReplayDanmakuFetcher {

    /** Notified with the current page/collected count so the caller can show live progress. */
    public interface ProgressListener {
        void onProgress(int pages, int collected);
    }

    private YouTubeReplayDanmakuFetcher() {
    }

    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";

    public static List<BulletCommentsInfoItem> fetchAll(final String videoId) throws Exception {
        return fetchAll(videoId, null);
    }

    public static List<BulletCommentsInfoItem> fetchAll(final String videoId,
            final ProgressListener progress) throws Exception {
        final Downloader downloader = NewPipe.getDownloader();
        final String page = downloader.get(WATCH_URL + videoId).responseBody();

        String continuation = extractInitialContinuation(page);
        if (continuation == null) {
            throw new IllegalStateException("No replay continuation found (is live chat replay enabled?)");
        }

        final List<BulletCommentsInfoItem> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        int pages = 0;
        // The replay API returns the next chunk keyed off the player offset, so advance it to the
        // latest message offset seen on each page (what yt-dlp does). Keeping it at "0" makes the
        // server return the same first page forever -> the 67-items bug.
        long offsetMs = 0;
        String prevContinuation = null;
        int stallCount = 0;
        while (continuation != null && !continuation.isEmpty() && pages < 2000) {
            final JsonObject result = fetchPage(continuation, String.valueOf(offsetMs));
            if (result == null) {
                break;
            }
            final JsonObject continuationContents = result.getObject("continuationContents");
            final JsonObject liveChatContinuation = continuationContents == null
                    ? null : continuationContents.getObject("liveChatContinuation");
            if (liveChatContinuation == null) {
                break;
            }
            final JsonArray actions = liveChatContinuation.getArray("actions");
            long newOffsetMs = offsetMs;
            if (actions != null) {
                for (int i = 0; i < actions.size(); i++) {
                    final Object a = actions.get(i);
                    if (!(a instanceof JsonObject)
                            || !((JsonObject) a).has("replayChatItemAction")) {
                        continue;
                    }
                    final JsonObject replayAction = (JsonObject) a;
                    final JsonObject replayChatItemAction =
                            replayAction.getObject("replayChatItemAction");
                    // videoOffsetTimeMsec is already in milliseconds (the extractor uses it raw).
                    final long msgMs = (long) safeParse(
                            replayChatItemAction.getString("videoOffsetTimeMsec", "0"));
                    if (msgMs > newOffsetMs) {
                        newOffsetMs = msgMs;
                    }
                    final JsonArray innerActions = replayChatItemAction.getArray("actions");
                    if (innerActions == null || innerActions.size() == 0) {
                        continue;
                    }
                    final JsonObject addItem = innerActions.getObject(0)
                            .getObject("addChatItemAction");
                    if (addItem == null) {
                        continue;
                    }
                    final JsonObject item = addItem.getObject("item");
                    if (item == null || !item.has("liveChatTextMessageRenderer")) {
                        continue;
                    }
                    final JsonObject renderer = item.getObject("liveChatTextMessageRenderer");
                    final String id = renderer.getString("id", "");
                    if (!seen.add(id)) {
                        continue;
                    }
                    final String text = extractText(renderer);
                    final BulletCommentsInfoItem bc = new BulletCommentsInfoItem(
                            0, WATCH_URL + videoId, text);
                    bc.setCommentText(text);
                    bc.setDuration(Duration.ofMillis(msgMs));
                    bc.setPosition(BulletCommentsInfoItem.Position.REGULAR);
                    bc.setArgbColor(0xFFFFFFFF);
                    bc.setRelativeFontSize(1.0);
                    out.add(bc);
                }
            }
            final String next = findNextContinuation(liveChatContinuation);
            // Google may return a transient empty page (0 new messages, same continuation). Do not
            // treat one such page as the end; only stop after several consecutive no-progress
            // pages so a long replay is not cut short.
            if (next != null && next.equals(prevContinuation) && newOffsetMs == offsetMs) {
                stallCount++;
                if (stallCount >= 3) {
                    android.util.Log.d("YouTubeFetcher", "stuck at page " + pages
                            + " (continuation unchanged, offset unchanged)");
                    break;
                }
            } else {
                stallCount = 0;
            }
            prevContinuation = next;
            offsetMs = newOffsetMs;
            continuation = next;
            pages++;
            if (progress != null) {
                progress.onProgress(pages, out.size());
            }
            if (pages % 10 == 0) {
                android.util.Log.d("YouTubeFetcher", "page " + pages + " collected " + out.size()
                        + " offsetMs=" + offsetMs);
            }
        }
        android.util.Log.d("YouTubeFetcher", "fetchAll done: " + out.size() + " comments over "
                + pages + " pages");
        return out;
    }

    private static String extractText(final JsonObject renderer) {
        final StringBuilder sb = new StringBuilder();
        final JsonObject message = renderer.getObject("message");
        if (message != null) {
            final JsonArray runs = message.getArray("runs");
            if (runs != null) {
                for (int i = 0; i < runs.size(); i++) {
                    final Object r = runs.get(i);
                    if (r instanceof JsonObject && ((JsonObject) r).has("text")) {
                        sb.append(((JsonObject) r).getString("text"));
                    }
                }
            }
        }
        return sb.toString();
    }

    private static JsonObject fetchPage(final String continuation, final String offsetMs) throws Exception {
        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
                .value("continuation", continuation)
                .object("currentPlayerState")
                .value("playerOffsetMs", offsetMs)
                .end()
                .done())
                .getBytes("UTF-8");
        return getJsonPostResponse("live_chat/get_live_chat_replay", body, Localization.DEFAULT);
    }

    private static String findNextContinuation(final JsonObject liveChatContinuation) {
        final JsonArray continuations = liveChatContinuation.getArray("continuations");
        if (continuations == null || continuations.size() == 0) {
            return null;
        }
        // The extractor picks index 0, or index 1 when there are exactly two, and reads
        // playerSeekContinuationData for a replay. Matching it makes the pagination advance.
        final int idx = continuations.size() == 2 ? 1 : 0;
        final JsonObject c = continuations.getObject(idx);
        for (final String key : new String[]{
                "playerSeekContinuationData", "invalidationContinuationData",
                "timedContinuationData", "reloadContinuationData", "liveChatReplayContinuationData"}) {
            if (c.has(key)) {
                return c.getObject(key).getString("continuation");
            }
        }
        return c.getString("continuation", null);
    }

    private static String extractInitialContinuation(final String page) throws Exception {
        if (!page.contains("var ytInitialData = ")) {
            return null;
        }
        final String json = page.split(Pattern.quote("var ytInitialData = "))[1]
                .split(Pattern.quote(";</script>"))[0];
        final JsonObject initialData = JsonParser.object().from(json);
        final JsonObject liveChat = initialData
                .getObject("contents").getObject("twoColumnWatchNextResults")
                .getObject("conversationBar").getObject("liveChatRenderer");
        if (liveChat == null) {
            return null;
        }
        final JsonArray continuations = liveChat.getArray("continuations");
        if (continuations == null || continuations.size() == 0) {
            return null;
        }
        final JsonObject c = continuations.getObject(0);
        // The extractor starts the replay by "reloading" the whole chat, so the initial token is
        // the reload continuation. Prefer it so pagination begins from the start of the video.
        for (final String key : new String[]{
                "reloadContinuationData", "liveChatReplayContinuationData",
                "playerSeekContinuationData", "invalidationContinuationData"}) {
            if (c.has(key)) {
                return c.getObject(key).getString("continuation");
            }
        }
        return c.getString("continuation", null);
    }

    private static double safeParse(final String s) {
        try {
            return Double.parseDouble(s);
        } catch (final Exception e) {
            return 0;
        }
    }
}
