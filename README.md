# Wanaku Integration Tests

Integration test framework for [Wanaku](https://github.com/wanaku-ai/wanaku) — an MCP Router that connects AI-enabled applications via the Model Context Protocol.

## What This Tests

This framework tests Wanaku capabilities:

- **HTTP Capability** — register HTTP endpoints as tools, invoke via MCP
- **Resources** — expose, list, read, and remove file resources via REST API, MCP, and CLI
- **Camel Integration** — Apache Camel-based tools, file resources, PostgreSQL, multi-instance
- **Semantic Router** — local semantic routes through Wanaku, audit delivery, controls and recovery
- **Cross-Capability Tests** — router restart and mixed-capability flows
- **Governance Audit** — decision and administrative events, query filters, redaction, retention,
  restart durability, and observational storage failures

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for Keycloak testcontainer)

## Setup

### Step 1: Get Wanaku Artifacts

Tests require Router, HTTP Capability, File Provider, and CLI JARs. Choose one option:

**Option A: Download from GitHub releases (recommended)**
```bash
./artifacts/download.sh 0.1.0             # release
./artifacts/download.sh 0.1.1-SNAPSHOT    # snapshot (early-access)
```

**Option B: Copy from local Wanaku build**
```bash
WANAKU_DIR=/path/to/wanaku
EXAMPLES_DIR=/path/to/wanaku-examples

cp -r $WANAKU_DIR/wanaku/wanaku-router/target/quarkus-app artifacts/wanaku-router-backend-0.1.0
cp -r $WANAKU_DIR/wanaku/capabilities/tools/wanaku-tool-service-http/target/quarkus-app artifacts/wanaku-tool-service-http-0.1.0
cp -r $EXAMPLES_DIR/providers/wanaku-provider-file/target/quarkus-app artifacts/wanaku-provider-file
cp -r $WANAKU_DIR/wanaku/cli/target/quarkus-app artifacts/wanaku-cli-0.1.0
```

After setup:
```
artifacts/
├── wanaku-router-backend-0.1.0/
│   ├── quarkus-run.jar
│   └── lib/
├── wanaku-tool-service-http-0.1.0/
│   ├── quarkus-run.jar
│   └── lib/
├── wanaku-provider-file/
│   ├── quarkus-run.jar
│   └── lib/
└── wanaku-cli-0.1.0/
    ├── quarkus-run.jar
    └── lib/
```

### Step 2: (Optional) Configure CLI for CLI tests

CLI tests require `wanaku` CLI. Choose one option:

**Option A: Use CLI JAR from artifacts**
```bash
# Set system property to point to CLI JAR
mvn clean install -Dwanaku.test.cli.path=../artifacts/wanaku-cli-0.1.0/quarkus-run.jar
```

**Option B: Install globally via jbang**
```bash
jbang app install wanaku@wanaku-ai/wanaku

# Verify installation
wanaku --version
```

> **Note:** CLI tests will be skipped if `wanaku` command is not found in PATH and `-Dwanaku.test.cli.path` is not set.

## Run Tests

Audit scenarios use isolated, harness-owned Rust servers and the local governance capture fixture:

```bash
mvn -DskipTests package -f fixtures/governance-capture-server/pom.xml
mvn verify -pl governance-tests -am -Dit.test='Audit*ITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server \
  -Dwanaku.test.evaluator.wasm=/path/to/wanaku/actions/dist/safety_review_action.wasm
```

Use a server build with governance audit support. Missing binaries, the capture fixture, or evaluator
WASM cause infrastructure skips; supported behavior failures fail the tests. External-server mode is
skipped because these scenarios change configuration, restart processes, and inject storage failures.
The `full-integration-test` workflow builds these prerequisites from source and runs the audit tests
in managed mode.

### Action-policy lifecycle

