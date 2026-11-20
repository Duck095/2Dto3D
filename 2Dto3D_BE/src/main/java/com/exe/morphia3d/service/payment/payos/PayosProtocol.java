package com.exe.morphia3d.service.payment.payos;

import com.exe.morphia3d.service.payment.provider.ProviderPaymentState;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Offline payOS payment-request, webhook and query contract primitives. */
public final class PayosProtocol {
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Pattern SIGNATURE = Pattern.compile("(?i)[0-9a-f]{64}");

    private PayosProtocol() {}

    public static PaymentLinkRequest createPaymentRequest(long orderCode, long amountVnd, String description,
                                                            String cancelUrl, String returnUrl,
                                                            Instant expiresAt, String checksumKey) {
        if (orderCode < 1 || amountVnd < 1
                || description == null || !description.matches("[A-Za-z0-9]{1,9}")
                || !safeHttps(cancelUrl) || !safeHttps(returnUrl) || expiresAt == null
                || expiresAt.getEpochSecond() <= 0 || expiresAt.getEpochSecond() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid payOS payment request fields");
        }
        requireSecret(checksumKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderCode", orderCode);
        body.put("amount", amountVnd);
        body.put("description", description);
        body.put("cancelUrl", cancelUrl);
        body.put("returnUrl", returnUrl);
        body.put("expiredAt", expiresAt.getEpochSecond());
        String canonical = "amount=" + amountVnd + "&cancelUrl=" + cancelUrl + "&description=" + description
                + "&orderCode=" + orderCode + "&returnUrl=" + returnUrl;
        body.put("signature", sign(canonical, checksumKey));
        return new PaymentLinkRequest(Map.copyOf(body), canonical, (String) body.get("signature"), orderCode,
                amountVnd, description, expiresAt);
    }

    public static boolean verifySignature(String canonical, String checksumKey, String signature) {
        if (canonical == null || checksumKey == null || checksumKey.isBlank() || signature == null
                || !SIGNATURE.matcher(signature).matches()) return false;
        byte[] calculated = sign(canonical, checksumKey).toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(calculated, signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /** payOS signs the alphabetically sorted fields in data, excluding the outer envelope. */
    public static String canonicalData(Map<String, ?> data) {
        if (data == null || data.isEmpty()) throw new IllegalArgumentException("payOS signed data is required");
        TreeMap<String, String> sorted = new TreeMap<>();
        data.forEach((key, value) -> {
            if (key == null || key.isBlank() || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                throw new IllegalArgumentException("payOS signed data must contain scalar fields");
            }
            sorted.put(key, canonicalValue(value));
        });
        return sorted.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right).orElseThrow();
    }

    public static String signData(Map<String, ?> data, String checksumKey) {
        return sign(canonicalData(data), checksumKey);
    }

    /** Verifies the provider signature and preserves signed anomalies as evidence for reconciliation. */
    public static VerifiedWebhook verifyWebhookEvidence(String payload, String merchantChannelId, String checksumKey) {
        if (merchantChannelId == null || merchantChannelId.isBlank() || merchantChannelId.length() > 128
                || payload == null || payload.isBlank() || payload.length() > 32_768) {
            throw new PayosVerificationException("PAYMENT_WEBHOOK_MALFORMED", "payOS webhook is malformed");
        }
        JsonNode root = parseObject(payload, "payOS webhook is malformed");
        JsonNode dataNode = root.get("data");
        String suppliedSignature = text(root.get("signature"));
        Map<String, Object> data = scalarMap(dataNode);
        String canonical = canonicalData(data);
        if (!verifySignature(canonical, checksumKey, suppliedSignature)) {
            throw new PayosVerificationException("PAYMENT_SIGNATURE_INVALID", "payOS webhook signature is invalid");
        }
        String reference = text(dataNode.get("reference"));
        String digest = sha256(canonical + "&signature=" + suppliedSignature.toLowerCase(java.util.Locale.ROOT));
        if (reference == null || reference.isBlank() || reference.length() > 255) reference = "MISSING-" + digest;
        long orderCode = positiveLong(text(dataNode.get("orderCode")));
        long amount = nonNegativeLong(text(dataNode.get("amount")));
        String rawCode = text(dataNode.get("code"));
        String providerStatus = "true".equalsIgnoreCase(text(root.get("success")))
                && "00".equals(text(root.get("code"))) && "00".equals(text(dataNode.get("code")))
                && "VND".equals(text(dataNode.get("currency"))) ? "PAID"
                : rawCode != null && rawCode.matches("[A-Za-z0-9_-]{1,32}") ? rawCode.toUpperCase(java.util.Locale.ROOT) : "UNCONFIRMED";
        ProviderPaymentState state = "PAID".equals(providerStatus)
                ? ProviderPaymentState.SUCCEEDED : ProviderPaymentState.UNKNOWN;
        return new VerifiedWebhook(merchantChannelId, orderCode, amount, text(dataNode.get("paymentLinkId")),
                text(dataNode.get("accountNumber")), text(dataNode.get("currency")), text(dataNode.get("description")), reference,
                text(dataNode.get("code")), text(dataNode.get("transactionDateTime")), state, providerStatus,
                digest, Instant.now());
    }

    /** Compatibility helper for tests/callers that need strict matching against one frozen attempt. */
    public static VerifiedWebhook verifyWebhook(String payload, ExpectedPayment expected, String checksumKey) {
        if (expected == null) throw new PayosVerificationException("PAYMENT_WEBHOOK_MALFORMED", "expected payment is required");
        VerifiedWebhook event = verifyWebhookEvidence(payload, "expected-channel", checksumKey);
        requireMatch(Long.toString(event.orderCode()), Long.toString(expected.orderCode()), "PAYMENT_ORDER_CODE_MISMATCH");
        requireMatch(Long.toString(event.amountVnd()), Long.toString(expected.amountVnd()), "PAYMENT_AMOUNT_MISMATCH");
        requireMatch(event.paymentLinkId(), expected.paymentLinkId(), "PAYMENT_LINK_MISMATCH");
        requireMatch(event.description(), expected.description(), "PAYMENT_DESCRIPTION_MISMATCH");
        requireMatch(event.recipientAccountNumber(), expected.receivingAccountNumber(), "PAYMENT_RECIPIENT_MISMATCH");
        requireMatch(event.currency(), "VND", "PAYMENT_CURRENCY_MISMATCH");
        if (event.state() != ProviderPaymentState.SUCCEEDED)
            throw new PayosVerificationException("PAYMENT_WEBHOOK_NOT_SUCCESSFUL", "payOS event is not a final payment success");
        return event;
    }

    private static long positiveLong(String value) { try { long n = Long.parseLong(value); return n > 0 ? n : 0; } catch (Exception ignored) { return 0; } }
    private static long nonNegativeLong(String value) { try { long n = Long.parseLong(value); return n >= 0 ? n : 0; } catch (Exception ignored) { return 0; } }

    /** Verifies the signed query response and requires a full amount-paid match before success. */
    public static ProviderPaymentState verifyPaymentLinkQuery(String response, ExpectedPayment expected, String checksumKey) {
        JsonNode root = parseObject(response, "payOS query response is malformed");
        JsonNode dataNode = root.get("data");
        Map<String, Object> data = scalarMap(dataNode);
        if (!verifySignature(canonicalData(data), checksumKey, text(root.get("signature")))) {
            throw new PayosVerificationException("PAYMENT_QUERY_SIGNATURE_INVALID", "payOS query signature is invalid");
        }
        if (!"00".equals(text(root.get("code")))) return ProviderPaymentState.UNKNOWN;
        if (!Long.toString(expected.orderCode()).equals(text(dataNode.get("orderCode")))
                || !expected.paymentLinkId().equals(text(dataNode.get("id")))
                || !Long.toString(expected.amountVnd()).equals(text(dataNode.get("amount")))) {
            throw new PayosVerificationException("PAYMENT_QUERY_IDENTITY_MISMATCH", "payOS query identity does not match the attempt");
        }
        String status = text(dataNode.get("status"));
        String amountPaidText = text(dataNode.get("amountPaid"));
        if ("PAID".equals(status) && Long.toString(expected.amountVnd()).equals(amountPaidText)) {
            return ProviderPaymentState.SUCCEEDED;
        }
        if ("CANCELLED".equals(status)) return ProviderPaymentState.CANCELED;
        if ("PENDING".equals(status)) return ProviderPaymentState.PENDING;
        return ProviderPaymentState.UNKNOWN;
    }

    /** Opaque result for persistence; only this verifier can construct it after HMAC validation. */
    public static VerifiedPaymentLinkQuery verifyPaymentLinkQueryEvidence(String response, String merchantChannelId,
            String queryRequestId, ExpectedPayment expected, String checksumKey) {
        if (expected == null || merchantChannelId == null || merchantChannelId.isBlank()
                || queryRequestId == null || queryRequestId.isBlank() || queryRequestId.length() > 128)
            throw new PayosVerificationException("PAYMENT_QUERY_CONTEXT_INVALID", "payOS query context is invalid");
        JsonNode root = parseObject(response, "payOS query response is malformed");
        JsonNode data = root.get("data");
        Map<String, Object> fields = scalarMap(data);
        String signature = text(root.get("signature"));
        if (!verifySignature(canonicalData(fields), checksumKey, signature))
            throw new PayosVerificationException("PAYMENT_QUERY_SIGNATURE_INVALID", "payOS query signature is invalid");
        String status = text(data.get("status"));
        long orderCode = positiveLong(text(data.get("orderCode")));
        long amount = nonNegativeLong(text(data.get("amount")));
        long amountPaid = nonNegativeLong(text(data.get("amountPaid")));
        String linkId = text(data.get("id"));
        boolean identity = orderCode == expected.orderCode() && amount == expected.amountVnd()
                && expected.paymentLinkId().equals(linkId);
        boolean envelope = "00".equals(text(root.get("code")));
        ProviderPaymentState state = ProviderPaymentState.UNKNOWN;
        String outcome = "UNKNOWN";
        if (identity && envelope && "PAID".equals(status) && amountPaid == expected.amountVnd()) {
            state = ProviderPaymentState.SUCCEEDED; outcome = "SUCCEEDED";
        } else if (identity && envelope && "CANCELLED".equals(status)) {
            state = ProviderPaymentState.CANCELED; outcome = "FAILED";
        } else if (identity && envelope && "PENDING".equals(status)) {
            state = ProviderPaymentState.PENDING; outcome = "PENDING";
        }
        String digest = sha256(canonicalData(fields) + "&signature=" + (signature == null ? "" : signature.toLowerCase(java.util.Locale.ROOT)));
        return new VerifiedPaymentLinkQuery(expected.attemptId(), merchantChannelId, queryRequestId,
                expected.orderCode(), expected.paymentLinkId(), expected.amountVnd(), amountPaid, status,
                outcome, digest, Instant.now(), state);
    }

    public static Map<String, Object> parseCreateResponse(String response, Set<String> allowedRedirectHosts,
                                                          long expectedOrderCode, long expectedAmountVnd,
                                                          String checksumKey) {
        JsonNode root = parseObject(response, "payOS create response is malformed");
        JsonNode data = root.get("data");
        if (!"00".equals(text(root.get("code"))) || data == null || !data.isObject()) {
            throw new PayosVerificationException("PAYMENT_CREATE_RESPONSE_INVALID", "payOS did not accept the payment request");
        }
        Map<String, Object> signedData = scalarMap(data);
        if (!verifySignature(canonicalData(signedData), checksumKey, text(root.get("signature")))) {
            throw new PayosVerificationException("PAYMENT_CREATE_RESPONSE_SIGNATURE_INVALID", "payOS create response signature is invalid");
        }
        if (!Long.toString(expectedOrderCode).equals(text(data.get("orderCode")))
                || !Long.toString(expectedAmountVnd).equals(text(data.get("amount")))
                || !"VND".equals(text(data.get("currency")))) {
            throw new PayosVerificationException("PAYMENT_CREATE_RESPONSE_MISMATCH", "payOS response does not match the request");
        }
        String linkId = text(data.get("paymentLinkId"));
        String checkoutUrl = text(data.get("checkoutUrl"));
        String qrCode = text(data.get("qrCode"));
        if (linkId == null || !linkId.matches("[A-Za-z0-9_-]{8,128}") || qrCode == null || qrCode.isBlank()
                || !allowedHttps(checkoutUrl, allowedRedirectHosts)) {
            throw new PayosVerificationException("PAYMENT_CREATE_RESPONSE_INVALID", "payOS link or QR response is invalid");
        }
        return Map.copyOf(signedData);
    }

    /** Stable digest of an already verified create response for durable evidence and recovery. */
    public static String verifiedCreateResponseDigest(String response) {
        JsonNode root = parseObject(response, "payOS create response is malformed");
        Map<String, Object> data = scalarMap(root.get("data"));
        String signature = text(root.get("signature"));
        return sha256(canonicalData(data) + "&signature="
                + (signature == null ? "" : signature.toLowerCase(java.util.Locale.ROOT)));
    }

    public static boolean allowedHttps(String rawUrl, Set<String> allowedHosts) {
        if (rawUrl == null || allowedHosts == null || allowedHosts.isEmpty()) return false;
        try {
            URI uri = URI.create(rawUrl);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && uri.getPort() == -1
                    && allowedHosts.stream().anyMatch(host -> host.equalsIgnoreCase(uri.getHost()));
        } catch (RuntimeException invalid) { return false; }
    }

    private static boolean safeHttps(String url) {
        return allowedHttps(url, Set.of(hostOf(url)));
    }

    private static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1) return "";
            return uri.getHost();
        } catch (RuntimeException invalid) { return ""; }
    }

    private static String canonicalValue(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        return "null".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text) ? "" : text;
    }

    private static JsonNode parseObject(String json, String message) {
        try {
            JsonNode root = STRICT_JSON.readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException(message);
            return root;
        } catch (RuntimeException error) { throw error instanceof PayosVerificationException p ? p
                : new PayosVerificationException("PAYMENT_WEBHOOK_MALFORMED", message); }
        catch (Exception error) { throw new PayosVerificationException("PAYMENT_WEBHOOK_MALFORMED", message); }
    }

    private static Map<String, Object> scalarMap(JsonNode node) {
        if (node == null || !node.isObject()) throw new PayosVerificationException("PAYMENT_DATA_MALFORMED", "payOS data object is missing");
        Map<String, Object> fields = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (value.isContainerNode()) throw new PayosVerificationException("PAYMENT_DATA_MALFORMED", "payOS data fields must be scalar");
            if (value.isNull()) fields.put(field.getKey(), null);
            else if (value.isNumber()) fields.put(field.getKey(), value.asText());
            else if (value.isBoolean()) fields.put(field.getKey(), value.asText());
            else fields.put(field.getKey(), value.asText());
        });
        return fields;
    }

    private static String text(JsonNode node) { return node == null || node.isNull() ? null : node.asText(); }
    private static void requireMatch(String actual, String expected, String code) {
        if (actual == null || !actual.equals(expected)) throw new PayosVerificationException(code, "payOS transaction does not match the frozen attempt");
    }
    private static String sign(String canonical, String secret) {
        requireSecret(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalArgumentException("Unable to calculate payOS HMAC", error); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireSecret(String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("payOS checksum key is required");
    }

    public record PaymentLinkRequest(Map<String, Object> body, String canonical, String signature,
                                     long orderCode, long amountVnd, String description, Instant expiresAt) {}
    public record ExpectedPayment(UUID attemptId, long orderCode, long amountVnd, String description,
                                  String paymentLinkId, String receivingAccountNumber) {}
    public static final class VerifiedWebhook {
        private final String merchantChannelId; private final long orderCode; private final long amountVnd;
        private final String paymentLinkId, recipientAccountNumber, currency, description, bankReference, rawProviderCode;
        private final String transactionDateTime, providerStatus, eventDigest; private final ProviderPaymentState state;
        private final Instant signatureVerifiedAt;
        private VerifiedWebhook(String merchantChannelId, long orderCode, long amountVnd, String paymentLinkId,
                String recipientAccountNumber, String currency, String description, String bankReference, String rawProviderCode,
                String transactionDateTime, ProviderPaymentState state, String providerStatus, String eventDigest,
                Instant signatureVerifiedAt) {
            this.merchantChannelId=merchantChannelId; this.orderCode=orderCode; this.amountVnd=amountVnd;
            this.paymentLinkId=paymentLinkId; this.recipientAccountNumber=recipientAccountNumber; this.currency=currency; this.description=description;
            this.bankReference=bankReference; this.rawProviderCode=rawProviderCode; this.transactionDateTime=transactionDateTime;
            this.state=state; this.providerStatus=providerStatus; this.eventDigest=eventDigest; this.signatureVerifiedAt=signatureVerifiedAt;
        }
        public String merchantChannelId(){return merchantChannelId;} public long orderCode(){return orderCode;}
        public long amountVnd(){return amountVnd;} public String paymentLinkId(){return paymentLinkId;}
        public String recipientAccountNumber(){return recipientAccountNumber;} public String currency(){return currency;}
        public String description(){return description;}
        public String bankReference(){return bankReference;} public String rawProviderCode(){return rawProviderCode;}
        public String transactionDateTime(){return transactionDateTime;} public ProviderPaymentState state(){return state;}
        public String providerStatus(){return providerStatus;} public String eventDigest(){return eventDigest;}
        public Instant signatureVerifiedAt(){return signatureVerifiedAt;}
    }

    public static final class VerifiedPaymentLinkQuery {
        private final UUID attemptId; private final String merchantChannelId, queryRequestId;
        private final long orderCode, amountVnd, amountPaidVnd; private final String paymentLinkId, providerStatus, outcome, responseDigest;
        private final Instant verifiedAt; private final ProviderPaymentState state;
        private VerifiedPaymentLinkQuery(UUID attemptId, String channel, String requestId, long orderCode,
                String linkId, long amount, long paid, String status, String outcome, String digest,
                Instant verifiedAt, ProviderPaymentState state) {
            this.attemptId=attemptId; this.merchantChannelId=channel; this.queryRequestId=requestId;
            this.orderCode=orderCode; this.paymentLinkId=linkId; this.amountVnd=amount; this.amountPaidVnd=paid;
            this.providerStatus=status; this.outcome=outcome; this.responseDigest=digest; this.verifiedAt=verifiedAt; this.state=state;
        }
        public UUID attemptId(){return attemptId;} public String merchantChannelId(){return merchantChannelId;}
        public String queryRequestId(){return queryRequestId;} public long orderCode(){return orderCode;}
        public String paymentLinkId(){return paymentLinkId;} public long amountVnd(){return amountVnd;}
        public long amountPaidVnd(){return amountPaidVnd;} public String providerStatus(){return providerStatus;}
        public String outcome(){return outcome;} public String responseDigest(){return responseDigest;}
        public Instant verifiedAt(){return verifiedAt;} public ProviderPaymentState state(){return state;}
    }
}
