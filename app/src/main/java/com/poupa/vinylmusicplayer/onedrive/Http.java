/*
 * Copyright 2026 Armia Abdurohman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Adapted from Sabangau's cloud sync (com.sabangau.sync.Http).
 */
package com.poupa.vinylmusicplayer.onedrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.poupa.vinylmusicplayer.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

/**
 * Minimal blocking HTTPS client for Microsoft Graph and the OAuth token endpoint.
 * Uses {@link HttpURLConnection} so OneDrive adds no HTTP library to the app.
 */
final class Http {

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Largest body read: a library scan page of 1000 items is about 1 MiB. */
    private static final int MAX_BODY_BYTES = 16 << 20;

    private static final String USER_AGENT = "VinylMusicPlayer/" + BuildConfig.VERSION_NAME;

    static final String FORM = "application/x-www-form-urlencoded";

    /** A finished request: status, body text and the {@code Location} header (redirects). */
    static final class Response {
        final int code;
        @NonNull
        final String body;
        @Nullable
        final String location;

        Response(int code, @NonNull String body, @Nullable String location) {
            this.code = code;
            this.body = body;
            this.location = location;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private Http() {}

    /**
     * Sends a request with an optional in-memory body. Redirects are not followed, so a
     * {@code 302} comes back with its {@link Response#location}.
     *
     * @param bearer OAuth access token, or {@code null} for unauthenticated requests
     */
    @NonNull
    static Response send(
            @NonNull String method,
            @NonNull String url,
            @Nullable String bearer,
            @Nullable String contentType,
            @Nullable byte[] body)
            throws IOException {
        final HttpURLConnection conn = open(method, url, bearer, null);
        try {
            if (body != null) {
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            return read(conn);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Opens a ranged GET of a pre-authenticated download URL. The caller reads the body and
     * disconnects.
     *
     * @param endInclusive last byte wanted, or a negative value for "until the end"
     */
    @NonNull
    static HttpURLConnection openRange(@NonNull String url, long start, long endInclusive) throws IOException {
        final String range = "bytes=" + start + "-" + (endInclusive >= 0 ? String.valueOf(endInclusive) : "");
        final HttpURLConnection conn = open("GET", url, null, Map.of("Range", range));
        // Audio must arrive byte-exact: never let a proxy transcode or compress it
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        return conn;
    }

    private static HttpURLConnection open(
            String method, String url, @Nullable String bearer, @Nullable Map<String, String> headers)
            throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if (headers != null) {
            for (final Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
        }
        return conn;
    }

    private static Response read(HttpURLConnection conn) throws IOException {
        final int code = conn.getResponseCode();
        final InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = "";
        if (in != null) {
            try (InputStream stream = in) {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                final byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = stream.read(buf)) > 0 && bytes.size() < MAX_BODY_BYTES) {
                    bytes.write(buf, 0, n);
                }
                body = bytes.toString("UTF-8");
            }
        }
        return new Response(code, body, conn.getHeaderField("Location"));
    }

    /** Encodes {@code fields} as an {@code application/x-www-form-urlencoded} body. */
    @NonNull
    static byte[] form(@NonNull Map<String, String> fields) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> f : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(f.getKey())).append('=').append(urlEncode(f.getValue()));
        }
        try {
            return sb.toString().getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 is always supported
        }
    }

    @NonNull
    static String urlEncode(@NonNull String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 is always supported
        }
    }

    /** Encodes one path segment ({@code +} is a literal plus in paths, so spaces become %20). */
    @NonNull
    static String encodePathSegment(@NonNull String segment) {
        return urlEncode(segment).replace("+", "%20");
    }

    /** Parses a JSON object body; a malformed body means the server misbehaved, so retry later. */
    @NonNull
    static JsonObject json(@NonNull String what, @NonNull Response response) throws OneDriveException {
        try {
            final JsonElement el = JsonParser.parseString(response.body);
            if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw OneDriveException.transientError(what + ": unreadable response", e);
        }
        throw OneDriveException.transientError(what + ": unexpected response", null);
    }

    /** A string member of {@code obj}, or {@code null} when absent or not a string. */
    @Nullable
    static String string(@Nullable JsonObject obj, @NonNull String member) {
        if (obj == null) return null;
        final JsonElement el = obj.get(member);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /** A numeric member of {@code obj}, or {@code fallback} when absent or not a number. */
    static long number(@Nullable JsonObject obj, @NonNull String member, long fallback) {
        if (obj == null) return fallback;
        final JsonElement el = obj.get(member);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Extracts a human-readable message from a Microsoft Graph or OAuth error body, or {@code
     * null} when there is none.
     */
    @Nullable
    static String errorMessage(@Nullable String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            final JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) return null;
            final JsonObject obj = el.getAsJsonObject();
            final JsonElement error = obj.get("error");
            String message = null;
            if (error != null && error.isJsonObject()) {
                // Graph: {"error":{"code":...,"message":...}}
                message = string(error.getAsJsonObject(), "message");
                if (message == null) message = string(error.getAsJsonObject(), "code");
            } else if (error != null && error.isJsonPrimitive()) {
                // OAuth: {"error":"invalid_grant","error_description":...}
                message = string(obj, "error_description");
                if (message == null) message = error.getAsString();
            }
            if (message == null) return null;
            message = message.trim();
            final int newline = message.indexOf('\n');
            if (newline > 0) message = message.substring(0, newline).trim();
            return message.length() > 200 ? message.substring(0, 200) + "…" : message;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }
}
