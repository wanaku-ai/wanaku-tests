# Test Plan: Wanaku Barn Service Catalog End to End

## Overview

This test plan is the end-to-end plan for Wanaku Barn. It uses the `wanaku` CLI against a local Wanaku Barn (no
authentication) and follows a service catalog from its source files to an MCP tool call through Wanaku Server. It
covers initialization, route authoring, rule generation, validation, packaging, deployment and listing, a removal and
restore round trip, template listing and instantiation, local deployment instructions and negative tests. Two phases
serve catalogs through a Camel Integration Capability (CIC) runtime and call their tools through Wanaku Server: the
catalog that this plan writes (Phase 11), and a catalog instantiated from the built-in `camel-qdrant-search-tool`
template against a local Qdrant vector database (Phase 12).

The detailed checks for catalog versioning and removal (stale versions, unknown versions, a second removal, a deploy
with a removed name, version restore) are in the Barn unit tests `CatalogVersioningTest` and `CatalogRemovalTest`.
This plan does not repeat them.

A deployed catalog does not create tools by itself. Barn only stores it. Tools appear in Wanaku Server when a CIC
runtime serves the catalog and is registered as a forward (Phases 11 and 12):

```text
catalog or template (Barn) --deploy/instantiate--> catalog (Barn) --CIC downloads--> CIC runtime :9190/mcp
    --forward--> Wanaku Server --MCP--> ${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp
```

### Components under test

| Component | Needed for | Common procedure |
|-----------|------------|------------------|
| Wanaku Barn | Phases 2 to 13 (`wanaku service ...`) | [common/start-barn.md](common/start-barn.md) |
| Wanaku Server | Phases 11, 12 and 13 | [common/start-server.md](common/start-server.md) |
| CIC runtime (container) | Phases 11 and 12 | [common/start-cic.md](common/start-cic.md) |
| Qdrant (container or Qdrant Cloud) | Phase 12 only (skippable) | Phase 12 of this plan |

All `wanaku service` commands use `--host "${WANAKU_BARN_URL}"`. The CLI default host is `http://localhost:8080`,
which is Wanaku Server. Wanaku Server does not serve the service catalog API, so always pass `--host`.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java --version` |
| `jq` | 1.6+ | `jq --version` |
| `curl` | any | `curl --version` |
| `unzip` | any | `unzip -v` |
| `base64` | any (coreutils) | `base64 --version` |
| `docker` or `podman` | 20+ | `docker --version` or `podman --version` |

### Container images and ports

| Item | Value | Needed for |
|------|-------|------------|
| CIC image | `quay.io/wanaku/camel-integration-capability:latest` (`CIC_IMAGE`) | Phases 11 and 12 |
| Qdrant image | `docker.io/qdrant/qdrant:latest` (`QDRANT_IMAGE`) | Phase 12 |
| Port `8081` | Wanaku Server MCP listener | Phases 11 to 13 |
| Port `9190` | CIC MCP port (`CIC_PORT`), host networking | Phases 11 and 12 |
| Ports `6333` and `6334` | Qdrant REST and gRPC (`QDRANT_REST_PORT`, `QDRANT_GRPC_PORT`) | Phase 12 |

The CIC runtime of Phase 11 stops before Phase 12 starts, so both phases use the same CIC port.

### Prerequisite check

The executable block is in Step S.1 below.

### CLI invocation

Call the CLI as `java -jar "${CLI_JAR}"`. Do not put the full command in one variable, because zsh treats it as one
token. `CLI_JAR` is an absolute path in this plan, because Phase 2 changes the current directory.

### Environment variables

The executable block is in Step S.2 below.

| Variable | Default | Description |
|----------|---------|-------------|
| `WANAKU_REPO_ROOT` | current directory | Root of this repository |
| `TEST_WORK_DIR` | `$(mktemp -d)` | Directory for the catalog sources and packages |
| `TEST_CATALOG_NAME` | `test-catalog` | Name of the catalog that this plan creates |
| `TEST_PACKAGE_OUTPUT` | `${TEST_WORK_DIR}/test-catalog.b64` | Output of `wanaku service package` |
| `TEST_ECHO_CATALOG` | `${TEST_CATALOG_NAME}-echo` | Catalog created from the `echo-tool` template |
| `CIC_NAME` | `cic-test-catalog` | CIC container name and forward name (Phase 11) |
| `CIC_NAMESPACE` | `default` | Wanaku namespace of the forwards (Phases 11 and 12) |
| `WANAKU_TEST_GOVERNED_NAMESPACES` | `${CIC_NAMESPACE}` | Namespaces where the test governance allows tool calls |
| `TEMPLATE_CATALOG` | `qdrant-search-test` | Catalog created from the `camel-qdrant-search-tool` template (Phase 12) |
| `TEMPLATE_PROPS_FILE` | `${TEST_WORK_DIR}/qdrant-search.properties` | Properties file for the instantiation (Phase 12) |
| `QDRANT_CIC_NAME` | `cic-qdrant` | CIC container name and forward name (Phase 12) |
| `QDRANT_IMAGE` | `docker.io/qdrant/qdrant:latest` | Qdrant container image |
| `QDRANT_CONTAINER` | `qdrant-test` | Qdrant container name |
| `QDRANT_HOST` | `localhost` | Qdrant host, as the CIC container sees it |
| `QDRANT_GRPC_PORT` | `6334` | Qdrant gRPC port (used by `camel-qdrant`) |
| `QDRANT_REST_PORT` | `6333` | Qdrant REST port (used to seed the test data) |
| `QDRANT_API_KEY` | _(empty)_ | Qdrant API key. Empty for a local container. |
| `QDRANT_COLLECTION` | `wanaku-test-collection` | Test collection |
| `QDRANT_VECTOR_DIMENSION` | `4` | Vector size of the test collection |

Barn reserves the name of a removed catalog until a restore (see Phase 8). Use a fresh Barn home per run (the default
of [common/start-barn.md](common/start-barn.md)), or set unique catalog names when you run against a shared Barn.

---

## Phase S: Setup

### Step S.1: Check prerequisites

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
for CMD in java jq curl unzip base64; do
  if command -v "${CMD}" > /dev/null 2>&1; then
    echo "PASS: ${CMD} found at $(command -v "${CMD}")"
  else
    echo "FAIL: ${CMD} is not installed"
    FAIL=1
  fi
done
if command -v docker > /dev/null 2>&1 || command -v podman > /dev/null 2>&1; then
  echo "PASS: container runtime found"
else
  echo "FAIL: neither docker nor podman is installed"
  FAIL=1
fi
[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || { echo "FAIL: prerequisites missing"; exit 1; }
```

**Expected result:**

