# Wanaku Cross-Capability Tests

Integration tests for scenarios that exercise more than one Wanaku capability at a time.

## Prerequisites

Required artifacts are downloaded via:

```bash
cd ..
./artifacts/download.sh
```

Docker is required for Keycloak because the test suite uses the router with authentication enabled when available.

## Run Tests

```bash
# All cross-capability tests
mvn clean install -pl cross-capability-tests

# Specific test class
mvn clean install -pl cross-capability-tests -Dtest=RouterReconnectionITCase
```

## Test Classes

| Class | Description |
|-------|-------------|
| `RouterReconnectionITCase` | Starts HTTP, file provider, and Camel capabilities, restarts the router, and verifies all capabilities reconnect |

## Architecture

```
┌─────────────┐     ┌──────────┐     ┌──────────────────────┐
│  Keycloak   │────▶│  Router  │◀────│  HTTP Capability     │
│  (Auth)     │     │  (MCP)   │     └──────────────────────┘
└─────────────┘     └──────────┘     ┌──────────────────────┐
                         ▲           │ File Provider        │
                         │           └──────────────────────┘
                         │           ┌──────────────────────┐
                         └──────────▶│ Camel Capability     │
                                     └──────────────────────┘
```

**Lifecycle:**
- Suite-scoped: Keycloak, Router
- Test-scoped: HTTP capability, file provider, Camel capability

## Fixtures

This module currently uses a single Camel fixture copied locally under `src/test/resources/fixtures/`:

- `simple-tool/` - direct routes and MCP tool rules used for router reconnection verification
