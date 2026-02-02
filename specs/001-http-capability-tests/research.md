# Research: HTTP Capability Tests

**Feature**: 001-http-capability-tests
**Date**: 2026-02-02

## Research Topics

### 1. MCP Client Implementation

**Decision**: Use MCPAssured from Quarkus MCP Server project

**Rationale**:
- The test-framework-concept.md explicitly references this: "The project will use the MCPAssured test client from the Quarkus MCP Server project"
- Well-maintained as part of the Quarkiverse ecosystem
- Handles SSE transport and Keycloak authentication out of the box
- Documented at https://docs.quarkiverse.io/quarkus-mcp-server/dev/#_testing
- Provides fluent API for tool listing and invocation

**Alternatives considered**:
- Custom MCP client: Higher development effort, maintenance burden
- Direct HTTP/SSE calls: Would require reimplementing MCP protocol parsing

**Integration approach**:
```xml
<dependency>
    <groupId>io.quarkiverse.mcp</groupId>
    <artifactId>quarkus-mcp-server-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

### 2. Mock HTTP Server

**Decision**: Use OkHttp MockWebServer (embedded in test process)

**Rationale**:
- Lightweight, starts in milliseconds
- No container overhead
- Dynamic port allocation built-in
- Easy request verification and response stubbing
- Well-documented and widely used in Java ecosystem
- Works offline without network access

**Alternatives considered**:
- WireMock standalone: Heavier, but offers more features (recording, templating)
- WireMock container via Testcontainers: Adds container startup time, port mapping complexity
- httpbin.org: Requires network, not suitable as primary testing approach

**Integration approach**:
```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <scope>test</scope>
</dependency>
```

---

### 3. Keycloak Container Configuration

**Decision**: Use Testcontainers Keycloak module with realm import

**Rationale**:
- Official Testcontainers module available
- Supports realm import from JSON file
- Dynamic port mapping handled automatically
- Matches constitution requirement for infrastructure via Testcontainers

**Configuration approach**:
```java
@Container
static KeycloakContainer keycloak = new KeycloakContainer()
    .withRealmImportFile("wanaku-realm.json")
    .withStartupTimeout(Duration.ofMinutes(2));
```

**Realm requirements**:
- Client: `wanaku-router` with client credentials grant
- Client: `wanaku-test` for test authentication
- Roles matching Wanaku's authorization model

---

### 4. Process Management for Local Java Processes

**Decision**: Use ProcessBuilder with graceful shutdown handling

**Rationale**:
- Standard Java API, no additional dependencies
- Full control over process lifecycle
- Can capture stdout/stderr for logging
- Supports environment variable and system property injection

**Implementation patterns**:
```java
public class ProcessManager {
    private Process process;

    public void start(List<String> command, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        pb.redirectOutput(logFile);
        pb.redirectErrorStream(true);
        process = pb.start();
    }

    public void stop() {
        process.destroy(); // SIGTERM
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly(); // SIGKILL
        }
    }
}
```

---

### 5. REST API Client for Router

**Decision**: Use Java 11+ HttpClient

**Rationale**:
- Built into JDK, no additional dependencies
- Supports async operations if needed
- Handles JSON with minimal additional libraries (Jackson already in classpath via Quarkus)
- Sufficient for REST API calls (tool registration, listing, removal)

**Alternatives considered**:
- OkHttp: Good option but adds dependency
- RestAssured: More test-focused but heavier
- Feign: Declarative but overkill for simple REST calls

---

### 6. Dynamic Port Allocation Strategy

**Decision**: ServerSocket(0) pattern with retry logic

**Rationale**:
- Standard approach for finding available ports
- Need retry logic for race conditions between port discovery and process startup
- Consistent with test-framework-concept.md guidance

**Implementation**:
```java
public class PortUtils {
    public static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static int findAvailablePortWithRetry(int maxRetries) {
        // Allocate and verify port is still available
    }
}
```

---

### 7. Test Lifecycle and Layered Isolation

**Decision**: JUnit 5 extensions with configurable lifecycle modes

**Rationale**:
- JUnit 5 `@ExtendWith` for shared infrastructure management
- Static fields in test classes for suite-scoped resources
- Instance fields for test-scoped resources
- Annotations for lifecycle mode configuration

**Implementation patterns**:
```java
@ExtendWith(WanakuTestExtension.class)
public abstract class BaseIntegrationTest {
    // Suite-scoped (shared across all tests)
    protected static KeycloakManager keycloakManager;
    protected static RouterManager routerManager;

    // Test-scoped by default
    protected HttpToolServiceManager httpToolServiceManager;
    protected MockWebServer mockServer;

    @BeforeAll
    static void setupSuiteInfrastructure() { ... }

    @BeforeEach
    void setupTestInfrastructure() { ... }

    @AfterEach
    void teardownTestInfrastructure() { ... }

    @AfterAll
    static void teardownSuiteInfrastructure() { ... }
}
```

---

### 8. Logging and Diagnostics

**Decision**: Redirect process output to timestamped log files in target/

**Rationale**:
- Matches constitution logging standards
- Preserves logs for post-mortem debugging
- Clear file naming: `{test-name}-{component}-{timestamp}.log`

**Implementation**:
```java
public class LogUtils {
    public static File createLogFile(String testName, String component) {
        String timestamp = Instant.now().toString().replace(":", "-");
        String filename = String.format("%s-%s-%s.log", testName, component, timestamp);
        return new File("target/logs", filename);
    }
}
```

---

## Dependency Summary

| Dependency | Purpose | Scope |
|------------|---------|-------|
| junit-jupiter | Test framework | test |
| testcontainers | Keycloak container | test |
| testcontainers-keycloak | Keycloak-specific support | test |
| quarkus-mcp-server-test | MCP client (MCPAssured) | test |
| mockwebserver | Mock HTTP endpoints | test |
| jackson-databind | JSON processing | compile |
| slf4j-api | Logging API | compile |
| logback-classic | Logging implementation | test |

## Open Questions Resolved

| Question | Resolution |
|----------|------------|
| Which MCP client library? | Quarkus MCPAssured (per test-framework-concept.md) |
| Mock server approach? | OkHttp MockWebServer (lightweight, embedded) |
| REST client library? | Java 11+ HttpClient (no additional deps) |
| How to manage local processes? | ProcessBuilder with graceful shutdown |
| Port allocation strategy? | ServerSocket(0) with retry logic |
