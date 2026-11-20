package com.exe.morphia3d.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public final class FeatureGateFilter extends OncePerRequestFilter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FeatureGate gate;

    public FeatureGateFilter(FeatureGate gate) {
        this.gate = gate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Payment controller and service gate after JWT authentication so anonymous calls remain 401.
        return FeatureRouteResolver.familyFor(path).orElse(null) == FeatureFamily.PAYMENT;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (FeatureFamily requiredFamily : FeatureRouteResolver.requiredFamiliesFor(request.getMethod(), path)) {
            if (!gate.isEnabled(requiredFamily)) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                JSON.writeValue(response.getWriter(),
                        new FeatureDisabledResponse("FEATURE_DISABLED", requiredFamily.name()));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    public record FeatureDisabledResponse(String code, String featureFamily) {}
}
