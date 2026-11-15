/*
 * Copyright 2026 Armia Abdurohman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Adapted from Sabangau's cloud sync (com.sabangau.sync.OneDriveAuth).
 */
package com.poupa.vinylmusicplayer.onedrive;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.poupa.vinylmusicplayer.BuildConfig;

import net.openid.appauth.AppAuthConfiguration;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.browser.BrowserAllowList;
import net.openid.appauth.browser.BrowserMatcher;
import net.openid.appauth.browser.BrowserSelector;
import net.openid.appauth.browser.VersionedBrowserMatcher;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Microsoft account sign-in for OneDrive: OAuth 2.0 authorization code flow with PKCE as a
 * public client (RFC 8252). AppAuth runs the browser part; the token requests are plain HTTPS
 * calls so streaming threads can refresh tokens synchronously.
 */
@RequiresApi(23)
public final class OneDriveAuth {

    /** {@code common} accepts personal Microsoft accounts as well as work and school accounts. */
    private static final String AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0";

    private static final String TOKEN_ENDPOINT = AUTHORITY + "/token";

    /** Vinyl only reads the drive: {@code Files.Read} is the least privilege that lists and downloads. */
    static final String SCOPES = "openid profile offline_access Files.Read";

    /** Refresh a little early so a token does not expire in the middle of a request. */
    private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    /** One refresh at a time: Microsoft rotates refresh tokens, so concurrent refreshes race. */
    private static final Object REFRESH_LOCK = new Object();

    /**
     * Custom Tabs of the browsers AppAuth is tested with. Others may show Microsoft's consent page
     * but not hand the redirect back to the app.
     */
    private static final BrowserMatcher TESTED_BROWSERS = new BrowserAllowList(
            VersionedBrowserMatcher.CHROME_CUSTOM_TAB,
            VersionedBrowserMatcher.SAMSUNG_CUSTOM_TAB,
            VersionedBrowserMatcher.FIREFOX_CUSTOM_TAB);

    private OneDriveAuth() {}

    /** Signs in with a tested browser when the phone has one, otherwise with any browser. */
    @NonNull
    public static AppAuthConfiguration appAuthConfiguration(@NonNull Context context) {
        if (BrowserSelector.select(context, TESTED_BROWSERS) == null) {
            return AppAuthConfiguration.DEFAULT;
        }
        return new AppAuthConfiguration.Builder().setBrowserMatcher(TESTED_BROWSERS).build();
    }

    /** Whether this build carries an Azure client id (see docs/ONEDRIVE.md). */
    public static boolean isConfigured() {
        return !BuildConfig.ONEDRIVE_CLIENT_ID.isEmpty();
    }

    public static boolean isSignedIn(@NonNull Context context) {
        return TokenStore.hasTokens(context);
    }

    /** Label of the signed-in account, if the sign-in carried one. Reads the Keystore: not on the main thread. */
    @Nullable
    public static String account(@NonNull Context context) {
        final OAuthTokens tokens = TokenStore.load(context);
        return tokens != null ? tokens.account : null;
    }

    /**
     * Forgets the tokens on this device. Microsoft offers no per-app revocation for public
     * clients; users can remove the app at account.microsoft.com, Privacy, App access.
     */
    public static void signOut(@NonNull Context context) {
        TokenStore.clear(context);
    }

    /**
     * The redirect URI that Azure's "Android" platform registers for a package name and signature
     * hash: {@code msauth://<package>/<URL-encoded signature hash>}. Each signing key (debug,
     * release) has its own hash, so each needs its own entry in Azure (see docs/ONEDRIVE.md).
     */
    @NonNull
    static Uri redirectUri(@NonNull Context context) {
        return Uri.parse(redirectUri(context.getPackageName(), signatureHash(context)));
    }

    @NonNull
    static String redirectUri(@NonNull String packageName, @NonNull String signatureHash) {
        // The URL encoding of the Base64 characters that need it, as Azure shows the URI
        final String encoded = signatureHash.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D");
        return "msauth://" + packageName + "/" + encoded;
    }

