# Wanaku Test Framework Specification

## Job Description

The job is to develop an automated JUnit-based test framework for the Wanaku MCP Router project that validates the complete workflow without manual intervention.

## Objectives

The framework must:
- Start and configure Keycloak authentication via Testcontainers
- Test different capability configurations with isolated state
- Execute CLI commands and validate responses (optional - may use REST API instead)
- Implement a minimal MCP client for tool/resource interaction
- Use dynamic port allocation to avoid conflicts
- Clean up state between tests for complete isolation
- Run the complete test suite with a single `mvn test` command

## Prerequisites

The framework assumes pre-built artifacts to optimize test execution speed:
- Router JAR must exist: `wanaku-router/target/quarkus-app/quarkus-run.jar`
- Capability JAR must exist: `camel-integration-capability/target/*-jar-with-dependencies.jar`
- Tests will fail fast with clear error if JARs are not found
- Users must build these once: `mvn clean package -DskipTests` in respective directories

## Architecture

### Pre-Phase (Runs once - @BeforeAll)

The job is to prepare the foundation infrastructure that is reused across all tests.

**Required steps:**
1. Verify pre-built JARs exist (fail fast if missing)

2. Start Keycloak container via Testcontainers:
   - Import wanaku realm configuration
   - Generate client credentials
   - Expose dynamic port for auth

3. Allocate dynamic ports for Router:
   - HTTP port (for MCP and REST API)
   - gRPC port (for capability communication)

4. Start wanaku-router as local Java process:
   - Inject dynamic Keycloak URL
   - Inject dynamic HTTP/gRPC ports
   - Configure isolated data directory (not ~/.wanaku)
   - Wait for health check to pass

5. Initialize MCP client with dynamic router URL

### Per-Test Setup (@BeforeEach)

The job is to prepare test-specific capability configuration for each test scenario.

**Required steps:**
1. Start test infrastructure via Testcontainers (PostgreSQL, etc.)
2. Allocate dynamic gRPC port for capability
3. Load test fixtures (routes, rules, dependencies) from src/test/resources/
4. Start camel-integration-capability as local Java process with:
   - Dynamic gRPC port
   - Dynamic router URL
   - Dynamic Keycloak URL
   - Dynamic database connection URLs
   - Test fixture file paths
5. Wait for capability registration in router

### Test Execution (@Test)

The job is to execute test scenario using CLI commands and MCP client, then validate with JUnit assertions.

**Required pattern:**
1. Start capability with test configuration
2. Execute CLI commands (wanaku tools add, wanaku tools list, etc.)
3. Use MCP client to invoke tools or read resources
4. Assert expected behavior using JUnit
5. Validate error handling for negative cases

**Example flow:**
```
Test: PostgresToolTest
1. Start camel-integration-capability with postgres-routes.yaml
2. Execute: wanaku tools list
3. Assert: tool "get-users" exists in output
4. MCP Client: invoke tool "get-users"
5. Assert: response contains expected user data
```

### Per-Test Cleanup (@AfterEach)

The job is to reset all test-specific state before the next test runs.

**Required steps:**
1. Stop capability process gracefully (SIGTERM, then SIGKILL if needed)
2. Remove tools/resources from Wanaku via REST API or CLI
3. Stop and remove test containers (Testcontainers handles this automatically)
4. Delete test data files from temp directories
5. Verify router is still healthy for next test

### Global Cleanup (@AfterAll)

The job is to teardown the foundation infrastructure.

**Required steps:**
1. Stop wanaku-router process gracefully
2. Clean isolated test data directory
3. Testcontainers automatically stops and removes all containers (Keycloak, PostgreSQL, etc.)

## Project Structure

The job is to organize tests as separate Maven modules grouped by capability type.

