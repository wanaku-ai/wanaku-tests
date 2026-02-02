# Feature Specification: HTTP Capability Tests

**Feature Branch**: `001-http-capability-tests`
**Created**: 2026-02-02
**Status**: Draft
**Input**: User description: "Create test code for the http-capability tests. This module should be capable of testing the wanaku-http-capability. As there is no foundational code available on this project yet, it should be a part of the project to also create the common test code."

## Clarifications

### Session 2026-02-02

- Q: How is the HTTP Tool Service deployed relative to the Router? → A: Separate gRPC service (must start wanaku-tool-service-http process alongside Router)
- Q: What lifecycle scope should the HTTP Tool Service follow? → A: Test-scoped by default (starts fresh each test in @BeforeEach, stops in @AfterEach), with option to run suite-scoped for performance optimization
- Q: Which method should tests use to register tools with the Router? → A: REST API as primary method, CLI for specific CLI-behavior validation tests
- Q: What HTTP endpoints should tests target for tool invocation? → A: Local mock server as primary (reliable, offline, controllable), with optional external API tests for real-world validation

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Create Common Test Infrastructure (Priority: P1)

As a test developer, I need a reusable test infrastructure (test-common module) that provides shared utilities for managing Keycloak, Router, and capability service lifecycles, so that I can write HTTP capability tests without duplicating infrastructure code.

**Why this priority**: This is the foundation for all test modules. Without the common infrastructure, no capability tests can be written. It enables the layered isolation model defined in the constitution.

**Independent Test**: Can be fully tested by starting Keycloak, Router, and HTTP Tool Service via the infrastructure managers, verifying health checks pass, and confirming graceful shutdown works.

**Acceptance Scenarios**:

1. **Given** a fresh test environment, **When** KeycloakManager starts a Keycloak container, **Then** the container is running with the wanaku realm imported and credentials are accessible.

2. **Given** Keycloak is running, **When** RouterManager starts the Wanaku Router process, **Then** the router is running with dynamic ports allocated and health check passes.

3. **Given** Router is running and test-scoped mode is active (default), **When** @BeforeEach is invoked, **Then** HttpToolServiceManager starts a fresh HTTP Tool Service that registers with the Router via gRPC.

4. **Given** suite-scoped mode is configured, **When** @BeforeAll is invoked, **Then** HttpToolServiceManager starts the HTTP Tool Service once and reuses it across all tests.

5. **Given** Router is running, **When** a test completes, **Then** RouterManager can clean all registered tools/resources without restarting.

6. **Given** Router is running, **When** a test requires fresh router state, **Then** RouterManager can restart the router and wait for health check.

7. **Given** all infrastructure is running, **When** @AfterAll is invoked, **Then** all processes stop gracefully and containers are removed.

---

### User Story 2 - HTTP Tool Registration and Listing (Priority: P2)

As a test developer, I need to verify that HTTP tools can be registered with the Wanaku Router and listed via the MCP protocol, so that I can confirm the HTTP capability works correctly for basic tool management.

**Why this priority**: Tool registration and listing are the fundamental operations that must work before tool invocation can be tested. This validates the core HTTP capability integration.

**Independent Test**: Can be fully tested by registering an HTTP tool pointing to the local mock server via REST API, listing tools via MCP client, and verifying the tool appears with correct metadata.

**Acceptance Scenarios**:

1. **Given** Router and HTTP Tool Service are running, **When** I register an HTTP tool via REST API targeting the mock server, **Then** the tool appears in the tools list with the correct name and description.

2. **Given** multiple HTTP tools are registered via REST API, **When** I list all tools, **Then** all registered tools are returned with their metadata.

3. **Given** an HTTP tool is registered, **When** I remove the tool via REST API, **Then** it no longer appears in the tools list.

4. **Given** Router and HTTP Tool Service are running, **When** I register an HTTP tool via CLI command, **Then** the tool appears in the tools list (CLI-specific validation test).

---

### User Story 3 - HTTP Tool Invocation (Priority: P3)

As a test developer, I need to verify that HTTP tools can be invoked via the MCP protocol and return correct responses, so that I can confirm end-to-end HTTP capability functionality.

**Why this priority**: Tool invocation is the primary use case for HTTP capability but depends on registration working correctly. This validates the complete data flow.

