# Quickstart: HTTP Capability Tests

**Feature**: 001-http-capability-tests
**Date**: 2026-02-02

## Prerequisites

1. **Java 21** installed
2. **Docker** running (for Testcontainers)
3. **Pre-built Wanaku artifacts** in `artifacts/` directory:
   - `wanaku-router-*.jar`
   - `wanaku-tool-service-http-*.jar`
   - (Optional) `wanaku` CLI binary on PATH

## Quick Start

### 1. Clone and Build

```bash
# Clone the test repository
git clone https://github.com/wanaku-ai/wanaku-tests.git
cd wanaku-tests

# Ensure artifacts are in place
ls artifacts/
# wanaku-router-1.0.0-runner.jar
# wanaku-tool-service-http-1.0.0-runner.jar

# Run all tests
mvn test
```

### 2. Run Specific Test Module

```bash
# Run only HTTP capability tests
mvn test -pl http-capability-tests

# Run a specific test class
mvn test -pl http-capability-tests -Dtest=HttpToolRegistrationTest

# Run with debug output
mvn test -pl http-capability-tests -Dtest.debug=true
```

### 3. View Test Logs

Test logs are preserved in `target/logs/`:

```bash
# List all test logs
ls http-capability-tests/target/logs/

# View Router logs
cat http-capability-tests/target/logs/HttpToolRegistrationTest-router-*.log

# View HTTP Tool Service logs
cat http-capability-tests/target/logs/HttpToolRegistrationTest-http-tool-service-*.log
```

---

## Writing Your First Test

### 1. Create Test Class

```java
package ai.wanaku.test.http;

import ai.wanaku.test.base.BaseIntegrationTest;
import ai.wanaku.test.client.RouterClient;
import ai.wanaku.test.mock.MockHttpServer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class MyFirstHttpToolTest extends BaseIntegrationTest {

    @Test
    void shouldRegisterAndInvokeHttpTool() {
        // 1. Setup mock response
        mockServer.enqueue(200, "{\"message\": \"Hello World\"}");

        // 2. Register tool pointing to mock
        routerClient.registerTool(HttpToolConfig.builder()
            .name("hello-api")
            .description("Returns a greeting")
            .uri(mockServer.getUrl("/hello"))
            .build());

        // 3. Verify tool is registered
        assertThat(routerClient.listTools())
            .extracting("name")
            .contains("hello-api");

        // 4. Invoke tool via MCP
        var result = mcpClient.callTool("hello-api", Map.of());

        // 5. Assert response
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Hello World");

        // 6. Verify mock received the request
        var request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/hello");
    }
}
```

### 2. Lifecycle Annotations

The `BaseIntegrationTest` handles infrastructure lifecycle automatically:

```java
public abstract class BaseIntegrationTest {

    // Suite-scoped (shared across all tests in class)
    protected static KeycloakManager keycloakManager;
    protected static RouterManager routerManager;

    // Test-scoped (fresh per test)
    protected HttpToolServiceManager httpToolServiceManager;
    protected MockHttpServer mockServer;
    protected RouterClient routerClient;
    protected McpClient mcpClient;

    @BeforeAll
    static void setupSuite() {
        // 1. Verify prerequisites (JARs exist)
        // 2. Start Keycloak container
        // 3. Allocate ports for Router
        // 4. Start Router process
        // 5. Wait for Router health check
    }

    @BeforeEach
    void setupTest() {
        // 1. Start MockHttpServer
        // 2. Allocate port for HTTP Tool Service
        // 3. Start HTTP Tool Service
        // 4. Wait for registration with Router
        // 5. Create clients
    }

    @AfterEach
    void teardownTest() {
        // 1. Stop HTTP Tool Service
        // 2. Stop MockHttpServer
        // 3. Clear all tools from Router
    }

    @AfterAll
    static void teardownSuite() {
        // 1. Stop Router
        // 2. Stop Keycloak
    }
}
```

---

## Common Test Patterns

### Testing Tool Registration

