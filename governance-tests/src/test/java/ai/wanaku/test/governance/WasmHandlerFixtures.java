package ai.wanaku.test.governance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.JsonNode;

/** WAT components use the versioned Wanaku tool-handler contract from Wanaku PR #2306. */
final class WasmHandlerFixtures {
    private WasmHandlerFixtures() {}

    static Path result(Path directory, String name, JsonNode result) throws IOException {
        byte[] json = result.toString().getBytes(StandardCharsets.UTF_8);
        return write(
                directory,
                name,
                template("result")
                        .replace("RESULT_JSON", encoded(json))
                        .replace("RESULT_LENGTH", Integer.toString(json.length)));
    }

    static Path host(Path directory, String name, String template, String namespace, String target) throws IOException {
        byte[] namespaceBytes = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
        return write(
                directory,
                name,
                template(template)
                        .replace("TARGET_NAMESPACE", encoded(namespaceBytes))
                        .replace("TARGET_NAME", encoded(targetBytes))
                        .replace("NAMESPACE_LENGTH", Integer.toString(namespaceBytes.length))
                        .replace("NAME_LENGTH", Integer.toString(targetBytes.length)));
    }

    static Path write(Path directory, String name, String wat) throws IOException {
        return Files.writeString(directory.resolve(name + ".wat"), wat);
    }

    private static String template(String name) throws IOException {
        try (InputStream stream =
                WasmHandlerFixtures.class.getResourceAsStream("/wasm-tool-handler/" + name + ".wat")) {
            if (stream == null) {
                throw new IOException("Missing WASM handler fixture: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String encoded(byte[] bytes) {
        StringBuilder encoded = new StringBuilder();
        for (byte value : bytes) {
            encoded.append('\\')
                    .append(Character.forDigit((value & 0xff) >>> 4, 16))
                    .append(Character.forDigit(value & 15, 16));
        }
        return encoded.toString();
    }
}
