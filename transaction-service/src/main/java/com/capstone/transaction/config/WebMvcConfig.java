package com.capstone.transaction.config;

import com.capstone.transaction.interceptor.IdempotencyInterceptor;
import com.capstone.transaction.interceptor.TransactionIdInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers application-wide MVC interceptors.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TransactionIdInterceptor transactionIdInterceptor;
    private final IdempotencyInterceptor idempotencyInterceptor;

    @Override
    public void addInterceptors(
            InterceptorRegistry registry) {

        registry.addInterceptor(transactionIdInterceptor)
                .addPathPatterns("/api/v1/ledger/mutate");
        
        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns("/api/v1/ledger/mutate", "/api/v1/payments", "/api/v1/admin/transfers/**", "/api/v1/admin/transactions/**")
                .excludePathPatterns("/api/v1/payments/*/cancel");
    }
}
