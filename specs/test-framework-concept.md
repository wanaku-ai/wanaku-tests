# Wanaku Test Framework Specification

## Job Description

The job is to develop an automated JUnit-based test framework for the Wanaku MCP Router project that validates the complete workflow without manual intervention.

## Objectives

The framework must:
- Clone and build required projects automatically
- Start and configure Keycloak authentication
- Build and test different capability configurations
- Execute CLI commands and validate responses
- Implement a minimal MCP client for tool/resource interaction
- Clean up state between tests for isolation
- Run the complete test suite with a single `mvn test` command

## Architecture

### Pre-Phase (Runs once - @BeforeAll)

The job is to prepare the foundation infrastructure that is reused across all tests.

**Required steps:**
1. Clone 3 repositories:
   - https://github.com/wanaku-ai/wanaku.git
   - https://github.com/wanaku-ai/wanaku-capabilities-java-sdk.git
   - https://github.com/wanaku-ai/camel-integration-capability.git

2. Start Keycloak container and configure authentication:
   - Use podman/docker to start Keycloak
   - Run auth configuration script
   - Generate and export client secret

3. Build foundation projects:
   - Build wanaku-capabilities-java-sdk (required dependency)
   - Build wanaku-router
   - Start wanaku-router instance

### Per-Test Setup (@BeforeEach)

The job is to build test-specific capability configuration for each test scenario.

**Required steps:**
1. Load test fixtures (routes, rules, dependencies) from fixtures/ directory
2. Build camel-integration-capability with test-specific configuration
3. Start test infrastructure (PostgreSQL container, SQLite database, data files)

**Rationale:** Each test validates a unique capability configuration. Isolation ensures tests don't interfere with each other.

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
1. Stop capability process
2. Remove tools/resources from Wanaku
3. Stop test containers (PostgreSQL, etc.)
4. Delete test data files
5. Clean ~/.wanaku data store if modified

**Rationale:** Next test must start with clean state to avoid port conflicts and data pollution.

### Global Cleanup (@AfterAll)

The job is to teardown the foundation infrastructure.

**Required steps:**
1. Stop wanaku-router
2. Stop Keycloak container
3. Remove all test containers

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

The job is to implement a basic MCP client in Java for automated testing.

**TODO:** Define transport protocol, authentication flow, and required API methods.

## CLI Integration

The job is to execute Wanaku CLI commands from Java tests and validate output.

**Approach:**
- Spawn `wanaku` CLI process using ProcessBuilder
- Capture stdout and stderr streams
- Parse output for JUnit assertions
- Check exit codes for success/failure validation

This approach tests the real CLI user experience.

## Test Examples

### HttpToolsTest
The job is to validate HTTP tool capability without Camel integration.
- Uses wanaku-tool-service-http
- Tests: add tool, list tools, invoke tool, parameter validation

### PostgresToolTest
The job is to validate Camel integration with PostgreSQL database.
- Uses camel-integration-capability + PostgreSQL container
- Tests: database connection, query execution, data retrieval

### FileResourceTest
The job is to validate file-based resources using Camel.
- Uses camel-integration-capability + local files
- Tests: resource registration, reading, content validation

### DataStoreTest
The job is to validate Wanaku data store functionality.
- Uses camel-integration-capability with datastore:// references
- Tests: store routes/rules, load from data store, capability startup

## Design Decisions

### 1. Container Management
- **Keycloak:** Manual podman/docker commands
  - Complex configuration with custom realm (wanaku-config.json)
  - Fixed client UUID and secret generation via API
  - Reuse existing configure-auth.sh scripts
  - Starts once in Pre-Phase, shared across all tests

- **PostgreSQL:** Testcontainers library
  - Simple configuration
  - Per-test lifecycle (create/destroy for each test)
  - Automatic cleanup and port management

### 2. Test Execution Model

The job is to run tests sequentially, not in parallel.
- Tests run one after another within each module
- All tests use fixed ports (8080 for router, 9190+ for capabilities)
- Maven can parallelize test modules using `-T` flag if needed

### 3. Build Strategy

The job is to build all projects during Pre-Phase (@BeforeAll).
- Clone repositories during Pre-Phase
- Build wanaku-capabilities-java-sdk during Pre-Phase
- Build wanaku-router during Pre-Phase
- Build camel-integration-capability during Per-Test Setup
- Execute with single `mvn test` command without manual preparation

### 4. Wanaku Router Lifecycle

The job is to start a single shared router instance for all tests.
- Start router once in Pre-Phase (@BeforeAll)
- Reuse the same instance across all tests
- Clean up tools/resources in Per-Test Cleanup (@AfterEach)
- Stop router in Global Cleanup (@AfterAll)

## Success Criteria

1. Complete test suite runs with single `mvn test` command
2. No manual steps required
3. Each test is fully isolated
4. Clear error messages when tests fail
5. Easy to add new test scenarios
