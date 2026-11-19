package ai.wanaku.fixtures.capture;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CaptureStateTest {
    private static final String PRIMARY = "synthetic-primary-fixture-token";
    private static final String SECONDARY = "synthetic-secondary-fixture-token";

    @Test
    void defaultModeDoesNotStoreOrEchoAuthorization() {
        CaptureState state = new CaptureState(false, PRIMARY, SECONDARY);
        assertThat(state.recordToolAuthorization("Bearer " + PRIMARY, true)).isEmpty();
        assertThat(state.recordPromptAuthorization("Bearer arbitrary-fixture-token", true)).isEmpty();
        assertThat(state.getToolAuthorization()).isEqualTo("disabled");
        assertThat(state.getPromptAuthorization()).isEqualTo("disabled");
        CaptureResource resource = new CaptureResource();
        resource.state = state;
        assertThat(resource.authorization()).isEqualTo(Map.of("tool", "disabled", "prompt", "disabled"));
    }

    @Test
    void syntheticModeReturnsOnlyConfiguredMatchingTokenAndNonSecretObservation() {
        CaptureState state = new CaptureState(true, PRIMARY, SECONDARY);
        assertThat(state.recordToolAuthorization("Bearer " + PRIMARY, true))
                .isEqualTo(" authorization=Bearer " + PRIMARY);
        assertThat(state.getToolAuthorization()).isEqualTo("primary");
        assertThat(state.recordPromptAuthorization("Bearer " + SECONDARY, true))
                .isEqualTo(" authorization=Bearer " + SECONDARY);
        assertThat(state.getPromptAuthorization()).isEqualTo("secondary");
    }

    @Test
    void unknownAuthorizationIsNeverRetainedOrEchoedEvenInSyntheticMode() {
        CaptureState state = new CaptureState(true, PRIMARY, SECONDARY);
        state.recordToolAuthorization("Bearer " + PRIMARY, true);
        state.recordPromptAuthorization("Bearer " + SECONDARY, true);
        assertThat(state.recordToolAuthorization("Bearer arbitrary-fixture-token", true)).isEmpty();
        assertThat(state.recordPromptAuthorization("Bearer synthetic-unconfigured-token", true)).isEmpty();
        assertThat(state.getToolAuthorization()).isEqualTo("unmatched");
        assertThat(state.getPromptAuthorization()).isEqualTo("unmatched");
    }

    @Test
    void remoteRequestsCannotUseSyntheticObservationOrEcho() {
        CaptureState state = new CaptureState(true, PRIMARY, SECONDARY);
        assertThat(state.recordToolAuthorization("Bearer " + PRIMARY, false)).isEmpty();
        assertThat(state.recordPromptAuthorization("Bearer " + SECONDARY, false)).isEmpty();
        assertThat(state.getToolAuthorization()).isEqualTo("remote");
        assertThat(state.getPromptAuthorization()).isEqualTo("remote");
    }

    @Test
    void configuredTokensWithoutSyntheticPrefixAreNotEligibleForEcho() {
        CaptureState state = new CaptureState(true, "not-a-synthetic-fixture-token", null);
        assertThat(state.recordToolAuthorization("Bearer not-a-synthetic-fixture-token", true)).isEmpty();
        assertThat(state.getToolAuthorization()).isEqualTo("unmatched");
    }

    @Test
    void missingAuthorizationRemainsNonSecretAndUnauthenticatedCallsStillCount() {
        CaptureState state = new CaptureState(true, PRIMARY, SECONDARY);
        assertThat(state.recordToolAuthorization(null, true)).isEmpty();
        assertThat(state.recordPromptAuthorization(null, true)).isEmpty();
        assertThat(state.getToolAuthorization()).isEqualTo("absent");
        assertThat(state.getPromptAuthorization()).isEqualTo("absent");
        assertThat(state.incrementToolCalls()).isEqualTo(1);
        assertThat(state.incrementPromptGets()).isEqualTo(1);
    }
}
