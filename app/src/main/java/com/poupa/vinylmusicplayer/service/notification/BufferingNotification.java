package com.poupa.vinylmusicplayer.service.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.helper.PendingIntentCompat;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.onedrive.OneDrive;
import com.poupa.vinylmusicplayer.ui.activities.MainActivity;
import com.poupa.vinylmusicplayer.util.SafeToast;

/**
 * Tells the user what is happening while a OneDrive song loads: a notification with a progress
 * bar, and a short message in the app every few seconds. Nothing is shown when the song starts
 * within a second. For an Ogg recording with video in it, the first message explains why the
 * whole file has to be downloaded first.
 */
public final class BufferingNotification {

    private static final int NOTIFICATION_ID = 4;
    private static final String CHANNEL_ID = "buffering_notification";

    /** Quick starts show nothing. */
    private static final long SHOW_AFTER_MS = 1_000;
    /** In-app progress messages, no more often than this. */
    private static final long MESSAGE_EVERY_MS = 8_000;
    /** A notification no longer updated goes away by itself after this long. */
    private static final long EXPIRE_MS = 5_000;

    private final Context context;
    private final NotificationManager manager;
    private final String title;
    private final long startedAt = SystemClock.elapsedRealtime();
    /** The service shows "Buffering…" when loading starts: the next message comes later. */
    private long lastMessageAt = startedAt;
    private boolean explained;
    private boolean posted;
    private boolean finished;

    public BufferingNotification(@NonNull Context context, @NonNull Song song) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.title = song.getTitle();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    this.context.getString(R.string.onedrive_buffering_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(this.context.getString(R.string.onedrive_buffering_channel_description));
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }

    /** Called about twice a second while the song loads (from the playback thread). */
    public void onProgress(@NonNull OneDrive.BufferingProgress progress) {
        final long now = SystemClock.elapsedRealtime();
        if (finished || now - startedAt < SHOW_AFTER_MS) return;

        final int percent = progress.needed > 0
                ? (int) Math.min(100, progress.received * 100 / progress.needed) : -1;
        final String detail = percent >= 0
                ? context.getString(R.string.onedrive_buffering_progress, percent,
                    size(progress.received), size(progress.needed))
                : context.getString(R.string.onedrive_loading);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.onedrive_buffering_title, title))
                .setContentText(detail)
                .setProgress(100, Math.max(percent, 0), percent < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                // Updates come twice a second: if they stop without a cancel, it goes by itself
                .setTimeoutAfter(EXPIRE_MS)
                .setContentIntent(PendingIntentCompat.getActivity(context, 0,
                        new Intent(context, MainActivity.class), 0));
        if (progress.wholeFile) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(detail + "\n"
                    + context.getString(R.string.onedrive_whole_file_needed, title, size(progress.size))));
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
            posted = true;
        } catch (RuntimeException ignored) {
            // notifications not allowed: the messages below still tell the user
        }

        if (progress.wholeFile && !explained) {
            explained = true;
            lastMessageAt = now;
            SafeToast.show(context, context.getString(R.string.onedrive_whole_file_needed, title, size(progress.size)));
        } else if (now - lastMessageAt >= MESSAGE_EVERY_MS) {
            lastMessageAt = now;
            SafeToast.show(context, percent >= 0
                    ? context.getString(R.string.onedrive_buffering_percent, percent)
                    : context.getString(R.string.onedrive_buffering));
        }
    }

    /** Removes the notification once the song plays, or could not be loaded. */
    public void finish() {
        finished = true;
        if (!posted) return;
        manager.cancel(NOTIFICATION_ID);
        // The system posts notifications asynchronously: a cancel sent right after the last
        // update can be overtaken by it, so cancel once more a moment later
        new Handler(Looper.getMainLooper()).postDelayed(() -> manager.cancel(NOTIFICATION_ID), 1_500);
    }

    @NonNull
    private String size(long bytes) {
        return Formatter.formatShortFileSize(context, bytes);
    }
}
