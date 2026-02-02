# Tasks: HTTP Capability Tests

**Input**: Design documents from `/specs/001-http-capability-tests/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Multi-module Maven**: `test-common/src/main/java/`, `http-capability-tests/src/test/java/`
- Paths based on plan.md structure

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and Maven module structure

- [ ] T001 Create parent pom.xml with Java 21, JUnit 5, Testcontainers, and dependency management in pom.xml
- [ ] T002 [P] Create test-common module pom.xml with dependencies in test-common/pom.xml
- [ ] T003 [P] Create http-capability-tests module pom.xml with test-common dependency in http-capability-tests/pom.xml
- [ ] T004 [P] Create artifacts directory structure for pre-built JARs in artifacts/README.md
- [ ] T005 [P] Create WanakuTestConstants with configuration keys in test-common/src/main/java/ai/wanaku/test/WanakuTestConstants.java

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T006 Implement PortUtils with ServerSocket(0) pattern and retry logic in test-common/src/main/java/ai/wanaku/test/utils/PortUtils.java
- [ ] T007 [P] Implement LogUtils for process output redirection to target/logs/ in test-common/src/main/java/ai/wanaku/test/utils/LogUtils.java
- [ ] T008 [P] Implement HealthCheckUtils for component readiness verification in test-common/src/main/java/ai/wanaku/test/utils/HealthCheckUtils.java
- [ ] T009 Implement TestConfiguration holder class with all config fields in test-common/src/main/java/ai/wanaku/test/config/TestConfiguration.java
- [ ] T010 Implement ProcessManager base class with start/stop/graceful shutdown in test-common/src/main/java/ai/wanaku/test/managers/ProcessManager.java
- [ ] T011 Implement KeycloakManager with Testcontainers and realm import in test-common/src/main/java/ai/wanaku/test/managers/KeycloakManager.java
- [ ] T012 Implement RouterManager extending ProcessManager with health check in test-common/src/main/java/ai/wanaku/test/managers/RouterManager.java
- [ ] T013 Implement HttpToolServiceManager extending ProcessManager with gRPC registration in test-common/src/main/java/ai/wanaku/test/managers/HttpToolServiceManager.java
- [ ] T014 Create wanaku-realm.json Keycloak realm configuration in http-capability-tests/src/test/resources/wanaku-realm.json

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Create Common Test Infrastructure (Priority: P1) 🎯 MVP

**Goal**: Provide reusable test infrastructure (test-common module) for managing Keycloak, Router, and capability lifecycles

**Independent Test**: Start Keycloak, Router, and HTTP Tool Service via managers, verify health checks pass, confirm graceful shutdown works

### Implementation for User Story 1

- [ ] T015 [P] [US1] Implement HttpToolConfig model class in test-common/src/main/java/ai/wanaku/test/model/HttpToolConfig.java
- [ ] T016 [P] [US1] Implement ToolInfo model class in test-common/src/main/java/ai/wanaku/test/model/ToolInfo.java
- [ ] T017 [US1] Implement RouterClient REST API client with tool CRUD operations in test-common/src/main/java/ai/wanaku/test/client/RouterClient.java
- [ ] T018 [P] [US1] Implement CLIExecutor utility for CLI command execution in test-common/src/main/java/ai/wanaku/test/client/CLIExecutor.java
- [ ] T019 [P] [US1] Implement CLIResult model class in test-common/src/main/java/ai/wanaku/test/client/CLIResult.java
- [ ] T020 [US1] Implement MockHttpServer wrapper around MockWebServer in test-common/src/main/java/ai/wanaku/test/mock/MockHttpServer.java
- [ ] T021 [US1] Implement BaseIntegrationTest with layered isolation lifecycle in test-common/src/main/java/ai/wanaku/test/base/BaseIntegrationTest.java
- [ ] T022 [US1] Create InfrastructureTest to verify managers start/stop correctly in http-capability-tests/src/test/java/ai/wanaku/test/http/InfrastructureTest.java

**Checkpoint**: User Story 1 complete - test infrastructure is functional and verified

---

## Phase 4: User Story 2 - HTTP Tool Registration and Listing (Priority: P2)

**Goal**: Verify HTTP tools can be registered with Router and listed via MCP protocol

**Independent Test**: Register HTTP tool via REST API, list via MCP client, verify correct metadata

### Implementation for User Story 2

- [ ] T023 [US2] Implement HttpToolRegistrationTest for REST API tool registration in http-capability-tests/src/test/java/ai/wanaku/test/http/HttpToolRegistrationTest.java
- [ ] T024 [US2] Add test method shouldRegisterHttpToolViaRestApi() in HttpToolRegistrationTest.java
- [ ] T025 [US2] Add test method shouldListMultipleRegisteredTools() in HttpToolRegistrationTest.java
- [ ] T026 [US2] Add test method shouldRemoveRegisteredTool() in HttpToolRegistrationTest.java
- [ ] T027 [US2] Implement HttpToolCliTest for CLI tool registration in http-capability-tests/src/test/java/ai/wanaku/test/http/HttpToolCliTest.java
- [ ] T028 [US2] Add test method shouldRegisterHttpToolViaCli() in HttpToolCliTest.java

**Checkpoint**: User Story 2 complete - tool registration and listing verified

---

## Phase 5: User Story 3 - HTTP Tool Invocation (Priority: P3)

**Goal**: Verify HTTP tools can be invoked via MCP protocol and return correct responses

**Independent Test**: Register tool targeting mock server, invoke via MCP client, verify response matches mock

### Implementation for User Story 3

- [ ] T029 [US3] Implement HttpToolInvocationTest for tool invocation in http-capability-tests/src/test/java/ai/wanaku/test/http/HttpToolInvocationTest.java
- [ ] T030 [US3] Add test method shouldInvokeToolWithValidParameters() in HttpToolInvocationTest.java
- [ ] T031 [US3] Add test method shouldReceiveErrorForInvalidParameters() in HttpToolInvocationTest.java
- [ ] T032 [US3] Add test method shouldReceiveMockConfiguredErrorResponse() in HttpToolInvocationTest.java
- [ ] T033 [US3] Implement HttpToolErrorHandlingTest for error scenarios in http-capability-tests/src/test/java/ai/wanaku/test/http/HttpToolErrorHandlingTest.java
- [ ] T034 [US3] Add test method shouldHandleUnreachableEndpoint() in HttpToolErrorHandlingTest.java
- [ ] T035 [P] [US3] Implement ExternalApiTest for optional real-world validation in http-capability-tests/src/test/java/ai/wanaku/test/http/ExternalApiTest.java
- [ ] T036 [US3] Add @DisabledIfSystemProperty for skipping external API tests in ExternalApiTest.java

**Checkpoint**: User Story 3 complete - tool invocation and error handling verified

---

## Phase 6: User Story 4 - Test Isolation Verification (Priority: P4)

**Goal**: Verify tests are properly isolated and don't affect each other

**Independent Test**: Run multiple tests registering different tools, verify each starts with clean state, no tool leakage

### Implementation for User Story 4

- [ ] T037 [US4] Implement TestIsolationTest for isolation verification in http-capability-tests/src/test/java/ai/wanaku/test/http/TestIsolationTest.java
- [ ] T038 [US4] Add test method shouldNotSeeToolsFromPreviousTest() in TestIsolationTest.java
- [ ] T039 [US4] Add test method shouldStartWithCleanStateAfterCleanup() in TestIsolationTest.java
- [ ] T040 [US4] Configure Maven Surefire for random test order execution in http-capability-tests/pom.xml
- [ ] T041 [US4] Add test method shouldPassConsistentlyInRandomOrder() in TestIsolationTest.java

**Checkpoint**: User Story 4 complete - test isolation verified

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T042 [P] Create sample-tool.properties for HTTP tool configuration example in http-capability-tests/src/test/resources/http-tools/sample-tool.properties
- [ ] T043 [P] Add Javadoc comments to all public classes in test-common module
- [ ] T044 Verify all tests pass with mvn test from project root
- [ ] T045 Run tests with randomized order and verify consistent results
- [ ] T046 Validate quickstart.md scenarios work as documented

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - User stories can proceed sequentially in priority order (P1 → P2 → P3 → P4)
  - US2, US3, US4 can run in parallel after US1 is complete (if staffed)
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - Foundation for all other stories
- **User Story 2 (P2)**: Depends on US1 (needs BaseIntegrationTest, RouterClient)
- **User Story 3 (P3)**: Depends on US1 (needs MockHttpServer, MCP client integration)
- **User Story 4 (P4)**: Depends on US1 (needs BaseIntegrationTest lifecycle verification)

### Within Each User Story

- Models before clients/services
- Managers before base test class
- Base test class before specific test classes
- Core tests before edge case tests

### Parallel Opportunities

- T002, T003, T004, T005 can run in parallel (setup phase)
- T007, T008 can run in parallel (foundational utilities)
- T015, T016 can run in parallel (model classes)
- T018, T019 can run in parallel (CLI components)
- T035 can run in parallel with other US3 tests (independent optional test)

---

## Parallel Example: Phase 2 Foundational

```bash
# Launch utilities in parallel:
Task: "Implement LogUtils in test-common/src/main/java/ai/wanaku/test/utils/LogUtils.java"
Task: "Implement HealthCheckUtils in test-common/src/main/java/ai/wanaku/test/utils/HealthCheckUtils.java"
```

## Parallel Example: User Story 1 Models

```bash
# Launch model classes in parallel:
Task: "Implement HttpToolConfig model in test-common/src/main/java/ai/wanaku/test/model/HttpToolConfig.java"
Task: "Implement ToolInfo model in test-common/src/main/java/ai/wanaku/test/model/ToolInfo.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Run InfrastructureTest, verify managers work
5. Deploy/demo if ready - can write basic tests using BaseIntegrationTest

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Infrastructure working (MVP!)
3. Add User Story 2 → Test independently → Tool registration working
4. Add User Story 3 → Test independently → Tool invocation working
5. Add User Story 4 → Test independently → Isolation verified
6. Each story adds test capability without breaking previous stories

### Single Developer Strategy

Follow priority order: P1 → P2 → P3 → P4
Stop at any checkpoint to verify story works independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- All file paths are relative to repository root
