# Resources Tests

Integration tests for Wanaku file resource provider capability.

## Prerequisites

File provider JAR is required. Download via:

```bash
cd ..
./artifacts/download.sh 0.1.0
```

This downloads `wanaku-provider-file` from [wanaku-examples releases](https://github.com/wanaku-ai/wanaku-examples/releases).

## Run Tests

```bash
# All resource tests
mvn clean install -pl resources-tests

# Specific test class
mvn clean install -pl resources-tests -Dtest=RestApiResourceITCase

# Single test
mvn clean install -pl resources-tests -Dtest=McpResourceITCase#shouldReadTextFileViaMcp
```

## Test Classes

| Class | Tests | Description |
|-------|-------|-------------|
| `RestApiResourceITCase` | 6 | Expose, list, remove resources via REST API |
| `McpResourceITCase` | 3 | List and read resources via MCP protocol |
| `CliResourceITCase` | 3 | Expose, list, and remove resources via CLI |

**Total: 12 tests**

## Architecture

```
┌─────────────┐     ┌──────────┐     ┌───────────────────┐
│  Keycloak   │────▶│  Router  │◀────│  File Provider    │
│  (Auth)     │     │  (MCP)   │     │  (Resource Svc)   │
└─────────────┘     └──────────┘     └───────────────────┘
       ▲                 ▲                    ▲
       │                 │                    │
       └─────────────────┴────────────────────┘
                         │
                    Test Framework
                    (ResourceTestBase)
```

**Lifecycle:**
- Suite-scoped: Keycloak, Router, File Provider (shared across tests in a class)
- Test-scoped: MCP client (fresh per test)