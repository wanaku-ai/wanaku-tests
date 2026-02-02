# Data Model: HTTP Capability Tests

**Feature**: 001-http-capability-tests
**Date**: 2026-02-02

## Overview

This document defines the key entities, their fields, relationships, and lifecycle states for the HTTP Capability test framework.

## Entities

### 1. TestConfiguration

Configuration holder for the entire test framework.

| Field | Type | Description |
|-------|------|-------------|
| routerJarPath | String | Path to pre-built Router JAR |
| httpToolServiceJarPath | String | Path to pre-built HTTP Tool Service JAR |
| cliPath | String | Path to Wanaku CLI binary (optional) |
| artifactsDir | String | Directory containing pre-built artifacts |
| tempDataDir | String | Isolated temp directory for test data |
| defaultTimeout | Duration | Default timeout for health checks and waits |
| routerHttpPort | int | Dynamically allocated Router HTTP port |
| routerGrpcPort | int | Dynamically allocated Router gRPC port |
| keycloakUrl | String | Keycloak auth URL (from container) |
| keycloakRealm | String | Keycloak realm name (default: "wanaku") |
| clientId | String | OAuth client ID for test authentication |
| clientSecret | String | OAuth client secret |

**Lifecycle**: Created once per test suite, immutable after initialization.

---

### 2. KeycloakManager

Manages the Keycloak container lifecycle.

| Field | Type | Description |
|-------|------|-------------|
| container | KeycloakContainer | Testcontainers Keycloak instance |
| authUrl | String | Keycloak auth server URL |
| realm | String | Realm name |
| adminUsername | String | Admin username |
| adminPassword | String | Admin password |
| clientCredentials | Map<String, String> | Client ID -> Secret mapping |

**States**:
- `STOPPED` → `STARTING` → `RUNNING` → `STOPPING` → `STOPPED`

**Validation Rules**:
- Realm JSON must exist in classpath
- Container must be healthy before transitioning to RUNNING
- All credentials must be accessible after startup

---

### 3. RouterManager

Manages the Wanaku Router process lifecycle.

| Field | Type | Description |
|-------|------|-------------|
| process | Process | Running Router process |
| httpPort | int | Dynamically allocated HTTP port |
| grpcPort | int | Dynamically allocated gRPC port |
| dataDir | Path | Isolated data directory |
| logFile | File | Process output log file |
| healthCheckUrl | String | Health check endpoint URL |
| restartMode | boolean | Whether per-test restart is enabled |

**States**:
- `STOPPED` → `STARTING` → `HEALTHY` → `RUNNING` → `STOPPING` → `STOPPED`

**Validation Rules**:
- JAR must exist before starting
- Health check must pass within timeout
- Process must be alive throughout RUNNING state
- Graceful shutdown (SIGTERM) before forced kill

---

### 4. HttpToolServiceManager

Manages the HTTP Tool Service process lifecycle.

| Field | Type | Description |
|-------|------|-------------|
| process | Process | Running HTTP Tool Service process |
| grpcPort | int | Dynamically allocated gRPC port |
| routerGrpcHost | String | Router gRPC host for registration |
| routerGrpcPort | int | Router gRPC port for registration |
| logFile | File | Process output log file |
| suiteScoped | boolean | Whether to run in suite-scoped mode |

**States**:
- `STOPPED` → `STARTING` → `REGISTERING` → `RUNNING` → `STOPPING` → `STOPPED`

**Validation Rules**:
- JAR must exist before starting
- Must successfully register with Router (transition to RUNNING)
- Default: test-scoped lifecycle (start/stop per test)
- Optional: suite-scoped lifecycle (shared across tests)

---

### 5. MockHttpServer

Wrapper around MockWebServer for HTTP tool target endpoints.

| Field | Type | Description |
|-------|------|-------------|
| server | MockWebServer | OkHttp MockWebServer instance |
| port | int | Dynamically allocated port |
| baseUrl | String | Base URL for tool registration |
| expectedRequests | List<RecordedRequest> | Captured requests for verification |

**States**:
- `STOPPED` → `RUNNING` → `STOPPED`

**Lifecycle**: Always test-scoped.

**API Methods**:
- `enqueueResponse(int code, String body)` - Queue a response
- `enqueueError(int code, String message)` - Queue an error response
- `takeRequest()` - Get next captured request for verification
- `getUrl(String path)` - Get full URL for tool registration

