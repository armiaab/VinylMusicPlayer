package com.poupa.vinylmusicplayer.onedrive;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists every music file of the drive into {@link OneDriveLibrary}, so they show up in the
 * library.
 *
 * Uses Microsoft Graph's delta listing (what the OneDrive sync clients use): the first scan
 * pages through the whole drive in large pages, keeping only folders and music files; later
 * scans send the saved delta link and get only what changed since, usually in one request.
 */
@RequiresApi(23)
final class OneDriveScanner {

    /** Excluded from backups, see res/xml/backup_rules.xml. */
    private static final String PREFS = "onedrive_scan";
    private static final String KEY_DELTA_LINK = "delta_link";
    private static final String KEY_ROOT_ID = "root_id";
    private static final String KEY_LAST_SCAN = "last_scan";

    private static final String SELECT =
            "id,name,size,file,folder,audio,video,cTag,lastModifiedDateTime,parentReference,deleted,root";

    /** Items per page; fewer round trips on big drives. The server may return fewer. */
    private static final int PAGE_SIZE = 1000;

    private final Context context;
    private final OneDriveClient client;
    private final OneDriveLibrary library;

    OneDriveScanner(@NonNull Context context, @NonNull OneDriveLibrary library) {
        this.context = context.getApplicationContext();
        this.client = new OneDriveClient(this.context);
        this.library = library;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the last scan is older than {@code maxAgeMs} (or there was none). */
    boolean isStale(long maxAgeMs) {
        return System.currentTimeMillis() - prefs().getLong(KEY_LAST_SCAN, 0) > maxAgeMs;
    }

    /** Forgets the scan position (signed out, or another account). */
    void forget() {
        prefs().edit().clear().apply();
    }

    /**
     * Scans the drive. Blocks; never call on the main thread.
     *
     * @param full list everything again instead of only the changes since the last scan
     */
    @NonNull
    OneDriveLibrary.ScanResult scan(boolean full) throws OneDriveException {
        final String deltaLink = full ? null : prefs().getString(KEY_DELTA_LINK, null);
        try {
            return run(deltaLink);
        } catch (OneDriveClient.ResyncRequiredException expired) {
            // The saved position is too old: list everything again
            try {
                return run(null);
            } catch (OneDriveClient.ResyncRequiredException again) {
                throw OneDriveException.transientError("OneDrive keeps asking for a rescan", again);
            }
        }
    }

    @NonNull
    private OneDriveLibrary.ScanResult run(@Nullable String deltaLink)
            throws OneDriveException, OneDriveClient.ResyncRequiredException {
        final boolean fullScan = deltaLink == null;
        final String base = OneDriveClient.driveUrl() + "/root/delta";
        String url = fullScan ? base + "?$select=" + SELECT + "&$top=" + PAGE_SIZE : deltaLink;
        String rootId = fullScan ? null : prefs().getString(KEY_ROOT_ID, null);

        final List<LibraryNaming.Folder> folders = new ArrayList<>();
        final List<DriveItem> audio = new ArrayList<>();
        final Set<String> deleted = new HashSet<>();
        boolean firstPage = true;

        while (true) {
            final JsonObject page;
            try {
                page = client.getPage(url);
            } catch (OneDriveException e) {
                // Some accounts refuse the page size or field selection on delta: drop them
                if (firstPage && fullScan && e.httpStatus == 400) {
                    if (url.contains("$top=")) {
                        url = base + "?$select=" + SELECT;
                        continue;
                    }
                    if (url.contains("$select=")) {
                        url = base;
                        continue;
                    }
                }
                throw e;
            }
            firstPage = false;

            final JsonElement value = page.get("value");
            if (value != null && value.isJsonArray()) {
                for (final JsonElement element : value.getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    final JsonObject item = element.getAsJsonObject();
                    final String id = Http.string(item, "id");
                    if (id == null) continue;
                    if (item.has("deleted")) {
                        deleted.add(id);
                    } else if (item.has("root")) {
                        rootId = id;
                    } else if (item.has("folder")) {
                        final String name = Http.string(item, "name");
                        final JsonElement parent = item.get("parentReference");
                        final String parentId = parent != null && parent.isJsonObject()
                                ? Http.string(parent.getAsJsonObject(), "id") : null;
                        if (name != null) folders.add(new LibraryNaming.Folder(id, name, parentId));
                    } else {
                        final DriveItem file = DriveItem.fromJson(item);
                        if (file != null && file.isPlayable()) {
                            audio.add(file);
                        } else if (!fullScan) {
                            // Changed into something that is not music (renamed...): leaves the library
                            deleted.add(id);
                        }
                    }
                }
            }

            final String next = Http.string(page, "@odata.nextLink");
            if (next != null) {
                url = next;
                continue;
            }
            final String newDeltaLink = Http.string(page, "@odata.deltaLink");
            final OneDriveLibrary.ScanResult result = library.applyScan(fullScan, rootId, folders, audio, deleted);
            prefs().edit()
                    .putString(KEY_DELTA_LINK, newDeltaLink)
                    .putString(KEY_ROOT_ID, rootId)
                    .putLong(KEY_LAST_SCAN, System.currentTimeMillis())
                    .apply();
            return result;
        }
    }
}