```
wanaku-tests/
├── pom.xml (parent)
│
├── test-common/
│   └── src/main/java/ai/wanaku/test/
│       ├── client/      (MCP client)
│       ├── managers/    (Keycloak, Router, Capability managers)
│       └── utils/
│
├── http-capability-tests/
│   ├── pom.xml
│   └── src/test/
│       ├── java/.../httptools/
│       │   ├── MeowFactsTest.java
│       │   └── WeatherSimpleTest.java
│       └── resources/
│
├── camel-tools-tests/
│   ├── pom.xml
│   └── src/test/
│       ├── java/.../cameltools/
│       │   ├── PostgresToolTest.java
│       │   └── FinanceDbToolTest.java
│       └── resources/
│           ├── postgres/
│           │   ├── postgres-routes.camel.yaml
│           │   ├── postgres-rules.yaml
│           │   ├── dependencies.txt
│           │   └── test-schema.sql
│           └── finance-db/
│               ├── finance-routes.camel.yaml
│               ├── finance-rules.yaml
│               ├── dependencies.txt
│               └── finance.db
│
├── camel-resources-tests/
│   ├── pom.xml
│   └── src/test/
│       ├── java/.../camelresources/
│       │   ├── OfficePolicyResourceTest.java
│       │   └── PerformanceCategoriesResourceTest.java
│       └── resources/
│           ├── office-policy/
│           │   ├── office-policy-routes.camel.yaml
│           │   ├── office-policy-rules.yaml
│           │   └── office-policy.txt
│           └── performance-categories/
│               ├── performance-routes.camel.yaml
│               ├── performance-rules.yaml
│               └── performance-categories.txt
│
├── datastore-tests/
│   ├── pom.xml
│   └── src/test/
│       ├── java/.../datastore/
│       │   ├── DataStoreAddTest.java
│       │   ├── DataStoreListTest.java
│       │   └── LoadFromDataStoreTest.java
│       └── resources/
│           └── finance-db/
│               ├── finance-routes.camel.yaml
│               ├── finance-rules.yaml
│               └── dependencies.txt
│
└── integration-tests/
    ├── pom.xml (parent for E2E modules)
    │
    ├── employee-management-e2e/
    │   ├── pom.xml
    │   └── src/test/
    │       ├── java/.../EmployeeManagementE2ETest.java
    │       └── resources/
    │           ├── postgres/
    │           ├── office-policy/
    │           ├── finance-db/
    │           └── performance-categories/
    │
    └── multi-capability-e2e/
        ├── pom.xml
        └── src/test/...
```

Each test module focuses on specific capability type. Integration tests are separate modules with complete workflow scenarios.

## MCP Client Implementation

**TODO:** Define transport protocol, authentication flow, and required API methods.

## CLI Integration

Execute Wanaku CLI commands from Java tests and validate output:
- Spawn `wanaku` CLI process using ProcessBuilder
- Capture stdout and stderr streams
- Parse output for JUnit assertions
- Check exit codes for success/failure validation

## Test Examples

### HttpToolsTest
- Uses wanaku-tool-service-http
- Tests: add tool, list tools, invoke tool, parameter validation

### PostgresToolTest
- Uses camel-integration-capability + PostgreSQL container
- Tests: database connection, query execution, data retrieval

### FileResourceTest
- Uses camel-integration-capability + local files
- Tests: resource registration, reading, content validation

### DataStoreTest
- Uses camel-integration-capability with datastore:// references
- Tests: store routes/rules, load from data store, capability startup

## Design Decisions

### 1. Hybrid Execution Model

**Infrastructure (Testcontainers):**
- Keycloak, PostgreSQL, and other external dependencies run in containers
- Dynamic ports managed by Testcontainers
- Containers communicate with local processes via localhost:mappedPort

**System Under Test (Local Processes):**
- Wanaku Router and capabilities run as local Java processes via ProcessBuilder
- Dynamic ports allocated via PortUtils
- Configuration injected via system properties

### 2. Port Allocation

- All ports allocated dynamically
- PortUtils finds available ports using ServerSocket(0) pattern
- Retry logic handles race conditions when starting processes
- Router: HTTP port, gRPC port
- Capabilities: gRPC port per test
- Containers: dynamic ports via Testcontainers

### 3. Data Isolation

- Each test run uses isolated temporary directory
- Not `~/.wanaku` to avoid conflicts with development environment
- Directory path passed via system property to router and capabilities
- Cleanup after all tests complete

### 4. Prerequisites

- Assume pre-built JARs exist before tests
- Router JAR: `wanaku-router/target/quarkus-app/quarkus-run.jar`
- Capability JAR: `camel-integration-capability/target/*-jar-with-dependencies.jar`
- Tests verify existence in @BeforeAll and fail fast if missing

### 5. Router Lifecycle

- Single shared instance per test suite
- Start once in @BeforeAll
- Reuse across all tests in module
- Clean tools/resources between tests in @AfterEach
- Health check before each test
- Stop in @AfterAll

### 6. Container Management

All infrastructure via Testcontainers:
- Keycloak: Start once in @BeforeAll with realm import
- PostgreSQL: Per-test lifecycle
- Automatic cleanup on shutdown

### 7. Logging

Process outputs redirected to log files in each module's `target/` directory:
- Format: `{test-name}-{component}-{timestamp}.log`
- Components: `wanaku-router`, `capability-{name}`, `keycloak`, `postgresql`
- Logs preserved for debugging failed tests

## Success Criteria

1. Complete test suite runs with single `mvn test` command
2. No manual steps required
3. Each test is fully isolated
4. Clear error messages when tests fail
5. Easy to add new test scenarios
