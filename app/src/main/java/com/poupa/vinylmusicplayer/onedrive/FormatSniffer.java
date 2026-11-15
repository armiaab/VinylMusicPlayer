package com.poupa.vinylmusicplayer.onedrive;

import androidx.annotation.NonNull;

/**
 * Works out which bytes of a partly downloaded audio file the platform decoder reads while
 * preparing, so playback can start before the whole file is on the device without the
 * prepare step ever waiting on the network.
 *
 * The answer is a head (bytes from the start: tags, embedded cover art, codec headers, the first
 * audio frames) and an optional tail (bytes at the end: an MP4 index stored after the audio,
 * the last Ogg page used for the duration, ID3v1 tags). Pure Java, unit-tested.
 */
final class FormatSniffer {

    /** Bytes downloaded before the first look at the file. */
    static final int INITIAL_BYTES = 64 * 1024;

    /** Audio data after the headers, so the decoder can produce its first frames. */
    static final int AUDIO_SLACK = 64 * 1024;

    /** Head used when the format has no header worth parsing (MP3 without ID3v2, AAC, AMR). */
    static final int DEFAULT_HEAD = 128 * 1024;

    /** Covers ID3v1 / APE tags that MP3 readers look for at the end. */
    static final int DEFAULT_TAIL = 64 * 1024;

    /** Android's Ogg reader seeks to the last page (at most ~64 KiB) to compute the duration. */
    static final int OGG_TAIL = 128 * 1024;

    /** Matroska keeps its seek index (Cues) at the end. */
    static final int MATROSKA_TAIL = 256 * 1024;

    /** Headers larger than this are not parsed further; the decoder reads them as they arrive. */
    static final int MAX_SNIFF_BYTES = 8 << 20;

    private FormatSniffer() {}

    /** What must be downloaded before the decoder may be prepared. */
    static final class Plan {
        /** Bytes needed from the start of the file. */
        final long headBytes;
        /** First byte of the tail needed before preparing; equals the file size when none is needed. */
        final long tailStart;
        /**
         * {@code false} when the headers continue beyond the bytes looked at: look again once
         * {@link #headBytes} are available.
         */
        final boolean complete;
        /** An Ogg file with several streams: only its audio stream can play, from the whole file. */
        final boolean multiStream;

        Plan(long headBytes, long tailStart, boolean complete) {
            this(headBytes, tailStart, complete, false);
        }

        Plan(long headBytes, long tailStart, boolean complete, boolean multiStream) {
            this.headBytes = headBytes;
            this.tailStart = tailStart;
            this.complete = complete;
            this.multiStream = multiStream;
        }

        boolean needsTail(long size) {
            return tailStart < size;
        }

        @NonNull
        @Override
        public String toString() {
            return "Plan{head=" + headBytes + ", tailStart=" + tailStart + ", complete=" + complete + "}";
        }
    }

    /**
     * @param head      the first {@code available} bytes of the file
     * @param available how many bytes of {@code head} are valid (may be less than the file size)
     * @param size      the total file size
     * @param extension lower-case file extension, used when the content has no magic number
     */
    @NonNull
    static Plan plan(@NonNull byte[] head, int available, long size, @NonNull String extension) {
        // Small files: just fetch them whole before playing
        if (size <= DEFAULT_HEAD + DEFAULT_TAIL) return new Plan(size, size, true);

        final int avail = Math.min(available, head.length);
        if (avail < 12 && avail < size) return incomplete(12, size);

        // An ID3v2 tag can sit in front of MP3, and sometimes in front of FLAC
        long offset = 0;
        if (matches(head, avail, 0, "ID3") && avail >= 10) {
            long tagSize = 10 + syncSafe(head, 6);
            if ((head[5] & 0x10) != 0) tagSize += 10; // footer present
            offset = tagSize;
            if (offset + 4 > avail) {
                // Large embedded cover art: the audio starts after the whole tag
                if (offset + 4 > MAX_SNIFF_BYTES) return finish(offset + AUDIO_SLACK, DEFAULT_TAIL, size, true);
                return incomplete(offset + 4 + AUDIO_SLACK, size);
            }
            if (!matches(head, avail, (int) offset, "fLaC")) {
                return finish(offset + AUDIO_SLACK, DEFAULT_TAIL, size, true);
            }
        }

        if (matches(head, avail, (int) offset, "fLaC")) return flac(head, avail, size, offset + 4);
        if (matches(head, avail, 0, "OggS")) return ogg(head, avail, size);
        if (matches(head, avail, 4, "ftyp")) return mp4(head, avail, size);
        if (matches(head, avail, 0, "RIFF") && matches(head, avail, 8, "WAVE")) return wav(head, avail, size);

        if (extension.equals("mka")) return finish(DEFAULT_HEAD, MATROSKA_TAIL, size, true);
        if (AudioFormats.isMp4Family(extension)) {
            // MP4 without a readable ftyp box: be safe and get the whole file first
            return new Plan(size, size, true);
        }
        return finish(DEFAULT_HEAD, DEFAULT_TAIL, size, true);
    }

