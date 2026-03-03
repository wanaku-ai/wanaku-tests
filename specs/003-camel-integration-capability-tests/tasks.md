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

- [ ] T001 Create `camel-integration-capability-tests/pom.xml` with dependencies on test-common, JUnit 5, AssertJ, Awaitility, Testcontainers (PostgreSQL)
- [ ] T002 Add `camel-integration-capability-tests` as module in root `pom.xml`
- [ ] T003 Update `artifacts/download.sh` to download CIC JAR — add a `download_jar` function (CIC is a single fat JAR, NOT a ZIP like other artifacts): `curl -fSL -o artifacts/camel-integration-capability/camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar` from `https://github.com/wanaku-ai/camel-integration-capability/releases/download/early-access/camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar`, creating the `camel-integration-capability/` directory first
- [ ] T004 Create test package directory `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/`

---

## Phase 2: Foundational — US1: Extend Test Infrastructure (Priority: P1)

**Purpose**: Core infrastructure that MUST be complete before ANY CIC test can run

**⚠️ CRITICAL**: No CIC test can run until this phase is complete

**Goal**: Extend test-common with CamelCapabilityManager, configuration builders, services package, and DataStoreClient so CIC tests can start/stop CIC instances and generate dynamic configs.

**Independent Test**: Start Keycloak, Router, and a CIC instance with a simple direct: route; verify CIC registers with Router and gRPC port is listening.

### Infrastructure Extensions

- [ ] T005 [US1] Add `PROP_CAMEL_CAPABILITY_JAR` and `ROUTER_DATA_STORE_PATH` constants to `test-common/src/main/java/ai/wanaku/test/WanakuTestConstants.java`
- [ ] T006 [US1] Extend `TestConfiguration.java` with `camelCapabilityJarPath` field, builder method, and `findJar()` lookup for `camel-integration-capability` prefix in `test-common/src/main/java/ai/wanaku/test/config/TestConfiguration.java`
- [ ] T007 [US1] Create `CamelCapabilityManager` extending `ProcessManager` in `test-common/src/main/java/ai/wanaku/test/managers/CamelCapabilityManager.java` — override `getJarPath()` to return fat JAR path, `getProcessArguments()` to return CLI args (`--name`, `--grpc-port`, `--routes-ref`, `--rules-ref`, `--dependencies`, `--registration-url`, `--registration-announce-address`, `--token-endpoint`, `--client-id`, `--client-secret`). **IMPORTANT**: CIC is a fat JAR (not Quarkus fast-jar) — override `start()` to use absolute JAR path without changing working directory (ProcessManager base class does `pb.directory(jarPath.getParent())` which is only needed for Quarkus fast-jar format where quarkus-run.jar expects relative lib/ paths)

### Test Fixtures

