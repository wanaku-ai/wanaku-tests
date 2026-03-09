# Tasks: Camel Integration Capability Tests

**Input**: Design documents from `/specs/003-camel-integration-capability-tests/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Tests are the primary deliverable (this IS a test module). Each user story phase produces test classes.

**Organization**: Tasks grouped by user story. US1 (infrastructure) is foundational and blocks all other stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Project initialization, artifact download, Maven module creation

- [X] T001 Create `camel-integration-capability-tests/pom.xml` with dependencies on test-common, JUnit 5, AssertJ, Awaitility, Testcontainers (PostgreSQL)
- [X] T002 Add `camel-integration-capability-tests` as module in root `pom.xml`
- [X] T003 Update `artifacts/download.sh` to download CIC JAR — add a `download_jar` function (CIC is a single fat JAR, NOT a ZIP like other artifacts): `curl -fSL -o artifacts/camel-integration-capability/camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar` from `https://github.com/wanaku-ai/camel-integration-capability/releases/download/early-access/camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar`, creating the `camel-integration-capability/` directory first
- [X] T004 Create test package directory `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/`

---

## Phase 2: Foundational — US1: Extend Test Infrastructure (Priority: P1)

**Purpose**: Core infrastructure that MUST be complete before ANY CIC test can run

**⚠️ CRITICAL**: No CIC test can run until this phase is complete

**Goal**: Extend test-common with CamelCapabilityManager, configuration builders, services package, and DataStoreClient so CIC tests can start/stop CIC instances and generate dynamic configs.

**Independent Test**: Start Keycloak, Router, and a CIC instance with a simple direct: route; verify CIC registers with Router and gRPC port is listening.

### Infrastructure Extensions

- [X] T005 [US1] Add `PROP_CAMEL_CAPABILITY_JAR` and `ROUTER_DATA_STORE_PATH` constants to `test-common/src/main/java/ai/wanaku/test/WanakuTestConstants.java`
- [X] T006 [US1] Extend `TestConfiguration.java` with `camelCapabilityJarPath` field, builder method, and `findJar()` lookup for `camel-integration-capability` prefix in `test-common/src/main/java/ai/wanaku/test/config/TestConfiguration.java`
- [X] T007 [US1] Create `CamelCapabilityManager` extending `ProcessManager` in `test-common/src/main/java/ai/wanaku/test/managers/CamelCapabilityManager.java` — override `getJarPath()` to return fat JAR path, `getProcessArguments()` to return CLI args (`--name`, `--grpc-port`, `--routes-ref`, `--rules-ref`, `--dependencies`, `--registration-url`, `--registration-announce-address`, `--token-endpoint`, `--client-id`, `--client-secret`). **IMPORTANT**: CIC is a fat JAR (not Quarkus fast-jar) — override `start()` to use absolute JAR path without changing working directory (ProcessManager base class does `pb.directory(jarPath.getParent())` which is only needed for Quarkus fast-jar format where quarkus-run.jar expects relative lib/ paths)

### Test Fixtures

