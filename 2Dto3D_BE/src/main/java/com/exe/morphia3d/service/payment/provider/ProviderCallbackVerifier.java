package com.exe.morphia3d.service.payment.provider;

import com.exe.morphia3d.domain.payment.PaymentAttemptEvent;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.service.VerifiedPaymentEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** Pure verifier boundary. It never applies an event or trusts browser-return data. */
public final class ProviderCallbackVerifier {
    private final Clock clock;
    public ProviderCallbackVerifier(Clock clock) { this.clock = clock; }

    public VerifiedProviderNotification verifyVnPayIpn(String rawQuery, ProviderAttemptContext expected, String hashSecret) {
        if (expected == null || expected.provider() != PaymentProvider.VNPAY) throw new ProviderVerificationException("VNPay attempt context is required");
        final Map<String, String> fields;
        try {
            fields = VnPayProtocol.parseQuery(rawQuery);
            if (!VnPayProtocol.verifyQuery(rawQuery, hashSecret)) throw new ProviderVerificationException("VNPay signature is invalid");
        } catch (IllegalArgumentException malformed) { throw new ProviderVerificationException("VNPay IPN is malformed"); }
        requireMatch(fields.get("vnp_TmnCode"), expected.merchantId(), "VNPay merchant mismatch");
        requireMatch(fields.get("vnp_TxnRef"), expected.merchantReference(), "VNPay transaction reference mismatch");
        long providerAmount = positiveLong(fields.get("vnp_Amount"), "VNPay amount is invalid");
        long amountVnd;
        try {
            if (providerAmount % 100 != 0) throw new ProviderVerificationException("VNPay amount unit is invalid");
            amountVnd = providerAmount / 100;
        } catch (ArithmeticException invalid) { throw new ProviderVerificationException("VNPay amount is invalid"); }
        if (amountVnd != expected.amountVnd()) throw new ProviderVerificationException("VNPay amount mismatch");
        String transactionId = fields.get("vnp_TransactionNo");
        if (transactionId == null || !transactionId.matches("[A-Za-z0-9]{1,255}")) throw new ProviderVerificationException("VNPay transaction id is invalid");
        String responseCode = required(fields.get("vnp_ResponseCode"), "VNPay response code missing");
        String transactionStatus = required(fields.get("vnp_TransactionStatus"), "VNPay transaction status missing");
        ProviderPaymentState state = ProviderStatusMapping.vnpay(responseCode, transactionStatus);
        String eventKey = expected.merchantReference() + ":" + transactionId + ":" + responseCode + ":" + transactionStatus;
        String digest = digest(VnPayProtocol.canonical(fields));
        Instant verifiedAt = clock.instant();
        VerifiedPaymentEvent lifecycleEvent = lifecycleEvent(expected, eventKey, digest, amountVnd, transactionId,
                state, "VNPAY_IPN", verifiedAt);
        return new VerifiedProviderNotification(expected.provider(), expected.attemptId(), expected.merchantReference(),
                expected.providerRequestId(), expected.merchantId(), state, eventKey, digest, transactionId,
                amountVnd, verifiedAt, "VNPAY_IPN", lifecycleEvent);
    }

    public VerifiedProviderNotification verifyMomoIpn(String json, ProviderAttemptContext expected, String accessKey, String secretKey) {
        if (expected == null || expected.provider() != PaymentProvider.MOMO) throw new ProviderVerificationException("MoMo attempt context is required");
        final Map<String, String> fields;
        try { fields = MomoProtocol.parseIpnJson(json); }
        catch (IllegalArgumentException malformed) { throw new ProviderVerificationException("MoMo IPN is malformed"); }
        final String canonical;
        try { canonical = MomoProtocol.ipnCanonical(fields, accessKey); }
        catch (IllegalArgumentException missing) { throw new ProviderVerificationException("MoMo IPN is missing signed fields"); }
        if (!MomoProtocol.verifySignature(canonical, secretKey, fields.get("signature"))) throw new ProviderVerificationException("MoMo signature is invalid");
        requireMatch(fields.get("partnerCode"), expected.merchantId(), "MoMo merchant mismatch");
        requireMatch(fields.get("orderId"), expected.merchantReference(), "MoMo order reference mismatch");
        if (expected.providerRequestId() == null || !expected.providerRequestId().equals(fields.get("requestId"))) {
            throw new ProviderVerificationException("MoMo request identity mismatch");
        }
        long amountVnd = positiveLong(fields.get("amount"), "MoMo amount is invalid");
        if (amountVnd != expected.amountVnd()) throw new ProviderVerificationException("MoMo amount mismatch");
        String transactionId = fields.get("transId");
        if (transactionId == null || !transactionId.matches("[0-9]{1,18}") || Long.parseLong(transactionId) <= 0) {
            throw new ProviderVerificationException("MoMo transaction id is invalid");
        }
        int resultCode;
        try { resultCode = Integer.parseInt(required(fields.get("resultCode"), "MoMo result code missing")); }
        catch (NumberFormatException invalid) { throw new ProviderVerificationException("MoMo result code is invalid"); }
        ProviderPaymentState state = ProviderStatusMapping.momo(resultCode);
        String eventKey = transactionId;
        String digest = digest(canonical + "&signature=" + fields.get("signature"));
        Instant verifiedAt = clock.instant();
        VerifiedPaymentEvent lifecycleEvent = lifecycleEvent(expected, eventKey, digest, amountVnd, transactionId,
                state, "MOMO_IPN", verifiedAt);
        return new VerifiedProviderNotification(expected.provider(), expected.attemptId(), expected.merchantReference(),
                expected.providerRequestId(), expected.merchantId(), state, eventKey, digest, transactionId,
                amountVnd, verifiedAt, "MOMO_IPN", lifecycleEvent);
    }

    private VerifiedPaymentEvent lifecycleEvent(ProviderAttemptContext expected, String key, String digest, long amount,
                                                 String transactionId, ProviderPaymentState state, String source,
                                                 Instant verifiedAt) {
        PaymentAttemptEvent event = switch (state) {
            case SUCCEEDED -> PaymentAttemptEvent.SUCCESS;
            case CANCELED -> PaymentAttemptEvent.CANCEL;
            // This foundation does not convert provider failure or ambiguous states to a terminal internal event.
            default -> null;
        };
        return event == null ? null : new VerifiedPaymentEvent(expected.provider(), expected.attemptId(),
                expected.merchantReference(), key, digest, amount, transactionId, event, verifiedAt, source);
    }

    private static void requireMatch(String actual, String expected, String message) {
        if (actual == null || !actual.equals(expected)) throw new ProviderVerificationException(message);
    }
    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new ProviderVerificationException(message);
        return value;
    }
    private static long positiveLong(String value, String message) {
        try {
            if (value == null || !value.matches("[0-9]{1,18}")) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) { throw new ProviderVerificationException(message); }
    }
    private static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