- [ ] T008 [P] [US1] Create `TestFixtures` in `test-common/src/main/java/ai/wanaku/test/fixtures/TestFixtures.java` — utility class with: `load(fixtureName)` copies all files from `src/test/resources/fixtures/{fixtureName}/` to tempDir and returns Path; `load(fixtureName, Map<String, String> vars)` does the same but replaces `${VAR}` placeholders in file contents with provided values (e.g., `${JDBC_URL}` → actual PostgreSQL URL)
- [ ] T009 [P] [US1] Create fixture files for simple-tool test in `camel-integration-capability-tests/src/test/resources/fixtures/simple-tool/` — `routes.camel.yaml` (direct: route returning static "Hello from CIC!") and `rules.yaml` (tool definition with name and description)
- [ ] T010 [P] [US1] Create fixture files for parameterized-tool test in `camel-integration-capability-tests/src/test/resources/fixtures/parameterized-tool/` — `routes.camel.yaml` (direct: route using Simple expression `${header.Wanaku.city}`) and `rules.yaml` (tool with `city` parameter)
- [ ] T010b [P] [US1] Create fixture files for explicit-mapping-tool test in `camel-integration-capability-tests/src/test/resources/fixtures/explicit-mapping-tool/` — `routes.camel.yaml` (direct: route using `${header.CUSTOM_HEADER}`) and `rules.yaml` (tool with explicit mapping.type: header, mapping.name: CUSTOM_HEADER)
- [ ] T010c [P] [US1] Create fixture files for file-resource test in `camel-integration-capability-tests/src/test/resources/fixtures/file-resource/` — `routes.camel.yaml` (file-reading route with autoStartup: false, `${FILE_DIR}` and `${FILE_NAME}` placeholders) and `rules.yaml` (resource definition)
- [ ] T010d [P] [US1] Create fixture files for postgres-tool test in `camel-integration-capability-tests/src/test/resources/fixtures/postgres-tool/` — `routes.camel.yaml` (JDBC route with DataSource bean using `${JDBC_URL}`, `${DB_USER}`, `${DB_PASSWORD}` placeholders), `rules.yaml` (tool definition), `dependencies.txt` (org.postgresql:postgresql:42.7.4), and `seed.sql` (CREATE TABLE + INSERT test data)
- [ ] T010e [P] [US1] Create fixture files for multi-instance tests in `camel-integration-capability-tests/src/test/resources/fixtures/multi-instance-tool/` and `fixtures/multi-instance-resource/` — separate routes + rules for tool and resource instances

### External Services Package

- [ ] T011 [P] [US1] Create `PostgresServiceManager` in `test-common/src/main/java/ai/wanaku/test/services/PostgresServiceManager.java` — Testcontainers-based PostgreSQL 16 manager with: `start()`, `stop()`, `getJdbcUrl()`, `getUsername()`, `getPassword()`, `executeSql(String sql)` for schema/data seeding, suite-scoped lifecycle

### Data Store Client

- [ ] T012 [P] [US1] Create `DataStoreClient` in `test-common/src/main/java/ai/wanaku/test/client/DataStoreClient.java` — REST API client for Router Data Store: `upload(name, content)` (base64 encodes + POST to `/api/v1/data-store/add`), `download(name)`, `list()`, `removeByName(name)`, `clearAll()`

### Test Base Class

- [ ] T013 [US1] Create `CamelCapabilityTestBase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelCapabilityTestBase.java` — extends `BaseIntegrationTest`, manages list of `CamelCapabilityManager` instances, provides `startCapability(name, routesYaml, rulesYaml)` and `startCapability(name, routesYaml, rulesYaml, dependenciesTxt)` helper methods, cleanup in `@AfterEach` stops all CIC instances and clears tools/resources, provides `getLogProfile()` returning `"camel-capability"`

**Checkpoint**: Foundation ready — CIC instances can be started/stopped, configs generated dynamically, PostgreSQL available via Testcontainers

---

## Phase 3: User Story 2 — CIC Tool Registration & Invocation (Priority: P2) 🎯 MVP

**Goal**: Verify CIC can expose Camel routes as MCP tools that can be listed and invoked.

**Independent Test**: Start CIC with a simple direct: route as a tool, list tools via MCP, invoke tool via MCP, verify response.