- [X] T008 [P] [US1] Create `TestFixtures` in `test-common/src/main/java/ai/wanaku/test/fixtures/TestFixtures.java` — utility class with: `load(fixtureName)` copies all files from `src/test/resources/fixtures/{fixtureName}/` to tempDir and returns Path; `load(fixtureName, Map<String, String> vars)` does the same but replaces `${VAR}` placeholders in file contents with provided values (e.g., `${JDBC_URL}` → actual PostgreSQL URL)
- [X] T009 [P] [US1] Create fixture files for simple-tool test in `camel-integration-capability-tests/src/test/resources/fixtures/simple-tool/` — `routes.camel.yaml` (direct: route returning static "Hello from CIC!") and `rules.yaml` (tool definition with name and description)
- [X] T010 [P] [US1] Create fixture files for parameterized-tool test in `camel-integration-capability-tests/src/test/resources/fixtures/parameterized-tool/` — `routes.camel.yaml` (direct: route using Simple expression `${header.Wanaku.city}`) and `rules.yaml` (tool with `city` parameter)
- [X] T010b [P] [US1] Create fixture files for explicit-mapping-tool test in `camel-integration-capability-tests/src/test/resources/fixtures/explicit-mapping-tool/` — `routes.camel.yaml` (direct: route using `${header.CUSTOM_HEADER}`) and `rules.yaml` (tool with explicit mapping.type: header, mapping.name: CUSTOM_HEADER)
- [X] T010c [P] [US1] Create fixture files for file-resource test in `camel-integration-capability-tests/src/test/resources/fixtures/file-resource/` — `routes.camel.yaml` (file-reading route with autoStartup: false, `${FILE_DIR}` and `${FILE_NAME}` placeholders) and `rules.yaml` (resource definition)
- [X] T010d [P] [US1] Create fixture files for postgres-tool test in `camel-integration-capability-tests/src/test/resources/fixtures/postgres-tool/` — `routes.camel.yaml` (JDBC route with DataSource bean using `${JDBC_URL}`, `${DB_USER}`, `${DB_PASSWORD}` placeholders), `rules.yaml` (tool definition), `dependencies.txt` (org.postgresql:postgresql:42.7.4), and `seed.sql` (CREATE TABLE + INSERT test data)
- [X] T010e [P] [US1] Create fixture files for multi-instance tests in `camel-integration-capability-tests/src/test/resources/fixtures/multi-instance-tool/` and `fixtures/multi-instance-resource/` — separate routes + rules for tool and resource instances

### External Services Package

- [X] T011 [P] [US1] Create `PostgresServiceManager` in `test-common/src/main/java/ai/wanaku/test/services/PostgresServiceManager.java` — Testcontainers-based PostgreSQL 16 manager with: `start()`, `stop()`, `getJdbcUrl()`, `getUsername()`, `getPassword()`, `executeSql(String sql)` for schema/data seeding, suite-scoped lifecycle

### Data Store Client

- [X] T012 [P] [US1] Create `DataStoreClient` in `test-common/src/main/java/ai/wanaku/test/client/DataStoreClient.java` — REST API client for Router Data Store: `upload(name, content)` (base64 encodes + POST to `/api/v1/data-store/add`), `download(name)`, `list()`, `removeByName(name)`, `clearAll()`

### Test Base Class

- [X] T013 [US1] Create `CamelCapabilityTestBase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelCapabilityTestBase.java` — extends `BaseIntegrationTest`, manages list of `CamelCapabilityManager` instances, provides `startCapability(name, routesYaml, rulesYaml)` and `startCapability(name, routesYaml, rulesYaml, dependenciesTxt)` helper methods, cleanup in `@AfterEach` stops all CIC instances and clears tools/resources, provides `getLogProfile()` returning `"camel-capability"`

**Checkpoint**: Foundation ready — CIC instances can be started/stopped, configs generated dynamically, PostgreSQL available via Testcontainers

---

## Phase 3: CamelBasicToolITCase — Simple Tools (Priority: P2) 🎯 MVP

**Goal**: Verify CIC works with simple Camel routes (direct: endpoints, no external dependencies).

**Fixture**: `simple-tool/` (basic), `parameterized-tool/`, `explicit-mapping-tool/`

**One ITCase file** — all simple tool tests. DataStore and multi-instance tests included here since they use the same fixtures.

