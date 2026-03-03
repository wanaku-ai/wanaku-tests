# Implementation Plan: Camel Integration Capability Tests

**Branch**: `003-camel-integration-capability-tests` | **Date**: 2026-03-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-camel-integration-capability-tests/spec.md`

## Summary

Create a test module for the Camel Integration Capability (CIC) — a universal Camel-based capability runner that exposes Apache Camel routes as MCP tools and resources. The module extends test-common with a CamelCapabilityManager (CLI-arg-based process management), configuration builders (routes YAML, rules YAML, dependencies.txt), a services/ package for Testcontainers-based external service managers (starting with PostgreSQL), and a DataStoreClient for `datastore://` configuration loading tests.

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**: JUnit 5, Testcontainers (Keycloak, PostgreSQL), McpAssured (quarkus-mcp-server-test 1.8.1), Jackson, Awaitility, AssertJ
**Storage**: PostgreSQL 16 via Testcontainers (for database tool tests), Wanaku Data Store (for config loading tests)
**Testing**: JUnit 5 integration tests (`*ITCase.java`), Maven Failsafe
**Target Platform**: macOS/Linux (local dev), CI (GitHub Actions)
**Project Type**: Multi-module Maven (extends existing wanaku-tests)
**Performance Goals**: N/A (test framework, not production)
**Constraints**: Tests must complete within CI timeout; Testcontainers requires Docker
**Scale/Scope**: ~20-30 tests across 5-6 test classes

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Hybrid Execution Model | ✅ PASS | Infrastructure (Keycloak, PostgreSQL) via Testcontainers; SUT (Router, CIC) as local Java processes; all ports dynamically allocated |
| II. Test Isolation | ✅ PASS | Each test gets fresh CIC instance(s); tools/resources cleaned via @AfterEach; CIC processes stopped after each test |
| III. Fail-Fast | ✅ PASS | CIC JAR existence checked in @BeforeAll; health checks via gRPC port + capability registration; logs in target/ |
| IV. Configuration Flexibility | ✅ PASS | CamelRouteBuilder/CamelRulesBuilder generate dynamic configs; no hardcoded fixtures required; system properties for JAR paths |
| V. Performance-Aware | ✅ PASS | Keycloak/Router suite-scoped; PostgreSQL suite-scoped; only CIC instances are per-test |
| VI. Layered Isolation | ✅ PASS | Core layer: Keycloak + Router (suite); Per-test layer: CIC instances + tool/resource cleanup |

No violations. Complexity Tracking section not needed.

## Project Structure

### Documentation (this feature)

```text
specs/003-camel-integration-capability-tests/
├── plan.md              # This file
├── research.md          # Phase 0: JAR format, Data Store API, PostgreSQL setup
├── data-model.md        # Phase 1: Entity definitions
├── quickstart.md        # Phase 1: Usage examples
├── contracts/           # Phase 1: API contracts
│   ├── data-store-api.md
│   └── cic-cli-args.md
└── tasks.md             # Phase 2: Task breakdown (created by /speckit.tasks)
```

### Source Code (repository root)

```text
test-common/src/main/java/ai/wanaku/test/
├── base/
│   └── BaseIntegrationTest.java          # Existing (no changes)
├── client/
│   ├── RouterClient.java                 # Existing (no changes)
│   ├── McpTestClient.java                # Existing (no changes)
│   ├── CLIExecutor.java                  # Existing (no changes)
│   └── DataStoreClient.java             # NEW: Data Store REST API client
├── config/
│   └── TestConfiguration.java            # EXTEND: add camelCapabilityJarPath
├── managers/
│   ├── ProcessManager.java               # Existing (no changes)
│   ├── RouterManager.java                # Existing (no changes)
│   ├── HttpCapabilityManager.java        # Existing (no changes)
│   ├── ResourceProviderManager.java      # Existing (no changes)
│   └── CamelCapabilityManager.java      # NEW: CIC process manager (CLI args)
├── services/                             # NEW: External service managers package
│   └── PostgresServiceManager.java      # NEW: PostgreSQL via Testcontainers
├── fixtures/
│   └── TestFixtures.java                # NEW: Load fixture dirs with ${VAR} substitution
├── utils/
│   └── (existing utils, no changes)
└── WanakuTestConstants.java              # EXTEND: add CIC constants

camel-integration-capability-tests/
├── pom.xml
├── src/test/java/ai/wanaku/test/camel/
│   ├── CamelCapabilityTestBase.java       # Base class for CIC tests
│   ├── CamelToolITCase.java               # Tool registration & invocation
│   ├── CamelResourceITCase.java           # Resource exposure & reading
│   ├── CamelDatabaseToolITCase.java       # PostgreSQL JDBC integration
│   ├── CamelDataStoreITCase.java          # datastore:// config loading
│   └── CamelMultiInstanceITCase.java      # Multiple concurrent CIC instances
└── src/test/resources/fixtures/           # Static test config files
    ├── simple-tool/                       # routes.camel.yaml, rules.yaml
    ├── parameterized-tool/                # routes with ${header.Wanaku.*}
    ├── explicit-mapping-tool/             # rules with explicit header mapping
    ├── file-resource/                     # file-reading route as resource
    ├── postgres-tool/                     # JDBC route + dependencies.txt + seed.sql
    ├── multi-instance-tool/               # tool for multi-instance test
    └── multi-instance-resource/           # resource for multi-instance test
```

**Structure Decision**: Follows existing multi-module Maven pattern. New test module `camel-integration-capability-tests` alongside `http-capability-tests` and `resources-tests`. Shared infrastructure goes in `test-common` under new packages (`services/`, `builders/`).

## Complexity Tracking

> No constitution violations to justify.