- The output includes `PASS: java found at`.
- The output includes `PASS: jq found at`.
- The output includes `PASS: curl found at`.
- The output includes `PASS: unzip found at`.
- The output includes `PASS: base64 found at`.
- The output includes `PASS: container runtime found`.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step S.2: Set environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-$(pwd)}"
export TEST_WORK_DIR="${TEST_WORK_DIR:-$(mktemp -d)}"
export TEST_CATALOG_NAME="${TEST_CATALOG_NAME:-test-catalog}"
export TEST_PACKAGE_OUTPUT="${TEST_PACKAGE_OUTPUT:-${TEST_WORK_DIR}/test-catalog.b64}"
export TEST_ECHO_CATALOG="${TEST_ECHO_CATALOG:-${TEST_CATALOG_NAME}-echo}"
export CIC_NAME="${CIC_NAME:-cic-test-catalog}"
export CIC_NAMESPACE="${CIC_NAMESPACE:-default}"
export WANAKU_TEST_GOVERNED_NAMESPACES="${WANAKU_TEST_GOVERNED_NAMESPACES:-${CIC_NAMESPACE}}"

export TEMPLATE_CATALOG="${TEMPLATE_CATALOG:-qdrant-search-test}"
export TEMPLATE_PROPS_FILE="${TEMPLATE_PROPS_FILE:-${TEST_WORK_DIR}/qdrant-search.properties}"
export QDRANT_CIC_NAME="${QDRANT_CIC_NAME:-cic-qdrant}"
export QDRANT_IMAGE="${QDRANT_IMAGE:-docker.io/qdrant/qdrant:latest}"
export QDRANT_CONTAINER="${QDRANT_CONTAINER:-qdrant-test}"
export QDRANT_HOST="${QDRANT_HOST:-localhost}"
export QDRANT_GRPC_PORT="${QDRANT_GRPC_PORT:-6334}"
export QDRANT_REST_PORT="${QDRANT_REST_PORT:-6333}"
export QDRANT_API_KEY="${QDRANT_API_KEY:-}"
export QDRANT_COLLECTION="${QDRANT_COLLECTION:-wanaku-test-collection}"
export QDRANT_VECTOR_DIMENSION="${QDRANT_VECTOR_DIMENSION:-4}"
```

**Expected result:**

- The commands complete without errors (inferred).

## Phase 1: Setup

### Step 1.1: Start Wanaku Barn

Follow [common/start-barn.md](common/start-barn.md).

After completion, `WANAKU_BARN_URL`, `WANAKU_BARN_PID` and
`CLI_JAR` must be set.

Run this block as a separate `bash` script, not in the shared session.

```bash
CLI_JAR="$(cd "$(dirname "${CLI_JAR}")" && pwd)/$(basename "${CLI_JAR}")"
export CLI_JAR
[ -f "${CLI_JAR}" ] && echo "PASS: CLI_JAR is ${CLI_JAR}" || { echo "FAIL: CLI_JAR not found"; exit 1; }
```

**Expected result:**

- The output includes `PASS: CLI_JAR is <CLI_JAR>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step 1.2: Start Wanaku Server with the test governance configuration

Follow [common/start-server.md](common/start-server.md) and run step 2b, so that the tool calls of Phases 11 and 12
are permitted in `${CIC_NAMESPACE}`. After completion, `WANAKU_SERVER_URL`, `WANAKU_MCP_BASE_URL` and
`WANAKU_SERVER_PID` must be set.

**Expected result:**

- The invoked procedure completes successfully (inferred).

### Step 1.3: Detect the container runtime

Follow [common/container-runtime.md](common/container-runtime.md). After completion, `CONTAINER_RUNTIME` must be set.

**Expected result:**

- The invoked procedure completes successfully (inferred).

## Phase 2: Service Catalog -- Init

### Test 2.1: Initialize a new service catalog

Run this block as a separate `bash` script, not in the shared session.

```bash
cd "${TEST_WORK_DIR}"
java -jar "${CLI_JAR}" service init --plain --name="${TEST_CATALOG_NAME}" --services=system-a,system-b
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS: service init succeeded" \
  || { echo "FAIL: service init failed (exit code ${EXIT_CODE})"; exit 1; }
CATALOG_DIR="${TEST_WORK_DIR}/${TEST_CATALOG_NAME}"
```

**Expected result:**

- The output includes `PASS: service init succeeded`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 2.2: Verify the catalog directory and index.properties

Run this block as a separate `bash` script, not in the shared session.

```bash
[ -d "${CATALOG_DIR}" ] && echo "PASS: catalog directory exists" || { echo "FAIL: ${CATALOG_DIR} missing"; exit 1; }
[ -f "${CATALOG_DIR}/index.properties" ] && echo "PASS: index.properties exists" || echo "FAIL: index.properties missing"
```

**Expected result:**

- The output includes `PASS: catalog directory exists`.
- The output includes `PASS: index.properties exists`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 2.3: Verify the service subdirectories

```bash run
for SVC in system-a system-b; do
  for EXT in camel.yaml wanaku-rules.yaml dependencies.txt; do
    FILE="${CATALOG_DIR}/${SVC}/${SVC}.${EXT}"
    [ -f "${FILE}" ] && echo "PASS: ${SVC}/${SVC}.${EXT} exists" || echo "FAIL: ${FILE} missing"
  done
done
```

**Expected result:**

- The output includes `PASS: system-a/system-a.camel.yaml exists`.
- The output includes `PASS: system-a/system-a.wanaku-rules.yaml exists`.
- The output includes `PASS: system-a/system-a.dependencies.txt exists`.
- The output includes `PASS: system-b/system-b.camel.yaml exists`.
- The output includes `PASS: system-b/system-b.wanaku-rules.yaml exists`.
- The output includes `PASS: system-b/system-b.dependencies.txt exists`.
- The output contains no line starting with `FAIL:`.

### Test 2.4: Verify the index.properties entries

```bash run
INDEX="${CATALOG_DIR}/index.properties"
grep -q "^catalog.name=${TEST_CATALOG_NAME}$" "${INDEX}" && echo "PASS: catalog.name" || echo "FAIL: catalog.name"
grep -q "^catalog.description=." "${INDEX}" && echo "PASS: catalog.description" || echo "FAIL: catalog.description"
grep -q "^catalog.services=system-a,system-b$" "${INDEX}" && echo "PASS: catalog.services" || echo "FAIL: catalog.services"
for SVC in system-a system-b; do
  for KEY in routes rules dependencies; do
    grep -q "^catalog.${KEY}.${SVC}=" "${INDEX}" && echo "PASS: catalog.${KEY}.${SVC}" || echo "FAIL: catalog.${KEY}.${SVC}"
  done
done
```

**Expected result:**

