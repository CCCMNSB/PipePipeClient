package org.schabi.newpipe.subtitles;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.subtitles.AssSubtitleParser;
import org.schabi.newpipe.player.subtitles.SubtitleLine;

import java.io.ByteArrayOutputStream;import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A transparent helper that opens the system file picker for a local .ass/.srt subtitle file, reads
 * it, parses it with {@link AssSubtitleParser} and hands the resulting lines to the active
 * {@link Player} for overlay display. The player runs in a Service (popup), which cannot receive a
 * picker result itself, so this tiny Activity does the picking and injects the parsed lines.
 */
public final class LocalSubtitlePickerActivity extends Activity {

    private static final int REQUEST_OPEN_FILE = 1001;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Translucent_NoTitleBar);
        super.onCreate(savedInstanceState);
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // \*/* so users can pick .ass / .srt (whose mime is often text/plain or octet-stream).
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_OPEN_FILE);
        } catch (final RuntimeException e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_FILE && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            loadAndInject(data.getData());
        } else {
            finish();
        }
    }

    private void loadAndInject(final Uri uri) {
        final String content = readText(uri);
        if (content == null || content.trim().isEmpty()) {
            Toast.makeText(this, "读取本地字幕失败或内容为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final List<SubtitleLine> lines = AssSubtitleParser.parse(content);
        final Player player = PlayerHolder.getInstance().getPlayer();
        if (player == null) {
            Toast.makeText(this, "请先播放视频，再导入本地字幕", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        player.showLocalSubtitleLines(lines);
        finish();
    }

    @androidx.annotation.Nullable
    private String readText(final Uri uri) {
        try (final InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            final byte[] bytes = baos.toByteArray();
            // Handle BOMs so GBK/UTF-16 exports that carry one still decode cleanly.
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            }
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
            }
            String s = new String(bytes, StandardCharsets.UTF_8);
            if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
                s = s.substring(1);
            }
            return s;
        } catch (final IOException e) {
            return null;
        }
    }
}
