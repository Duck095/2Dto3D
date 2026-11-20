package com.exe.morphia3d.service.payment.provider;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

public final class RedirectUrlPolicy {
    private RedirectUrlPolicy() {}

    public static boolean validate(String rawUrl, Set<String> allowedHosts, Instant now, Instant expiresAt, boolean alreadyConsumed) {
        if (rawUrl == null || allowedHosts == null || allowedHosts.isEmpty() || now == null || expiresAt == null
                || alreadyConsumed || !now.isBefore(expiresAt)) return false;
        return allowedHttps(rawUrl, allowedHosts);
    }

    public static boolean allowedHttps(String rawUrl, Set<String> allowedHosts) {
        if (rawUrl == null || allowedHosts == null || allowedHosts.isEmpty()) return false;
        try {
            URI uri = URI.create(rawUrl);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && allowedHosts.stream().anyMatch(host -> host.equalsIgnoreCase(uri.getHost()));
        } catch (Exception invalid) { return false; }
    }
}
