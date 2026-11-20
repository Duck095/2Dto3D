package com.exe.morphia3d.service.payment.provider;

import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.domain.payment.PaymentAttemptEvent;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderProtocolFoundationTest {
    private static final UUID ATTEMPT = UUID.fromString("f5ae8520-5472-4bc6-a6d4-c99cc86092ee");

    @Test
    void providerReferencesAreStableAndAlphanumericForBothProviders() {
        String vnpay = ProviderReference.forAttempt(PaymentProvider.VNPAY, ATTEMPT);
        String momo = ProviderReference.forAttempt(PaymentProvider.MOMO, ATTEMPT);

        assertThat(vnpay).isEqualTo("f5ae852054724bc6a6d4c99cc86092ee").matches("[A-Za-z0-9]{1,100}");
        assertThat(momo).isEqualTo(vnpay).hasSizeLessThanOrEqualTo(50);
        assertThat(ProviderReference.forAttempt(PaymentProvider.MOMO, ATTEMPT)).isEqualTo(momo);
        assertThatThrownBy(() -> ProviderReference.forAttempt(PaymentProvider.UNKNOWN, ATTEMPT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vnpayAmountConversionIsExactAndOverflowSafe() {
        assertThat(VnPayProtocol.amountToProvider(125_000L)).isEqualTo(12_500_000L);
        assertThatThrownBy(() -> VnPayProtocol.amountToProvider(Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VnPayProtocol.amountToProvider(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hmacImplementationsMatchPublishedRfc4231TestVector() {
        String data = "what do ya want for nothing?";
        assertThat(VnPayProtocol.sign(data, "Jefe"))
                .isEqualTo("164b7a7bfcf819e2e395fbe73b56e0a3"
                        + "87bd64222e831fd610270cd7ea250554"
                        + "9758bf75c05a994a6d034f65f8f0e6fd"
                        + "caeab1a34d4a6b4b636e070a38bce737");
        assertThat(MomoProtocol.hmacSha256("Jefe", data))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void vnpayUrlAndSignatureRoundTripAndRejectDuplicateQueryKeys() {
        String url = VnPayProtocol.createPaymentUrl("https://sandbox.example/pay", "secret", VnPayProtocol.PaymentFields.builder()
                .tmnCode("MERCHANT1").txnRef("ref001").amountVnd(125_000L).currency("VND")
                .orderInfo("Order 42 & model").orderType("other").locale("vn")
                .returnUrl("https://shop.example/payment-result").ipAddress("127.0.0.1")
                .createDate("20261112120000").expireDate("20261112121500").build());

        URI uri = URI.create(url);
        assertThat(uri.getHost()).isEqualTo("sandbox.example");
        assertThat(url).contains("vnp_Amount=12500000");
        assertThat(VnPayProtocol.verifyQuery(uri.getRawQuery(), "secret")).isTrue();
        assertThatThrownBy(() -> VnPayProtocol.verifyQuery(uri.getRawQuery() + "&vnp_TmnCode=ATTACK", "secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void momoRequestUsesProviderIdentityAndRawVndWithExactRequestSignature() {
        MomoProtocol.CreateRequest request = MomoProtocol.createRequest(new MomoProtocol.Config(
                "PARTNER1", "ACCESS1", "secret", "STORE1", "https://shop.example/payment-result",
                "https://api.example/ipn/momo", "payWithMethod", "vi"),
                ATTEMPT, 125_000L, "Client key ignored");

        assertThat(request.body().get("amount")).isEqualTo(125_000L);
        assertThat(request.body().get("requestId")).isEqualTo("f5ae852054724bc6a6d4c99cc86092ee");
        assertThat(request.body().get("orderId")).isEqualTo("f5ae852054724bc6a6d4c99cc86092ee");
        assertThat(request.body()).doesNotContainKey("accessKey");
        assertThat(MomoProtocol.verifySignature(request.canonical(), "secret", request.signature())).isTrue();
        assertThatThrownBy(() -> MomoProtocol.createRequest(new MomoProtocol.Config(
                "PARTNER1", "ACCESS1", "secret", "STORE1", "https://shop.example/payment-result",
                "https://api.example/ipn/momo", "payWithMethod", "vi"), ATTEMPT, Long.MAX_VALUE, "key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callbackMappingPreservesUnknownAuthorizedAndPendingOutcomes() {
        assertThat(ProviderStatusMapping.vnpay("00", "00")).isEqualTo(ProviderPaymentState.SUCCEEDED);
        assertThat(ProviderStatusMapping.vnpay("00", "07")).isEqualTo(ProviderPaymentState.UNKNOWN);
        assertThat(ProviderStatusMapping.momo(0)).isEqualTo(ProviderPaymentState.SUCCEEDED);
        assertThat(ProviderStatusMapping.momo(9000)).isEqualTo(ProviderPaymentState.AUTHORIZED);
        assertThat(ProviderStatusMapping.momo(7002)).isEqualTo(ProviderPaymentState.PENDING);
        assertThat(ProviderStatusMapping.momo(987654)).isEqualTo(ProviderPaymentState.UNKNOWN);
    }

    @Test
    void vnpayIpnVerifierRequiresSignedMerchantReferenceAndExactAmount() {
        ProviderCallbackVerifier verifier = new ProviderCallbackVerifier(Clock.fixed(
                java.time.Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC));
        ProviderAttemptContext context = new ProviderAttemptContext(ATTEMPT, PaymentProvider.VNPAY,
                ProviderReference.forAttempt(PaymentProvider.VNPAY, ATTEMPT), 125_000L, "MERCHANT1", null);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("vnp_TmnCode", "MERCHANT1"); fields.put("vnp_TxnRef", context.merchantReference());
        fields.put("vnp_Amount", "12500000"); fields.put("vnp_TransactionNo", "987654321");
        fields.put("vnp_ResponseCode", "00"); fields.put("vnp_TransactionStatus", "00");
        fields.put("vnp_PayDate", "20261112115959");
        String query = signedVnPayQuery(fields, "secret");

        var verified = verifier.verifyVnPayIpn(query, context, "secret");

        assertThat(verified.state()).isEqualTo(ProviderPaymentState.SUCCEEDED);
        assertThat(verified.lifecycleEvent()).isNotNull().extracting("event").isEqualTo(PaymentAttemptEvent.SUCCESS);
        Map<String, String> tamperedAmount = new LinkedHashMap<>(fields);
        tamperedAmount.put("vnp_Amount", "12500100");
        assertThatThrownBy(() -> verifier.verifyVnPayIpn(signedVnPayQuery(tamperedAmount, "secret"), context, "secret"))
                .isInstanceOf(ProviderVerificationException.class).hasMessageContaining("amount mismatch");
        Map<String, String> wrongMerchant = new LinkedHashMap<>(fields);
        wrongMerchant.put("vnp_TmnCode", "OTHER");
        assertThatThrownBy(() -> verifier.verifyVnPayIpn(signedVnPayQuery(wrongMerchant, "secret"), context, "secret"))
                .isInstanceOf(ProviderVerificationException.class).hasMessageContaining("merchant mismatch");
    }

    @Test
    void momoIpnVerifierRejectsTamperedSignatureAndPreservesAuthorizedState() {
        ProviderCallbackVerifier verifier = new ProviderCallbackVerifier(Clock.systemUTC());
        String reference = ProviderReference.forAttempt(PaymentProvider.MOMO, ATTEMPT);
        ProviderAttemptContext context = new ProviderAttemptContext(ATTEMPT, PaymentProvider.MOMO,
                reference, 125_000L, "PARTNER1", reference);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("amount", "125000"); fields.put("extraData", ""); fields.put("message", "Authorized");
        fields.put("orderId", reference); fields.put("orderInfo", "Payment " + reference); fields.put("orderType", "momo_wallet");
        fields.put("partnerCode", "PARTNER1"); fields.put("payType", "qr"); fields.put("requestId", reference);
        fields.put("responseTime", "1794484800000"); fields.put("resultCode", "9000"); fields.put("transId", "12345678");
        fields.put("signature", MomoProtocol.hmacSha256("secret", MomoProtocol.ipnCanonical(fields, "ACCESS1")));
        String json = toJson(fields);

        var verified = verifier.verifyMomoIpn(json, context, "ACCESS1", "secret");

        assertThat(verified.state()).isEqualTo(ProviderPaymentState.AUTHORIZED);
        assertThat(verified.lifecycleEvent()).isNull();
        fields.put("amount", "125001");
        String canonical = MomoProtocol.ipnCanonical(fields, "ACCESS1");
        fields.put("signature", MomoProtocol.hmacSha256("secret", canonical));
        assertThatThrownBy(() -> verifier.verifyMomoIpn(toJson(fields), context, "ACCESS1", "secret"))
                .isInstanceOf(ProviderVerificationException.class).hasMessageContaining("amount mismatch");
        assertThatThrownBy(() -> MomoProtocol.parseIpnJson("{\"amount\":1,\"amount\":2}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redirectPolicyRejectsUnlistedHostsHttpExpiredAndReplayedLinks() {
        Instant expiry = Instant.parse("2026-11-12T13:00:00Z");
        assertThat(RedirectUrlPolicy.validate("https://pay.example/path?t=token", java.util.Set.of("pay.example"),
                Instant.parse("2026-11-12T12:00:00Z"), expiry, false)).isTrue();
        assertThat(RedirectUrlPolicy.validate("https://evil.example/path", java.util.Set.of("pay.example"),
                Instant.parse("2026-11-12T12:00:00Z"), expiry, false)).isFalse();
        assertThat(RedirectUrlPolicy.validate("http://pay.example/path", java.util.Set.of("pay.example"),
                Instant.parse("2026-11-12T12:00:00Z"), expiry, false)).isFalse();
        assertThat(RedirectUrlPolicy.validate("https://pay.example/path", java.util.Set.of("pay.example"),
                expiry, expiry, false)).isFalse();
        assertThat(RedirectUrlPolicy.validate("https://pay.example/path", java.util.Set.of("pay.example"),
                Instant.parse("2026-11-12T12:00:00Z"), expiry, true)).isFalse();
    }

    @Test
    void momoAdapterUsesFakeTransportAndKeepsAmbiguousCreateOutcomesUnknown() {
        MomoProtocol.Config config = new MomoProtocol.Config("PARTNER1", "ACCESS1", "secret", "STORE1",
                "https://shop.example/payment-result", "https://api.example/ipn/momo", "payWithMethod", "vi");
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        MomoTransport fake = (endpoint, body, timeout) -> {
            sends.incrementAndGet();
            return "{\"partnerCode\":\"PARTNER1\",\"requestId\":\"f5ae852054724bc6a6d4c99cc86092ee\","
                    + "\"orderId\":\"f5ae852054724bc6a6d4c99cc86092ee\",\"amount\":125000,\"resultCode\":0,"
                    + "\"payUrl\":\"https://pay.example/session\"}";
        };
        MomoPaymentAttemptGateway gateway = new MomoPaymentAttemptGateway(config, "https://api.example/v2/gateway/api/create",
                java.util.Set.of("api.example"), java.util.Set.of("pay.example"), java.util.Set.of("shop.example", "api.example"),
                fake, java.time.Duration.ofSeconds(30));
        var result = gateway.submit(new com.exe.morphia3d.service.PaymentProviderRequest(ATTEMPT, 41, 125_000L,
                "client-key-does-not-leave-backend", ProviderReference.forAttempt(PaymentProvider.MOMO, ATTEMPT)));
        assertThat(result.outcome()).isEqualTo(com.exe.morphia3d.service.PaymentProviderSubmissionResult.Outcome.ACCEPTED);
        assertThat(result.redirectUrl()).isEqualTo("https://pay.example/session");
        assertThat(sends).hasValue(1);

        MomoTransport timeout = (endpoint, body, requestTimeout) -> { throw new IllegalStateException("simulated timeout"); };
        var unknown = new MomoPaymentAttemptGateway(config, "https://api.example/v2/gateway/api/create",
                java.util.Set.of("api.example"), java.util.Set.of("pay.example"), java.util.Set.of("shop.example", "api.example"),
                timeout, java.time.Duration.ofSeconds(30))
                .submit(new com.exe.morphia3d.service.PaymentProviderRequest(ATTEMPT, 41, 125_000L, "key",
                        ProviderReference.forAttempt(PaymentProvider.MOMO, ATTEMPT)));
        assertThat(unknown.outcome()).isEqualTo(com.exe.morphia3d.service.PaymentProviderSubmissionResult.Outcome.UNKNOWN);
        assertThat(unknown.redirectUrl()).isNull();
    }

    @Test
    void vnPayGatewayCreatesOnlyAllowlistedHttpsRedirectAndUsesAttemptReference() {
        var gateway = new VnPayPaymentAttemptGateway("https://pay.example/vpcpay.html", "MERCHANT1", "secret",
                java.util.Set.of("pay.example"), java.util.Set.of("shop.example"), request ->
                new VnPayPaymentAttemptGateway.Metadata("Order " + request.orderId(), "other", "vn",
                        "https://shop.example/payment-result", "127.0.0.1", "20261112120000", "20261112121500"));
        var request = new com.exe.morphia3d.service.PaymentProviderRequest(ATTEMPT, 41, 125_000L, "browser-key",
                ProviderReference.forAttempt(PaymentProvider.VNPAY, ATTEMPT));
        var result = gateway.submit(request);
        assertThat(result.outcome()).isEqualTo(com.exe.morphia3d.service.PaymentProviderSubmissionResult.Outcome.ACCEPTED);
        assertThat(VnPayProtocol.parseQuery(URI.create(result.redirectUrl()).getRawQuery()).get("vnp_TxnRef"))
                .isEqualTo(request.merchantReference());
        assertThat(new VnPayPaymentAttemptGateway("https://evil.example/vpcpay.html", "MERCHANT1", "secret",
                java.util.Set.of("pay.example"), java.util.Set.of("shop.example"), ignored -> null).isAvailable()).isFalse();
    }

    @Test
    void providerAcknowledgementsRemainSeparateAndUseDocumentedDuplicateCodes() {
        assertThat(ProviderAcknowledgementPolicy.vnpay(ProviderAcknowledgementPolicy.CallbackReceiptLedgerOutcome.RECORDED)).isEqualTo("00");
        assertThat(ProviderAcknowledgementPolicy.vnpay(ProviderAcknowledgementPolicy.CallbackReceiptLedgerOutcome.DUPLICATE)).isEqualTo("02");
        assertThat(ProviderAcknowledgementPolicy.vnpay(ProviderAcknowledgementPolicy.CallbackReceiptLedgerOutcome.INVALID_SIGNATURE)).isEqualTo("97");
        assertThat(ProviderAcknowledgementPolicy.momoHttpStatus(true)).hasValue(204);
        assertThat(ProviderAcknowledgementPolicy.momoHttpStatus(false)).isEmpty();
    }

    @Test
    void parallelDuplicateCallbackReceiptsResolveToOneDurableEvent() throws Exception {
        CallbackReceiptLedger ledger = new CallbackReceiptLedger();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                start.await();
                return ledger.record("VNPAY", "txn-1", "digest-1");
            })).toList();
            start.countDown();
            var outcomes = tasks.stream().map(task -> {
                try { return task.get(3, TimeUnit.SECONDS); }
                catch (Exception e) { throw new RuntimeException(e); }
            }).toList();
            assertThat(outcomes).containsOnly(CallbackReceiptLedger.Outcome.RECORDED, CallbackReceiptLedger.Outcome.DUPLICATE);
            assertThat(outcomes.stream().filter(value -> value == CallbackReceiptLedger.Outcome.RECORDED)).hasSize(1);
            assertThat(outcomes.stream().filter(value -> value == CallbackReceiptLedger.Outcome.DUPLICATE)).hasSize(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger.record("VNPAY", "txn-1", "different-digest"))
                .isEqualTo(CallbackReceiptLedger.Outcome.CONFLICT);
    }

    private String signedVnPayQuery(Map<String, String> fields, String secret) {
        Map<String, String> signed = new LinkedHashMap<>(fields);
        signed.put("vnp_SecureHash", VnPayProtocol.sign(VnPayProtocol.canonical(fields), secret));
        return signed.entrySet().stream().map(e -> java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8)
                + "=" + java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }

    private String toJson(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> "\"" + e.getKey() + "\":\"" + e.getValue().replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
}
