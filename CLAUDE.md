# wanaku-tests

Integration tests and Midnight Saci test plans for Wanaku (governed action proxy for AI agents). The integration
tests run against real processes (Wanaku Server, capabilities, fixtures) managed via the ProcessManager lifecycle. The
Wanaku Server is a Rust native binary.

The contributor documentation is in `docs/` (start at `docs/index.md`). It is the source of truth for how to run the
tests, the system properties, the CI workflows, the test plans and the fixtures. Do not copy its content here.

## Build & Run

```bash
# Build without tests
mvn -DskipTests package

# Build the fixtures that most modules need
mvn -DskipTests package -f fixtures/test-mcp-server/pom.xml
mvn -DskipTests package -f fixtures/governance-capture-server/pom.xml

# Run a module against a local server build
mvn verify -pl router-tests -am -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

Tests use maven-failsafe-plugin (`*ITCase.java` suffix). Spotless (Palantir format) auto-applies at compile.
See `docs/integration-tests/run-locally.md`, `docs/integration-tests/modules.md` and
`docs/reference/system-properties.md`.

## Project Structure

```text
wanaku-tests/
  pom.xml                 # Parent POM (Java 21, modules, dependency management)
  docs/                   # Contributor documentation
  test-common/            # Shared framework (base classes, clients, managers, stubs, utils)
  router-tests/  mcp-forwarding-tests/  resources-tests/  governance-tests/
  camel-integration-capability-tests/  semantic-router-tests/  a2a-tests/
  fixtures/               # test-mcp-server, governance-capture-server, evaluator-block
  plans/                  # Midnight Saci test plans (plans/common: shared procedures)
  saci/                   # Saci worker image
  load/                   # k6 load tests
```

### test-common layout

- `base/` - BaseIntegrationTest, SharedInfrastructure (one server per module), SkipThresholdExtension
- `managers/` - ProcessManager hierarchy (WanakuServerManager, CamelCapabilityManager, MockMcpServerManager, SemanticRouterManager, OpaServerManager)
- `client/` - RouterClient (REST), McpTestClient (MCP protocol), CLIExecutor, ForwardsClient, NamespaceClient and other API clients
- `config/` - TestConfiguration (system-properties-driven, builder pattern)
- `stub/` - DeterministicLlmStub
- `utils/` - PortUtils, HealthCheckUtils, LogUtils

See `docs/integration-tests/framework.md` for the lifecycle.

## Code Style & Conventions

- Java 21, formatted by Spotless with Palantir Java Format (auto-applied at compile)
- Wildcard imports are forbidden; import order enforced by Spotless
- Test classes: `*ITCase.java` (failsafe convention)
- Base classes per module: `RouterTestBase`, `ResourceTestBase`, `CamelCapabilityTestBase`, `GovernanceTestBase`, ...
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

## Verifying Changes on CI

Run the `full-integration-test` workflow on your own fork. See `docs/integration-tests/run-in-github-ci.md` for the
commands and the inputs.

## Documentation

- Write the contributor docs in `docs/`. Each folder has an `index.md` hub that lists every page in it.
- When you add a page, add it to the hub of its folder.
- When you move a page out of a directory, leave a short pointer file at the old path.
- Use ASD-STE100 Simplified Technical English for technical text.
- Follow `docs/contributing/documentation.md`, and run its checks before you push.

<!-- MANUAL ADDITIONS END -->
