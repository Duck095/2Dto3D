package com.exe.morphia3d.feature;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/** Blocks media upload routes in QA before upload orchestration can persist an intent. */
public final class QaMediaUploadFilter extends OncePerRequestFilter {
    private final boolean adminProductUploadEnabled;

    public QaMediaUploadFilter() {
        this(false);
    }

    public QaMediaUploadFilter(boolean adminProductUploadEnabled) {
        this.adminProductUploadEnabled = adminProductUploadEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        return request.getDispatcherType() != DispatcherType.REQUEST
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !isMultipart(request.getContentType())
                || FeatureRouteResolver.familyFor(path).isPresent()
                || adminProductUploadEnabled && "/api/admin/products".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"FEATURE_DISABLED\",\"featureFamily\":\"MEDIA\"}");
    }

    private boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    private String pathWithinApplication(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
