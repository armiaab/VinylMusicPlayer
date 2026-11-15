package com.poupa.vinylmusicplayer.helper.menu;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.dialogs.AddToPlaylistDialog;
import com.poupa.vinylmusicplayer.dialogs.helper.DeleteSongsHelper;
import com.poupa.vinylmusicplayer.helper.MusicPlayerRemote;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.onedrive.OneDrive;

import java.util.Collection;
import java.util.List;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class SongsMenuHelper {
    public static void handleMenuClick(@NonNull final FragmentActivity activity, @NonNull final Collection<? extends Song> songs, final int menuItemId) {
        if (menuItemId == R.id.action_play_next) {
            MusicPlayerRemote.playNext(songs);
        } else if (menuItemId == R.id.action_add_to_current_playing) {
            MusicPlayerRemote.enqueue(songs);
        } else if (menuItemId == R.id.action_add_to_playlist) {
            // Playlists keep MediaStore songs: OneDrive songs take part once saved offline
            final List<Song> local = OneDrive.requireLocalSongs(activity, songs);
            if (!local.isEmpty()) {
                AddToPlaylistDialog.create(local).show(activity.getSupportFragmentManager(), "ADD_PLAYLIST");
            }
        } else if (menuItemId == R.id.action_delete_from_device) {
            final List<Song> local = OneDrive.requireLocalSongs(activity, songs);
            if (!local.isEmpty()) {
                DeleteSongsHelper.delete(local, activity.getSupportFragmentManager(), "DELETE_SONGS");
            }
        }
    }
}
