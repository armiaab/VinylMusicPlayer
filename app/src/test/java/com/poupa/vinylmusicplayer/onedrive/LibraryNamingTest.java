package com.poupa.vinylmusicplayer.onedrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class LibraryNamingTest {

    private static Map<String, LibraryNaming.Folder> tree() {
        final Map<String, LibraryNaming.Folder> folders = new HashMap<>();
        for (final LibraryNaming.Folder f : new LibraryNaming.Folder[]{
                new LibraryNaming.Folder("active", "Active", "root"),
                new LibraryNaming.Folder("generals", "Generals", "active"),
                new LibraryNaming.Folder("recording", "Recording", "generals"),
                new LibraryNaming.Folder("orphan", "Lost", "deleted-parent"),
        }) {
            folders.put(f.id, f);
        }
        return folders;
    }

    @Test
    public void folderPathsAreBuiltFromTheTree() {
        final LibraryNaming.PathResolver paths = new LibraryNaming.PathResolver(tree(), "root");
        assertEquals("", paths.path("root"));
        assertEquals("Active", paths.path("active"));
        assertEquals("Active/Generals/Recording", paths.path("recording"));
        assertEquals("Active/Generals/Recording", paths.path("recording")); // memoized
    }

    @Test
    public void foldersWithoutAPathToTheRootHaveNoPath() {
        final LibraryNaming.PathResolver paths = new LibraryNaming.PathResolver(tree(), "root");
        assertNull(paths.path("orphan"));
        assertNull(paths.path("unknown"));
        assertNull(paths.path(null));
    }

    @Test
    public void untaggedSongsTakeTheirFolderAsAlbumLikeMediaStore() {
        assertEquals("Recording", LibraryNaming.album("", "Active/Generals/Recording"));
        assertEquals("Active", LibraryNaming.album("  ", "Active"));
        assertEquals(LibraryNaming.ROOT_ALBUM, LibraryNaming.album("", ""));
        assertEquals("Abbey Road", LibraryNaming.album("Abbey Road", "Music/Beatles"));
    }

    @Test
    public void missingArtistIsMediaStoresUnknown() {
        assertEquals("<unknown>", LibraryNaming.artist(""));
        assertEquals("The Beatles", LibraryNaming.artist(" The Beatles "));
    }

    @Test
    public void albumIdsAreStableNegativeAndPerAlbum() {
        final long a = LibraryNaming.albumId("Recording", "<unknown>");
        assertEquals(a, LibraryNaming.albumId("recording", "<UNKNOWN>"));
        assertNotEquals(a, LibraryNaming.albumId("Recording", "Someone"));
        assertTrue(a < -1);
    }
}
