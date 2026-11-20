package com.poupa.vinylmusicplayer.onedrive;

import android.content.Context;
import android.media.MediaDataSource;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.poupa.vinylmusicplayer.App;
import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.discog.Discography;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.util.OopsHandler;
import com.poupa.vinylmusicplayer.util.SafeToast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Entry point of the OneDrive feature for the rest of the app: library scan, playback hooks for
 * the music service and player, and guards for the menus.
 *
 * Once signed in, the music of the whole drive is listed in the library. OneDrive songs stream
 * while they play and are then saved to Music/OneDrive, where MediaStore picks them up like any
 * local song; that copy replaces the OneDrive entry, and later plays are fully offline.
 */
public final class OneDrive {

    /** Player uri of a song that still has to come from OneDrive. */
    public static final String STREAM_SCHEME = "vinyl-onedrive://stream/";

    /** While buffering, longest time without any data arriving before giving up. */
    private static final long BUFFER_TIMEOUT_MS = 45_000;

    /** Automatic rescans (after the local library is synced) happen at most this often. */
    private static final long AUTO_SCAN_INTERVAL_MS = 15 * 60 * 1000L;

    /**
     * Rescans when the app comes back to the foreground happen at most this often. A delta scan
     * with nothing new is a single small request.
     */
    private static final long RESUME_SCAN_INTERVAL_MS = 60 * 1000L;

