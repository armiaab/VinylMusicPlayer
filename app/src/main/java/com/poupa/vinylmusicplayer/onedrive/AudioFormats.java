package com.poupa.vinylmusicplayer.onedrive;

import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Audio file formats that Android's platform decoders play on every supported version. Lossless
 * formats (FLAC, WAV) are decoded bit-exact; nothing here is transcoded.
 *
 * Detection goes by file extension, so it also works where the platform MIME table lacks an
 * entry (for example .opus or .ogg on older Android versions).
 */
public final class AudioFormats {

    /** Extension to MIME type, used when the platform does not know the extension. */
    private static final Map<String, String> FALLBACK_MIME = new HashMap<>();

    static {
        FALLBACK_MIME.put("mp3", "audio/mpeg");
        FALLBACK_MIME.put("m4a", "audio/mp4");
        FALLBACK_MIME.put("m4b", "audio/mp4");
        FALLBACK_MIME.put("aac", "audio/aac");
        FALLBACK_MIME.put("ogg", "audio/ogg");
        FALLBACK_MIME.put("oga", "audio/ogg");
        FALLBACK_MIME.put("opus", "audio/ogg");
        FALLBACK_MIME.put("flac", "audio/flac");
        FALLBACK_MIME.put("wav", "audio/x-wav");
        FALLBACK_MIME.put("mka", "audio/x-matroska");
        FALLBACK_MIME.put("3gp", "audio/3gpp");
        FALLBACK_MIME.put("3ga", "audio/3gpp");
        FALLBACK_MIME.put("amr", "audio/amr");
        FALLBACK_MIME.put("awb", "audio/amr-wb");
        FALLBACK_MIME.put("mid", "audio/midi");
        FALLBACK_MIME.put("midi", "audio/midi");
    }

    private AudioFormats() {}

    /** Lower-case extension of {@code fileName} without the dot, or "" when there is none. */
    @NonNull
    public static String extension(@Nullable String fileName) {
        if (fileName == null) return "";
        final int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        final int dot = fileName.lastIndexOf('.');
        if (dot <= slash + 1 || dot == fileName.length() - 1) return ""; // no dot, or ".hidden"
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Whether a file of this name can be played by the platform decoders. */
    public static boolean isPlayable(@Nullable String fileName) {
        return FALLBACK_MIME.containsKey(extension(fileName));
    }

    /**
     * Whether a file name looks like audio: a {@link #isPlayable playable} extension, or one the
     * platform MIME table maps to audio (e.g. ringtone formats). Decided from the name alone,
     * without touching the file system.
     */
    public static boolean isAudioFileName(@Nullable String fileName) {
        final String ext = extension(fileName);
        if (ext.isEmpty()) return false;
        if (FALLBACK_MIME.containsKey(ext)) return true;
        String mime = null;
        try {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        } catch (RuntimeException ignored) {
            // not available in JVM unit tests
        }
        return mime != null
                && (mime.startsWith("audio/") || mime.equals("application/ogg") || mime.equals("application/opus"));
    }

    /** Whether the format is lossless (decoded bit-exact). */
    public static boolean isLossless(@Nullable String fileName) {
        final String ext = extension(fileName);
        return ext.equals("flac") || ext.equals("wav");
    }

    /** Short format label for display, e.g. "FLAC". */
    @NonNull
    public static String label(@Nullable String fileName) {
        return extension(fileName).toUpperCase(Locale.ROOT);
    }

    /**
     * MIME type of a playable file: the platform's own mapping when it has one (MediaStore checks
     * extensions against it), otherwise the built-in one. {@code null} for unsupported files.
     */
    @Nullable
    public static String mimeType(@Nullable String fileName) {
        final String ext = extension(fileName);
        final String fallback = FALLBACK_MIME.get(ext);
        if (fallback == null) return null;
        String platform = null;
        try {
            platform = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        } catch (RuntimeException ignored) {
            // not available in JVM unit tests
        }
        return platform != null ? platform : fallback;
    }

    /** Whether the format may keep data the decoder reads first at the end of the file. */
    static boolean isMp4Family(@NonNull String ext) {
        return ext.equals("m4a") || ext.equals("m4b") || ext.equals("3gp") || ext.equals("3ga");
    }
}
