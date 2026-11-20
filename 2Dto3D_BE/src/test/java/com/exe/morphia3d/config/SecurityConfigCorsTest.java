package com.exe.morphia3d.config;

import com.exe.morphia3d.security.JwtAuthFilter;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.feature.FeatureGateProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.CorsConfiguration;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityConfigCorsTest {

    @Test
    void loopbackFrontendOriginIsLimitedToDevelopmentAndTestProfiles() {
        for (String profile : List.of("dev", "test")) {
            CorsConfiguration configuration = configurationFor(profile);
            assertThat(configuration.getAllowedOrigins()).contains("http://localhost:3000", "http://localhost:5173",
                    "http://127.0.0.1:3000");
        }

        CorsConfiguration production = configurationFor("prod");
        assertThat(production.getAllowedOrigins()).contains("http://localhost:3000", "http://localhost:5173")
                .doesNotContain("http://127.0.0.1:3000");
    }

    @Test
    void qaProfileAllowsTheLoopbackNginxOrigin() {
        CorsConfiguration qa = configurationFor("qa");

        assertThat(qa.getAllowedOrigins()).contains("http://127.0.0.1:8088");
    }

    @Test
    void allowedOriginCanPreflightPostWithIdempotencyAndLegacyHeaders() throws Exception {
        String origin = "http://localhost:3000";
        MockHttpServletResponse response = preflight("prod", origin, "POST",
                "Idempotency-Key, Authorization, Content-Type, X-Requested-With");

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST");
        assertThat(response.getHeader("Access-Control-Allow-Headers"))
                .contains("Idempotency-Key", "Authorization", "Content-Type", "X-Requested-With")
                .doesNotContain("*");

        CorsConfiguration configuration = configurationFor("prod");
        assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowedHeaders()).containsExactly(
                "Authorization", "Content-Type", "X-Requested-With", "Idempotency-Key").doesNotContain("*");
        assertThat(configuration.getAllowedOrigins()).doesNotContain("*");
    }

    @Test
    void preflightFromDisallowedOriginReceivesNoCorsGrant() throws Exception {
        MockHttpServletResponse response = preflight("prod", "https://unapproved.example", "POST", "Idempotency-Key");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void qaOriginIsGrantedOnlyByQaProfile() throws Exception {
        String qaOrigin = "http://127.0.0.1:8088";

        MockHttpServletResponse qaResponse = preflight("qa", qaOrigin, "POST", "Idempotency-Key");
        MockHttpServletResponse prodResponse = preflight("prod", qaOrigin, "POST", "Idempotency-Key");

        assertThat(qaResponse.getHeader("Access-Control-Allow-Origin")).isEqualTo(qaOrigin);
        assertThat(prodResponse.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void developmentOnlyOriginIsNotGrantedByProductionProfile() throws Exception {
        String devOrigin = "http://127.0.0.1:3000";

        MockHttpServletResponse devResponse = preflight("dev", devOrigin, "POST", "Idempotency-Key");
        MockHttpServletResponse prodResponse = preflight("prod", devOrigin, "POST", "Idempotency-Key");

        assertThat(devResponse.getHeader("Access-Control-Allow-Origin")).isEqualTo(devOrigin);
        assertThat(prodResponse.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    private CorsConfiguration configurationFor(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        SecurityConfig config = new SecurityConfig(mock(UserDetailsServiceImpl.class), mock(JwtAuthFilter.class),
                environment, new FeatureGate(new FeatureGateProperties()));
        return config.corsConfigurationSource().getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/me"));
    }

    private MockHttpServletResponse preflight(String profile, String origin, String method, String headers)
            throws IOException, ServletException {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        SecurityConfig config = new SecurityConfig(mock(UserDetailsServiceImpl.class), mock(JwtAuthFilter.class),
                environment, new FeatureGate(new FeatureGateProperties()));

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/design/projects");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        request.addHeader("Access-Control-Request-Headers", headers);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        new CorsFilter(config.corsConfigurationSource()).doFilter(request, response, chain);
        return response;
    }
}
