package com.exe.morphia3d.service.payment.provider;

import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.PaymentProviderRequest;
import com.exe.morphia3d.service.PaymentProviderSubmissionResult;

import java.util.Set;

/** Not component-scanned: explicit wiring requires separate merchant-specific approval. */
public final class VnPayPaymentAttemptGateway implements PaymentAttemptGateway {
    private final String endpoint;
    private final String tmnCode;
    private final String hashSecret;
    private final Set<String> allowedRedirectHosts;
    private final Set<String> allowedReturnHosts;
    private final MetadataSource metadataSource;

    public VnPayPaymentAttemptGateway(String endpoint, String tmnCode, String hashSecret,
                                      Set<String> allowedRedirectHosts, Set<String> allowedReturnHosts,
                                      MetadataSource metadataSource) {
        this.endpoint = endpoint; this.tmnCode = tmnCode; this.hashSecret = hashSecret;
        this.allowedRedirectHosts = allowedRedirectHosts == null ? Set.of() : Set.copyOf(allowedRedirectHosts);
        this.allowedReturnHosts = allowedReturnHosts == null ? Set.of() : Set.copyOf(allowedReturnHosts);
        this.metadataSource = metadataSource;
    }

    @Override public PaymentProvider provider() { return PaymentProvider.VNPAY; }
    @Override public boolean isAvailable() {
        return RedirectUrlPolicy.allowedHttps(endpoint, allowedRedirectHosts) && tmnCode != null && !tmnCode.isBlank()
                && hashSecret != null && !hashSecret.isBlank() && metadataSource != null;
    }

    @Override public PaymentProviderSubmissionResult submit(PaymentProviderRequest request) {
        if (!isAvailable() || request == null || request.attemptId() == null
                || !ProviderReference.forAttempt(PaymentProvider.VNPAY, request.attemptId()).equals(request.merchantReference())) {
            return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.REJECTED,
                    null, null, null, "PROVIDER_REQUEST_INVALID", "Provider request is not compatible with the verified protocol");
        }
        Metadata metadata = metadataSource.forRequest(request);
        if (metadata == null || !RedirectUrlPolicy.allowedHttps(metadata.returnUrl(), allowedReturnHosts)) {
            return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.REJECTED,
                    null, null, null, "RETURN_URL_UNSAFE", "Configured merchant return URL is not allowlisted");
        }
        String url = VnPayProtocol.createPaymentUrl(endpoint, hashSecret, VnPayProtocol.PaymentFields.builder()
                .tmnCode(tmnCode).txnRef(request.merchantReference()).amountVnd(request.amountVnd()).currency("VND")
                .orderInfo(metadata.orderInfo()).orderType(metadata.orderType()).locale(metadata.locale())
                .returnUrl(metadata.returnUrl()).ipAddress(metadata.ipAddress()).createDate(metadata.createDate())
                .expireDate(metadata.expireDate()).build());
        return new PaymentProviderSubmissionResult(PaymentProviderSubmissionResult.Outcome.ACCEPTED,
                request.merchantReference(), null, url, null, null);
    }

    @FunctionalInterface public interface MetadataSource { Metadata forRequest(PaymentProviderRequest request); }
    public record Metadata(String orderInfo, String orderType, String locale, String returnUrl,
                           String ipAddress, String createDate, String expireDate) {}
}
