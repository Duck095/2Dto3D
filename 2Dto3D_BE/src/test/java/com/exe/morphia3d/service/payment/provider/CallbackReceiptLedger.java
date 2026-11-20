package com.exe.morphia3d.service.payment.provider;

import java.util.concurrent.ConcurrentHashMap;

/** Concurrent in-memory model used only for offline duplicate-delivery contract tests. */
final class CallbackReceiptLedger {
    private final ConcurrentHashMap<String, String> digests = new ConcurrentHashMap<>();

    Outcome record(String provider, String eventKey, String digest) {
        if (provider == null || provider.isBlank() || eventKey == null || eventKey.isBlank() || digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("Receipt identity and digest are required");
        }
        String key = provider + ":" + eventKey;
        String prior = digests.putIfAbsent(key, digest);
        if (prior == null) return Outcome.RECORDED;
        return prior.equals(digest) ? Outcome.DUPLICATE : Outcome.CONFLICT;
    }

    enum Outcome { RECORDED, DUPLICATE, CONFLICT }
}
