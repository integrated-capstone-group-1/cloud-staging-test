package com.capstone.transaction.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
    public static final String IDEMPOTENCY_KEY_HEADER_ALT = "Idempotency-Key";

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (!StringUtils.hasText(idempotencyKey)) {
            idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER_ALT);
        }

        if (!StringUtils.hasText(idempotencyKey)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                {"type":"https://capstone.bank/errors/validation","title":"Bad Request","status":400,"detail":"Missing required Idempotency-Key header"}
                """);
            return false;
        }

        request.setAttribute("idempotencyKey", idempotencyKey);
        return true;
    }
}
