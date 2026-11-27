package com.poupa.vinylmusicplayer.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.afollestad.materialdialogs.MaterialDialog;
import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.helper.menu.ResetPlayCountsHelper;
import com.poupa.vinylmusicplayer.model.Song;

import java.util.concurrent.Executors;

/** Confirms clearing one song's play statistics from the most-played list. */
public class ResetPlayCountsDialog extends DialogFragment {

    @NonNull
    public static ResetPlayCountsDialog create(@NonNull Song song) {
        ResetPlayCountsDialog dialog = new ResetPlayCountsDialog();
        Bundle args = new Bundle();
        args.putParcelable("song", song);
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Song song = getArguments().getParcelable("song");

        return new MaterialDialog.Builder(requireActivity())
                .title(R.string.reset_play_counts_title)
                .content(R.string.reset_play_counts_message)
                .positiveText(R.string.reset_action)
                .negativeText(android.R.string.cancel)
                .onPositive((dialog, which) -> {
                    if (getActivity() == null || song == null) {
                        return;
                    }
                    ResetPlayCountsHelper.reset(getActivity(), song, Executors.newSingleThreadExecutor());
                })
                .build();
    }
}
