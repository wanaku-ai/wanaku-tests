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
import ai.wanaku.test.model.HttpToolConfig;
import ai.wanaku.test.model.ResourceConfig;
import ai.wanaku.test.model.ResourceReference;
import ai.wanaku.test.model.ToolInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST API client for Router management operations.
 *
 * API Endpoints (as of Wanaku Router):
 * - POST /api/v1/tools/add - Register a tool
 * - GET /api/v1/tools/list - List all tools
 * - POST /api/v1/tools?name={name} - Get tool by name
 * - PUT /api/v1/tools/remove?tool={name} - Remove a tool
 * - POST /api/v1/resources/expose - Expose a resource
 * - GET /api/v1/resources/list - List all resources
 * - PUT /api/v1/resources/remove?resource={name} - Remove a resource
 * - POST /api/v1/resources/exposeWithPayload - Expose a resource with configuration
 */
public class RouterClient {

    private static final Logger LOG = LoggerFactory.getLogger(RouterClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public RouterClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Registers a new HTTP tool.
     *
     * @param config the tool configuration
     * @return information about the registered tool
     * @throws ToolExistsException if a tool with the same name already exists
     */
    public ToolInfo registerTool(HttpToolConfig config) {
        LOG.debug("Registering tool: {}", config.getName());

        Map<String, Object> body = new HashMap<>();
        body.put("name", config.getName());
        body.put("description", config.getDescription());
        body.put("type", "http");
        body.put("uri", config.getUri());
        body.put("inputSchema", config.getInputSchema());

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;
                return objectMapper.treeToValue(dataNode, ToolInfo.class);
            } else if (response.statusCode() == 409) {
                throw new ToolExistsException("Tool '" + config.getName() + "' already exists");
            } else {
                throw new RouterClientException(
                        "Failed to register tool: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to register tool", e);
        }
    }

    /**
     * Lists all registered tools.
     */
    public List<ToolInfo> listTools() {
        LOG.debug("Listing tools");

        try {
            HttpRequest request =
                    buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                // Parse WanakuResponse wrapper
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                if (dataNode.isArray()) {
                    return objectMapper.convertValue(dataNode, new TypeReference<List<ToolInfo>>() {});
                }
                return new ArrayList<>();
            } else {
                throw new RouterClientException("Failed to list tools: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to list tools", e);
        }
    }