- The output includes `PASS: catalog.name`.
- The output includes `PASS: catalog.description`.
- The output includes `PASS: catalog.services`.
- The output includes `PASS: catalog.routes.system-a`.
- The output includes `PASS: catalog.routes.system-b`.
- The output includes `PASS: catalog.rules.system-a`.
- The output includes `PASS: catalog.rules.system-b`.
- The output includes `PASS: catalog.dependencies.system-a`.
- The output includes `PASS: catalog.dependencies.system-b`.
- The output contains no line starting with `FAIL:`.

## Phase 3: Service Catalog -- Write the Routes

The CIC serves a route as an MCP tool when the route starts from `ai-tool:<name>`. The tool arguments arrive as
message headers. These routes return the `message` argument with a prefix, so that Phase 11 can check the result
without an external system.

### Step 3.1: Write the system-a route

```bash run
cat > "${CATALOG_DIR}/system-a/system-a.camel.yaml" <<'ROUTE_EOF'
- route:
    id: get-system-a-data
    from:
      uri: ai-tool:get-system-a-data
      parameters:
        tags: wanaku
        description: "Return the message prefixed with system-a"
        parameter.message: string
        parameter.message.description: "The message to process"
      steps:
        - setBody:
            simple: "system-a: ${header.message}"
ROUTE_EOF
grep -q "uri: ai-tool:get-system-a-data" "${CATALOG_DIR}/system-a/system-a.camel.yaml" \
  && echo "PASS: system-a route written" || echo "FAIL: system-a route not written"
```

**Expected result:**

- The output includes `PASS: system-a route written`.
- The output contains no line starting with `FAIL:`.

### Step 3.2: Write the system-b route

```bash run
cat > "${CATALOG_DIR}/system-b/system-b.camel.yaml" <<'ROUTE_EOF'
- route:
    id: get-system-b-data
    from:
      uri: ai-tool:get-system-b-data
      parameters:
        tags: wanaku
        description: "Return the message prefixed with system-b"
        parameter.message: string
        parameter.message.description: "The message to process"
      steps:
        - setBody:
            simple: "system-b: ${header.message}"
ROUTE_EOF
grep -q "uri: ai-tool:get-system-b-data" "${CATALOG_DIR}/system-b/system-b.camel.yaml" \
  && echo "PASS: system-b route written" || echo "FAIL: system-b route not written"
```

**Expected result:**

- The output includes `PASS: system-b route written`.
- The output contains no line starting with `FAIL:`.

## Phase 4: Service Catalog -- Expose

`wanaku service expose` generates rules files from the route IDs. Rules files are optional since 0.3.0, because the
`ai-tool:` routes define the tools. The command is still supported.

### Test 4.1: Generate the rules

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service expose --plain --path="${CATALOG_DIR}" --namespace=default 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Expose complete: 2 route(s) across 2 system(s)" \
  && echo "PASS: service expose succeeded" || echo "FAIL: service expose failed (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: service expose succeeded`.
- The output contains no line starting with `FAIL:`.

### Test 4.2: Verify the generated rules

```bash run
for SVC in system-a system-b; do
  RULES="${CATALOG_DIR}/${SVC}/${SVC}.wanaku-rules.yaml"
  grep -q "get-${SVC}-data:" "${RULES}" && echo "PASS: ${SVC} rules contain get-${SVC}-data" \
    || echo "FAIL: ${SVC} rules do not contain get-${SVC}-data"
  grep -q 'namespace: "default"' "${RULES}" && echo "PASS: ${SVC} rules have the namespace" \
    || echo "FAIL: ${SVC} rules have no namespace"
done
```

**Expected result:**

- The output includes `PASS: system-a rules contain get-system-a-data`.
- The output includes `PASS: system-b rules contain get-system-b-data`.
- The output includes `PASS: system-a rules have the namespace`.
- The output includes `PASS: system-b rules have the namespace`.
- The output contains no line starting with `FAIL:`.

## Phase 5: Service Catalog -- Validate

### Test 5.1: Barn validates the catalog

`wanaku service validate` sends the package to Barn. Barn does not deploy it. A missing `catalog.icon` is a warning.

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service validate --plain --path="${CATALOG_DIR}" \
  --host "${WANAKU_BARN_URL}" --no-auth 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service catalog '${TEST_CATALOG_NAME}' is valid" \
  && echo "PASS: catalog is valid" || echo "FAIL: catalog validation failed (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: catalog is valid`.
- The output contains no line starting with `FAIL:`.

### Test 5.2: Barn reports an invalid route (negative)

```bash run
BAD_DIR="${TEST_WORK_DIR}/invalid-catalog"
mkdir -p "${BAD_DIR}/sys"
printf 'catalog.name=invalid-catalog\ncatalog.description=Invalid\ncatalog.services=sys\ncatalog.routes.sys=sys/sys.camel.yaml\n' \
  > "${BAD_DIR}/index.properties"
printf -- '- route:\n    id: broken\n    steps: []\n' > "${BAD_DIR}/sys/sys.camel.yaml"

OUTPUT=$(java -jar "${CLI_JAR}" service validate --plain --path="${BAD_DIR}" --host "${WANAKU_BARN_URL}" --no-auth 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "required property 'from' not found" \
  && echo "${OUTPUT}" | grep -q "is invalid" \
  && echo "PASS: invalid route reported" || echo "FAIL: invalid route not reported (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: invalid route reported`.
- The output contains no line starting with `FAIL:`.

## Phase 6: Service Catalog -- Package

### Test 6.1: Package the catalog

Run this block as a separate `bash` script, not in the shared session.

```bash
java -jar "${CLI_JAR}" service package --plain --path="${CATALOG_DIR}" -o "${TEST_PACKAGE_OUTPUT}"
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && [ -s "${TEST_PACKAGE_OUTPUT}" ] && echo "PASS: package written to ${TEST_PACKAGE_OUTPUT}" \
  || { echo "FAIL: service package failed (exit code ${EXIT_CODE})"; exit 1; }
```

**Expected result:**

- The output includes `PASS: package written to <TEST_PACKAGE_OUTPUT>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.2: The package is a Base64-encoded ZIP with all files

```bash run
base64 -d < "${TEST_PACKAGE_OUTPUT}" > "${TEST_WORK_DIR}/package.zip" 2>/dev/null \
  && echo "PASS: package is valid Base64" || echo "FAIL: package is not valid Base64"

LISTING=$(unzip -l "${TEST_WORK_DIR}/package.zip" 2>&1)
for ENTRY in index.properties system-a/system-a.camel.yaml system-b/system-b.camel.yaml; do
  echo "${LISTING}" | grep -q " ${ENTRY}$" && echo "PASS: ZIP contains ${ENTRY}" || echo "FAIL: ZIP lacks ${ENTRY}"
done
```

**Expected result:**

- The output includes `PASS: package is valid Base64`.
- The output includes `PASS: ZIP contains index.properties`.
- The output includes `PASS: ZIP contains system-a/system-a.camel.yaml`.
- The output includes `PASS: ZIP contains system-b/system-b.camel.yaml`.
- The output contains no line starting with `FAIL:`.

## Phase 7: Service Catalog -- Deploy and List

### Test 7.1: Deploy the catalog to Barn

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(java -jar "${CLI_JAR}" service deploy --plain --path="${CATALOG_DIR}" --host "${WANAKU_BARN_URL}" --no-auth 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service catalog '${TEST_CATALOG_NAME}' deployed successfully" \
  && echo "PASS: service deploy succeeded" || { echo "FAIL: service deploy failed (exit code ${EXIT_CODE})"; exit 1; }
```

**Expected result:**

- The output includes `PASS: service deploy succeeded`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.2: The catalog is in the list

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service catalog list --plain --host "${WANAKU_BARN_URL}" --no-auth 2>&1)
echo "${OUTPUT}" | grep -q "^${TEST_CATALOG_NAME}[[:space:]]" \
  && echo "PASS: ${TEST_CATALOG_NAME} is in the catalog list" || echo "FAIL: ${TEST_CATALOG_NAME} not listed"
