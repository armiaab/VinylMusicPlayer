package com.poupa.vinylmusicplayer.onedrive;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Saves a completely downloaded OneDrive song for offline listening, mirroring its OneDrive
 * folder below {@code Music/OneDrive}. Files there are indexed by MediaStore, so they show up in
 * the library (songs, albums, artists, folders) with their own tags and cover art.
 *
 * <ul>
 *   <li>Android 10+: inserted through MediaStore (no storage permission needed).</li>
 *   <li>Android 6-9: written to the shared Music folder, then scanned.</li>
 *   <li>Otherwise: kept in the app's own storage; it plays offline but stays out of the library.</li>
 * </ul>
 */
@RequiresApi(23)
final class OfflineSaver {

    private static final String TAG = "OneDriveOfflineSaver";

    /** Below the shared Music folder. */
    static final String ROOT_FOLDER = "OneDrive";

    private static final int COPY_BUFFER = 256 * 1024;

    /** Where a copy was saved. */
    static final class Saved {
        @NonNull final String path;
        @Nullable final String uri;

        Saved(@NonNull String path, @Nullable String uri) {
            this.path = path;
            this.uri = uri;
        }
    }

    private OfflineSaver() {}

    /**
     * Copies {@code source}; {@code null} when no location could take it.
     *
     * @param fileName   the OneDrive file name
     * @param folderPath the OneDrive folder below the drive root, "" for the root
     */
    @Nullable
    static Saved save(@NonNull Context context, @NonNull String fileName, @NonNull String folderPath, @NonNull File file) {
        final String name = safeName(fileName);
        final String folder = safeFolderPath(folderPath);
        final Content source;
        try {
            source = Content.of(file, name);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read " + name, e);
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                final Saved saved = saveToMediaStore(context, name, folder, source);
                if (saved != null) return saved;
            } else if (canWriteSharedStorage(context)) {
                final File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        ROOT_FOLDER + (folder.isEmpty() ? "" : "/" + folder));
                final File target = copyToDirectory(source, dir, name);
                MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()},
                        new String[]{AudioFormats.mimeType(name)}, null);
                return new Saved(target.getAbsolutePath(), null);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not save " + name + " to the shared Music folder", e);
        }
        // Fallback: the app's own storage
        try {
            File base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (base == null) base = new File(context.getFilesDir(), "music");
            final File dir = new File(base, ROOT_FOLDER + (folder.isEmpty() ? "" : "/" + folder));
            return new Saved(copyToDirectory(source, dir, name).getAbsolutePath(), null);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not save " + name + " for offline use", e);
            return null;
        }
    }

    /**
     * What is saved: the downloaded file as it is, or for an Ogg recording with video in it, its
     * audio stream alone (OggAudioOnly; the sound is untouched). That copy is a plain audio file
     * Android indexes and plays, and it leaves the video's space behind (217 of 240 MB for a
     * one-hour screen recording).
     */
    private static final class Content {
        @NonNull private final File file;
        @Nullable private final OggAudioOnly audioOnly;
        final long size;

        private Content(@NonNull File file, @Nullable OggAudioOnly audioOnly) {
            this.file = file;
            this.audioOnly = audioOnly;
            this.size = audioOnly != null ? audioOnly.size : file.length();
        }

        @NonNull
        static Content of(@NonNull File file, @NonNull String name) throws IOException {
            if (MultiStreamOgg.isOggName(name)) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    final byte[] head = new byte[(int) Math.min(raf.length(), 64 * 1024)];
                    raf.readFully(head);
                    if (OggAudioOnly.isMultiplexed(head, head.length)) {
                        final OggAudioOnly view = OggAudioOnly.scan(MultiStreamOgg.channelSource(raf.getChannel()), raf.length());
                        if (view != null) return new Content(file, view);
                    }
                }
            }
            return new Content(file, null);
        }

        void writeTo(@NonNull OutputStream out) throws IOException {
            if (audioOnly == null) {
                try (InputStream in = new FileInputStream(file)) {
                    copy(in, out);
                }
            } else {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    audioOnly.copyTo(MultiStreamOgg.channelSource(raf.getChannel()), out);
                }
            }
        }
    }

    private static boolean canWriteSharedStorage(@NonNull Context context) {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Nullable
    private static Saved saveToMediaStore(@NonNull Context context, @NonNull String name,
                                          @NonNull String folder, @NonNull Content source) throws IOException {
        final String mime = AudioFormats.mimeType(name);
        if (mime == null) return null; // MediaStore would refuse it in the audio collection

        final ContentResolver resolver = context.getContentResolver();
        final Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final String relativePath = Environment.DIRECTORY_MUSIC + "/" + ROOT_FOLDER
                + (folder.isEmpty() ? "" : "/" + folder) + "/";

        // An earlier copy of the same file (same folder, name and size): reuse it, no duplicate
        try (Cursor c = resolver.query(collection,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{relativePath, name}, null)) {
            if (c != null && c.moveToFirst() && c.getLong(1) == source.size) {
                final Uri existing = ContentUris.withAppendedId(collection, c.getLong(0));
                final String path = c.getString(2);
                return new Saved(path != null ? path : existing.toString(), existing.toString());
            }
        }

        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        final Uri uri = resolver.insert(collection, values);
        if (uri == null) return null;
        try {
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("MediaStore gave no output stream");
                final java.io.BufferedOutputStream buffered = new java.io.BufferedOutputStream(out, COPY_BUFFER);
                source.writeTo(buffered);
                buffered.flush();
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null);
            throw e;
        }

        String path = null;
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) path = c.getString(0);
        }
        return new Saved(path != null ? path : uri.toString(), uri.toString());
    }

    /** Copies into {@code dir}, reusing an identical earlier copy and numbering a clashing name. */
    @NonNull
    private static File copyToDirectory(@NonNull Content source, @NonNull File dir, @NonNull String name) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File target = new File(dir, name);
        for (int n = 2; target.exists(); n++) {
            if (target.length() == source.size) return target; // earlier copy of this file
            target = new File(dir, numbered(name, n));
        }
        final File partial = new File(dir, "." + target.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(partial)) {
            final java.io.BufferedOutputStream buffered = new java.io.BufferedOutputStream(out, COPY_BUFFER);
            source.writeTo(buffered);
            buffered.flush();
            out.getFD().sync();
        }
        if (!partial.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("Cannot rename into " + target);
        }
        return target;
    }

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        final byte[] buffer = new byte[COPY_BUFFER];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    /** {@code song.mp3} becomes {@code song (2).mp3}. */
    @NonNull
    static String numbered(@NonNull String name, int n) {
        final int dot = name.lastIndexOf('.');
        return dot > 0
                ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot)
                : name + " (" + n + ")";
    }

    /** One file name, without characters that shared storage rejects. */
    @NonNull
    static String safeName(@NonNull String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (safe.startsWith(".")) safe = safe.substring(1);
        return safe.isEmpty() ? "track" : safe;
    }

    /** A folder path below the drive root ("A/B"), each segment made safe; "" for the root. */
    @NonNull
    static String safeFolderPath(@NonNull String folderPath) {
        final StringBuilder sb = new StringBuilder();
        for (final String segment : folderPath.split("/")) {
            String safe = segment.replaceAll("[\\\\:*?\"<>|\\p{Cntrl}]", "_").trim();
            while (safe.startsWith(".")) safe = safe.substring(1);
            while (safe.endsWith(".")) safe = safe.substring(0, safe.length() - 1);
            if (safe.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(safe);
        }
        return sb.toString();
    }
}
