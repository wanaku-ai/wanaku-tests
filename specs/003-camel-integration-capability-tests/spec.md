# Feature Specification: Camel Integration Capability Tests

**Feature Branch**: `003-camel-integration-capability-tests`
**Created**: 2026-03-03
**Status**: Draft
**Input**: User description: "Create test code for the Camel Integration Capability (CIC) tests. This module should test the camel-integration-capability — a universal Camel-based capability runner that exposes Apache Camel routes as MCP tools and resources. A single JAR is configured dynamically via YAML files (routes, rules, dependencies) and CLI arguments. The existing test-common infrastructure should be reused and extended."

## Clarifications

### Session 2026-03-03

- Q: What is the CIC? → A: A single universal JAR that can act as any MCP tool or resource provider. Configured via 3 files: routes YAML (Camel DSL), rules YAML (MCP definitions), and optional dependencies.txt (Maven coordinates). The same JAR runs multiple times with different configs on different gRPC ports.
- Q: How does CIC differ from HTTP Capability and File Provider? → A: HTTP Capability and File Provider are specialized, single-purpose services. CIC is universal — one JAR becomes any capability depending on the YAML configuration. It uses Apache Camel routes under the hood.
- Q: What configuration loading schemes exist? → A: Two schemes: `file://` (local filesystem, absolute paths) and `datastore://` (Wanaku Data Store, files stored in Router). Tests should cover both.
- Q: What is the difference between tools and resources in CIC rules? → A: Tools have optional parameters (mapped to Camel headers), execute routes, and return results. Resources are read-only, have no parameters, their routes must have `autoStartup: false`, and content is read via consumer template.
- Q: What external dependencies are needed for testing? → A: PostgreSQL via Testcontainers (for database tool tests). No external services needed for file resource tests. SQLite can be used as an embedded database alternative.
- Q: Where is the pre-built JAR? → A: GitHub releases at `wanaku-ai/camel-integration-capability`. Needs to be added to download.sh and artifacts/.
- Q: What CLI arguments does CIC accept? → A: `--name`, `--grpc-port`, `--routes-ref`, `--rules-ref`, `--dependencies`, `--registration-url`, `--registration-announce-address`, `--token-endpoint`, `--client-id`, `--client-secret`, `--data-dir`, `--init-from`, `--repositories`, `--retries`, `--wait-seconds`, `--no-wait`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Extend Common Test Infrastructure for CIC (Priority: P1)

As a test developer, I need the test-common module extended with a CamelCapabilityManager that starts the camel-integration-capability JAR with dynamically generated YAML configuration files, so that I can write CIC tests without duplicating process management code.

**Why this priority**: CIC requires a new process manager pattern — unlike HTTP Capability or File Provider which take only system properties, CIC takes CLI arguments and external YAML files. The manager must generate routes/rules YAML at runtime and pass them via CLI args.

**Independent Test**: Can be tested by starting Keycloak, Router, and a CIC instance with a simple file-reading route, verifying health checks pass, and confirming the capability registers with the Router.

**Acceptance Scenarios**:

1. **Given** the test-common module, **When** CamelCapabilityManager starts the CIC JAR with a routes YAML and rules YAML, **Then** the CIC process starts, registers with the Router via gRPC, and the health check passes.

2. **Given** CamelCapabilityManager is configured with OIDC credentials, **When** it starts, **Then** it authenticates with Keycloak and registers successfully.

3. **Given** a CIC instance is running, **When** the test completes, **Then** CamelCapabilityManager stops the process gracefully.

4. **Given** multiple CIC instances are needed (e.g., one tool + one resource), **When** tests start them on different gRPC ports, **Then** both register independently with the Router without conflict.

5. **Given** routes YAML, rules YAML, and dependencies.txt are generated at runtime, **When** CamelCapabilityManager starts CIC, **Then** all three files are passed correctly via `--routes-ref`, `--rules-ref`, and `--dependencies` CLI arguments.

---

### User Story 2 - CIC Tool Registration and Invocation (Priority: P2)

As a test developer, I need to verify that CIC can expose Camel routes as MCP tools and that those tools can be invoked via the MCP protocol, so that I can confirm the fundamental tool capability works.

**Why this priority**: Tool exposure is the primary use case for CIC. Tests must verify the full flow: CIC starts → route loaded → tool registered in Router → tool invokable via MCP → response returned.

**Independent Test**: Can be tested by starting CIC with a simple Camel route (e.g., a direct: route that returns a static string), listing tools via MCP, invoking the tool, and verifying the response.

**Acceptance Scenarios**:

1. **Given** CIC is running with a route that returns a static response, **When** I list tools via MCP, **Then** the tool appears with the correct name and description from the rules YAML.

