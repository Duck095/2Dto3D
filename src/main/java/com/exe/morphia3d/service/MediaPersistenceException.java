package com.exe.morphia3d.service;

/** Safe persistence failure classification used to decide whether compensation is permitted. */
public final class MediaPersistenceException extends RuntimeException {

    public enum Outcome {
        ROLLED_BACK,
        UNKNOWN
    }

    private final Outcome outcome;

    private MediaPersistenceException(Outcome outcome, Throwable cause) {
        super(outcome == Outcome.UNKNOWN
                ? "The model persistence outcome could not be confirmed"
                : "The model persistence transaction was rolled back", cause);
        this.outcome = outcome;
    }

    public static MediaPersistenceException rolledBack(Throwable cause) {
        return new MediaPersistenceException(Outcome.ROLLED_BACK, cause);
    }

    public static MediaPersistenceException outcomeUnknown(Throwable cause) {
        return new MediaPersistenceException(Outcome.UNKNOWN, cause);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public boolean isSafeToCompensate() {
        return outcome == Outcome.ROLLED_BACK;
    }
}
