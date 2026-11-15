package com.poupa.vinylmusicplayer.onedrive;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Read-only Microsoft Graph calls: list folders and resolve download URLs. */
@RequiresApi(23)
public final class OneDriveClient {

    private static final String DRIVE = "https://graph.microsoft.com/v1.0/me/drive";

    /** Only what the browser and the player need, to keep responses small. */
    private static final String SELECT = "id,name,size,folder,file,audio,video,cTag,lastModifiedDateTime";

    /** Graph's largest page for children listings. */
    private static final int PAGE_SIZE = 200;

    /** Bounds a listing so a huge folder cannot exhaust memory. */
    private static final int MAX_ITEMS = 10_000;

    private final Context context;

    public OneDriveClient(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Folders and playable audio files directly inside a folder; other files are left out.
     *
     * @param folderId the folder, or {@code null} for the drive root
     */
    @NonNull
    public List<DriveItem> listChildren(@Nullable String folderId) throws OneDriveException {
        final String base = folderId == null
                ? DRIVE + "/root"
                : DRIVE + "/items/" + Http.encodePathSegment(folderId);
        String url = base + "/children?$select=" + SELECT + "&$top=" + PAGE_SIZE;

        final List<DriveItem> items = new ArrayList<>();
        int seen = 0;
        while (url != null && seen < MAX_ITEMS) {
            final String pageUrl = url;
            final Http.Response r = execute("List folder", token -> Http.send("GET", pageUrl, token, null, null));
            if (!r.ok()) throw OneDriveException.fromHttp("List folder", r);
            final JsonObject page = Http.json("List folder", r);
            final JsonElement value = page.get("value");
            if (value != null && value.isJsonArray()) {
                final JsonArray array = value.getAsJsonArray();
                for (final JsonElement el : array) {
                    seen++;
                    if (!el.isJsonObject()) continue;
                    final DriveItem item = DriveItem.fromJson(el.getAsJsonObject());
                    if (item != null && (item.isFolder || item.isPlayable())) items.add(item);
                }
            }
            url = Http.string(page, "@odata.nextLink");
        }
        return items;
    }

    /**
     * A short-lived pre-authenticated URL of the file content. It accepts Range requests and must
     * not be sent the access token.
     */
    @NonNull
    String downloadUrl(@NonNull String itemId) throws OneDriveException {
        final String url = DRIVE + "/items/" + Http.encodePathSegment(itemId) + "/content";
        final Http.Response r = execute("Download", token -> Http.send("GET", url, token, null, null));
        if ((r.code == 302 || r.code == 303 || r.code == 307) && r.location != null) {
            return r.location;
        }
        if (r.code == 404) throw OneDriveException.permanent("The file is no longer on OneDrive");
        throw OneDriveException.fromHttp("Download", r);
    }

    /** A delta link expired: the drive has to be listed again from the start. */
    static final class ResyncRequiredException extends Exception {
        ResyncRequiredException() {
            super("OneDrive asked for a full rescan");
        }
    }

    /** {@code GET} of a Graph page (delta listing); retries temporary failures with backoff. */
    @NonNull
    JsonObject getPage(@NonNull String url) throws OneDriveException, ResyncRequiredException {
        for (int attempt = 1; ; attempt++) {
            try {
                final Http.Response r = execute("Library scan", token -> Http.send("GET", url, token, null, null));
                if (r.ok()) return Http.json("Library scan", r);
                if (r.code == 410) throw new ResyncRequiredException();
                throw OneDriveException.fromHttp("Library scan", r);
            } catch (OneDriveException e) {
                if (e.kind != OneDriveException.Kind.TRANSIENT || attempt >= 4) throw e;
                try {
                    Thread.sleep(1000L << attempt); // 2, 4, 8 s
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    static String driveUrl() {
        return DRIVE;
    }

    /**
     * Up to {@code length} bytes at {@code offset} of a pre-authenticated download URL (fewer at
     * the end of the file). Used to read headers without downloading the file.
     */
    @NonNull
    static byte[] readRange(@NonNull String downloadUrl, long offset, int length) throws IOException {
        final java.net.HttpURLConnection conn = Http.openRange(downloadUrl, offset, offset + length - 1);
        try {
            final int code = conn.getResponseCode();
            if (code != 206 && !(code == 200 && offset == 0)) {
                // 200 for a later range means the server ignores ranges: do not download it all
                throw new IOException("Range read failed (HTTP " + code + ")");
            }
            final byte[] buffer = new byte[length];
            int total = 0;
            try (java.io.InputStream in = conn.getInputStream()) {
                int n;
                while (total < length && (n = in.read(buffer, total, length - total)) > 0) total += n;
            }
            if (total == length) return buffer;
            final byte[] shorter = new byte[total];
            System.arraycopy(buffer, 0, shorter, 0, total);
            return shorter;
        } finally {
            conn.disconnect();
        }
    }

    private interface AuthorizedCall {
        Http.Response run(String accessToken) throws IOException;
    }

    /** Sends {@code call}; when Graph rejects the token, refreshes it once and sends it again. */
    private Http.Response execute(String what, AuthorizedCall call) throws OneDriveException {
        try {
            Http.Response r = call.run(OneDriveAuth.accessToken(context, false));
            if (r.code == 401) r = call.run(OneDriveAuth.accessToken(context, true));
            return r;
        } catch (IOException e) {
            throw OneDriveException.transientError(what + ": " + e.getMessage(), e);
        }
    }
}
