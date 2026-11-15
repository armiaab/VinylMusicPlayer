package com.poupa.vinylmusicplayer.glide.audiocover;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.poupa.vinylmusicplayer.BuildConfig;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.util.PreferenceUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A random Unsplash photo for songs that have no artwork of their own (no embedded cover, no
 * album art, no cover.jpg in their folder), through the official Unsplash API
 * (https://unsplash.com/documentation, see docs/RANDOM_ARTWORK.md).
 *
 * <ul>
 *   <li>Fetched only for the song being played, and only as the image download setting allows
 *       (Wi-Fi only by default). Nothing about the song is sent: the request only asks for a
 *       squarish "music" photo.</li>
 *   <li>Kept on the device per song, so a song keeps its photo, also offline and everywhere it
 *       is shown. A new photo costs two API requests (the photo, and the download report the
 *       API guidelines ask for).</li>
 *   <li>Failures back off (per song, and for Unsplash as a whole when it refuses, e.g. the
 *       hourly limit) so that the many cover requests of the playing song (player,
 *       notification, lock screen, widgets) never pile up on the network.</li>
 * </ul>
 */
public final class RandomArtwork {

    private static final String TAG = "RandomArtwork";

    /** Square photos; large enough for the now-playing screen, small to download (~100 KB). */
    private static final int SIZE_PX = 720;

    private static final String RANDOM_PHOTO =
            "https://api.unsplash.com/photos/random?query=music&orientation=squarish&content_filter=high";

    private static final String DIR = "random_artwork";
    /** Oldest photos are dropped beyond this many (about 40 MB). */
    private static final int MAX_FILES = 400;
    private static final int MAX_BYTES = 4 << 20;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    /** After a failed attempt for a song, the song is not tried again for this long. */
    private static final long SONG_RETRY_MS = 10 * 60 * 1000L;
    /** After Unsplash refuses (rate limit, bad key) or is unreachable, wait this long. */
    private static final long SERVICE_RETRY_MS = 15 * 60 * 1000L;

    /** Id of the song being played; only it may cause a download. */
    private static volatile long playingSongId = Long.MIN_VALUE;

    // Guarded by RandomArtwork.class
    private static final Map<String, Long> songFailedAt = new HashMap<>();
    private static long serviceFailedAt = -SERVICE_RETRY_MS;

    private RandomArtwork() {}

    /** Called by the music service whenever the current song changes. */
    public static void setPlayingSong(@NonNull Song song) {
        playingSongId = song.id;
    }

    /** Whether this build can fetch photos (it carries an Unsplash access key). */
    public static boolean isAvailable() {
        return !BuildConfig.UNSPLASH_ACCESS_KEY.isEmpty();
    }

    /**
     * The random artwork of a song: from the device when it was fetched before, otherwise
     * downloaded now if the song is the one playing. Blocks (Glide's loader thread).
     *
     * @return {@code null} when off, not allowed, or unavailable
     */
    @Nullable
    static InputStream load(@NonNull Context context, @NonNull Song song) {
        if (song.id == Song.EMPTY_SONG.id || !PreferenceUtil.getInstance().randomArtwork()) return null;
        final String key = key(song);
        final File file = new File(new File(context.getFilesDir(), DIR), key + ".jpg");
        try {
            if (isUsable(file)) return new FileInputStream(file);
            if (!isAvailable() || song.id != playingSongId
                    || !PreferenceUtil.isAllowedToDownloadMetadata(context)) return null;
            synchronized (RandomArtwork.class) {
                // One download at a time; later callers find the file, or the recent failure
                if (!isUsable(file)) {
                    final long now = SystemClock.elapsedRealtime();
                    final Long failedAt = songFailedAt.get(key);
                    if (failedAt != null && now - failedAt < SONG_RETRY_MS) return null;
                    if (now - serviceFailedAt < SERVICE_RETRY_MS) return null;
                    try {
                        fetch(file);
                    } catch (IOException | RuntimeException e) {
                        Log.w(TAG, "Unsplash: " + e);
                        songFailedAt.put(key, SystemClock.elapsedRealtime());
                        if (e instanceof ServiceRefusedException || e instanceof java.net.UnknownHostException
                                || e instanceof java.net.SocketTimeoutException) {
                            serviceFailedAt = SystemClock.elapsedRealtime();
                        }
                        return null;
                    }
                    songFailedAt.remove(key);
                    trim(file.getParentFile());
                }
            }
            return new FileInputStream(file);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "No random artwork: " + e);
            return null;
        }
    }

    private static boolean isUsable(@NonNull File file) {
        return file.isFile() && file.length() > 0;
    }

    /** Unsplash said no (rate limit, invalid key): back off for everyone. */
    private static final class ServiceRefusedException extends IOException {
        ServiceRefusedException(String message) {
            super(message);
        }
    }

    /**
     * A random squarish music photo, hotlinked from Unsplash's image CDN at the size needed,
     * then reported as downloaded as the API guidelines ask.
     */
    private static void fetch(@NonNull File target) throws IOException {
        final String auth = "Client-ID " + BuildConfig.UNSPLASH_ACCESS_KEY;
        final JsonObject photo = JsonParser.parseString(readText(RANDOM_PHOTO, auth)).getAsJsonObject();
        final JsonObject urls = photo.getAsJsonObject("urls");
        final String raw = urls != null ? string(urls, "raw") : null;
        if (raw == null) throw new IOException("No photo in the response");
        final String sized = raw + (raw.contains("?") ? "&" : "?")
                + "w=" + SIZE_PX + "&h=" + SIZE_PX + "&fit=crop&crop=entropy&fm=jpg&q=80";
        download(sized, target);

        final JsonObject links = photo.getAsJsonObject("links");
        final String downloadLocation = links != null ? string(links, "download_location") : null;
        if (downloadLocation != null) {
            try {
                readText(downloadLocation, auth); // credits the use to the photographer
            } catch (IOException e) {
                Log.w(TAG, "Unsplash download report failed: " + e);
            }
        }
    }

    @Nullable
    private static String string(@NonNull JsonObject obj, @NonNull String member) {
        final JsonElement el = obj.get(member);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /** Stable per song, and independent of where the file is (OneDrive or its offline copy). */
    @NonNull
    static String key(@NonNull Song song) {
        final String identity = (song.title + '\u0000' + song.albumName + '\u0000' + song.artistNames)
                .toLowerCase(Locale.ROOT);
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    private static HttpURLConnection open(@NonNull String url, @Nullable String authorization) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "VinylMusicPlayer/" + BuildConfig.VERSION_NAME);
        if (authorization != null) {
            conn.setRequestProperty("Authorization", authorization);
            conn.setRequestProperty("Accept-Version", "v1");
        }
        return conn;
    }

    @NonNull
    private static String readText(@NonNull String url, @NonNull String authorization) throws IOException {
        final HttpURLConnection conn = open(url, authorization);
        try {
            final int code = conn.getResponseCode();
            if (code == 401 || code == 403 || code == 429) throw new ServiceRefusedException("HTTP " + code);
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buffer = new byte[8 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < MAX_BYTES) out.write(buffer, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Downloads an image from Unsplash's CDN (no authorization: it must not get the key). */
    private static void download(@NonNull String url, @NonNull File target) throws IOException {
        final File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        final HttpURLConnection conn = open(url, null);
        conn.setRequestProperty("Accept", "image/jpeg,image/*");
        final File partial = new File(dir, target.getName() + ".part");
        try {
            final int code = conn.getResponseCode();
            final String type = conn.getContentType();
            if (code != 200 || type == null || !type.startsWith("image/")) {
                throw new IOException("Unexpected response " + code + " " + type);
            }
            int total = 0;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                final byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("Image too large");
                    out.write(buffer, 0, n);
                }
            }
            if (total == 0 || !partial.renameTo(target)) throw new IOException("Could not store the image");
        } finally {
            conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
        }
    }

    /** Keeps the newest {@link #MAX_FILES} photos. */
    private static void trim(@Nullable File dir) {
        final File[] files = dir != null ? dir.listFiles((d, name) -> name.endsWith(".jpg")) : null;
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
