package com.exe.morphia3d.security;

import com.exe.morphia3d.config.MomoConfig;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.userdetails.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SecretConfigurationHardeningTest {
    private static final String VALID_JWT_KEY = "test-only-jwt-signing-key-with-at-least-32-bytes";
    private static final String PARTNER_CODE = "test-partner-code";
    private static final String ACCESS_KEY = "test-access-key";
    private static final String MOMO_SECRET = "test-momo-secret-never-for-live-use";

    private final ApplicationContextRunner jwtRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtTestConfiguration.class);

    private final ApplicationContextRunner momoRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(MomoTestConfiguration.class);

    @Test
    void validJwtKeySignsAndVerifiesTokenWithExistingSubjectAndRoleClaims() {
        jwtRunner.withPropertyValues("jwt.secret=" + VALID_JWT_KEY, "jwt.expiration=3600000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtUtil jwt = context.getBean(JwtUtil.class);
                    var user = User.withUsername("designer@example.test")
                            .password("unused")
                            .authorities("ROLE_DESIGNER")
                            .build();
                    String token = jwt.generateToken(user);

                    assertThat(jwt.isTokenValid(token, user)).isTrue();
                    assertThat(jwt.extractEmail(token)).isEqualTo("designer@example.test");
                    assertThat(Jwts.parserBuilder().setSigningKey(VALID_JWT_KEY.getBytes())
                            .build().parseClaimsJws(token).getBody().get("role", String.class))
                            .isEqualTo("ROLE_DESIGNER");
                });
    }

    @Test
    void missingJwtKeyFailsSpringContextWithoutPrintingASecret() {
        jwtRunner.withPropertyValues("jwt.expiration=3600000").run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context.getStartupFailure()))
                    .contains("jwt.secret")
                    .doesNotContain(VALID_JWT_KEY);
        });
    }

    @Test
    void blankAndShortJwtKeysFailSpringContextWithoutPrintingTheirValues() {
        for (String invalidKey : List.of("   ", "short-test-key")) {
            jwtRunner.withPropertyValues("jwt.secret=" + invalidKey, "jwt.expiration=3600000")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(failureMessages(context.getStartupFailure()))
                                .contains("jwt.secret")
                                .doesNotContain(invalidKey);
                    });
        }
    }

    @Test
    void validMomoPropertiesBindAtStartupWithoutStringifyingCredentialValues() {
        momoRunner.withPropertyValues(validMomoProperties()).run(context -> {
            assertThat(context).hasNotFailed();
            MomoConfig config = context.getBean(MomoConfig.class);
            assertThat(config.getPartnerCode()).isEqualTo(PARTNER_CODE);
            assertThat(config.getAccessKey()).isEqualTo(ACCESS_KEY);
            assertThat(config.getSecretKey()).isEqualTo(MOMO_SECRET);
            assertThat(config.toString()).doesNotContain(PARTNER_CODE, ACCESS_KEY, MOMO_SECRET);
        });
    }

    @Test
    void testProfileRetainsIsolatedDummyCredentials() throws Exception {
        Properties properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application-test.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }

        for (String property : List.of("jwt.secret", "momo.partner-code", "momo.access-key", "momo.secret-key")) {
            String value = properties.getProperty(property);
            assertThat(value).isNotBlank().doesNotContain("${");
        }
    }

    @Test
    void missingAndBlankMomoCredentialsFailBindingAndNameTheInvalidPropertyWithoutValues() {
        Map<String, String> required = Map.of(
                "momo.partner-code", PARTNER_CODE,
                "momo.access-key", ACCESS_KEY,
                "momo.secret-key", MOMO_SECRET);

        for (String missingProperty : required.keySet()) {
            String[] values = required.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(missingProperty))
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toArray(String[]::new);
            momoRunner.withPropertyValues(values).run(context -> {
                assertThat(context).hasFailed();
                String messages = failureMessages(context.getStartupFailure());
                assertThat(messages).contains(missingProperty);
                assertThat(messages).doesNotContain(PARTNER_CODE, ACCESS_KEY, MOMO_SECRET);
            });
        }

        for (String blankProperty : required.keySet()) {
            String[] values = required.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + (entry.getKey().equals(blankProperty) ? "   " : entry.getValue()))
                    .toArray(String[]::new);
            momoRunner.withPropertyValues(values).run(context -> {
                assertThat(context).hasFailed();
                String messages = failureMessages(context.getStartupFailure());
                assertThat(messages).contains(blankProperty);
                assertThat(messages).doesNotContain(PARTNER_CODE, ACCESS_KEY, MOMO_SECRET);
            });
        }
    }

    private static String[] validMomoProperties() {
        return new String[]{
                "momo.partner-code=" + PARTNER_CODE,
                "momo.access-key=" + ACCESS_KEY,
                "momo.secret-key=" + MOMO_SECRET,
                "momo.endpoint=http://localhost.invalid/momo/create",
                "momo.redirect-url=http://localhost.invalid/momo/return",
                "momo.ipn-url=http://localhost.invalid/momo/ipn",
                "momo.request-type=captureWallet"
        };
    }

    private static String failureMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
        }
        return String.join("\n", messages);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JwtUtil.class)
    static class JwtTestConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MomoConfig.class)
    static class MomoTestConfiguration {
    }
}
