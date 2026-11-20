package com.exe.morphia3d.service.payment.payos;

import java.time.Duration;
import java.util.Map;

/** Injection port only. This module intentionally ships without an HTTP implementation. */
public interface PayosTransport {
    HttpResponse createPaymentLink(String endpoint, String clientId, String apiKey,
                                   Map<String, Object> request, Duration timeout);
    HttpResponse getPaymentLink(String endpoint, String clientId, String apiKey,
                                long orderCode, Duration timeout);

    record HttpResponse(int status, String body) {}
}
