package ai.wanaku.test.resources;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.model.ResourceConfig;
import ai.wanaku.test.model.ResourceReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for resource registration, listing, and removal via REST API.
 * Infrastructure availability is assumed — tests skip if not present.
 */
@QuarkusTest
class RestApiResourceITCase extends ResourceTestBase {

    @BeforeEach
    void assumeInfrastructureAvailable() {
        Assumptions.assumeTrue(isRouterAvailable(), "Router must be available");
        Assumptions.assumeTrue(isFileProviderAvailable(), "File provider must be available");
    }

    @DisplayName("Expose a file resource and verify it appears in the resource list")
    @Test
    void shouldExposeAndListFileResource() throws Exception {
        // Given
        Path testFile = createTestFile("test-expose.txt", "Hello Wanaku Resources");
        ResourceConfig config = ResourceConfig.builder()
                .name("test-expose-resource")
                .location(testFile.toUri().toString())
                .type("file")
                .mimeType("text/plain")
                .description("Test resource for expose verification")
                .build();

        // When
        routerClient.exposeResource(config);

        // Then
        List<ResourceReference> resources = routerClient.listResources();
        assertThat(resources).isNotEmpty();

        ResourceReference found = resources.stream()
                .filter(r -> "test-expose-resource".equals(r.getName()))
                .findFirst()
                .orElse(null);

        assertThat(found).as("Resource should be in the list").isNotNull();
        assertThat(found.getType()).isEqualTo("file");
        assertThat(found.getMimeType()).isEqualTo("text/plain");
    }

    @DisplayName("Remove an exposed resource and verify it no longer appears in the list")
    @Test
    void shouldRemoveResource() throws Exception {
        // Given
        Path testFile = createTestFile("test-remove.txt", "To be removed");
        ResourceConfig config = ResourceConfig.builder()
                .name("test-remove-resource")
                .location(testFile.toUri().toString())
                .build();

        routerClient.exposeResource(config);
        assertThat(routerClient.resourceExists("test-remove-resource")).isTrue();

        // When
        boolean removed = routerClient.removeResource("test-remove-resource");

        // Then
        assertThat(removed).isTrue();
        assertThat(routerClient.resourceExists("test-remove-resource")).isFalse();
    }

    @DisplayName("Reject exposing a resource with a duplicate name")
    @Test
    void shouldRejectDuplicateResourceExpose() throws Exception {
        // Given
        Path testFile = createTestFile("test-duplicate.txt", "First registration");
        ResourceConfig config = ResourceConfig.builder()
                .name("duplicate-resource")
                .location(testFile.toUri().toString())
                .build();

        routerClient.exposeResource(config);

        // When/Then
        ResourceConfig duplicateConfig = ResourceConfig.builder()
                .name("duplicate-resource")
                .location(testFile.toUri().toString())
                .description("Second registration attempt")
                .build();

        assertThatThrownBy(() -> routerClient.exposeResource(duplicateConfig))
                .isInstanceOf(RouterClient.ResourceExistsException.class)
                .hasMessageContaining("duplicate-resource");
    }

    @DisplayName("Return false when trying to remove a resource that doesn't exist")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentResource() {
        // When
        boolean removed = routerClient.removeResource("nonexistent-resource");

        // Then
        assertThat(removed).isFalse();
    }

    @DisplayName("Expose 3 resources and verify all are returned in the list")
    @Test
    void shouldListMultipleResources() throws Exception {
        // Given
        Path file1 = createTestFile("multi-1.txt", "First");
        Path file2 = createTestFile("multi-2.json", "{\"id\": 2}");
        Path file3 = createTestFile("multi-3.csv", "a,b,c");

        routerClient.exposeResource(ResourceConfig.builder()
                .name("resource-alpha")
                .location(file1.toUri().toString())
                .mimeType("text/plain")
                .build());

        routerClient.exposeResource(ResourceConfig.builder()
                .name("resource-beta")
                .location(file2.toUri().toString())
                .mimeType("application/json")
                .build());

        routerClient.exposeResource(ResourceConfig.builder()
                .name("resource-gamma")
                .location(file3.toUri().toString())
                .mimeType("text/csv")
                .build());

        // When
        List<ResourceReference> resources = routerClient.listResources();

        // Then
        assertThat(resources).hasSize(3);
        assertThat(resources)
                .extracting(ResourceReference::getName)
                .containsExactlyInAnyOrder("resource-alpha", "resource-beta", "resource-gamma");
    }

    @DisplayName("Expose a resource with configuration data and verify configurationURI is set")
    @Test
    void shouldExposeResourceWithConfiguration() throws Exception {
        // Given
        Path testFile = createTestFile("test-config.txt", "Configured resource");
        ResourceConfig config = ResourceConfig.builder()
                .name("configured-resource")
                .location(testFile.toUri().toString())
                .mimeType("text/plain")
                .build();

        // When
        routerClient.exposeResourceWithConfig(config, "some.property=value");

        // Then
        ResourceReference resource = routerClient.getResourceInfo("configured-resource");
        assertThat(resource).isNotNull();
        assertThat(resource.getName()).isEqualTo("configured-resource");
        assertThat(resource.getConfigurationURI()).isNotNull().isNotEmpty();

        // Verify configuration content was persisted correctly.
        // The Router API has no endpoint to read configuration data back, so we read
        // the file at the configurationURI returned by the API. This URI is part of the
        // API contract (returned in ResourceReference), not an internal implementation detail.
        Path configFile = Path.of(URI.create(resource.getConfigurationURI()));
        assertThat(configFile).exists();
        String configContent = Files.readString(configFile);
        assertThat(configContent).contains("some.property=value");
    }
}
