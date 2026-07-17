package ai.wanaku.test.router;

import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import ai.wanaku.test.client.PromptsClient;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class PromptsCrudITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
    }

    @DisplayName("Add a prompt and verify it exists")
    @Test
    void shouldAddPrompt() {
        // Given
        String name = "greeting-prompt";
        String description = "A greeting prompt";

        // When
        promptsClient.add(name, description);

        // Then
        assertThat(promptsClient.exists(name)).isTrue();
    }

    @DisplayName("Add 3 prompts and verify all are present in the list")
    @Test
    void shouldListPrompts() {
        // Given
        promptsClient.add("prompt-alpha", "First prompt");
        promptsClient.add("prompt-beta", "Second prompt");
        promptsClient.add("prompt-gamma", "Third prompt");

        // When
        List<JsonNode> prompts = promptsClient.list();

        // Then
        assertThat(prompts).hasSize(3);
        assertThat(prompts)
                .extracting(p -> p.get("name").asText())
                .containsExactlyInAnyOrder("prompt-alpha", "prompt-beta", "prompt-gamma");
    }

    @DisplayName("Add a prompt, remove it, and verify it is no longer in the list")
    @Test
    void shouldRemovePrompt() {
        // Given
        String name = "remove-me-prompt";
        promptsClient.add(name, "To be removed");
        assertThat(promptsClient.exists(name)).isTrue();

        // When
        boolean removed = promptsClient.remove(name);

        // Then
        assertThat(removed).isTrue();
        assertThat(promptsClient.exists(name)).isFalse();
    }

    @DisplayName("Return false when removing a nonexistent prompt")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentPrompt() {
        // When
        boolean removed = promptsClient.remove("nonexistent");

        // Then
        assertThat(removed).isFalse();
    }

    @DisplayName("Add a prompt, edit its description, and verify the update")
    @Test
    void shouldEditPrompt() {
        // Given
        String name = "editable-prompt";
        promptsClient.add(name, "Original description");
        assertThat(promptsClient.exists(name)).isTrue();

        // When
        promptsClient.edit(name, Map.of("description", "Updated description"));

        // Then
        List<JsonNode> prompts = promptsClient.list();
        JsonNode edited = prompts.stream()
                .filter(p -> p.get("name").asText().equals(name))
                .findFirst()
                .orElse(null);

        assertThat(edited).isNotNull();
        assertThat(edited.get("description").asText()).isEqualTo("Updated description");
    }

    @DisplayName("Handle adding a prompt with a duplicate name")
    @Test
    void shouldHandleDuplicatePrompt() {
        // Given
        String name = "duplicate-prompt";
        promptsClient.add(name, "First registration");

        try {
            promptsClient.add(name, "Second registration");
            assertThat(promptsClient.exists(name)).isTrue();
        } catch (PromptsClient.PromptExistsException e) {
            assertThat(e.getMessage()).contains(name);
        } catch (PromptsClient.PromptsClientException e) {
            assertThat(e.getMessage()).containsAnyOf("409", "500", "already exists", "Generic error");
        }
    }
}
