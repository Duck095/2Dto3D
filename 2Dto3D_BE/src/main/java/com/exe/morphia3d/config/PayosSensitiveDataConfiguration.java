package com.exe.morphia3d.config;

import com.exe.morphia3d.service.payment.payos.PayosSensitiveDataCipher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** No built-in key: persistence is unavailable unless an operator injects an external key. */
@Configuration
public class PayosSensitiveDataConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.payment.payos.encryption-key-version")
    PayosSensitiveDataCipher payosSensitiveDataCipher(
            org.springframework.core.env.Environment environment) {
        int version = environment.getProperty("app.payment.payos.encryption-key-version", Integer.class, 1);
        if (version <= 0 || version > Short.MAX_VALUE) throw new IllegalStateException("Invalid payOS encryption key version");
        Map<Integer, String> encodedKeys = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("app.payment.payos.encryption-keyring",
                        org.springframework.boot.context.properties.bind.Bindable.mapOf(Integer.class, String.class))
                .orElse(Map.of());
        Map<Integer, byte[]> keys = new HashMap<>();
        try {
            encodedKeys.forEach((keyVersion, encoded) -> {
                if (keyVersion <= 0 || keyVersion > Short.MAX_VALUE) throw new IllegalStateException("Invalid payOS encryption key version");
                keys.put(keyVersion, Base64.getDecoder().decode(encoded));
            });
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid external payOS encryption key configuration");
        }
        return new PayosSensitiveDataCipher(keys, version);
    }
}
