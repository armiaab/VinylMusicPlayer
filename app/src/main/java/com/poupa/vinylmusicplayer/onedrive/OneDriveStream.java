package com.poupa.vinylmusicplayer.onedrive;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One OneDrive file being downloaded into the cache while it plays.
 *
 * The download order follows what the decoder needs: first the head (tags, cover art, codec
 * headers), then the tail when the format keeps something there (an MP4 index after the audio,
 * the last Ogg page), then the rest in order. {@link #isReady()} turns true once the decoder can
 * be prepared without waiting; later reads past the downloaded part simply wait for it.
 *
 * When the file is complete it is saved for offline listening ({@link OfflineSaver}); from then
 * on the song plays from that local copy. The cache file itself is deleted when the stream is
 * no longer used.
 */
@RequiresApi(23)
final class OneDriveStream {

    private static final String TAG = "OneDriveStream";

    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAX_ATTEMPTS = 6;
    private static final long MAX_BACKOFF_MS = 8_000;

    final long songId;
    final long size;

    private final Context context;
    private final OneDriveClient client;
    private final OneDriveLibrary library;
    private final Executor callbacks;
    private final String itemId;
    private final String fileName;
    private final String folderPath;
    private final String extension;
    private final File file;

    private final Object lock = new Object();

    // Guarded by lock
    /** Bytes [0, contiguousEnd) are on disk. */
    private long contiguousEnd;
    @Nullable
    private FormatSniffer.Plan plan;
    private boolean tailDone;
    private boolean complete;
    private boolean failed;
    @Nullable
    private String failure;
    private boolean destroyed;
    private boolean workerDone;
    private boolean cleaned;
    private boolean readyFired;
    /** All bytes written so far (head, tail and middle): shows that the download progresses. */
    private long bytesReceived;
    private final List<Runnable> readyListeners = new ArrayList<>();

    /** Holders of this stream; guarded by {@link OneDriveStreams}. */
    int refs;

    private RandomAccessFile raf;
    private FileChannel channel;
    @Nullable
    private volatile HttpURLConnection activeConnection;

    OneDriveStream(@NonNull Context context, @NonNull OneDriveClient client, @NonNull OneDriveLibrary library,
                   @NonNull Executor callbacks, @NonNull OneDriveLibrary.Entry entry) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.library = library;
        this.callbacks = callbacks;
        this.songId = entry.songId;
        this.size = entry.size;
        this.itemId = entry.itemId;
        this.fileName = entry.name;
        this.folderPath = entry.folderPath;
        this.extension = entry.extension();
        this.file = library.cacheFile(entry);
    }

    /** Opens the cache file and starts downloading. */
    void start() {
        final Thread worker = new Thread(this::run, "OneDriveStream" + songId);
        worker.setDaemon(true);
        worker.start();
    }

    // --- state -----------------------------------------------------------------------------

    /** The decoder can be prepared without waiting for the network. */
    boolean isReady() {
        synchronized (lock) {
            return isReadyLocked();
        }
    }

    /** Failed or released for good: a new stream has to be started to play the song again. */
    boolean isDead() {
        synchronized (lock) {
            return failed || destroyed;
        }
    }

    @Nullable
    String failure() {
        synchronized (lock) {
            return failure;
        }
    }

    private boolean isReadyLocked() {
        if (complete) return true;
        if (plan == null || !plan.complete || contiguousEnd < plan.headBytes) return false;
        return !plan.needsTail(size) || tailDone || contiguousEnd >= plan.tailStart;
    }

    private boolean isCompleteLocked() {
        if (contiguousEnd >= size) return true;
        return plan != null && plan.needsTail(size) && tailDone && contiguousEnd >= plan.tailStart;
    }

    private boolean isAvailableLocked(long position, int length) {
        if (complete) return true;
        final long end = position + length;
        if (end <= contiguousEnd) return true;
        return tailDone && plan != null && position >= plan.tailStart;
    }

    /**
     * Waits until {@link #isReady()}. Gives up only when the download stalls: a file that must
     * arrive whole first (a multi-stream Ogg) may take longer than any fixed limit.
     *
     * @param stallTimeoutMs longest time without any data arriving
     * @param abort polled while waiting; returning true gives up (e.g. another song was chosen)
     * @return whether the stream became ready
     */
    boolean awaitReady(long stallTimeoutMs, @NonNull BooleanSupplier abort,
                       @Nullable Consumer<OneDrive.BufferingProgress> onProgress) throws InterruptedException {
        long seen = -1;
        long deadline = 0;
        long lastReport = 0;
        while (true) {
            final OneDrive.BufferingProgress progress;
            synchronized (lock) {
                if (isReadyLocked()) return true;
                if (failed || destroyed) return false;
                final long now = SystemClock.elapsedRealtime();
                if (bytesReceived != seen) {
                    seen = bytesReceived;
                    deadline = now + stallTimeoutMs;
                }
                if (now >= deadline) return false;
                lock.wait(Math.min(deadline - now, 250));
                progress = onProgress != null ? progressLocked() : null;
            }
            if (abort.getAsBoolean()) return false;
            // Reported outside the lock, so showing it never holds up the download
            final long now = SystemClock.elapsedRealtime();
            if (progress != null && now - lastReport >= 500) {
                lastReport = now;
                onProgress.accept(progress);
            }
        }
    }

    /** How far the download is from {@link #isReady()}. */
    @NonNull
    private OneDrive.BufferingProgress progressLocked() {
        if (plan == null || !plan.complete) {
            return new OneDrive.BufferingProgress(bytesReceived, -1, size, false); // not planned yet
        }
        final long needed = plan.headBytes + (plan.needsTail(size) ? size - plan.tailStart : 0);
        return new OneDrive.BufferingProgress(Math.min(bytesReceived, needed), needed, size, plan.multiStream);
    }

    /** Runs {@code listener} on the callback thread once the stream is ready (never if it fails). */
    void whenReady(@NonNull Runnable listener) {
        synchronized (lock) {
            if (!readyFired) {
                readyListeners.add(listener);
                return;
            }
        }
        callbacks.execute(listener);
    }

    private void fireReadyIfNeededLocked() {
        if (readyFired || !isReadyLocked()) return;
        readyFired = true;
        final List<Runnable> listeners = new ArrayList<>(readyListeners);
        readyListeners.clear();
        for (final Runnable listener : listeners) callbacks.execute(listener);
    }

    // --- reading (decoder threads) ---------------------------------------------------------

    /**
     * Reads exactly {@code length} bytes at {@code position} (fewer only at the end of the file),
     * waiting for them to be downloaded. Decoders treat short reads as errors, so this never
     * returns a partial range early.
     */
    int read(long position, @NonNull byte[] buffer, int offset, int length) throws IOException {
        if (position >= size) return -1;
        final int wanted = (int) Math.min(length, size - position);
        if (wanted <= 0) return 0;
        final FileChannel ch;
        synchronized (lock) {
            while (!isAvailableLocked(position, wanted)) {
                if (failed) throw new IOException("OneDrive download failed: " + failure);
                if (destroyed) throw new IOException("OneDrive stream closed");
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while buffering", e);
                }
            }
            if (destroyed || channel == null) throw new IOException("OneDrive stream closed");
            ch = channel;
        }
        final ByteBuffer target = ByteBuffer.wrap(buffer, offset, wanted);
        int total = 0;
        while (target.hasRemaining()) {
            final int n = ch.read(target, position + total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    // --- lifecycle -------------------------------------------------------------------------

    /** Stops the download if it is still running and deletes the cache file. Idempotent. */
    void destroy() {
        final boolean cleanNow;
        synchronized (lock) {
            if (destroyed) return;
            destroyed = true;
            cleanNow = workerDone;
            lock.notifyAll();
        }
        final HttpURLConnection conn = activeConnection;
        if (conn != null) conn.disconnect(); // unblocks the worker's read
        if (cleanNow) cleanup();
    }

    private void cleanup() {
        synchronized (lock) {
            if (cleaned) return;
            cleaned = true;
            try {
                if (channel != null) channel.close();
                if (raf != null) raf.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            channel = null;
            raf = null;
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // --- downloading (worker thread) -------------------------------------------------------

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        boolean cleanNow = false;
        try {
            if (size <= 0) throw OneDriveException.permanent("Empty file");
            final File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            synchronized (lock) {
                if (destroyed) return;
                raf = new RandomAccessFile(file, "rw");
                raf.setLength(size); // sparse: the tail may be written before the middle
                channel = raf.getChannel();
            }
            download();
            synchronized (lock) {
                if (destroyed) return;
                complete = true;
                lock.notifyAll();
                fireReadyIfNeededLocked();
            }
            saveOffline();
        } catch (OneDriveException | IOException e) {
            synchronized (lock) {
                if (!destroyed) {
                    failed = true;
                    failure = e.getMessage();
                    Log.w(TAG, "Streaming " + fileName + " failed", e);
                }
                lock.notifyAll();
            }
        } finally {
            synchronized (lock) {
                workerDone = true;
                cleanNow = destroyed;
            }
            if (cleanNow) cleanup();
        }
    }

    /** Fetches ranges until the whole file is on disk; retries with backoff and fresh URLs. */
    private void download() throws OneDriveException, IOException {
        String url = client.downloadUrl(itemId);
        int attempts = 0;
        while (true) {
            final long fetchStart;
            final long fetchEnd; // inclusive, or -1 for "until told to stop"
            final boolean isTail;
            synchronized (lock) {
                if (destroyed) return;
                if (plan != null && plan.needsTail(size) && !tailDone && contiguousEnd >= plan.tailStart) {
                    tailDone = true; // the sequential part already covered it
                }
                if (isCompleteLocked()) return;
                if (plan != null && plan.complete && plan.needsTail(size) && !tailDone
                        && contiguousEnd >= plan.headBytes) {
                    fetchStart = plan.tailStart;
                    fetchEnd = size - 1;
                    isTail = true;
                } else {
                    fetchStart = contiguousEnd;
                    fetchEnd = (plan != null && plan.needsTail(size) && tailDone) ? plan.tailStart - 1 : -1;
                    isTail = false;
                }
            }

            try {
                final boolean progressed = fetch(url, fetchStart, fetchEnd, isTail);
                if (progressed) {
                    attempts = 0;
                } else {
                    // The server answered but sent nothing: do not spin on it
                    if (++attempts >= MAX_ATTEMPTS) throw new IOException("OneDrive sent no data");
                    backoff(attempts);
                }
            } catch (UrlExpiredException e) {
                if (++attempts >= MAX_ATTEMPTS) throw new IOException("Download link keeps expiring", e);
                url = client.downloadUrl(itemId);
            } catch (IOException e) {
                synchronized (lock) {
                    if (destroyed) return;
                }
                if (++attempts >= MAX_ATTEMPTS) throw e;
                backoff(attempts);
            }
        }
    }

    private static final class UrlExpiredException extends IOException {
        UrlExpiredException(String message) {
            super(message);
        }
    }

    /**
     * One ranged request. Sequential fetches stop by themselves once the head is in and a tail
     * has to come next.
     *
     * @return whether any bytes arrived
     */
    private boolean fetch(String url, long start, long endInclusive, boolean isTail) throws IOException {
        final HttpURLConnection conn = Http.openRange(url, start, endInclusive);
        activeConnection = conn;
        try {
            synchronized (lock) {
                if (destroyed) return false;
            }
            final int code = conn.getResponseCode();
            if (code == 401 || code == 403 || code == 404 || code == 410) {
                throw new UrlExpiredException("Download link rejected (HTTP " + code + ")");
            }
            if (code == 416) throw new IOException("The file changed on OneDrive; refresh the folder");
            if (code != 206 && code != 200) throw new IOException("Download failed (HTTP " + code + ")");
            checkTotalSize(conn);

            long position = start;
            try (InputStream in = conn.getInputStream()) {
                if (code == 200 && start > 0) skipFully(in, start); // server ignored the range
                final byte[] buffer = new byte[BUFFER_BYTES];
                boolean progressed = false;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    final long remainingInRange = (isTail ? size : (endInclusive >= 0 ? endInclusive + 1 : size)) - position;
                    if (remainingInRange <= 0) break;
                    final int count = (int) Math.min(n, remainingInRange);
                    writeFully(buffer, count, position);
                    position += count;
                    progressed = true;
                    if (!isTail && onSequentialProgress(position)) break;
                }
                if (isTail) {
                    if (position < size) throw new IOException("Tail download ended early");
                    synchronized (lock) {
                        tailDone = true;
                        lock.notifyAll();
                        fireReadyIfNeededLocked();
                    }
                }
                return progressed;
            }
        } finally {
            activeConnection = null;
            conn.disconnect();
        }
    }

    /**
     * Publishes sequential progress and decides whether to stop this request.
     *
     * @return true when the request should end (the tail must be fetched next, or the stream is gone)
     */
    private boolean onSequentialProgress(long position) throws IOException {
        boolean sniff = false;
        synchronized (lock) {
            if (destroyed) return true;
            contiguousEnd = position;
            lock.notifyAll();
            if (plan == null) {
                sniff = position >= Math.min(size, FormatSniffer.INITIAL_BYTES);
            } else if (!plan.complete) {
                sniff = position >= plan.headBytes;
            }
        }
        if (sniff) sniff(position);
        synchronized (lock) {
            fireReadyIfNeededLocked();
            if (plan != null && plan.needsTail(size) && !tailDone) {
                if (position >= plan.tailStart) {
                    tailDone = true;
                    lock.notifyAll();
                    fireReadyIfNeededLocked();
                    return false;
                }
                // The head is in: fetch the tail before the rest, so the decoder can start
                return plan.complete && position >= plan.headBytes;
            }
            return false;
        }
    }

    /** Looks at the downloaded head to plan what the decoder needs. */
    private void sniff(long available) throws IOException {
        final int length = (int) Math.min(available, FormatSniffer.MAX_SNIFF_BYTES);
        final byte[] head = new byte[length];
        final FileChannel ch;
        synchronized (lock) {
            ch = channel;
        }
        if (ch == null) return;
        final ByteBuffer target = ByteBuffer.wrap(head);
        int read = 0;
        while (target.hasRemaining()) {
            final int n = ch.read(target, read);
            if (n < 0) break;
            read += n;
        }
        final FormatSniffer.Plan next = FormatSniffer.plan(head, read, size, extension);
        synchronized (lock) {
            plan = next;
            lock.notifyAll();
        }
    }

    private void writeFully(byte[] buffer, int count, long position) throws IOException {
        final FileChannel ch;
        synchronized (lock) {
            if (destroyed || channel == null) throw new IOException("OneDrive stream closed");
            ch = channel;
        }
        final ByteBuffer source = ByteBuffer.wrap(buffer, 0, count);
        long at = position;
        while (source.hasRemaining()) {
            at += ch.write(source, at);
        }
        synchronized (lock) {
            bytesReceived += count;
            lock.notifyAll();
        }
    }

    /** The file must still be the one that was listed, or the bytes would not fit together. */
    private void checkTotalSize(HttpURLConnection conn) throws IOException {
        final String range = conn.getHeaderField("Content-Range"); // "bytes 0-99/1234"
        if (range == null) return;
        final int slash = range.lastIndexOf('/');
        if (slash < 0 || slash == range.length() - 1 || range.endsWith("*")) return;
        try {
            final long total = Long.parseLong(range.substring(slash + 1).trim());
            if (total != size) throw new IOException("The file changed on OneDrive; refresh the folder");
        } catch (NumberFormatException ignored) {
            // unparsable header: trust the listing
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        final byte[] scratch = new byte[BUFFER_BYTES];
        while (left > 0) {
            final int n = in.read(scratch, 0, (int) Math.min(scratch.length, left));
            if (n < 0) throw new IOException("Download ended early");
            left -= n;
        }
    }

    private void backoff(int attempt) throws IOException {
        final long delay = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(attempt - 1, 4));
        final long deadline = SystemClock.elapsedRealtime() + delay;
        synchronized (lock) {
            long remaining;
            while (!destroyed && (remaining = deadline - SystemClock.elapsedRealtime()) > 0) {
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            }
        }
    }

    private void saveOffline() {
        final OfflineSaver.Saved saved = OfflineSaver.save(context, fileName, folderPath, file);
        if (saved != null) {
            library.setOffline(songId, saved.path, saved.uri);
        }
    }
}
