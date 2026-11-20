package com.exe.morphia3d.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayosMerchantBindingPropertiesTest {
    @Test
    void defaultsToIncompleteConfigurationAndDoesNotExposeSecretsInToString() {
        PayosMerchantBindingProperties properties = new PayosMerchantBindingProperties();

        assertThat(properties.isComplete()).isFalse();
        assertThat(properties.getApiEndpoint()).isEqualTo("https://api-merchant.payos.vn");
        assertThat(properties.toString()).doesNotContain(
                "TEST_ONLY_CLIENT_ID", "TEST_ONLY_API_KEY", "TEST_ONLY_CHECKSUM_KEY", "123456789");
    }

    @Test
    void completeMerchantConfigurationRequiresChannelCredentialsReceivingAccountAndHttpsUrls() {
        PayosMerchantBindingProperties properties = completeProperties();

        assertThat(properties.isComplete()).isTrue();
        properties.setChecksumKey("");
        assertThat(properties.isComplete()).isFalse();
    }

    @Test
    void unsafeUrlsUnknownReturnHostsAndInvalidExpirationFailClosed() {
        PayosMerchantBindingProperties properties = completeProperties();

        properties.setWebhookUrl("http://payments.example/webhook");
        assertThat(properties.isComplete()).isFalse();
        properties.setWebhookUrl("https://payments.example/webhook");

        properties.setReturnUrl("https://attacker.example/return");
        assertThat(properties.isComplete()).isFalse();
        properties.setReturnUrl("https://shop.example/return");

        properties.setLinkExpiration(Duration.ofSeconds((long) Integer.MAX_VALUE + 1));
        assertThat(properties.isComplete()).isFalse();

        properties.setLinkExpiration(Duration.ofDays(365L * 12));
        assertThat(properties.isComplete()).isFalse();
    }

    @Test
    void merchantConfigurationCannotAuthorizeLiveProvenanceOrProviderTransport() {
        PayosMerchantBindingProperties properties = completeProperties();

        assertThat(properties.isComplete()).isTrue();
        assertThat(properties.isTransportEnabled()).isFalse();
        assertThat(properties.isLiveProvenanceEnabled()).isFalse();

        PayosMerchantBindingProperties switchedBinding = completeProperties();
        switchedBinding.setChannelId("different-channel-after-restart");
        switchedBinding.setChecksumKey("different-key-version");
        assertThat(switchedBinding.isTransportEnabled()).isFalse();
        assertThat(switchedBinding.isLiveProvenanceEnabled()).isFalse();
    }

    private PayosMerchantBindingProperties completeProperties() {
        PayosMerchantBindingProperties properties = new PayosMerchantBindingProperties();
        properties.setChannelId("channel-test");
        properties.setClientId("TEST_ONLY_CLIENT_ID");
        properties.setApiKey("TEST_ONLY_API_KEY");
        properties.setChecksumKey("TEST_ONLY_CHECKSUM_KEY");
        properties.setReceivingBankBin("970422");
        properties.setReceivingAccountNumber("123456789");
        properties.setReceivingAccountName("TEST ACCOUNT");
        properties.setWebhookUrl("https://shop.example/api/webhooks/payos/channel-test");
        properties.setReturnUrl("https://shop.example/payment/result");
        properties.setCancelUrl("https://shop.example/checkout/cancelled");
        properties.setAllowedApplicationHosts(Set.of("shop.example"));
        return properties;
    }
}
