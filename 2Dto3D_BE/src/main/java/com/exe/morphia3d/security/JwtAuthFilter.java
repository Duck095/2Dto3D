package com.exe.morphia3d.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> POSTGRESQL_UNAVAILABLE_STATES = Set.of("57P01", "57P02", "57P03", "57P04");

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String email = jwtUtil.extractEmail(token);
                if (email != null && !email.isBlank()) {
                    UserDetails userDetails = null;
                    try {
                        userDetails = userDetailsService.loadUserByUsername(email);
                    } catch (UsernameNotFoundException missingAccount) {
                        SecurityContextHolder.clearContext();
                    } catch (DataAccessException persistenceFailure) {
                        if (!isAdminMeshEndpoint(request) || !isConnectionUnavailable(persistenceFailure)) {
                            throw persistenceFailure;
                        }
                        SecurityContextHolder.clearContext();
                        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        response.setContentType("application/json");
                        response.setHeader("Cache-Control", "no-store");
                        response.getWriter().write("{\"code\":\"PERSISTENCE_UNAVAILABLE\",\"message\":\"The persistence service is temporarily unavailable.\"}");
                        return;
                    }
                    if (userDetails != null && userDetails.isEnabled() && jwtUtil.isTokenValid(token, userDetails)) {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            }
        } catch (JwtException | IllegalArgumentException invalidToken) {
            SecurityContextHolder.clearContext();
            log.debug("Ignoring invalid bearer token");
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAdminMeshEndpoint(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }

        String[] segments = uri.split("/", -1);
        if (segments.length != 6 || !segments[1].equals("api") || !segments[2].equals("admin")
                || !segments[3].equals("products") || segments[4].isBlank()) {
            return false;
        }

        String method = request.getMethod();
        return ("GET".equals(method) && ("mesh-structure".equals(segments[5])
                || "mesh-configurations".equals(segments[5])))
                || ("PUT".equals(method) && "mesh-configurations".equals(segments[5]));
    }

    private boolean isConnectionUnavailable(DataAccessException failure) {
        if (failure instanceof DataAccessResourceFailureException) {
            return true;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sqlException) {
                if (isConnectionUnavailableSqlState(sqlException.getSQLState())) {
                    return true;
                }
                if (sqlException.getNextException() != null) {
                    pending.addLast(sqlException.getNextException());
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
        }
        return false;
    }

    private boolean isConnectionUnavailableSqlState(String sqlState) {
        if (sqlState == null) {
            return false;
        }
        String normalized = sqlState.toUpperCase(Locale.ROOT);
        return normalized.startsWith("08")
                || POSTGRESQL_UNAVAILABLE_STATES.contains(normalized);
    }
}
