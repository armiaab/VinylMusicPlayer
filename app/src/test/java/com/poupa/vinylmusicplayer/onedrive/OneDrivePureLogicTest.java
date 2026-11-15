package com.poupa.vinylmusicplayer.onedrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Format detection, token parsing, redirect URIs and offline file names. */
public class OneDrivePureLogicTest {

    // --- AudioFormats ----------------------------------------------------------------------

    @Test
    public void extensionIsLowerCaseAndIgnoresHiddenFiles() {
        assertEquals("flac", AudioFormats.extension("01 Song.FLAC"));
        assertEquals("", AudioFormats.extension(".hidden"));
        assertEquals("", AudioFormats.extension("noext"));
        assertEquals("", AudioFormats.extension("dir.d/file"));
        assertEquals("", AudioFormats.extension("trailing."));
    }

    @Test
    public void commonFormatsArePlayableAndUnsupportedOnesAreNot() {
        for (final String name : new String[]{"a.mp3", "a.m4a", "a.aac", "a.ogg", "a.oga", "a.opus",
                "a.flac", "a.wav", "a.mka", "a.3gp", "a.amr", "a.mid"}) {
            assertTrue(name, AudioFormats.isPlayable(name));
        }
        for (final String name : new String[]{"a.wma", "a.ape", "a.aiff", "a.txt", "a.jpg", "a"}) {
            assertFalse(name, AudioFormats.isPlayable(name));
        }
    }

    @Test
    public void audioIsRecognisedFromTheNameAlone() {
        // The Folders view decides from the name, before any file system call
        assertTrue(AudioFormats.isAudioFileName("lecture.WAV"));
        assertTrue(AudioFormats.isAudioFileName("talk.opus"));
        assertTrue(AudioFormats.isAudioFileName("clip.m4a"));
        assertFalse(AudioFormats.isAudioFileName("data.csv"));
        assertFalse(AudioFormats.isAudioFileName("Makefile"));
        assertFalse(AudioFormats.isAudioFileName("Album 1.5"));
    }

    @Test
    public void mimeTypeFallsBackToTheBuiltInTable() {
        // MimeTypeMap is not available in JVM tests: the built-in table answers
        assertEquals("audio/ogg", AudioFormats.mimeType("x.opus"));
        assertEquals("audio/flac", AudioFormats.mimeType("x.flac"));
        assertNull(AudioFormats.mimeType("x.wma"));
    }

    @Test
    public void losslessFormats() {
        assertTrue(AudioFormats.isLossless("x.flac"));
        assertTrue(AudioFormats.isLossless("x.WAV"));
        assertFalse(AudioFormats.isLossless("x.mp3"));
    }

    // --- OAuthTokens -----------------------------------------------------------------------

    @Test
    public void tokenResponseIsParsed() throws Exception {
        final OAuthTokens t = OAuthTokens.fromTokenResponse(
                "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600}", null, 1000);
        assertEquals("a", t.accessToken);
        assertEquals("r", t.refreshToken);
        assertEquals(1000 + 3600_000, t.expiresAtMillis);
        assertTrue(t.expiresWithin(1000 + 3600_000 - 10, 60_000));
        assertFalse(t.expiresWithin(1000, 60_000));
    }

    @Test
    public void refreshKeepsTheRefreshTokenAndAccountWhenNotReissued() throws Exception {
        final OAuthTokens previous = new OAuthTokens("old", "refresh", 0, "me@example.com");
        final OAuthTokens t = OAuthTokens.fromTokenResponse("{\"access_token\":\"new\"}", previous, 0);
        assertEquals("refresh", t.refreshToken);
        assertEquals("me@example.com", t.account);
    }

    @Test
    public void responseWithoutAccessTokenIsRejected() {
        try {
            OAuthTokens.fromTokenResponse("{\"error\":\"invalid_grant\"}", null, 0);
            fail();
        } catch (OneDriveException e) {
            assertEquals(OneDriveException.Kind.PERMANENT, e.kind);
        }
    }

