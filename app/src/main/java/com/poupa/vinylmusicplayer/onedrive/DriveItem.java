package com.poupa.vinylmusicplayer.onedrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** A file or folder listed from OneDrive, with the audio metadata Graph extracted for it. */
public final class DriveItem {
    @NonNull
    public final String id;
    @NonNull
    public final String name;
    public final boolean isFolder;
    /** Number of children of a folder; 0 for files. */
    public final int childCount;
    public final long size;
    /** Changes whenever the file content changes. */
    @Nullable
    public final String cTag;
    public final long lastModifiedMillis;
    /** Id of the containing folder, when the listing reported it (library scans do). */
    @Nullable
    public final String parentId;

    // Audio facet: empty / 0 when Graph has no metadata (common for OneDrive for Business)
    @NonNull
    public final String title;
    @NonNull
    public final String artist;
    @NonNull
    public final String albumArtist;
    @NonNull
    public final String album;
    @NonNull
    public final String genre;
    public final int track;
    public final int disc;
    public final int year;
    /** From Graph; the browser fills it in from the library when Graph has none. */
    public long durationMs;

    /** Set by the browser: whether a complete offline copy exists on this device. */
    public boolean offline;

    DriveItem(@NonNull String id, @NonNull String name, boolean isFolder, int childCount, long size,
              @Nullable String cTag, long lastModifiedMillis, @Nullable String parentId,
              @NonNull String title, @NonNull String artist, @NonNull String albumArtist,
              @NonNull String album, @NonNull String genre, int track, int disc, int year, long durationMs) {
        this.id = id;
        this.name = name;
        this.isFolder = isFolder;
        this.childCount = childCount;
        this.size = size;
        this.cTag = cTag;
        this.lastModifiedMillis = lastModifiedMillis;
        this.parentId = parentId;
        this.title = title;
        this.artist = artist;
        this.albumArtist = albumArtist;
        this.album = album;
        this.genre = genre;
        this.track = track;
        this.disc = disc;
        this.year = year;
        this.durationMs = durationMs;
    }

    public boolean isPlayable() {
        return !isFolder && AudioFormats.isPlayable(name);
    }

    /** Parses one entry of a Graph {@code children} response; {@code null} when unusable. */
    @Nullable
    static DriveItem fromJson(@NonNull JsonObject obj) {
        final String id = Http.string(obj, "id");
        final String name = Http.string(obj, "name");
        if (id == null || name == null) return null;

        final JsonElement folderEl = obj.get("folder");
        final boolean isFolder = folderEl != null && folderEl.isJsonObject();
        final int childCount = isFolder ? (int) Http.number(folderEl.getAsJsonObject(), "childCount", 0) : 0;

        final JsonElement audioEl = obj.get("audio");
        final JsonObject audio = audioEl != null && audioEl.isJsonObject() ? audioEl.getAsJsonObject() : null;
        final JsonElement parentEl = obj.get("parentReference");
        final String parentId = parentEl != null && parentEl.isJsonObject()
                ? Http.string(parentEl.getAsJsonObject(), "id") : null;
        // OneDrive files some recordings (M4A with a video track...) as video: same duration field
        long durationMs = Http.number(audio, "duration", 0);
        if (durationMs <= 0) {
            final JsonElement videoEl = obj.get("video");
            if (videoEl != null && videoEl.isJsonObject()) {
                durationMs = Http.number(videoEl.getAsJsonObject(), "duration", 0);
            }
        }

        return new DriveItem(
                id,
                name,
                isFolder,
                childCount,
                Http.number(obj, "size", 0),
                Http.string(obj, "cTag"),
                parseIsoDate(Http.string(obj, "lastModifiedDateTime")),
                parentId,
                nonNull(Http.string(audio, "title")),
                nonNull(Http.string(audio, "artist")),
                nonNull(Http.string(audio, "albumArtist")),
                nonNull(Http.string(audio, "album")),
                nonNull(Http.string(audio, "genre")),
                (int) Http.number(audio, "track", 0),
                (int) Http.number(audio, "disc", 0),
                (int) Http.number(audio, "year", 0),
                durationMs);
    }

    @NonNull
    private static String nonNull(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    /** Graph timestamps look like {@code 2024-03-01T10:15:30.123Z} (fraction optional). */
    static long parseIsoDate(@Nullable String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(iso.substring(0, 19)).getTime();
        } catch (ParseException | NullPointerException e) {
            return 0;
        }
    }
}