---

### 6. RouterClient

REST API client for Router management operations.

| Field | Type | Description |
|-------|------|-------------|
| httpClient | HttpClient | Java HTTP client instance |
| baseUrl | String | Router REST API base URL |
| accessToken | String | OAuth access token |
| tokenExpiry | Instant | Token expiration time |

**Operations**:
- `registerTool(HttpToolConfig)` → ToolInfo
- `listTools()` → List<ToolInfo>
- `removeTool(String name)` → boolean
- `getToolInfo(String name)` → ToolInfo
- `clearAllTools()` → void

---

### 7. HttpToolConfig

Configuration for registering an HTTP tool.

| Field | Type | Description |
|-------|------|-------------|
| name | String | Tool name (unique identifier) |
| description | String | Human-readable description |
| targetUri | String | Target HTTP endpoint URL |
| method | String | HTTP method (GET, POST, etc.) |
| headers | Map<String, String> | Static headers (header.* prefix) |
| queryParams | Map<String, String> | Static query params (query.* prefix) |
| inputSchema | JsonSchema | Expected input parameters |

**Validation Rules**:
- Name must be unique per Router instance
- Target URI must be valid URL
- Method must be valid HTTP method

---

### 8. ToolInfo

Information about a registered tool (returned from Router).

| Field | Type | Description |
|-------|------|-------------|
| name | String | Tool name |
| description | String | Tool description |
| type | String | Tool type (always "http" for this module) |
| uri | String | Target endpoint URI |
| inputSchema | JsonSchema | Input parameter schema |

---

### 9. CLIExecutor

Utility for executing Wanaku CLI commands.

| Field | Type | Description |
|-------|------|-------------|
| cliPath | String | Path to CLI binary |
| workingDir | Path | Working directory for CLI execution |
| environment | Map<String, String> | Environment variables |
| timeout | Duration | Command execution timeout |

**Operations**:
- `execute(String... args)` → CLIResult
- `executeWithAuth(String token, String... args)` → CLIResult

---

### 10. CLIResult

Result of a CLI command execution.

| Field | Type | Description |
|-------|------|-------------|
| exitCode | int | Process exit code |
| stdout | String | Standard output |
| stderr | String | Standard error |
| duration | Duration | Execution time |

---

## Relationships

```
TestConfiguration
    └── used by: All managers and clients

KeycloakManager
    └── provides auth to: RouterManager, RouterClient, MCPClient

RouterManager
    └── hosts: HttpToolServiceManager (via gRPC registration)
    └── provides endpoints to: RouterClient, MCPClient

HttpToolServiceManager
    └── registers with: RouterManager
    └── invokes: MockHttpServer (when tools are called)

RouterClient
    └── uses: RouterManager (REST API)
    └── manages: HttpToolConfig → ToolInfo

MockHttpServer
    └── serves: HTTP tool invocations
    └── lifecycle: per-test

BaseIntegrationTest
    └── owns: KeycloakManager (static, suite-scoped)
    └── owns: RouterManager (static, suite-scoped with optional restart)
    └── owns: HttpToolServiceManager (instance, configurable scope)
    └── owns: MockHttpServer (instance, test-scoped)
    └── owns: RouterClient (instance)
```

## State Transitions

### Router Lifecycle (Suite-Scoped)

```
@BeforeAll
    STOPPED ──start()──> STARTING ──healthCheck()──> HEALTHY ──> RUNNING

@AfterEach (if restartMode enabled)
    RUNNING ──restart()──> STOPPING ──> STOPPED ──> STARTING ──> RUNNING

@AfterAll
    RUNNING ──stop()──> STOPPING ──> STOPPED
```

### HTTP Tool Service Lifecycle (Test-Scoped Default)

```
@BeforeEach
    STOPPED ──start()──> STARTING ──register()──> REGISTERING ──> RUNNING

@AfterEach
    RUNNING ──stop()──> STOPPING ──> STOPPED
```

### HTTP Tool Service Lifecycle (Suite-Scoped Mode)

```
@BeforeAll
    STOPPED ──start()──> STARTING ──register()──> REGISTERING ──> RUNNING

@AfterAll
    RUNNING ──stop()──> STOPPING ──> STOPPED
```
