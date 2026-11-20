package com.exe.morphia3d.service.payment.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Offline implementation of the public VNPay PAY 2.1.0 GET signing contract. */
public final class VnPayProtocol {
    private static final long MAX_PROVIDER_AMOUNT = 999_999_999_999L;

    private VnPayProtocol() {}

    public static long amountToProvider(long amountVnd) {
        if (amountVnd <= 0) throw new IllegalArgumentException("VND amount must be positive");
        try {
            long providerAmount = Math.multiplyExact(amountVnd, 100L);
            if (providerAmount > MAX_PROVIDER_AMOUNT) throw new IllegalArgumentException("VNPay amount exceeds PAY field limit");
            return providerAmount;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("VNPay amount overflow", overflow);
        }
    }

    public static String createPaymentUrl(String endpoint, String secret, PaymentFields fields) {
        if (endpoint == null || !endpoint.startsWith("https://") || secret == null || secret.isBlank() || fields == null) {
            throw new IllegalArgumentException("HTTPS endpoint, signing secret and payment fields are required");
        }
        Map<String, String> params = fields.toParameters();
        String signature = sign(canonical(params), secret);
        params.put("vnp_SecureHash", signature);
        String query = params.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElseThrow();
        return endpoint + "?" + query;
    }

    public static boolean verifyQuery(String rawQuery, String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Signing secret is required");
        Map<String, String> params = parseQuery(rawQuery);
        String signature = params.remove("vnp_SecureHash");
        params.remove("vnp_SecureHashType");
        if (signature == null || !signature.matches("(?i)[0-9a-f]{128}")) return false;
        return MessageDigest.isEqual(sign(canonical(params), secret).getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) throw new IllegalArgumentException("VNPay query is required");
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) throw new IllegalArgumentException("Empty VNPay query parameter");
            int separator = pair.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("Malformed VNPay query parameter");
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate VNPay query parameter");
        }
        return values;
    }

    public static String canonical(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (!"vnp_SecureHash".equals(key) && !"vnp_SecureHashType".equals(key)) {
                if (key == null || value == null) throw new IllegalArgumentException("Null VNPay parameter");
                sorted.put(key, value);
            }
        });
        List<String> fields = new ArrayList<>();
        sorted.forEach((key, value) -> fields.add(encode(key) + "=" + encode(value)));
        return String.join("&", fields);
    }

    public static String sign(String canonical, String secret) {
        return hmac("HmacSHA512", canonical, secret);
    }

    private static String hmac(String algorithm, String data, String secret) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to calculate VNPay signature", error);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    public static final class PaymentFields {
        private final String tmnCode, txnRef, currency, orderInfo, orderType, locale, returnUrl, ipAddress, createDate, expireDate;
        private final long amountVnd;

        private PaymentFields(Builder b) {
            tmnCode = b.tmnCode; txnRef = b.txnRef; amountVnd = b.amountVnd; currency = b.currency;
            orderInfo = b.orderInfo; orderType = b.orderType; locale = b.locale; returnUrl = b.returnUrl;
            ipAddress = b.ipAddress; createDate = b.createDate; expireDate = b.expireDate;
        }

        private Map<String, String> toParameters() {
            require(tmnCode, "tmnCode"); require(txnRef, "txnRef"); require(currency, "currency");
            require(orderInfo, "orderInfo"); require(orderType, "orderType"); require(locale, "locale");
            require(returnUrl, "returnUrl"); require(ipAddress, "ipAddress"); require(createDate, "createDate"); require(expireDate, "expireDate");
            if (!txnRef.matches("[A-Za-z0-9]{1,100}")) throw new IllegalArgumentException("VNPay TxnRef must be alphanumeric");
            if (!createDate.matches("[0-9]{14}") || !expireDate.matches("[0-9]{14}") || expireDate.compareTo(createDate) <= 0) {
                throw new IllegalArgumentException("VNPay dates must be ordered yyyyMMddHHmmss values");
            }
            if (!"VND".equals(currency)) throw new IllegalArgumentException("Only VND is supported by this foundation");
            Map<String, String> p = new LinkedHashMap<>();
            p.put("vnp_Version", "2.1.0"); p.put("vnp_Command", "pay"); p.put("vnp_TmnCode", tmnCode);
            p.put("vnp_Amount", Long.toString(amountToProvider(amountVnd))); p.put("vnp_CurrCode", currency);
            p.put("vnp_TxnRef", txnRef); p.put("vnp_OrderInfo", orderInfo); p.put("vnp_OrderType", orderType);
            p.put("vnp_Locale", locale); p.put("vnp_ReturnUrl", returnUrl); p.put("vnp_IpAddr", ipAddress);
            p.put("vnp_CreateDate", createDate); p.put("vnp_ExpireDate", expireDate);
            return p;
        }

        private static void require(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("VNPay " + field + " is required");
        }
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private String tmnCode, txnRef, currency, orderInfo, orderType, locale, returnUrl, ipAddress, createDate, expireDate;
            private long amountVnd;
            public Builder tmnCode(String v) { tmnCode=v; return this; } public Builder txnRef(String v) { txnRef=v; return this; }
            public Builder amountVnd(long v) { amountVnd=v; return this; } public Builder currency(String v) { currency=v; return this; }
            public Builder orderInfo(String v) { orderInfo=v; return this; } public Builder orderType(String v) { orderType=v; return this; }
            public Builder locale(String v) { locale=v; return this; } public Builder returnUrl(String v) { returnUrl=v; return this; }
            public Builder ipAddress(String v) { ipAddress=v; return this; } public Builder createDate(String v) { createDate=v; return this; }
            public Builder expireDate(String v) { expireDate=v; return this; } public PaymentFields build() { return new PaymentFields(this); }
        }
    }
}
