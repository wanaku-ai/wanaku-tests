package ai.wanaku.test.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ServiceCatalogClient {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceCatalogClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;

    public ServiceCatalogClient(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public void deploy(String name, String packageData) {
        LOG.debug("Deploying service catalog: {}", name);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("data", packageData);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_SERVICE_CATALOG_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                LOG.debug("Service catalog deployed: {}", name);
            } else if (response.statusCode() == 409) {
                throw new ServiceCatalogExistsException("Service catalog '" + name + "' already exists");
            } else {
                throw new ServiceCatalogClientException(
                        "Failed to deploy service catalog: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceCatalogClientException("Failed to deploy service catalog", e);
        }
    }

    public List<JsonNode> list() {
        LOG.debug("Listing service catalogs");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_SERVICE_CATALOG_PATH)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List service catalogs response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                List<JsonNode> catalogs = new ArrayList<>();
                if (dataNode.isArray()) {
                    for (JsonNode catalog : dataNode) {
                        catalogs.add(catalog);
                    }
                }
                return catalogs;
            } else {
                throw new ServiceCatalogClientException("Failed to list service catalogs: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceCatalogClientException("Failed to list service catalogs", e);
        }
    }

    public boolean remove(String name) {
        LOG.debug("Removing service catalog: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_SERVICE_CATALOG_PATH + "/" + encodedName)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Remove service catalog response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Service catalog removed: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Service catalog not found: {}", name);
                return false;
            } else {
                throw new ServiceCatalogClientException("Failed to remove service catalog: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceCatalogClientException("Failed to remove service catalog", e);
        }
    }

    public void clearAll() {
        LOG.debug("Clearing all service catalogs");

        List<JsonNode> catalogs = list();
        for (JsonNode catalog : catalogs) {
            String name = catalog.has("name") ? catalog.get("name").asText() : null;
            if (name != null) {
                try {
                    remove(name);
                } catch (Exception e) {
                    LOG.warn("Failed to remove service catalog {}: {}", name, e.getMessage());
                }
            }
        }
        LOG.debug("Cleared {} service catalogs", catalogs.size());
    }

    public boolean exists(String name) {
        return list().stream()
                .anyMatch(c -> c.has("name") && c.get("name").asText().equals(name));
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

    public static class ServiceCatalogClientException extends RuntimeException {
        public ServiceCatalogClientException(String message) {
            super(message);
        }

        public ServiceCatalogClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ServiceCatalogExistsException extends ServiceCatalogClientException {
        public ServiceCatalogExistsException(String message) {
            super(message);
        }
    }
}