The lifecycle scenario in `governance-tests` exercises the public management and MCP boundaries as one
persisted workflow: startup with an allow posture, runtime deny activation, rejected candidate retention,
immutable rollback, and process restart. It uses the local governance capture fixture and reuses the
focused selector/precedence/validation suites for their specialized matrices.

```bash
mvn -DskipTests package -f fixtures/governance-capture-server/pom.xml
mvn verify -pl governance-tests -am -Dit.test='ActionPolicyLifecycleITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

The lifecycle test requires a managed Wanaku server and the governance capture fixture. It deliberately
does not support external-server mode because the scenario changes live action-policy state, restarts
the process while retaining the persistence directory, and verifies the revision history after restart.
The capture fixture remains alive across the managed server restart; the test refreshes or recreates its
forward before the final post-restart MCP requests.

### OPA evaluator

OPA scenarios require a Wanaku build with OPA evaluator support (PR #2082), its
`opa_allow_action.wasm`, and a native OPA binary. Real policy tests start OPA locally;
contract and governance tests use a local HTTP stub. All suites use the capture fixture
and isolated, harness-owned servers:

```bash
mvn -DskipTests package -f fixtures/governance-capture-server/pom.xml
mvn verify -pl governance-tests -am -Dit.test='Opa*ITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server \
  -Dwanaku.test.opa.wasm=/path/to/wanaku/actions/dist/opa_allow_action.wasm \
  -Dwanaku.test.opa.binary=/path/to/opa
```

The OPA WASM property is separate from `wanaku.test.evaluator.wasm` (the safety-review
action). Missing local prerequisites cause infrastructure skips; invalid configured
OPA paths and behavior failures fail. External-server mode is skipped. The
`full-integration-test` workflow detects OPA-capable sources, builds the OPA action,
installs checksum-verified OPA 1.21.1, and fails if any OPA suite is missing or skipped.
Older source branches retain their existing behavior.

```bash
# Recommended: build and run all tests with CLI JAR and debug logging
mvn clean install -Dwanaku.test.cli.path=../artifacts/wanaku-cli-0.1.0/quarkus-run.jar -Dwanaku.log.level=DEBUG

# Build and run all tests (requires wanaku CLI installed via jbang)
mvn clean install

# Run single test
mvn clean install -pl http-capability-tests -Dtest=HttpToolRegistrationITCase#shouldRegisterHttpToolViaRestApi

# Run with debug logging
mvn clean install -Dwanaku.log.level=DEBUG

# Run with CLI JAR instead of system CLI
mvn clean install -Dwanaku.test.cli.path=../artifacts/wanaku-cli-0.1.0/quarkus-run.jar
```

## Project Structure

```
wanaku-tests/
├── artifacts/             # Wanaku JARs (not in git)
├── load/                  # k6 load tests for the MCP request path (see load/README.md)
├── http-capability-tests/ # HTTP capability tests (18 tests)
│   └── src/test/java/ai/wanaku/test/http/
│       ├── HttpToolCliITCase.java          # CLI tool management (3)
│       ├── HttpToolRegistrationITCase.java # Register, list, remove tools via REST API (9)
│       └── PublicApiITCase.java            # External API invocations (6)
├── resources-tests/       # Resource provider tests (12 tests)
│   └── src/test/java/ai/wanaku/test/resources/
│       ├── RestApiResourceITCase.java     # Expose, list, remove resources via REST API (6)
│       ├── McpResourceITCase.java         # List and read resources via MCP (3)
│       └── CliResourceITCase.java         # Expose, list, remove resources via CLI (3)
├── cross-capability-tests/ # Mixed capability tests
│   └── src/test/java/ai/wanaku/test/cross/
│       └── RouterReconnectionITCase.java  # Router restart + HTTP, resource, CIC reconnection
├── camel-integration-capability-tests/ # CIC tests (16 tests)
│   ├── src/test/java/ai/wanaku/test/camel/
│   │   ├── CamelBasicToolITCase.java      # Simple tools: register, invoke, params (6)
│   │   ├── CamelFileResourceITCase.java   # File resources: list, read, error (5)
│   │   ├── CamelPostgresToolITCase.java   # PostgreSQL JDBC tool via Testcontainers (3)
│   │   └── CamelMultiInstanceITCase.java  # Multiple CIC instances simultaneously (2)
│   └── src/test/resources/fixtures/       # Static YAML config per scenario
│       ├── simple-tool/                   # direct: routes + tool rules
│       ├── file-resource/                 # file route + resource rules
│       ├── postgres-tool/                 # JDBC route + deps + seed.sql
│       └── multi-instance-tool/           # tool for multi-instance test
└── test-common/           # Shared infrastructure
    └── src/main/java/ai/wanaku/test/
        ├── base/      # BaseIntegrationTest
        ├── client/    # RouterClient, McpTestClient, CLIExecutor, DataStoreClient
        ├── fixtures/  # TestFixtures (load + ${VAR} substitution)
        ├── managers/  # KeycloakManager, RouterManager, CamelCapabilityManager
        └── services/  # PostgresServiceManager (Testcontainers)
