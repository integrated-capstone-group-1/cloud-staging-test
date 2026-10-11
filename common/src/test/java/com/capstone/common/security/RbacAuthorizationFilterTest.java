package com.capstone.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RbacAuthorizationFilterTest {

    private RbacAuthorizationFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new RbacAuthorizationFilter();
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String customerId, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                customerId,
                null,
                List.of(new SimpleGrantedAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("DIG-71: Public endpoint /api/auth/login bypasses RBAC checks")
    void testPublicEndpointBypassesRbac() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("DIG-71: Customer accessing /api/admin/** is rejected with 403 Forbidden and ACCESS_DENIED")
    void testCustomerAccessingAdminEndpoint_forbidden() throws ServletException, IOException {
        authenticate("cust-001", "ROLE_CUSTOMER");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/customers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("ACCESS_DENIED");
        assertThat(response.getContentAsString()).contains("Customer attempted to access admin endpoint");
    }

    @Test
    @DisplayName("DIG-71: Customer accessing /v1/admin/** is rejected with 403 Forbidden and ACCESS_DENIED")
    void testCustomerAccessingV1AdminEndpoint_forbidden() throws ServletException, IOException {
        authenticate("cust-001", "ROLE_CUSTOMER");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/audit-logs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("ACCESS_DENIED");
        assertThat(response.getContentAsString()).contains("Customer attempted to access admin endpoint");
    }

    @Test
    @DisplayName("DIG-71: Public endpoint /v1/auth/otp/request bypasses RBAC checks")
    void testV1AuthEndpointBypassesRbac() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/otp/request");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("DIG-71: Admin accessing /api/admin/** is permitted")
    void testAdminAccessingAdminEndpoint_permitted() throws ServletException, IOException {
        authenticate("admin-001", "ROLE_ADMIN");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/customers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("DIG-71: Unauthenticated request to /api/admin/** is rejected with 401 Unauthorized")
    void testUnauthenticatedAccessingAdminEndpoint_unauthorized() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/customers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("DIG-71: Customer accessing /internal/** is rejected with 403 Forbidden and ACCESS_DENIED")
    void testCustomerAccessingInternalEndpoint_forbidden() throws ServletException, IOException {
        authenticate("cust-001", "ROLE_CUSTOMER");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/core/settlement");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCESS_DENIED");
    }

    @Test
    @DisplayName("DIG-71: Internal service role accessing /internal/** is permitted")
    void testInternalRoleAccessingInternalEndpoint_permitted() throws ServletException, IOException {
        authenticate("service-internal", "ROLE_INTERNAL");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/core/settlement");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("DIG-71: Customer accessing regular /api/customers/** endpoint is permitted")
    void testCustomerAccessingCustomerEndpoint_permitted() throws ServletException, IOException {
        authenticate("cust-001", "ROLE_CUSTOMER");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers/cust-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
    }
}