    /** FLAC: STREAMINFO and every other metadata block (cover art, tags) precede the audio. */
    private static Plan flac(byte[] b, int avail, long size, long blockStart) {
        long p = blockStart;
        while (true) {
            if (p + 4 > avail) return tooLargeOr(p + 4 + AUDIO_SLACK, DEFAULT_TAIL, size);
            final boolean last = (b[(int) p] & 0x80) != 0;
            final long length = u24(b, (int) p + 1);
            p += 4 + length;
            if (last || p >= size) break;
        }
        return finish(p + AUDIO_SLACK, DEFAULT_TAIL, size, true);
    }

    /**
     * Ogg: the codec headers are the first packets (Vorbis 3, Opus 2). The comment packet can
     * carry cover art, so count packets across pages instead of assuming a size.
     */
    private static Plan ogg(byte[] b, int avail, long size) {
        // Several streams (e.g. video + audio): Android refuses the file as it is; its audio
        // stream is indexed from all the pages (OggAudioOnly), so the whole file is needed
        if (OggAudioOnly.isMultiplexed(b, avail)) return new Plan(size, size, true, true);
        long p = 0;
        int packets = 0;
        int needed = -1;
        while (true) {
            if (p + 27 > avail) return tooLargeOr(p + 27 + AUDIO_SLACK, OGG_TAIL, size);
            if (!matches(b, avail, (int) p, "OggS")) {
                // Not a clean page sequence: fall back to a generous fixed head
                return finish(4L * DEFAULT_HEAD, OGG_TAIL, size, true);
            }
            final int segments = b[(int) p + 26] & 0xFF;
            final long bodyStart = p + 27 + segments;
            if (bodyStart > avail) return tooLargeOr(bodyStart + AUDIO_SLACK, OGG_TAIL, size);
            if (needed < 0) {
                if (bodyStart + 8 > avail) return tooLargeOr(bodyStart + 8 + AUDIO_SLACK, OGG_TAIL, size);
                needed = oggHeaderPackets(b, (int) bodyStart);
            }
            long bodyLength = 0;
            for (int i = 0; i < segments; i++) {
                final int lacing = b[(int) p + 27 + i] & 0xFF;
                bodyLength += lacing;
                if (lacing < 255) packets++; // a packet ends in this segment
            }
            p = bodyStart + bodyLength;
            if (packets >= needed || p >= size) break;
        }
        return finish(p + AUDIO_SLACK, OGG_TAIL, size, true);
    }

    private static int oggHeaderPackets(byte[] b, int body) {
        if (matches(b, b.length, body, "OpusHead")) return 2;
        if ((b[body] & 0xFF) == 0x7F && matches(b, b.length, body + 1, "FLAC") && body + 9 <= b.length) {
            // Ogg FLAC mapping: the first packet says how many header packets follow
            final int following = ((b[body + 7] & 0xFF) << 8) | (b[body + 8] & 0xFF);
            return 1 + (following > 0 ? following : 1);
        }
        return 3; // Vorbis: identification, comment, setup
    }