```

**Expected result:**

- The output includes `PASS: <TEST_CATALOG_NAME> is in the catalog list`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

## Phase 8: Service Catalog -- Remove and Restore

Barn does not delete a removed catalog. It hides it, keeps its versions and reserves its name until a restore. This
phase runs one round trip. The Barn unit tests `CatalogRemovalTest` and `CatalogVersioningTest` cover the other cases.

### Test 8.1: Remove the catalog

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service catalog remove --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name="${TEST_CATALOG_NAME}" 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Successfully removed service catalog '${TEST_CATALOG_NAME}'" \
  && echo "PASS: catalog removed" || echo "FAIL: catalog remove failed (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: catalog removed`.
- The output contains no line starting with `FAIL:`.

### Test 8.2: The catalog is not listed, and it is in the removed list

```bash run
java -jar "${CLI_JAR}" service catalog list --plain --host "${WANAKU_BARN_URL}" --no-auth 2>&1 \
  | grep -q "^${TEST_CATALOG_NAME}[[:space:]]" \
  && echo "FAIL: removed catalog is still listed" || echo "PASS: removed catalog is not listed"

java -jar "${CLI_JAR}" service removed --plain --host "${WANAKU_BARN_URL}" --no-auth 2>&1 \
  | grep -q "^${TEST_CATALOG_NAME}[[:space:]]" \
  && echo "PASS: catalog is in the removed list" || echo "FAIL: catalog is not in the removed list"
```

**Expected result:**

- The output includes `PASS: removed catalog is not listed`.
- The output includes `PASS: catalog is in the removed list`.
- The output contains no line starting with `FAIL:`.

### Test 8.3: Restore the catalog

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service restore --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name="${TEST_CATALOG_NAME}" 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Restored '${TEST_CATALOG_NAME}'" \
  && echo "PASS: catalog restored" || echo "FAIL: restore failed (exit code ${EXIT_CODE})"
java -jar "${CLI_JAR}" service catalog list --plain --host "${WANAKU_BARN_URL}" --no-auth 2>&1 \
  | grep -q "^${TEST_CATALOG_NAME}[[:space:]]" \
  && echo "PASS: restored catalog is listed" || echo "FAIL: restored catalog is not listed"
```

**Expected result:**

- The output includes `PASS: catalog restored`.
- The output includes `PASS: restored catalog is listed`.
- The output contains no line starting with `FAIL:`.

## Phase 9: Service Templates

### Test 9.1: List the templates

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template list --plain --host "${WANAKU_BARN_URL}" --no-auth 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS: template list succeeded" || echo "FAIL: template list failed"
for TEMPLATE in echo-tool kafka-tool sql-tool github-pullrequest-source-tool; do
  echo "${OUTPUT}" | grep -q "^${TEMPLATE}[[:space:]]" && echo "PASS: built-in template ${TEMPLATE} listed" \
    || echo "FAIL: built-in template ${TEMPLATE} not listed"
done
```

**Expected result:**

- The output includes `PASS: template list succeeded`.
- The output includes `PASS: built-in template echo-tool listed`.
- The output includes `PASS: built-in template kafka-tool listed`.
- The output includes `PASS: built-in template sql-tool listed`.
- The output includes `PASS: built-in template github-pullrequest-source-tool listed`.
- The output contains no line starting with `FAIL:`.

### Test 9.2: Instantiate the echo-tool template

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template instantiate --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name echo-tool --property "echo.separator= | " --service-name "${TEST_ECHO_CATALOG}" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service catalog created successfully from template 'echo-tool'" \
  && echo "PASS: echo-tool instantiated" || echo "FAIL: echo-tool instantiation failed (exit code ${EXIT_CODE})"

java -jar "${CLI_JAR}" service catalog list --plain --host "${WANAKU_BARN_URL}" --no-auth 2>&1 \
  | grep -q "^${TEST_ECHO_CATALOG}[[:space:]]" \
  && echo "PASS: ${TEST_ECHO_CATALOG} is listed" || echo "FAIL: ${TEST_ECHO_CATALOG} not listed"
```

**Expected result:**

- The output includes `PASS: echo-tool instantiated`.
- The output includes `PASS: <TEST_ECHO_CATALOG> is listed`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Test 9.3: The instantiated catalog has the supplied value

```bash run
java -jar "${CLI_JAR}" service versions download --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name="${TEST_ECHO_CATALOG}" --version=1 --output="${TEST_WORK_DIR}/echo.zip" > /dev/null 2>&1
unzip -p "${TEST_WORK_DIR}/echo.zip" echo/service.properties | grep -q '^echo.separator=' \
  && unzip -p "${TEST_WORK_DIR}/echo.zip" echo/service.properties | grep '^echo.separator=' | grep -q '|' \
  && echo "PASS: echo.separator has the supplied value" || echo "FAIL: echo.separator was not replaced"
