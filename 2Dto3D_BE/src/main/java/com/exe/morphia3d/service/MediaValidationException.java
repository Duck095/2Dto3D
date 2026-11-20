package com.exe.morphia3d.service;

public class MediaValidationException extends RuntimeException {

    public enum Reason {
        MISSING_FILE,
        TOO_LARGE,
        TOO_MANY_IMAGES,
        UNSUPPORTED_EXTENSION,
        UNSUPPORTED_MIME_TYPE,
        CONTENT_MISMATCH,
        MALFORMED_IMAGE,
        IMAGE_DIMENSIONS_EXCEEDED,
        INVALID_GLB,
        MALFORMED_REQUEST
    }

    private final Reason reason;

    public MediaValidationException(Reason reason) {
        super("Uploaded media failed validation: " + reason.name());
        this.reason = reason;
    }

    public MediaValidationException(Reason reason, Throwable cause) {
        super("Uploaded media failed validation: " + reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
