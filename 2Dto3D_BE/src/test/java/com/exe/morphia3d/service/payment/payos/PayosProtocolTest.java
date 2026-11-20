package com.exe.morphia3d.service.payment.payos;

import com.exe.morphia3d.service.payment.provider.ProviderPaymentState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayosProtocolTest {
    private static final String CHECKSUM = "1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675";
    private static final UUID ATTEMPT = UUID.fromString("f5ae8520-5472-4bc6-a6d4-c99cc86092ee");

    @Test
    void createSignatureUsesDocumentedSortedFieldsAndExactVnd() {
        var request = PayosProtocol.createPaymentRequest(123L, 135_000L, "D3F9", "https://shop.example/cancel",
                "https://shop.example/return", Instant.parse("2026-11-13T12:00:00Z"), CHECKSUM);

        assertThat(request.body()).containsEntry("orderCode", 123L).containsEntry("amount", 135_000L)
                .containsEntry("description", "D3F9").containsEntry("expiredAt", 1_794_571_200L);
        assertThat(request.canonical()).isEqualTo("amount=135000&cancelUrl=https://shop.example/cancel"
                + "&description=D3F9&orderCode=123&returnUrl=https://shop.example/return");
        assertThat(PayosProtocol.verifySignature(request.canonical(), CHECKSUM, request.signature())).isTrue();
        assertThatThrownBy(() -> PayosProtocol.createPaymentRequest(0, 135_000L, "D3F9", "https://shop.example/cancel",
                "https://shop.example/return", Instant.parse("2026-11-13T12:00:00Z"), CHECKSUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentedWebhookVectorVerifiesAndMatchesFrozenAttempt() {
        String json = """
                {"code":"00","desc":"success","success":true,"data":{
                  "orderCode":123,"amount":3000,"description":"VQRIO123","accountNumber":"12345678",
                  "reference":"TF230204212323","transactionDateTime":"2023-02-04 18:25:00","currency":"VND",
                  "paymentLinkId":"124c33293c43417ab7879e14c8d9eb18","code":"00","desc":"Thành công",
                  "counterAccountBankId":"","counterAccountBankName":"","counterAccountName":"",
                  "counterAccountNumber":"","virtualAccountName":"","virtualAccountNumber":""},
                  "signature":"412e915d2871504ed31be63c8f62a149a4410d34c4c42affc9006ef9917eaa03"}
                """;
        var expected = new PayosProtocol.ExpectedPayment(ATTEMPT, 123L, 3_000L, "VQRIO123",
                "124c33293c43417ab7879e14c8d9eb18", "12345678");

        var event = PayosProtocol.verifyWebhook(json, expected, CHECKSUM);

        assertThat(event.state()).isEqualTo(ProviderPaymentState.SUCCEEDED);
        assertThat(event.orderCode()).isEqualTo(123L);
        assertThat(event.paymentLinkId()).isEqualTo(expected.paymentLinkId());
        assertThat(event.bankReference()).isEqualTo("TF230204212323");
        assertThat(event.merchantChannelId()).isEqualTo("expected-channel");
    }

    @Test
    void signedPendingOrMismatchedWebhookIsPreservedAsNonSuccessTypedEvidence() {
        Map<String, Object> pending = webhookData();
        pending.put("code", "PENDING");
        var verifiedPending = PayosProtocol.verifyWebhookEvidence(signedWebhook(pending), "merchant-test", CHECKSUM);
        assertThat(verifiedPending.state()).isEqualTo(ProviderPaymentState.UNKNOWN);
        assertThat(verifiedPending.providerStatus()).isEqualTo("PENDING");

        Map<String, Object> wrongAmount = webhookData();
        wrongAmount.put("amount", 1L);
        var signedAnomaly = PayosProtocol.verifyWebhookEvidence(signedWebhook(wrongAmount), "merchant-test", CHECKSUM);
        assertThat(signedAnomaly.state()).isEqualTo(ProviderPaymentState.SUCCEEDED);
        assertThat(signedAnomaly.amountVnd()).isEqualTo(1L);
        assertThat(signedAnomaly.orderCode()).isEqualTo(123L);
        assertThat(java.util.Arrays.stream(PayosProtocol.VerifiedWebhook.class.getDeclaredConstructors())
                .allMatch(constructor -> java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()))).isTrue();
    }

    @Test
    void validSignatureWithWrongAmountOrderCodeLinkOrRecipientNeverVerifiesAsSuccess() {
        Map<String, Object> data = webhookData();
        assertMismatched(data, "amount", 3_001, "PAYMENT_AMOUNT_MISMATCH");
        assertMismatched(data, "orderCode", 124, "PAYMENT_ORDER_CODE_MISMATCH");
        assertMismatched(data, "paymentLinkId", "other-link", "PAYMENT_LINK_MISMATCH");
        assertMismatched(data, "accountNumber", "99999999", "PAYMENT_RECIPIENT_MISMATCH");
    }

    @Test
    void malformedOrTamperedWebhookSignatureAndDuplicateJsonKeysAreRejected() {
        String valid = signedWebhook(webhookData());
        String tampered = valid.replace("12345678", "87654321");
        assertThatThrownBy(() -> PayosProtocol.verifyWebhook(tampered, expected(), CHECKSUM))
                .isInstanceOf(PayosVerificationException.class);
        assertThatThrownBy(() -> PayosProtocol.verifyWebhook(valid.replace("\"signature\":\"", "\"signature\":\"bad"), expected(), CHECKSUM))
                .isInstanceOf(PayosVerificationException.class);
        assertThatThrownBy(() -> PayosProtocol.verifyWebhook("{\"signature\":\"a\",\"signature\":\"b\"}", expected(), CHECKSUM))
                .isInstanceOf(PayosVerificationException.class);
    }

    @Test
    void queryRequiresSignedMatchingOrderLinkAndExactAmount() {
        Map<String, Object> data = Map.of("orderCode", 123L, "amount", 3000L, "amountPaid", 3000L,
                "amountRemaining", 0L, "status", "PAID", "id", "124c33293c43417ab7879e14c8d9eb18");
        String queryResponse = signedDataEnvelope(data);
        assertThat(PayosProtocol.verifyPaymentLinkQuery(queryResponse, expected(), CHECKSUM))
                .isEqualTo(ProviderPaymentState.SUCCEEDED);

        Map<String, Object> mismatched = new LinkedHashMap<>(data);
        mismatched.put("amountPaid", 2_999L);
        assertThat(PayosProtocol.verifyPaymentLinkQuery(signedDataEnvelope(mismatched), expected(), CHECKSUM))
                .isEqualTo(ProviderPaymentState.UNKNOWN);

        String tamperedResponse = queryResponse.replaceAll("[0-9a-f]{64}(?=\\\"}$)", "0".repeat(64));
        assertThatThrownBy(() -> PayosProtocol.verifyPaymentLinkQueryEvidence(tamperedResponse, "merchant-test", "query-1",
                expected(), CHECKSUM)).isInstanceOf(PayosVerificationException.class);

        Map<String, Object> wrongOrder = new LinkedHashMap<>(data);
        wrongOrder.put("orderCode", 124L);
        var signedMismatch = PayosProtocol.verifyPaymentLinkQueryEvidence(signedDataEnvelope(wrongOrder),
                "merchant-test", "query-2", expected(), CHECKSUM);
        assertThat(signedMismatch.outcome()).isEqualTo("UNKNOWN");
    }

    @Test
    void createResponseMustHaveValidSignatureAndAllowlistedHttpsUrl() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bin", "970422"); data.put("accountNumber", "12345678");
        data.put("accountName", "TEST ACCOUNT"); data.put("amount", 3_000L);
        data.put("description", "VQRIO123"); data.put("orderCode", 123L);
        data.put("currency", "VND"); data.put("paymentLinkId", "124c33293c43417ab7879e14c8d9eb18");
        data.put("status", "PENDING"); data.put("checkoutUrl", "https://pay.payos.vn/web/123");
        data.put("qrCode", "000201010212");
        String response = signedDataEnvelope(data);

        assertThat(PayosProtocol.parseCreateResponse(response, java.util.Set.of("pay.payos.vn"), 123L, 3_000L, CHECKSUM))
                .containsEntry("paymentLinkId", "124c33293c43417ab7879e14c8d9eb18");
        Map<String, Object> evilUrlData = new LinkedHashMap<>(data);
        evilUrlData.put("checkoutUrl", "https://evil.example/web/123");
        String evilUrlResponse = signedDataEnvelope(evilUrlData);
        assertThatThrownBy(() -> PayosProtocol.parseCreateResponse(evilUrlResponse,
                java.util.Set.of("pay.payos.vn"), 123L, 3_000L, CHECKSUM))
                .isInstanceOf(PayosVerificationException.class);
        assertThatThrownBy(() -> PayosProtocol.parseCreateResponse(response.replaceAll("[0-9a-f]{64}(?=\\\"}$)", "0".repeat(64)),
                java.util.Set.of("pay.payos.vn"), 123L, 3_000L, CHECKSUM))
                .isInstanceOf(PayosVerificationException.class);
    }

    @Test
    void injectedTransportTimeoutIsUnknownAndDoesNotAutomaticallyCreateAgain() {
        AtomicInteger createCalls = new AtomicInteger();
        PayosTransport transport = new PayosTransport() {
            @Override public PayosTransport.HttpResponse createPaymentLink(String endpoint, String clientId, String apiKey,
                    Map<String, Object> request, Duration timeout) {
                createCalls.incrementAndGet();
                throw new IllegalStateException("TEST_ONLY timeout after request write");
            }
            @Override public PayosTransport.HttpResponse getPaymentLink(String endpoint, String clientId, String apiKey,
                    long orderCode, Duration timeout) {
                return new PayosTransport.HttpResponse(200, "{}");
            }
        };
        PayosGateway gateway = new PayosGateway("https://api-merchant.payos.vn", "client-test", "api-test", CHECKSUM,
                java.util.Set.of("shop.example"), Duration.ofSeconds(10), transport);

        var first = gateway.create(new PayosGateway.Command(ATTEMPT, 123L, 135_000L, "D3F9",
                "https://shop.example/cancel", "https://shop.example/return", Instant.parse("2026-11-13T12:00:00Z")));

        assertThat(first.outcome()).isEqualTo(PayosGateway.Outcome.UNKNOWN);
        assertThat(createCalls).hasValue(1);
        assertThat(first.checkoutUrl()).isNull();
    }

    @Test
    void injectedTransportReturnsAcceptedVerifiedPaymentLink() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bin", "970422"); data.put("accountNumber", "12345678");
        data.put("accountName", "TEST ACCOUNT"); data.put("amount", 135_000L);
        data.put("description", "D3F9"); data.put("orderCode", 123L);
        data.put("currency", "VND"); data.put("paymentLinkId", "124c33293c43417ab7879e14c8d9eb18");
        data.put("status", "PENDING"); data.put("checkoutUrl", "https://pay.payos.vn/web/123");
        data.put("qrCode", "000201010212");
        String response = signedDataEnvelope(data);
        AtomicInteger calls = new AtomicInteger();
        PayosTransport transport = new PayosTransport() {
            @Override public PayosTransport.HttpResponse createPaymentLink(String endpoint, String clientId, String apiKey,
                    Map<String, Object> request, Duration timeout) {
                calls.incrementAndGet();
                assertThat(endpoint).isEqualTo("https://api-merchant.payos.vn/v2/payment-requests");
                assertThat(request).containsEntry("orderCode", 123L).containsEntry("amount", 135_000L);
                return new PayosTransport.HttpResponse(200, response);
            }
            @Override public PayosTransport.HttpResponse getPaymentLink(String endpoint, String clientId, String apiKey,
                    long orderCode, Duration timeout) {
                throw new AssertionError("create must not query the provider");
            }
        };
        PayosGateway gateway = new PayosGateway("https://api-merchant.payos.vn", "client-test", "api-test", CHECKSUM,
                java.util.Set.of("pay.payos.vn"), Duration.ofSeconds(10), transport);

        var result = gateway.create(new PayosGateway.Command(ATTEMPT, 123L, 135_000L, "D3F9",
                "https://shop.example/cancel", "https://shop.example/return", Instant.parse("2026-11-13T12:00:00Z")));

        assertThat(result.outcome()).isEqualTo(PayosGateway.Outcome.ACCEPTED);
        assertThat(result.paymentLinkId()).isEqualTo("124c33293c43417ab7879e14c8d9eb18");
        assertThat(result.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/123");
        assertThat(result.qrCode()).isEqualTo("000201010212");
        assertThat(result.bankBin()).isEqualTo("970422");
        assertThat(result.recipientAccountNumber()).isEqualTo("12345678");
        assertThat(result.accountName()).isEqualTo("TEST ACCOUNT");
        assertThat(result.providerResponseDigest()).matches("[0-9a-f]{64}");
        assertThat(calls).hasValue(1);
    }

    private static void assertMismatched(Map<String, Object> source, String key, Object value, String expectedCode) {
        Map<String, Object> changed = new LinkedHashMap<>(source);
        changed.put(key, value);
        assertThatThrownBy(() -> PayosProtocol.verifyWebhook(signedWebhook(changed), expected(), CHECKSUM))
                .isInstanceOf(PayosVerificationException.class)
                .satisfies(error -> assertThat(((PayosVerificationException) error).code()).isEqualTo(expectedCode));
    }

    private static PayosProtocol.ExpectedPayment expected() {
        return new PayosProtocol.ExpectedPayment(ATTEMPT, 123L, 3_000L, "VQRIO123",
                "124c33293c43417ab7879e14c8d9eb18", "12345678");
    }

    private static Map<String, Object> webhookData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCode", 123L); data.put("amount", 3000L); data.put("description", "VQRIO123");
        data.put("accountNumber", "12345678"); data.put("reference", "TF230204212323");
        data.put("transactionDateTime", "2023-02-04 18:25:00"); data.put("currency", "VND");
        data.put("paymentLinkId", "124c33293c43417ab7879e14c8d9eb18"); data.put("code", "00");
        data.put("desc", "Thành công"); data.put("counterAccountBankId", "");
        data.put("counterAccountBankName", ""); data.put("counterAccountName", "");
        data.put("counterAccountNumber", ""); data.put("virtualAccountName", "");
        data.put("virtualAccountNumber", "");
        return data;
    }

    private static String signedWebhook(Map<String, Object> data) {
        String signature = PayosProtocol.signData(data, CHECKSUM);
        return "{\"code\":\"00\",\"desc\":\"success\",\"success\":true,\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data).toString()
                + ",\"signature\":\"" + signature + "\"}";
    }

    private static String signedDataEnvelope(Map<String, Object> data) {
        return "{\"code\":\"00\",\"desc\":\"success\",\"data\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data).toString()
                + ",\"signature\":\"" + PayosProtocol.signData(data, CHECKSUM) + "\"}";
    }
}
