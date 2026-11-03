package com.exe.morphia3d.config;

import com.exe.morphia3d.security.JwtAuthFilter;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

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

    private CorsConfiguration configurationFor(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        SecurityConfig config = new SecurityConfig(mock(UserDetailsServiceImpl.class), mock(JwtAuthFilter.class), environment);
        return config.corsConfigurationSource().getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/me"));
    }
}
