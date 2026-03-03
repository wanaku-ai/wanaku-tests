# Data Model: Camel Integration Capability Tests

## New Entities

### CamelCapabilityConfig

Configuration for starting a CIC instance. Passed to CamelCapabilityManager.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | String | Yes | Service name for registration (e.g., `test-db-tool`) |
| routesYamlPath | Path | Yes | Absolute path to routes YAML file |
| rulesYamlPath | Path | No | Absolute path to rules YAML file |
| dependenciesPath | Path | No | Absolute path to dependencies.txt file |
| grpcPort | int | Yes (auto) | gRPC server port (dynamically allocated) |
| dataDir | Path | No | Directory for downloaded files (default: temp dir) |

### TestFixtures

Utility class for loading test fixture directories from `src/test/resources/fixtures/`.

| Method | Parameters | Returns | Description |
|--------|-----------|---------|-------------|
| `load` | `String fixtureName` | `Path` | Copies all files from `fixtures/{fixtureName}/` to tempDir |
| `load` | `String fixtureName, Map<String, String> vars` | `Path` | Same, but replaces `${VAR}` placeholders in file contents |

### Fixture Directory Structure (conceptual)

Each fixture directory contains 2-3 static YAML/TXT files:

| File | Format | Purpose |
|------|--------|---------|
| `routes.camel.yaml` | Apache Camel YAML DSL | Route definitions (direct:, file:, jdbc:) |
| `rules.yaml` | CIC rules YAML | MCP tool/resource definitions |
| `dependencies.txt` | Maven coordinates | Optional external dependencies |
| `seed.sql` | SQL | Optional database schema + test data |

Placeholder substitution in fixture files:

| Placeholder | Resolved From | Used In |
|-------------|--------------|---------|
| `${JDBC_URL}` | PostgresServiceManager.getJdbcUrl() | postgres-tool/routes.camel.yaml |
| `${DB_USER}` | PostgresServiceManager.getUsername() | postgres-tool/routes.camel.yaml |
| `${DB_PASSWORD}` | PostgresServiceManager.getPassword() | postgres-tool/routes.camel.yaml |
| `${FILE_DIR}` | tempDataDir path | file-resource/routes.camel.yaml |
| `${FILE_NAME}` | test file name | file-resource/routes.camel.yaml |

### DataStoreEntry

Represents a file in the Wanaku Data Store. Used by DataStoreClient.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | String | No (auto) | Auto-generated UUID |
| name | String | Yes | File name (e.g., `routes.yaml`) |
| data | String | Yes | Base64-encoded file content |
| labels | Map<String, String> | No | Key-value labels for filtering |

## Existing Entities (Extended)

### TestConfiguration

Extended with CIC JAR path.

| New Field | Type | Description |
|-----------|------|-------------|
| camelCapabilityJarPath | Path | Path to CIC fat JAR |

### WanakuTestConstants

Extended with CIC constants.

| New Constant | Value | Description |
|--------------|-------|-------------|
| PROP_CAMEL_CAPABILITY_JAR | `wanaku.test.camel-capability.jar` | System property for CIC JAR |
| ROUTER_DATA_STORE_PATH | `/api/v1/data-store` | Data Store REST API base path |

## Relationships

```
TestConfiguration
  ├── routerJarPath → RouterManager
  ├── httpToolServiceJarPath → HttpCapabilityManager
  ├── fileProviderJarPath → ResourceProviderManager
  └── camelCapabilityJarPath → CamelCapabilityManager  ← NEW

CamelCapabilityManager
  ├── uses CamelCapabilityConfig
  ├── reads CamelRouteDefinition (from YAML file)
  ├── reads CamelToolRule / CamelResourceRule (from YAML file)
  └── registers with Router via gRPC

TestFixtures.load("simple-tool") → copies fixtures/ → tempDir/routes.camel.yaml, rules.yaml
TestFixtures.load("postgres-tool", vars) → copies + substitutes ${VAR} → tempDir/

DataStoreClient → uploads/downloads → Router Data Store API
CamelCapabilityManager → downloads from → Data Store (via datastore:// scheme)

PostgresServiceManager (Testcontainers)
  └── provides JDBC URL → used in CamelRouteDefinition beans
```