**Independent Test**: Can be fully tested by registering an HTTP tool targeting the mock server, invoking it via MCP client with test parameters, and verifying the response matches the mock's configured response.

**Acceptance Scenarios**:

1. **Given** an HTTP tool is registered targeting the mock server, **When** I invoke the tool with valid parameters, **Then** I receive a successful response with the expected data format configured in the mock.

2. **Given** an HTTP tool is registered, **When** I invoke it with invalid parameters, **Then** I receive an appropriate error response indicating the validation failure.

3. **Given** the mock server is configured to return an error, **When** I invoke the tool, **Then** I receive an error response matching the mock's configured error.

4. **Given** an HTTP tool targets an unreachable endpoint (mock server stopped), **When** I invoke the tool, **Then** I receive an error response indicating connection failure within a reasonable timeout.

5. **Given** an HTTP tool is registered targeting an external API (optional real-world test), **When** I invoke the tool, **Then** I receive a valid response from the external service.

---

### User Story 4 - Test Isolation Verification (Priority: P4)

As a test developer, I need to verify that tests are properly isolated and don't affect each other, so that tests can run in any order without flaky behavior.

**Why this priority**: Isolation is a non-negotiable principle in the constitution. This story validates that the infrastructure correctly implements the layered isolation model.

**Independent Test**: Can be fully tested by running multiple tests that register different tools, verifying each test starts with clean state, and confirming no tool leakage between tests.

**Acceptance Scenarios**:

1. **Given** Test A registers tool "tool-a" and completes, **When** Test B runs, **Then** tool "tool-a" is not present in the tools list.

2. **Given** Router state cleanup is triggered, **When** a new test starts, **Then** no tools or resources from previous tests are present.

3. **Given** tests run in random order, **When** the full test suite completes, **Then** all tests pass consistently.

---

### Edge Cases

- What happens when the Router process crashes mid-test? The framework must detect this and fail the test with a clear error.
- What happens when the HTTP Tool Service process crashes? The framework must detect this and fail the test with a clear error.
- How does the system handle Keycloak authentication timeout? Tests must fail fast with actionable error messages.
- What happens when dynamic port allocation fails due to port exhaustion? The framework must retry or fail with clear instructions.
- How does the system handle slow HTTP endpoints? Timeouts must be configurable and failures must be clearly reported.
- What happens when the HTTP Tool Service fails to register with the Router? Tests must fail fast with clear gRPC connection diagnostics.
- What happens when the mock server fails to start? Tests must fail fast with clear error indicating mock server unavailability.

## Requirements *(mandatory)*

### Functional Requirements

#### Common Test Infrastructure (test-common module)

- **FR-001**: The framework MUST provide a KeycloakManager that starts Keycloak via Testcontainers with the wanaku realm imported.
- **FR-002**: The framework MUST provide a RouterManager that starts the Wanaku Router as a local Java process with dynamic port allocation for HTTP and gRPC.
- **FR-003**: The framework MUST provide an HttpToolServiceManager that starts the wanaku-tool-service-http as a separate local Java process with dynamic gRPC port, configured to register with the Router. By default, the service starts fresh for each test (@BeforeEach) and stops after each test (@AfterEach). An optional suite-scoped mode allows reusing the service across all tests for performance optimization.
- **FR-004**: The framework MUST provide a PortUtils utility that allocates available ports using the ServerSocket(0) pattern with retry logic.
- **FR-005**: The framework MUST provide an MCP client that can list tools, invoke tools, and handle authentication with Keycloak.
- **FR-006**: The framework MUST provide a base test class that implements the layered isolation lifecycle (@BeforeAll, @BeforeEach, @AfterEach, @AfterAll).
- **FR-007**: The framework MUST verify pre-built JAR existence for both Router and HTTP Tool Service in @BeforeAll and fail fast with clear instructions if missing.
- **FR-008**: The framework MUST redirect all process output (stdout/stderr) to log files in the target/ directory.
- **FR-009**: The framework MUST provide health check utilities that wait for components to be ready before proceeding.
- **FR-010**: The framework MUST support configurable timeouts for all wait operations.
- **FR-011**: The framework MUST use isolated temporary directories for test data (never ~/.wanaku).
- **FR-012**: The framework MUST provide a RouterClient that wraps the Router's REST API for tool registration, listing, and removal operations.
- **FR-013**: The framework MUST provide a CLIExecutor utility for spawning Wanaku CLI commands and capturing output for CLI-specific validation tests.
- **FR-014**: The framework MUST provide a MockHttpServer that can be started per-test to serve as target endpoints for HTTP tools. The mock server must support configurable responses, error simulation, and request verification.