- [X] T014 Create `CamelBasicToolITCase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelBasicToolITCase.java` — extend `CamelCapabilityTestBase`, `@BeforeEach` with `assumeThat(isRouterAvailable())` and `assumeThat(isCamelCapabilityAvailable())` and `assumeThat(isMcpClientAvailable())`
- [X] T015 Add test `shouldRegisterSimpleToolViaMcp` — load `fixtures/simple-tool/`, start CIC, list tools via MCP `toolsList()`, assert tool "simple-greeting" appears
- [X] T016 Add test `shouldInvokeSimpleToolViaMcp` — load `fixtures/simple-tool/`, start CIC, invoke via MCP `toolsCall("simple-greeting", Map.of())`, assert response contains "Hello from CIC!"
- [X] T017 Add test `shouldListToolViaRestApi` — load `fixtures/simple-tool/`, start CIC, list tools via `routerClient.listTools()`, assert tool appears
- [X] T018 Add test `shouldInvokeToolWithParameters` — load `fixtures/parameterized-tool/`, start CIC, invoke via MCP with `Map.of("city", "London")`, assert response contains "London"
- [X] T019 Add test `shouldInvokeToolWithExplicitParameterMapping` — load `fixtures/explicit-mapping-tool/`, start CIC, invoke via MCP with `Map.of("myValue", "test-value-123")`, assert response contains "test-value-123"
- [X] T020 Add test `shouldHandleMissingRequiredParameter` — load `fixtures/parameterized-tool/`, start CIC, invoke without required 'city' parameter, assert error or default response
- [X] T021 ~~shouldFailOnInvalidRouteSyntax~~ — REMOVED: tests CIC behavior, not Wanaku integration
- [X] T022 Add test `shouldRunMultipleToolsSimultaneously` — load `fixtures/simple-tool/` and `fixtures/parameterized-tool/`, start 2 CIC instances with different names and ports, invoke each via MCP, assert correct independent responses
- [X] T023 Add test `shouldLoadToolFromDataStore` — read `fixtures/simple-tool/` files, upload to Data Store via `dataStoreClient.upload()`, start CIC with `datastore://` references, invoke tool, assert same result as file:// — skip if Data Store unavailable via `assumeThat(isDataStoreAvailable())`
- [X] T024 Add test `shouldStartWithCleanState` — assert no tools from other tests are present at start (isolation verification)

**Checkpoint**: CIC basic tools work — register, list, invoke, params, multi-instance, datastore, isolation

---

## Phase 4: CamelFileResourceITCase — File Resources (Priority: P3)

**Goal**: Verify CIC can expose file-reading Camel routes as MCP resources.

**Fixture**: `file-resource/` (one fixture, `${FILE_DIR}` and `${FILE_NAME}` substituted at runtime)

- [X] T025 Create `CamelFileResourceITCase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelFileResourceITCase.java` — extend `CamelCapabilityTestBase`, `@BeforeEach` with assumeThat checks for Router, CIC, MCP
- [X] T026 Add test `shouldListFileResourceViaMcp` — create test file, load `fixtures/file-resource/` with `${FILE_DIR}` and `${FILE_NAME}` substituted, start CIC, list resources via MCP, assert "test-file-resource" appears
- [X] T027 Add test `shouldReadFileResourceViaMcp` — same setup, read resource via MCP `resourcesRead()`, assert content matches source file text
- [X] T028 Add test `shouldListFileResourceViaRestApi` — same setup, list resources via `routerClient.listResources()`, assert resource appears
- [X] T029 Add test `shouldHandleNonExistentFileResource` — load fixture with `${FILE_DIR}` pointing to non-existent path, read via MCP, assert error or empty response
- [X] T030 Add test `shouldRunToolAndResourceSimultaneously` — start CIC instance A with `fixtures/simple-tool/` (tool), start CIC instance B with `fixtures/file-resource/` (resource), list tools + resources via MCP, invoke tool and read resource independently, assert both work
- [X] T031 Add test `shouldLoadFileResourceFromDataStore` — upload `fixtures/file-resource/` to Data Store, start CIC with `datastore://`, read resource, assert same result — skip if Data Store unavailable
- [X] T032 Add test `shouldStartWithCleanResourceState` — assert no resources from other tests are present (isolation verification)

**Checkpoint**: CIC file resources work — expose, list, read, multi-instance with tool, datastore, isolation

---

## Phase 5: CamelPostgresToolITCase — PostgreSQL Database Tool (Priority: P4)

**Goal**: Verify CIC works with JDBC Camel routes, dynamic dependency loading, and PostgreSQL via Testcontainers.

