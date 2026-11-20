package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.dto.response.PaymentAttemptResponse;
import com.exe.morphia3d.dto.response.PaymentReadinessResponse;
import com.exe.morphia3d.dto.response.PayosPaymentInfoResponse;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.service.PaymentApiException;
import com.exe.morphia3d.service.PaymentAttemptCreationResult;
import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.PaymentAttemptLifecycleService;
import com.exe.morphia3d.service.PaymentAttemptPersistenceService;
import com.exe.morphia3d.service.PaymentReadinessService;
import com.exe.morphia3d.service.PaymentService;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.PayosWebhookProcessingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PaymentServiceImpl implements PaymentService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[!-~]{1,128}$");
    private static final List<PaymentAttemptStatus> RETRYABLE = List.of(
            PaymentAttemptStatus.FAILED, PaymentAttemptStatus.CANCELED, PaymentAttemptStatus.EXPIRED);

    private final FeatureGate featureGate;
    private final PaymentReadinessService readinessService;
    private final PaymentAttemptPersistenceService persistenceService;
    private final PaymentAttemptLifecycleService lifecycleService;
    private final Map<PaymentProvider, PaymentAttemptGateway> gateways;
    private final PayosPaymentPersistenceService payosPersistenceService;
    private final PayosWebhookProcessingService payosWebhookProcessingService;

    @Autowired
    public PaymentServiceImpl(
            FeatureGate featureGate,
            PaymentReadinessService readinessService,
            PaymentAttemptPersistenceService persistenceService,
            PaymentAttemptLifecycleService lifecycleService,
            List<PaymentAttemptGateway> gateways,
            ObjectProvider<PayosPaymentPersistenceService> payosPersistenceService,
            ObjectProvider<PayosWebhookProcessingService> payosWebhookProcessingService) {
        this(featureGate, readinessService, persistenceService, lifecycleService, gateways,
                payosPersistenceService.getIfAvailable(), payosWebhookProcessingService.getIfAvailable());
    }

    public PaymentServiceImpl(FeatureGate featureGate, PaymentReadinessService readinessService,
            PaymentAttemptPersistenceService persistenceService, PaymentAttemptLifecycleService lifecycleService,
            List<PaymentAttemptGateway> gateways) {
        this(featureGate, readinessService, persistenceService, lifecycleService, gateways, (PayosPaymentPersistenceService) null);
    }

    public PaymentServiceImpl(FeatureGate featureGate, PaymentReadinessService readinessService,
            PaymentAttemptPersistenceService persistenceService, PaymentAttemptLifecycleService lifecycleService,
            List<PaymentAttemptGateway> gateways, PayosPaymentPersistenceService payosPersistenceService) {
        this(featureGate, readinessService, persistenceService, lifecycleService, gateways, payosPersistenceService, null);
    }

    public PaymentServiceImpl(FeatureGate featureGate, PaymentReadinessService readinessService,
            PaymentAttemptPersistenceService persistenceService, PaymentAttemptLifecycleService lifecycleService,
            List<PaymentAttemptGateway> gateways, PayosPaymentPersistenceService payosPersistenceService,
            PayosWebhookProcessingService payosWebhookProcessingService) {
        this.featureGate = featureGate;
        this.readinessService = readinessService;
        this.persistenceService = persistenceService;
        this.lifecycleService = lifecycleService;
        this.payosPersistenceService = payosPersistenceService;
        this.payosWebhookProcessingService = payosWebhookProcessingService;
        EnumMap<PaymentProvider, PaymentAttemptGateway> providers = new EnumMap<>(PaymentProvider.class);
        gateways.forEach(gateway -> providers.put(gateway.provider(), gateway));
        this.gateways = Map.copyOf(providers);
    }

    @Override
    public PaymentReadinessResponse getReadiness(Integer authenticatedUserId, Integer orderId) {
        requirePaymentEnabled();
        if (orderId == null || orderId < 1) throw badRequest("ORDER_ID_INVALID", "A valid Order ID is required");
        return readinessService.getReadiness(authenticatedUserId, orderId);
    }

    @Override
    public PaymentAttemptResponse createAttempt(
            Integer authenticatedUserId,
            CreatePaymentAttemptRequest request,
            String idempotencyKey) {
        requirePaymentEnabled();
        if (request == null || request.orderId() == null || request.orderId() < 1 || request.method() == null) {
            throw badRequest("PAYMENT_REQUEST_INVALID", "Order ID and provider are required");
        }
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw badRequest("IDEMPOTENCY_KEY_INVALID", "A printable Idempotency-Key of 1 to 128 characters is required");
        }

        PaymentProvider provider = switch (request.method()) {
            case BANK_TRANSFER -> PaymentProvider.PAYOS;
            case VNPAY, MOMO -> throw badRequest("PAYMENT_METHOD_RETIRED", "Only BANK_TRANSFER is accepted for new payment attempts");
        };
        PaymentAttemptGateway gateway = gateways.get(provider);
        PaymentAttemptCreationResult creation = persistenceService.createOrReplay(authenticatedUserId, request,
                idempotencyKey, gateway != null && gateway.isAvailable());
        if (!creation.created()) {
            return currentResponse(authenticatedUserId, lifecycleService.response(creation.attempt(), null));
        }
        PaymentAttemptResponse dispatched = lifecycleService.dispatchNewAttempt(creation.attempt().getAttemptId(), gateway);
        return currentResponse(authenticatedUserId, dispatched);
    }

    @Override
    public PaymentAttemptResponse getAttempt(Integer authenticatedUserId, UUID attemptId) {
        requirePaymentEnabled();
        PaymentAttempt attempt = persistenceService.getOwnedAttempt(authenticatedUserId, attemptId);
        if (attempt.getProvider() == PaymentProvider.PAYOS && payosWebhookProcessingService != null)
            payosWebhookProcessingService.applyDurableMatchedReceipts(attemptId);
        attempt = persistenceService.getOwnedAttempt(authenticatedUserId, attemptId);
        PaymentReadinessResponse readiness = readinessService.getReadiness(authenticatedUserId, attempt.getOrderId());
        boolean retryAllowed = RETRYABLE.contains(attempt.getStatus()) && readiness.eligible()
                && Boolean.TRUE.equals(readiness.providerAvailability().get(attempt.getProvider()));
        return enrichPayos(authenticatedUserId, lifecycleService.response(attempt, retryAllowed, null));
    }

    private PaymentAttemptResponse currentResponse(Integer ownerId, PaymentAttemptResponse response) {
        if (response.provider() != PaymentProvider.PAYOS || payosPersistenceService == null) return response;
        if (payosWebhookProcessingService != null)
            payosWebhookProcessingService.applyDurableMatchedReceipts(response.attemptId());
        PaymentAttempt attempt = persistenceService.getOwnedAttempt(ownerId, response.attemptId());
        return enrichPayos(ownerId, lifecycleService.response(attempt, false, null));
    }

    private PaymentAttemptResponse enrichPayos(Integer ownerId, PaymentAttemptResponse response) {
        if (payosPersistenceService == null || response.provider() != PaymentProvider.PAYOS
                || response.status() == PaymentAttemptStatus.SUCCEEDED) return response;
        var link = payosPersistenceService.readOwned(response.attemptId(), ownerId);
        PayosPaymentInfoResponse payos = new PayosPaymentInfoResponse(link.orderCode(), link.paymentLinkId(),
                link.checkoutUrl(), link.qrData(), link.expiresAt(), link.state(), link.provenance(),
                !"LIVE".equals(link.provenance()));
        return new PaymentAttemptResponse(response.attemptId(), response.orderId(), response.provider(),
                response.amountVnd(), response.status(), response.retryAllowed(), response.redirectUrl(),
                response.errorCode(), payos);
    }

    private void requirePaymentEnabled() {
        featureGate.requireEnabled(FeatureFamily.PAYMENT);
    }

    private PaymentApiException badRequest(String code, String message) {
        return new PaymentApiException(400, code, message);
    }
}
