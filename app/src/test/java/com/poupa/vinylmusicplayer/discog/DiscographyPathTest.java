package com.poupa.vinylmusicplayer.discog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiscographyPathTest {

    @Test
    public void filesInsideTheFolderMatch() {
        assertTrue(Discography.isInside("/storage/music/a.mp3", "/storage/music"));
        assertTrue(Discography.isInside("/storage/music/sub/a.mp3", "/storage/music"));
        assertTrue(Discography.isInside("/storage/music/a.mp3", "/storage/music/"));
    }

    @Test
    public void theFolderOrFileItselfMatches() {
        assertTrue(Discography.isInside("/storage/music", "/storage/music"));
        assertTrue(Discography.isInside("/storage/music/a.mp3", "/storage/music/a.mp3"));
    }

    @Test
    public void siblingsSharingThePrefixDoNotMatch() {
        assertFalse(Discography.isInside("/storage/music-old/a.mp3", "/storage/music"));
        assertFalse(Discography.isInside("/storage/music/podcasts-old/a.mp3", "/storage/music/podcasts"));
        assertFalse(Discography.isInside("/storage/other/a.mp3", "/storage/music"));
    }
}