    /**
     * MP4 / M4A: the decoder needs the {@code moov} index. It is either before the audio
     * ({@code mdat}) or after it, in which case everything after {@code mdat} becomes the tail.
     */
    private static Plan mp4(byte[] b, int avail, long size) {
        long p = 0;
        while (p + 8 <= size) {
            if (p + 16 > avail && p + 8 > avail) return tooLargeOr(p + 16 + AUDIO_SLACK, DEFAULT_TAIL, size);
            long boxSize = u32(b, (int) p);
            final String type = fourCc(b, (int) p + 4);
            long header = 8;
            if (boxSize == 1) {
                if (p + 16 > avail) return tooLargeOr(p + 16 + AUDIO_SLACK, DEFAULT_TAIL, size);
                boxSize = u64(b, (int) p + 8);
                header = 16;
            } else if (boxSize == 0) {
                boxSize = size - p; // extends to the end of the file
            }
            if (boxSize < header) break; // corrupt: fall through to "whole file"

            if (type.equals("moov")) {
                // Index first ("fast start"): download it completely, then a little audio
                return finish(p + boxSize + AUDIO_SLACK, DEFAULT_TAIL, size, true);
            }
            if (type.equals("mdat")) {
                final long mdatEnd = p + boxSize;
                if (mdatEnd >= size) break; // no index after the audio: cannot plan
                final long head = Math.min(size, p + header + AUDIO_SLACK);
                return new Plan(head, Math.max(head, mdatEnd), true);
            }
            p += boxSize;
        }
        return new Plan(size, size, true);
    }

    /** WAV: header chunks up to {@code data}; the reader stops there. */
    private static Plan wav(byte[] b, int avail, long size) {
        long p = 12;
        while (p + 8 <= size) {
            if (p + 8 > avail) return tooLargeOr(p + 8 + AUDIO_SLACK, 0, size);
            final String id = fourCc(b, (int) p);
            final long length = u32le(b, (int) p + 4);
            if (id.equals("data")) return finish(p + 8 + AUDIO_SLACK, 0, size, true);
            p += 8 + length + (length & 1);
        }
        return finish(DEFAULT_HEAD, 0, size, true);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static Plan incomplete(long headBytes, long size) {
        return new Plan(Math.min(size, headBytes), size, false);
    }

    /** Asks for more bytes, unless the headers are so large that parsing them is not worth it. */
    private static Plan tooLargeOr(long headBytes, int tail, long size) {
        if (headBytes > MAX_SNIFF_BYTES) return finish(MAX_SNIFF_BYTES, tail, size, true);
        return incomplete(headBytes, size);
    }

    /** A finished plan; a tail that would overlap the head is dropped (sequential covers it). */
    private static Plan finish(long headBytes, int tailBytes, long size, boolean complete) {
        final long head = Math.min(size, headBytes);
        long tailStart = tailBytes > 0 ? size - tailBytes : size;
        if (tailStart <= head) tailStart = size;
        return new Plan(head, tailStart, complete);
    }

    private static boolean matches(byte[] b, int avail, int offset, String ascii) {
        if (offset < 0 || offset + ascii.length() > Math.min(avail, b.length)) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if (b[offset + i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    private static String fourCc(byte[] b, int offset) {
        final char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) (b[offset + i] & 0xFF);
        return new String(c);
    }

    private static long syncSafe(byte[] b, int offset) {
        return ((long) (b[offset] & 0x7F) << 21) | ((b[offset + 1] & 0x7F) << 14)
                | ((b[offset + 2] & 0x7F) << 7) | (b[offset + 3] & 0x7F);
    }

    private static long u24(byte[] b, int offset) {
        return ((long) (b[offset] & 0xFF) << 16) | ((b[offset + 1] & 0xFF) << 8) | (b[offset + 2] & 0xFF);
    }

    private static long u32(byte[] b, int offset) {
        return ((long) (b[offset] & 0xFF) << 24) | ((long) (b[offset + 1] & 0xFF) << 16)
                | ((long) (b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static long u32le(byte[] b, int offset) {
        return ((long) (b[offset + 3] & 0xFF) << 24) | ((long) (b[offset + 2] & 0xFF) << 16)
                | ((long) (b[offset + 1] & 0xFF) << 8) | (b[offset] & 0xFF);
    }

    private static long u64(byte[] b, int offset) {
        return (u32(b, offset) << 32) | u32(b, offset + 4);
    }
}
