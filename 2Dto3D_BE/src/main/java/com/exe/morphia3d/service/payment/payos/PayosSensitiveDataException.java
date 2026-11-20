package com.exe.morphia3d.service.payment.payos;

public final class PayosSensitiveDataException extends RuntimeException {
    private final String code;

    public PayosSensitiveDataException(String code) { super(code); this.code = code; }
    public PayosSensitiveDataException(String code, Throwable cause) { super(code, cause); this.code = code; }
    public String code() { return code; }
}
