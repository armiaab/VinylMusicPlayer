package com.poupa.vinylmusicplayer.discog;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.poupa.vinylmusicplayer.App;
import com.poupa.vinylmusicplayer.discog.tagging.TagExtractor;
import com.poupa.vinylmusicplayer.interfaces.MusicServiceEventListener;
import com.poupa.vinylmusicplayer.model.Album;
import com.poupa.vinylmusicplayer.model.Artist;
import com.poupa.vinylmusicplayer.model.Genre;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.onedrive.OneDrive;
import com.poupa.vinylmusicplayer.onedrive.OneDriveLibrary;
import com.poupa.vinylmusicplayer.provider.BlacklistStore;
import com.poupa.vinylmusicplayer.sort.SongSortOrder;
import com.poupa.vinylmusicplayer.ui.activities.base.AbsMusicServiceActivity;
import com.poupa.vinylmusicplayer.util.FileUtil;
import com.poupa.vinylmusicplayer.util.OopsHandler;
import com.poupa.vinylmusicplayer.util.PreferenceUtil;
import com.poupa.vinylmusicplayer.util.StringUtil;

import org.jaudiotagger.tag.reference.GenreTypes;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * @author SC (soncaokim)
 */

public class Discography implements MusicServiceEventListener {
    /** Tag reading is I/O bound: a few threads overlap the waits without crowding the device. */
    private static final int TAG_READ_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));

    private static final ThreadFactory TAG_READER_THREAD_FACTORY = runnable -> {
        final Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "DiscographyTagReader");
        thread.setDaemon(true);
        return thread;
    };

    /** A MediaStore song checked off the cache lock; {@code tagsRead} when its tags were extracted. */
    private static final class PreparedSong {
        @NonNull final Song song;
        final boolean tagsRead;

        PreparedSong(@NonNull Song song, boolean tagsRead) {
            this.song = song;
            this.tagsRead = tagsRead;
        }
    }

    // TODO wrap this inside the MemCache class
    private final DB database = new DB();
    private final MemCache cache = new MemCache();
    /** What each OneDrive song in the cache was added with, to skip unchanged ones (guarded by cache). */
    private final Map<Long, String> oneDriveSignatures = new HashMap<>();

    private final Map<AbsMusicServiceActivity, Handler> attachedActivitiesAndQueue = new HashMap<>();
    private static final String TASK_QUEUE_COALESCENCE_TOKEN = "Discography.triggerSyncWithMediaStore";
    private final Collection<Runnable> changedListeners = new LinkedList<>();

    // TODO This is not a singleton and should not be declared as such
    @NonNull
    public static Discography getInstance() {
        return App.getDiscography();
    }

    public void addActivity(@NonNull final AbsMusicServiceActivity activity) {
        if (!attachedActivitiesAndQueue.containsKey(activity)) {
            attachedActivitiesAndQueue.put(activity, new Handler(activity.getMainLooper()));

            activity.addMusicServiceEventListener(this);

            if (cache.consistencyState == MemCache.ConsistencyState.UNINITIALIZED) {
                // These operations are IO intensive and may take time, depending on the library size
                // delay it till UI is availble
                fetchAllSongs();
                // OneDrive songs are not stored with the others: show them right away too
                OneDrive.publishToLibrary();
                triggerSyncWithMediaStore(false);
            }
        }
    }

    public void removeActivity(@NonNull final AbsMusicServiceActivity activity) {
        if (attachedActivitiesAndQueue.containsKey(activity)) {
            activity.removeMusicServiceEventListener(this);

            // Flush the delayed task queue - dont do anything is we are stopping
            final Handler handler = attachedActivitiesAndQueue.get(activity);
            Objects.requireNonNull(handler);
            handler.removeCallbacksAndMessages(TASK_QUEUE_COALESCENCE_TOKEN);

            attachedActivitiesAndQueue.remove(activity);
        }
    }

    void setCacheState(final MemCache.ConsistencyState value) {
        synchronized (cache) {
            cache.consistencyState = value;
        }
    }

    private MemCache.ConsistencyState getCacheState() {
        synchronized (cache) {
            return cache.consistencyState;
        }
    }

    private static final BiPredicate<Song, Song> IS_METADATA_OBSOLETE = (final @NonNull Song incomingSong, final @NonNull Song cachedSong) -> {
        if (incomingSong.dateAdded != cachedSong.dateAdded) return true;
        if (incomingSong.dateModified != cachedSong.dateModified) return true;
        return (!incomingSong.data.equals(cachedSong.data));
    };

    /**
     * @param tagsAlreadyRead the tags of {@code song} were extracted beforehand (off the cache lock)
     */
    @NonNull
    private Song getOrAddSong(@NonNull final Song song, @NonNull final SyncWithMediaStoreAsyncTask.Progress progress, final boolean tagsAlreadyRead) {
        synchronized (cache) {
            final Song discogSong = getSong(song.id);
            boolean existsAndObsolete = false;
            if (!discogSong.equals(Song.EMPTY_SONG)) {
                existsAndObsolete = IS_METADATA_OBSOLETE.test(song, discogSong);
                if (existsAndObsolete) {
                    removeSongById(song.id);
                } else {
                    return discogSong;
                }
            }

            addSong(song, false, !tagsAlreadyRead);
            if (existsAndObsolete) {++ progress.updated;}
            else {++ progress.added;}

            return song;
        }
    }

    /** Whether the song is new or changed since it was added, so its tags have to be read. */
    private boolean needsTags(@NonNull final Song song) {
        synchronized (cache) {
            final Song cached = cache.songsById.get(song.id);
            return cached == null || IS_METADATA_OBSOLETE.test(song, cached);
        }
    }

    @NonNull
    public Song getSong(long songId) {
        Song song;
        synchronized (cache) {
            song = cache.songsById.get(songId);
        }
        if (song == null && OneDriveLibrary.isOneDriveId(songId)) {
            // A OneDrive song kept in a queue, history or play count (looked up outside the
            // cache lock: the OneDrive library calls back into Discography)
            song = OneDrive.getSong(songId);
        }
        return song == null ? Song.EMPTY_SONG : song;
    }

    @NonNull
    public ArrayList<Song> getSongsFromIdsAndCleanupOrphans(@NonNull ArrayList<Long> songIds, @Nullable Consumer<ArrayList<Long>> orphanIdsCleaner) {
        ArrayList<Long> orphanSongIds = new ArrayList<>();
        ArrayList<Song> songs = new ArrayList<>();

        // OneDrive ids are resolved after the cache lock is released, keeping their place
        final ArrayList<Song> lookups = new ArrayList<>(songIds.size());
        synchronized (cache) {
            for (Long id : songIds) {
                lookups.add(cache.songsById.get(id));
            }
        }
        for (int i = 0; i < songIds.size(); i++) {
            final long id = songIds.get(i);
            Song song = lookups.get(i);
            if (song == null && OneDriveLibrary.isOneDriveId(id)) {
                song = OneDrive.getSong(id);
            }
            if (song != null) {
                songs.add(song);
            } else if (orphanIdsCleaner != null) {
                orphanSongIds.add(id);
            }
        }

        MemCache.ConsistencyState cacheState = getCacheState();
        // In the case where the Discog is being reset situation, the operation takes time,
        // and while the cache is being filled up,
        // correct and existing songs may be considered as orphan
        // --> incorrectly cleaned from the auxiliary DBs (history, queue, etc)
        if ((orphanIdsCleaner != null) && (cacheState != MemCache.ConsistencyState.RESETTING)) {
            orphanIdsCleaner.accept(orphanSongIds);
        }
        return songs;
    }

    @NonNull
    public Song getSongByPath(@NonNull final String path) {
        synchronized (cache) {
            final Song song = cache.songsByPath.get(path);
            return song == null ? Song.EMPTY_SONG : song;
        }
    }

    @NonNull
    public ArrayList<Song> getAllSongs(@Nullable Comparator<Song> sortOrder) {
        synchronized (cache) {
            // Make a copy here, to avoid error while the caller is iterating on the result
            ArrayList<Song> copy = new ArrayList<>(cache.songsById.values());

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            if (sortOrder != null) {Collections.sort(copy, sortOrder);}

            return copy;
        }
    }

    @NonNull
    public Map<Long, Song> getAllSongsById() {
        synchronized (cache) {
            // Make a copy here, to avoid error while the caller is iterating on the result
            return new HashMap<>(cache.songsById);
        }
    }

    @Nullable
    public Artist getArtist(long artistId) {
        synchronized (cache) {
            return cache.artistsById.get(artistId);
        }
    }

    @Nullable
    public Artist getArtistByName(String artistName) {
        synchronized (cache) {
            return cache.artistsByName.get(artistName);
        }
    }

    @NonNull
    public ArrayList<Artist> getAllArtists(@NonNull Comparator<Artist> sortOrder) {
        synchronized (cache) {
            // Make a copy here, to avoid error while the caller is iterating on the result
            ArrayList<Artist> copy = new ArrayList<>(cache.artistsById.values());

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            Collections.sort(copy, sortOrder);
            return copy;
        }
    }

    @Nullable
    public Album getAlbum(long albumId) {
        synchronized (cache) {
            Map<Long, MemCache.AlbumSlice> albumsByArtist = cache.albumsByAlbumIdAndArtistId.get(albumId);
            if (albumsByArtist == null) return null;
            return mergeFullAlbum(albumsByArtist.values());
        }
    }

    @NonNull
    public ArrayList<Album> getAllAlbums(@NonNull Comparator<? super Album> sortOrder) {
        synchronized (cache) {
            ArrayList<Album> fullAlbums = new ArrayList<>();
            for (Map<Long, MemCache.AlbumSlice> albumsByArtist : cache.albumsByAlbumIdAndArtistId.values()) {
                if (albumsByArtist.isEmpty()) continue; // no songs: not an album to show
                fullAlbums.add(mergeFullAlbum(albumsByArtist.values()));
            }

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            Collections.sort(fullAlbums, sortOrder);

            return fullAlbums;
        }
    }

    @NonNull
    private static Album mergeFullAlbum(@NonNull Iterable<? extends MemCache.AlbumSlice> albumParts) {
        Album fullAlbum = new Album();
        for (Album fragment : albumParts) {
            for (Song song : fragment.songs) {
                if (fullAlbum.songs.contains(song)) continue;
                fullAlbum.songs.add(song);
            }
        }
        // Maintain sorted album after merge
        Collections.sort(fullAlbum.songs, SongSortOrder.BY_DISC_TRACK);
        return fullAlbum;
    }

    @NonNull
    public ArrayList<Genre> getAllGenres(@NonNull Comparator<? super Genre> sortOrder) {
        synchronized (cache) {
            // Make a copy here, to avoid error while the caller is iterating on the result
            ArrayList<Genre> copy = new ArrayList<>(cache.genresByName.values());

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            Collections.sort(copy, sortOrder);

            return copy;
        }
    }

    @Nullable
    public ArrayList<Song> getSongsForGenre(long genreId, @NonNull Comparator<? super Song> sortOrder) {
        synchronized (cache) {
            ArrayList<Song> songs = cache.songsByGenreId.get(genreId);
            if (songs == null) {return null;}

            ArrayList<Song> copy = new ArrayList<>(songs);

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            Collections.sort(copy, sortOrder);

            return copy;
        }
    }

    public float getMaxReplayGain() {
        return cache.getMaxReplayGain();
    }

    private void addSong(@NonNull Song song, boolean cacheOnly) {
        addSong(song, cacheOnly, !cacheOnly);
    }

    private void addSong(@NonNull Song song, boolean cacheOnly, boolean extractTags) {
        synchronized (cache) {
            // Race condition check: If the song has been added -> skip
            if (cache.songsById.containsKey(song.id)) {
                return;
            }

            if (extractTags) {
                TagExtractor.extractTags(song);
            }

            Consumer<List<String>> normNames = (@NonNull List<String> names) -> {
                List<String> normalized = new ArrayList<>();
                for (String name : names) {
                    normalized.add(StringUtil.unicodeNormalize(name));
                }
                names.clear(); names.addAll(normalized);
            };
            normNames.accept(song.albumArtistNames);
            normNames.accept(song.artistNames);
            // Albums are grouped by artist: a song without any would join no album and leave
            // an empty one behind. Use MediaStore's unknown artist, as for untagged local files.
            if (song.artistNames.isEmpty() && song.albumArtistNames.isEmpty()) {
                song.artistNames = new ArrayList<>(List.of(MediaStore.UNKNOWN_STRING));
            }
            song.albumName = StringUtil.unicodeNormalize(song.albumName);
            song.title = StringUtil.unicodeNormalize(song.title);
            normNames.accept(song.genres);

            // Replace genre numerical ID3v1 values by textual ones
            List<String> normalizedGenres = new ArrayList<>(song.genres.size());
            for (String genre : song.genres) {
                try {
                    int genreId = Integer.parseInt(genre);
                    String genreName = GenreTypes.getInstanceOf().getValueForId(genreId);
                    if (genreName != null) {
                        normalizedGenres.add(genreName);
                    }
                } catch (NumberFormatException ignored) {
                    normalizedGenres.add(genre);
                }
            }
            song.genres = normalizedGenres;

            cache.addSong(song);

            if (!cacheOnly) {
                database.addSong(song);
            }

            notifyDiscographyChanged();
        }
    }

    public void triggerSyncWithMediaStore(boolean reset) {
        // Pick an attached activity
        if (attachedActivitiesAndQueue.isEmpty()) {throw new IllegalStateException("No attached activity");}
        final AbsMusicServiceActivity activity = (AbsMusicServiceActivity)attachedActivitiesAndQueue.keySet().toArray()[0];
        Objects.requireNonNull(activity);

        if (getCacheState() != MemCache.ConsistencyState.OK) {
            // Prevent reentrance - delay to later
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final long DELAY = 500L;

                final Handler handler = attachedActivitiesAndQueue.get(activity);
                Objects.requireNonNull(handler);
                handler.removeCallbacksAndMessages(TASK_QUEUE_COALESCENCE_TOKEN);
                handler.postDelayed(() -> triggerSyncWithMediaStore(reset), TASK_QUEUE_COALESCENCE_TOKEN, DELAY);
            } // else: too bad, just drop the operation. It is unlikely we get there anyway
        } else {
            (new SyncWithMediaStoreAsyncTask(activity, this, reset)).execute();
        }
    }

    SyncWithMediaStoreAsyncTask.Progress syncWithMediaStore(@NonNull final Consumer<SyncWithMediaStoreAsyncTask.Progress> progressUpdater) {
        final Context context = App.getInstance().getApplicationContext();

        // Zombies are tracks that are removed but still indexed by MediaStore
        final Predicate<Song> isZombie = (s) -> !(new File(s.data)).exists();

        // Whitelist
        final File startDirectory = PreferenceUtil.getInstance().getStartDirectory();
        final String startPath = FileUtil.safeGetCanonicalPath(startDirectory);
        final Predicate<Song> isNotWhiteListed = (s) -> {
            if (PreferenceUtil.getInstance().getWhitelistEnabled()) {
                return !s.data.startsWith(startPath);
            }
            return false;
        };

        // Blacklist
        final ArrayList<String> blackListedPaths = BlacklistStore.getInstance(context).getPaths();
        final Predicate<Song> isBlackListed = (s) -> {
            for (final String path : blackListedPaths) {
                if (s.data.startsWith(path)) return true;
            }
            return false;
        };

        final SyncWithMediaStoreAsyncTask.Progress counters = new SyncWithMediaStoreAsyncTask.Progress();
        final ArrayList<Song> alienSongs = MediaStoreBridge.getAllSongs(context);
        final Set<Long> importedSongIds = new HashSet<>();

        // The slow parts are file I/O: checking that each file still exists, and reading the tags
        // of new or changed songs. They run on a few threads, outside the cache lock (so the UI
        // is not blocked meanwhile). Songs are then added one at a time, in MediaStore order, as
        // soon as they are ready.
        final List<Song> candidates = new ArrayList<>(alienSongs.size());
        for (final Song song : alienSongs) {
            if (isNotWhiteListed.test(song)) continue;
            if (isBlackListed.test(song)) continue;
            candidates.add(song);
        }
        TagExtractor.prepareForParallelReads();
        final ExecutorService readers = Executors.newFixedThreadPool(TAG_READ_THREADS, TAG_READER_THREAD_FACTORY);
        try {
            final List<Future<PreparedSong>> prepared = new ArrayList<>(candidates.size());
            for (final Song song : candidates) {
                prepared.add(readers.submit(() -> {
                    if (isZombie.test(song)) return null;
                    final boolean readTags = needsTags(song);
                    if (readTags) TagExtractor.extractTags(song);
                    return new PreparedSong(song, readTags);
                }));
            }
            for (int i = 0; i < prepared.size(); i++) {
                PreparedSong ready;
                try {
                    ready = prepared.get(i).get();
                } catch (ExecutionException e) {
                    // Should not happen (the extractor catches its own errors): do it here instead
                    OopsHandler.collectStackTrace(e);
                    ready = new PreparedSong(candidates.get(i), false);
                }
                if (ready == null) continue; // zombie

                final Song matchedSong = getOrAddSong(ready.song, counters, ready.tagsRead);
                importedSongIds.add(matchedSong.id);

                progressUpdater.accept(counters);
            }
        } catch (InterruptedException e) {
            // Incomplete sync: do not clean "orphans" that were simply not processed
            Thread.currentThread().interrupt();
            return counters;
        } finally {
            readers.shutdownNow();
        }

        synchronized (cache) {
            // Clean orphan songs (removed from MediaStore)
            final Set<Long> cacheSongsId = new HashSet<>(cache.songsById.keySet()); // make a copy
            cacheSongsId.removeAll(importedSongIds);
            // OneDrive songs are not in MediaStore: the OneDrive scan maintains them
            cacheSongsId.removeIf(OneDriveLibrary::isOneDriveId);
            removeSongsById(cacheSongsId);

            counters.removed = cacheSongsId.size();
        }

        return counters;
    }

    @Override
    public void onServiceConnected() {}

    @Override
    public void onServiceDisconnected() {}

    @Override
    public void onQueueChanged() {}

    @Override
    public void onPlayingMetaChanged() {}

    @Override
    public void onPlayStateChanged() {}

    @Override
    public void onRepeatModeChanged() {}

    @Override
    public void onShuffleModeChanged() {}

    @Override
    public void onMediaStoreChanged() {
        triggerSyncWithMediaStore(false);
    }

    public void addChangedListener(Runnable listener) {
        changedListeners.add(listener);
    }

    public void removeChangedListener(Runnable listener) {
        attachedActivitiesAndQueue.forEach((activity, handler) -> handler.removeCallbacks(listener));
        changedListeners.remove(listener);
    }

    private void notifyDiscographyChanged() {
        // Notify the attached activiies to reload the UI content
        // Since this can be called from a background thread, make it safe by wrapping as an event to main thread
        attachedActivitiesAndQueue.forEach((activity, handler) -> {
            // Post as much 1 event per a coalescence period
            final long COALESCENCE_DELAY = 200L;
            for (final Runnable listener : changedListeners) {
                handler.removeCallbacks(listener);
                handler.postDelayed(listener, COALESCENCE_DELAY);
            }
        });
    }

    public void removeSongByPath(@NonNull String... paths) {
        synchronized (cache) {
            ArrayList<Long> matchingSongIds = new ArrayList<>();
            for (String path : paths) {
                final Song song = cache.songsByPath.get(path);
                if (song != null) {
                    matchingSongIds.add(song.id);
                }
            }
            removeSongsById(matchingSongIds);
        }
    }

    private void removeSongById(final long songId) {
        removeSongsById(List.of(songId));
    }

    private void removeSongsById(@NonNull final Collection<Long> songIds) {
        if (songIds.isEmpty()) return;

        for (final long songId : songIds) {
            cache.removeSongById(songId);
            database.removeSongById(songId);
        }
        notifyDiscographyChanged();
    }

    void clear() {
        database.clear();
        cache.clear();
        synchronized (cache) {
            oneDriveSignatures.clear();
        }
    }

    /**
     * Shows exactly these OneDrive songs in the library (Songs, Albums, Artists, Genres, search).
     * They live in memory only: the OneDrive library keeps them, and puts them back after a
     * restart or a reset. Unchanged songs are left as they are.
     */
    public void replaceOneDriveSongs(@NonNull final Collection<Song> songs) {
        boolean changed = false;
        synchronized (cache) {
            final Map<Long, Song> incoming = new HashMap<>();
            for (final Song song : songs) incoming.put(song.id, song);

            // Drop those no longer listed, or changed (they are added again below)
            for (final Long id : new ArrayList<>(cache.songsById.keySet())) {
                if (!OneDriveLibrary.isOneDriveId(id)) continue;
                final Song next = incoming.get(id);
                if (next == null || !oneDriveSignature(next).equals(oneDriveSignatures.get(id))) {
                    cache.removeSongById(id);
                    oneDriveSignatures.remove(id);
                    changed = true;
                }
            }
            for (final Song song : songs) {
                if (cache.songsById.containsKey(song.id)) continue;
                final String signature = oneDriveSignature(song); // before addSong normalizes names
                addSong(song, true);
                oneDriveSignatures.put(song.id, signature);
                changed = true;
            }
        }
        if (changed) notifyDiscographyChanged();
    }

    @NonNull
    private static String oneDriveSignature(@NonNull final Song song) {
        final char sep = '\u0000';
        return song.data + sep + song.dateModified + sep + song.albumId + sep + song.duration
                + sep + song.title + sep + song.artistNames + sep + song.albumArtistNames
                + sep + song.albumName + sep + song.trackNumber + sep + song.discNumber
                + sep + song.year + sep + song.genres;
    }

    private void fetchAllSongs() {
        setCacheState(MemCache.ConsistencyState.REFRESHING);

        Collection<Song> songs = database.fetchAllSongs();
        for (Song song : songs) {
            addSong(song, true);
        }

        setCacheState(MemCache.ConsistencyState.OK);
    }
}
