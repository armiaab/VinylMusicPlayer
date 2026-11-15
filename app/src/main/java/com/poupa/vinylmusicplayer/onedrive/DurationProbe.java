package com.poupa.vinylmusicplayer.onedrive;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Reads the length of an audio file from a few small byte ranges (its headers, and for Ogg its
 * last page), so songs can show their duration without being downloaded. Pure Java, unit-tested.
 */
final class DurationProbe {

    /** Random access to the file's bytes (ranged downloads for OneDrive files). */
    interface Source {
        /** Up to {@code length} bytes at {@code offset}; fewer only at the end of the file. */
        @NonNull
        byte[] read(long offset, int length) throws IOException;
    }

    static final int HEAD_BYTES = 64 * 1024;
    private static final int OGG_TAIL_BYTES = 64 * 1024;
    /** Box headers are 8 or 16 bytes; top-level MP4 walks stop after this many boxes. */
    private static final int MAX_MP4_BOXES = 64;

    private DurationProbe() {}

    /** The duration in milliseconds, or 0 when the format is not supported or not understood. */
    static long durationMs(@NonNull Source source, long size, @NonNull String extension) throws IOException {
        if (size <= 0) return 0;
        byte[] head = source.read(0, (int) Math.min(size, HEAD_BYTES));
        long base = 0; // file offset of head[0]

        // ID3v2 in front of MP3 (or FLAC): look behind it
        if (matches(head, 0, "ID3") && head.length >= 10) {
            long tagSize = 10 + syncSafe(head, 6);
            if ((head[5] & 0x10) != 0) tagSize += 10;
            if (tagSize >= size) return 0;
            if (tagSize + 4 > head.length) {
                base = tagSize;
                head = source.read(base, (int) Math.min(size - base, HEAD_BYTES));
            } else {
                final byte[] rest = new byte[head.length - (int) tagSize];
                System.arraycopy(head, (int) tagSize, rest, 0, rest.length);
                head = rest;
                base = tagSize;
            }
        }

        if (matches(head, 0, "fLaC")) return flac(head);
        if (base == 0 && matches(head, 0, "RIFF") && matches(head, 8, "WAVE")) return wav(source, head, size);
        if (base == 0 && matches(head, 0, "OggS")) return ogg(source, head, size);
        if (base == 0 && matches(head, 4, "ftyp")) return mp4(source, size);
        if (extension.equals("mp3") || base > 0) return mp3(head, base, size);
        return 0;
    }

    // --- FLAC: STREAMINFO holds the sample rate and the total number of samples -------------

    private static long flac(byte[] b) {
        // "fLaC", block header (4), STREAMINFO: min/max block (4), min/max frame (6), then
        // sample rate (20 bits), channels (3), bits per sample (5), total samples (36 bits)
        final int p = 4 + 4 + 10;
        if (b.length < p + 8) return 0;
        final long sampleRate = ((long) (b[p] & 0xFF) << 12) | ((b[p + 1] & 0xFF) << 4) | ((b[p + 2] & 0xF0) >> 4);
        final long totalSamples = ((long) (b[p + 3] & 0x0F) << 32) | u32(b, p + 4);
        if (sampleRate <= 0 || totalSamples <= 0) return 0;
        return totalSamples * 1000 / sampleRate;
    }

    // --- WAV: data size / byte rate --------------------------------------------------------

    private static long wav(Source source, byte[] head, long size) throws IOException {
        long byteRate = 0;
        long p = 12;
        for (int guard = 0; guard < 64 && p + 8 <= size; guard++) {
            final byte[] header = slice(source, head, p, 8);
            if (header.length < 8) return 0;
            final String id = fourCc(header, 0);
            final long length = u32le(header, 4);
            if (id.equals("fmt ")) {
                final byte[] fmt = slice(source, head, p + 8, 16);
                if (fmt.length < 16) return 0;
                byteRate = u32le(fmt, 8);
            } else if (id.equals("data")) {
                if (byteRate <= 0) return 0;
                long dataSize = length;
                // Streamed recordings may leave the size unset or too large: use what is there
                if (dataSize == 0 || dataSize == 0xFFFFFFFFL || p + 8 + dataSize > size) dataSize = size - p - 8;
                return dataSize * 1000 / byteRate;
            }
            p += 8 + length + (length & 1);
        }
        return 0;
    }

