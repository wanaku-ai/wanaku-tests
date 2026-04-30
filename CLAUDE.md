# wanaku-tests

Integration test framework for Wanaku (MCP Router). Tests run against real processes (Router, capability providers, Keycloak) managed via ProcessManager lifecycle.

## Build & Run

```bash
# Build without tests
mvn -DskipTests package

# Run all integration tests (requires JARs in artifacts/)
mvn verify

# Run a specific test module
mvn verify -pl resources-tests

# Download artifacts first
cd artifacts && ./download.sh
```

Tests use maven-failsafe-plugin (`*ITCase.java` suffix). Spotless (Palantir format) auto-applies at compile.

## Project Structure

```
wanaku-tests/
  pom.xml                        # Parent POM (Java 21, modules, dependency management)
  artifacts/                     # Wanaku JARs (not in git, downloaded via download.sh)
  test-common/                   # Shared infrastructure (base classes, clients, managers, utils)
  http-capability-tests/         # HTTP tool service integration tests
  resources-tests/               # File resource provider integration tests
  camel-integration-capability-tests/  # Camel Integration Capability tests
```

### test-common layout

- `base/` - BaseIntegrationTest (layered lifecycle: suite-scoped infra, test-scoped capabilities)
- `managers/` - ProcessManager hierarchy (RouterManager, HttpCapabilityManager, ResourceProviderManager, CamelCapabilityManager, KeycloakManager)
- `client/` - RouterClient (REST), McpTestClient (MCP protocol), CLIExecutor, DataStoreClient
- `config/` - TestConfiguration (system-properties-driven, builder pattern), TargetConfiguration, OidcCredentials
- `model/` - HttpToolConfig, ResourceConfig, ToolInfo, ResourceReference
- `utils/` - PortUtils, HealthCheckUtils, LogUtils

## Key System Properties

- `wanaku.test.artifacts.dir` - path to artifacts directory (default: "artifacts")
- `wanaku.test.router.jar` - router JAR path
- `wanaku.test.http-service.jar` - HTTP tool service JAR path
- `wanaku.test.file-provider.jar` - file provider JAR path
- `wanaku.test.camel-capability.jar` - CIC JAR path
- `wanaku.test.cli.path` - CLI path (JAR or binary)
- `wanaku.test.timeout` - global timeout in seconds (default: 60)

## Test Lifecycle

1. `@BeforeAll` (suite-scoped, static): Keycloak container + Router process
2. `@BeforeEach` (test-scoped): capability providers + MCP client
3. Test execution
4. `@AfterEach`: capability teardown + resource cleanup
5. `@AfterAll`: Router + Keycloak shutdown

Tests gracefully skip when required JARs are missing (check `isRouterAvailable()`, `isFileProviderAvailable()`, etc.).

## Code Style & Conventions

- Java 21, formatted by Spotless with Palantir Java Format (auto-applied at compile)
- Wildcard imports are forbidden; import order enforced by Spotless
- Test classes: `*ITCase.java` (failsafe convention)
- Base classes per module: `HttpCapabilityTestBase`, `ResourceTestBase`, `CamelCapabilityTestBase`
- Assertions: AssertJ; async waits: Awaitility
- Ports allocated dynamically via `PortUtils.findAvailablePort()`

## Guidelines for Code Generation

Prioritize clarity, stability, and ease of maintenance:

- Keep test infrastructure simple and predictable. Avoid clever abstractions; prefer explicit setup over hidden magic in base classes.
- Each test should be readable on its own. A reader should understand what is being tested without tracing through multiple layers of inheritance.
- Use descriptive method and variable names. Favor longer, self-documenting names over terse ones.
- Do not add retry/polling logic unless testing an inherently async operation. Tests that need retries to pass are hiding flaky behavior.
- Prefer `assumeThat` (skip) over `assertThat` (fail) when checking infrastructure availability, so missing JARs don't cause false failures.
- Process lifecycle management must be deterministic: clear start, health-check, use, shutdown sequence. Always clean up in `@AfterAll`/`@AfterEach`.
- Do not introduce new dependencies without justification. The current stack (JUnit 5, AssertJ, Awaitility, Testcontainers) covers most needs.
- When adding a new test module, follow the existing pattern: own pom.xml inheriting parent, own `*TestBase` extending `BaseIntegrationTest`, `*ITCase` test classes.
- Log meaningful context (what failed, where, which port/path) at WARN level for skipped infrastructure. Do not log at INFO/DEBUG for routine operations in production-path code.
- Do not catch and swallow exceptions in test setup/teardown. Let failures propagate so they surface clearly.

## Recent Changes

- 001-http-capability-tests: Added Java 17+ (aligned with Quarkus requirements)
- 002-resources-tests: Resource provider integration tests with file provider lifecycle
- 003-camel-integration-capability-tests: Camel Integration Capability tests with multi-instance support
- Switched from maven-surefire-plugin to maven-failsafe-plugin for integration tests
- Made download script version-parameterized with snapshot support

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