- [ ] T014 [US2] Create `CamelToolITCase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelToolITCase.java` with test: `shouldRegisterSimpleToolViaMcp` — load `fixtures/simple-tool/`, start CIC, list tools via MCP, assert tool appears with correct name and description
- [ ] T015 [US2] Add test `shouldInvokeSimpleToolViaMcp` to `CamelToolITCase.java` — load `fixtures/simple-tool/`, start CIC, invoke tool via MCP `toolsCall()`, assert response contains "Hello from CIC!"
- [ ] T016 [US2] Add test `shouldListToolViaRestApi` to `CamelToolITCase.java` — load `fixtures/simple-tool/`, start CIC, list tools via `routerClient.listTools()`, assert CIC tool appears
- [ ] T017 [US2] Add test `shouldInvokeToolWithParameters` to `CamelToolITCase.java` — load `fixtures/parameterized-tool/`, start CIC, invoke via MCP with `Map.of("city", "London")`, assert parameter is resolved in response
- [ ] T018 [US2] Add test `shouldInvokeToolWithExplicitParameterMapping` to `CamelToolITCase.java` — load `fixtures/explicit-mapping-tool/`, start CIC, invoke via MCP, assert parameter mapped to correct header name
- [ ] T019 [US2] Add test `shouldHandleMissingRequiredParameter` to `CamelToolITCase.java` — load `fixtures/parameterized-tool/` (has required parameter), invoke tool without it, assert error response
- [ ] T019b [US2] Add test `shouldFailOnInvalidRouteSyntax` to `CamelToolITCase.java` — create malformed routes YAML in tempDir (inline, not fixture), start CIC, assert CIC fails to start and CamelCapabilityManager reports the failure clearly

**Checkpoint**: CIC tools work end-to-end — register, list, invoke with parameters, error handling

---

## Phase 4: User Story 3 — CIC Resource Exposure & Reading (Priority: P3)

**Goal**: Verify CIC can expose Camel file-reading routes as MCP resources that can be listed and read.

**Independent Test**: Start CIC with a file-reading route as a resource, list resources via MCP, read resource content, verify it matches the source file.

- [ ] T020 [US3] Create `CamelResourceITCase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelResourceITCase.java` with test: `shouldListResourceViaMcp` — create test file, load `fixtures/file-resource/` with `${FILE_DIR}` and `${FILE_NAME}` substituted, start CIC, list resources via MCP, assert resource appears with correct name
- [ ] T021 [US3] Add test `shouldReadFileResourceViaMcp` to `CamelResourceITCase.java` — same fixture setup, read resource via MCP `resourcesRead()`, assert content matches source file text
- [ ] T022 [US3] Add test `shouldListResourceViaRestApi` to `CamelResourceITCase.java` — same fixture setup, list resources via `routerClient.listResources()`, assert CIC resource appears
- [ ] T023 [US3] Add test `shouldHandleNonExistentFileResource` to `CamelResourceITCase.java` — load `fixtures/file-resource/` with `${FILE_DIR}` pointing to non-existent path, attempt to read via MCP, assert error or empty response

**Checkpoint**: CIC resources work end-to-end — expose, list, read content, error handling

---

## Phase 5: User Story 4 — Database Tool via CIC (Priority: P4)

**Goal**: Verify CIC can expose database queries as MCP tools using JDBC Camel routes with dynamic dependency loading.

**Independent Test**: Start PostgreSQL via Testcontainers, start CIC with JDBC route + dependencies.txt, invoke tool, verify query results.

- [ ] T024 [US4] Create `CamelDatabaseToolITCase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelDatabaseToolITCase.java` — extend `CamelCapabilityTestBase`, add `@BeforeAll` to start `PostgresServiceManager` with test schema (`CREATE TABLE users (id SERIAL, name TEXT, email TEXT)`) and seed data (`INSERT INTO users VALUES (1, 'Alice', 'alice@test.com'), (2, 'Bob', 'bob@test.com')`)
- [ ] T025 [US4] Add test `shouldQueryDatabaseViaTool` to `CamelDatabaseToolITCase.java` — load `fixtures/postgres-tool/` with `${JDBC_URL}`, `${DB_USER}`, `${DB_PASSWORD}` substituted from PostgresServiceManager, start CIC with routes + rules + dependencies.txt, invoke tool via MCP, assert response contains "Alice" and "Bob"
- [ ] T026 [US4] Add test `shouldHandleDatabaseError` to `CamelDatabaseToolITCase.java` — load `fixtures/postgres-tool/` but with modified route (setBody to query non-existent table), invoke tool, assert error response

