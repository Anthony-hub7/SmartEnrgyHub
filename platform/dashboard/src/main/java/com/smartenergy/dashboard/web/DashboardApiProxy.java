package com.smartenergy.dashboard.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Same-origin read-only proxy for the dashboard's service APIs. */
@RestController
public class DashboardApiProxy {
    private static final String PREFIX = "/dashboard-api/";
    private final RestClient client;
    private final Map<String, String> serviceUrls;

    public DashboardApiProxy(
            RestClient.Builder builder,
            @Value("${smartenergy.dashboard.collector-url}") String collector,
            @Value("${smartenergy.dashboard.storage-url}") String storage,
            @Value("${smartenergy.dashboard.analytics-url}") String analytics,
            @Value("${smartenergy.dashboard.sensor-url}") String sensor) {
        this.client = builder.build();
        this.serviceUrls = Map.of("collector", collector, "storage", storage,
                "analytics", analytics, "sensor", sensor);
    }

    @GetMapping("/dashboard-api/{service}/**")
    public ResponseEntity<byte[]> get(@PathVariable String service, HttpServletRequest request) {
        String baseUrl = serviceUrls.get(service);
        if (baseUrl == null) {
            return ResponseEntity.notFound().build();
        }
        String requestPath = request.getRequestURI();
        String suffix = requestPath.substring((PREFIX + service).length());
        String query = request.getQueryString();
        URI target = URI.create(baseUrl + suffix + (query == null ? "" : "?" + query));
        return client.get().uri(target).exchange((upstreamRequest, upstreamResponse) -> {
            HttpHeaders headers = new HttpHeaders();
            if (upstreamResponse.getHeaders().getContentType() != null) {
                headers.setContentType(upstreamResponse.getHeaders().getContentType());
            }
            byte[] body = upstreamResponse.getBody().readAllBytes();
            return ResponseEntity.status(upstreamResponse.getStatusCode())
                    .headers(headers).body(body);
        });
    }
}
