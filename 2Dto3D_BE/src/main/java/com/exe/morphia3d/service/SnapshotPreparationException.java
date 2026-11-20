package com.exe.morphia3d.service;

/** Safe-to-handle failure while preparing trusted snapshot upload input. */
public final class SnapshotPreparationException extends RuntimeException {
    public SnapshotPreparationException(String message, Throwable cause) {
        super(message, cause);
    }
}
