# Wanaku HTTP Capability Integration Tests

This module contains integration tests for the Wanaku HTTP Capability Service.

## Test Classes

### HttpToolRegistrationITCase
Tests for HTTP tool registration via REST API. Verifies tools can be registered with Router and listed via MCP protocol.

### HttpToolCliITCase
Tests for HTTP tool invocation via CLI. Verifies tools can be invoked using the Wanaku CLI.

### PublicApiITCase
Tests for the public API endpoints of the HTTP Capability Service.

### RouterReconnectionITCase
**Integration test to verify that capabilities reconnect to the Wanaku router after the router is restarted.**

This test validates the reconnection resilience of the Wanaku capability system by performing the following steps:

1. **Start Wanaku router** - The router is started as part of the test suite setup
2. **Start http-capability** - HTTP capability is started and registers with the router
3. **Start resource provider** - File resource provider is started and registers with the router
4. **Start camel-integration-capability** - Camel capability is started with a simple tool configuration
5. **Verify initial state** - All capabilities are registered and tools/resources are available
6. **Stop Wanaku router** - The router process is stopped
7. **Start Wanaku router again** - The router is restarted with new ports
8. **Verify reconnection** - All capabilities automatically reconnect to the new router instance

#### Expected Behavior

All tools and resources should automatically reconnect to the router after it restarts, without requiring manual intervention. The test verifies:

- HTTP capability reconnects and re-registers
- Resource provider reconnects and re-registers
- Camel capability reconnects and re-registers
- All previously registered tools persist and are available after reconnection
- All previously registered resources persist and are available after reconnection

#### Test Scope

This test exercises the full stack:
- Router lifecycle management
- HTTP capability reconnection logic
- Resource provider reconnection logic
- Camel integration capability reconnection logic
- Tool and resource persistence across router restarts

#### Prerequisites

The test requires all component JARs to be available:
- `wanaku-router.jar` (or Quarkus app directory)
- `http-capability.jar` (or Quarkus app directory)
- `file-provider.jar` (or Quarkus app directory)
- `camel-integration-capability.jar` (fat JAR)

## Running the Tests

```bash
# Run all HTTP capability tests
mvn test -pl http-capability-tests

# Run only the reconnection test
mvn test -pl http-capability-tests -Dtest=RouterReconnectionITCase

# Run with artifacts downloaded
./artifacts/download.sh
mvn test -pl http-capability-tests
```

## Test Infrastructure

Tests extend `HttpCapabilityTestBase` which provides:
- Automatic Router startup/shutdown (suite-scoped)
- Automatic HTTP Capability startup/shutdown (test-scoped)
- Automatic Keycloak startup/shutdown for authentication (suite-scoped)
- MCP client for protocol testing
- Router REST API client for management operations

The `RouterReconnectionITCase` additionally manages:
- Resource provider lifecycle
- Camel capability lifecycle
- Router restart and reconnection verification
