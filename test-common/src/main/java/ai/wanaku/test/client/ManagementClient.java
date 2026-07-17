package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ManagementClient {

    private static final Logger LOG = LoggerFactory.getLogger(ManagementClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    public ManagementClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public JsonNode getInfo() {
        LOG.debug("Getting router info");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_MANAGEMENT_INFO_PATH)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Info response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                return root.has("data") ? root.get("data") : root;
            } else {
                throw new ManagementClientException("Failed to get info: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ManagementClientException("Failed to get info", e);
        }
    }

    public JsonNode getStatistics() {
        LOG.debug("Getting router statistics");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_MANAGEMENT_STATISTICS_PATH)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Statistics response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                return root.has("data") ? root.get("data") : root;
            } else {
                throw new ManagementClientException("Failed to get statistics: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ManagementClientException("Failed to get statistics", e);
        }
    }

    public boolean isAvailable() {
        try {
            getInfo();
            return true;
        } catch (ManagementClientException e) {
            return false;
        }
    }

    private HttpRequest.Builder buildRequest(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        if (accessToken != null && !accessToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public static class ManagementClientException extends RuntimeException {
        public ManagementClientException(String message) {
            super(message);
        }

        public ManagementClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
