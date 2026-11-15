package com.poupa.vinylmusicplayer.onedrive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

public class OggAudioOnlyTest {

    private static byte[] page(int headerType, long serial, int sequence, byte[] body) {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        final byte[] capture = "OggS".getBytes(StandardCharsets.ISO_8859_1);
        b.write(capture, 0, 4);
        b.write(0);
        b.write(headerType);
        for (int i = 0; i < 8; i++) b.write(0); // granule
        for (int i = 0; i < 4; i++) b.write((int) (serial >> (8 * i)));
        for (int i = 0; i < 4; i++) b.write(sequence >> (8 * i));
        for (int i = 0; i < 4; i++) b.write(0); // checksum (not checked here)
        b.write(1);
        b.write(body.length);
        b.write(body, 0, body.length);
        return b.toByteArray();
    }

    private static byte[] body(String start, int length) {
        final byte[] out = new byte[length];
        final byte[] s = start.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(s, 0, out, 0, s.length);
        for (int i = s.length; i < length; i++) out[i] = (byte) i;
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (final byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    private static OggAudioOnly.Source source(byte[] file) {
        return (position, buffer, offset, length) -> {
            if (position + length > file.length) throw new EOFException();
            System.arraycopy(file, (int) position, buffer, offset, length);
        };
    }

    private final byte[] videoHeader = page(0x02, 1, 0, body("\u0080theora", 42));
    private final byte[] audioHeader = page(0x02, 2, 0, body("\u0001vorbis", 30));
    private final byte[] video1 = page(0x00, 1, 1, body("v", 200));
    private final byte[] audio1 = page(0x00, 2, 1, body("a", 120));
    private final byte[] video2 = page(0x00, 1, 2, body("v", 250));
    private final byte[] audio2 = page(0x04, 2, 2, body("a", 90));
    private final byte[] file = concat(videoHeader, audioHeader, video1, audio1, video2, audio2);

    @Test
    public void detectsSeveralStreams() {
        assertTrue(OggAudioOnly.isMultiplexed(file, file.length));
        final byte[] single = concat(audioHeader, audio1);
        assertFalse(OggAudioOnly.isMultiplexed(single, single.length));
        assertFalse(OggAudioOnly.isMultiplexed(new byte[100], 100));
    }

    @Test
    public void audioOnlyViewIsTheAudioPagesInOrder() throws Exception {
        final OggAudioOnly view = OggAudioOnly.scan(source(file), file.length);
        assertNotNull(view);
        final byte[] expected = concat(audioHeader, audio1, audio2);
        assertEquals(expected.length, view.size);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        view.copyTo(source(file), out);
        assertArrayEquals(expected, out.toByteArray());

        // Reads across page boundaries, and at the end
        final byte[] part = new byte[60];
        assertEquals(60, view.read(source(file), audioHeader.length - 20, part, 0, 60));
        for (int i = 0; i < 60; i++) assertEquals(expected[audioHeader.length - 20 + i], part[i]);
        assertEquals(-1, view.read(source(file), view.size, part, 0, 10));
        assertEquals(5, view.read(source(file), view.size - 5, part, 0, 10));
    }

    @Test
    public void singleStreamNeedsNothing() throws Exception {
        final byte[] single = concat(audioHeader, audio1, audio2);
        assertNull(OggAudioOnly.scan(source(single), single.length));
    }

    @Test
    public void noAudioStreamNeedsNothing() throws Exception {
        final byte[] videoOnly = concat(videoHeader, page(0x02, 3, 0, body("fishead", 64)), video1);
        assertNull(OggAudioOnly.scan(source(videoOnly), videoOnly.length));
    }

    @Test
    public void streamingPlanWaitsForTheWholeMultiStreamFile() {
        final long size = 50L << 20;
        final byte[] head = new byte[64 * 1024];
        System.arraycopy(file, 0, head, 0, file.length);
        final FormatSniffer.Plan plan = FormatSniffer.plan(head, head.length, size, "ogg");
        assertEquals(size, plan.headBytes);
        assertTrue(plan.multiStream);
    }
}