    /**
     * Gets information about a specific tool.
     *
     * @throws ToolNotFoundException if the tool does not exist
     */
    public ToolInfo getToolInfo(String name) {
        LOG.debug("Getting tool info: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "/" + encodedName)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Get tool response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                // Parse WanakuResponse wrapper
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    throw new ToolNotFoundException("Tool '" + name + "' not found");
                }

                return objectMapper.treeToValue(dataNode, ToolInfo.class);
            } else if (response.statusCode() == 404) {
                throw new ToolNotFoundException("Tool '" + name + "' not found");
            } else {
                // Check if response contains error
                try {
                    JsonNode root = objectMapper.readTree(response.body());
                    if (root.has("error") && !root.get("error").isNull()) {
                        String errorMsg = root.get("error").has("message")
                                ? root.get("error").get("message").asText()
                                : root.get("error").asText();
                        if (errorMsg.contains("not found")) {
                            throw new ToolNotFoundException("Tool '" + name + "' not found");
                        }
                    }
                } catch (IOException ignored) {
                }
                throw new RouterClientException("Failed to get tool: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to get tool", e);
        }
    }

    /**
     * Removes a registered tool.
     *
     * @return true if removed, false if not found
     */
    public boolean removeTool(String name) {
        LOG.debug("Removing tool: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "/" + encodedName)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Remove response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Tool removed: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Tool not found: {}", name);
                return false;
            } else {
                throw new RouterClientException("Failed to remove tool: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to remove tool", e);
        }
    }

    /**
     * Removes all registered tools.
     */
    public void clearAllTools() {
        LOG.debug("Clearing all tools");

        List<ToolInfo> tools = listTools();
        for (ToolInfo tool : tools) {
            try {
                removeTool(tool.getName());
            } catch (Exception e) {
                LOG.warn("Failed to remove tool {}: {}", tool.getName(), e.getMessage());
            }
        }
        LOG.debug("Cleared {} tools", tools.size());
    }

    /**
     * Checks if a tool exists.
     */
    public boolean toolExists(String name) {
        try {
            getToolInfo(name);
            return true;
        } catch (ToolNotFoundException e) {
            return false;
        }
    }

    /**
     * Registers a tool with static headers/config via /addWithPayload endpoint.
     *
     * @param config the tool configuration
     * @param configurationData properties-format string (e.g., "header.X-Api-Key=secret")
     */
    public ToolInfo registerToolWithConfig(HttpToolConfig config, String configurationData) {
        LOG.debug("Registering tool with configurationData: {}", config.getName());

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", config.getName());
        payload.put("description", config.getDescription());
        payload.put("type", "http");
        payload.put("uri", config.getUri());
        payload.put(
                "inputSchema",
                config.getInputSchema() != null
                        ? config.getInputSchema()
                        : Map.of("type", "object", "properties", Map.of()));

        Map<String, Object> body = new HashMap<>();
        body.put("payload", payload);
        body.put("configurationData", configurationData);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_TOOLS_PATH + "/payloads")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;
                return objectMapper.treeToValue(dataNode, ToolInfo.class);
            } else if (response.statusCode() == 409) {
                throw new ToolExistsException("Tool '" + config.getName() + "' already exists");
            } else {
                throw new RouterClientException(
                        "Failed to register tool: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to register tool", e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Resource operations
    // ──────────────────────────────────────────────────────────────

    /**
     * Exposes a new resource via the Router.
     *
     * @param config the resource configuration
     * @throws ResourceExistsException if a resource with the same name already exists
     */
    public void exposeResource(ResourceConfig config) {
        LOG.debug("Exposing resource: {}", config.getName());

        try {
            String json = objectMapper.writeValueAsString(config.toMap());

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_RESOURCES_PATH)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                LOG.debug("Resource exposed: {}", config.getName());
            } else if (response.statusCode() == 409) {
                throw new ResourceExistsException("Resource '" + config.getName() + "' already exists");
            } else {
                throw new RouterClientException(
                        "Failed to expose resource: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to expose resource", e);
        }
    }

    /**
     * Lists all registered resources.
     */
    public List<ResourceReference> listResources() {
        LOG.debug("Listing resources");

        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_RESOURCES_PATH)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("List resources response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataNode = root.has("data") ? root.get("data") : root;

                if (dataNode == null || dataNode.isNull()) {
                    return new ArrayList<>();
                }

                if (dataNode.isArray()) {
                    return objectMapper.convertValue(dataNode, new TypeReference<List<ResourceReference>>() {});
                }
                return new ArrayList<>();
            } else {
                throw new RouterClientException("Failed to list resources: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to list resources", e);
        }
    }

    /**
     * Removes a registered resource.
     *
     * @return true if removed, false if not found
     */
    public boolean removeResource(String name) {
        LOG.debug("Removing resource: {}", name);

        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_RESOURCES_PATH + "/" + encodedName)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("Remove resource response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.debug("Resource removed: {}", name);
                return true;
            } else if (response.statusCode() == 404) {
                LOG.debug("Resource not found: {}", name);
                return false;
            } else {
                throw new RouterClientException("Failed to remove resource: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to remove resource", e);
        }
    }

    /**
     * Gets information about a specific resource by name.
     *
     * @throws ResourceNotFoundException if no resource with the given name is found
     */
    public ResourceReference getResourceInfo(String name) {
        LOG.debug("Getting resource info: {}", name);

        List<ResourceReference> resources = listResources();
        return resources.stream()
                .filter(r -> name.equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Resource '" + name + "' not found"));
    }

    /**
     * Removes all registered resources.
     */
    public void clearAllResources() {
        LOG.debug("Clearing all resources");

        List<ResourceReference> resources = listResources();
        for (ResourceReference resource : resources) {
            try {
                removeResource(resource.getName());
            } catch (Exception e) {
                LOG.warn("Failed to remove resource {}: {}", resource.getName(), e.getMessage());
            }
        }
        LOG.debug("Cleared {} resources", resources.size());
    }

    /**
     * Checks if a resource with the given name exists.
     */
    public boolean resourceExists(String name) {
        List<ResourceReference> resources = listResources();
        return resources.stream().anyMatch(r -> name.equals(r.getName()));
    }

    /**
     * Exposes a resource with configuration data via /exposeWithPayload endpoint.
     *
     * @param config the resource configuration
     * @param configurationData properties-format string (e.g., "some.property=value")
     */
    public void exposeResourceWithConfig(ResourceConfig config, String configurationData) {
        LOG.debug("Exposing resource with configurationData: {}", config.getName());

        Map<String, Object> body = new HashMap<>();
        body.put("payload", config.toMap());
        body.put("configurationData", configurationData);

        try {
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_RESOURCES_PATH + "/payloads")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                LOG.debug("Resource exposed with config: {}", config.getName());
            } else if (response.statusCode() == 409) {
                throw new ResourceExistsException("Resource '" + config.getName() + "' already exists");
            } else {
                throw new RouterClientException(
                        "Failed to expose resource: " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RouterClientException("Failed to expose resource", e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Capability discovery operations
    // ──────────────────────────────────────────────────────────────

    /**
     * Deregisters a capability from the Router by service name.
     * Looks up the capability's ServiceTarget (to get the ID), then calls the deregister endpoint.
     *
     * @param serviceName the service name to deregister
     * @param accessToken Bearer token for the management API (required, management endpoints are authenticated)
     * @return true if deregistered, false if capability was not found
     */
    public boolean deregisterCapability(String serviceName, String accessToken) {
        LOG.debug("Deregistering capability: {}", serviceName);

        try {
            // Find the ServiceTarget by service name
            HttpRequest listRequest = buildRequest(WanakuTestConstants.ROUTER_CAPABILITIES_PATH)
                    .GET()
                    .build();
            HttpResponse<String> listResponse = httpClient.send(listRequest, HttpResponse.BodyHandlers.ofString());

            if (listResponse.statusCode() != 200) {
                LOG.warn("Failed to list capabilities for deregistration: {}", listResponse.statusCode());
                return false;
            }

            JsonNode data = objectMapper.readTree(listResponse.body()).path("data");
            JsonNode targetNode = null;
            for (JsonNode capability : data) {
                if (serviceName.equals(capability.path("serviceName").asText())) {
                    targetNode = capability;
                    break;
                }
            }

            if (targetNode == null) {
                LOG.debug("Capability '{}' not found, nothing to deregister", serviceName);
                return false;
            }

            // Call deregister endpoint with the full ServiceTarget
            String serviceTargetJson = objectMapper.writeValueAsString(targetNode);

            HttpRequest.Builder deregBuilder = buildRequest(WanakuTestConstants.ROUTER_MANAGEMENT_DISCOVERY_PATH)
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(serviceTargetJson))
                    .header("Content-Type", "application/json");

            if (accessToken != null) {
                deregBuilder.header("Authorization", "Bearer " + accessToken);
            }

            HttpResponse<String> deregResponse =
                    httpClient.send(deregBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (deregResponse.statusCode() == 200) {
                LOG.debug("Capability '{}' deregistered successfully", serviceName);
                return true;
            } else {
                LOG.warn(
                        "Failed to deregister capability '{}': {} - {}",
                        serviceName,
                        deregResponse.statusCode(),
                        deregResponse.body());
                return false;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Failed to deregister capability '{}': {}", serviceName, e.getMessage());
            return false;
        }
    }

    private HttpRequest.Builder buildRequest(String path) {
        return HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Checks if a specific capability service is registered with the Router.
     *
     * @param serviceName the service name (e.g., "http" for HTTP tool service)
     * @return true if the service is found in the registered capabilities list
     */
    public boolean isCapabilityRegistered(String serviceName) {
        /*
         * GET /api/v1/capabilities returns:
         * {
         *   "data": [
         *     { "serviceName": "http", "serviceType": "TOOLS", "host": "...", "port": 9000 },
         *     { "serviceName": "exec", "serviceType": "TOOLS", "host": "...", "port": 9001 }
         *   ]
         * }
         */
        try {
            HttpRequest request = buildRequest(WanakuTestConstants.ROUTER_CAPABILITIES_PATH)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return false;

            JsonNode data = objectMapper.readTree(response.body()).path("data");
            for (JsonNode capability : data) {
                if (serviceName.equals(capability.path("serviceName").asText())) {
                    return true;
                }
            }
        } catch (IOException | InterruptedException e) {
            LOG.debug("Failed to check capability: {}", e.getMessage());
        }
        return false;
    }

    // Exception classes
    public static class RouterClientException extends RuntimeException {
        public RouterClientException(String message) {
            super(message);
        }

        public RouterClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ToolExistsException extends RouterClientException {
        public ToolExistsException(String message) {
            super(message);
        }
    }

    public static class ToolNotFoundException extends RouterClientException {
        public ToolNotFoundException(String message) {
            super(message);
        }
    }

    public static class ResourceExistsException extends RouterClientException {
        public ResourceExistsException(String message) {
            super(message);
        }
    }

    public static class ResourceNotFoundException extends RouterClientException {
        public ResourceNotFoundException(String message) {
            super(message);
        }
    }
}
