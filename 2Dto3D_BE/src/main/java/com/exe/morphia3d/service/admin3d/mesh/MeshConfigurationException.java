package com.exe.morphia3d.service.admin3d.mesh;

/** Domain failures for the future Admin 3D HTTP boundary. */
public abstract class MeshConfigurationException extends RuntimeException {
    private final int statusCode;

    protected MeshConfigurationException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    protected MeshConfigurationException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }

    public static final class NotFound extends MeshConfigurationException {
        public NotFound(String message) { super(404, message); }
    }

    public static final class MaterialNotFound extends MeshConfigurationException {
        public MaterialNotFound(String message) { super(422, message); }
        public MaterialNotFound(String message, Throwable cause) { super(422, message, cause); }
    }

    public static final class Conflict extends MeshConfigurationException {
        public Conflict(String message) { super(409, message); }
        public Conflict(String message, Throwable cause) { super(409, message, cause); }
    }

    public static final class Invalid extends MeshConfigurationException {
        public Invalid(String message) { super(422, message); }
        public Invalid(String message, Throwable cause) { super(422, message, cause); }
    }

    public static final class PersistenceFailure extends MeshConfigurationException {
        public PersistenceFailure(String message, Throwable cause) { super(500, message, cause); }
    }
}
