package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.PaymentProvider;

public interface PaymentAttemptGateway {
    PaymentProvider provider();
    boolean isAvailable();
    /** True when the adapter owns a provider-specific durable one-shot claim. */
    default boolean managesSubmissionClaim() { return false; }
    PaymentProviderSubmissionResult submit(PaymentProviderRequest request);
}
