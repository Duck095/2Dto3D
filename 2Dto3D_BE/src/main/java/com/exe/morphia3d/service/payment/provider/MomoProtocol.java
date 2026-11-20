package com.exe.morphia3d.service.payment.provider;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Offline request and signature primitives for MoMo Collection Link payWithMethod. */
public final class MomoProtocol {
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private MomoProtocol() {}

    public static CreateRequest createRequest(Config config, UUID attemptId, long amountVnd, String ignoredClientKey) {
        if (config == null || attemptId == null || amountVnd < 1_000 || amountVnd > 50_000_000L) {
            throw new IllegalArgumentException("Valid MoMo config, attempt and amount are required");
        }
        config.validate();
        String reference = ProviderReference.forAttempt(com.exe.morphia3d.enums.PaymentProvider.MOMO, attemptId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", config.partnerCode()); body.put("storeId", config.storeId());
        body.put("requestType", config.requestType()); body.put("ipnUrl", config.ipnUrl());
        body.put("redirectUrl", config.redirectUrl()); body.put("orderId", reference); body.put("amount", amountVnd);
        body.put("requestId", reference); body.put("extraData", ""); body.put("orderInfo", "Payment " + reference);
        body.put("lang", config.language());
        String canonical = String.join("&",
                "accessKey=" + config.accessKey(), "amount=" + amountVnd, "extraData=",
                "ipnUrl=" + config.ipnUrl(), "orderId=" + reference, "orderInfo=Payment " + reference,
                "partnerCode=" + config.partnerCode(), "redirectUrl=" + config.redirectUrl(),
                "requestId=" + reference, "requestType=" + config.requestType());
        return new CreateRequest(Map.copyOf(body), canonical, hmacSha256(config.secretKey(), canonical), reference);
    }

    public static String ipnCanonical(Map<String, String> fields, String accessKey) {
        String[] names = {"amount", "extraData", "message", "orderId", "orderInfo", "orderType", "partnerCode",
                "payType", "requestId", "responseTime", "resultCode", "transId"};
        StringBuilder canonical = new StringBuilder("accessKey=").append(required(accessKey, "accessKey"));
        for (String name : names) {
            String value = fields.get(name);
            if (value == null || (!"extraData".equals(name) && value.isBlank())) throw new IllegalArgumentException("MoMo " + name + " is required");
            canonical.append('&').append(name).append('=').append(value);
        }
        return canonical.toString();
    }

    public static boolean verifySignature(String canonical, String secret, String signature) {
        if (canonical == null || secret == null || secret.isBlank() || signature == null
                || !signature.matches("(?i)[0-9a-f]{64}")) return false;
        byte[] expected = hmacSha256(secret, canonical).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(required(secret, "secretKey").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to calculate MoMo signature", error);
        }
    }

    public static Map<String, String> parseIpnJson(String json) {
        if (json == null || json.isBlank() || json.length() > 32_768) throw new IllegalArgumentException("Invalid MoMo IPN payload size");
        try {
            JsonNode root = STRICT_JSON.readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("MoMo IPN must be a JSON object");
            Map<String, String> fields = new LinkedHashMap<>();
            var iterator = root.fields();
            while (iterator.hasNext()) {
                var field = iterator.next();
                JsonNode value = field.getValue();
                if (value == null || (value.isContainerNode()) || value.isNull()) throw new IllegalArgumentException("MoMo IPN fields must be scalar values");
                fields.put(field.getKey(), value.asText());
            }
            return Map.copyOf(fields);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Malformed MoMo IPN JSON", error);
        }
    }

    public static Map<String, String> parseCreateResponse(String json) {
        if (json == null || json.isBlank() || json.length() > 32_768) throw new IllegalArgumentException("Invalid MoMo response size");
        try {
            JsonNode root = STRICT_JSON.readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("MoMo response must be a JSON object");
            Map<String, String> fields = new LinkedHashMap<>();
            root.fields().forEachRemaining(field -> {
                if (field.getValue().isContainerNode() || field.getValue().isNull()) throw new IllegalArgumentException("MoMo response fields must be scalar values");
                fields.put(field.getKey(), field.getValue().asText());
            });
            return Map.copyOf(fields);
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Malformed MoMo create response", error); }
    }

    public static boolean isSafePayUrl(String url, java.util.Set<String> allowedHosts) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && allowedHosts.stream().anyMatch(host -> host.equalsIgnoreCase(uri.getHost()));
        } catch (Exception invalid) { return false; }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MoMo " + name + " is required");
        return value;
    }

    public record Config(String partnerCode, String accessKey, String secretKey, String storeId,
                         String redirectUrl, String ipnUrl, String requestType, String language) {
        void validate() {
            required(partnerCode, "partnerCode"); required(accessKey, "accessKey"); required(secretKey, "secretKey");
            required(storeId, "storeId"); required(redirectUrl, "redirectUrl"); required(ipnUrl, "ipnUrl");
            if (!"payWithMethod".equals(requestType)) throw new IllegalArgumentException("Only approved payWithMethod is supported");
            if (!"vi".equals(language) && !"en".equals(language)) throw new IllegalArgumentException("Unsupported language");
            requireHttps(redirectUrl); requireHttps(ipnUrl);
        }
    }
    public record CreateRequest(Map<String, Object> body, String canonical, String signature, String requestId) {}
    private static void requireHttps(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("MoMo URLs must be absolute HTTPS URLs");
        }
    }
}
