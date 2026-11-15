package com.poupa.vinylmusicplayer.onedrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * The audio stream of an Ogg file that holds several streams, such as a screen recording with
 * Theora video and Vorbis audio.
 *
 * Android's Ogg reader only looks at the first stream of a file: when that is video, it finds "no
 * track" and refuses the whole file. But Ogg files are made of self-contained pages tagged with
 * their stream, so the pages of the audio stream alone, in order, form a valid single-stream Ogg
 * file. This class indexes those pages and serves that audio-only view, byte for byte from the
 * original (no re-encoding: the sound is untouched). Pure Java, unit-tested.
 */
public final class OggAudioOnly {

    /** Random access to the original file. */
    public interface Source {
        /** Exactly {@code length} bytes at {@code position}; {@link EOFException} past the end. */
        void readFully(long position, @NonNull byte[] buffer, int offset, int length) throws IOException;
    }

    private static final int HEADER = 27;
    private static final int BLOCK = 64 * 1024;
    /** A page holds at most 27 + 255 + 255 * 255 bytes. */
    private static final int MAX_PAGE = HEADER + 255 + 255 * 255;

    /** Where each kept page is in the original file, and where it is in the audio-only view. */
    private final long[] physicalStart;
    private final long[] virtualStart;
    private final int[] pageLength;
    private final int pages;
    /** Size of the audio-only view. */
    public final long size;

    private OggAudioOnly(long[] physicalStart, long[] virtualStart, int[] pageLength, int pages, long size) {
        this.physicalStart = physicalStart;
        this.virtualStart = virtualStart;
        this.pageLength = pageLength;
        this.pages = pages;
        this.size = size;
    }

    /**
     * Whether the start of a file shows an Ogg file with several streams (all streams begin with
     * a "beginning of stream" page before any other page). Needs the first few KB.
     */
    public static boolean isMultiplexed(@NonNull byte[] head, int available) {
        final int n = Math.min(available, head.length);
        if (n < HEADER || !isCapture(head, 0)) return false;
        if ((head[5] & 0x02) == 0) return false;
        final int next = pageEnd(head, 0, n);
        return next > 0 && next + HEADER <= n && isCapture(head, next) && (head[next + 5] & 0x02) != 0;
    }

    /**
     * Indexes the audio stream of an Ogg file with several streams.
     *
     * @return {@code null} when there is nothing to do: a single stream (Android plays it as is),
     * or no audio stream Android can decode (Vorbis, Opus, FLAC)
     */
    @Nullable
    public static OggAudioOnly scan(@NonNull Source source, long fileSize) throws IOException {
        final BlockReader reader = new BlockReader(source, fileSize);

        // The beginning-of-stream pages come first: find the audio stream
        long position = 0;
        int beginnings = 0;
        long audioSerial = -1;
        while (position + HEADER <= fileSize) {
            final byte[] header = reader.bytes(position, HEADER);
            if (!isCapture(header, 0) || (header[5] & 0x02) == 0) break;
            beginnings++;
            final int segments = header[26] & 0xFF;
            final byte[] lacing = reader.bytes(position + HEADER, segments);
            int bodyLength = 0;
            for (int i = 0; i < segments; i++) bodyLength += lacing[i] & 0xFF;
            if (audioSerial < 0 && bodyLength > 0) {
                final byte[] body = reader.bytes(position + HEADER + segments, Math.min(bodyLength, 16));
                if (isAudioHeader(body)) audioSerial = u32le(header, 14);
            }
            position += HEADER + segments + bodyLength;
        }
        if (beginnings < 2 || audioSerial < 0) return null;

        // Every page of that stream, in order
        long[] physical = new long[1024];
        long[] virtual = new long[1024];
        int[] lengths = new int[1024];
        int count = 0;
        long virtualSize = 0;
        position = 0;
        while (position + HEADER <= fileSize) {
            byte[] header = reader.bytes(position, HEADER);
            if (!isCapture(header, 0)) {
                // Damaged data: look for the next page within a block
                final long next = reader.find(position + 1, BLOCK);
                if (next < 0) break;
                position = next;
                continue;
            }
            final int segments = header[26] & 0xFF;
            if (position + HEADER + segments > fileSize) break;
            final byte[] lacing = reader.bytes(position + HEADER, segments);
            int bodyLength = 0;
            for (int i = 0; i < segments; i++) bodyLength += lacing[i] & 0xFF;
            final int length = HEADER + segments + bodyLength;
            if (position + length > fileSize) break; // truncated last page
            if (u32le(header, 14) == audioSerial) {
                if (count == physical.length) {
                    physical = Arrays.copyOf(physical, count * 2);
                    virtual = Arrays.copyOf(virtual, count * 2);
                    lengths = Arrays.copyOf(lengths, count * 2);
                }
                physical[count] = position;
                virtual[count] = virtualSize;
                lengths[count] = length;
                count++;
                virtualSize += length;
            }
            position += length;
        }
        return count == 0 ? null : new OggAudioOnly(physical, virtual, lengths, count, virtualSize);
    }

