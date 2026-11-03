package com.exe.morphia3d.utils;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

@Component
public class VnPayUtil {

    public String hmacSHA512(String key, String data) {
        try {
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            hmac512.init(secretKey);
            byte[] bytes = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi tạo chữ ký VNPay", e);
        }
    }

    public String buildHashData(Map<String, String> params) {
        List<String> sortedKeys = params.keySet().stream().sorted().toList();
        StringBuilder hashData = new StringBuilder();
        for (int i = 0; i < sortedKeys.size(); i++) {
            String key = sortedKeys.get(i);
            String value = params.get(key);
            if (value != null && !value.isEmpty()) {
                hashData.append(key).append('=').append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
                if (i < sortedKeys.size() - 1) {
                    hashData.append('&');
                }
            }
        }
        return hashData.toString();
    }

    public String getRandomTxnRef() {
        SecureRandom rnd = new SecureRandom();
        return String.valueOf(System.currentTimeMillis()) + rnd.nextInt(1000);
    }
}