package com.exe.morphia3d.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Secret-manager/environment binding metadata only. It deliberately cannot enable
 * provider transport or grant LIVE provenance in this foundation step.
 */
@ConfigurationProperties(prefix = "app.payment.payos.merchant")
public class PayosMerchantBindingProperties {
    public static final String PRODUCTION_API_ENDPOINT = "https://api-merchant.payos.vn";

    private String channelId;
    private String clientId;
    private String apiKey;
    private String checksumKey;
    private String receivingBankBin;
    private String receivingAccountNumber;
    private String receivingAccountName;
    private String webhookUrl;
    private String returnUrl;
    private String cancelUrl;
    private Set<String> allowedApplicationHosts = Set.of();
    private Set<String> allowedCheckoutHosts = Set.of("pay.payos.vn");
    private Duration linkExpiration = Duration.ofMinutes(20);
    private Duration requestTimeout = Duration.ofSeconds(5);

    public String getApiEndpoint() { return PRODUCTION_API_ENDPOINT; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getChecksumKey() { return checksumKey; }
    public void setChecksumKey(String checksumKey) { this.checksumKey = checksumKey; }
    public String getReceivingBankBin() { return receivingBankBin; }
    public void setReceivingBankBin(String receivingBankBin) { this.receivingBankBin = receivingBankBin; }
    public String getReceivingAccountNumber() { return receivingAccountNumber; }
    public void setReceivingAccountNumber(String receivingAccountNumber) { this.receivingAccountNumber = receivingAccountNumber; }
    public String getReceivingAccountName() { return receivingAccountName; }
    public void setReceivingAccountName(String receivingAccountName) { this.receivingAccountName = receivingAccountName; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public void setCancelUrl(String cancelUrl) { this.cancelUrl = cancelUrl; }
    public Set<String> getAllowedApplicationHosts() { return allowedApplicationHosts; }
    public void setAllowedApplicationHosts(Set<String> allowedApplicationHosts) {
        this.allowedApplicationHosts = allowedApplicationHosts == null ? Set.of() : Set.copyOf(allowedApplicationHosts);
    }
    public Set<String> getAllowedCheckoutHosts() { return allowedCheckoutHosts; }
    public void setAllowedCheckoutHosts(Set<String> allowedCheckoutHosts) {
        this.allowedCheckoutHosts = allowedCheckoutHosts == null ? Set.of() : Set.copyOf(allowedCheckoutHosts);
    }
    public Duration getLinkExpiration() { return linkExpiration; }
    public void setLinkExpiration(Duration linkExpiration) { this.linkExpiration = linkExpiration; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }

    /** Completeness is advisory only: no gateway, webhook route, or LIVE provenance consumes it yet. */
    public boolean isComplete() {
        return hasText(channelId) && channelId.length() <= 128
                && hasText(clientId) && clientId.length() <= 256
                && hasText(apiKey) && apiKey.length() <= 512
                && hasText(checksumKey) && checksumKey.length() <= 512
                && receivingBankBin != null && receivingBankBin.matches("[0-9]{6,8}")
                && receivingAccountNumber != null && receivingAccountNumber.matches("[0-9]{6,24}")
                && hasText(receivingAccountName) && receivingAccountName.length() <= 128
                && validApplicationUrl(webhookUrl) && validApplicationUrl(returnUrl) && validApplicationUrl(cancelUrl)
                && allowedCheckoutHosts.contains("pay.payos.vn")
                && linkExpiration != null && !linkExpiration.isNegative() && !linkExpiration.isZero()
                && providerExpiryFitsInt32(linkExpiration)
                && requestTimeout != null && !requestTimeout.isNegative() && !requestTimeout.isZero()
                && requestTimeout.compareTo(Duration.ofSeconds(30)) <= 0
                && !PRODUCTION_API_ENDPOINT.isBlank();
    }

    /** These are code-level locks, not environment properties; a later task needs explicit approval to change them. */
    public boolean isTransportEnabled() { return false; }
    public boolean isLiveProvenanceEnabled() { return false; }

    @Override
    public String toString() {
        return "PayosMerchantBindingProperties{complete=" + isComplete()
                + ", transportEnabled=false, liveProvenanceEnabled=false}";
    }

    private boolean validApplicationUrl(String value) {
        if (!hasText(value) || allowedApplicationHosts == null || allowedApplicationHosts.isEmpty()) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null && uri.getPort() == -1
                    && allowedApplicationHosts.stream().anyMatch(host -> host.equalsIgnoreCase(uri.getHost()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private boolean providerExpiryFitsInt32(Duration expiration) {
        try {
            return Instant.now().plus(expiration).getEpochSecond() <= Integer.MAX_VALUE;
        } catch (ArithmeticException | java.time.DateTimeException invalid) {
            return false;
        }
    }
}