    /** Base64 of the SHA-1 of the certificate this app is signed with: Azure's "signature hash". */
    @NonNull
    static String signatureHash(@NonNull Context context) {
        try {
            final PackageManager pm = context.getPackageManager();
            final Signature[] signers;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final SigningInfo signing = pm.getPackageInfo(
                        context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                if (signing == null) throw new IllegalStateException("No signing certificate");
                signers = signing.hasMultipleSigners()
                        ? signing.getApkContentsSigners()
                        : signing.getSigningCertificateHistory();
            } else {
                @SuppressWarnings("deprecation")
                final PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                final Signature[] legacy = info.signatures;
                signers = legacy;
            }
            if (signers == null || signers.length == 0) throw new IllegalStateException("No signing certificate");
            // Without key rotation the history holds just the current certificate; with it, the
            // last entry is the current one
            return signatureHash(signers[signers.length - 1].toByteArray());
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Own package not found", e);
        }
    }

    @NonNull
    static String signatureHash(@NonNull byte[] certificate) {
        try {
            final byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(certificate);
            return Base64.encodeToString(sha1, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The browser sign-in request; AppAuth adds the PKCE challenge, state and nonce. */
    @NonNull
    public static AuthorizationRequest authorizationRequest(@NonNull Context context) {
        final AuthorizationServiceConfiguration config = new AuthorizationServiceConfiguration(
                Uri.parse(AUTHORITY + "/authorize"), Uri.parse(TOKEN_ENDPOINT));
        return new AuthorizationRequest.Builder(
                config, BuildConfig.ONEDRIVE_CLIENT_ID, ResponseTypeValues.CODE, redirectUri(context))
                .setScope(SCOPES)
                .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
                .build();
    }

    /**
     * Exchanges the authorization code from the browser for tokens and stores them. Blocks;
     * never call on the main thread.
     *
     * @return the signed-in account label, if the id token carried one
     */
    @Nullable
    public static String completeSignIn(@NonNull Context context, @NonNull AuthorizationResponse response)
            throws OneDriveException {
        if (response.authorizationCode == null) {
            throw OneDriveException.permanent("Microsoft sign-in returned no authorization code");
        }
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", BuildConfig.ONEDRIVE_CLIENT_ID);
        form.put("code", response.authorizationCode);
        form.put("redirect_uri", response.request.redirectUri.toString());
        if (response.request.codeVerifier != null) {
            form.put("code_verifier", response.request.codeVerifier);
        }
        form.put("scope", SCOPES);
        synchronized (REFRESH_LOCK) {
            final OAuthTokens tokens = requestTokens(form, null);
            TokenStore.save(context, tokens);
            return tokens.account;
        }
    }

    /**
     * Returns a valid access token, refreshing it when it is about to expire. Blocks; never call
     * on the main thread.
     *
     * @param forceRefresh refresh even if the stored token looks valid (Graph rejected it)
     * @throws OneDriveException {@link OneDriveException.Kind#AUTH} when the user has to sign in again
     */
    @NonNull
    static String accessToken(@NonNull Context context, boolean forceRefresh) throws OneDriveException {
        synchronized (REFRESH_LOCK) {
            final OAuthTokens tokens = TokenStore.load(context);
            if (tokens == null || tokens.refreshToken == null) {
                throw OneDriveException.auth("OneDrive has to be connected again");
            }
            if (!forceRefresh && !tokens.expiresWithin(System.currentTimeMillis(), REFRESH_MARGIN_MS)) {
                return tokens.accessToken;
            }
            final Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("client_id", BuildConfig.ONEDRIVE_CLIENT_ID);
            form.put("refresh_token", tokens.refreshToken);
            form.put("scope", SCOPES);
            final OAuthTokens fresh = requestTokens(form, tokens);
            // Microsoft rotates refresh tokens: always keep the newest one
            TokenStore.save(context, fresh);
            return fresh.accessToken;
        }
    }

    private static OAuthTokens requestTokens(Map<String, String> form, @Nullable OAuthTokens previous)
            throws OneDriveException {
        final Http.Response r;
        try {
            r = Http.send("POST", TOKEN_ENDPOINT, null, Http.FORM, Http.form(form));
        } catch (IOException e) {
            throw OneDriveException.transientError("Microsoft sign-in: " + e.getMessage(), e);
        }
        if (r.ok()) return OAuthTokens.fromTokenResponse(r.body, previous, System.currentTimeMillis());
        if (r.code == 400 || r.code == 401) {
            // invalid_grant (expired or revoked), interaction_required, consent_required, ...
            final String detail = Http.errorMessage(r.body);
            throw OneDriveException.auth("Microsoft sign-in was rejected" + (detail != null ? ": " + detail : ""));
        }
        throw OneDriveException.fromHttp("Microsoft sign-in", r);
    }
}
