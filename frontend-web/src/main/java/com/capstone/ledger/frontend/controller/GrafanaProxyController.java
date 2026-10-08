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
            // Fixed: Don't append "/grafana" again - subPath already contains the path
            String targetUrl = grafanaInternalUrl + subPath + (queryString != null ? "?" + queryString : "");

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
                String uri = request.getRequestURI();
                response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                if (uri.contains("/api/")) {
                    response.setContentType("application/json");
                    response.getWriter().write("{\"status\":\"offline\",\"message\":\"Grafana container is currently offline on internal network\"}");
                } else {
                    response.setContentType("text/html;charset=UTF-8");
                    response.getWriter().write("""
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta charset="utf-8"/>
                            <title>Grafana Stack Offline</title>
                            <style>
                                body { font-family: system-ui, -apple-system, sans-serif; background: #0f1117; color: #d8d9da; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                                .card { background: #181b1f; border: 1px solid #2c3235; border-radius: 8px; padding: 2rem; max-width: 540px; text-align: center; box-shadow: 0 4px 20px rgba(0,0,0,0.4); }
                                h3 { color: #f2c94c; margin-top: 0; }
                                code { background: #111217; padding: 6px 12px; border-radius: 4px; color: #73bf69; font-size: 0.9rem; display: inline-block; margin: 12px 0; }
                                p { font-size: 0.95rem; line-height: 1.5; color: #a0a5ad; }
                                .btn { background: #3274d9; color: #fff; padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; text-decoration: none; display: inline-block; margin-top: 10px; }
                                .btn:hover { background: #2457a6; }
                            </style>
                        </head>
                        <body>
                            <div class="card">
                                <h3>Observability Stack Offline</h3>
                                <p>The internal Grafana & Prometheus service is not currently running on the private bridge network.</p>
                                <p>To start the telemetry containers in your terminal, run:</p>
                                <code>docker-compose up -d grafana prometheus loki</code>
                                <p><small>Once started, click below to refresh the live stream.</small></p>
                                <div style="display: flex; gap: 8px; justify-content: center; margin-top: 12px;">
                                    <a href="/admin/monitoring" class="btn" style="background: #4a5568;">Back to Admin Portal</a>
                                    <a href="javascript:location.reload()" class="btn">Reload Live Stream</a>
                                </div>
                            </div>
                        </body>
                        </html>
                    """);
                }
            } catch (Exception ignored) {}
        }
    }
}

