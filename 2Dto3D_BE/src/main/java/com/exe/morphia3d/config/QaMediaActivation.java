package com.exe.morphia3d.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Validated, immutable selection for QA media isolation and provider construction. */
public final class QaMediaActivation {
    public enum Mode { NON_QA, QA_DISABLED, QA_LIVE }

    private static final Set<String> SENTINELS = Set.of("qa-disabled", "disabled", "unset", "placeholder",
            "changeme", "change-me", "your-cloud-name", "example", "dummy", "test");

    private final Mode mode;
    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final String proxyHost;
    private final int proxyPort;

    private QaMediaActivation(Mode mode, String cloudName, String apiKey, String apiSecret,
                              String proxyHost, int proxyPort) {
        this.mode = mode;
        this.cloudName = cloudName;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
    }

    public static QaMediaActivation resolve(Environment environment) {
        QaMediaActivationProperties properties = Binder.get(environment)
                .bind("app.qa-media", QaMediaActivationProperties.class)
                .orElseGet(QaMediaActivationProperties::new);
        String[] active = environment.getActiveProfiles();
        boolean qa = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("qa"));

        if (qa) {
            String[] effective = active.length == 0 ? environment.getDefaultProfiles() : active;
            if (effective.length != 1 || !Arrays.asList(effective).contains("qa")) {
                throw new IllegalStateException("QA media requires the qa profile to be active by itself");
            }
            if (!properties.isLiveEnabled()) {
                return new QaMediaActivation(Mode.QA_DISABLED, null, null, null, null, 0);
            }
            if (!properties.isLiveApproved()) {
                throw new IllegalStateException("QA media-live requires explicit approval");
            }
            validateCloudName(properties.getCloudName());
            validateApiKey(properties.getApiKey());
            validateApiSecret(properties.getApiSecret());
            String proxyHost = validateProxy(properties.getProxyHost(), properties.getProxyPort());
            return new QaMediaActivation(Mode.QA_LIVE, properties.getCloudName().trim(),
                    properties.getApiKey(), properties.getApiSecret(), proxyHost,
                    proxyHost == null ? 0 : properties.getProxyPort());
        }

        if (properties.isLiveEnabled() || properties.isLiveApproved()
                || nonBlank(properties.getApiKey()) || nonBlank(properties.getApiSecret())
                || nonBlank(properties.getCloudName())
                || nonBlank(properties.getProxyHost()) || properties.getProxyPort() != 0
                || nonBlank(environment.getProperty("QA_MEDIA_LIVE_APPROVED"))
                || nonBlank(environment.getProperty("QA_CLOUDINARY_CLOUD_NAME"))
                || nonBlank(environment.getProperty("QA_CLOUDINARY_API_KEY"))
                || nonBlank(environment.getProperty("QA_CLOUDINARY_API_SECRET"))
                || nonBlank(environment.getProperty("QA_CLOUDINARY_PROXY_HOST"))
                || nonBlank(environment.getProperty("QA_CLOUDINARY_PROXY_PORT"))) {
            throw new IllegalStateException("QA media activation settings cannot be used outside the qa profile");
        }
        return new QaMediaActivation(Mode.NON_QA, null, null, null, null, 0);
    }

    private static String validateProxy(String host, int port) {
        boolean hostPresent = nonBlank(host);
        boolean portPresent = port != 0;
        if (!hostPresent && !portPresent) return null;
        if (!hostPresent || port < 1 || port > 65535) {
            throw new IllegalStateException("QA Cloudinary proxy host and port must be configured together");
        }
        String value = host.trim();
        if (value.length() > 253 || value.contains("://") || value.contains("/")
                || value.contains("@") || value.contains("?") || value.contains("#")
                || !value.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) {
            throw new IllegalStateException("QA Cloudinary proxy host is invalid");
        }
        for (String label : value.split("\\.")) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalStateException("QA Cloudinary proxy host is invalid");
            }
        }
        return value;
    }

    private static void validateCloudName(String cloudName) {
        String value = cloudName == null ? "" : cloudName.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || SENTINELS.contains(normalized) || value.contains("${")
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,254}")) {
            throw new IllegalStateException("A valid dedicated QA Cloudinary cloud name is required");
        }
    }

    private static void validateCredential(String value, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || value.contains("${") || value.contains("<") || value.contains(">")
                || SENTINELS.contains(normalized) || normalized.contains("placeholder")
                || normalized.contains("changeme") || normalized.contains("your_")) {
            throw new IllegalStateException(label + " is required and must not be a placeholder");
        }
    }

    private static void validateApiKey(String value) {
        validateCredential(value, "QA Cloudinary API key");
        if (!value.trim().matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalStateException("QA Cloudinary API key format is invalid");
        }
    }

    private static void validateApiSecret(String value) {
        validateCredential(value, "QA Cloudinary API secret");
        if (value.trim().length() < 16) {
            throw new IllegalStateException("QA Cloudinary API secret is invalid");
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    public Mode mode() { return mode; }
    public String cloudName() { return cloudName; }
    public String apiKey() { return apiKey; }
    public String apiSecret() { return apiSecret; }
    public String proxyHost() { return proxyHost; }
    public int proxyPort() { return proxyPort; }
}
