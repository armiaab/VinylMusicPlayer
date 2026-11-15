/*
 * Copyright 2026 Armia Abdurohman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Adapted from Sabangau's cloud sync (com.sabangau.sync.TokenStore).
 */
package com.poupa.vinylmusicplayer.onedrive;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the OneDrive tokens encrypted with an AES-GCM key that never leaves the Android Keystore.
 * The preferences file is excluded from backups; a copy restored elsewhere could not be
 * decrypted anyway and simply reads as "signed out".
 */
@RequiresApi(23)
final class TokenStore {

    private static final String TAG = "OneDriveTokenStore";

    /** Excluded from backups, see res/xml/backup_rules.xml. */
    private static final String PREFS = "onedrive_tokens";

    private static final String KEY_TOKENS = "tokens";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEYSTORE_ALIAS = "vinyl_onedrive_token_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private TokenStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Stores the tokens synchronously: Microsoft rotates refresh tokens, so a newly issued one
     * must be on disk before it is used. Never call on the main thread.
     */
    @SuppressLint("ApplySharedPref")
    static void save(@NonNull Context context, @NonNull OAuthTokens tokens) throws OneDriveException {
        try {
            prefs(context).edit().putString(KEY_TOKENS, encrypt(tokens.toJson())).commit();
        } catch (GeneralSecurityException | ProviderException e) {
            throw OneDriveException.permanent("Could not store the OneDrive sign-in securely: " + e);
        }
    }

    /** The stored tokens, or {@code null} when signed out or the stored value is unreadable. */
    @Nullable
    static OAuthTokens load(@NonNull Context context) {
        final String stored = prefs(context).getString(KEY_TOKENS, null);
        if (stored == null) return null;
        try {
            return OAuthTokens.fromJson(decrypt(stored));
        } catch (GeneralSecurityException | IllegalArgumentException | ProviderException e) {
            Log.w(TAG, "Stored OneDrive sign-in is unreadable; treating as signed out", e);
            clear(context);
            return null;
        }
    }

    static boolean hasTokens(@NonNull Context context) {
        return prefs(context).contains(KEY_TOKENS);
    }

    @SuppressLint("ApplySharedPref")
    static void clear(@NonNull Context context) {
        prefs(context).edit().remove(KEY_TOKENS).commit();
    }

    private static String encrypt(String plain) throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        final byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws GeneralSecurityException {
        final int sep = stored.indexOf(':');
        if (sep <= 0) throw new GeneralSecurityException("Malformed token record");
        final byte[] iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP);
        final byte[] sealed = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP);
        final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    }

    private static synchronized SecretKey key() throws GeneralSecurityException {
        final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        try {
            keyStore.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException("Android Keystore unavailable", e);
        }
        final KeyStore.Entry entry = keyStore.getEntry(KEYSTORE_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
        return generator.generateKey();
    }
}
