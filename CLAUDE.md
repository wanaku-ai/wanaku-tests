# wanaku-tests

Integration test framework for Wanaku (MCP Router). Tests run against real processes (Wanaku server, capability providers, Keycloak) managed via ProcessManager lifecycle. The Wanaku server is a Rust native binary.

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
- `managers/` - ProcessManager hierarchy (WanakuServerManager, HttpCapabilityManager, ResourceProviderManager, CamelCapabilityManager, KeycloakManager)
- `client/` - RouterClient (REST), McpTestClient (MCP protocol), ServiceClient (services API), CLIExecutor, DataStoreClient
- `config/` - TestConfiguration (system-properties-driven, builder pattern), TargetConfiguration, OidcCredentials
- `model/` - HttpToolConfig, ResourceConfig, ToolInfo, ResourceReference
- `utils/` - PortUtils, HealthCheckUtils, LogUtils

## Key System Properties

- `wanaku.test.artifacts.dir` - path to artifacts directory (default: "artifacts")
- `wanaku.test.server.binary` - wanaku-server binary path
- `wanaku.test.camel-capability.jar` - CIC JAR path
- `wanaku.test.cli.path` - CLI path (JAR or binary)
- `wanaku.test.timeout` - global timeout in seconds (default: 60)
- `wanaku.test.skip.threshold` - max allowed skip percentage before build fails (default: 30)
- `wanaku.test.server.mcp-id-filter` - add the `wanaku_mcp_id` filter to the generated server pipeline (default: false). Enable for servers that include wanaku-ai/wanaku#1849; older servers abort on the unknown filter type. The `full-integration-test` workflow sets this automatically by inspecting the built server's `default.yaml`.
- `wanaku.test.server.forward-headers` - comma-separated allowlist of request headers the server may forward to downstream MCP servers, set as the `WANAKU_FORWARD_HEADERS` env var (default: unset). Header forwarding is default-deny (wanaku-ai/wanaku#873); the `mcp-forwarding-tests` module sets this to `Authorization` so `McpHeaderForwardingITCase` can verify forwarding. Left unset, the server keeps its default-deny posture.
- `wanaku.test.external.mgmt.port` - connect to an already-running server management API on this port (skip launching server)
- `wanaku.test.external.mcp.port` - connect to an already-running server MCP endpoint on this port (requires mgmt.port too)
- `wanaku.test.external.cic.url` - connect to an already-running CIC MCP endpoint at this URL (skip launching CIC)

### Debugging with external instances

To run tests against already-running server and CIC instances (useful for debugging):

```bash
mvn verify -pl camel-integration-capability-tests \
  -Dwanaku.test.external.mgmt.port=8080 \
  -Dwanaku.test.external.mcp.port=8081 \
  -Dwanaku.test.external.cic.url=http://localhost:9000/mcp
```

You can also use only the server properties (the framework will still launch CIC) or only the CIC property (the framework will still launch the server).

When debugging the `mcp-forwarding-tests` against an external server, the framework does not launch the server and therefore cannot set `WANAKU_FORWARD_HEADERS`. Start your external server with `WANAKU_FORWARD_HEADERS=Authorization` yourself, otherwise `McpHeaderForwardingITCase` fails with "Missing required argument: authorization".

## Test Lifecycle

1. `@BeforeAll` (suite-scoped, static): Keycloak container + Wanaku server process
2. `@BeforeEach` (test-scoped): capability providers + MCP client
3. Test execution
4. `@AfterEach`: capability teardown + resource cleanup
5. `@AfterAll`: Server + Keycloak shutdown

Tests gracefully skip when required binaries are missing (check `isServerRunning()`, `isMcpClientAvailable()`, etc.).

### Wanaku Server Mode

When `wanaku.test.server.binary` is set and the binary exists, the framework starts the Wanaku server:
- WanakuServerManager starts a Rust binary
- Two ports: management API (dynamic) + MCP server (dynamic, separate port)
- No Keycloak/OIDC — all clients use null access tokens
- Capability providers start as standalone gRPC servers; the test harness registers them via `POST /api/v1/services`
- DataStore, ServiceCatalog, and Authentication tests are skipped (not native in the server)
- Health check uses `/healthz` instead of `/q/health/ready`

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

## Verifying Changes on CI

In many cases, you want to make sure that the changes work on CI. 

To do so trigger a CI build on YOUR OWN fork using your work branch:

```shell
gh workflow run integration-tests.yml --ref <work branch> -f version=<Wanaku version to use> -R <your github ID>/wanaku-tests
```

## Testing From Source

The `full-integration-test` workflow builds all Wanaku components from source before running the integration tests. This is useful for validating unreleased changes across repositories.

Each component accepts a repository (`owner/repo`) and branch. All default to the `wanaku-ai` org on `main`.

### Build all from a release branch (e.g. 0.2.x)

```shell
gh workflow run full-integration-test.yml \
  -f wanaku_branch=0.2.x \
  -f sdk_branch=0.2.x \
  -f cic_branch=0.2.x \
  -f barn_branch=0.2.x \
  -R <your github ID>/wanaku-tests
```

### Build only wanaku from a specific branch (others use main)

```shell
gh workflow run full-integration-test.yml \
  -f wanaku_branch=0.2.x \
  -R <your github ID>/wanaku-tests
```

### Build from a fork

```shell
gh workflow run full-integration-test.yml \
  -f wanaku_repo=<your github ID>/wanaku \
  -f wanaku_branch=my-feature-branch \
  -R <your github ID>/wanaku-tests
```

### Available inputs

| Input | Default | Description |
|-------|---------|-------------|
| `wanaku_repo` | `wanaku-ai/wanaku` | Wanaku repository (includes Rust server code) |
| `wanaku_branch` | `main` | Wanaku branch |
| `sdk_repo` | `wanaku-ai/wanaku-capabilities-java-sdk` | SDK repository |
| `sdk_branch` | `main` | SDK branch |
| `cic_repo` | `wanaku-ai/camel-integration-capability` | CIC repository |
| `cic_branch` | `main` | CIC branch |
| `barn_repo` | `wanaku-ai/wanaku-barn` | Wanaku Barn repository (Java components, CLI) |
| `barn_branch` | `main` | Wanaku Barn branch |

<!-- MANUAL ADDITIONS END -->