```

## Logs

After test run, logs are in `http-capability-tests/target/logs/`:

```
target/logs/
├── test-framework.log           # Test framework output
├── router/                      # Router process logs (per test class)
│   └── wanaku-router-HttpToolCliITCase-2026-02-13_16-31-33.log
└── http-capability/             # HTTP Capability logs (per test)
    └── HttpToolCliITCase/
        └── shouldRegisterHttpToolViaCli-2026-02-13_16-31-38.log
```

## Architecture

```
┌─────────────┐     ┌──────────┐     ┌───────────────────┐
│  Keycloak   │────▶│  Router  │◀────│  HTTP Capability  │
│  (Auth)     │     │  (MCP)   │     │  (Tool Service)   │
└─────────────┘     └──────────┘     └───────────────────┘
       ▲                 ▲                    ▲
       └─────────────────┴────────────────────┘
                    Test Framework
```

**Lifecycle:**
- Suite-scoped: Keycloak, Router (shared across tests in a class)
- Test-scoped: HTTP Capability (fresh per test)

## Testing From Source (CI)

The `full-integration-test` workflow builds all Wanaku components from source before running the integration tests. This is useful for validating unreleased changes across repositories.

Each component accepts a repository (`owner/repo`) and branch input. All default to the `wanaku-ai` org on `main`.

**Build all from a release branch (e.g. 0.2.x):**
```bash
gh workflow run full-integration-test.yml \
  -f wanaku_branch=0.2.x \
  -f sdk_branch=0.2.x \
  -f cic_branch=0.2.x \
  -f barn_branch=0.2.x \
  -R <your github ID>/wanaku-tests
```

**Build only wanaku from a specific branch (others use main):**
```bash
gh workflow run full-integration-test.yml \
  -f wanaku_branch=0.2.x \
  -R <your github ID>/wanaku-tests
```

**Build from a fork:**
```bash
gh workflow run full-integration-test.yml \
  -f wanaku_repo=<your github ID>/wanaku \
  -f wanaku_branch=my-feature-branch \
  -R <your github ID>/wanaku-tests
