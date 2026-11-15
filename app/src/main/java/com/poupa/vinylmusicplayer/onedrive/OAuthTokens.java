/*
 * Copyright 2026 Armia Abdurohman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Adapted from Sabangau's cloud sync (com.sabangau.sync.OAuthTokens).
 */
package com.poupa.vinylmusicplayer.onedrive;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/** OAuth tokens of the signed-in OneDrive account. */
final class OAuthTokens {

    /** Used when the token endpoint does not say how long the access token lives. */
    private static final long DEFAULT_LIFETIME_SECONDS = 3600;

    /** Short-lived token sent to Microsoft Graph. */
    @NonNull
    final String accessToken;
    /** Long-lived token used to get new access tokens; rotated by Microsoft. */
    @Nullable
    final String refreshToken;
    /** When {@link #accessToken} stops working. */
    final long expiresAtMillis;
    /** Label of the signed-in account (e-mail address or name), if known. */
    @Nullable
    final String account;

    OAuthTokens(@NonNull String accessToken, @Nullable String refreshToken, long expiresAtMillis, @Nullable String account) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAtMillis = expiresAtMillis;
        this.account = account;
    }

    boolean expiresWithin(long nowMillis, long marginMillis) {
        return expiresAtMillis - marginMillis <= nowMillis;
    }

    /**
     * Parses a successful token endpoint response. Values the response leaves out (the refresh
     * token and the account on refresh) are carried over from {@code previous}.
     */
    @NonNull
    static OAuthTokens fromTokenResponse(@NonNull String json, @Nullable OAuthTokens previous, long nowMillis)
            throws OneDriveException {
        final JsonObject obj = parseObject(json);
        final String access = obj != null ? Http.string(obj, "access_token") : null;
        if (access == null || access.isEmpty()) {
            throw OneDriveException.permanent("Sign-in response contained no access token");
        }
        long lifetime = DEFAULT_LIFETIME_SECONDS;
        final JsonElement expiresIn = obj.get("expires_in");
        if (expiresIn != null && expiresIn.isJsonPrimitive()) {
            try {
                lifetime = Long.parseLong(expiresIn.getAsString().trim());
            } catch (NumberFormatException ignore) {
                // keep the default lifetime
            }
        }
        String refresh = Http.string(obj, "refresh_token");
        if (refresh == null && previous != null) refresh = previous.refreshToken;
        String account = accountFromIdToken(Http.string(obj, "id_token"));
        if (account == null && previous != null) account = previous.account;
        return new OAuthTokens(access, refresh, nowMillis + lifetime * 1000L, account);
    }

    /**
     * Reads the account label from the payload of an OpenID Connect id token. The token is only
     * used for display; it is not verified.
     */
    @Nullable
    static String accountFromIdToken(@Nullable String idToken) {
        if (idToken == null) return null;
        // header.payload.signature
        final int first = idToken.indexOf('.');
        final int second = first < 0 ? -1 : idToken.indexOf('.', first + 1);
        if (second < 0) return null;
        try {
            final byte[] payload = Base64.decode(
                    idToken.substring(first + 1, second),
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            if (payload == null) return null;
            final JsonObject claims = parseObject(new String(payload, StandardCharsets.UTF_8));
            if (claims == null) return null;
            for (final String claim : new String[]{"preferred_username", "email", "name"}) {
                final String value = Http.string(claims, claim);
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
        } catch (IllegalArgumentException e) {
            // not base64url: no label
        }
        return null;
    }

    @NonNull
    String toJson() {
        final JsonObject obj = new JsonObject();
        obj.addProperty("access_token", accessToken);
        obj.addProperty("refresh_token", refreshToken);
        obj.addProperty("expires_at", expiresAtMillis);
        obj.addProperty("account", account);
        return obj.toString();
    }

    /** Reads what {@link #toJson()} wrote; {@code null} when it is unusable. */
    @Nullable
    static OAuthTokens fromJson(@Nullable String json) {
        final JsonObject obj = parseObject(json);
        if (obj == null) return null;
        final String access = Http.string(obj, "access_token");
        final JsonElement expiresAt = obj.get("expires_at");
        if (access == null || expiresAt == null || !expiresAt.isJsonPrimitive()) return null;
        try {
            return new OAuthTokens(
                    access,
                    Http.string(obj, "refresh_token"),
                    expiresAt.getAsLong(),
                    Http.string(obj, "account"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static JsonObject parseObject(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            final JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** Keeps tokens out of logs and crash reports. */
    @NonNull
    @Override
    public String toString() {
        return "OAuthTokens{account=" + account + ", expiresAt=" + expiresAtMillis + "}";
    }
}
