package ai.wanaku.test.router;

import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class NamespaceCrudITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
    }

    @DisplayName("Create a namespace and verify it exists")
    @Test
    void shouldCreateNamespace() {
        String id = namespaceClient.create("test-ns", "/test-ns");

        assertThat(id).isNotNull();
        assertThat(namespaceClient.exists("test-ns")).isTrue();
    }

    @DisplayName("List namespaces and verify result is non-null")
    @Test
    void shouldListNamespaces() {
        List<JsonNode> namespaces = namespaceClient.list();

        assertThat(namespaces).isNotNull();
    }

    @DisplayName("Create a namespace, show it by ID, and verify returned node has the name")
    @Test
    void shouldShowNamespace() {
        String id = namespaceClient.create("show-ns", "/show-ns");
        assumeThat(id).as("Namespace ID must be returned").isNotNull();

        JsonNode node = namespaceClient.show(id);

        assertThat(node).isNotNull();
        assertThat(node.has("name")).isTrue();
        assertThat(node.get("name").asText()).isEqualTo("show-ns");
    }

    @DisplayName("Create a namespace, delete it by ID, and verify it no longer exists")
    @Test
    void shouldDeleteNamespace() {
        String id = namespaceClient.create("delete-ns", "/delete-ns");
        assumeThat(id).as("Namespace ID must be returned").isNotNull();
        assertThat(namespaceClient.exists("delete-ns")).isTrue();

        boolean deleted = namespaceClient.delete(id);

        assertThat(deleted).isTrue();
        assertThat(namespaceClient.exists("delete-ns")).isFalse();
    }

    @DisplayName("Return false when deleting a namespace that does not exist")
    @Test
    void shouldReturnFalseWhenDeletingNonexistentNamespace() {
        boolean deleted = namespaceClient.delete("nonexistent-id-12345");

        assertThat(deleted).isFalse();
    }
}