2. **Given** CIC is running with a route that accepts parameters, **When** I invoke the tool via MCP with parameters, **Then** the parameters are mapped to Camel headers and the route produces the expected response.

3. **Given** CIC is running with a rules YAML that defines required parameters, **When** I invoke the tool without required parameters, **Then** an error response is returned.

4. **Given** CIC is running with a route that uses Camel Simple expressions, **When** I invoke the tool with parameters, **Then** the Simple expressions resolve correctly using the parameter values.

5. **Given** CIC is running, **When** I list tools via REST API, **Then** the registered tool appears in the tools list.

---

### User Story 3 - CIC Resource Exposure and Reading (Priority: P3)

As a test developer, I need to verify that CIC can expose Camel routes as MCP resources and that those resources can be read via the MCP protocol, so that I can confirm read-only data access works through CIC.

**Why this priority**: Resources are the second major capability of CIC. Unlike tools, resources have no parameters and their routes must have `autoStartup: false`. This tests a fundamentally different code path.

**Independent Test**: Can be tested by starting CIC with a file-reading Camel route as a resource, listing resources via MCP, reading the resource, and verifying the content matches the source file.

**Acceptance Scenarios**:

1. **Given** CIC is running with a file-reading route defined as a resource in rules YAML, **When** I list resources via MCP, **Then** the resource appears with the correct name and description.

2. **Given** CIC is running with a file resource, **When** I read the resource via MCP (`resources/read`), **Then** I receive the file content as text.

3. **Given** CIC is running with a resource pointing to a non-existent file, **When** I attempt to read it via MCP, **Then** I receive an error or empty response.

4. **Given** CIC is running with a resource, **When** I list resources via REST API, **Then** the resource appears in the resources list with correct metadata.

---

### User Story 4 - Database Tool via CIC (Priority: P4)

As a test developer, I need to verify that CIC can expose database queries as MCP tools using JDBC Camel routes, so that I can confirm CIC handles external dependencies and database integration correctly.

**Why this priority**: Database integration is the most common real-world use case for CIC. It exercises dynamic dependency loading (PostgreSQL JDBC driver), bean initialization (DataSource), and query execution — all critical CIC features.

**Independent Test**: Can be tested by starting PostgreSQL via Testcontainers, starting CIC with a JDBC route and dependencies.txt containing the PostgreSQL driver, invoking the tool, and verifying query results.

**Acceptance Scenarios**:

1. **Given** PostgreSQL is running with test data, **When** CIC starts with a JDBC route, dependencies.txt (PostgreSQL driver), and tool rules, **Then** the tool registers with the Router and health check passes.

2. **Given** a database tool is registered, **When** I invoke the tool via MCP, **Then** I receive the query results as a string.

3. **Given** a database tool with parameterized query, **When** I invoke it with parameters, **Then** the parameters are substituted into the query and correct results are returned.

4. **Given** a database tool queries a non-existent table, **When** I invoke it, **Then** I receive an error response with a meaningful message.

---

### User Story 5 - Data Store Configuration Loading (Priority: P5)

As a test developer, I need to verify that CIC can load its configuration files (routes, rules, dependencies) from the Wanaku Data Store instead of local files, so that I can confirm the `datastore://` scheme works for centralized configuration management.

**Why this priority**: Data Store loading is how CIC is used in production — configuration is managed centrally by the Router, not scattered on local filesystems. This tests the full download workflow: upload to Data Store → CIC downloads → routes loaded.

**Independent Test**: Can be tested by uploading routes/rules YAML to the Router's Data Store via CLI, starting CIC with `datastore://` references, and verifying the capability registers and functions correctly.

**Acceptance Scenarios**:

1. **Given** routes YAML is uploaded to the Data Store via `wanaku data-store add`, **When** CIC starts with `--routes-ref datastore://routes.yaml`, **Then** CIC downloads the file from Data Store and loads the routes.

2. **Given** both routes and rules are in the Data Store, **When** CIC starts with `datastore://` references for both, **Then** the capability registers with the correct tools/resources.

3. **Given** a tool loaded from Data Store, **When** I invoke it via MCP, **Then** it behaves identically to a tool loaded from `file://`.

4. **Given** dependencies.txt is in the Data Store, **When** CIC starts with `--dependencies datastore://dependencies.txt`, **Then** the dependencies are downloaded and loaded into the classpath.

---

### User Story 6 - Multiple CIC Instances (Priority: P6)

As a test developer, I need to verify that multiple CIC instances can run simultaneously with different configurations, so that I can confirm the multi-capability deployment pattern works (same JAR, different roles).

**Why this priority**: The defining feature of CIC is "one JAR, many roles." This tests the real-world deployment pattern where multiple CIC instances run as different tools and resources concurrently.

**Independent Test**: Can be tested by starting two CIC instances (one as a tool, one as a resource) on different ports, verifying both register with the Router, and invoking/reading from each independently.

