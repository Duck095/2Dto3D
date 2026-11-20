package com.exe.morphia3d.service.ai;

public class AiGenerationException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public AiGenerationException(String code) {
        this(code, false);
    }

    public AiGenerationException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
}