**Checkpoint**: Database tools work end-to-end — PostgreSQL via Testcontainers, dynamic dependency loading, query execution, error handling

---

## Phase 6: User Story 5 — Data Store Configuration Loading (Priority: P5)

**Goal**: Verify CIC can load routes/rules from Wanaku Data Store via `datastore://` scheme.

**Independent Test**: Upload routes+rules YAML to Data Store via REST API, start CIC with `datastore://` references, invoke tool/read resource, verify it works identically to `file://`.

- [ ] T027 [US5] Create `CamelDataStoreITCase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelDataStoreITCase.java` — extend `CamelCapabilityTestBase`, add `@BeforeEach` with `assumeThat(isDataStoreAvailable())` precondition check (verify Data Store API responds at `/api/v1/data-store/list`), add `@AfterEach` to clean Data Store entries via `dataStoreClient.clearAll()`
- [ ] T028 [US5] Add test `shouldLoadToolFromDataStore` to `CamelDataStoreITCase.java` — read fixture files from `fixtures/simple-tool/`, upload their content to Data Store via `dataStoreClient.upload()`, start CIC with `--routes-ref datastore://routes.camel.yaml --rules-ref datastore://rules.yaml`, invoke tool via MCP, assert response matches expected output
- [ ] T029 [US5] Add test `shouldLoadResourceFromDataStore` to `CamelDataStoreITCase.java` — read fixture files from `fixtures/file-resource/` (with placeholders resolved), upload to Data Store, start CIC with `datastore://` references, read resource via MCP, assert content matches

**Checkpoint**: Data Store loading works — upload config → CIC downloads via datastore:// → capability functions correctly

---

## Phase 7: User Story 6 — Multiple CIC Instances (Priority: P6)

**Goal**: Verify multiple CIC instances can run simultaneously as different capabilities.

**Independent Test**: Start 2 CIC instances (one tool, one resource) on different ports, verify both register, invoke/read from each independently.

- [ ] T030 [US6] Create `CamelMultiInstanceITCase` in `camel-integration-capability-tests/src/test/java/ai/wanaku/test/camel/CamelMultiInstanceITCase.java` with test: `shouldRunToolAndResourceSimultaneously` — load `fixtures/multi-instance-tool/` and `fixtures/multi-instance-resource/`, start CIC instance A as tool (dynamic port, name "test-tool-svc"), start CIC instance B as resource (dynamic port, name "test-resource-svc"), list tools and resources via MCP, invoke tool and read resource independently, assert both return correct results
- [ ] T031 [US6] Add test `shouldRunMultipleToolsSimultaneously` to `CamelMultiInstanceITCase.java` — load `fixtures/simple-tool/` and `fixtures/parameterized-tool/`, start 2 CIC instances as tools with different names, invoke each via MCP, assert correct and independent responses

**Checkpoint**: Multi-instance pattern works — one JAR, many roles, no interference

---

## Phase 8: User Story 7 — Test Isolation (Priority: P7)

**Goal**: Verify CIC tests are properly isolated.

**Independent Test**: Run tests that register different capabilities, verify no leakage between tests.

- [ ] T032 [US7] Add isolation verification to `CamelToolITCase.java` — add test `shouldStartWithCleanState` that asserts no tools from other tests are present at the start of this test (listTools returns only tools registered in this test)
- [ ] T033 [US7] Add isolation verification to `CamelResourceITCase.java` — add test `shouldStartWithCleanResourceState` that asserts no resources from other tests are present

**Checkpoint**: Isolation verified — no tool/resource leakage between tests

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, cleanup, validation