```

**Expected result:**

- The output includes `PASS: echo.separator has the supplied value`.
- The output contains no line starting with `FAIL:`.

## Phase 10: Deployment Instructions

`wanaku service instructions` supports the models `local` and `docker`. This plan checks the `local` model.

> [!NOTE]
> The generated instructions still use the options of the gRPC-based capability (`--registration-url` of the router,
> `--grpc-port`, client credentials). They do not match the current CIC contract (see
> [common/start-cic.md](common/start-cic.md)). The test checks only the catalog and system names.

### Test 10.1: Local instructions

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service instructions --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name="${TEST_CATALOG_NAME}" --model=local 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "(camel-integration-capability, local)" \
  && echo "PASS: local instructions returned" || echo "FAIL: local instructions failed (exit code ${EXIT_CODE})"
for SVC in system-a system-b; do
  echo "${OUTPUT}" | grep -q -- "--service-catalog-system ${SVC}" && echo "PASS: instructions cover ${SVC}" \
    || echo "FAIL: instructions do not cover ${SVC}"
done
echo "${OUTPUT}" | grep -q -- "--service-catalog ${TEST_CATALOG_NAME}" \
  && echo "PASS: instructions name the catalog" || echo "FAIL: instructions do not name the catalog"
```

**Expected result:**

- The output includes `PASS: local instructions returned`.
- The output includes `PASS: instructions cover system-a`.
- The output includes `PASS: instructions cover system-b`.
- The output includes `PASS: instructions name the catalog`.
- The output contains no line starting with `FAIL:`.

## Phase 11: Serve the Catalog through the CIC

This phase is required. It needs Wanaku Server (Step 1.2) and a container runtime (Step 1.3). It serves only
`system-a`.

### Test 11.0: Pre-flight check

```bash run
if [ -n "${CONTAINER_RUNTIME}" ] && [ -n "${WANAKU_SERVER_URL}" ]; then
  echo "PASS: Phase 11 pre-flight checks passed"
else
  echo "FAIL: Wanaku Server or container runtime not available -- run Steps 1.2 and 1.3"
fi
```

**Expected result:**

- The output includes `PASS: Phase 11 pre-flight checks passed`.
- The output contains no line starting with `FAIL:`.

### Step 11.1: Set the CIC runtime inputs

```bash run
export CIC_CATALOG="${TEST_CATALOG_NAME}"
export CIC_CATALOG_SYSTEM="system-a"
```

**Expected result:**

- The block exports `CIC_CATALOG` and `CIC_CATALOG_SYSTEM` without errors (inferred).

### Step 11.2: Start the CIC runtime

Use the values set in Step 11.1.

Follow [common/start-cic.md](common/start-cic.md).

After completion, the forward `${CIC_NAME}` is registered in `${CIC_NAMESPACE}`.

**Expected result:**

- The forward `<CIC_NAME>` is registered in `<CIC_NAMESPACE>`.

### Test 11.3: Only the tool of the served system is registered

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^get-system-a-data[[:space:]]" && echo "PASS: get-system-a-data registered" \
  || echo "FAIL: get-system-a-data not registered"
echo "${OUTPUT}" | grep -q "^get-system-b-data[[:space:]]" && echo "FAIL: get-system-b-data registered" \
  || echo "PASS: get-system-b-data not registered (system-b is not served)"
```

**Expected result:**

- The output includes `PASS: get-system-a-data registered`.
- The output includes `PASS: get-system-b-data not registered (system-b is not served)`.
- The output contains no line starting with `FAIL:`.

### Test 11.4: Call the tool through Wanaku Server

The restored catalog of Phase 8 has the route of Step 3.1.

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name get-system-a-data --param "message=hello" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "system-a: hello" \
  && echo "PASS: tool returned 'system-a: hello'" || echo "FAIL: tool call failed (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: tool returned 'system-a: hello'`.
- The output contains no line starting with `FAIL:`.

### Step 11.5: Stop the CIC runtime

Run the Shutdown section of [common/start-cic.md](common/start-cic.md). Then verify that the tool is gone:

```bash run
java -jar "${CLI_JAR}" forwards remove --host "${WANAKU_SERVER_URL}" --no-auth \
  --name "${CIC_NAME:-cic-test}" 2>/dev/null || true
${CONTAINER_RUNTIME} stop "${CIC_NAME:-cic-test}" > /dev/null 2>&1 || true
${CONTAINER_RUNTIME} rm "${CIC_NAME:-cic-test}" > /dev/null 2>&1 || true
echo "PASS: CIC runtime stopped and its forward removed"
```

```bash run
java -jar "${CLI_JAR}" tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>&1 \
  | grep -q "^get-system-a-data[[:space:]]" \
  && echo "FAIL: get-system-a-data still registered" || echo "PASS: tool removed with its forward"
```

**Expected result:**

- The output includes `PASS: CIC runtime stopped and its forward removed`.
- The output includes `PASS: tool removed with its forward`.
- The output contains no line starting with `FAIL:`.

## Phase 12: Service Template End to End: Qdrant Search

This phase instantiates the built-in `camel-qdrant-search-tool` template, serves the catalog through a CIC runtime
and calls the `qdrant-similarity-search` tool through Wanaku Server against a seeded Qdrant collection. It reuses
sections C and D of [common/service-template-checks.md](common/service-template-checks.md). The template source and
listing checks are not part of this plan.

The phase needs a container runtime for Qdrant (Option A) and for the CIC. If Test 12.0 or Test 12.9 prints `SKIP:`,
skip the remaining steps of this phase and go to Phase 13. Step 14.3 removes the Qdrant data in all cases.

> [!IMPORTANT]
> Qdrant similarity search needs a query vector. The template route sends the message body to Qdrant without an
> embedding step, and the tool parameter `query` is a string. Test 12.11 records whether the route returns search
> results. Until the template converts the query into a vector, expect Test 12.11 to fail. Test 12.10 checks the
> plumbing (Wanaku Server, CIC and Qdrant connection) independently of the search result.

### Test 12.0: Pre-flight check

```bash run
[ -n "${CONTAINER_RUNTIME}" ] && echo "PASS: container runtime ${CONTAINER_RUNTIME} is available" \
  || echo "SKIP: no container runtime -- skip Phase 12"
```

**Expected result:**

- The output includes `PASS: container runtime <CONTAINER_RUNTIME> is available` or `SKIP: no container runtime -- skip Phase 12`.

### Step 12.1: Set the template variables

These values are fixed by the template. `TEMPLATE_PROPS_PATH` is the `catalog.properties.qdrant-search` entry of the
template `index.properties`.

```bash run
export TEMPLATE_NAME="camel-qdrant-search-tool"
export TEMPLATE_SYSTEM="qdrant-search"
export TEMPLATE_TOOL="qdrant-similarity-search"
export TEMPLATE_TOOL_PARAMS="query"
export TEMPLATE_SEARCH="qdrant"
export TEMPLATE_PROPS_PATH="qdrant-search/service.properties"
export TEMPLATE_WORK_DIR="${TEST_WORK_DIR}/qdrant"
mkdir -p "${TEMPLATE_WORK_DIR}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 12.2: Start Qdrant

Choose the applicable backend option below; run only that option.

Use a local container (Option A) or Qdrant Cloud (Option B). `curl` is used here because Qdrant is not a Wanaku
component.

#### Option A: Local container

Run this block as a separate `bash` script, not in the shared session.

```bash
${CONTAINER_RUNTIME} run -d --name "${QDRANT_CONTAINER}" \
  -p "${QDRANT_REST_PORT}:6333" -p "${QDRANT_GRPC_PORT}:6334" "${QDRANT_IMAGE}" > /dev/null

