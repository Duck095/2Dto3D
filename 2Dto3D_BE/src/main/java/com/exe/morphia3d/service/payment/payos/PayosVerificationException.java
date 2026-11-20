package com.exe.morphia3d.service.payment.payos;

public final class PayosVerificationException extends RuntimeException {
    private final String code;
    public PayosVerificationException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
