# Implementation Plan: HTTP Capability Tests

**Branch**: `001-http-capability-tests` | **Date**: 2026-02-02 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-http-capability-tests/spec.md`

## Summary

Create a JUnit 5 integration test framework for testing the Wanaku HTTP Tool Service capability. The implementation includes two Maven modules: `test-common` (shared infrastructure managers, utilities, and clients) and `http-capability-tests` (HTTP capability-specific tests). The framework uses Testcontainers for infrastructure (Keycloak), local Java processes for the system under test (Router, HTTP Tool Service), and an embedded mock HTTP server for reliable, offline-capable testing.

## Technical Context

**Language/Version**: Java 21 (per constitution requirement)
**Primary Dependencies**:
- JUnit 5 (jupiter) for test framework
- Testcontainers for Keycloak container management
- Quarkus MCP Server test utilities (MCPAssured) for MCP client
- OkHttp/Java HttpClient for REST API client (RouterClient)
- WireMock or MockWebServer for mock HTTP endpoints
- SLF4J + Logback for logging

**Storage**: N/A (test framework, no persistent storage)
**Testing**: JUnit 5 with Testcontainers
**Target Platform**: JVM (Linux, macOS, Windows with Docker)
**Project Type**: Multi-module Maven project
**Performance Goals**: Test suite completes in reasonable time for developer feedback loops
**Constraints**: Requires Docker for Testcontainers; pre-built JARs for Router and HTTP Tool Service
**Scale/Scope**: Initial scope covers HTTP capability; designed for extension to other capabilities

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Hybrid Execution Model | ✅ PASS | Keycloak via Testcontainers; Router/HTTP Tool Service as local Java processes; dynamic port allocation |
| II. Test Isolation | ✅ PASS | Isolated temp directories; state cleanup in @AfterEach; HTTP Tool Service test-scoped by default |
| III. Fail-Fast with Clear Errors | ✅ PASS | JAR existence verification; health checks; configurable timeouts; log preservation |
| IV. Configuration Flexibility | ✅ PASS | Test fixtures from resources; system property injection; configurable lifecycle modes |
| V. Performance-Aware Resource Management | ✅ PASS | Keycloak/Router suite-scoped; graceful shutdown; port allocation retry logic |
| VI. Layered Isolation | ✅ PASS | Core (Keycloak) suite-scoped; Router configurable; HTTP Tool Service test-scoped by default with suite-scoped option |

## Project Structure

### Documentation (this feature)

```text
specs/001-http-capability-tests/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
wanaku-tests/
├── pom.xml                          # Parent POM with dependency management
│
├── test-common/
│   ├── pom.xml
│   └── src/main/java/ai/wanaku/test/
│       ├── WanakuTestConstants.java
│       ├── config/
│       │   └── TestConfiguration.java
│       ├── managers/
│       │   ├── KeycloakManager.java
│       │   ├── RouterManager.java
│       │   ├── HttpToolServiceManager.java
│       │   └── ProcessManager.java      # Base class for process management
│       ├── client/
│       │   ├── RouterClient.java        # REST API client
│       │   └── CLIExecutor.java         # CLI command executor
│       ├── mock/
│       │   └── MockHttpServer.java      # WireMock/MockWebServer wrapper
│       ├── utils/
│       │   ├── PortUtils.java
│       │   ├── HealthCheckUtils.java
│       │   └── LogUtils.java
│       └── base/
│           └── BaseIntegrationTest.java # Abstract base with lifecycle
│
├── http-capability-tests/
│   ├── pom.xml
│   └── src/test/
│       ├── java/ai/wanaku/test/http/
│       │   ├── HttpToolRegistrationTest.java
│       │   ├── HttpToolInvocationTest.java
│       │   ├── HttpToolCliTest.java
│       │   ├── HttpToolErrorHandlingTest.java
│       │   ├── TestIsolationTest.java
│       │   └── ExternalApiTest.java     # Optional, skippable
│       └── resources/
│           ├── wanaku-realm.json        # Keycloak realm config
│           └── http-tools/
│               └── sample-tool.properties
│
└── artifacts/                           # Pre-built JARs location
    ├── wanaku-router-*.jar
    └── wanaku-tool-service-http-*.jar
```

**Structure Decision**: Multi-module Maven project following the test-framework-concept.md architecture. The `test-common` module provides reusable infrastructure while `http-capability-tests` contains the HTTP-specific tests. This structure supports future capability test modules.

## Constitution Check (Post-Design Verification)

*Re-verified after Phase 1 design completion.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Hybrid Execution Model | ✅ PASS | data-model.md: KeycloakManager uses Testcontainers; RouterManager/HttpToolServiceManager use ProcessBuilder |
| II. Test Isolation | ✅ PASS | data-model.md: HttpToolServiceManager test-scoped by default; MockHttpServer test-scoped; clearAllTools() in @AfterEach |
| III. Fail-Fast with Clear Errors | ✅ PASS | quickstart.md: JAR verification, health checks, log preservation in target/logs/ |
| IV. Configuration Flexibility | ✅ PASS | quickstart.md: System properties for all paths, timeouts; test fixtures from resources |
| V. Performance-Aware Resource Management | ✅ PASS | data-model.md: Keycloak/Router suite-scoped; graceful shutdown (SIGTERM then SIGKILL) |
| VI. Layered Isolation | ✅ PASS | data-model.md: Core layer (Keycloak), Router layer (configurable), Per-test layer (HTTP Tool Service, MockHttpServer) |

**Post-Design Gate**: ✅ PASSED - All constitution principles satisfied.

## Complexity Tracking

> No constitution violations requiring justification.

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| Two modules initially | test-common + http-capability-tests | Minimum viable for shared infrastructure + first capability tests |
| Mock server embedded | WireMock/MockWebServer in-process | Faster startup, simpler port management than container |
| Quarkus MCP test client | MCPAssured from quarkus-mcp-server | Maintained, well-documented, handles auth/SSE properly |

## Generated Artifacts

| Artifact | Path | Description |
|----------|------|-------------|
| research.md | specs/001-http-capability-tests/research.md | Technology decisions and rationale |
| data-model.md | specs/001-http-capability-tests/data-model.md | Entity definitions and lifecycle states |
| router-api.md | specs/001-http-capability-tests/contracts/router-api.md | REST API contract for RouterClient |
| mcp-client.md | specs/001-http-capability-tests/contracts/mcp-client.md | MCP protocol contract for tool operations |
| quickstart.md | specs/001-http-capability-tests/quickstart.md | Developer guide for writing tests |

## Next Steps

Run `/speckit.tasks` to generate the implementation task list (tasks.md).
