package com.capstone.orchestrator.config;

import com.capstone.common.security.JwtAuthenticationFilter;
import com.capstone.common.security.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security Configuration for the Tier 3 Channel Orchestrator.
 *
 * [W-H-Y MODEL]:
 * - WHAT: Secures incoming customer channel endpoints using stateless JWT authentication.
 * - HOW: Registers JwtAuthenticationFilter before UsernamePasswordAuthenticationFilter.
 *        Validates JWT signed by Login Service and populates SecurityContext with customerId.
 * - WHY: Ensures every customer request carried through the BFF is cryptographically verified
 *        and provides customer context for ownership checks.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        // ponytail: Redis blacklist check is enforced at Edge Gateway (Tier 2); null template keeps BFF stateless
        return new JwtAuthenticationFilter(tokenProvider, null);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/v1/payments/**", "/api/v1/accounts/**", "/api/v1/dashboard/**")
                                .hasAnyRole("CUSTOMER", "ADMIN", "INTERNAL")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

