package ai.wanaku.fixtures.capture;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CaptureStateITCase {

    @Test
    void snapshotsAreIndependentOfCallerMutations() {
        CaptureState state = new CaptureState();
        assertThat(state.getLastToolCall().isObject()).isTrue();
        assertThat(state.getLastToolCall().isEmpty()).isTrue();
        var arguments = JsonNodeFactory.instance.objectNode();
        arguments.putObject("value").put("flag", false);
        state.recordToolCall("capture_structured_tool", arguments);
        var expected = JsonNodeFactory.instance.objectNode();
        expected.put("name", "capture_structured_tool");
        expected.putObject("arguments").putObject("value").put("flag", false);

        arguments.withObject("/value").put("flag", true);
        var snapshot = state.getLastToolCall();
        assertThat(snapshot).isEqualTo(expected);
        var secondSnapshot = state.getLastToolCall();
        assertThat(snapshot).isNotSameAs(secondSnapshot);
        assertThat(snapshot.get("arguments")).isNotSameAs(secondSnapshot.get("arguments"));
        ((ObjectNode) snapshot.get("arguments")).removeAll();
        assertThat(secondSnapshot).isEqualTo(expected);
        assertThat(state.getLastToolCall()).isEqualTo(expected);
    }
}
