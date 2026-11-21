package com.exe.morphia3d.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthFilterOutageTest {
    private static final String EMAIL = "mesh-admin@example.invalid";
    private static final String SECRET = "x".repeat(40);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"57P01", "08006", "08003"})
    void adminMeshEndpointReturnsSanitized503ForJpaWrappedConnectionSqlStates(String sqlState) throws Exception {
        UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
        JwtAuthFilter filter = filter(users);
        String token = adminToken();
        SQLException connectionFailure = new SQLException("private database detail", sqlState);
        when(users.loadUserByUsername(EMAIL)).thenThrow(new JpaSystemException(
                new IllegalStateException("user lookup failed", connectionFailure)));
        MockHttpServletRequest request = request("GET", "/api/admin/products/7/mesh-structure", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilterInternal(request, response, (servletRequest, servletResponse) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\":\"PERSISTENCE_UNAVAILABLE\",\"message\":\"The persistence service is temporarily unavailable.\"}");
        assertThat(response.getContentAsString()).doesNotContain("private", sqlState, token, "SQLException");
        assertThat(continued).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(users).loadUserByUsername(EMAIL);
    }

    @Test
    void adminMeshEndpointKeepsExistingResourceFailureMapping() throws Exception {
        UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
        JwtAuthFilter filter = filter(users);
        when(users.loadUserByUsername(EMAIL)).thenThrow(
                new DataAccessResourceFailureException("private resource detail"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request("GET", "/api/admin/products/7/mesh-configurations", adminToken()),
                response, (servletRequest, servletResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("PERSISTENCE_UNAVAILABLE")
                .doesNotContain("private resource detail");
    }

    @Test
    void unrelatedSqlFailureIsNotConvertedTo503() throws Exception {
        UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
        JwtAuthFilter filter = filter(users);
        SQLException constraintFailure = new SQLException("duplicate key", "23505");
        JpaSystemException persistenceFailure = new JpaSystemException(
                new IllegalStateException("user lookup failed", constraintFailure));
        when(users.loadUserByUsername(EMAIL)).thenThrow(persistenceFailure);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilterInternal(
                request("GET", "/api/admin/products/7/mesh-structure", adminToken()), response,
                (servletRequest, servletResponse) -> { }))
                .isSameAs(persistenceFailure);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void connectionFailureOnUnrelatedEndpointIsNotConvertedTo503() throws Exception {
        UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
        JwtAuthFilter filter = filter(users);
        JpaSystemException persistenceFailure = new JpaSystemException(
                new IllegalStateException("lookup failed", new SQLException("connection lost", "57P01")));
        when(users.loadUserByUsername(EMAIL)).thenThrow(persistenceFailure);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilterInternal(
                request("GET", "/api/admin/products", adminToken()), response,
                (servletRequest, servletResponse) -> { }))
                .isSameAs(persistenceFailure);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void invalidJwtDoesNotPerformUserLookupOrAuthorizeRequest() throws Exception {
        UserDetailsServiceImpl users = mock(UserDetailsServiceImpl.class);
        JwtAuthFilter filter = filter(users);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilterInternal(request("GET", "/api/admin/products/7/mesh-structure", "not-a-jwt"), response,
                (servletRequest, servletResponse) -> continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users);
    }

    private static JwtAuthFilter filter(UserDetailsServiceImpl users) {
        return new JwtAuthFilter(new JwtUtil(SECRET, 60_000), users);
    }

    private static String adminToken() {
        return new JwtUtil(SECRET, 60_000).generateToken(User.withUsername(EMAIL)
                .password("test-only")
                .roles("ADMIN")
                .build());
    }

    private static MockHttpServletRequest request(String method, String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
