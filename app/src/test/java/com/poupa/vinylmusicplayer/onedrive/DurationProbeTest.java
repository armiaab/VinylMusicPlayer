package com.poupa.vinylmusicplayer.onedrive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class DurationProbeTest {

    /** A file of {@code size} bytes holding {@code head} at 0 and {@code tail} at the end. */
    private static DurationProbe.Source file(byte[] head, byte[] tail, long size) {
        return (offset, length) -> {
            final int n = (int) Math.max(0, Math.min(length, size - offset));
            final byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                final long at = offset + i;
                if (at < head.length) out[i] = head[(int) at];
                else if (at >= size - tail.length) out[i] = tail[(int) (at - (size - tail.length))];
            }
            return out;
        };
    }

    private static final class Bytes {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes ascii(String s) {
            final byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            out.write(b, 0, b.length);
            return this;
        }

        Bytes u8(int... v) {
            for (final int x : v) out.write(x & 0xFF);
            return this;
        }

        Bytes u16le(int v) {
            return u8(v, v >> 8);
        }

        Bytes u32le(long v) {
            return u8((int) v, (int) (v >> 8), (int) (v >> 16), (int) (v >> 24));
        }

        Bytes u32(long v) {
            return u8((int) (v >> 24), (int) (v >> 16), (int) (v >> 8), (int) v);
        }

        Bytes u64le(long v) {
            return u32le(v).u32le(v >>> 32);
        }

        Bytes zeros(int n) {
            for (int i = 0; i < n; i++) out.write(0);
            return this;
        }

        byte[] get() {
            return out.toByteArray();
        }
    }

    @Test
    public void wavIsDataSizeOverByteRate() throws Exception {
        final long byteRate = 44100 * 4; // 16-bit stereo
        final long dataSize = byteRate * 209; // 3:29
        final byte[] head = new Bytes()
                .ascii("RIFF").u32le(36 + dataSize).ascii("WAVE")
                .ascii("fmt ").u32le(16).u16le(1).u16le(2).u32le(44100).u32le(byteRate).u16le(4).u16le(16)
                .ascii("data").u32le(dataSize).get();
        assertEquals(209_000, DurationProbe.durationMs(file(head, new byte[0], 44 + dataSize), 44 + dataSize, "wav"));
    }

    @Test
    public void flacIsTotalSamplesOverSampleRate() throws Exception {
        // STREAMINFO: 44100 Hz, stereo, 16 bit, 44100 * 60 samples
        final long samples = 44100L * 60;
        final long sampleRate = 44100;
        final byte[] head = new Bytes().ascii("fLaC").u8(0x80, 0, 0, 34).zeros(10)
                .u8((int) (sampleRate >> 12), (int) (sampleRate >> 4),
                        (int) (((sampleRate & 0xF) << 4) | (1 << 1) | (15 >> 4)),
                        (int) (((15 & 0xF) << 4) | (samples >> 32)))
                .u32(samples).zeros(16).get();
        assertEquals(60_000, DurationProbe.durationMs(file(head, new byte[0], 5_000_000), 5_000_000, "flac"));
    }

    @Test
    public void mp3WithXingHeaderCountsFrames() throws Exception {
        // MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo; Xing at 4 + 32
        final byte[] head = new Bytes().u8(0xFF, 0xFB, 0x90, 0x00).zeros(32)
                .ascii("Xing").u32(1).u32(1000).zeros(100).get();
        // 1000 frames * 1152 samples / 44100 Hz = 26.122 s
        assertEquals(26_122, DurationProbe.durationMs(file(head, new byte[0], 4_000_000), 4_000_000, "mp3"));
    }

    @Test
    public void mp3WithoutHeaderIsConstantBitrate() throws Exception {
        final byte[] head = new Bytes().u8(0xFF, 0xFB, 0x90, 0x00).zeros(200).get();
        final long size = 128_000 / 8 * 60; // 60 s at 128 kbps
        assertEquals(60_000, DurationProbe.durationMs(file(head, new byte[0], size), size, "mp3"));
    }

    private static Bytes oggPage(Bytes b, int headerType, long granule, long serial, byte[] body) {
        b.ascii("OggS").u8(0, headerType).u64le(granule).u32le(serial).u32le(0).u32le(0).u8(1).u8(body.length);
        b.out.write(body, 0, body.length);
        return b;
    }

    @Test
    public void oggVorbisIsLastGranuleOverRate() throws Exception {
        final byte[] id = new Bytes().u8(0x01).ascii("vorbis").u32le(0).u8(2).u32le(48000).zeros(15).get();
        final byte[] head = oggPage(new Bytes(), 0x02, 0, 7, id).get();
        final byte[] tail = oggPage(new Bytes(), 0x04, 48000L * 802, 7, new byte[10]).get();
        assertEquals(802_000, DurationProbe.durationMs(file(head, tail, 10_000_000), 10_000_000, "ogg"));
    }

    @Test
    public void opusSubtractsPreSkipAndCounts48kHz() throws Exception {
        final byte[] id = new Bytes().ascii("OpusHead").u8(1, 2).u16le(312).u32le(16000).zeros(7).get();
        final byte[] head = oggPage(new Bytes(), 0x02, 0, 9, id).get();
        final byte[] tail = oggPage(new Bytes(), 0x04, 48000L * 10 + 312, 9, new byte[10]).get();
        assertEquals(10_000, DurationProbe.durationMs(file(head, tail, 2_000_000), 2_000_000, "opus"));
    }

    @Test
    public void oggWithVideoUsesTheAudioStream() throws Exception {
        final byte[] theora = new Bytes().u8(0x80).ascii("theora").zeros(35).get();
        final byte[] vorbis = new Bytes().u8(0x01).ascii("vorbis").u32le(0).u8(2).u32le(44100).zeros(15).get();
        final Bytes h = new Bytes();
        oggPage(h, 0x02, 0, 1, theora);
        oggPage(h, 0x02, 0, 2, vorbis);
        final Bytes t = new Bytes();
        oggPage(t, 0x00, 44100L * 100, 2, new byte[10]); // last audio page
        oggPage(t, 0x04, 999_999_999L, 1, new byte[10]);  // last video page, other serial
        assertEquals(100_000, DurationProbe.durationMs(file(h.get(), t.get(), 50_000_000), 50_000_000, "ogg"));
    }

    @Test
    public void mp4ReadsTheMovieHeaderAfterTheAudio() throws Exception {
        final long mdatSize = 20_000_000;
        final byte[] head = new Bytes()
                .u32(24).ascii("ftyp").ascii("M4A ").u32(0).ascii("isomM4A ")
                .u32(mdatSize).ascii("mdat").get();
        final byte[] moov = new Bytes()
                .u32(8 + 8 + 100).ascii("moov")
                .u32(8 + 100).ascii("mvhd").u8(0, 0, 0, 0).u32(0).u32(0).u32(1000).u32(1_503_000).zeros(80)
                .get();
        final long size = 24 + mdatSize + moov.length;
        assertEquals(1_503_000, DurationProbe.durationMs(file(head, moov, size), size, "m4a"));
    }

    @Test
    public void unsupportedFormatsAreUnknown() throws Exception {
        assertEquals(0, DurationProbe.durationMs(file(new byte[100], new byte[0], 100_000), 100_000, "amr"));
    }
}