- [ ] T034 [P] Update root `README.md` to add `camel-integration-capability-tests` module to project structure and modules section
- [ ] T035 [P] Create `camel-integration-capability-tests/README.md` with test class table, prerequisites, and architecture diagram (following pattern from `http-capability-tests/README.md`)
- [ ] T036 Run full test suite (`mvn clean install`) to verify all modules work together without interference
- [ ] T037 Validate quickstart.md examples against actual implementation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **US2 Tools (Phase 3)**: Depends on Phase 2 — MVP, start here first
- **US3 Resources (Phase 4)**: Depends on Phase 2 — can parallel with US2
- **US4 Database (Phase 5)**: Depends on Phase 2 — can parallel with US2/US3
- **US5 Data Store (Phase 6)**: Depends on Phase 2 — can parallel with US2/US3/US4
- **US6 Multi-Instance (Phase 7)**: Depends on Phase 2 — best after US2+US3 are validated
- **US7 Isolation (Phase 8)**: Depends on US2+US3 (adds tests to those classes)
- **Polish (Phase 9)**: Depends on all desired user stories being complete

### User Story Dependencies

- **US1 (P1)**: Foundational — BLOCKS all other stories
- **US2 (P2)**: Can start after US1 — no dependency on other stories
- **US3 (P3)**: Can start after US1 — no dependency on other stories
- **US4 (P4)**: Can start after US1 — needs PostgresServiceManager from US1
- **US5 (P5)**: Can start after US1 — needs DataStoreClient from US1
- **US6 (P6)**: Can start after US1 — best validated after US2+US3
- **US7 (P7)**: Requires US2+US3 test classes to exist

### Within Each User Story

- Infrastructure/builders before test classes
- Test classes can be written incrementally (one test method at a time)
- Each test method should be independently verifiable

### Parallel Opportunities

- T008, T009, T010, T011, T012 can all run in parallel (different files, no dependencies)
- US2, US3, US4, US5 can all start in parallel after Phase 2
- T034, T035 can run in parallel (different files)

---

## Parallel Example: Phase 2 (Foundational)

```bash
# These can all run in parallel (different files):
Task T008: "Create TestFixtures in test-common/.../fixtures/TestFixtures.java"
Task T009-T010e: "Create fixture files in src/test/resources/fixtures/*/"
Task T011: "Create PostgresServiceManager in test-common/.../services/PostgresServiceManager.java"
Task T012: "Create DataStoreClient in test-common/.../client/DataStoreClient.java"
```

## Parallel Example: User Stories (after Phase 2)

```bash
# All user stories can start in parallel after foundational phase:
Developer A: US2 (Tools) — T014-T019
Developer B: US3 (Resources) — T020-T023
Developer C: US4 (Database) — T024-T026
Developer D: US5 (Data Store) — T027-T029
```

---

## Implementation Strategy

### MVP First (US1 + US2 Only)

1. Complete Phase 1: Setup (T001-T004)
2. Complete Phase 2: Foundational US1 (T005-T013)
3. Complete Phase 3: US2 Tool Tests (T014-T019)
4. **STOP and VALIDATE**: Run `mvn clean install -pl camel-integration-capability-tests`
5. 6 tests passing = MVP delivered

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready
2. Add US2 (Tools) → 6 tests → **MVP!**
3. Add US3 (Resources) → +4 tests → 10 total
4. Add US4 (Database) → +3 tests → 13 total
5. Add US5 (Data Store) → +3 tests → 16 total
6. Add US6 (Multi-Instance) → +2 tests → 18 total
7. Add US7 (Isolation) → +2 tests → 20 total
8. Polish → README, docs → Complete

---

## Notes

- CIC JAR is a fat JAR (not Quarkus fast-jar) — different process launch pattern
- CIC uses CLI args (not system properties) — CamelCapabilityManager must handle this
- Routes/rules/dependencies are static fixture files in `src/test/resources/fixtures/{scenario}/`
- TestFixtures copies fixtures to tempDir with optional `${VAR}` substitution for dynamic values
- PostgreSQL is suite-scoped (shared across tests in CamelDatabaseToolITCase)
- Data Store entries must be cleaned in @AfterEach to prevent test pollution
- Multiple CIC instances need unique names and gRPC ports