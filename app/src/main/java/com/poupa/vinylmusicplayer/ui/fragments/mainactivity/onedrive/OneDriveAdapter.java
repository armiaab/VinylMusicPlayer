package com.poupa.vinylmusicplayer.ui.fragments.mainactivity.onedrive;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.kabouzeid.appthemehelper.ThemeStore;
import com.kabouzeid.appthemehelper.util.ATHUtil;
import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.adapter.base.MediaEntryViewHolder;
import com.poupa.vinylmusicplayer.databinding.ItemListBinding;
import com.poupa.vinylmusicplayer.onedrive.AudioFormats;
import com.poupa.vinylmusicplayer.onedrive.DriveItem;
import com.poupa.vinylmusicplayer.util.FileUtil;
import com.poupa.vinylmusicplayer.util.ImageTheme.ThemeStyleUtil;
import com.poupa.vinylmusicplayer.util.ImageUtil;
import com.poupa.vinylmusicplayer.util.MusicUtil;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Folders and songs of one OneDrive folder. */
class OneDriveAdapter extends RecyclerView.Adapter<OneDriveAdapter.ViewHolder>
        implements FastScrollRecyclerView.SectionedAdapter {

    interface Callbacks {
        void onItemClicked(@NonNull DriveItem item);

        void onItemMenuClicked(@NonNull DriveItem item, @NonNull View anchor);
    }

    private final AppCompatActivity activity;
    private final Callbacks callbacks;
    private final int iconColor;
    private final int accentColor;
    @NonNull
    private List<DriveItem> items = new ArrayList<>();

    OneDriveAdapter(@NonNull AppCompatActivity activity, @NonNull Callbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
        this.iconColor = ATHUtil.resolveColor(activity, R.attr.iconColor);
        this.accentColor = ThemeStore.accentColor(activity);
        setHasStableIds(true);
    }

    void swap(@NonNull List<DriveItem> newItems) {
        items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    List<DriveItem> items() {
        return items;
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id.hashCode();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemListBinding.inflate(LayoutInflater.from(activity), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final DriveItem item = items.get(position);

        if (holder.shortSeparator != null) {
            holder.shortSeparator.setVisibility(position == getItemCount() - 1
                    ? View.GONE
                    : ThemeStyleUtil.getInstance().getShortSeparatorVisibilityState());
        }
        if (holder.title != null) holder.title.setText(title(item));
        if (holder.text != null) {
            holder.text.setVisibility(View.VISIBLE);
            holder.text.setText(subtitle(item));
        }
        if (holder.image != null) {
            final int icon = item.isFolder
                    ? R.drawable.ic_folder_white_24dp
                    : item.offline ? R.drawable.ic_cloud_done_white_24dp : R.drawable.ic_file_music_white_24dp;
            // Offline songs are tinted with the accent color, so they stand out
            final int color = !item.isFolder && item.offline ? accentColor : iconColor;
            holder.image.setImageDrawable(ImageUtil.getTintedVectorDrawable(activity, icon, color));
        }
    }

    @NonNull
    private static String title(@NonNull DriveItem item) {
        if (item.isFolder) return item.name;
        return item.title.isEmpty() ? FileUtil.stripExtension(item.name) : item.title;
    }

    @NonNull
    private String subtitle(@NonNull DriveItem item) {
        if (item.isFolder) {
            return activity.getString(R.string.onedrive_folder_items, item.childCount);
        }
        return MusicUtil.buildInfoString(
                item.artist,
                AudioFormats.label(item.name),
                readableSize(item.size),
                item.durationMs > 0 ? MusicUtil.getReadableDurationString(item.durationMs) : "",
                item.offline ? activity.getString(R.string.onedrive_offline) : "");
    }

    @NonNull
    private static String readableSize(long size) {
        if (size <= 0) return "";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        final int group = Math.min(units.length - 1, (int) (Math.log10(size) / Math.log10(1024)));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, group)) + " " + units[group];
    }

    @NonNull
    @Override
    public String getSectionName(int position) {
        final String title = title(items.get(position));
        return title.isEmpty() ? "" : title.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    class ViewHolder extends MediaEntryViewHolder {
        ViewHolder(@NonNull ItemListBinding binding) {
            super(binding);
            ThemeStyleUtil.getInstance().setHeightListItem(binding.getRoot(), activity.getResources().getDisplayMetrics().density);
            if (imageBorderTheme != null) {
                imageBorderTheme.setRadius(ThemeStyleUtil.getInstance().getAlbumRadiusImage(activity));
            }
            if (menu != null) {
                menu.setOnClickListener(v -> {
                    final int position = getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        callbacks.onItemMenuClicked(items.get(position), v);
                    }
                });
            }
        }

        @Override
        public void onClick(View v) {
            final int position = getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) {
                callbacks.onItemClicked(items.get(position));
            }
        }
    }
}
