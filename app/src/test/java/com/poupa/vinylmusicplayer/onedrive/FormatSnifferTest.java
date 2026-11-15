package com.poupa.vinylmusicplayer.onedrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class FormatSnifferTest {

    private static final int SLACK = FormatSniffer.AUDIO_SLACK;
    private static final long MB = 1024 * 1024;

    /** Little helper to lay out file headers byte by byte. */
    private static final class Bytes {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes ascii(String s) {
            final byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            out.write(b, 0, b.length);
            return this;
        }

        Bytes u8(int... values) {
            for (final int v : values) out.write(v & 0xFF);
            return this;
        }

        Bytes u24(long v) {
            return u8((int) (v >> 16), (int) (v >> 8), (int) v);
        }

        Bytes u32(long v) {
            return u8((int) (v >> 24), (int) (v >> 16), (int) (v >> 8), (int) v);
        }

        Bytes u32le(long v) {
            return u8((int) v, (int) (v >> 8), (int) (v >> 16), (int) (v >> 24));
        }

        Bytes zeros(int n) {
            for (int i = 0; i < n; i++) out.write(0);
            return this;
        }

        int length() {
            return out.size();
        }

        /** The bytes, padded with zeros to {@code size}. */
        byte[] padTo(int size) {
            final byte[] b = out.toByteArray();
            final byte[] padded = new byte[Math.max(size, b.length)];
            System.arraycopy(b, 0, padded, 0, b.length);
            return padded;
        }
    }

    @Test
    public void smallFileIsFetchedWhole() {
        final FormatSniffer.Plan plan = FormatSniffer.plan(new byte[1000], 1000, 100_000, "mp3");
        assertEquals(100_000, plan.headBytes);
        assertFalse(plan.needsTail(100_000));
        assertTrue(plan.complete);
    }

    @Test
    public void mp3WithLargeId3CoverArtWaitsForTheWholeTag() {
        final long size = 8 * MB;
        final int tagSize = 1024 * 1024; // syncsafe 0x00 0x40 0x00 0x00
        final byte[] head = new Bytes().ascii("ID3").u8(3, 0, 0).u8(0x00, 0x40, 0x00, 0x00).padTo(64 * 1024);

        final FormatSniffer.Plan first = FormatSniffer.plan(head, head.length, size, "mp3");
        assertFalse("tag continues past the sniffed bytes", first.complete);
        assertTrue(first.headBytes > 10 + tagSize);

        final byte[] more = new Bytes().ascii("ID3").u8(3, 0, 0).u8(0x00, 0x40, 0x00, 0x00)
                .padTo((int) first.headBytes);
        final FormatSniffer.Plan second = FormatSniffer.plan(more, more.length, size, "mp3");
        assertTrue(second.complete);
        assertEquals(10 + tagSize + SLACK, second.headBytes);
        assertEquals("ID3v1 at the end", size - FormatSniffer.DEFAULT_TAIL, second.tailStart);
    }

    @Test
    public void flacMetadataBlocksIncludingPictureAreInTheHead() {
        final long size = 30 * MB;
        final int pictureLength = 200_000;
        final byte[] head = new Bytes()
                .ascii("fLaC")
                .u8(0x00).u24(34).zeros(34)               // STREAMINFO, not last
                .u8(0x80 | 6).u24(pictureLength)          // PICTURE, last
                .padTo(64 * 1024);

        final FormatSniffer.Plan plan = FormatSniffer.plan(head, head.length, size, "flac");
        assertTrue(plan.complete);
        assertEquals(4 + 4 + 34 + 4 + pictureLength + SLACK, plan.headBytes);
    }

    @Test
    public void flacBehindId3IsParsedAsFlac() {
        final long size = 30 * MB;
        final byte[] head = new Bytes()
                .ascii("ID3").u8(4, 0, 0).u8(0, 0, 0, 10).zeros(10) // 10-byte ID3 body
                .ascii("fLaC")
                .u8(0x80).u24(34).zeros(34)
                .padTo(64 * 1024);
        final FormatSniffer.Plan plan = FormatSniffer.plan(head, head.length, size, "flac");
        assertTrue(plan.complete);
        assertEquals(20 + 4 + 4 + 34 + SLACK, plan.headBytes);
    }

    @Test
    public void mp4WithIndexAfterTheAudioFetchesItAsTail() {
        final long mdatSize = 5 * MB;
        final long moovSize = 100 * 1024;
        final long size = 24 + mdatSize + moovSize;
        final byte[] head = new Bytes()
                .u32(24).ascii("ftyp").ascii("M4A ").u32(0).ascii("isomM4A ")
                .u32(mdatSize).ascii("mdat")
                .padTo(64 * 1024);

        final FormatSniffer.Plan plan = FormatSniffer.plan(head, head.length, size, "m4a");
        assertTrue(plan.complete);
        assertEquals(24 + 8 + SLACK, plan.headBytes);
        assertEquals("everything after mdat", 24 + mdatSize, plan.tailStart);
        assertTrue(plan.needsTail(size));
    }

    @Test
    public void mp4WithIndexFirstDownloadsTheWholeIndex() {
        final long moovSize = 300 * 1024;
        final long size = 6 * MB;
        final byte[] head = new Bytes()
                .u32(24).ascii("ftyp").ascii("M4A ").u32(0).ascii("isomM4A ")
                .u32(moovSize).ascii("moov")
                .padTo(64 * 1024);

        final FormatSniffer.Plan plan = FormatSniffer.plan(head, head.length, size, "m4a");
        assertTrue(plan.complete);
        assertEquals(24 + moovSize + SLACK, plan.headBytes);
    }

    @Test
    public void mp4ExtensionWithoutFtypIsFetchedWhole() {
        final long size = 6 * MB;
        final FormatSniffer.Plan plan = FormatSniffer.plan(new byte[64 * 1024], 64 * 1024, size, "m4a");
        assertEquals(size, plan.headBytes);
    }

    @Test
    public void oggVorbisHeadCoversAllThreeHeaderPackets() {
        final long size = 10 * MB;
        final Bytes b = new Bytes();
        // Page 1: identification packet (30 bytes)
        oggPage(b, new int[]{30});
        b.u8(0x01).ascii("vorbis").zeros(23);
        // Page 2: comment packet of 300 bytes (255 + 45)
        oggPage(b, new int[]{255, 45});
        b.zeros(300);
        // Page 3: setup packet of 520 bytes (255 + 255 + 10)
        oggPage(b, new int[]{255, 255, 10});
        b.zeros(520);
        final int headerEnd = b.length();
        final byte[] head = b.padTo(64 * 1024);

        final FormatSniffer.Plan plan = FormatSniffer.plan(head, head.length, size, "ogg");
        assertTrue(plan.complete);
        assertEquals(headerEnd + SLACK, plan.headBytes);
        assertEquals("last page for the duration", size - FormatSniffer.OGG_TAIL, plan.tailStart);
    }

    @Test
    public void opusNeedsTwoHeaderPackets() {
        final long size = 10 * MB;
        final Bytes b = new Bytes();
        oggPage(b, new int[]{19});
        b.ascii("OpusHead").zeros(11);
        oggPage(b, new int[]{100});
        b.zeros(100);
        final int headerEnd = b.length();
        final byte[] head = b.padTo(64 * 1024);

        final FormatSniffer.Plan plan = FormatSniffer.plan(head, head.length, size, "opus");
        assertEquals(headerEnd + SLACK, plan.headBytes);
    }

    private static void oggPage(Bytes b, int[] lacing) {
        b.ascii("OggS").u8(0, 0).zeros(8).zeros(4).zeros(4).zeros(4).u8(lacing.length);
        for (final int l : lacing) b.u8(l);
    }

    @Test
    public void wavHeadEndsAtTheDataChunk() {
        final long size = 50 * MB;
        final byte[] head = new Bytes()
                .ascii("RIFF").u32le(size - 8).ascii("WAVE")
                .ascii("fmt ").u32le(16).zeros(16)
                .ascii("data").u32le(size - 44)
                .padTo(64 * 1024);
        final FormatSniffer.Plan plan = FormatSniffer.plan(head, head.length, size, "wav");
        assertTrue(plan.complete);
        assertEquals(44 + SLACK, plan.headBytes);
        assertFalse(plan.needsTail(size));
    }

    @Test
    public void mp3WithoutTagsUsesTheDefaultHeadAndTail() {
        final long size = 6 * MB;
        final FormatSniffer.Plan plan = FormatSniffer.plan(new byte[64 * 1024], 64 * 1024, size, "mp3");
        assertEquals(FormatSniffer.DEFAULT_HEAD, plan.headBytes);
        assertEquals(size - FormatSniffer.DEFAULT_TAIL, plan.tailStart);
    }
}
