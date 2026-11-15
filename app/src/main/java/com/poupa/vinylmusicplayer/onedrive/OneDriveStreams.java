package com.poupa.vinylmusicplayer.onedrive;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Keeps at most a few {@link OneDriveStream}s alive: the playing song, the prefetched next song,
 * and recently released ones for a short grace period (so going back a track does not download
 * it again, and a stream survives the hand-over from the playback thread to the decoder).
 */
@RequiresApi(23)
final class OneDriveStreams {

    /** How long an unused stream is kept before its download is stopped and its cache deleted. */
    private static final long GRACE_MS = 20_000;

    private final Context context;
    private final OneDriveClient client;
    private final OneDriveLibrary library;

    /** Runs ready callbacks and expiries off the caller's thread. */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "OneDriveStreams");
        t.setDaemon(true);
        return t;
    });

    // Guarded by this
    private final Map<Long, OneDriveStream> streams = new HashMap<>();
    @Nullable
    private OneDriveStream prefetched;

    OneDriveStreams(@NonNull Context context, @NonNull OneDriveLibrary library) {
        this.context = context.getApplicationContext();
        this.client = new OneDriveClient(this.context);
        this.library = library;
        deleteLeftovers();
    }

    /** Gets the stream of a song, starting its download when needed, and holds it. */
    @NonNull
    synchronized OneDriveStream acquire(long songId) throws IOException {
        OneDriveStream stream = streams.get(songId);
        if (stream == null || stream.isDead()) {
            final OneDriveLibrary.Entry entry = library.entry(songId);
            if (entry == null) throw new IOException("Unknown OneDrive song " + songId);
            stream = new OneDriveStream(context, client, library, executor, entry);
            streams.put(songId, stream);
            stream.start();
        }
        stream.refs++;
        return stream;
    }

    /** Lets go of a stream; unused streams expire after {@link #GRACE_MS}. */
    synchronized void release(@NonNull OneDriveStream stream) {
        if (stream.refs > 0) stream.refs--;
        if (stream.refs == 0) {
            executor.schedule(() -> expire(stream), GRACE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void expire(@NonNull OneDriveStream stream) {
        if (stream.refs > 0) return; // picked up again meanwhile
        if (streams.get(stream.songId) == stream) streams.remove(stream.songId);
        stream.destroy();
    }

    /** Whether the song can be handed to the decoder now. Never starts a download. */
    synchronized boolean isReady(long songId) {
        final OneDriveStream stream = streams.get(songId);
        return stream != null && stream.isReady();
    }

    /** Why the last stream of the song failed, if it did. */
    @Nullable
    synchronized String failure(long songId) {
        final OneDriveStream stream = streams.get(songId);
        return stream != null ? stream.failure() : null;
    }

    /**
     * Starts (or joins) the download of a song and blocks until the decoder can be prepared.
     * Call from the playback thread, never while holding the music service lock.
     */
    boolean awaitReady(long songId, long timeoutMs, @NonNull BooleanSupplier abort,
                       @Nullable java.util.function.Consumer<OneDrive.BufferingProgress> onProgress) {
        final OneDriveStream stream;
        try {
            stream = acquire(songId);
        } catch (IOException e) {
            return false;
        }
        try {
            return stream.awaitReady(timeoutMs, abort, onProgress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            release(stream); // the grace period keeps it until the decoder opens it
        }
    }

    /**
     * Downloads the next song ahead of time; {@code onReady} runs once it can be prepared, so it
     * can be set up for gapless playback. Replaces the previous prefetch.
     */
    void prefetch(long songId, @NonNull Runnable onReady) {
        final OneDriveStream previous;
        final OneDriveStream next;
        synchronized (this) {
            if (prefetched != null && prefetched.songId == songId && !prefetched.isDead()) {
                prefetched.whenReady(onReady);
                return;
            }
            previous = prefetched;
            try {
                next = acquire(songId);
            } catch (IOException e) {
                prefetched = null;
                if (previous != null) release(previous);
                return;
            }
            prefetched = next;
            if (previous != null) release(previous);
        }
        next.whenReady(onReady);
    }

    /** Opens a stream for the decoder. Holds it until the returned source is closed. */
    @NonNull
    OneDriveDataSource openDataSource(long songId) throws IOException {
        return new OneDriveDataSource(this, acquire(songId));
    }

    /** Deletes cache files of an earlier run (a stream never outlives the process). */
    private void deleteLeftovers() {
        final File[] files = library.cacheDir().listFiles();
        if (files == null) return;
        for (final File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
