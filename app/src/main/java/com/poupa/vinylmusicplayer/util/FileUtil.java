package com.poupa.vinylmusicplayer.util;

import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.poupa.vinylmusicplayer.discog.Discography;
import com.poupa.vinylmusicplayer.model.Song;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public final class FileUtil {
    private FileUtil() {
    }

    @NonNull
    public static ArrayList<Song> matchFilesWithMediaStore(@NonNull List<File> files) {
        ArrayList<Song> songs = new ArrayList<>();
        for (File file : files) {
            String path = safeGetCanonicalPath(file);
            Song song = Discography.getInstance().getSongByPath(path);
            if (!song.equals(Song.EMPTY_SONG)) {
                songs.add(song);
            }
        }
        return songs;
    }

    /**
     * The library songs that {@code matchFilesWithMediaStore(listFilesDeep(roots, fileFilter))}
     * finds, in {@code order}, without walking the folders.
     *
     * Only songs in the library can be played, and the library already knows their paths. So
     * instead of visiting every entry below the roots (one file system call each, slow on big
     * trees and through Android's storage layer), this picks the songs located below the roots
     * and checks only them and their folders: each folder on the way must pass the filter (as the
     * walk requires), and each song must still be a file.
     */
    @NonNull
    public static ArrayList<Song> listSongsDeep(@NonNull Collection<File> roots, @NonNull FileFilter fileFilter,
                                                @NonNull Comparator<File> order) {
        final Discography discography = Discography.getInstance();
        final List<Song> library = discography.getAllSongs(null);
        final Map<String, Boolean> folderAccepted = new HashMap<>();
        final Set<String> foundPaths = new HashSet<>();
        final Map<File, Song> songOfFile = new IdentityHashMap<>();
        final List<File> files = new ArrayList<>();

        for (final File root : roots) {
            final String rootPath = safeGetCanonicalPath(root);
            if (!root.isDirectory()) {
                // A file selected on its own: the walk keeps it when the filter accepts it
                if (fileFilter.accept(root) && foundPaths.add(rootPath)) {
                    final Song song = discography.getSongByPath(rootPath);
                    if (!song.equals(Song.EMPTY_SONG)) {
                        final File file = new File(rootPath);
                        songOfFile.put(file, song);
                        files.add(file);
                    }
                }
                continue;
            }
            final String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
            for (final Song song : library) {
                final String path = song.data;
                if (!path.startsWith(prefix) || foundPaths.contains(path)) continue;
                if (!foldersPass(prefix, path, fileFilter, folderAccepted)) continue;
                final File file = new File(path);
                if (!fileFilter.accept(file) || !file.isFile()) continue;
                foundPaths.add(path);
                songOfFile.put(file, song);
                files.add(file);
            }
        }

        Collections.sort(files, order);
        final ArrayList<Song> songs = new ArrayList<>(files.size());
        for (final File file : files) {
            songs.add(songOfFile.get(file));
        }
        return songs;
    }

    /** Whether every folder between {@code prefix} and the file at {@code path} passes the filter. */
    private static boolean foldersPass(@NonNull String prefix, @NonNull String path, @NonNull FileFilter filter,
                                       @NonNull Map<String, Boolean> cache) {
        int from = prefix.length();
        int separator;
        while ((separator = path.indexOf(File.separatorChar, from)) >= 0) {
            final String folder = path.substring(0, separator);
            Boolean accepted = cache.get(folder);
            if (accepted == null) {
                accepted = filter.accept(new File(folder));
                cache.put(folder, accepted);
            }
            if (!accepted) return false;
            from = separator + 1;
        }
        return true;
    }

    @NonNull
    public static List<File> listFiles(@NonNull File directory, @Nullable FileFilter fileFilter) {
        List<File> fileList = new LinkedList<>();
        File[] found = directory.listFiles(fileFilter);
        if (found != null) {
            Collections.addAll(fileList, found);
        }
        return fileList;
    }

    @NonNull
    public static List<File> listFilesDeep(@NonNull File directory, @Nullable FileFilter fileFilter) {
        List<File> files = new LinkedList<>();
        internalListFilesDeep(files, directory, fileFilter);
        return files;
    }

    @NonNull
    public static List<File> listFilesDeep(@NonNull Collection<File> files, @Nullable FileFilter fileFilter) {
        List<File> resFiles = new LinkedList<>();
        for (File file : files) {
            if (file.isDirectory()) {
                internalListFilesDeep(resFiles, file, fileFilter);
            } else if (fileFilter == null || fileFilter.accept(file)) {
                resFiles.add(file);
            }
        }
        return resFiles;
    }

    private static void internalListFilesDeep(@NonNull Collection<File> files, @NonNull File directory, @Nullable FileFilter fileFilter) {
        File[] found = directory.listFiles(fileFilter);

        if (found != null) {
            for (File file : found) {
                if (file.isDirectory()) {
                    internalListFilesDeep(files, file, fileFilter);
                } else {
                    files.add(file);
                }
            }
        }
    }

    public static boolean fileIsMimeType(File file, String mimeType, MimeTypeMap mimeTypeMap) {
        if (mimeType == null || mimeType.equals("*/*")) {
            return true;
        } else {
            // get the file mime type
            // Dont use MimeTypeMap.getFileExtensionFromUrl since it fails to process URI with non-Latin characters
            Function<File, String> getFileExtension = (theFile) -> {
                final String uri = theFile.toURI().toString();
                int dotPos = uri.lastIndexOf('.');
                if (dotPos < 0) {return "";}
                return uri.substring(dotPos + 1).toLowerCase();
            };
            final String fileExtension = getFileExtension.apply(file);
            final String fileType = mimeTypeMap.getMimeTypeFromExtension(fileExtension);
            if (fileType == null) {
                return false;
            }
            // check the 'type/subtype' pattern
            if (fileType.equals(mimeType)) {
                return true;
            }
            // check the 'type/*' pattern
            int mimeTypeDelimiter = mimeType.lastIndexOf('/');
            if (mimeTypeDelimiter == -1) {
                return false;
            }
            String mimeTypeMainType = mimeType.substring(0, mimeTypeDelimiter);
            String mimeTypeSubtype = mimeType.substring(mimeTypeDelimiter + 1);
            if (!mimeTypeSubtype.equals("*")) {
                return false;
            }
            int fileTypeDelimiter = fileType.lastIndexOf('/');
            if (fileTypeDelimiter == -1) {
                return false;
            }
            String fileTypeMainType = fileType.substring(0, fileTypeDelimiter);
            return fileTypeMainType.equals(mimeTypeMainType);
        }
    }

    public static String stripExtension(String str) {
        if (str == null) return null;
        int pos = str.lastIndexOf('.');
        if (pos == -1) return str;
        return str.substring(0, pos);
    }

    public static String readFromStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    public static String read(File file) throws Exception {
        FileInputStream fin = new FileInputStream(file);
        String ret = readFromStream(fin);
        fin.close();
        return ret;
    }

    public static String safeGetCanonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            e.printStackTrace();
            return file.getAbsolutePath();
        }
    }

    public static File safeGetCanonicalFile(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            e.printStackTrace();
            return file.getAbsoluteFile();
        }
    }

    public static byte[] readBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            baos.write(buffer, 0, count);
        }
        stream.close();
        return baos.toByteArray();
    }
}