**Acceptance Scenarios**:

1. **Given** two CIC instances start with different `--name` and `--grpc-port`, **When** both are running, **Then** both register with the Router as separate capabilities.

2. **Given** CIC instance A is a tool and CIC instance B is a resource, **When** I list tools and resources via MCP, **Then** the tool appears in tools list and the resource appears in resources list.

3. **Given** both instances are running, **When** I invoke the tool and read the resource, **Then** both return correct results without interfering with each other.

---

### User Story 7 - Test Isolation Verification (Priority: P7)

As a test developer, I need to verify that CIC tests are properly isolated, so that tests can run in any order without flaky behavior.

**Why this priority**: Same as for all test modules — isolation is non-negotiable.

**Acceptance Scenarios**:

1. **Given** Test A registers a CIC tool and completes, **When** Test B runs, **Then** no tools from Test A are present.

2. **Given** a CIC instance is stopped between tests, **When** a new test starts a fresh CIC instance, **Then** the new instance registers without conflict from the previous one.

---

### Edge Cases

- What happens when the CIC JAR is missing? The framework must fail fast with a clear error pointing to download.sh.
- What happens when routes YAML has invalid Camel DSL syntax? CIC should fail to start and the test should fail with a clear error.
- What happens when rules YAML references a route ID that doesn't exist? The capability should fail or produce an error.
- What happens when dependencies.txt references a non-existent Maven artifact? CIC should fail with a dependency resolution error.
- What happens when the Data Store is unreachable? CIC should retry according to `--retries` and `--wait-seconds`, then fail.
- What happens when two CIC instances try to use the same gRPC port? The second should fail to start.
- What happens when PostgreSQL is unavailable for a database tool? The tool invocation should return a connection error.
- What happens when a route uses an unsupported Camel component without the dependency? The route should fail with a "component not found" error.

## Requirements *(mandatory)*

### Functional Requirements

#### Test-Common Extensions

- **FR-001**: The framework MUST provide a CamelCapabilityManager that starts camel-integration-capability as a local Java process, passing configuration via CLI arguments (`--name`, `--grpc-port`, `--routes-ref`, `--rules-ref`, `--dependencies`, `--registration-url`, `--registration-announce-address`, `--token-endpoint`, `--client-id`, `--client-secret`).
- **FR-002**: Test configuration files (routes YAML, rules YAML, dependencies.txt) MUST be stored as static fixtures in `src/test/resources/fixtures/{scenario-name}/`. Each scenario gets its own directory with 2-3 files.
- **FR-003**: The framework MUST provide a `TestFixtures` utility that copies fixture files from test resources to a temporary directory, with optional `${VAR}` placeholder substitution for dynamic values (e.g., database URLs, ports known only at runtime).
- **FR-004**: The framework MUST extend TestConfiguration with a `camelCapabilityJarPath` property and corresponding `findJar()` lookup for `camel-integration-capability` artifacts.
- **FR-005**: CamelCapabilityManager MUST support the `file://` scheme for all configuration file references, pointing to fixture files copied to the temporary directory.
- **FR-006**: The framework MUST support starting multiple CamelCapabilityManager instances simultaneously on different gRPC ports.
- **FR-007**: The framework MUST provide a `test-common/src/main/java/ai/wanaku/test/services/` package for Testcontainers-based external service managers. Each manager handles lifecycle, connection details, and test data seeding. The first implementation is PostgresServiceManager. This package is designed for extensibility — future CIC tests may need MySQL, Kafka, MongoDB, S3 (via LocalStack), or any other backend accessible via Camel components.
- **FR-008**: The framework MUST update download.sh to include the camel-integration-capability JAR from GitHub releases.

#### CIC Tests Module

- **FR-009**: The test module MUST verify that CIC can register Camel routes as MCP tools with the Router.
- **FR-010**: The test module MUST verify that MCP tools can be invoked and return correct responses.
- **FR-011**: The test module MUST verify parameter mapping — both automatic (Wanaku. prefix) and explicit (custom header names via rules properties).
- **FR-012**: The test module MUST verify that CIC can register Camel routes as MCP resources.
- **FR-013**: The test module MUST verify that MCP resources can be read and return correct file content.
- **FR-014**: The test module MUST verify database tool integration with PostgreSQL (JDBC route, query execution, result serialization).
- **FR-015**: The test module MUST verify dynamic dependency loading via dependencies.txt (e.g., PostgreSQL JDBC driver).
- **FR-016**: The test module MUST verify Data Store configuration loading (`datastore://` scheme) for routes and rules.
- **FR-017**: The test module MUST verify that multiple CIC instances can run simultaneously with different configurations.
- **FR-018**: The test module MUST clean all registered tools and resources between tests.
- **FR-019**: The test module MUST validate error handling for invalid routes, missing dependencies, and unavailable backends.

