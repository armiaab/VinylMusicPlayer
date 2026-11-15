package com.poupa.vinylmusicplayer.onedrive;

import android.media.MediaDataSource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.Closeable;
import java.io.IOException;

/** Feeds the audio-only view of a multi-stream Ogg file ({@link OggAudioOnly}) to the player. */
@RequiresApi(23)
public final class OggAudioDataSource extends MediaDataSource {

    private final OggAudioOnly view;
    private final OggAudioOnly.Source source;
    @Nullable
    private final Closeable owner;
    private boolean closed;

    OggAudioDataSource(@NonNull OggAudioOnly view, @NonNull OggAudioOnly.Source source, @Nullable Closeable owner) {
        this.view = view;
        this.source = source;
        this.owner = owner;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        return view.read(source, position, buffer, offset, size);
    }

    @Override
    public long getSize() {
        return view.size;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (owner != null) owner.close();
    }
}