    @Test
    public void storedTokensRoundTrip() {
        final OAuthTokens t = new OAuthTokens("a", "r", 42, "acc");
        final OAuthTokens back = OAuthTokens.fromJson(t.toJson());
        assertEquals("a", back.accessToken);
        assertEquals("r", back.refreshToken);
        assertEquals(42, back.expiresAtMillis);
        assertEquals("acc", back.account);
        assertNull(OAuthTokens.fromJson("not json"));
        assertFalse(t.toString().contains("a\""));
    }

    // --- OneDriveAuth / Http ---------------------------------------------------------------

    @Test
    public void redirectUriEncodesTheSignatureHashLikeAzure() {
        assertEquals("msauth://com.poupa.vinylmusicplayer/ab%2Bcd%2Fef%3D",
                OneDriveAuth.redirectUri("com.poupa.vinylmusicplayer", "ab+cd/ef="));
    }

    @Test
    public void graphErrorMessagesAreExtracted() {
        assertEquals("Item not found",
                Http.errorMessage("{\"error\":{\"code\":\"itemNotFound\",\"message\":\"Item not found\"}}"));
        assertEquals("Token expired",
                Http.errorMessage("{\"error\":\"invalid_grant\",\"error_description\":\"Token expired\\nTrace\"}"));
        assertNull(Http.errorMessage("<html>"));
    }

    @Test
    public void httpStatusesAreClassified() {
        assertEquals(OneDriveException.Kind.AUTH, OneDriveException.classify(401));
        assertEquals(OneDriveException.Kind.TRANSIENT, OneDriveException.classify(429));
        assertEquals(OneDriveException.Kind.TRANSIENT, OneDriveException.classify(503));
        assertEquals(OneDriveException.Kind.PERMANENT, OneDriveException.classify(404));
    }

    // --- DriveItem -------------------------------------------------------------------------

    @Test
    public void graphTimestampsAreParsed() {
        assertEquals(1709288130000L, DriveItem.parseIsoDate("2024-03-01T10:15:30.123Z"));
        assertEquals(1709288130000L, DriveItem.parseIsoDate("2024-03-01T10:15:30Z"));
        assertEquals(0, DriveItem.parseIsoDate(null));
        assertEquals(0, DriveItem.parseIsoDate("garbage"));
    }

    @Test
    public void graphItemWithAudioFacetIsParsed() {
        final DriveItem item = DriveItem.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"ABC!12\",\"name\":\"01 Intro.flac\",\"size\":1234,\"cTag\":\"c1\","
                        + "\"file\":{\"mimeType\":\"audio/flac\"},"
                        + "\"audio\":{\"title\":\"Intro\",\"artist\":\"Band\",\"album\":\"LP\",\"track\":1,"
                        + "\"disc\":1,\"year\":2001,\"duration\":215000}}").getAsJsonObject());
        assertEquals("ABC!12", item.id);
        assertFalse(item.isFolder);
        assertTrue(item.isPlayable());
        assertEquals("Intro", item.title);
        assertEquals("Band", item.artist);
        assertEquals(215000, item.durationMs);
        assertEquals(1, item.track);

        final DriveItem folder = DriveItem.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"F\",\"name\":\"Albums\",\"folder\":{\"childCount\":7}}").getAsJsonObject());
        assertTrue(folder.isFolder);
        assertEquals(7, folder.childCount);
        assertFalse(folder.isPlayable());
    }

    // --- OfflineSaver ----------------------------------------------------------------------

    @Test
    public void offlineFileNamesAreSafeAndNumbered() {
        assertEquals("AC_DC - T_N_T.mp3", OfflineSaver.safeName("AC/DC - T:N:T.mp3"));
        assertEquals("track", OfflineSaver.safeName("..."));
        assertEquals("song (2).mp3", OfflineSaver.numbered("song.mp3", 2));
        assertEquals("song (3)", OfflineSaver.numbered("song", 3));
        assertEquals("Music/Rock_Pop/Album", OfflineSaver.safeFolderPath("/Music//Rock:Pop/Album./"));
        assertEquals("", OfflineSaver.safeFolderPath(""));
    }
}