**Fixture**: `postgres-tool/` (one fixture, query parameterized via `${header.Wanaku.query}`, DB connection via `${JDBC_URL}` / `${DB_USER}` / `${DB_PASSWORD}`)

- [X] T033 Create `CamelPostgresToolITCase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelPostgresToolITCase.java` — extend `CamelCapabilityTestBase`, `@BeforeAll` starts `PostgresServiceManager` with postgres:16, seeds database using `fixtures/postgres-tool/seed.sql`, `@AfterAll` stops PostgreSQL
- [X] T034 Add test `shouldQueryDatabaseViaTool` — load `fixtures/postgres-tool/` with DB vars substituted, start CIC with routes + rules + dependencies.txt, invoke tool via MCP with `Map.of("query", "SELECT * FROM users ORDER BY id")`, assert response contains "Alice" and "Bob"
- [X] T035 Add test `shouldHandleDatabaseError` — same fixture, invoke tool with `Map.of("query", "SELECT * FROM nonexistent_table")`, assert error response
- [X] T036 Add test `shouldLoadPostgresToolFromDataStore` — upload `fixtures/postgres-tool/` to Data Store (with DB vars resolved), start CIC with `datastore://`, invoke tool, assert same result — skip if Data Store unavailable

**Checkpoint**: CIC database tools work — PostgreSQL, JDBC, dynamic deps, error handling, datastore

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, cleanup, validation

- [X] T037 [P] Update root `README.md` to add `camel-integration-capability-tests` module to project structure and modules section
- [X] T038 [P] Create `camel-integration-capability-tests/README.md` with test class table, prerequisites, and architecture diagram (following pattern from `http-capability-tests/README.md`)
- [X] T039 Run full test suite (`mvn clean install`) to verify all modules work together without interference
- [X] T040 Validate quickstart.md examples against actual implementation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately ✅
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all ITCase files ✅
- **CamelBasicToolITCase (Phase 3)**: Depends on Phase 2 — MVP, start here first
- **CamelFileResourceITCase (Phase 4)**: Depends on Phase 2 — can parallel with Phase 3
- **CamelPostgresToolITCase (Phase 5)**: Depends on Phase 2 — can parallel with Phase 3/4
- **Polish (Phase 6)**: Depends on all ITCase files being complete

### Architecture: One Camel backend = One fixture = One ITCase

- Each ITCase covers ALL aspects of its backend: MCP, REST API, DataStore, multi-instance, isolation
- DataStore tests are inside each ITCase (not separate) — skipped if Data Store unavailable
- Multi-instance tests are inside the relevant ITCase
- Error cases use the same fixture with different parameters (not separate fixtures)

### Parallel Opportunities

- Phase 2: T008-T012 can all run in parallel (different files)
- After Phase 2: All three ITCase files can be developed in parallel
- T037, T038 can run in parallel (different files)

---

## Implementation Strategy

### MVP First

1. Complete Phase 1: Setup (T001-T004) ✅
2. Complete Phase 2: Foundational (T005-T013) ✅
3. Complete Phase 3: CamelBasicToolITCase (T014-T024) — 11 tests
4. **STOP and VALIDATE**: Run `mvn clean install -pl camel-integration-capability-tests`

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready ✅
2. CamelBasicToolITCase → 11 tests → **MVP!**
3. CamelFileResourceITCase → +8 tests → 19 total
4. CamelPostgresToolITCase → +4 tests → 23 total
5. Polish → README, docs → Complete

---

## Notes

- CIC JAR is a fat JAR (not Quarkus fast-jar) — different process launch pattern
- CIC uses CLI args (not system properties) — CamelCapabilityManager must handle this
- One Camel backend = one fixture directory = one ITCase file
- Different scenarios within same backend → different parameters, NOT different fixtures
- TestFixtures copies fixtures to tempDir with optional `${VAR}` substitution for dynamic values
- PostgreSQL is suite-scoped (shared across tests in CamelPostgresToolITCase)
- Data Store tests are inside each ITCase, skipped if Data Store unavailable
- Multiple CIC instances need unique names and gRPC ports