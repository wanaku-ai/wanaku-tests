# Research: Camel Integration Capability Tests

## R1: CIC JAR Availability and Format

**Decision**: Use the fat JAR `camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar` from the `early-access` release on GitHub.

**Rationale**: The `early-access` release on `wanaku-ai/camel-integration-capability` provides two JARs:
- `camel-integration-capability-main-*-jar-with-dependencies.jar` (~56 MB) — standalone CLI, this is what we need
- `camel-integration-capability-plugin-*-shaded.jar` (~18 MB) — embeddable plugin, not relevant for testing

The main JAR is a **standard fat JAR** (maven-assembly-plugin), NOT a Quarkus fast-jar. It runs with `java -jar <name>.jar <args>`. Main class: `ai.wanaku.capability.camel.CamelToolMain`.

**Download URL**: `https://github.com/wanaku-ai/camel-integration-capability/releases/download/early-access/camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar`

**Alternatives considered**:
- Building from source: Requires wanaku-capabilities-java-sdk + camel-integration-capability repos. Too complex for test framework users.
- Plugin JAR: Designed for embedding into existing Camel apps, not standalone execution.

---

## R2: CIC Process Management Pattern

**Decision**: CamelCapabilityManager extends ProcessManager but overrides argument passing to use CLI args instead of JVM system properties.

**Rationale**: Unlike HTTP Capability and File Provider (Quarkus apps configured via `-D` system properties), CIC is a plain Camel Main + Picocli application that accepts all configuration via command-line arguments (`--name`, `--routes-ref`, etc.). The ProcessManager base class supports this via `getProcessArguments()`.

Key difference: CIC JAR is a single fat JAR file (not Quarkus fast-jar in a directory). ProcessManager needs to handle both patterns:
- Quarkus: `cd quarkus-app/ && java -jar quarkus-run.jar`
- Fat JAR: `java -jar camel-integration-capability-main-*.jar --arg1 val1 --arg2 val2`

**Alternatives considered**:
- Creating a completely new manager class without ProcessManager inheritance: Would duplicate start/stop/logging logic.
- Wrapping CIC in a shell script: Unnecessary complexity.

---

## R3: Data Store API

**Decision**: Use Router REST API (`/api/v1/data-store/add`) for uploading configs, then start CIC with `datastore://` references.

**Rationale**: The Data Store API is well-defined:

**Upload**: `POST /api/v1/data-store/add` with body:
```json
{
  "name": "routes.yaml",
  "data": "<base64-encoded-content>",
  "labels": {}
}
```

**Download**: `GET /api/v1/data-store/get?name=routes.yaml` returns DataStore object with base64-encoded data.

**CIC reference**: `--routes-ref datastore://routes.yaml` — CIC downloads the file from Data Store via the services HTTP client.

For tests, we need a DataStoreClient in test-common that can:
1. Upload files to Data Store (base64 encode + POST)
2. List/get files from Data Store
3. Remove files from Data Store (cleanup)

**Alternatives considered**:
- Using CLI (`wanaku data-store add`): Works but REST API is more programmable and doesn't require CLI binary.
- Skipping Data Store tests: Not acceptable — datastore:// is a key production pattern.

---

## R4: PostgreSQL Integration

**Decision**: Use Testcontainers `postgres:16` image with dynamic port allocation. Schema/data seeding via JDBC at startup.

**Rationale**: PostgreSQL is the primary database backend used in CIC examples (BUILD_GUIDE Steps 7, 9). Testcontainers provides reproducible setup. The PostgresServiceManager should:
1. Start container with dynamic port
2. Create test database/schema
3. Seed test data (e.g., users table)
4. Provide JDBC URL for route configuration
5. Stop container in @AfterAll

Dependencies needed in `dependencies.txt` for CIC:
```
org.postgresql:postgresql:42.7.4
io.quarkus:quarkus-agroal:3.17.5
io.agroal:agroal-pool:2.6
```

**Alternatives considered**:
- SQLite (embedded): Simpler but doesn't test real JDBC/DataSource setup. Can be added as secondary test.
- H2 in-memory: Not a Camel-supported component in the same way.
- MySQL: PostgreSQL is the documented example; use it first.

---

## R5: Configuration File Management

**Decision**: Use static fixture files in `src/test/resources/fixtures/{scenario}/` with a `TestFixtures` utility for copying and `${VAR}` placeholder substitution.

**Rationale**: Constitution Principle IV requires fixtures loaded from `src/test/resources/`. Static YAML files are easier to read, review, and maintain than Java-generated YAML strings. For dynamic values (database URLs, ports), simple `${VAR}` placeholder substitution provides sufficient flexibility without the complexity of a builder API.

Each test scenario gets its own directory with 2-3 files:
```
fixtures/simple-tool/routes.camel.yaml + rules.yaml
fixtures/postgres-tool/routes.camel.yaml + rules.yaml + dependencies.txt + seed.sql
```

`TestFixtures.load("simple-tool")` copies files to tempDir. `TestFixtures.load("postgres-tool", Map.of("JDBC_URL", url))` copies and substitutes placeholders.

**Alternatives considered**:
- Builder classes (CamelRouteBuilder, CamelRulesBuilder): More flexible but adds code complexity, YAML-in-Java is hard to read, and doesn't align with Constitution Principle IV.
- In-memory configuration: CIC requires file paths on CLI, not in-memory configs.

---

## R6: Multiple CIC Instances

**Decision**: CamelCapabilityManager supports multiple concurrent instances via unique names and dynamic port allocation.

**Rationale**: The defining CIC pattern is "one JAR, many roles." Tests must verify this by running 2+ instances simultaneously. Each instance needs:
- Unique `--name` (e.g., `test-tool-1`, `test-resource-1`)
- Unique `--grpc-port` (dynamic allocation)
- Separate config files
- Independent registration with Router

The test base class manages a list of CamelCapabilityManager instances and stops all in @AfterEach.

**Alternatives considered**:
- Sequential testing (start instance A, test, stop, start instance B): Doesn't test concurrent registration.
- Single instance with multiple routes: Doesn't test the multi-instance deployment pattern.