package com.exe.morphia3d.service.payment.provider;

import java.time.Duration;
import java.util.Map;

/** Transport port. This task intentionally provides no network implementation. */
@FunctionalInterface
public interface MomoTransport {
    String postJson(String endpoint, Map<String, Object> body, Duration timeout);
}
