package com.poupa.vinylmusicplayer.ui.fragments.mainactivity.onedrive;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.appbar.AppBarLayout;
import com.kabouzeid.appthemehelper.ThemeStore;
import com.kabouzeid.appthemehelper.common.ATHToolbarActivity;
import com.kabouzeid.appthemehelper.util.ToolbarContentTintHelper;
import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.databinding.FragmentOnedriveBinding;
import com.poupa.vinylmusicplayer.helper.MusicPlayerRemote;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.onedrive.DriveItem;
import com.poupa.vinylmusicplayer.onedrive.OneDrive;
import com.poupa.vinylmusicplayer.onedrive.OneDriveAuth;
import com.poupa.vinylmusicplayer.onedrive.OneDriveClient;
import com.poupa.vinylmusicplayer.onedrive.OneDriveException;
import com.poupa.vinylmusicplayer.ui.activities.MainActivity;
import com.poupa.vinylmusicplayer.ui.fragments.mainactivity.AbsMainActivityFragment;
import com.poupa.vinylmusicplayer.util.PreferenceUtil;
import com.poupa.vinylmusicplayer.util.SafeToast;
import com.poupa.vinylmusicplayer.util.ViewUtil;

import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Browses the signed-in OneDrive: folders and the music files in them (MP3, M4A, AAC, OGG,
 * Opus, FLAC, WAV...). Songs stream when played and are then saved for offline listening.
 */