for i in $(seq 1 20); do
  curl -sf "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/healthz" > /dev/null 2>&1 && break
  sleep 2
done
curl -sf "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/healthz" > /dev/null \
  && echo "PASS: Qdrant is healthy" || { echo "FAIL: Qdrant is not healthy"; exit 1; }
```

#### Option B: Qdrant Cloud

1. Create a cluster at <https://cloud.qdrant.io>.
2. Set `QDRANT_HOST` to the cluster host and `QDRANT_API_KEY` to the API key.

**Expected result:**

- For the local backend option, the output includes `PASS: Qdrant is healthy`; for the cloud/API alternative, the specified backend settings are configured (inferred).
- The commands exit with code 0.

### Step 12.3: Seed the test collection

Run this block as a separate `bash` script, not in the shared session.

```bash
QDRANT_REST="http://${QDRANT_HOST}:${QDRANT_REST_PORT}"

curl -sf -X PUT "${QDRANT_REST}/collections/${QDRANT_COLLECTION}" -H "Content-Type: application/json" \
  ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} \
  -d '{"vectors": {"size": '"${QDRANT_VECTOR_DIMENSION}"', "distance": "Cosine"}}' > /dev/null \
  && echo "PASS: collection created" || { echo "FAIL: collection not created"; exit 1; }

curl -sf -X PUT "${QDRANT_REST}/collections/${QDRANT_COLLECTION}/points?wait=true" \
  -H "Content-Type: application/json" ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} \
  -d '{"points": [
        {"id": 1, "vector": [0.1, 0.2, 0.3, 0.4], "payload": {"content": "Apache Camel integration framework"}},
        {"id": 2, "vector": [0.5, 0.6, 0.7, 0.8], "payload": {"content": "Qdrant vector database"}},
        {"id": 3, "vector": [0.9, 0.1, 0.2, 0.3], "payload": {"content": "Wanaku governed action proxy"}}
      ]}' > /dev/null \
  && echo "PASS: test vectors inserted" || { echo "FAIL: test vectors not inserted"; exit 1; }

POINTS=$(curl -sf "${QDRANT_REST}/collections/${QDRANT_COLLECTION}" ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} \
  | jq '.result.points_count')
[ "${POINTS:-0}" -ge 3 ] && echo "PASS: collection has ${POINTS} points" || echo "FAIL: expected 3 points, got ${POINTS}"
```

**Expected result:**

- The output includes `PASS: collection created`.
- The output includes `PASS: test vectors inserted`.
- The output includes `PASS: collection has <POINTS> points`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step 12.4: Write the instantiation properties

Barn substitutes values by property key. The keys must match the keys in `service.properties`.

```bash run
cat > "${TEMPLATE_PROPS_FILE}" <<EOF
qdrant.host=${QDRANT_HOST}
qdrant.port=${QDRANT_GRPC_PORT}
qdrant.collection=${QDRANT_COLLECTION}
qdrant.api.key=${QDRANT_API_KEY}
EOF
[ -s "${TEMPLATE_PROPS_FILE}" ] && echo "PASS: properties written" || echo "FAIL: properties file is empty"
```

**Expected result:**

- The output includes `PASS: properties written`.
- The output contains no line starting with `FAIL:`.

### Test 12.5: Instantiate the template

Run Step S.1 and Tests C.1 to C.3 of [common/service-template-checks.md](common/service-template-checks.md).

**Expected result:**

- Every line of the invoked steps starts with `PASS:`.
- The catalog `<TEMPLATE_CATALOG>` is in the catalog list.
- The catalog `<TEMPLATE_CATALOG>` contains the supplied Qdrant host, port, collection and API key.
- The output contains no line starting with `FAIL:`.

### Step 12.6: Set the CIC runtime inputs

The CIC of this phase uses its own container name and forward name. `CATALOG_CIC_NAME` keeps the name of the Phase 11
runtime for the cleanup.

```bash run
export CATALOG_CIC_NAME="${CIC_NAME}"
export CIC_NAME="${QDRANT_CIC_NAME}"
export CIC_CATALOG="${TEMPLATE_CATALOG}"
export CIC_CATALOG_SYSTEM="${TEMPLATE_SYSTEM}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 12.7: Start the CIC runtime

Use the values set in Step 12.6.

Follow [common/start-cic.md](common/start-cic.md).

The CIC uses host networking, so `QDRANT_HOST=localhost` reaches the Qdrant container of Step 12.2.

**Expected result:**

- The forward `<CIC_NAME>` is registered in `<CIC_NAMESPACE>`.

### Test 12.8: The tool is registered in Wanaku Server

Run Tests D.1 to D.3 of [common/service-template-checks.md](common/service-template-checks.md).

**Expected result:**

- `qdrant-similarity-search` is in `wanaku tools list` with the type `mcp-forward` and the input schema property
  `query`.
- `qdrant-similarity-search` is listed at `<WANAKU_MCP_BASE_URL>/<CIC_NAMESPACE>/mcp`.
- The output contains no line starting with `FAIL:`.

### Test 12.9: Pre-flight check for the tool call

```bash run
PHASE12_SKIP=0
curl -sf "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/healthz" > /dev/null 2>&1 \
  || { echo "SKIP: Qdrant is not reachable"; PHASE12_SKIP=1; }
[ "${PHASE12_SKIP}" -eq 0 ] && echo "PASS: Qdrant tool call pre-flight checks passed"
```

**Expected result:**

- The output includes `PASS: Qdrant tool call pre-flight checks passed` or `SKIP: Qdrant is not reachable`.

### Test 12.10: The call reaches Qdrant through Wanaku Server

The check passes when Wanaku Server forwards the call and the CIC runs the route. A Qdrant error about the query
format proves the same path. A governance denial, a connection error or an unknown tool fails the check.

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "query=vector database" 2>&1)
echo "${OUTPUT}"
if echo "${OUTPUT}" | grep -Eqi "governance|no_match|unable to connect|tool not found|unknown tool"; then
  echo "FAIL: the call did not reach the CIC route"
else
  echo "PASS: the call reached the CIC route"
