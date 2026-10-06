package com.capstone.ledger.frontend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;

/**
 * Reverse Proxy for Grafana Observability Dashboards (DIG-30, CH-03, T-014).
 * Proxies /grafana/** and /admin/grafana/** requests to internal Grafana container (:3000).
 * Prevents host port exposure of :3000 outside the banking bridge network.
 * Only accessible to authenticated Administrator sessions (enforced by SessionAuthInterceptor).
 */
@Controller
public class GrafanaProxyController {

    private static final Logger log = LoggerFactory.getLogger(GrafanaProxyController.class);

    @Value("${banking.grafana.internal-url:http://localhost:3000}")
    private String grafanaInternalUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @RequestMapping(value = {"/grafana/**", "/admin/grafana/**"})
    public void proxyGrafana(HttpServletRequest request, HttpServletResponse response) {
        try {
            String uri = request.getRequestURI();
            String subPath = uri.startsWith("/admin/grafana")
                    ? uri.substring("/admin/grafana".length())
                    : uri.substring("/grafana".length());

            if (subPath.isEmpty()) {
                subPath = "/";
            }

            String queryString = request.getQueryString();
            String targetUrl = grafanaInternalUrl + "/grafana" + subPath + (queryString != null ? "?" + queryString : "");

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(15));

            // Copy request headers except host and content-length
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames != null && headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (headerName.equalsIgnoreCase("host") || headerName.equalsIgnoreCase("content-length")) {
                    continue;
                }
                Enumeration<String> headerValues = request.getHeaders(headerName);
                while (headerValues.hasMoreElements()) {
                    reqBuilder.header(headerName, headerValues.nextElement());
                }
            }

            // Copy method and payload
            String method = request.getMethod();
            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(request.getInputStream().readAllBytes()));
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<InputStream> upstreamRes = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            response.setStatus(upstreamRes.statusCode());
            upstreamRes.headers().map().forEach((key, values) -> {
                if (!key.equalsIgnoreCase("transfer-encoding") && !key.equalsIgnoreCase("content-length")) {
                    values.forEach(v -> response.addHeader(key, v));
                }
            });

            try (InputStream in = upstreamRes.body(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        } catch (Exception e) {
            log.debug("Grafana upstream proxy unavailable: {}", e.getMessage());
            try {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Grafana upstream container unavailable on internal port :3000");
            } catch (Exception ignored) {}
        }
    }
}
