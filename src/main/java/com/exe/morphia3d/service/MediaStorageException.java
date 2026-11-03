package com.exe.morphia3d.service;

public class MediaStorageException extends RuntimeException {

    public enum Reason {
        PROVIDER_UNAVAILABLE,
        INVALID_PROVIDER_RESPONSE,
        IDENTITY_MISMATCH,
        UNSUPPORTED_IDENTITY,
        INPUT_STREAM_FAILURE
    }

    private final Reason reason;

    public MediaStorageException(Reason reason) {
        super(safeMessage(reason));
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    private static String safeMessage(Reason reason) {
        if (reason == Reason.PROVIDER_UNAVAILABLE) {
            return "Media provider is temporarily unavailable";
        }
        if (reason == Reason.INVALID_PROVIDER_RESPONSE) {
            return "Media provider returned an invalid response";
        }
        if (reason == Reason.IDENTITY_MISMATCH) {
            return "Media provider response did not match the requested asset identity";
        }
        if (reason == Reason.UNSUPPORTED_IDENTITY) {
            return "Stored media identity is not valid for this provider operation";
        }
        return "Media input could not be read";
    }
}