fi
```

**Expected result:**

- The output includes `PASS: the call reached the CIC route`.
- The output contains no line starting with `FAIL:`.

### Test 12.11: The search returns the seeded payloads

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain \
  --name "${TEMPLATE_TOOL}" --param "query=[0.5 0.6 0.7 0.8]" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -Eqi "Camel|Qdrant|Wanaku" \
  && echo "PASS: search returned seeded payloads" \
  || echo "FAIL: search did not return seeded payloads (known gap: the route has no embedding step)"
```

**Expected result:**

- The output includes `PASS: search returned seeded payloads`.
- The output contains no line starting with `FAIL:`.

### Test 12.12: The tool is not served in another namespace (negative)

Wanaku Server serves a tool only in the namespace of its forward.

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool list --uri "${WANAKU_MCP_BASE_URL}/qdrant-other-ns/mcp" \
  --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "${TEMPLATE_TOOL}" \
  && echo "FAIL: tool is visible in another namespace" || echo "PASS: tool is not visible in another namespace"
```

**Expected result:**

- The output includes `PASS: tool is not visible in another namespace`.
- The output contains no line starting with `FAIL:`.

### Step 12.13: Stop the CIC runtime

Run the Shutdown section of [common/start-cic.md](common/start-cic.md):

```bash run
java -jar "${CLI_JAR}" forwards remove --host "${WANAKU_SERVER_URL}" --no-auth \
  --name "${CIC_NAME:-cic-test}" 2>/dev/null || true
${CONTAINER_RUNTIME} stop "${CIC_NAME:-cic-test}" > /dev/null 2>&1 || true
${CONTAINER_RUNTIME} rm "${CIC_NAME:-cic-test}" > /dev/null 2>&1 || true
echo "PASS: CIC runtime stopped and its forward removed"
```

**Expected result:**

- The output includes `PASS: CIC runtime stopped and its forward removed`.

## Phase 13: Negative Tests

### Test 13.1: Init without --name fails

```bash run
cd "${TEST_WORK_DIR}"
java -jar "${CLI_JAR}" service init --plain --services=a,b > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: init without --name rejected" || { echo "FAIL: init without --name accepted"; rm -rf a b; }
```

**Expected result:**

- The output includes `PASS: init without --name rejected`.
- The output contains no line starting with `FAIL:`.

### Test 13.2: Init without --services fails

```bash run
java -jar "${CLI_JAR}" service init --plain --name=should-fail > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: init without --services rejected" || { echo "FAIL: init without --services accepted"; rm -rf should-fail; }
```

**Expected result:**

- The output includes `PASS: init without --services rejected`.
- The output contains no line starting with `FAIL:`.

### Test 13.3: Init into an existing directory fails

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service init --plain --name="${TEST_CATALOG_NAME}" --services=x 2>&1)
[ $? -ne 0 ] && echo "${OUTPUT}" | grep -q "already exists" \
  && echo "PASS: init into an existing directory rejected" || echo "FAIL: init into an existing directory accepted"
```

**Expected result:**

- The output includes `PASS: init into an existing directory rejected`.
- The output contains no line starting with `FAIL:`.

### Test 13.4: Expose, package and deploy with a missing path fail

```bash run
for CMD in expose package; do
  OUTPUT=$(java -jar "${CLI_JAR}" service "${CMD}" --plain --path=/tmp/nonexistent-catalog-99999 2>&1)
  [ $? -ne 0 ] && echo "${OUTPUT}" | grep -q "index.properties not found" \
    && echo "PASS: ${CMD} with a missing path rejected" || echo "FAIL: ${CMD} with a missing path accepted"
done
java -jar "${CLI_JAR}" service deploy --plain --path=/tmp/nonexistent-catalog-99999 \
  --host "${WANAKU_BARN_URL}" --no-auth > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: deploy with a missing path rejected" || echo "FAIL: deploy with a missing path accepted"
```

**Expected result:**

- The output includes `PASS: expose with a missing path rejected`.
- The output includes `PASS: package with a missing path rejected`.
- The output includes `PASS: deploy with a missing path rejected`.
- The output contains no line starting with `FAIL:`.

### Test 13.5: Commands against an unreachable host fail

```bash run
for ARGS in "service catalog list" "service template list"; do
  OUTPUT=$(java -jar "${CLI_JAR}" ${ARGS} --plain --host "http://localhost:59999" --no-auth 2>&1)
  [ $? -ne 0 ] && echo "${OUTPUT}" | grep -q "Unable to connect" \
    && echo "PASS: '${ARGS}' with an unreachable host rejected" || echo "FAIL: '${ARGS}' with an unreachable host accepted"
done
java -jar "${CLI_JAR}" service deploy --plain --path="${CATALOG_DIR}" --host "http://localhost:59999" --no-auth > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: deploy with an unreachable host rejected" || echo "FAIL: deploy with an unreachable host accepted"
```

**Expected result:**

- The output includes `PASS: 'service catalog list' with an unreachable host rejected`.
- The output includes `PASS: 'service template list' with an unreachable host rejected`.
- The output includes `PASS: deploy with an unreachable host rejected`.
- The output contains no line starting with `FAIL:`.

### Test 13.6: Instantiate an unknown template fails

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template instantiate --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name nonexistent-template-99999 --property key=value 2>&1)
[ $? -ne 0 ] && echo "${OUTPUT}" | grep -q "does not exist" \
  && echo "PASS: unknown template rejected" || echo "FAIL: unknown template accepted"
```

**Expected result:**

- The output includes `PASS: unknown template rejected`.
- The output contains no line starting with `FAIL:`.

### Test 13.7: Instructions without --name, without --model, or for an unknown catalog fail

```bash run
java -jar "${CLI_JAR}" service instructions --plain --host "${WANAKU_BARN_URL}" --no-auth --model=local > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: instructions without --name rejected" || echo "FAIL: instructions without --name accepted"
java -jar "${CLI_JAR}" service instructions --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name="${TEST_CATALOG_NAME}" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: instructions without --model rejected" || echo "FAIL: instructions without --model accepted"
java -jar "${CLI_JAR}" service instructions --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name=nonexistent-catalog-99999 --model=local > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: instructions for an unknown catalog rejected" \
  || echo "FAIL: instructions for an unknown catalog accepted"
```

**Expected result:**

- The output includes `PASS: instructions without --name rejected`.
- The output includes `PASS: instructions without --model rejected`.
- The output includes `PASS: instructions for an unknown catalog rejected`.
- The output contains no line starting with `FAIL:`.

### Test 13.8: Catalog remove and restore with bad input fail

```bash run
java -jar "${CLI_JAR}" service catalog remove --plain --host "${WANAKU_BARN_URL}" --no-auth > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: remove without --name rejected" || echo "FAIL: remove without --name accepted"
java -jar "${CLI_JAR}" service catalog remove --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name=nonexistent-catalog-99999 > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: remove of an unknown catalog rejected" || echo "FAIL: remove of an unknown catalog accepted"
java -jar "${CLI_JAR}" service restore --plain --host "${WANAKU_BARN_URL}" --no-auth \
  --name=nonexistent-catalog-99999 > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: restore of an unknown catalog rejected" || echo "FAIL: restore of an unknown catalog accepted"