    /**
     * Reads the audio-only view: up to {@code length} bytes at {@code position}.
     *
     * @return bytes read, or -1 at the end
     */
    public int read(@NonNull Source source, long position, @NonNull byte[] buffer, int offset, int length) throws IOException {
        if (position >= size) return -1;
        int page = Arrays.binarySearch(virtualStart, 0, pages, position);
        if (page < 0) page = -page - 2; // the page that contains position
        int done = 0;
        long at = position;
        while (done < length && page < pages && at < size) {
            final long inPage = at - virtualStart[page];
            final int chunk = (int) Math.min(length - done, pageLength[page] - inPage);
            source.readFully(physicalStart[page] + inPage, buffer, offset + done, chunk);
            done += chunk;
            at += chunk;
            page++;
        }
        return done;
    }

    /** Writes the whole audio-only file. */
    public void copyTo(@NonNull Source source, @NonNull OutputStream out) throws IOException {
        final byte[] buffer = new byte[MAX_PAGE];
        for (int page = 0; page < pages; page++) {
            source.readFully(physicalStart[page], buffer, 0, pageLength[page]);
            out.write(buffer, 0, pageLength[page]);
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Vorbis, Opus or FLAC: the Ogg codecs Android decodes. */
    private static boolean isAudioHeader(@NonNull byte[] body) {
        if (body.length >= 7 && (body[0] & 0xFF) == 0x01 && matches(body, 1, "vorbis")) return true;
        if (body.length >= 8 && matches(body, 0, "OpusHead")) return true;
        return body.length >= 5 && (body[0] & 0xFF) == 0x7F && matches(body, 1, "FLAC");
    }

    /** End of the page starting at {@code start} in {@code b}, or -1 when its header is incomplete. */
    private static int pageEnd(@NonNull byte[] b, int start, int available) {
        if (start + HEADER > available) return -1;
        final int segments = b[start + 26] & 0xFF;
        if (start + HEADER + segments > available) return -1;
        int body = 0;
        for (int i = 0; i < segments; i++) body += b[start + HEADER + i] & 0xFF;
        return start + HEADER + segments + body;
    }

    private static boolean isCapture(@NonNull byte[] b, int offset) {
        return matches(b, offset, "OggS");
    }

    private static boolean matches(@NonNull byte[] b, int offset, @NonNull String ascii) {
        if (offset < 0 || offset + ascii.length() > b.length) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if (b[offset + i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    private static long u32le(@NonNull byte[] b, int offset) {
        return ((long) (b[offset + 3] & 0xFF) << 24) | ((long) (b[offset + 2] & 0xFF) << 16)
                | ((long) (b[offset + 1] & 0xFF) << 8) | (b[offset] & 0xFF);
    }

    /** Page headers come in small pieces: read the file a block at a time. */
    private static final class BlockReader {
        private final Source source;
        private final long fileSize;
        private final byte[] block = new byte[BLOCK];
        private long blockStart = -1;
        private int blockLength;

        BlockReader(@NonNull Source source, long fileSize) {
            this.source = source;
            this.fileSize = fileSize;
        }

        /** {@code length} bytes at {@code position} (header pieces: at most 282 bytes). */
        @NonNull
        byte[] bytes(long position, int length) throws IOException {
            final byte[] out = new byte[length];
            if (length == 0) return out;
            if (blockStart < 0 || position < blockStart || position + length > blockStart + blockLength) {
                blockStart = position;
                blockLength = (int) Math.min(BLOCK, fileSize - position);
                if (blockLength < length) throw new EOFException();
                source.readFully(blockStart, block, 0, blockLength);
            }
            System.arraycopy(block, (int) (position - blockStart), out, 0, length);
            return out;
        }

        /** Position of the next "OggS" at or after {@code from}, within {@code limit} bytes; -1 if none. */
        long find(long from, int limit) throws IOException {
            final long end = Math.min(fileSize - 4, from + limit);
            for (long p = from; p <= end; p++) {
                final byte[] four = bytes(p, 4);
                if (isCapture(four, 0)) return p;
            }
            return -1;
        }
    }
}