    /** Library scans and library updates run one at a time, in order, in the background. */
    private static final ExecutorService LIBRARY_SYNC = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "OneDriveLibrarySync");
        thread.setDaemon(true);
        return thread;
    });

    /** Scans queued or running; automatic scans are skipped while one is on its way. */
    private static final AtomicInteger PENDING_SCANS = new AtomicInteger();

    /** Library updates (cheap, in memory) have their own thread, never queued behind a scan. */
    private static final ExecutorService PUBLISH = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "OneDriveLibraryPublish");
        thread.setDaemon(true);
        return thread;
    });
    private static final java.util.concurrent.atomic.AtomicBoolean PUBLISH_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Nullable
    private static OneDriveLibrary library;
    @Nullable
    private static Object streams; // OneDriveStreams, typed loosely so this class loads on API < 23
    @Nullable
    private static Object scanner; // OneDriveScanner, same reason

    private OneDrive() {}

    @RequiresApi(23)
    @NonNull
    private static synchronized OneDriveScanner scanner() {
        if (scanner == null) scanner = new OneDriveScanner(App.getStaticContext(), library());
        return (OneDriveScanner) scanner;
    }

    // --- library scan ----------------------------------------------------------------------

    /**
     * Lists the music of the whole drive into the library (Songs, Albums, Artists...).
     *
     * @param full     list everything again, instead of only what changed since the last scan
     * @param announce tell the user when it starts and how it went
     */
    public static void scanLibrary(@NonNull Context context, boolean full, boolean announce) {
        if (!isAvailable()) return;
        final Context app = context.getApplicationContext();
        if (!OneDriveAuth.isSignedIn(app)) return;
        if (!full && !announce && PENDING_SCANS.get() > 0) return; // one is already on its way
        PENDING_SCANS.incrementAndGet();
        libraryTask(() -> {
            try {
                if (announce) SafeToast.show(app, R.string.onedrive_scanning);
                final OneDriveLibrary.ScanResult result = scanner().scan(full);
                library().trashCopiesOfRemovedFiles();
                publishNow();
                if (announce || result.added > 0 || result.removed > 0) {
                    SafeToast.show(app, app.getString(R.string.onedrive_scan_done, result.total, result.added, result.removed));
                }
                probeDurations();
            } catch (OneDriveException e) {
                if (announce) SafeToast.show(app, app.getString(R.string.onedrive_scan_failed, e.getMessage()));
            } catch (RuntimeException e) {
                OopsHandler.collectStackTrace(e);
            } finally {
                PENDING_SCANS.decrementAndGet();
            }
        });
    }

    /**
     * Puts the OneDrive songs of the last scan into the library, in the background. Runs on its
     * own thread, not behind scans and duration reads, so a song saved offline is swapped for its
     * local copy right after MediaStore indexed it (no duplicate in between). Bursts coalesce.
     */
    public static void publishToLibrary() {
        if (!isAvailable()) return;
        if (PUBLISH_PENDING.compareAndSet(false, true)) {
            PUBLISH.execute(() -> {
                PUBLISH_PENDING.set(false);
                publishNow();
            });
        }
    }

    private static final Object PUBLISH_LOCK = new Object();

    private static void publishNow() {
        // One at a time: an older snapshot must never overwrite a newer one
        synchronized (PUBLISH_LOCK) {
            try {
                final OneDriveLibrary lib = library();
                final List<Song> songs = lib.librarySongs();
                Discography.getInstance().replaceOneDriveSongs(songs, lib.shadowedPaths());
            } catch (RuntimeException e) {
                OopsHandler.collectStackTrace(e);
            }
        }
    }

    /** Runs a library task; an unexpected error is recorded, never allowed to crash the app. */
    private static void libraryTask(@NonNull Runnable task) {
        LIBRARY_SYNC.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                OopsHandler.collectStackTrace(e);
            }
        });
    }

    /**
     * Called after each sync of the local library: OneDrive songs are put back (a reset clears
     * them), songs saved offline meanwhile are swapped for their local copy, and the drive is
     * rescanned when that is due (always after a reset).
     */
    public static void onLibrarySynced(@NonNull Context context, boolean reset) {
        if (!isAvailable()) return;
        final Context app = context.getApplicationContext();
        publishToLibrary();
        if (reset) {
            scanLibrary(app, true, true);
        } else if (OneDriveAuth.isSignedIn(app) && PENDING_SCANS.get() == 0
                && PROBE_QUEUED.compareAndSet(false, true)) {
            libraryTask(() -> {
                PROBE_QUEUED.set(false);
                if (scanner().isStale(AUTO_SCAN_INTERVAL_MS)) {
                    scanLibrary(app, false, false);
                } else {
                    // The last scan still holds: finish what it left (an interrupted pass, or
                    // leftovers marked before this version)
                    if (scanner().hasScanned() && library().trashCopiesOfRemovedFiles() > 0) publishNow();
                    probeDurations();
                }
            });
        }
    }

    /**
     * The app came to the foreground: picks up files added to the drive meanwhile. Without this,
     * a new file only reached the library after an app restart or a change in the local library
     * (and then only once the last scan was 15 minutes old), or from Refresh in the OneDrive tab.
     */
    public static void onAppResumed(@NonNull Context context) {
        if (!isAvailable()) return;
        final Context app = context.getApplicationContext();
        if (!OneDriveAuth.isSignedIn(app) || PENDING_SCANS.get() > 0) return;
        libraryTask(() -> {
            // Before the first scan completes, onSignedIn / the library sync start a full one
            if (scanner().hasScanned() && scanner().isStale(RESUME_SCAN_INTERVAL_MS)) {
                scanLibrary(app, false, false);
            }
        });
    }

    /** Files read at once while looking for durations. */
    private static final int PROBE_THREADS = 4;

    /**
     * A duration pass is waiting on the library thread. Every change of the local library asks
     * for one; without coalescing they piled up on that single thread, and a scan (including
     * Refresh) had to wait behind all of them before new drive files could show up.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean PROBE_QUEUED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Reads the duration of library songs that Graph gave none (common for recordings, and for
     * work accounts) from a few bytes of each file: about 64 to 200 KB per song instead of the
     * whole file. Runs on the library sync thread; updates the library as it goes.
     */
    @RequiresApi(23)
    private static void probeDurations() {
        final List<OneDriveLibrary.ProbeTarget> targets = library().durationProbeTargets();
        if (targets.isEmpty()) return;
        final OneDriveClient client = new OneDriveClient(App.getStaticContext());
        final ExecutorService pool = Executors.newFixedThreadPool(PROBE_THREADS, runnable -> {
            final Thread thread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, "OneDriveDurationProbe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            final List<java.util.concurrent.Future<?>> pending = new ArrayList<>(targets.size());
            for (final OneDriveLibrary.ProbeTarget target : targets) {
                pending.add(pool.submit(() -> {
                    final long durationMs;
                    try {
                        final String url = client.downloadUrl(target.itemId);
                        durationMs = DurationProbe.durationMs(
                                (offset, length) -> OneDriveClient.readRange(url, offset, length),
                                target.size, target.extension);
                    } catch (OneDriveException e) {
                        if (e.kind != OneDriveException.Kind.PERMANENT) return; // try again next time
                        library().setProbedDuration(target, 0);
                        return;
                    } catch (IOException | RuntimeException e) {
                        return; // network trouble: try again next time
                    }
                    library().setProbedDuration(target, durationMs);
                }));
            }
            int done = 0;
            for (final java.util.concurrent.Future<?> future : pending) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // handled in the task
                }
                if (++done % 50 == 0) publishNow(); // big libraries: show progress
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        publishNow();
    }

    /** First scan right after signing in. */
    public static void onSignedIn(@NonNull Context context) {
        scanLibrary(context, true, true);
    }

    /** Forgets the account: its songs leave the library; songs saved offline stay. */
    public static void onSignedOut(@NonNull Context context) {
        if (!isAvailable()) return;
        final Context app = context.getApplicationContext();
        libraryTask(() -> {
            OneDriveAuth.signOut(app);
            scanner().forget();
            library().clearLibrary();
            publishNow();
        });
    }

    /** Whether this build and this device support OneDrive (client id configured, Android 6+). */
    public static boolean isAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && OneDriveAuth.isConfigured();
    }

    @NonNull
    public static synchronized OneDriveLibrary library() {
        if (library == null) library = new OneDriveLibrary(App.getStaticContext());
        return library;
    }

    @RequiresApi(23)
    @NonNull
    private static synchronized OneDriveStreams streams() {
        if (streams == null) streams = new OneDriveStreams(App.getStaticContext(), library());
        return (OneDriveStreams) streams;
    }

    public static boolean isOneDriveSong(@NonNull Song song) {
        return OneDriveLibrary.isOneDriveId(song.id);
    }

    /**
     * Whether the song's file may be read (tags, cover, lyrics): always for local songs and
     * offline copies; for a OneDrive song still downloading, only once its download is complete.
     * Cheap: no file system or network access.
     */
    public static boolean isFileComplete(@NonNull Song song) {
        if (!isOneDriveSong(song)) return true;
        final String cacheDir = library().cacheDir().getPath() + java.io.File.separator;
        if (!song.data.startsWith(cacheDir)) return true; // its offline copy
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && streams().isComplete(song.id);
    }

    // --- discography -----------------------------------------------------------------------

    /** The song of a OneDrive id kept in a queue, history or play count, or null when unknown. */
    @Nullable
    public static Song getSong(long songId) {
        if (!OneDriveLibrary.isOneDriveId(songId)) return null;
        return library().getSong(songId);
    }

    // --- playback (music service, player) --------------------------------------------------

    /**
     * The uri the player opens for a OneDrive song: its offline copy when there is one,
     * otherwise its stream. May touch the storage: not on the main thread.
     */
    @NonNull
    public static String playbackUri(@NonNull Song song) {
        final String uri = library().playbackUri(song);
        if (isStreamUri(uri)) return uri;
        // A local file: an identical original found on the device may be an Ogg recording
        // with video in it, which plays through its audio stream
        final String path = library().offlinePath(song.id);
        return path != null ? MultiStreamOgg.trackUri(path, uri) : uri;
    }

    public static boolean isStreamUri(@Nullable String uri) {
        return uri != null && uri.startsWith(STREAM_SCHEME);
    }

    /**
     * Whether the song still needs network data before the player may prepare it. Preparing
     * blocks on reads, and the music service prepares while holding its lock, so such a song
     * must first go through {@link #awaitReady}.
     */
    public static boolean needsBuffering(@NonNull Song song) {
        if (!isOneDriveSong(song) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        if (!isStreamUri(playbackUri(song))) return false; // offline copy
        return !streams().isReady(song.id);
    }

    /** Whether the player can open the song right now without waiting. Never starts a download. */
    public static boolean canOpenNow(@NonNull Song song) {
        return !needsBuffering(song);
    }

    /**
     * Downloads the start of a song and waits until it can be prepared. Call from the playback
     * thread, not while holding the music service lock.
     *
     * @param abort polled while waiting; returning true gives up (another song was chosen)
     */
    public static boolean awaitReady(@NonNull Song song, @NonNull BooleanSupplier abort,
                                     @Nullable java.util.function.Consumer<BufferingProgress> onProgress) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        return streams().awaitReady(song.id, BUFFER_TIMEOUT_MS, abort, onProgress);
    }

    /** How far a song is from playing while it loads. */
    public static final class BufferingProgress {
        /** Bytes received so far (of {@link #needed}). */
        public final long received;
        /** Bytes needed before it can play, or -1 while that is not known yet. */
        public final long needed;
        /** Size of the whole file. */
        public final long size;
        /** An Ogg recording with video in it: the whole file must arrive before its audio plays. */
        public final boolean wholeFile;

        BufferingProgress(long received, long needed, long size, boolean wholeFile) {
            this.received = received;
            this.needed = needed;
            this.size = size;
            this.wholeFile = wholeFile;
        }
    }

    /** Downloads the next song ahead of time; {@code onReady} runs once it can be prepared. */
    public static void prefetch(@NonNull Song song, @NonNull Runnable onReady) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        streams().prefetch(song.id, onReady);
    }

    /** A message for a song that could not be streamed. */
    @NonNull
    public static String failureMessage(@NonNull Context context, @NonNull Song song) {
        String reason = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) reason = streams().failure(song.id);
        return reason == null
                ? context.getString(R.string.onedrive_stream_failed_no_reason)
                : context.getString(R.string.onedrive_stream_failed, reason);
    }

    /** The data source the player reads a streamed song from. */
    @RequiresApi(23)
    @NonNull
    public static MediaDataSource openDataSource(@NonNull String streamUri) throws IOException {
        final long songId;
        try {
            songId = Long.parseLong(streamUri.substring(STREAM_SCHEME.length()));
        } catch (NumberFormatException e) {
            throw new IOException("Bad OneDrive stream uri " + streamUri, e);
        }
        final MediaDataSource raw = streams().openDataSource(songId);
        final OneDriveLibrary.Entry entry = library().entry(songId);
        if (entry == null) return raw;
        try {
            // An Ogg recording with video in it: give the player its audio stream only
            return MultiStreamOgg.wrapIfMultiplexed(raw, entry.name);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    // --- menus -----------------------------------------------------------------------------

    /**
     * The local library song to use for actions that need a file in MediaStore (playlists,
     * favorites, tag editor, sharing, ringtone, delete, go to album/artist).
     *
     * @return {@code song} itself for local songs; for OneDrive songs, the library copy once it
     * has been saved and indexed, otherwise {@code null}
     */
    @Nullable
    public static Song localSong(@NonNull Song song) {
        if (!isOneDriveSong(song)) return song;
        return library().librarySong(song.id);
    }

    /** {@link #localSong}, telling the user why when the song is not available locally yet. */
    @Nullable
    public static Song requireLocalSong(@NonNull Context context, @NonNull Song song) {
        final Song local = localSong(song);
        if (local == null) SafeToast.show(context, R.string.onedrive_not_offline_yet);
        return local;
    }

    /** {@link #localSong} for each song; tells the user when some had to be left out. */
    @NonNull
    public static List<Song> requireLocalSongs(@NonNull Context context, @NonNull Collection<? extends Song> songs) {
        final List<Song> result = new ArrayList<>(songs.size());
        boolean skipped = false;
        for (final Song song : songs) {
            final Song local = localSong(song);
            if (local != null) result.add(local);
            else skipped = true;
        }
        if (skipped) SafeToast.show(context, R.string.onedrive_not_offline_yet);
        return result;
    }
}