```

| Input | Default | Description |
|-------|---------|-------------|
| `wanaku_repo` | `wanaku-ai/wanaku` | Wanaku repository |
| `wanaku_branch` | `main` | Wanaku branch |
| `sdk_repo` | `wanaku-ai/wanaku-capabilities-java-sdk` | SDK repository |
| `sdk_branch` | `main` | SDK branch |
| `cic_repo` | `wanaku-ai/camel-integration-capability` | CIC repository |
| `cic_branch` | `main` | CIC branch |
| `barn_repo` | `wanaku-ai/wanaku-barn` | Barn repository |
| `barn_branch` | `main` | Barn branch |
| `wsr_repo` | `wanaku-ai/wanaku-semantic-router` | Semantic Router repository |
| `wsr_branch` | `main` | Semantic Router branch |

### Semantic router and audit tests

The `semantic-router-tests` module requires a Wanaku server containing
[the semantic audit receiver](https://github.com/wanaku-ai/wanaku/pull/2125) and a
WSR build containing **both** [audit delivery](https://github.com/wanaku-ai/wanaku-semantic-router/pull/13)
and [local route startup](https://github.com/wanaku-ai/wanaku-semantic-router/issues/14).
Until these features reach `main`, select a combined WSR branch; the workflow does
not merge feature branches. Local startup must support `--routes-ref`, an adjacent
`semantic-router.properties`, and `--dependencies`.
Audit source revision and digest must come from the loaded local snapshot
(`catalog.reference()`); Barn catalog settings are absent in local mode.

```bash
gh workflow run full-integration-test.yml \
  -f wanaku_repo=<your github ID>/wanaku \
  -f wanaku_branch=semantic-audit-receiver \
  -f wsr_repo=<your github ID>/wanaku-semantic-router \
  -f wsr_branch=local-startup-and-audit \
  -R <your github ID>/wanaku-tests
```

Source CI builds WSR after the SDK and preserves its executable thin JAR and
adjacent `lib/` under `artifacts/wanaku-semantic-router/`. It passes the explicit
JAR path and fails if any expected semantic router scenario is missing, skipped,
or failed, including parameterized routing cases.

For a local run, copy the complete WSR distribution, keeping `lib/` beside its
executable JAR, then run:

```bash
mvn verify -pl semantic-router-tests -am \
  -Dwanaku.test.server.binary=/absolute/path/to/wanaku-server \
  -Dwanaku.test.semantic-router.jar=/absolute/path/to/wanaku-semantic-router.jar
```

Missing local artifacts produce WARN messages and skipped tests. Existing artifacts
that fail startup or lack the required features fail the tests. Routing uses a
local deterministic model fixture; no external model service or API key is required.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Skipping infrastructure setup` | Copy JARs to `artifacts/` |
| `wanaku: command not found` | Install CLI: `jbang app install wanaku@wanaku-ai/wanaku` |
| `Port already in use` | Kill orphan processes: `pkill -f quarkus-run.jar` |
| `Keycloak connection refused` | Ensure Docker is running |

## Modules

- [HTTP Capability Tests](http-capability-tests/README.md) — HTTP tool registration and invocation
- [Resources Tests](resources-tests/README.md) — file resource management via REST API, MCP, and CLI
- [Cross-Capability Tests](cross-capability-tests/README.md) — router restart and mixed-capability scenarios
- [Camel Integration Capability Tests](camel-integration-capability-tests/README.md) — CIC tools, resources, PostgreSQL, multi-instance
- [Saci test image](saci/README.md) — a [Midnight Saci](https://github.com/orpiske/midnight-saci) worker image with `wanaku-server` and the `wanaku` CLI, for Saci test plans

### A2A integration tests

The `a2a-tests` module tests the managed A2A 0.3 JSON-RPC proxy introduced in
[Wanaku PR #2071](https://github.com/wanaku-ai/wanaku/pull/2071), using a custom
agent hosted by Camel Integration Capability. See [the module README](a2a-tests/README.md)
for protocol compatibility, prerequisites, and coverage.

```bash
mvn verify -pl a2a-tests -am \
  -Dwanaku.test.server.binary=/path/to/wanaku-server \
  -Dwanaku.test.server.mcp-id-filter=true \
  -Dwanaku.test.server.a2a-enabled=true \
  -Dwanaku.test.camel-capability.jar=/path/to/cic.jar
```

A2A is disabled by default because older servers reject the new pipeline filters.
The source-build CI workflow detects support and enables it automatically. For
an external server, also supply `wanaku.test.external.a2a.port` alongside the
management and MCP ports; the external pipeline must expose the managed A2A
listener and allow the test agent actions.