@RequiresApi(23)
public class OneDriveFragment extends AbsMainActivityFragment implements
        MainActivity.MainActivityFragmentCallbacks,
        OneDriveAdapter.Callbacks,
        AppBarLayout.OnOffsetChangedListener {

    private static final String STATE_FOLDER_IDS = "onedrive_folder_ids";
    private static final String STATE_FOLDER_NAMES = "onedrive_folder_names";
    private static final String ROOT_KEY = "";

    /** Listings of this session, so going back is instant. Refreshed from the menu. */
    private static final LruCache<String, List<DriveItem>> LISTINGS = new LruCache<>(32);

    /** One background thread for listing, registering songs and signing in. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "OneDriveBrowser");
        t.setDaemon(true);
        return t;
    });

    private static final Comparator<DriveItem> FOLDERS_FIRST_BY_NAME = (a, b) -> {
        if (a.isFolder != b.isFolder) return a.isFolder ? -1 : 1;
        return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
    };

    private final Handler main = new Handler(Looper.getMainLooper());

    /** The open folders below the root, outermost first. */
    private final ArrayList<String> folderIds = new ArrayList<>();
    private final ArrayList<String> folderNames = new ArrayList<>();

    private FragmentOnedriveBinding binding;
    private OneDriveAdapter adapter;
    private ActivityResultLauncher<Intent> signInLauncher;
    @Nullable
    private AuthorizationService authService;
    /** Ignores results of a listing the user has navigated away from. */
    private int generation;

    public static OneDriveFragment newInstance() {
        return new OneDriveFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        signInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onSignInResult);
        if (savedInstanceState != null) {
            final ArrayList<String> ids = savedInstanceState.getStringArrayList(STATE_FOLDER_IDS);
            final ArrayList<String> names = savedInstanceState.getStringArrayList(STATE_FOLDER_NAMES);
            if (ids != null && names != null && ids.size() == names.size()) {
                folderIds.addAll(ids);
                folderNames.addAll(names);
            }
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentOnedriveBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        getMainActivity().setStatusbarColorAuto();
        getMainActivity().setNavigationbarColorAuto();
        getMainActivity().setTaskDescriptionColorAuto();

        final int primaryColor = ThemeStore.primaryColor(requireActivity());
        binding.appbar.setBackgroundColor(primaryColor);
        binding.toolbar.setBackgroundColor(primaryColor);
        binding.toolbar.setNavigationIcon(R.drawable.ic_menu_white_24dp);
        getMainActivity().setSupportActionBar(binding.toolbar);
        binding.appbar.addOnOffsetChangedListener(this);

        ViewUtil.setUpFastScrollRecyclerViewColor(requireActivity(), binding.recyclerView,
                PreferenceUtil.getInstance().getAccentColor());
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));
        adapter = new OneDriveAdapter(getMainActivity(), this);
        binding.recyclerView.setAdapter(adapter);

        binding.signInButton.setOnClickListener(v -> signIn());

        render();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_FOLDER_IDS, folderIds);
        outState.putStringArrayList(STATE_FOLDER_NAMES, folderNames);
    }

    @Override
    public void onDestroyView() {
        binding.appbar.removeOnOffsetChangedListener(this);
        generation++;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (authService != null) {
            authService.dispose();
            authService = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean handleBackPress() {
        if (folderIds.isEmpty()) return false;
        folderIds.remove(folderIds.size() - 1);
        folderNames.remove(folderNames.size() - 1);
        load(false);
        return true;
    }

    // --- options menu ----------------------------------------------------------------------

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_onedrive, menu);
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(requireActivity(), binding.toolbar, menu,
                ATHToolbarActivity.getToolbarBackgroundColor(binding.toolbar));
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        super.onPrepareOptionsMenu(menu);
        final boolean signedIn = OneDriveAuth.isSignedIn(requireContext());
        for (final int id : new int[]{R.id.action_shuffle_all, R.id.action_onedrive_play_all,
                R.id.action_onedrive_refresh, R.id.action_onedrive_sign_out}) {
            final MenuItem item = menu.findItem(id);
            if (item != null) item.setVisible(signedIn);
        }
        ToolbarContentTintHelper.handleOnPrepareOptionsMenu(requireActivity(), binding.toolbar);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.action_shuffle_all) {
            withSongs(playableItems(), currentPath(), songs -> MusicPlayerRemote.openAndShuffleQueue(songs, true));
            return true;
        } else if (id == R.id.action_onedrive_play_all) {
            withSongs(playableItems(), currentPath(), songs -> MusicPlayerRemote.openQueue(songs, 0, true));
            return true;
        } else if (id == R.id.action_onedrive_refresh) {
            load(true);
            // Also pick up changes elsewhere in the drive for the library
            OneDrive.scanLibrary(requireContext(), false, true);
            return true;
        } else if (id == R.id.action_onedrive_sign_out) {
            confirmSignOut();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- items -----------------------------------------------------------------------------

    @Override
    public void onItemClicked(@NonNull DriveItem item) {
        if (item.isFolder) {
            folderIds.add(item.id);
            folderNames.add(item.name);
            load(false);
            return;
        }
        // Play the folder's songs, starting with this one
        final List<DriveItem> playable = playableItems();
        final int start = Math.max(0, playable.indexOf(item));
        withSongs(playable, currentPath(), songs -> {
            if (!songs.isEmpty()) {
                MusicPlayerRemote.enqueueSongsWithConfirmation(requireActivity(), songs, Math.min(start, songs.size() - 1));
            }
        });
    }

    @Override
    public void onItemMenuClicked(@NonNull DriveItem item, @NonNull View anchor) {
        final PopupMenu popup = new PopupMenu(requireActivity(), anchor);
        popup.inflate(R.menu.menu_item_onedrive);
        popup.getMenu().findItem(R.id.action_play).setVisible(item.isFolder);
        popup.setOnMenuItemClickListener(menuItem -> {
            final int id = menuItem.getItemId();
            if (item.isFolder) {
                final String path = childPath(item.name);
                withFolderSongs(item, path, songs -> {
                    if (id == R.id.action_play) MusicPlayerRemote.openQueue(songs, 0, true);
                    else if (id == R.id.action_play_next) MusicPlayerRemote.playNext(songs);
                    else if (id == R.id.action_add_to_current_playing) MusicPlayerRemote.enqueue(songs);
                });
            } else {
                withSongs(Collections.singletonList(item), currentPath(), songs -> {
                    if (id == R.id.action_play_next) MusicPlayerRemote.playNext(songs);
                    else if (id == R.id.action_add_to_current_playing) MusicPlayerRemote.enqueue(songs);
                });
            }
            return true;
        });
        popup.show();
    }

    @NonNull
    private List<DriveItem> playableItems() {
        final List<DriveItem> playable = new ArrayList<>();
        if (adapter == null) return playable;
        for (final DriveItem item : adapter.items()) {
            if (item.isPlayable()) playable.add(item);
        }
        return playable;
    }

    private interface SongsCallback {
        void onSongs(@NonNull List<Song> songs);
    }

    /**
     * Turns files into songs off the main thread. Songs already saved offline come back as their
     * library copy (with its own tags, ReplayGain and cover); the others as OneDrive songs.
     */
    private void withSongs(@NonNull List<DriveItem> items, @NonNull String folderPath, @NonNull SongsCallback callback) {
        if (items.isEmpty()) {
            SafeToast.show(requireContext(), R.string.onedrive_no_music_here);
            return;
        }
        final List<DriveItem> copy = new ArrayList<>(items);
        IO.execute(() -> {
            final List<Song> registered = OneDrive.library().register(copy, folderPath);
            final List<Song> songs = new ArrayList<>(registered.size());
            for (final Song song : registered) {
                final Song local = OneDrive.localSong(song);
                songs.add(local != null ? local : song);
            }
            main.post(() -> {
                if (isAdded()) callback.onSongs(songs);
            });
        });
    }

    /** The songs directly inside a folder (from the session cache, or listed now). */
    private void withFolderSongs(@NonNull DriveItem folder, @NonNull String folderPath, @NonNull SongsCallback callback) {
        final Context app = requireContext().getApplicationContext();
        IO.execute(() -> {
            List<DriveItem> children = LISTINGS.get(folder.id);
            if (children == null) {
                try {
                    children = sorted(new OneDriveClient(app).listChildren(folder.id));
                    LISTINGS.put(folder.id, children);
                } catch (OneDriveException e) {
                    final String message = e.getMessage();
                    main.post(() -> SafeToast.show(app, app.getString(R.string.onedrive_load_failed, message)));
                    return;
                }
            }
            final List<DriveItem> playable = new ArrayList<>();
            for (final DriveItem item : children) {
                if (item.isPlayable()) playable.add(item);
            }
            main.post(() -> {
                if (isAdded()) withSongs(playable, folderPath, callback);
            });
        });
    }

    // --- loading ---------------------------------------------------------------------------

    private void render() {
        if (binding == null) return;
        requireActivity().invalidateOptionsMenu();
        if (!OneDriveAuth.isSignedIn(requireContext())) {
            generation++;
            updateTitle();
            adapter.swap(new ArrayList<>());
            binding.progress.setVisibility(View.GONE);
            binding.message.setVisibility(View.GONE);
            binding.signInPanel.setVisibility(View.VISIBLE);
            return;
        }
        binding.signInPanel.setVisibility(View.GONE);
        load(false);
    }

    private void load(boolean refresh) {
        if (binding == null) return;
        updateTitle();
        final String folderId = currentFolderId();
        final String key = folderId == null ? ROOT_KEY : folderId;
        final int gen = ++generation;

        final List<DriveItem> cached = refresh ? null : LISTINGS.get(key);
        if (cached != null) {
            show(cached);
            // Songs may have been saved offline since: refresh their marks
            IO.execute(() -> {
                OneDrive.library().markOfflineState(cached);
                main.post(() -> {
                    if (gen == generation && binding != null) adapter.notifyDataSetChanged();
                });
            });
            return;
        }

        adapter.swap(new ArrayList<>());
        binding.message.setVisibility(View.GONE);
        binding.progress.setVisibility(View.VISIBLE);
        final Context app = requireContext().getApplicationContext();
        IO.execute(() -> {
            try {
                final List<DriveItem> items = sorted(new OneDriveClient(app).listChildren(folderId));
                OneDrive.library().markOfflineState(items);
                LISTINGS.put(key, items);
                main.post(() -> {
                    if (gen == generation && binding != null) show(items);
                });
            } catch (OneDriveException e) {
                main.post(() -> {
                    if (gen == generation && binding != null) showError(e);
                });
            }
        });
    }

    private void show(@NonNull List<DriveItem> items) {
        binding.progress.setVisibility(View.GONE);
        binding.signInPanel.setVisibility(View.GONE);
        adapter.swap(items);
        binding.recyclerView.scrollToPosition(0);
        if (items.isEmpty()) {
            binding.message.setText(R.string.onedrive_empty_folder);
            binding.message.setVisibility(View.VISIBLE);
        } else {
            binding.message.setVisibility(View.GONE);
        }
    }

    private void showError(@NonNull OneDriveException e) {
        binding.progress.setVisibility(View.GONE);
        if (e.kind == OneDriveException.Kind.AUTH) {
            // The sign-in expired or was revoked: its songs cannot stream any more
            OneDriveAuth.signOut(requireContext());
            OneDrive.onSignedOut(requireContext());
            SafeToast.show(requireContext(), getString(R.string.onedrive_sign_in_failed, e.getMessage()));
            render();
            return;
        }
        final Throwable cause = e.getCause();
        final boolean offline = cause instanceof UnknownHostException
                || cause instanceof ConnectException
                || cause instanceof SocketTimeoutException;
        binding.message.setText(offline
                ? getString(R.string.onedrive_offline_hint)
                : getString(R.string.onedrive_load_failed, e.getMessage()));
        binding.message.setVisibility(View.VISIBLE);
    }

    @NonNull
    private static List<DriveItem> sorted(@NonNull List<DriveItem> items) {
        final List<DriveItem> copy = new ArrayList<>(items);
        Collections.sort(copy, FOLDERS_FIRST_BY_NAME);
        return copy;
    }

    private void updateTitle() {
        requireActivity().setTitle(R.string.onedrive);
        binding.toolbar.setTitle(R.string.onedrive);
        binding.toolbar.setSubtitle(folderNames.isEmpty() ? null : currentPath());
    }

    @Nullable
    private String currentFolderId() {
        return folderIds.isEmpty() ? null : folderIds.get(folderIds.size() - 1);
    }

    /** "Music/Album" for the open folder, "" at the root. Mirrored below Music/OneDrive offline. */
    @NonNull
    private String currentPath() {
        return TextUtils.join("/", folderNames);
    }

    @NonNull
    private String childPath(@NonNull String name) {
        return folderNames.isEmpty() ? name : currentPath() + "/" + name;
    }

    // --- sign-in ---------------------------------------------------------------------------

    private void signIn() {
        final Context context = requireContext();
        try {
            if (authService == null) {
                authService = new AuthorizationService(context, OneDriveAuth.appAuthConfiguration(context));
            }
            signInLauncher.launch(authService.getAuthorizationRequestIntent(OneDriveAuth.authorizationRequest(context)));
        } catch (ActivityNotFoundException e) {
            SafeToast.show(context, R.string.onedrive_no_browser);
        } catch (RuntimeException e) {
            SafeToast.show(context, getString(R.string.onedrive_sign_in_failed, String.valueOf(e.getMessage())));
        }
    }

    private void onSignInResult(@NonNull ActivityResult result) {
        final Context app = requireContext().getApplicationContext();
        final Intent data = result.getData();
        final AuthorizationResponse response = data != null ? AuthorizationResponse.fromIntent(data) : null;
        if (response == null) {
            final AuthorizationException error = data != null ? AuthorizationException.fromIntent(data) : null;
            final boolean cancelled = error == null
                    || (error.type == AuthorizationException.TYPE_GENERAL_ERROR
                    && error.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code);
            if (cancelled) {
                SafeToast.show(app, R.string.onedrive_sign_in_cancelled);
            } else {
                final String reason = error.errorDescription != null ? error.errorDescription
                        : error.error != null ? error.error : String.valueOf(error.getMessage());
                SafeToast.show(app, app.getString(R.string.onedrive_sign_in_failed, reason));
            }
            return;
        }
        if (binding != null) {
            binding.signInPanel.setVisibility(View.GONE);
            binding.progress.setVisibility(View.VISIBLE);
        }
        IO.execute(() -> {
            String toast;
            try {
                final String account = OneDriveAuth.completeSignIn(app, response);
                toast = account != null ? app.getString(R.string.onedrive_signed_in_as, account) : null;
                // List the music of the whole drive in the library
                OneDrive.onSignedIn(app);
            } catch (OneDriveException e) {
                toast = app.getString(R.string.onedrive_sign_in_failed, e.getMessage());
            }
            final String message = toast;
            main.post(() -> {
                if (message != null) SafeToast.show(app, message);
                if (isAdded()) render();
            });
        });
    }

    private void confirmSignOut() {
        new MaterialDialog.Builder(requireActivity())
                .title(R.string.onedrive_sign_out)
                .content(R.string.onedrive_sign_out_confirm)
                .positiveText(R.string.onedrive_sign_out)
                .negativeText(android.R.string.cancel)
                .onPositive((dialog, which) -> {
                    final Context app = requireContext().getApplicationContext();
                    IO.execute(() -> {
                        OneDriveAuth.signOut(app);
                        OneDrive.onSignedOut(app); // its songs leave the library
                        LISTINGS.evictAll();
                        main.post(() -> {
                            folderIds.clear();
                            folderNames.clear();
                            if (isAdded()) render();
                        });
                    });
                })
                .show();
    }

    @Override
    public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
        binding.container.setPadding(
                binding.container.getPaddingLeft(),
                binding.container.getPaddingTop(),
                binding.container.getPaddingRight(),
                binding.appbar.getTotalScrollRange() + verticalOffset);
    }
}