```java
@Test
void shouldRegisterHttpTool() {
    var config = HttpToolConfig.builder()
        .name("my-tool")
        .uri(mockServer.getUrl("/api"))
        .build();

    routerClient.registerTool(config);

    assertThat(routerClient.toolExists("my-tool")).isTrue();
}

@Test
void shouldRejectDuplicateToolName() {
    routerClient.registerTool(createTool("duplicate"));

    assertThatThrownBy(() -> routerClient.registerTool(createTool("duplicate")))
        .isInstanceOf(ToolExistsException.class);
}
```

### Testing Tool Invocation

```java
@Test
void shouldInvokeToolWithParameters() {
    mockServer.enqueue(200, "{\"result\": \"success\"}");

    routerClient.registerTool(HttpToolConfig.builder()
        .name("param-tool")
        .uri(mockServer.getUrl("/api"))
        .inputSchema(schema -> schema.required("id"))
        .build());

    var result = mcpClient.callTool("param-tool", Map.of("id", "123"));

    assertThat(result.isError()).isFalse();
    assertThat(mockServer.takeRequest().getPath()).contains("id=123");
}
```

### Testing Error Handling

```java
@Test
void shouldHandleConnectionError() {
    // Register tool with unreachable URL
    routerClient.registerTool(HttpToolConfig.builder()
        .name("unreachable")
        .uri("http://localhost:1/nowhere")
        .build());

    var result = mcpClient.callTool("unreachable", Map.of());

    assertThat(result.isError()).isTrue();
    assertThat(result.getContent()).contains("Connection refused");
}

@Test
void shouldHandleHttpError() {
    mockServer.enqueue(500, "Internal Server Error");

    routerClient.registerTool(HttpToolConfig.builder()
        .name("error-tool")
        .uri(mockServer.getUrl("/error"))
        .build());

    var result = mcpClient.callTool("error-tool", Map.of());

    assertThat(result.isError()).isTrue();
}
```

### Testing Isolation

```java
@Test
@Order(1)
void testA_RegistersTool() {
    routerClient.registerTool(createTool("tool-a"));
    assertThat(routerClient.toolExists("tool-a")).isTrue();
}

@Test
@Order(2)
void testB_ShouldNotSeePreviousTool() {
    // This passes because @AfterEach clears all tools
    assertThat(routerClient.toolExists("tool-a")).isFalse();
}
```

---

## Configuration

### System Properties

| Property | Default | Description |
|----------|---------|-------------|
| `wanaku.test.artifacts.dir` | `artifacts/` | Directory containing pre-built JARs |
| `wanaku.test.router.jar` | Auto-detect | Explicit path to Router JAR |
| `wanaku.test.http-service.jar` | Auto-detect | Explicit path to HTTP Tool Service JAR |
| `wanaku.test.cli.path` | `wanaku` | Path to CLI binary |
| `wanaku.test.timeout` | `60s` | Default timeout for health checks |
| `wanaku.test.debug` | `false` | Enable debug logging |
| `wanaku.test.external-apis` | `false` | Enable external API tests |

### Example Usage

```bash
# Custom artifacts directory
mvn test -Dwanaku.test.artifacts.dir=/path/to/artifacts

# Skip external API tests
mvn test -Dwanaku.test.external-apis=false

# Extended timeout for slow CI
mvn test -Dwanaku.test.timeout=120s
```

---

## Troubleshooting

### "JAR not found" Error

```
ERROR: Router JAR not found at artifacts/wanaku-router-*.jar
```

**Solution**: Ensure pre-built JARs are in the `artifacts/` directory.

### "Port already in use" Error

```
ERROR: Failed to allocate port after 5 retries
```

**Solution**: Check for leftover processes from previous test runs:
```bash
ps aux | grep wanaku
# Kill any orphan processes
```

### Docker Connection Error

```
ERROR: Could not connect to Docker daemon
```

**Solution**: Ensure Docker is running:
```bash
docker info
```

### Test Timeout

```
ERROR: Health check failed after 60 seconds
```

**Solution**: Increase timeout or check logs for startup errors:
```bash
cat http-capability-tests/target/logs/*-router-*.log
```
