package ai.wanaku.fixtures.capture;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptureStateTest {
    private static final String PRIMARY = "synthetic-primary-fixture-token";
    private static final String SECONDARY = "synthetic-secondary-fixture-token";

    @Test
    void defaultModeDoesNotStoreOrEchoAuthorization() {
        CaptureState state = new CaptureState(false, PRIMARY, SECONDARY);
        assertEquals("", state.recordToolAuthorization("Bearer " + PRIMARY, true));
        assertEquals("", state.recordPromptAuthorization("Bearer arbitrary-fixture-token", true));
        assertEquals("disabled", state.getToolAuthorization());
        assertEquals("disabled", state.getPromptAuthorization());
        CaptureResource resource = new CaptureResource();
        resource.state = state;
        assertEquals(Map.of("tool", "disabled", "prompt", "disabled"), resource.authorization());
    }

    @Test
    void syntheticModeReturnsOnlyConfiguredMatchingTokenAndNonSecretObservation() {
        CaptureState state = new CaptureState(true, PRIMARY, SECONDARY);
        assertEquals(" authorization=Bearer " + PRIMARY, state.recordToolAuthorization("Bearer " + PRIMARY, true));
        assertEquals("primary", state.getToolAuthorization());
        assertEquals(" authorization=Bearer " + SECONDARY, state.recordPromptAuthorization("Bearer " + SECONDARY, true));
        assertEquals("secondary", state.getPromptAuthorization());
    }

    @Test
    void unknownAuthorizationIsNeverRetainedOrEchoedEvenInSyntheticMode() {
        CaptureState state = new CaptureState(true, PRIMARY, SECONDARY);
        state.recordToolAuthorization("Bearer " + PRIMARY, true);
        state.recordPromptAuthorization("Bearer " + SECONDARY, true);
        assertEquals("", state.recordToolAuthorization("Bearer arbitrary-fixture-token", true));
        assertEquals("", state.recordPromptAuthorization("Bearer synthetic-unconfigured-token", true));
        assertEquals("unmatched", state.getToolAuthorization());
        assertEquals("unmatched", state.getPromptAuthorization());
    }

    @Test
    void remoteRequestsCannotUseSyntheticObservationOrEcho() {
        CaptureState state = new CaptureState(true, PRIMARY, SECONDARY);
        assertEquals("", state.recordToolAuthorization("Bearer " + PRIMARY, false));
        assertEquals("", state.recordPromptAuthorization("Bearer " + SECONDARY, false));
        assertEquals("remote", state.getToolAuthorization());
        assertEquals("remote", state.getPromptAuthorization());
    }

    @Test
    void configuredTokensWithoutSyntheticPrefixAreNotEligibleForEcho() {
        CaptureState state = new CaptureState(true, "not-a-synthetic-fixture-token", null);
        assertEquals("", state.recordToolAuthorization("Bearer not-a-synthetic-fixture-token", true));
        assertEquals("unmatched", state.getToolAuthorization());
    }

    @Test
    void missingAuthorizationRemainsNonSecretAndUnauthenticatedCallsStillCount() {
        CaptureState state = new CaptureState(true, PRIMARY, SECONDARY);
        assertEquals("", state.recordToolAuthorization(null, true));
        assertEquals("", state.recordPromptAuthorization(null, true));
        assertEquals("absent", state.getToolAuthorization());
        assertEquals("absent", state.getPromptAuthorization());
        assertEquals(1, state.incrementToolCalls());
        assertEquals(1, state.incrementPromptGets());
    }
}