#### HTTP Capability Tests (http-capability-tests module)

- **FR-015**: The test module MUST be able to register HTTP tools with the Wanaku Router primarily via REST API (RouterClient).
- **FR-016**: The test module MUST include CLI-specific tests that validate tool operations via the Wanaku CLI command.
- **FR-017**: The test module MUST be able to list registered HTTP tools via MCP client.
- **FR-018**: The test module MUST be able to invoke HTTP tools and validate responses against mock server expectations.
- **FR-019**: The test module MUST be able to remove HTTP tools from the Router.
- **FR-020**: The test module MUST validate error handling for invalid tool parameters.
- **FR-021**: The test module MUST validate error handling for unreachable HTTP endpoints.
- **FR-022**: The test module MUST clean all registered tools between tests.
- **FR-023**: The test module MAY include optional tests that target external public APIs (e.g., httpbin.org) for real-world validation. These tests should be skippable via configuration.

### Key Entities

- **KeycloakManager**: Manages the Keycloak container lifecycle, realm import, and credential generation. Provides authentication URLs and tokens for other components. Lifecycle: suite-scoped (starts in @BeforeAll, stops in @AfterAll).
- **RouterManager**: Manages the Wanaku Router process lifecycle, health checks, and state cleanup. Provides dynamic HTTP and gRPC port information. Lifecycle: suite-scoped by default with optional per-test restart.
- **HttpToolServiceManager**: Manages the wanaku-tool-service-http process lifecycle. Configures gRPC connection to Router and waits for successful registration. Lifecycle: test-scoped by default (starts in @BeforeEach, stops in @AfterEach), with optional suite-scoped mode.
- **MockHttpServer**: Embedded or container-based mock HTTP server for testing tool invocations. Supports configurable responses, latency simulation, error injection, and request verification. Lifecycle: test-scoped.
- **PortUtils**: Utility for dynamic port allocation with retry logic to handle race conditions.
- **RouterClient**: REST API client for the Wanaku Router. Provides methods for tool registration, listing, removal, and other management operations. Used as the primary interface for test setup.
- **CLIExecutor**: Utility for spawning Wanaku CLI commands, capturing stdout/stderr, and validating exit codes. Used for CLI-specific behavior tests.
- **MCPClient**: Client for interacting with the Wanaku Router via MCP protocol. Supports tool listing, tool invocation, and authentication.
- **BaseIntegrationTest**: Abstract base class implementing the layered isolation lifecycle pattern for all test classes.
- **HttpTool**: Represents an HTTP tool configuration with target URL, HTTP method, headers (header.* prefix), and query parameters (query.* prefix).

### Assumptions

- Pre-built Wanaku Router JAR is available at a configurable location (default: artifacts directory).
- Pre-built wanaku-tool-service-http JAR is available at a configurable location.
- Wanaku CLI binary is available on PATH or at a configurable location for CLI-specific tests.
- Docker is available on the test execution environment for Testcontainers.
- Tests run on a system with sufficient available ports for dynamic allocation.
- Network access to external APIs is optional and only required for real-world validation tests.
- Keycloak realm configuration file is available in the test resources.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Complete test suite runs successfully with a single `mvn test` command and no manual intervention.
- **SC-002**: All tests pass when run in any order (verified by running with randomized test order).
- **SC-003**: Test failures provide actionable error messages that identify the root cause within 30 seconds of investigation.
- **SC-004**: Adding a new HTTP tool test requires only creating a new test method and optional resource files (no infrastructure code changes).
- **SC-005**: The test suite completes execution for the HTTP capability module within a reasonable timeframe for developer feedback loops.
- **SC-006**: No test data persists after the test suite completes (verified by checking for leftover processes and temp directories).
- **SC-007**: Framework correctly detects and reports missing prerequisites (Router JAR, HTTP Tool Service JAR, CLI binary) before attempting to run tests.
- **SC-008**: Core tests (using mock server) pass without network access to external services.
