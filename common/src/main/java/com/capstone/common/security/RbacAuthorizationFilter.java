package com.capstone.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DIG-71: RBAC security filter enforcing CUSTOMER vs ADMIN role authorization across endpoints.
 * Enforces fail-closed access controls:
 * - /api/admin/** and /admin/** strictly require ROLE_ADMIN.
 * - /internal/** strictly requires ROLE_INTERNAL or ROLE_ADMIN.
 * - Public routes (/api/auth/**, /actuator/**) bypass RBAC.
 * Violations return RFC 7807 ProblemDetail with 403 Forbidden and error_code=ACCESS_DENIED.
 */
@Slf4j
public class RbacAuthorizationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public RbacAuthorizationFilter() {
        this.objectMapper = new ObjectMapper();
    }

    public RbacAuthorizationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 1. Public paths bypass RBAC checks
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 2. Admin routes: strictly requires ROLE_ADMIN
        if (isAdminPath(path)) {
            if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
                sendUnauthorized(response, request, "Authentication required to access admin endpoints");
                return;
            }

            if (!hasAuthority(authentication, "ROLE_ADMIN")) {
                log.warn("RBAC ACCESS_DENIED: User {} with roles {} attempted to access admin endpoint {}",
                        authentication.getName(), authentication.getAuthorities(), path);
                sendForbidden(response, request, "Customer attempted to access admin endpoint without ROLE_ADMIN");
                return;
            }
        }

        // 3. Internal routes: strictly requires ROLE_INTERNAL or ROLE_ADMIN
        if (isInternalPath(path)) {
            if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
                sendUnauthorized(response, request, "Authentication required to access internal endpoints");
                return;
            }

            if (!hasAuthority(authentication, "ROLE_INTERNAL") && !hasAuthority(authentication, "ROLE_ADMIN")) {
                log.warn("RBAC ACCESS_DENIED: User {} attempted to access internal endpoint {}",
                        authentication.getName(), path);
                sendForbidden(response, request, "Access denied to internal endpoint");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/actuator/")
                || path.equals("/error");
    }

    private boolean isAdminPath(String path) {
        return path.startsWith("/api/admin/") || path.startsWith("/admin/");
    }

    private boolean isInternalPath(String path) {
        return path.startsWith("/internal/");
    }

    private boolean hasAuthority(Authentication authentication, String targetRole) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(auth -> auth.equalsIgnoreCase(targetRole)
                        || auth.equalsIgnoreCase(targetRole.replace("ROLE_", "")));
    }

    private void sendUnauthorized(HttpServletResponse response, HttpServletRequest request, String detail) throws IOException {
        writeRfc7807Response(response, request, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Unauthorized", detail, "https://capstone.bank/errors/invalid-credentials");
    }

    private void sendForbidden(HttpServletResponse response, HttpServletRequest request, String detail) throws IOException {
        writeRfc7807Response(response, request, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "Access Denied", detail, "https://capstone.bank/errors/access-denied");
    }

    private void writeRfc7807Response(HttpServletResponse response, HttpServletRequest request,
                                      HttpStatus status, String errorCode,
                                      String title, String detail, String type) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", URI.create(type));
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", URI.create(request.getRequestURI()));
        body.put("error_code", errorCode);
        body.put("timestamp", Instant.now().toString());

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