### Key Entities

- **CamelCapabilityManager**: Manages the camel-integration-capability process lifecycle. Unlike ProcessManager subclasses for HTTP/File providers that use system properties, CamelCapabilityManager passes configuration via CLI arguments and external YAML files. Supports multiple concurrent instances.
- **TestFixtures**: Utility that loads test fixture directories from `src/test/resources/fixtures/`, copies files to a temporary directory, and supports `${VAR}` placeholder substitution for dynamic values (e.g., `${JDBC_URL}`, `${DB_USER}`). Returns Path to the temp directory for use with `--routes-ref file://...`.
- **services/ package** (`test-common/.../services/`): Dedicated package for Testcontainers-based external service managers. Each manager follows a common pattern: start container, expose connection details, seed test data, stop container. Designed for extensibility — any backend accessible via Camel components can have a manager here.
  - **PostgresServiceManager**: First implementation. Manages PostgreSQL container. Provides JDBC URL, credentials, schema init, and test data seeding.
  - *(Future)*: MySQLServiceManager, KafkaServiceManager, MongoServiceManager, LocalStackServiceManager (S3/SQS), etc.
- **CamelCapabilityTestBase**: New test base class extending BaseIntegrationTest with CIC-specific lifecycle management. Manages CamelCapabilityManager instances and cleanup.

### Assumptions

- Pre-built camel-integration-capability JAR is available at `artifacts/camel-integration-capability/` (fat JAR with dependencies, not Quarkus fast-jar format).
- CIC JAR naming follows: `camel-integration-capability-*-jar-with-dependencies.jar`.
- CIC registers with Router via gRPC (same discovery mechanism as other capabilities).
- CIC uses `--registration-url` for Router REST API and `--token-endpoint` for OIDC.
- PostgreSQL Testcontainer uses `postgres:16` image with dynamic port allocation.
- Data Store API is available via Router REST API (`/api/v1/data-store/*`) or CLI (`wanaku data-store add`).
- Routes YAML follows Apache Camel YAML DSL specification.
- Rules YAML follows CIC-specific schema with `mcp.tools` and `mcp.resources` sections.
- Dependencies.txt uses Maven coordinate format: `groupId:artifactId:version`.

## Reuse from Previous Modules

### Reuse as-is
- KeycloakManager
- RouterManager
- ProcessManager (base class)
- PortUtils, HealthCheckUtils, LogUtils
- BaseIntegrationTest lifecycle pattern
- CLIExecutor, CLIResult
- WanakuTestConstants
- OidcCredentials
- RouterClient (tool and resource operations)
- McpTestClient (tools and resources MCP operations)

### Extend
- **TestConfiguration**: Add `camelCapabilityJarPath` property and `findJar()` for CIC JAR lookup.
- **WanakuTestConstants**: Add `PROP_CAMEL_CAPABILITY_JAR`, `ROUTER_DATA_STORE_PATH`.
- **download.sh**: Add CIC JAR download from camel-integration-capability GitHub releases.

### Create new
- **CamelCapabilityManager**: New process manager using CLI arguments instead of system properties. Supports concurrent instances.
- **TestFixtures**: Utility for loading fixture files from resources with optional `${VAR}` substitution.
- **fixtures/ directory**: Static test config files in `src/test/resources/fixtures/{scenario}/` (routes, rules, dependencies per scenario).
- **services/ package**: Dedicated package for external service managers (Testcontainers-based).
  - **PostgresServiceManager**: First implementation (PostgreSQL).
- **CamelCapabilityTestBase**: New test base class with CIC-specific setup/teardown.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Complete CIC test suite runs with `mvn clean install -pl camel-integration-capability-tests` and no manual intervention.
- **SC-002**: All tests pass when run in any order (verified by running with randomized test order).
- **SC-003**: Test failures provide actionable error messages identifying the root cause within 30 seconds of investigation.
- **SC-004**: Adding a new CIC test requires only creating a fixture directory with routes/rules YAML files and a test method (no infrastructure code changes).
- **SC-005**: Framework correctly detects and reports missing prerequisites (CIC JAR, PostgreSQL Docker image) before attempting to run tests.
- **SC-006**: No test data (temp files, database containers, registered tools/resources) persists after the test suite completes.
- **SC-007**: CIC tests can run independently from HTTP capability tests and resource tests.
- **SC-008**: Multiple CIC instances can run concurrently without port conflicts or registration collisions.
- **SC-009**: Data Store tests work end-to-end: upload config → start CIC with datastore:// → invoke tool/read resource → verify result.
- **SC-010**: Database tool tests start PostgreSQL container automatically via Testcontainers (no manual Docker setup required).