package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.entity.PayosWebhookReceipt;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.payment.payos.PayosProtocol;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PayosWebhookProcessingServiceTest {
    private static final String CHANNEL = "test-channel";
    private static final String KEY = "test-checksum-key";

    @Test
    void onlyVerifiedMatchedPaidReceiptIsPassedIntoLifecycle() {
        UUID attemptId = UUID.randomUUID();
        PayosPaymentPersistenceService persistence = mock(PayosPaymentPersistenceService.class);
        PaymentAttemptPersistenceService attempts = mock(PaymentAttemptPersistenceService.class);
        PaymentAttemptLifecycleService lifecycle = mock(PaymentAttemptLifecycleService.class);
        when(persistence.recordVerifiedWebhook(any())).thenReturn(receipt(attemptId, "MATCHED"));
        when(attempts.getAttemptForLifecycle(attemptId)).thenReturn(PaymentAttempt.builder()
                .attemptId(attemptId).orderId(81).provider(PaymentProvider.PAYOS).amountVnd(125000L)
                .merchantReference("payos-reference-81").status(PaymentAttemptStatus.PENDING).build());
        when(lifecycle.applyVerifiedEvent(any())).thenAnswer(call -> {
            VerifiedPaymentEvent event = call.getArgument(0);
            assertThat(event.provider()).isEqualTo(PaymentProvider.PAYOS);
            assertThat(event.attemptId()).isEqualTo(attemptId);
            assertThat(event.amountVnd()).isEqualTo(125000L);
            return PaymentAttempt.builder().attemptId(attemptId).status(PaymentAttemptStatus.SUCCEEDED).build();
        });
        PayosWebhookProcessingService service = service(persistence, attempts, lifecycle);

        var result = service.process(CHANNEL, paidPayload());

        assertThat(result.disposition()).isEqualTo("MATCHED");
        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        verify(persistence).recordVerifiedWebhook(any(PayosProtocol.VerifiedWebhook.class));
        verify(lifecycle).applyVerifiedEvent(any(VerifiedPaymentEvent.class));
    }

    @Test
    void signedPendingReceiptIsDurableButNeverSettles() {
        PayosPaymentPersistenceService persistence = mock(PayosPaymentPersistenceService.class);
        PaymentAttemptPersistenceService attempts = mock(PaymentAttemptPersistenceService.class);
        PaymentAttemptLifecycleService lifecycle = mock(PaymentAttemptLifecycleService.class);
        when(persistence.recordVerifiedWebhook(any())).thenReturn(receipt(null, "RECONCILIATION_REQUIRED"));

        var result = service(persistence, attempts, lifecycle).process(CHANNEL, payload("01"));

        assertThat(result.disposition()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.paymentStatus()).isNull();
        verifyNoInteractions(attempts, lifecycle);
    }

    @Test
    void invalidSignatureNeverPersistsReceiptOrSettles() {
        PayosPaymentPersistenceService persistence = mock(PayosPaymentPersistenceService.class);
        PaymentAttemptPersistenceService attempts = mock(PaymentAttemptPersistenceService.class);
        PaymentAttemptLifecycleService lifecycle = mock(PaymentAttemptLifecycleService.class);
        String invalid = paidPayload().replace("\"signature\":\"", "\"signature\":\"0");

        assertThatThrownBy(() -> service(persistence, attempts, lifecycle).process(CHANNEL, invalid))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(persistence, attempts, lifecycle);
    }

    private PayosWebhookProcessingService service(PayosPaymentPersistenceService persistence,
            PaymentAttemptPersistenceService attempts, PaymentAttemptLifecycleService lifecycle) {
        return new PayosWebhookProcessingService(Map.of(CHANNEL, KEY), persistence, attempts, lifecycle);
    }

    private PayosWebhookReceipt receipt(UUID attemptId, String disposition) {
        return PayosWebhookReceipt.builder().receiptId(UUID.randomUUID()).attemptId(attemptId)
                .merchantChannelId(CHANNEL).bankReference("bank-ref-001").eventDigest("a".repeat(64))
                .orderCode(991L).paymentLinkId("link-identifier-01").amountVnd(125000L)
                .providerStatus("MATCHED".equals(disposition) ? "PAID" : "01")
                .currency("VND").signatureVerified(true).signatureVerifiedAt(java.time.Instant.now())
                .disposition(disposition).build();
    }

    private String paidPayload() { return payload("00"); }

    private String payload(String code) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountNumber", "123456789");
        data.put("amount", "125000");
        data.put("code", code);
        data.put("currency", "VND");
        data.put("description", "2DTO3D001");
        data.put("orderCode", "991");
        data.put("paymentLinkId", "link-identifier-01");
        data.put("reference", "bank-ref-001");
        String signature = PayosProtocol.signData(data, KEY);
        return "{\"success\":true,\"code\":\"00\",\"data\":{" +
                "\"accountNumber\":\"123456789\",\"amount\":125000,\"code\":\"" + code + "\"," +
                "\"currency\":\"VND\",\"description\":\"2DTO3D001\",\"orderCode\":991," +
                "\"paymentLinkId\":\"link-identifier-01\",\"reference\":\"bank-ref-001\"}," +
                "\"signature\":\"" + signature + "\"}";
    }
}
