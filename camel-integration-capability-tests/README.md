# Camel Integration Capability Tests

Integration tests for the [Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability) (CIC) — a universal Camel-based capability runner that exposes Apache Camel routes as MCP tools and resources.

## Prerequisites

Required artifacts are downloaded via:

```bash
cd ..
./artifacts/download.sh
```

Docker is required for Keycloak (all tests) and PostgreSQL (database tests).

## Run Tests

```bash
# All CIC tests
mvn clean install -pl camel-integration-capability-tests

# Specific test class
mvn clean install -pl camel-integration-capability-tests -Dtest=CamelBasicToolITCase

# Single test
mvn clean install -pl camel-integration-capability-tests -Dtest=CamelBasicToolITCase#shouldInvokeSimpleToolViaMcp
```

## Test Classes

| Class | Tests | Fixture | Description |
|-------|-------|---------|-------------|
| `CamelBasicToolITCase` | 6 | simple-tool/ | Register, invoke, params, explicit mapping, DataStore |
| `CamelFileResourceITCase` | 5 | file-resource/ | List, read, non-existent file, resource switching, DataStore |
| `CamelPostgresToolITCase` | 3 | postgres-tool/ | JDBC query, error handling, DataStore (requires PostgreSQL) |
| `CamelMultiInstanceITCase` | 2 | cross-fixture | Multiple CIC instances running simultaneously |

**Total: 16 tests**

## Architecture

```
┌─────────────┐     ┌──────────┐     ┌─────────────────────────────┐
│  Keycloak   │────▶│  Router  │◀────│  CIC Instance(s)            │
│  (Auth)     │     │  (MCP)   │     │  (Camel routes as tools/    │
└─────────────┘     └──────────┘     │   resources via gRPC)       │
                         │           └─────────────────────────────┘
                         │                       │
                    Test Framework          ┌────┴────┐
                                           │PostgreSQL│ (for DB tests)
                                           └─────────┘
```

**Lifecycle:**
- Suite-scoped: Keycloak, Router, PostgreSQL (shared across tests in a class)
- Test-scoped: CIC instances (fresh per test, stopped in @AfterEach)

## Fixtures

Each test scenario has a fixture directory under `src/test/resources/fixtures/`:

```
fixtures/
├── simple-tool/            # 3 routes + 3 tools in one CIC instance
│   ├── routes.camel.yaml   # direct: routes (greeting, weather, explicit mapping)
│   └── rules.yaml          # MCP tool definitions with parameter mappings
├── file-resource/           # File-reading route as MCP resource
│   ├── routes.camel.yaml   # file: route with ${FILE_DIR}/${FILE_NAME} placeholders
│   └── rules.yaml          # MCP resource definition
├── postgres-tool/           # PostgreSQL JDBC route
│   ├── routes.camel.yaml   # JDBC route with ${JDBC_URL}/${DB_USER}/${DB_PASSWORD}
│   ├── rules.yaml          # MCP tool with parameterized query
│   ├── dependencies.txt    # org.postgresql:postgresql:42.7.4
│   └── seed.sql            # CREATE TABLE + test data
└── multi-instance-tool/     # Tool for multi-instance test
```

Placeholders (`${VAR}`) are substituted at runtime by `TestFixtures.load()`.