    // --- Ogg: last granule position / sample rate ------------------------------------------

    private static long ogg(Source source, byte[] head, long size) throws IOException {
        // An Ogg file may interleave several streams (e.g. Theora video + Vorbis audio). Each
        // starts with a beginning-of-stream page: find the audio one and its serial number.
        long rate = 0;
        long preSkip = 0;
        long serial = -1;
        int p = 0;
        while (p + 27 <= head.length && matches(head, p, "OggS")) {
            if ((head[p + 5] & 0x02) == 0) break; // past the beginning-of-stream pages
            final int segments = head[p + 26] & 0xFF;
            final int body = p + 27 + segments;
            if (body + 20 > head.length) break;
            int bodyLength = 0;
            for (int i = 0; i < segments; i++) bodyLength += head[p + 27 + i] & 0xFF;
            if (matches(head, body, "OpusHead")) {
                rate = 48_000; // Opus granules always count 48 kHz samples
                preSkip = (head[body + 10] & 0xFF) | ((head[body + 11] & 0xFF) << 8);
                serial = u32le(head, p + 14);
                break;
            }
            if ((head[body] & 0xFF) == 0x01 && matches(head, body + 1, "vorbis")) {
                rate = u32le(head, body + 12);
                serial = u32le(head, p + 14);
                break;
            }
            p = body + bodyLength;
        }
        if (serial < 0 || rate <= 0) return 0;

        // The last page of that stream holds its final sample position
        final long tailStart = Math.max(0, size - OGG_TAIL_BYTES);
        final byte[] tail = tailStart == 0 ? head : source.read(tailStart, (int) (size - tailStart));
        for (int i = tail.length - 27; i >= 0; i--) {
            if (matches(tail, i, "OggS") && u32le(tail, i + 14) == serial) {
                final long granule = u64le(tail, i + 6);
                if (granule <= 0 || granule == -1L) continue; // page without a position
                return Math.max(0, granule - preSkip) * 1000 / rate;
            }
        }
        return 0;
    }

    // --- MP4: duration and time scale of the movie header (mvhd) ---------------------------

    private static long mp4(Source source, long size) throws IOException {
        long p = 0;
        for (int boxes = 0; boxes < MAX_MP4_BOXES && p + 8 <= size; boxes++) {
            final byte[] header = source.read(p, 16);
            if (header.length < 8) return 0;
            long boxSize = u32(header, 0);
            final String type = fourCc(header, 4);
            long headerSize = 8;
            if (boxSize == 1) {
                if (header.length < 16) return 0;
                boxSize = (u32(header, 8) << 32) | u32(header, 12);
                headerSize = 16;
            } else if (boxSize == 0) {
                boxSize = size - p;
            }
            if (boxSize < headerSize) return 0;
            if (type.equals("moov")) {
                final byte[] moov = source.read(p + headerSize, (int) Math.min(boxSize - headerSize, 4096));
                return mvhd(moov);
            }
            p += boxSize;
        }
        return 0;
    }

    private static long mvhd(byte[] moov) {
        int p = 0;
        while (p + 8 <= moov.length) {
            final long boxSize = u32(moov, p);
            if (fourCc(moov, p + 4).equals("mvhd")) {
                final int d = p + 8;
                if (d + 1 > moov.length) return 0;
                final int version = moov[d] & 0xFF;
                final long timescale;
                final long duration;
                if (version == 1) {
                    if (d + 32 > moov.length) return 0;
                    timescale = u32(moov, d + 20);
                    duration = (u32(moov, d + 24) << 32) | u32(moov, d + 28);
                } else {
                    if (d + 20 > moov.length) return 0;
                    timescale = u32(moov, d + 12);
                    duration = u32(moov, d + 16);
                }
                return timescale > 0 ? duration * 1000 / timescale : 0;
            }
            if (boxSize < 8) return 0;
            p += (int) boxSize;
        }
        return 0;
    }

