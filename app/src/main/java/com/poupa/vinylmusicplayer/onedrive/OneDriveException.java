/*
 * Copyright 2026 Armia Abdurohman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Adapted from Sabangau's cloud sync (com.sabangau.sync.SyncException).
 */
package com.poupa.vinylmusicplayer.onedrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** A failed OneDrive request, classified by what the caller should do about it. */
public final class OneDriveException extends Exception {

    public enum Kind {
        /** Network trouble, server errors, rate limits: try again later. */
        TRANSIENT,
        /** The user has to sign in again. */
        AUTH,
        /** Retrying will not help (bad request, file gone). */
        PERMANENT
    }

    @NonNull
    public final Kind kind;

    /** HTTP status of the failed request, or 0 when the failure was not an HTTP response. */
    public final int httpStatus;

    OneDriveException(@NonNull Kind kind, int httpStatus, @NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    static OneDriveException transientError(@NonNull String message, @Nullable Throwable cause) {
        return new OneDriveException(Kind.TRANSIENT, 0, message, cause);
    }

    static OneDriveException auth(@NonNull String message) {
        return new OneDriveException(Kind.AUTH, 0, message, null);
    }

    static OneDriveException permanent(@NonNull String message) {
        return new OneDriveException(Kind.PERMANENT, 0, message, null);
    }

    /** Classifies an unexpected HTTP response of Microsoft Graph or the token endpoint. */
    static OneDriveException fromHttp(@NonNull String what, @NonNull Http.Response response) {
        final int code = response.code;
        final String detail = Http.errorMessage(response.body);
        final String message = what + " failed (HTTP " + code + ")" + (detail != null ? ": " + detail : "");
        return new OneDriveException(classify(code), code, message, null);
    }

    static Kind classify(int code) {
        if (code == 401) return Kind.AUTH;
        if (code == 408 || code == 429 || code >= 500) return Kind.TRANSIENT;
        return Kind.PERMANENT;
    }
}
