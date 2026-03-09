# HTTP Capability Tests

Integration tests for Wanaku HTTP Tool capability.

## Prerequisites

Required artifacts are downloaded via:

```bash
cd ..
./artifacts/download.sh
```

This downloads Router, HTTP Tool Service, and CLI from [wanaku releases](https://github.com/wanaku-ai/wanaku/releases/tag/early-access).

## Run Tests

```bash
# All HTTP capability tests
mvn clean install -pl http-capability-tests

# Specific test class
mvn clean install -pl http-capability-tests -Dtest=HttpToolCliITCase

# Single test
mvn clean install -pl http-capability-tests -Dtest=HttpToolCliITCase#shouldRegisterHttpToolViaCli
```

## Test Classes

| Class | Tests | Description |
|-------|-------|-------------|
| `HttpToolCliITCase` | 3 | CLI tool registration, listing, and removal |
| `HttpToolRegistrationITCase` | 9 | REST API tool CRUD operations |
| `PublicApiITCase` | 6 | External API invocations (httpbin, jsonplaceholder, meowfacts) |

**Total: 18 tests**

## Architecture

```
┌─────────────┐     ┌──────────┐     ┌───────────────────┐
│  Keycloak   │────▶│  Router  │◀────│  HTTP Capability  │
│  (Auth)     │     │  (MCP)   │     │  (Tool Service)   │
└─────────────┘     └──────────┘     └───────────────────┘
       ▲                 ▲                    ▲
       │                 │                    │
       └─────────────────┴────────────────────┘
                         │
                    Test Framework
                    (BaseIntegrationTest)
```

**Lifecycle:**
- Suite-scoped: Keycloak, Router (shared across tests in a class)
- Test-scoped: HTTP Capability (fresh per test)

## Log Structure

```
logs/
├── test-framework.log
├── router/
│   ├── wanaku-router-HttpToolCliITCase-2026-02-05_15-35-09.log
│   └── wanaku-router-HttpToolRegistrationITCase-2026-02-05_15-36-01.log
└── http-capability/
    ├── HttpToolCliITCase/
    │   ├── shouldRegisterHttpToolViaCli-2026-02-05_15-35-12.log
    │   └── shouldListToolsViaCli-2026-02-05_15-35-18.log
    └── HttpToolRegistrationITCase/
        └── shouldRegisterHttpToolViaRestApi-2026-02-05_15-36-05.log
```

## Known Limitations

- **CLI stdout capture**: JLine requires TTY. Tests verify CLI results via REST API instead of stdout.
