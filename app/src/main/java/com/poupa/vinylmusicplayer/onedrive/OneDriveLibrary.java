package com.poupa.vinylmusicplayer.onedrive;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.poupa.vinylmusicplayer.discog.Discography;
import com.poupa.vinylmusicplayer.discog.tagging.MultiValuesTagUtil;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.util.FileUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The music files of the signed-in OneDrive, with the song id Vinyl knows them by and the
 * location of their offline copy.
 *
 * OneDrive songs use negative ids below {@link #ID_BASE}, so they never collide with MediaStore
 * ids and survive queue, history and play count storage, which all keep song ids. A library scan
 * ({@link OneDriveScanner}) lists every music file of the drive here; those are shown in the
 * library and stream when played. Once a song has been played it is saved to Music/OneDrive and
 * indexed by MediaStore like any other song; that local copy then replaces it in the library.
 */
public final class OneDriveLibrary {

    private static final String TAG = "OneDriveLibrary";

    /** Entry of the signed-in drive, as far as scans know. */
    static final int DRIVE_CURRENT = 0;
    /**
     * A scan found the file deleted from the drive (or replaced, e.g. by another format): an
     * offline copy of it is a leftover.
     */
    static final int DRIVE_GONE = 1;
    /** Entry of an account signed out since: its offline copies stay, whatever the next account has. */
    static final int DRIVE_SIGNED_OUT = 2;

    /** OneDrive song ids are {@code ID_BASE - rowId}. */
    static final long ID_BASE = -1_000_000_000L;

    private static final String CACHE_DIR = "onedrive_stream";

    private final Context context;
    private final Db db;

    private final Map<Long, Entry> bySongId = new HashMap<>();
    private final Map<String, Entry> byItemId = new HashMap<>();
    private boolean loaded;

    private final Map<String, LibraryNaming.Folder> folders = new HashMap<>();
    private boolean foldersLoaded;

    /** One registered OneDrive file. */
    static final class Entry {
        final long songId;
        @NonNull final String itemId;
        @NonNull String name;
        /** Folder path below the drive root, "" for the root. Mirrored below Music/OneDrive. */
        @NonNull String folderPath;
        @Nullable String parentId;
        /** Listed in the library (found by the last scan). */
        boolean inLibrary;
        /** {@link #DRIVE_CURRENT}, {@link #DRIVE_GONE} or {@link #DRIVE_SIGNED_OUT}. */
        int driveState;
        long size;
        @Nullable String cTag;
        long modifiedMillis;
        @NonNull String title = "";
        @NonNull String artist = "";
        @NonNull String albumArtist = "";
        @NonNull String album = "";
        @NonNull String genre = "";
        int track;
        int disc;
        int year;
        long durationMs;
        /** Absolute path of the offline copy, or null while the song is only on OneDrive. */
        @Nullable String localPath;
        /** MediaStore uri of the offline copy, when it is in the shared library. */
        @Nullable String localUri;
        /** Version (cTag) whose duration was read from the file, if any. */
        @Nullable String probedCTag;

        Entry(long songId, @NonNull String itemId, @NonNull String name, @NonNull String folderPath) {
            this.songId = songId;
            this.itemId = itemId;
            this.name = name;
            this.folderPath = folderPath;
        }

        @NonNull
        String extension() {
            return AudioFormats.extension(name);
        }
    }

    /** What a scan changed in the library. */
    public static final class ScanResult {
        public int added;
        public int removed;
        public int total;
    }

    OneDriveLibrary(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.db = new Db(this.context);
    }

    public static boolean isOneDriveId(long songId) {
        return songId < ID_BASE;
    }

    // --- lookups ---------------------------------------------------------------------------

    @Nullable
    synchronized Entry entry(long songId) {
        ensureLoaded();
        return bySongId.get(songId);
    }

    /** The song for a registered OneDrive id, or {@code null} when it is unknown. */
    @Nullable
    public synchronized Song getSong(long songId) {
        final Entry e = entry(songId);
        return e == null ? null : toSong(e);
    }

    /**
     * Files of the device that a listed OneDrive song stands for (see {@link #librarySongs}):
     * the library shows the OneDrive song in their place. Updated by each {@link #librarySongs}.
     */
    @NonNull
    private volatile Set<String> shadowedPaths = java.util.Collections.emptySet();

    @NonNull
    Set<String> shadowedPaths() {
        return shadowedPaths;
    }

    /**
     * The OneDrive songs to show in the library: those found by the last scan, except the ones
     * whose offline copy (saved by Vinyl in Music/OneDrive) is already in the library: that copy
     * is shown instead. A file that is identical to one already on the device elsewhere (a
     * recording backed up to OneDrive...) is shown as the OneDrive song, in its OneDrive folder,
     * and plays from the device file; that device file is left out of the library
     * ({@link #shadowedPaths}), so it is not listed twice.
     */
    @NonNull
    List<Song> librarySongs() {
        final List<Song> candidates = new ArrayList<>();
        final Set<Long> onDevice = new HashSet<>();
        final Set<String> shadowed = new HashSet<>();
        final Map<Long, String> expectedCopies = new HashMap<>();
        final Set<String> claimedCopies = new HashSet<>();
        final Map<Long, Long> versionTimes = new HashMap<>();
        final Map<String, DeviceFile[]> deviceFiles = deviceIndex();
        synchronized (this) {
            ensureLoaded();
            for (final Entry e : bySongId.values()) {
                if (!e.inLibrary) continue;
                if (e.localPath == null && e.localUri == null) {
                    // Already on the device (e.g. recorded here, then backed up)? Use that file
                    adoptDeviceCopy(e, deviceFiles);
                } else {
                    dropRedundantOwnCopy(e, deviceFiles);
                }
                // Only real file paths: an offline copy saved where MediaStore gave no file path
                // is recorded by its content:// uri, which no File check can find
                if (e.localPath != null && e.localPath.startsWith(File.separator) && !isOwnCopy(e.localPath)) {
                    if (new File(e.localPath).isFile()) {
                        onDevice.add(e.songId);
                        shadowed.add(e.localPath);
                    } else {
                        clearOffline(e); // the device file was deleted: stream it again
                    }
                }
                candidates.add(toSong(e));
                if (e.localPath == null && e.localUri == null) {
                    expectedCopies.put(e.songId, expectedCopyPath(e));
                    versionTimes.put(e.songId, e.modifiedMillis);
                }
            }
            // Copies that belong to some file, e.g. one deleted from the drive whose copy is
            // about to be trashed: never another file's copy
            for (final Entry e : bySongId.values()) {
                if (e.localPath != null) claimedCopies.add(e.localPath);
            }
        }
        // Discography lookups outside this lock: Discography calls back into this class
        final Discography discography = Discography.getInstance();
        final List<Song> songs = new ArrayList<>(candidates.size());
        shadowedPaths = java.util.Collections.unmodifiableSet(shadowed);
        for (final Song song : candidates) {
            if (onDevice.contains(song.id)) {
                songs.add(song); // listed in its OneDrive folder, plays from the device file
                continue;
            }
            if (librarySong(song.id) != null) continue; // its offline copy is listed instead
            final String expected = expectedCopies.get(song.id);
            if (expected != null) {
                // A copy saved earlier but never recorded (the app stopped in between, or its
                // data was cleared): adopt it rather than listing the song twice. Only when it is
                // this very file: a new file of the same name (a deleted file uploaded again, a
                // new version) must not be hidden behind the old file's copy, which is trashed.
                // (Android renames a trashed file, so it no longer exists at its path.)
                final Song copy = discography.getSongByPath(expected);
                if (!copy.equals(Song.EMPTY_SONG) && !claimedCopies.contains(expected)
                        && isCopyOfVersion(new File(expected), versionTimes.get(song.id))) {
                    setOffline(song.id, copy.data, ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, copy.id).toString());
                    continue;
                }
            }
            songs.add(song);
        }
        return songs;
    }

    /**
     * Offline copies of files deleted from the drive (or replaced, e.g. converted to another
     * format) are leftovers that would stay in the library next to the drive's current files.
     * Moves Vinyl's own such copies to the device trash, where they can still be restored for
     * 30 days (Android 11+; on older versions they stay). Never touches a file Vinyl did not
     * make. Call only after a completed scan of the signed-in drive. Not on the main thread.
     *
     * @return how many copies were moved to the trash
     */
    int trashCopiesOfRemovedFiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0;
        final List<Entry> leftovers = new ArrayList<>();
        synchronized (this) {
            ensureLoaded();
            for (final Entry e : bySongId.values()) {
                if (e.driveState == DRIVE_GONE && !e.inLibrary && e.localUri != null && e.localPath != null
                        && isOwnCopy(e.localPath)) {
                    leftovers.add(e);
                }
            }
        }
        int trashed = 0;
        for (final Entry e : leftovers) {
            final Boolean done = trashOwnCopy(e);
            if (done == null) continue; // could not tell: try again after the next scan
            synchronized (this) {
                // Trashed, or already gone: either way it is no longer this song's copy
                if (!e.inLibrary && e.localUri != null) clearOffline(e);
            }
            if (done) trashed++;
        }
        return trashed;
    }

    /**
     * @return true when moved to the trash, false when it no longer exists or is not Vinyl's own
     * file (left alone), null when that could not be checked
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Nullable
    private Boolean trashOwnCopy(@NonNull Entry e) {
        return e.localUri == null || e.localPath == null ? Boolean.FALSE : trashOwnCopy(e.localPath, e.localUri);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Nullable
    private Boolean trashOwnCopy(@NonNull String localPath, @NonNull String localUri) {
        final ContentResolver resolver = context.getContentResolver();
        final Uri uri = Uri.parse(localUri);
        try (Cursor c = resolver.query(uri,
                new String[]{MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, null, null, null)) {
            if (c == null) return null;
            if (!c.moveToFirst()) return false; // already deleted
            if (!TextUtils.equals(c.getString(0), localPath)
                    || !TextUtils.equals(c.getString(1), context.getPackageName())) {
                return false; // not the copy Vinyl made
            }
        } catch (RuntimeException ex) {
            return null;
        }
        final ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_TRASHED, 1);
        try {
            return resolver.update(uri, v, null, null) > 0 ? Boolean.TRUE : null;
        } catch (RuntimeException ex) {
            Log.w(TAG, "Could not move the old copy " + localPath + " to the trash", ex);
            return null;
        }
    }

    // --- files changed on the drive ---------------------------------------------------------

    /** Own copies of an older version of a file, to discard once the database is written. */
    private final List<String[]> staleOwnCopies = new ArrayList<>();

    /**
     * The file's content changed on the drive (a new version uploaded over it): its offline copy
     * holds the old version, and kept playing in its place. Forgets that copy, so the new version
     * is listed and streams (and is saved again); Vinyl's own copy of the old version is then
     * discarded by {@link #discardStaleCopies}. A file Vinyl did not make (an identical original
     * found on the device) is only no longer used, never touched.
     */
    private void forgetOutdatedCopy(@NonNull Entry e, @NonNull DriveItem item, @NonNull ContentValues v) {
        if (e.localPath == null && e.localUri == null) return;
        if (e.cTag == null || item.cTag == null || e.cTag.equals(item.cTag)) return; // same content
        if (isOwnCopy(e.localPath)) {
            staleOwnCopies.add(new String[]{e.localPath, e.localUri});
        }
        e.localPath = null;
        e.localUri = null;
        v.putNull(Db.LOCAL_PATH);
        v.putNull(Db.LOCAL_URI);
        ownCopiesChecked.remove(e.itemId);
        deviceCopyMissedAt.remove(e.itemId);
    }

    /**
     * Moves own copies of replaced versions to the device trash (restorable for 30 days; deleted
     * before Android 11), so the new version's copy can take their place. Call after the database
     * transaction that found them.
     */
    private void discardStaleCopies() {
        if (staleOwnCopies.isEmpty()) return;
        final List<String[]> copies = new ArrayList<>(staleOwnCopies);
        staleOwnCopies.clear();
        for (final String[] copy : copies) {
            final String path = copy[0];
            final String uri = copy[1];
            try {
                if (uri == null) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(path).delete();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    trashOwnCopy(path, uri);
                } else {
                    context.getContentResolver().delete(Uri.parse(uri), null, null);
                }
            } catch (RuntimeException ex) {
                Log.w(TAG, "Could not discard the old copy " + path, ex);
            }
        }
    }

    /** Leeway for the device clock against OneDrive's when comparing times. */
    private static final long CLOCK_SKEW_MS = 5 * 60 * 1000L;

    /**
     * Whether a copy found where the file's copy belongs can hold its current version: it exists
     * and was written after that version reached the drive. A copy of an older version (the
     * file was replaced, and the app stopped before that copy was discarded) is older than it.
     */
    private static boolean isCopyOfVersion(@NonNull File copy, @Nullable Long versionMillis) {
        if (!copy.isFile()) return false; // gone, or trashed (Android renames trashed files)
        if (versionMillis == null || versionMillis <= 0) return true; // unknown: trust the name
        final long written = copy.lastModified();
        return written == 0 || written >= versionMillis - CLOCK_SKEW_MS;
    }

    /** Where {@link OfflineSaver} puts the copy of an entry in the shared Music folder. */
    @NonNull
    private static String expectedCopyPath(@NonNull Entry e) {
        final String folder = OfflineSaver.safeFolderPath(e.folderPath);
        final File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MUSIC), OfflineSaver.ROOT_FOLDER + (folder.isEmpty() ? "" : "/" + folder));
        return new File(dir, OfflineSaver.safeName(e.name)).getAbsolutePath();
    }

    /**
     * Registers listed files (or refreshes their metadata) and returns them as songs, in order.
     * Writes the database: never call on the main thread.
     */
    @NonNull
    public synchronized List<Song> register(@NonNull List<DriveItem> items, @NonNull String folderPath) {
        ensureLoaded();
        staleOwnCopies.clear(); // from a transaction that failed: the database still uses them
        final List<Song> songs = new ArrayList<>(items.size());
        final SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            for (final DriveItem item : items) {
                if (!item.isPlayable()) continue;
                songs.add(toSong(upsert(w, item, folderPath, null)));
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        discardStaleCopies();
        return songs;
    }

    /**
     * Applies a scan of the drive.
     *
     * @param full          a complete listing: files not in it leave the library
     * @param rootId        id of the drive root folder
     * @param changedFolders folders listed by the scan (all of them for a full scan)
     * @param audio         music files listed by the scan, with their parent folder
     * @param deletedIds    files and folders the scan reports as deleted
     */
    @NonNull
    synchronized ScanResult applyScan(boolean full, @Nullable String rootId,
                                      @NonNull Collection<LibraryNaming.Folder> changedFolders,
                                      @NonNull Collection<DriveItem> audio,
                                      @NonNull Collection<String> deletedIds) {
        ensureLoaded();
        ensureFoldersLoaded();
        staleOwnCopies.clear(); // from a transaction that failed: the database still uses them
        final ScanResult result = new ScanResult();
        final SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            // Folder tree
            if (full) {
                folders.clear();
                w.delete(Db.FOLDERS, null, null);
            }
            for (final String id : deletedIds) {
                if (folders.remove(id) != null) w.delete(Db.FOLDERS, Db.FOLDER_ID + "=?", new String[]{id});
            }
            for (final LibraryNaming.Folder folder : changedFolders) {
                folders.put(folder.id, folder);
                final ContentValues v = new ContentValues();
                v.put(Db.FOLDER_ID, folder.id);
                v.put(Db.FOLDER_NAME, folder.name);
                v.put(Db.FOLDER_PARENT, folder.parentId);
                w.insertWithOnConflict(Db.FOLDERS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            final LibraryNaming.PathResolver paths = new LibraryNaming.PathResolver(folders, rootId);

            // Files listed by the scan join (or stay in) the library
            final Set<String> seen = new HashSet<>();
            for (final DriveItem item : audio) {
                final String path = paths.path(item.parentId);
                if (path == null) continue; // in a folder that is gone
                final Entry e = upsert(w, item, path, item.parentId);
                seen.add(e.itemId);
                if (!e.inLibrary) {
                    setInLibrary(w, e, true);
                    result.added++;
                }
                // On this drive (again: restored from the recycle bin, or the account signed in again)
                if (e.driveState != DRIVE_CURRENT) setDriveState(w, e, DRIVE_CURRENT);
            }

            // Folders a full listing found: a file of one of them that it did not list is gone
            final Set<String> listedFolders = new HashSet<>();
            if (full) {
                listedFolders.add("");
                for (final String folderId : folders.keySet()) {
                    final String path = paths.path(folderId);
                    if (path != null) listedFolders.add(path);
                }
            }

            // Files that left the drive (or every unlisted file after a full listing)
            for (final Entry e : bySongId.values()) {
                if (seen.contains(e.itemId)) continue;
                if (!e.inLibrary) {
                    // Never listed by a scan (e.g. only browsed), or taken out earlier: the file
                    // may still have been deleted from the drive since (its copy is a leftover)
                    if (e.driveState == DRIVE_CURRENT
                            && (deletedIds.contains(e.itemId) || listedFolders.contains(e.folderPath))) {
                        setDriveState(w, e, DRIVE_GONE);
                    }
                    continue;
                }
                boolean gone = full || deletedIds.contains(e.itemId);
                if (!gone && e.parentId != null) {
                    // A folder may have been renamed, moved or deleted
                    final String path = paths.path(e.parentId);
                    if (path == null) {
                        gone = true;
                    } else if (!path.equals(e.folderPath)) {
                        e.folderPath = path;
                        final ContentValues v = new ContentValues();
                        v.put(Db.FOLDER_PATH, path);
                        w.update(Db.TABLE, v, Db.ITEM_ID + "=?", new String[]{e.itemId});
                    }
                }
                if (gone) {
                    setInLibrary(w, e, false);
                    setDriveState(w, e, DRIVE_GONE);
                    result.removed++;
                }
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        discardStaleCopies();
        for (final Entry e : bySongId.values()) {
            if (e.inLibrary) result.total++;
        }
        return result;
    }

    /** Takes every OneDrive song out of the library (signed out). Offline copies stay. */
    synchronized void clearLibrary() {
        ensureLoaded();
        final SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            final ContentValues v = new ContentValues();
            v.put(Db.IN_LIBRARY, 0);
            v.put(Db.DRIVE_STATE, DRIVE_SIGNED_OUT);
            w.update(Db.TABLE, v, null, null);
            w.delete(Db.FOLDERS, null, null);
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        for (final Entry e : bySongId.values()) {
            e.inLibrary = false;
            e.driveState = DRIVE_SIGNED_OUT;
        }
        folders.clear();
    }

    /**
     * Marks which of {@code items} have a complete offline copy on this device. Checks the
     * storage: never call on the main thread.
     */
    public synchronized void markOfflineState(@NonNull List<DriveItem> items) {
        ensureLoaded();
        for (final DriveItem item : items) {
            final Entry e = byItemId.get(item.id);
            item.offline = e != null && localCopyExists(e);
            // A duration read from the file when Graph has none
            if (item.durationMs <= 0 && e != null && e.durationMs > 0
                    && e.cTag != null && e.cTag.equals(item.cTag)) {
                item.durationMs = e.durationMs;
            }
        }
    }

    /**
     * The uri the player should open: the offline copy when there is one, otherwise a OneDrive
     * stream. May check the storage: not on the main thread.
     */
    @NonNull
    synchronized String playbackUri(@NonNull Song song) {
        final Entry e = entry(song.id);
        if (e != null && (localCopyExists(e) || adoptDeviceCopy(e))) {
            return e.localUri != null ? e.localUri : e.localPath;
        }
        return OneDrive.STREAM_SCHEME + song.id;
    }

    /** Path of the song's offline copy (or the identical file found on the device), if any. */
    @Nullable
    synchronized String offlinePath(long songId) {
        final Entry e = entry(songId);
        return e != null ? e.localPath : null;
    }

    // --- files already on the device -------------------------------------------------------

    /**
     * The folders where {@link OfflineSaver} keeps its copies (shared Music folder, the app's own
     * external storage, its internal fallback), each ending with a separator. A path is an own
     * copy only when it starts with one of them: a user folder that merely contains
     * "Music/OneDrive" somewhere in its path is not Vinyl's.
     */
    @Nullable
    private volatile List<String> ownCopyRoots;

    @NonNull
    private List<String> ownCopyRoots() {
        List<String> roots = ownCopyRoots;
        if (roots != null) return roots;
        roots = new ArrayList<>();
        final String folder = File.separator + OfflineSaver.ROOT_FOLDER + File.separator;
        try {
            roots.add(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC).getAbsolutePath() + folder);
        } catch (RuntimeException ignored) {
            // no shared storage
        }
        try {
            for (final File dir : context.getExternalFilesDirs(android.os.Environment.DIRECTORY_MUSIC)) {
                if (dir != null) roots.add(dir.getAbsolutePath() + folder);
            }
        } catch (RuntimeException ignored) {
            // no external storage
        }
        roots.add(new File(context.getFilesDir(), "music").getAbsolutePath() + folder);
        roots = java.util.Collections.unmodifiableList(roots);
        ownCopyRoots = roots;
        return roots;
    }

    /** Whether a path is one of {@link OfflineSaver}'s own copies. */
    private boolean isOwnCopy(@Nullable String path) {
        if (path == null) return false;
        for (final String root : ownCopyRoots()) {
            if (path.startsWith(root)) return true;
        }
        return false;
    }

    /** Don't ask MediaStore again for a file that was not there a moment ago. */
    private static final long DEVICE_LOOKUP_RETRY_MS = 60_000;
    private final Map<String, Long> deviceCopyMissedAt = new HashMap<>();
    /** Own copies already checked for an identical original elsewhere (once per run). */
    private final java.util.Set<String> ownCopiesChecked = new HashSet<>();

    /** A file on the device. */
    private static final class DeviceFile {
        @NonNull final String path;
        @NonNull final String uri;

        DeviceFile(@NonNull String path, @NonNull String uri) {
            this.path = path;
            this.uri = uri;
        }
    }

    /**
     * A file on the device identical to the OneDrive one (same name and size), e.g. a recording
     * made on this phone and backed up to OneDrive. Prefers a file that is not one of
     * {@link OfflineSaver}'s own copies. Queries MediaStore: not on the main thread.
     */
    @Nullable
    private DeviceFile findDeviceFile(@NonNull String name, long size, boolean othersOnly) {
        if (size <= 0) return null;
        DeviceFile ownCopy = null;
        try (Cursor c = context.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.SIZE + "=?",
                new String[]{name, String.valueOf(size)}, null)) {
            while (c != null && c.moveToNext()) {
                final String path = c.getString(1);
                if (path == null || !new File(path).isFile()) continue;
                final DeviceFile file = new DeviceFile(path, ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString());
                if (!isOwnCopy(path)) return file;
                if (!othersOnly && ownCopy == null) ownCopy = file;
            }
        } catch (RuntimeException ignored) {
            // no MediaStore access: download as usual
        }
        return ownCopy;
    }

    /**
     * All audio files on the device by name and size, for many lookups at once: [0] a file that
     * is not an own copy, [1] an own copy. One MediaStore query: not on the main thread.
     */
    @NonNull
    private Map<String, DeviceFile[]> deviceIndex() {
        final Map<String, DeviceFile[]> index = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                final String path = c.getString(1);
                final String name = c.getString(2);
                if (path == null || name == null) continue;
                final DeviceFile file = new DeviceFile(path, ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString());
                final DeviceFile[] slots = index.computeIfAbsent(name + '|' + c.getLong(3), k -> new DeviceFile[2]);
                final int slot = isOwnCopy(path) ? 1 : 0;
                if (slots[slot] == null) slots[slot] = file;
            }
        } catch (RuntimeException ignored) {
            // no MediaStore access: nothing to match
        }
        return index;
    }

    /**
     * Uses an identical file already on the device as the song's offline copy: no download.
     *
     * @param index from {@link #deviceIndex()}, or {@code null} to query for this one file
     */
    private boolean adoptDeviceCopy(@NonNull Entry e, @Nullable Map<String, DeviceFile[]> index) {
        final DeviceFile file;
        if (index != null) {
            final DeviceFile[] slots = index.get(e.name + '|' + e.size);
            file = slots == null ? null : slots[0] != null ? slots[0] : slots[1];
        } else {
            final long now = android.os.SystemClock.elapsedRealtime();
            final Long missedAt = deviceCopyMissedAt.get(e.itemId);
            if (missedAt != null && now - missedAt < DEVICE_LOOKUP_RETRY_MS) return false;
            file = findDeviceFile(e.name, e.size, false);
            if (file == null) deviceCopyMissedAt.put(e.itemId, now);
        }
        if (file == null || !new File(file.path).isFile()) return false;
        setOffline(e.songId, file.path, file.uri);
        return true;
    }

    private boolean adoptDeviceCopy(@NonNull Entry e) {
        return adoptDeviceCopy(e, null);
    }

    /**
     * When an own copy has an identical original elsewhere on the device (the round trip
     * phone → OneDrive → phone), keeps the original and deletes the copy, which only Vinyl made.
     */
    private void dropRedundantOwnCopy(@NonNull Entry e, @NonNull Map<String, DeviceFile[]> index) {
        if (e.localPath == null || !isOwnCopy(e.localPath) || !ownCopiesChecked.add(e.itemId)) return;
        final DeviceFile[] slots = index.get(e.name + '|' + e.size);
        final DeviceFile original = slots != null ? slots[0] : null;
        if (original == null || !new File(original.path).isFile()) return;
        final String ownPath = e.localPath;
        final String ownUri = e.localUri;
        setOffline(e.songId, original.path, original.uri);
        try {
            if (ownUri != null) context.getContentResolver().delete(Uri.parse(ownUri), null, null);
            else //noinspection ResultOfMethodCallIgnored
                new File(ownPath).delete();
        } catch (RuntimeException ignored) {
            // could not delete: it stays, harmless
        }
    }

    /** Whether the song has a complete offline copy. May check the storage: not on the main thread. */
    synchronized boolean isOffline(long songId) {
        final Entry e = entry(songId);
        return e != null && localCopyExists(e);
    }

    /**
     * The library song for the offline copy of a OneDrive song, so menus that need a local
     * MediaStore song (playlists, tag editor, sharing...) can act on it. {@code null} while the
     * song is only on OneDrive, or before MediaStore has indexed the copy.
     */
    @Nullable
    Song librarySong(long songId) {
        // Read the entry under this lock, query Discography outside it: Discography calls back
        // into this class while holding its own lock
        final String localUri;
        final String localPath;
        synchronized (this) {
            final Entry e = entry(songId);
            if (e == null) return null;
            localUri = e.localUri;
            localPath = e.localPath;
        }
        final Discography discography = Discography.getInstance();
        if (localUri != null) {
            try {
                final Song song = discography.getSong(ContentUris.parseId(Uri.parse(localUri)));
                if (!song.equals(Song.EMPTY_SONG)) return song;
            } catch (NumberFormatException | UnsupportedOperationException ignored) {
                // not a MediaStore item uri
            }
        }
        if (localPath != null) {
            // A device file shown as this OneDrive song: kept out of the library listing
            final Song shadowed = discography.getShadowedSong(localPath);
            if (shadowed != null) return shadowed;
            final Song song = discography.getSongByPath(localPath);
            if (!song.equals(Song.EMPTY_SONG) && !isOneDriveId(song.id)) return song;
        }
        return null;
    }

    /** Where the stream of a song is cached while it downloads. */
    @NonNull
    File cacheFile(@NonNull Entry e) {
        return new File(cacheDir(), (-e.songId) + "." + (e.extension().isEmpty() ? "bin" : e.extension()));
    }

    @NonNull
    File cacheDir() {
        return new File(context.getCacheDir(), CACHE_DIR);
    }

    // --- offline copies --------------------------------------------------------------------

    synchronized void setOffline(long songId, @NonNull String localPath, @Nullable String localUri) {
        final Entry e = entry(songId);
        if (e == null) return;
        e.localPath = localPath;
        e.localUri = localUri;
        final ContentValues v = new ContentValues();
        v.put(Db.LOCAL_PATH, localPath);
        v.put(Db.LOCAL_URI, localUri);
        db.getWritableDatabase().update(Db.TABLE, v, Db.ITEM_ID + "=?", new String[]{e.itemId});
    }

    private void clearOffline(@NonNull Entry e) {
        e.localPath = null;
        e.localUri = null;
        final ContentValues v = new ContentValues();
        v.putNull(Db.LOCAL_PATH);
        v.putNull(Db.LOCAL_URI);
        db.getWritableDatabase().update(Db.TABLE, v, Db.ITEM_ID + "=?", new String[]{e.itemId});
    }

    /** Checks that the offline copy still exists; forgets it when the user deleted it. */
    private boolean localCopyExists(@NonNull Entry e) {
        if (e.localPath == null && e.localUri == null) return false;
        boolean exists;
        if (e.localUri != null) {
            exists = false;
            try (Cursor c = context.getContentResolver().query(
                    Uri.parse(e.localUri), new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
                exists = c != null && c.moveToFirst();
            } catch (RuntimeException ignored) {
                // the provider rejects the uri: treat as gone
            }
        } else {
            final File file = new File(e.localPath);
            exists = file.isFile() && file.length() > 0;
        }
        if (!exists) clearOffline(e);
        return exists;
    }

    // --- songs -----------------------------------------------------------------------------

    @NonNull
    private Song toSong(@NonNull Entry e) {
        final String title = !e.title.isEmpty() ? e.title : FileUtil.stripExtension(e.name);
        final String data = e.localPath != null ? e.localPath : cacheFile(e).getAbsolutePath();
        final long modifiedSeconds = e.modifiedMillis / 1000L;
        final String album = LibraryNaming.album(e.album, e.folderPath);
        final String artist = LibraryNaming.artist(e.artist);
        final Song song = new Song(
                e.songId,
                title,
                e.track,
                e.year,
                e.durationMs,
                data,
                modifiedSeconds,
                modifiedSeconds,
                LibraryNaming.albumId(album, e.albumArtist.isEmpty() ? artist : e.albumArtist),
                album,
                MultiValuesTagUtil.split(artist));
        song.albumArtistNames = MultiValuesTagUtil.split(e.albumArtist);
        song.genres = MultiValuesTagUtil.split(e.genre);
        song.discNumber = e.disc;
        return song;
    }

    /** Inserts or updates the row of a listed file; keeps its id, offline copy and library state. */
    @NonNull
    private Entry upsert(@NonNull SQLiteDatabase w, @NonNull DriveItem item, @NonNull String folderPath,
                         @Nullable String parentId) {
        Entry e = byItemId.get(item.id);
        // Graph often has no duration (recordings, work accounts): keep the one read from the
        // file itself as long as the file is unchanged
        final long durationMs = item.durationMs > 0 ? item.durationMs
                : (e != null && e.cTag != null && e.cTag.equals(item.cTag) ? e.durationMs : 0);
        final ContentValues v = metadataValues(item, folderPath);
        v.put(Db.DURATION, durationMs);
        if (parentId != null) v.put(Db.PARENT_ID, parentId);
        if (e == null) {
            v.put(Db.ITEM_ID, item.id);
            final long rowId = w.insertOrThrow(Db.TABLE, null, v);
            e = new Entry(ID_BASE - rowId, item.id, item.name, folderPath);
            bySongId.put(e.songId, e);
            byItemId.put(e.itemId, e);
        } else {
            forgetOutdatedCopy(e, item, v);
            w.update(Db.TABLE, v, Db.ITEM_ID + "=?", new String[]{item.id});
        }
        applyMetadata(e, item, folderPath);
        e.durationMs = durationMs;
        if (parentId != null) e.parentId = parentId;
        return e;
    }

    // --- durations read from the files ------------------------------------------------------

    /** A library file whose duration is unknown and has not been read from this version yet. */
    static final class ProbeTarget {
        final long songId;
        @NonNull final String itemId;
        final long size;
        @NonNull final String extension;
        @Nullable final String cTag;

        ProbeTarget(@NonNull Entry e) {
            songId = e.songId;
            itemId = e.itemId;
            size = e.size;
            extension = e.extension();
            cTag = e.cTag;
        }
    }

    @NonNull
    synchronized List<ProbeTarget> durationProbeTargets() {
        ensureLoaded();
        final List<ProbeTarget> targets = new ArrayList<>();
        for (final Entry e : bySongId.values()) {
            if (!e.inLibrary || e.durationMs > 0 || e.size <= 0) continue;
            if (e.probedCTag != null && e.probedCTag.equals(e.cTag)) continue; // tried this version
            targets.add(new ProbeTarget(e));
        }
        return targets;
    }

    /** Stores a duration read from the file (0 when it could not be read: not tried again). */
    synchronized void setProbedDuration(@NonNull ProbeTarget target, long durationMs) {
        final Entry e = entry(target.songId);
        if (e == null || (e.cTag != null && !e.cTag.equals(target.cTag))) return; // changed meanwhile
        e.probedCTag = target.cTag;
        final ContentValues v = new ContentValues();
        v.put(Db.PROBED_CTAG, target.cTag);
        if (durationMs > 0) {
            e.durationMs = durationMs;
            v.put(Db.DURATION, durationMs);
        }
        db.getWritableDatabase().update(Db.TABLE, v, Db.ITEM_ID + "=?", new String[]{e.itemId});
    }

    private static void setInLibrary(@NonNull SQLiteDatabase w, @NonNull Entry e, boolean inLibrary) {
        e.inLibrary = inLibrary;
        final ContentValues v = new ContentValues();
        v.put(Db.IN_LIBRARY, inLibrary ? 1 : 0);
        w.update(Db.TABLE, v, Db.ITEM_ID + "=?", new String[]{e.itemId});
    }

    private static void setDriveState(@NonNull SQLiteDatabase w, @NonNull Entry e, int state) {
        e.driveState = state;
        final ContentValues v = new ContentValues();
        v.put(Db.DRIVE_STATE, state);
        w.update(Db.TABLE, v, Db.ITEM_ID + "=?", new String[]{e.itemId});
    }

    private static void applyMetadata(@NonNull Entry e, @NonNull DriveItem item, @NonNull String folderPath) {
        e.name = item.name;
        e.folderPath = folderPath;
        e.size = item.size;
        e.cTag = item.cTag;
        e.modifiedMillis = item.lastModifiedMillis;
        e.title = item.title;
        e.artist = item.artist;
        e.albumArtist = item.albumArtist;
        e.album = item.album;
        e.genre = item.genre;
        e.track = item.track;
        e.disc = item.disc;
        e.year = item.year;
        e.durationMs = item.durationMs;
    }

    @NonNull
    private static ContentValues metadataValues(@NonNull DriveItem item, @NonNull String folderPath) {
        final ContentValues v = new ContentValues();
        v.put(Db.NAME, item.name);
        v.put(Db.FOLDER_PATH, folderPath);
        v.put(Db.SIZE, item.size);
        v.put(Db.CTAG, item.cTag);
        v.put(Db.MODIFIED, item.lastModifiedMillis);
        v.put(Db.TITLE, item.title);
        v.put(Db.ARTIST, item.artist);
        v.put(Db.ALBUM_ARTIST, item.albumArtist);
        v.put(Db.ALBUM, item.album);
        v.put(Db.GENRE, item.genre);
        v.put(Db.TRACK, item.track);
        v.put(Db.DISC, item.disc);
        v.put(Db.YEAR, item.year);
        v.put(Db.DURATION, item.durationMs);
        return v;
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try (Cursor c = db.getReadableDatabase().query(Db.TABLE, null, null, null, null, null, null)) {
            final int iRow = c.getColumnIndexOrThrow(Db.ROW_ID);
            final int iItem = c.getColumnIndexOrThrow(Db.ITEM_ID);
            final int iName = c.getColumnIndexOrThrow(Db.NAME);
            final int iFolder = c.getColumnIndexOrThrow(Db.FOLDER_PATH);
            final int iParent = c.getColumnIndexOrThrow(Db.PARENT_ID);
            final int iInLibrary = c.getColumnIndexOrThrow(Db.IN_LIBRARY);
            final int iSize = c.getColumnIndexOrThrow(Db.SIZE);
            final int iCTag = c.getColumnIndexOrThrow(Db.CTAG);
            final int iModified = c.getColumnIndexOrThrow(Db.MODIFIED);
            final int iTitle = c.getColumnIndexOrThrow(Db.TITLE);
            final int iArtist = c.getColumnIndexOrThrow(Db.ARTIST);
            final int iAlbumArtist = c.getColumnIndexOrThrow(Db.ALBUM_ARTIST);
            final int iAlbum = c.getColumnIndexOrThrow(Db.ALBUM);
            final int iGenre = c.getColumnIndexOrThrow(Db.GENRE);
            final int iTrack = c.getColumnIndexOrThrow(Db.TRACK);
            final int iDisc = c.getColumnIndexOrThrow(Db.DISC);
            final int iYear = c.getColumnIndexOrThrow(Db.YEAR);
            final int iDuration = c.getColumnIndexOrThrow(Db.DURATION);
            final int iPath = c.getColumnIndexOrThrow(Db.LOCAL_PATH);
            final int iUri = c.getColumnIndexOrThrow(Db.LOCAL_URI);
            final int iProbed = c.getColumnIndexOrThrow(Db.PROBED_CTAG);
            final int iDriveState = c.getColumnIndexOrThrow(Db.DRIVE_STATE);
            while (c.moveToNext()) {
                final Entry e = new Entry(ID_BASE - c.getLong(iRow), c.getString(iItem),
                        nonNull(c.getString(iName)), nonNull(c.getString(iFolder)));
                e.parentId = c.getString(iParent);
                e.inLibrary = c.getInt(iInLibrary) != 0;
                e.size = c.getLong(iSize);
                e.cTag = c.getString(iCTag);
                e.modifiedMillis = c.getLong(iModified);
                e.title = nonNull(c.getString(iTitle));
                e.artist = nonNull(c.getString(iArtist));
                e.albumArtist = nonNull(c.getString(iAlbumArtist));
                e.album = nonNull(c.getString(iAlbum));
                e.genre = nonNull(c.getString(iGenre));
                e.track = c.getInt(iTrack);
                e.disc = c.getInt(iDisc);
                e.year = c.getInt(iYear);
                e.durationMs = c.getLong(iDuration);
                e.localPath = c.getString(iPath);
                e.localUri = c.getString(iUri);
                e.probedCTag = c.getString(iProbed);
                e.driveState = c.getInt(iDriveState);
                bySongId.put(e.songId, e);
                byItemId.put(e.itemId, e);
            }
        }
    }

    private void ensureFoldersLoaded() {
        if (foldersLoaded) return;
        foldersLoaded = true;
        try (Cursor c = db.getReadableDatabase().query(Db.FOLDERS,
                new String[]{Db.FOLDER_ID, Db.FOLDER_NAME, Db.FOLDER_PARENT}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                final LibraryNaming.Folder f = new LibraryNaming.Folder(c.getString(0), nonNull(c.getString(1)), c.getString(2));
                folders.put(f.id, f);
            }
        }
    }

    @NonNull
    private static String nonNull(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** {@code onedrive.db}; excluded from backups (res/xml/backup_rules.xml). */
    private static final class Db extends SQLiteOpenHelper {
        static final String NAME_DB = "onedrive.db";
        static final int VERSION = 5;

        static final String TABLE = "items";
        static final String ROW_ID = "_id";
        static final String ITEM_ID = "item_id";
        static final String NAME = "name";
        static final String FOLDER_PATH = "folder_path";
        static final String PARENT_ID = "parent_id";
        static final String IN_LIBRARY = "in_library";
        static final String SIZE = "size";
        static final String CTAG = "ctag";
        static final String MODIFIED = "modified";
        static final String TITLE = "title";
        static final String ARTIST = "artist";
        static final String ALBUM_ARTIST = "album_artist";
        static final String ALBUM = "album";
        static final String GENRE = "genre";
        static final String TRACK = "track";
        static final String DISC = "disc";
        static final String YEAR = "year";
        static final String DURATION = "duration";
        static final String LOCAL_PATH = "local_path";
        static final String LOCAL_URI = "local_uri";
        static final String PROBED_CTAG = "probed_ctag";
        /** {@link OneDriveLibrary#DRIVE_CURRENT}, {@link OneDriveLibrary#DRIVE_GONE}... */
        static final String DRIVE_STATE = "gone_from_drive";

        static final String FOLDERS = "folders";
        static final String FOLDER_ID = "id";
        static final String FOLDER_NAME = "name";
        static final String FOLDER_PARENT = "parent_id";

        Db(Context context) {
            super(context, NAME_DB, null, VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // AUTOINCREMENT: row ids, and so song ids, are never reused after a row is deleted
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + ROW_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + ITEM_ID + " TEXT NOT NULL UNIQUE, "
                    + NAME + " TEXT NOT NULL, "
                    + FOLDER_PATH + " TEXT NOT NULL, "
                    + SIZE + " INTEGER NOT NULL DEFAULT 0, "
                    + CTAG + " TEXT, "
                    + MODIFIED + " INTEGER NOT NULL DEFAULT 0, "
                    + TITLE + " TEXT, "
                    + ARTIST + " TEXT, "
                    + ALBUM_ARTIST + " TEXT, "
                    + ALBUM + " TEXT, "
                    + GENRE + " TEXT, "
                    + TRACK + " INTEGER NOT NULL DEFAULT 0, "
                    + DISC + " INTEGER NOT NULL DEFAULT 0, "
                    + YEAR + " INTEGER NOT NULL DEFAULT 0, "
                    + DURATION + " INTEGER NOT NULL DEFAULT 0, "
                    + LOCAL_PATH + " TEXT, "
                    + LOCAL_URI + " TEXT, "
                    + PARENT_ID + " TEXT, "
                    + IN_LIBRARY + " INTEGER NOT NULL DEFAULT 0, "
                    + PROBED_CTAG + " TEXT, "
                    + DRIVE_STATE + " INTEGER NOT NULL DEFAULT 0)");
            createFolders(db);
        }

        private static void createFolders(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + FOLDERS + " ("
                    + FOLDER_ID + " TEXT PRIMARY KEY, "
                    + FOLDER_NAME + " TEXT NOT NULL, "
                    + FOLDER_PARENT + " TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // Version 2: library scan (whole drive listed in the library)
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + PARENT_ID + " TEXT");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + IN_LIBRARY + " INTEGER NOT NULL DEFAULT 0");
                createFolders(db);
            }
            if (oldVersion < 3) {
                // Version 3: durations read from the files when Graph has none
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + PROBED_CTAG + " TEXT");
            }
            if (oldVersion < 4) {
                // Version 4: files deleted from the drive, whose offline copies are leftovers
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + DRIVE_STATE + " INTEGER NOT NULL DEFAULT 0");
            }
            if (oldVersion < 5) {
                // Files gone before this version: those not in the library although their folder
                // is on the signed-in drive (it has library files, or the last scan listed it).
                // After signing out, nothing is in the library and no folder is known: none.
                db.execSQL("UPDATE " + TABLE + " SET " + DRIVE_STATE + "=" + DRIVE_GONE
                        + " WHERE " + IN_LIBRARY + "=0 AND " + DRIVE_STATE + "=" + DRIVE_CURRENT
                        + " AND (" + PARENT_ID + " IN (SELECT " + FOLDER_ID + " FROM " + FOLDERS + ")"
                        + " OR " + FOLDER_PATH + " IN (SELECT " + FOLDER_PATH + " FROM " + TABLE + " WHERE " + IN_LIBRARY + "=1))");
            }
        }
    }
}
