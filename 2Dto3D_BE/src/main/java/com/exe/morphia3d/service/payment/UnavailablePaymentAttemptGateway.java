package com.exe.morphia3d.service.payment;

import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.PaymentProviderRequest;
import com.exe.morphia3d.service.PaymentProviderSubmissionResult;
import org.springframework.http.HttpStatus;

public final class UnavailablePaymentAttemptGateway implements PaymentAttemptGateway {
    private final PaymentProvider provider;

    public UnavailablePaymentAttemptGateway(PaymentProvider provider) {
        this.provider = provider;
    }

    @Override public PaymentProvider provider() { return provider; }
    @Override public boolean isAvailable() { return false; }

    @Override
    public PaymentProviderSubmissionResult submit(PaymentProviderRequest request) {
        throw new IllegalStateException("Payment provider adapter is not authorized or available");
    }
}
