package com.poupa.vinylmusicplayer.onedrive;

import android.media.MediaDataSource;
import android.os.Build;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.poupa.vinylmusicplayer.model.Song;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Plays Ogg files that hold several streams (e.g. a recording with Theora video and Vorbis
 * audio), which Android refuses as a whole: the player is given the audio stream only
 * ({@link OggAudioOnly}).
 */
public final class MultiStreamOgg {

    /** Player uri of a local file to play through its audio-only view. */
    public static final String LOCAL_SCHEME = "vinyl-ogg-audio:";

    private static final int HEAD_BYTES = 64 * 1024;

    /** Per file (path, size, date): whether it holds several streams. */
    private static final LruCache<String, Boolean> MULTIPLEXED = new LruCache<>(256);
    /** Per file: the page index, so replaying or seeking back does not scan again. */
    private static final LruCache<String, OggAudioOnly> VIEWS = new LruCache<>(8);

    private MultiStreamOgg() {}

    static boolean isOggName(@NonNull String fileName) {
        final String ext = AudioFormats.extension(fileName);
        return ext.equals("ogg") || ext.equals("oga") || ext.equals("ogx") || ext.equals("ogv") || ext.equals("opus");
    }

    /**
     * The uri the player should open for a local song: {@code defaultUri}, unless the song is an
     * Ogg file with several streams. Reads the start of Ogg files once (remembered).
     */
    @NonNull
    public static String trackUri(@NonNull Song song, @NonNull String defaultUri) {
        return trackUri(song.data, defaultUri);
    }

    /** {@link #trackUri(Song, String)} for a file path (e.g. a OneDrive song's local copy). */
    @NonNull
    public static String trackUri(@NonNull String path, @NonNull String defaultUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !isOggName(path)) return defaultUri;
        final File file = new File(path);
        final String key = key(file);
        Boolean multiplexed = MULTIPLEXED.get(key);
        if (multiplexed == null) {
            multiplexed = false;
            try (InputStream in = new java.io.FileInputStream(file)) {
                final byte[] head = new byte[HEAD_BYTES];
                int n = 0;
                int read;
                while (n < head.length && (read = in.read(head, n, head.length - n)) > 0) n += read;
                multiplexed = OggAudioOnly.isMultiplexed(head, n);
            } catch (IOException | RuntimeException ignored) {
                // unreadable by path: let the player try the file as it is
            }
            MULTIPLEXED.put(key, multiplexed);
        }
        return multiplexed ? LOCAL_SCHEME + path : defaultUri;
    }

    public static boolean isLocalUri(@NonNull String uri) {
        return uri.startsWith(LOCAL_SCHEME);
    }

    /** The audio-only view of a local multi-stream Ogg file, for the player. */
    @RequiresApi(23)
    @NonNull
    public static MediaDataSource openLocal(@NonNull String uri) throws IOException {
        final File file = new File(uri.substring(LOCAL_SCHEME.length()));
        final RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            final OggAudioOnly.Source source = channelSource(raf.getChannel());
            final String key = key(file);
            OggAudioOnly view = VIEWS.get(key);
            if (view == null) {
                view = OggAudioOnly.scan(source, raf.length());
                if (view == null) throw new IOException("No playable audio stream in " + file.getName());
                VIEWS.put(key, view);
            }
            return new OggAudioDataSource(view, source, raf);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    /** Wraps a OneDrive stream when it is a multi-stream Ogg file; the stream must be complete. */
    @RequiresApi(23)
    @NonNull
    static MediaDataSource wrapIfMultiplexed(@NonNull MediaDataSource raw, @NonNull String fileName) throws IOException {
        if (!isOggName(fileName)) return raw;
        final OggAudioOnly.Source source = (position, buffer, offset, length) -> {
            int done = 0;
            while (done < length) {
                final int n = raw.readAt(position + done, buffer, offset + done, length - done);
                if (n <= 0) throw new EOFException();
                done += n;
            }
        };
        final long size = raw.getSize();
        final byte[] head = new byte[(int) Math.min(size, HEAD_BYTES)];
        source.readFully(0, head, 0, head.length);
        if (!OggAudioOnly.isMultiplexed(head, head.length)) return raw;
        final OggAudioOnly view = OggAudioOnly.scan(source, size);
        return view == null ? raw : new OggAudioDataSource(view, source, raw);
    }

    /** Positional reads: safe from the player's reading threads at once. */
    @NonNull
    static OggAudioOnly.Source channelSource(@NonNull FileChannel channel) {
        return (position, buffer, offset, length) -> {
            final ByteBuffer target = ByteBuffer.wrap(buffer, offset, length);
            long at = position;
            while (target.hasRemaining()) {
                final int n = channel.read(target, at);
                if (n < 0) throw new EOFException();
                at += n;
            }
        };
    }

    @NonNull
    private static String key(@NonNull File file) {
        return file.getPath() + '|' + file.length() + '|' + file.lastModified();
    }
}
