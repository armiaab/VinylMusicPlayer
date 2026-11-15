package com.poupa.vinylmusicplayer.onedrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How OneDrive files are named in the library, following MediaStore's conventions for local
 * files so both kinds group the same way. Pure Java, unit-tested.
 */
final class LibraryNaming {

    /** MediaStore's value for a missing artist; shown as "Unknown artist". */
    static final String UNKNOWN_ARTIST = "<unknown>";

    /** Album of files in the drive root without an album tag. */
    static final String ROOT_ALBUM = "OneDrive";

    private LibraryNaming() {}

    /** A folder of the drive: its name and parent ({@code null} for the root). */
    static final class Folder {
        @NonNull final String id;
        @NonNull final String name;
        @Nullable final String parentId;

        Folder(@NonNull String id, @NonNull String name, @Nullable String parentId) {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
        }
    }

    /**
     * Resolves folder paths below the drive root ("Music/Albums/X", "" for the root) from the
     * folder tree, memoizing them. A folder whose chain does not reach the root (its ancestor was
     * deleted, or the tree is incomplete) has no path.
     */
    static final class PathResolver {
        private final Map<String, Folder> folders;
        @Nullable private final String rootId;
        private final Map<String, String> cache = new HashMap<>();

        PathResolver(@NonNull Map<String, Folder> folders, @Nullable String rootId) {
            this.folders = folders;
            this.rootId = rootId;
        }

        /** The path of a folder, or {@code null} when it is not reachable from the root. */
        @Nullable
        String path(@Nullable String folderId) {
            if (folderId == null) return null;
            if (folderId.equals(rootId)) return "";
            final String cached = cache.get(folderId);
            if (cached != null) return cached;

            // Walk up iteratively (deep trees), then fill the cache on the way back
            final java.util.ArrayList<Folder> chain = new java.util.ArrayList<>();
            String current = folderId;
            String base = null;
            for (int guard = 0; guard < 10_000; guard++) {
                if (current == null) return null;
                if (current.equals(rootId)) {
                    base = "";
                    break;
                }
                final String known = cache.get(current);
                if (known != null) {
                    base = known;
                    break;
                }
                final Folder folder = folders.get(current);
                if (folder == null) return null; // unknown or deleted ancestor
                chain.add(folder);
                current = folder.parentId;
            }
            if (base == null) return null; // cycle guard
            String path = base;
            for (int i = chain.size() - 1; i >= 0; i--) {
                final Folder folder = chain.get(i);
                path = path.isEmpty() ? folder.name : path + "/" + folder.name;
                cache.put(folder.id, path);
            }
            return path;
        }
    }

    /** The album tag, or the containing folder's name as MediaStore does for untagged files. */
    @NonNull
    static String album(@NonNull String albumTag, @NonNull String folderPath) {
        if (!albumTag.trim().isEmpty()) return albumTag.trim();
        if (folderPath.isEmpty()) return ROOT_ALBUM;
        final int slash = folderPath.lastIndexOf('/');
        return slash >= 0 ? folderPath.substring(slash + 1) : folderPath;
    }

    /** The artist tag, or MediaStore's unknown artist. */
    @NonNull
    static String artist(@NonNull String artistTag) {
        return artistTag.trim().isEmpty() ? UNKNOWN_ARTIST : artistTag.trim();
    }

    /**
     * A stable album id for an album name and artist: negative (MediaStore ids are positive)
     * and never -1 (the empty album). Songs of one album get the same id.
     */
    static long albumId(@NonNull String album, @NonNull String artist) {
        final String key = album.toLowerCase(Locale.ROOT) + '\u0000' + artist.toLowerCase(Locale.ROOT);
        long hash = 0xcbf29ce484222325L; // FNV-1a, 64 bit
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return -2 - (hash & 0x3FFFFFFFFFFFFFFFL);
    }
}
