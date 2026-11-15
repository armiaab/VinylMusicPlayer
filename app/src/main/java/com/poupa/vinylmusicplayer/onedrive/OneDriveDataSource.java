package com.poupa.vinylmusicplayer.onedrive;

import android.media.MediaDataSource;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.IOException;

/**
 * Feeds a OneDrive stream to {@link android.media.MediaPlayer}. The platform decoder reads the
 * original file bytes, so quality is exactly that of the file on OneDrive.
 */
@RequiresApi(23)
public final class OneDriveDataSource extends MediaDataSource {

    private final OneDriveStreams owner;
    private final OneDriveStream stream;
    private boolean closed;

    OneDriveDataSource(@NonNull OneDriveStreams owner, @NonNull OneDriveStream stream) {
        this.owner = owner;
        this.stream = stream;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        return stream.read(position, buffer, offset, size);
    }

    @Override
    public long getSize() {
        return stream.size;
    }

    /** Called by the player when it is reset or released, and by Vinyl itself. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        owner.release(stream);
    }
}
