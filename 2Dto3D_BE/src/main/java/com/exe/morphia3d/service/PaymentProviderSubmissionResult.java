package com.exe.morphia3d.service;

public record PaymentProviderSubmissionResult(
        Outcome outcome,
        String providerRequestId,
        String providerTransactionId,
        String redirectUrl,
        String errorCode,
        String safeErrorMessage) {
    public enum Outcome { ACCEPTED, REJECTED, UNKNOWN }
}
