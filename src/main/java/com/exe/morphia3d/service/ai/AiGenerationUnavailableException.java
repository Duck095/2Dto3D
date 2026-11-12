package com.exe.morphia3d.service.ai;

public class AiGenerationUnavailableException extends RuntimeException {
    private final String code;

    public AiGenerationUnavailableException(String code) {
        super("AI generation is unavailable");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
