package ai.wanaku.test.router;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.DataStoreClient;
import ai.wanaku.test.client.ForwardsClient;
import ai.wanaku.test.client.ManagementClient;
import ai.wanaku.test.client.NamespaceClient;
import ai.wanaku.test.client.PromptsClient;
import ai.wanaku.test.client.ServiceCatalogClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public abstract class RouterTestBase extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(RouterTestBase.class);

    protected DataStoreClient dataStoreClient;
    protected NamespaceClient namespaceClient;
    protected PromptsClient promptsClient;
    protected ForwardsClient forwardsClient;
    protected ManagementClient managementClient;
    protected ServiceCatalogClient serviceCatalogClient;

    @BeforeEach
    void setupRouterClients(TestInfo testInfo) {
        if (routerManager != null && routerManager.isRunning()) {
            String accessToken = null;
            if (keycloakManager != null && keycloakManager.isRunning()) {
                accessToken = keycloakManager.getMcpToken();
            }

            String baseUrl = routerManager.getBaseUrl();
            dataStoreClient = new DataStoreClient(baseUrl, accessToken);
            namespaceClient = new NamespaceClient(baseUrl, accessToken);
            promptsClient = new PromptsClient(baseUrl, accessToken);
            forwardsClient = new ForwardsClient(baseUrl, accessToken);
            managementClient = new ManagementClient(baseUrl, accessToken);
            serviceCatalogClient = new ServiceCatalogClient(baseUrl, accessToken);
        }
    }

    @AfterEach
    void cleanupRouterState() {
        if (promptsClient != null) {
            try {
                promptsClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear prompts: {}", e.getMessage());
            }
        }
        if (forwardsClient != null) {
            try {
                forwardsClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear forwards: {}", e.getMessage());
            }
        }
        if (dataStoreClient != null) {
            try {
                dataStoreClient.clearAll();
            } catch (Exception e) {
                LOG.warn("Failed to clear data store: {}", e.getMessage());
            }
        }
    }

    protected String getOrCreateNamespaceId(String name) {
        if (namespaceClient == null) {
            return null;
        }
        try {
            List<JsonNode> namespaces = namespaceClient.list();
            for (JsonNode ns : namespaces) {
                if (ns.has("name") && name.equals(ns.get("name").asText())) {
                    return ns.has("id") ? ns.get("id").asText() : null;
                }
            }
            return namespaceClient.create(name, "/" + name);
        } catch (Exception e) {
            LOG.warn("Failed to get/create namespace '{}': {}", name, e.getMessage());
            return null;
        }
    }

    @Override
    protected String getLogProfile() {
        return "router";
    }
}