```

**Expected result:**

- The output includes `PASS: remove without --name rejected`.
- The output includes `PASS: remove of an unknown catalog rejected`.
- The output includes `PASS: restore of an unknown catalog rejected`.
- The output contains no line starting with `FAIL:`.

### Test 13.9: The service catalog API is not on Wanaku Server

This test confirms that the `service` commands need `--host "${WANAKU_BARN_URL}"`.

```bash run
java -jar "${CLI_JAR}" service catalog list --plain --host "${WANAKU_SERVER_URL}" --no-auth > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: Wanaku Server does not serve the service catalog API" \
  || echo "FAIL: service catalog list succeeded against Wanaku Server"
```

**Expected result:**

- The output includes `PASS: Wanaku Server does not serve the service catalog API`.
- The output contains no line starting with `FAIL:`.

## Phase 14: Cleanup

### Step 14.1: Stop the CIC runtimes

The commands repeat the CIC shutdown of Steps 11.5 and 12.13 for both runtimes. They are idempotent.

```bash run
for NAME in "${CATALOG_CIC_NAME:-${CIC_NAME}}" "${QDRANT_CIC_NAME}"; do
  java -jar "${CLI_JAR}" forwards remove --host "${WANAKU_SERVER_URL}" --no-auth --name "${NAME}" 2>/dev/null || true
  ${CONTAINER_RUNTIME} stop "${NAME}" > /dev/null 2>&1 || true
  ${CONTAINER_RUNTIME} rm "${NAME}" > /dev/null 2>&1 || true
done
echo "PASS: CIC runtimes stopped and their forwards removed"
```

**Expected result:**

- The output includes `PASS: CIC runtimes stopped and their forwards removed`.

### Step 14.2: Remove the catalogs

```bash run
for NAME in "${TEST_CATALOG_NAME}" "${TEST_ECHO_CATALOG}" "${TEMPLATE_CATALOG}"; do
  java -jar "${CLI_JAR}" service catalog remove --plain --host "${WANAKU_BARN_URL}" --no-auth \
    --name="${NAME}" > /dev/null 2>&1 || true
done
OUTPUT=$(java -jar "${CLI_JAR}" service catalog list --plain --host "${WANAKU_BARN_URL}" --no-auth 2>&1)
echo "${OUTPUT}" | grep -Eq "^(${TEST_CATALOG_NAME}|${TEST_ECHO_CATALOG}|${TEMPLATE_CATALOG})[[:space:]]" \
  && echo "FAIL: test catalogs still listed" || echo "PASS: test catalogs removed"
```

**Expected result:**

- The output includes `PASS: test catalogs removed`.
- The output contains no line starting with `FAIL:`.

### Step 14.3: Remove the Qdrant data

The commands are idempotent. They also run when Phase 12 was skipped.

```bash run
curl -s -X DELETE "http://${QDRANT_HOST}:${QDRANT_REST_PORT}/collections/${QDRANT_COLLECTION}" \
  ${QDRANT_API_KEY:+-H "api-key: ${QDRANT_API_KEY}"} > /dev/null 2>&1 || true
${CONTAINER_RUNTIME} rm -f "${QDRANT_CONTAINER}" > /dev/null 2>&1 || true
echo "PASS: Qdrant test data removed"
```

**Expected result:**

- The output includes `PASS: Qdrant test data removed`.

### Step 14.4: Remove the temporary files

`TEST_WORK_DIR` also holds `TEMPLATE_WORK_DIR` and the default `TEMPLATE_PROPS_FILE`.

```bash run
cd "${WANAKU_REPO_ROOT}"
rm -rf "${TEST_WORK_DIR}"
echo "PASS: temporary files removed"
```

**Expected result:**

- The output includes `PASS: temporary files removed`.

### Step 14.5: Stop the processes

Run the Shutdown sections of [common/start-barn.md](common/start-barn.md) and
[common/start-server.md](common/start-server.md).

```bash run
if [ -n "${WANAKU_BARN_PID}" ]; then
  kill "${WANAKU_BARN_PID}" 2>/dev/null || true
  wait "${WANAKU_BARN_PID}" 2>/dev/null || true
  echo "PASS: Wanaku Barn stopped"
fi
rm -rf "${WANAKU_BARN_HOME}"
```

**Expected result:**

- If `WANAKU_BARN_PID` is set, the output includes `PASS: Wanaku Barn stopped`.

## Test Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| S | S.1 | Check prerequisites | Setup |
| S | S.2 | Set environment variables | Setup |
| 1 | 1.1-1.3 | Start Barn and Wanaku Server (test governance), detect container runtime | Critical |
| 2 | 2.1-2.4 | Catalog init and structure | Critical |
| 3 | 3.1-3.2 | Write `ai-tool:` routes | Critical |
| 4 | 4.1-4.2 | Generate rules with expose | Medium |
| 5 | 5.1-5.2 | Barn validation (valid and invalid catalog) | High |
| 6 | 6.1-6.2 | Package into a Base64-encoded ZIP | High |
| 7 | 7.1-7.2 | Deploy to Barn and list | Critical |
| 8 | 8.1-8.3 | Remove, listed as removed, restore | High |
| 9 | 9.1-9.3 | Template list, echo-tool instantiation, substituted value | High |
| 10 | 10.1 | Local deployment instructions | Medium |
| 11 | 11.0-11.5 | Serve through the CIC, tool in Wanaku Server, MCP call, forward removal | Critical |
| 12 | 12.0-12.4 | Qdrant pre-flight, template variables, Qdrant start and seed, instantiation properties | Setup |
| 12 | 12.5 | Instantiate `camel-qdrant-search-tool` and verify the substituted values | High |
| 12 | 12.6-12.8 | Serve the Qdrant catalog through the CIC and verify the tool in Wanaku Server | High |
| 12 | 12.9-12.10 | MCP call reaches the CIC route and Qdrant | High |
| 12 | 12.11 | Search returns seeded payloads (known gap) | Medium |
| 12 | 12.12 | Tool not served in another namespace (negative) | High |
| 12 | 12.13 | Stop the Qdrant CIC runtime | Setup |
| 13 | 13.1-13.9 | Negative tests (missing arguments, bad paths, unreachable host, unknown names, wrong host) | High |
| 14 | 14.1-14.5 | Cleanup (CIC runtimes, catalogs, Qdrant, files, processes) | Critical |