    // --- MP3: Xing/Info or VBRI frame count, else constant bitrate --------------------------

    private static final int[][] BITRATES_KBPS = {
            // MPEG-1 Layer III
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
            // MPEG-2/2.5 Layer III
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
    };
    private static final int[][] SAMPLE_RATES = {
            {44100, 48000, 32000}, // MPEG-1
            {22050, 24000, 16000}, // MPEG-2
            {11025, 12000, 8000},  // MPEG-2.5
    };

    private static long mp3(byte[] b, long base, long size) {
        for (int i = 0; i + 4 <= b.length; i++) {
            if ((b[i] & 0xFF) != 0xFF || (b[i + 1] & 0xE0) != 0xE0) continue;
            final int versionBits = (b[i + 1] >> 3) & 0x3; // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
            final int layerBits = (b[i + 1] >> 1) & 0x3;   // 1 = Layer III
            final int bitrateIndex = (b[i + 2] >> 4) & 0xF;
            final int rateIndex = (b[i + 2] >> 2) & 0x3;
            if (versionBits == 1 || layerBits != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) continue;
            final boolean mpeg1 = versionBits == 3;
            final int sampleRate = SAMPLE_RATES[mpeg1 ? 0 : (versionBits == 2 ? 1 : 2)][rateIndex];
            final int bitrate = BITRATES_KBPS[mpeg1 ? 0 : 1][bitrateIndex] * 1000;
            final boolean mono = ((b[i + 3] >> 6) & 0x3) == 3;
            final int samplesPerFrame = mpeg1 ? 1152 : 576;

            // Xing / Info header (VBR): the total frame count
            final int xing = i + 4 + (mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17));
            if (xing + 12 <= b.length && (matches(b, xing, "Xing") || matches(b, xing, "Info"))) {
                final long flags = u32(b, xing + 4);
                if ((flags & 1) != 0) {
                    final long frames = u32(b, xing + 8);
                    if (frames > 0) return frames * samplesPerFrame * 1000 / sampleRate;
                }
            }
            // VBRI header (Fraunhofer VBR)
            final int vbri = i + 4 + 32;
            if (vbri + 18 <= b.length && matches(b, vbri, "VBRI")) {
                final long frames = u32(b, vbri + 14);
                if (frames > 0) return frames * samplesPerFrame * 1000 / sampleRate;
            }
            // Constant bitrate
            final long audioBytes = size - base - i;
            return audioBytes * 8 * 1000 / bitrate;
        }
        return 0;
    }

    // --- helpers ---------------------------------------------------------------------------

    /** {@code length} bytes at file offset {@code offset}, from {@code head} when it has them. */
    private static byte[] slice(Source source, byte[] head, long offset, int length) throws IOException {
        if (offset + length <= head.length) {
            final byte[] out = new byte[length];
            System.arraycopy(head, (int) offset, out, 0, length);
            return out;
        }
        return source.read(offset, length);
    }

    private static boolean matches(byte[] b, int offset, String ascii) {
        if (offset < 0 || offset + ascii.length() > b.length) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if (b[offset + i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    private static String fourCc(byte[] b, int offset) {
        if (offset + 4 > b.length) return "";
        final char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) (b[offset + i] & 0xFF);
        return new String(c);
    }

    private static long syncSafe(byte[] b, int offset) {
        return ((long) (b[offset] & 0x7F) << 21) | ((b[offset + 1] & 0x7F) << 14)
                | ((b[offset + 2] & 0x7F) << 7) | (b[offset + 3] & 0x7F);
    }

    private static long u32(byte[] b, int offset) {
        return ((long) (b[offset] & 0xFF) << 24) | ((long) (b[offset + 1] & 0xFF) << 16)
                | ((long) (b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static long u32le(byte[] b, int offset) {
        return ((long) (b[offset + 3] & 0xFF) << 24) | ((long) (b[offset + 2] & 0xFF) << 16)
                | ((long) (b[offset + 1] & 0xFF) << 8) | (b[offset] & 0xFF);
    }

    private static long u64le(byte[] b, int offset) {
        return (u32le(b, offset + 4) << 32) | u32le(b, offset);
    }
}
