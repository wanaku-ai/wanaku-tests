# Test Plan: Wanaku MCP CLI Commands

## Overview

This test plan verifies the `wanaku mcp` CLI commands. These commands talk to an MCP endpoint directly. The plan points
them at the MCP endpoint of **Wanaku Server** (`${WANAKU_MCP_BASE_URL}/<namespace>/mcp`). The tools, resources and
prompts come from the supplied infra-remediation MCP test fixture, which is registered with Wanaku Server as a forward. Thus the
`list`, `call`, `read` and `get` operations return real data.

This plan covers tool/resource/prompt operations, namespace isolation, governance, enable/disable, the forward
lifecycle across a server restart, evaluator updates and rollback through the CLI, and error handling against a builtin or existing Wanaku Server. All steps are
automatable.

The plan does not test help output, option validation or argument conversion. The CLI unit tests in
`wanaku-ai/wanaku-barn` (`apps/wanaku-cli`) cover them.
The server needs a reachable infra-remediation MCP test fixture; it does not use Wanaku Barn, Keycloak or oauth2-proxy.

For operator deployment and authenticated MCP operations on OpenShift, use
[openshift-mcp-deployment.md](openshift-mcp-deployment.md).

## Prerequisites

### Required tools

| Tool              | Minimum version                                                  | Verify command               |
|-------------------|------------------------------------------------------------------|------------------------------|
| `wanaku` CLI      | installed executable or local build                              | `wanaku --version`           |
| `java`            | 21+, only for direct jar invocation                              | `java -version`              |
| `wanaku-server`   | installed in the worker image; builtin mode only                 | `command -v wanaku-server`   |
| `test-mcp-server` | installed fixture launcher; only when no fixture URL is supplied | `command -v test-mcp-server` |
| `curl`            | any                                                              | `curl --version`             |
| `timeout`         | GNU coreutils; required in the worker image                      | `timeout --version`          |
| `jq`              | 1.6+                                                             | `jq --version`               |
| `base64`          | GNU coreutils; builtin evaluator scenario                         | `base64 --version`           |

### CLI invocation

The plan calls the CLI as `wanaku`. Step S.1 prefers an installed executable on `PATH`, including the
`/usr/local/bin/wanaku` launcher supplied by the Saci test image. Its Linux x86_64 native CLI does not require
Java; on other architectures the image launcher provides its JVM invocation. Set `CLI_JAR` explicitly to
use a particular jar instead. If no installed executable is available, the plan falls back to the local build jar.
Run the steps in the same shell so the selected invocation remains available.

Do not put a full command in a variable (for example `WANAKU_CLI="java -jar ..."`); `zsh` does not split it into words.

### How to read MCP command output

- The `wanaku mcp` commands write results to stdout. Capture stdout and stderr separately. When the CLI talks to an
  upstream server that sends SSE frames, the MCP client library can write `WARN` log lines with Java stack traces to
  stderr. These lines are not errors of the command.
- `wanaku mcp ... list` prints one line per entry: `<name> <description>` for tools and prompts, and
  `<name> <uri> <mimeType>` for resources. An empty list prints `No tools found` (or `No resources found`,
  `No prompts found`) and exits with `0`.
- A failed call prints the error message and exits with `1`. Missing picocli-required options (for example `--uri`
  on `list` subcommands) exit with `2`.
- The CLI sends every `--param` and `--arg` value as a JSON **string**. The supplied Quarkus fixture converts
  numeric strings for integer tool arguments, so `scaleDeployment --param replicas=2` succeeds. A non-numeric
  value must be rejected; do not assume every upstream schema handles strings the same way.

### Known limitations

- Wanaku Server is fail-safe. It denies `tools/call`, `resources/read` and `prompts/get` in a namespace unless the
  governance configuration or an action policy allows them. The denial message is
  `No governance policy permits this action.` (action-policy filter, `-32003`) or `evaluator: no_match` (evaluator
  filter). Listing requests are not governed. For a builtin server, this plan prepares the test governance configuration of
  [common/start-server.md](common/start-server.md) (step 2b).
- Tool, resource and prompt names are unique in the whole Wanaku Server registry, not per namespace. Do not register
  the same upstream server twice in one server. The second discovery moves the entries to the namespace of the second
  forward.

---

## Environment variables

Set variables before Step S.2 to override these defaults. Keep exports and ownership variables in the shared shell.

| Variable                                                                | Purpose                                                                       | Default                                                  |
|-------------------------------------------------------------------------|-------------------------------------------------------------------------------|----------------------------------------------------------|
| `CLI_JAR`                                                               | Explicit jar override; otherwise PATH CLI or local build fallback             | Unset                                                    |
| `WANAKU_SERVER_MODE`                                                    | `builtin` launches the installed server binary; `existing` reuses an instance | `builtin`                                                |
| `WANAKU_SERVER_MGMT_PORT`                                               | Builtin management listener port                                              | `8080`                                                   |
| `WANAKU_SERVER_URL`                                                     | Management API base URL                                                       | Builtin: `http://localhost:8080`; required for existing  |
| `WANAKU_MCP_BASE_URL`                                                   | MCP listener base URL, without namespace/path                                 | Builtin: `http://localhost:8081`; required for existing  |
| `MCP_RUN_ID`                                                            | Unique run suffix                                                             | Timestamp and shell PID                                  |
| `MCP_NAMESPACE` / `MCP_ISOLATED_NAMESPACE` / `MCP_UNGOVERNED_NAMESPACE` | New, distinct test namespaces                                                 | Run-specific DNS labels                                  |
| `TEST_MCP_FORWARD`                                                      | Name of this run's forward                                                    | `infra-remediation-${MCP_RUN_ID}`                               |
| `TEST_MCP_URL`                                                          | Running infra-remediation MCP fixture URL reachable from Wanaku Server                | Launch the installed fixture when unset; `TEST_MCP_ADVERTISED_URL` accepted as fallback |
| `TEST_MCP_PORT`                                                         | Port for the fixture launched by this plan                                    | `8181` |

Example before setup: `export TEST_MCP_URL=http://fixture:8181/mcp` (replace `fixture` with the reachable fixture
service name). When the fixture runs beside the builtin server in the same worker container, use its localhost URL.
When no fixture URL is supplied, Step 1.3 launches the container's `test-mcp-server` directly and uses
`http://localhost:${TEST_MCP_PORT:-8181}/mcp`. Cleanup stops only the fixture launched by this run.
An existing Wanaku Server must be able to reach the worker's localhost endpoint for this default to work;
otherwise supply a reachable fixture URL.

The test fixture forward goes into `MCP_NAMESPACE`. `MCP_ISOLATED_NAMESPACE` is governed but has no forward.
`MCP_UNGOVERNED_NAMESPACE` has the fail-safe defaults.

Minimal existing-instance configuration before Phase S (replace the example service names; this example is not an
additional step to run in builtin mode):

```bash
export WANAKU_SERVER_MODE=existing
export WANAKU_SERVER_URL=http://wanaku-test:8080
export WANAKU_MCP_BASE_URL=http://wanaku-test:8081
export TEST_MCP_URL=http://fixture:8181/mcp
```

`TEST_MCP_URL` is the upstream fixture endpoint registered with the server. `TEST_MCP_FORWARD` is only a forward
**name**, generated per run by default; do not put a URL in it.

## Phase S: Setup

### Step S.1: Define the CLI invocation

```bash run
# An explicit CLI_JAR overrides PATH; otherwise prefer the installed launcher.
WANAKU_CLI_EXEC="$(unset -f wanaku 2>/dev/null; command -v wanaku 2>/dev/null || true)"
if [ -n "${CLI_JAR:-}" ]; then
  WANAKU_CLI_MODE=jar
elif [ -n "${WANAKU_CLI_EXEC}" ] && [ -x "${WANAKU_CLI_EXEC}" ]; then
  WANAKU_CLI_MODE=executable
else
  CLI_JAR=apps/wanaku-cli/target/quarkus-app/quarkus-run.jar
  WANAKU_CLI_MODE=jar
fi
if [ "${WANAKU_CLI_MODE}" = jar ]; then
  [ -r "${CLI_JAR}" ] \
    || { echo "FAIL [S.1]: selected jar missing or unreadable: ${CLI_JAR}. Install wanaku or set CLI_JAR to a built jar."; return 1; }
  command -v java > /dev/null 2>&1 \
    || { echo "FAIL [S.1]: jar invocation requires Java 21+ on PATH"; return 1; }
  wanaku() { java -jar "${CLI_JAR}" "$@"; }
else
  wanaku() { "${WANAKU_CLI_EXEC}" "$@"; }
fi
wanaku --version \
  && echo "PASS [S.1]: selected ${WANAKU_CLI_MODE} CLI runs" \
  || { echo "FAIL [S.1]: selected ${WANAKU_CLI_MODE} CLI failed --version; inspect the error above (launcher, permissions or Java compatibility)."; return 1; }
```

**Expected result:** The selected CLI prints its version and `PASS [S.1]`; setup stops with a useful diagnostic
if the executable/jar cannot run. Java is required only when invoking a jar directly.

### Step S.2: Select the server mode and test environment

```bash run run
# builtin: launch wanaku-server in this worker; existing: use an already running server.
export WANAKU_SERVER_MODE="${WANAKU_SERVER_MODE:-builtin}"
export MCP_RUN_ID="${MCP_RUN_ID:-$(date +%s)-$$}"
export MCP_NAMESPACE="${MCP_NAMESPACE:-mcp-${MCP_RUN_ID}}"
export MCP_ISOLATED_NAMESPACE="${MCP_ISOLATED_NAMESPACE:-mcp-isolated-${MCP_RUN_ID}}"
export MCP_UNGOVERNED_NAMESPACE="${MCP_UNGOVERNED_NAMESPACE:-mcp-ungoverned-${MCP_RUN_ID}}"
export TEST_MCP_FORWARD="${TEST_MCP_FORWARD:-infra-remediation-${MCP_RUN_ID}}"
export WANAKU_SERVER_MGMT_PORT="${WANAKU_SERVER_MGMT_PORT:-8080}"
export MCP_UNREACHABLE_URI="${MCP_UNREACHABLE_URI:-http://localhost:59999/default/mcp}"
export WANAKU_TEST_GOVERNED_NAMESPACES="${MCP_NAMESPACE},${MCP_ISOLATED_NAMESPACE}"
# Ownership is recorded only after successful creation, for partial-run cleanup.
MCP_CREATED_NAMESPACES=""
MCP_CREATED_FORWARD=false
MCP_CREATED_SERVER=false
MCP_CREATED_SERVER_DATA_DIR=""
WANAKU_SERVER_PID=""
MCP_CREATED_FIXTURE=false
MCP_EVALUATOR_DATA_DIR=""
MCP_FIXTURE_PID=""
MCP_FIXTURE_DATA_DIR=""
# Set by the first block of Step 1.3 when the fixture is ready; guards the forward registration.
MCP_FIXTURE_READY=false
export TEST_MCP_URL="${TEST_MCP_URL:-${TEST_MCP_ADVERTISED_URL:-}}"
# Accept the supplied fixture origin as shorthand, preserving explicit endpoint paths.
TEST_MCP_URL="${TEST_MCP_URL%/}"
case "${TEST_MCP_URL}" in
  http://*|https://*)
    case "${TEST_MCP_URL#*://}" in
      */*) ;;
      *) TEST_MCP_URL="${TEST_MCP_URL}/mcp" ;;
    esac
    ;;
esac
```

**Expected result:**

- The commands exit with code 0 (inferred).

## Phase 0: Prerequisites

### Test 0.1: Verify the required tools

```bash run
for CMD in curl jq timeout; do
  command -v "${CMD}" > /dev/null 2>&1 && echo "PASS [0.1]: ${CMD} available" || echo "FAIL [0.1]: ${CMD} not found"
done
wanaku --version > /dev/null 2>&1 && echo "PASS [0.1]: wanaku CLI available" || echo "FAIL [0.1]: wanaku CLI not available"
```

**Expected result:**

- The output includes `PASS [0.1]: curl available`.
- The output includes `PASS [0.1]: jq available`.
- The output includes `PASS [0.1]: timeout available`.
- The output includes `PASS [0.1]: wanaku CLI available`.
- The output contains no line starting with `FAIL`.

---

## Phase 1: Environment Setup

### Step 1.1: Launch the builtin server OR connect to an existing instance

Run setup and cleanup in the same shell so ownership variables survive. Stop on any failed setup check.
This plan uses unauthenticated, trusted test endpoints (`--no-auth`). For authenticated deployments, use
[openshift-mcp-deployment.md](openshift-mcp-deployment.md).
Namespace overrides must be distinct DNS labels of at most 63 characters and absent before this run.

**Builtin mode (`WANAKU_SERVER_MODE=builtin`, default):** Use the `wanaku-server` binary already installed in
the worker image. It runs directly in the same worker container as the CLI. No nested container runtime or image
pull is needed. Management and MCP URLs are set automatically. Both listener ports must be free; never terminate an
unrelated listener to free a port. The MCP port is fixed at `8081` by the embedded server pipeline.

The following block prepares the namespace-scoped governance configuration described in
[common/start-server.md](common/start-server.md#step-2b-optional-prepare-the-test-governance-configuration) and starts
the installed binary. Run this block directly; no other startup procedure is needed.

```bash run
case "${WANAKU_SERVER_MODE}" in
  builtin)
    WANAKU_SERVER_BIN=$(command -v wanaku-server) \
      || { echo "FAIL [1.1]: builtin mode requires wanaku-server on PATH"; return 1; }
    for PORT in "${WANAKU_SERVER_MGMT_PORT}" 8081; do
      if curl --connect-timeout 1 --max-time 2 -s -o /dev/null "http://localhost:${PORT}/"; then
        echo "FAIL [1.1]: builtin listener port already in use"; return 1
      fi
    done
    export WANAKU_SERVER_DATA_DIR="$(mktemp -d)"
    MCP_CREATED_SERVER_DATA_DIR="${WANAKU_SERVER_DATA_DIR}"
    export WANAKU_SERVER_CONFIG="${WANAKU_SERVER_DATA_DIR}/wanaku.yaml"
    {
      # An empty evaluator list makes the server start with an active evaluator revision, which Test 5.6 captures.
      echo "evaluators: []"
      echo "governance:"
      echo "  default:"
      echo "    mode: enforce"
      echo "    no_match: deny"
      echo "    on_failure: deny"
      echo "  namespaces:"
      for NS in $(echo "${WANAKU_TEST_GOVERNED_NAMESPACES}" | tr ',' ' '); do
        echo "    ${NS}:"
        echo "      no_match: allow"
      done
    } > "${WANAKU_SERVER_CONFIG}"
    export WANAKU_SERVER_URL="http://localhost:${WANAKU_SERVER_MGMT_PORT}"
    export WANAKU_MCP_BASE_URL="http://localhost:8081"
    export WANAKU_SERVER_LOG="${WANAKU_SERVER_DATA_DIR}/wanaku-server.log"
    WANAKU_MGMT_LISTEN="0.0.0.0:${WANAKU_SERVER_MGMT_PORT}" \
    WANAKU_PERSIST_BACKEND=file WANAKU_PERSIST_PATH="${WANAKU_SERVER_DATA_DIR}" \
      "${WANAKU_SERVER_BIN}" --wanaku-config "${WANAKU_SERVER_CONFIG}" \
      < /dev/null > "${WANAKU_SERVER_LOG}" 2>&1 &
    WANAKU_SERVER_PID=$!
    MCP_CREATED_SERVER=true
    echo "PASS [1.1]: builtin server process launched"
    ;;
  existing) ;;
  *) echo "FAIL [1.1]: mode must be builtin or existing"; return 1 ;;
esac
```

The background process uses `/dev/null` for stdin and a file for stdout/stderr, so it does not retain the worker's
captured input/output streams. Readiness below is bounded to about 30 seconds; each registry query has a 10-second
limit plus a 2-second termination grace period. Step 1.1 should complete within about 85 seconds, including port
checks. Run Phase 8 cleanup after any failure.

**Existing mode (`WANAKU_SERVER_MODE=existing`):** Set both `WANAKU_SERVER_URL` (management API base URL)
and `WANAKU_MCP_BASE_URL` (MCP listener base URL, without a namespace or `/mcp`). These may have different hosts,
ports or schemes. No local server binary or server PID is required. Do not start, restart or reconfigure
the instance from this plan. Its owner must provision governance allowing calls in `MCP_NAMESPACE` and
`MCP_ISOLATED_NAMESPACE`, with fail-safe denial in `MCP_UNGOVERNED_NAMESPACE`, before running the tests.
If unavailable, mark the affected governance/call tests BLOCKED, never PASS.

The tests modify global registry entries (including disabling `restartService`). Use a dedicated empty test instance for the
run. Unique namespaces alone cannot prevent the global name collisions described above. Inspect the lists below
and stop if any tools, resources, prompts or forwards exist. Do not remove other users' entries to satisfy this
prerequisite.

```bash run
case "${WANAKU_SERVER_MODE}" in
  builtin)
    [ "${MCP_CREATED_SERVER:-false}" = true ] \
      || { echo "FAIL [1.1]: the first block did not launch the builtin server"; return 1; }
    ;;
  existing)
    [ -n "${WANAKU_SERVER_URL:-}" ] || { echo "FAIL [1.1]: set WANAKU_SERVER_URL to the management API base URL"; return 1; }
    [ -n "${WANAKU_MCP_BASE_URL:-}" ] || { echo "FAIL [1.1]: set WANAKU_MCP_BASE_URL to the MCP listener base URL"; return 1; }
    echo "PASS [1.1]: existing server selected (${WANAKU_SERVER_URL})"
    ;;
  *) echo "FAIL [1.1]: mode must be builtin or existing"; return 1 ;;
esac
for ATTEMPT in $(seq 1 15); do
  if [ "${MCP_CREATED_SERVER:-false}" = true ] && ! kill -0 "${WANAKU_SERVER_PID}" 2>/dev/null; then
    echo "FAIL [1.1]: builtin server exited during startup"
    tail -n 20 "${WANAKU_SERVER_LOG}"
    return 1
  fi
  if curl --connect-timeout 1 --max-time 1 -fsS "${WANAKU_SERVER_URL}/healthz" > /dev/null 2>&1; then
    echo "PASS [1.1]: management endpoint ready"
    break
  fi
  if [ "${ATTEMPT}" -eq 15 ]; then
    echo "FAIL [1.1]: management endpoint unavailable after bounded readiness wait"
    [ "${MCP_CREATED_SERVER:-false}" != true ] || tail -n 20 "${WANAKU_SERVER_LOG}"
    return 1
  fi
  sleep 1
done
for KIND in tools resources prompts forwards; do
  # timeout needs an executable, so invoke the route selected in S.1 directly.
  if [ "${WANAKU_CLI_MODE}" = jar ]; then
    timeout --kill-after=2s 10s java -jar "${CLI_JAR}" \
      "${KIND}" list --host "${WANAKU_SERVER_URL}" --no-auth --plain
  else
    timeout --kill-after=2s 10s "${WANAKU_CLI_EXEC}" \
      "${KIND}" list --host "${WANAKU_SERVER_URL}" --no-auth --plain
  fi
  RESULT=$?
  [ "${RESULT}" -eq 0 ] \
    || { echo "FAIL [1.1]: ${KIND} registry inspection failed or timed out (exit ${RESULT})"; return 1; }
done
echo "PASS [1.1]: server setup complete (mode ${WANAKU_SERVER_MODE})"
```

Use the recorded outputs of the `run` blocks for assessment. Do not rerun startup or wait for the background server
to exit; it must remain running for later steps. After comparing the recorded evidence with the criteria below,
submit the step assessment.

**Expected result:**

- Both `run` blocks exit with code 0.
- The output includes `PASS [1.1]: management endpoint ready`.
- The output includes `PASS [1.1]: server setup complete (mode <WANAKU_SERVER_MODE>)` for the selected mode.
- The output contains no line starting with `FAIL`.

### Step 1.2: Create the test namespaces

```bash run
EXISTING_NAMESPACES=$(wanaku namespaces list --host "${WANAKU_SERVER_URL}" --no-auth --plain) \
  || { echo "FAIL [1.2]: namespace list failed"; return 1; }
for NS_VAR in MCP_NAMESPACE MCP_ISOLATED_NAMESPACE MCP_UNGOVERNED_NAMESPACE; do
  NS="${!NS_VAR}"
  # Creating an existing namespace does not fail, so check first: this run must not reuse a namespace
  if echo "${EXISTING_NAMESPACES}" | awk -F'\t' -v ns="${NS}" '$1 == ns { found = 1 } END { exit !found }'; then
    echo "FAIL [1.2]: namespace ${NS} already exists; use new namespace names"; return 1
  fi
  wanaku namespaces create "${NS}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null \
    || { echo "FAIL [1.2]: namespace creation failed; stop before registering the forward"; return 1; }
  MCP_CREATED_NAMESPACES="${MCP_CREATED_NAMESPACES} ${NS}"
  echo "PASS [1.2]: new namespace created for ${NS_VAR} (${NS})"
done
```

**Expected result:**

- The output includes `PASS [1.2]: new namespace created for MCP_NAMESPACE`.
- The output includes `PASS [1.2]: new namespace created for MCP_ISOLATED_NAMESPACE`.
- The output includes `PASS [1.2]: new namespace created for MCP_UNGOVERNED_NAMESPACE`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 1.3: Start or reuse the MCP fixture and register it as a forward

Provide `TEST_MCP_URL` before setup to reuse a running fixture, or leave it unset to launch the fixture
already installed in the worker container. Use the supplied `fixtures/test-mcp-server` fixture, implemented by
`InfraRemediationServer`. Its tools include `restartService`, `scaleDeployment` and `escalateTicket`; its static
resource is `safetyLimits` at `config://policies/safety-limits`; its prompts are `summarizeIncident` and
`draftEscalation`. These are simulated remediation operations in the test fixture.
It must use Streamable HTTP and be reachable **from Wanaku Server**, not just from the CLI worker.
Supply the MCP endpoint (for example `http://host.docker.internal:38181/mcp`). Step S.2 also accepts a bare
HTTP(S) origin and appends the fixture's `/mcp` path; an explicit endpoint path is preserved.
For builtin mode a fixture in the same worker can use localhost. For existing mode, localhost refers to the existing
server's machine/container; use a reachable fixture service URL instead. A fixture connection failure is a setup
failure, not a tool-list test failure. Supplied fixtures are never stopped by this plan. The automatically
launched fixture listens on `0.0.0.0` at `TEST_MCP_PORT` (default `8181`); no nested container is needed.

```bash run
if [ -z "${TEST_MCP_URL:-}" ]; then
  command -v test-mcp-server > /dev/null 2>&1 \
    || { echo "FAIL [1.3]: install test-mcp-server in the worker or provide TEST_MCP_URL"; return 1; }
  export TEST_MCP_PORT="${TEST_MCP_PORT:-8181}"
  case "${TEST_MCP_PORT}" in
    ''|*[!0-9]*) echo "FAIL [1.3]: TEST_MCP_PORT must be an integer"; return 1 ;;
  esac
  [ "${TEST_MCP_PORT}" -ge 1 ] && [ "${TEST_MCP_PORT}" -le 65535 ] \
    || { echo "FAIL [1.3]: TEST_MCP_PORT must be between 1 and 65535"; return 1; }
  export TEST_MCP_URL="http://localhost:${TEST_MCP_PORT}/mcp"
  if curl --connect-timeout 1 --max-time 2 -s -o /dev/null "${TEST_MCP_URL}"; then
    echo "FAIL [1.3]: fixture port already in use; provide TEST_MCP_URL or select a free TEST_MCP_PORT"; return 1
  fi
  MCP_FIXTURE_DATA_DIR=$(mktemp -d) \
    || { echo "FAIL [1.3]: cannot create fixture log directory"; return 1; }
  QUARKUS_HTTP_HOST=0.0.0.0 QUARKUS_HTTP_PORT="${TEST_MCP_PORT}" \
    test-mcp-server < /dev/null > "${MCP_FIXTURE_DATA_DIR}/fixture.log" 2>&1 &
  MCP_FIXTURE_PID=$!
  MCP_CREATED_FIXTURE=true
  for ATTEMPT in $(seq 1 30); do
    kill -0 "${MCP_FIXTURE_PID}" 2>/dev/null \
      || { echo "FAIL [1.3]: fixture exited; inspect ${MCP_FIXTURE_DATA_DIR}/fixture.log"; return 1; }
    if curl --connect-timeout 1 --max-time 2 -fsS \
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
      --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"wanaku-cli-plan","version":"1"}}}' \
      "${TEST_MCP_URL}" > "${MCP_FIXTURE_DATA_DIR}/initialize.out" 2>/dev/null \
      && sed -n -e 's/^data: *//p' -e '/^{/p' "${MCP_FIXTURE_DATA_DIR}/initialize.out" \
        | jq -e '.result.serverInfo and .result.capabilities' > /dev/null 2>&1; then
      echo "PASS [1.3]: launched fixture accepts MCP initialization"
      break
    fi
    [ "${ATTEMPT}" -lt 30 ] \
      || { echo "FAIL [1.3]: fixture startup timed out; inspect ${MCP_FIXTURE_DATA_DIR}/fixture.log"; return 1; }
    sleep 1
  done
fi
case "${TEST_MCP_URL}" in
  http://*|https://*) ;;
  *) echo "FAIL [1.3]: TEST_MCP_URL must use http:// or https://"; return 1 ;;
esac
export TEST_MCP_NAMESPACE="${MCP_NAMESPACE}"
MCP_FIXTURE_READY=true
```

Register and verify discovery below using the CLI selected in S.1, before any MCP CLI tests.
Record ownership immediately after successful registration, so cleanup also works if discovery fails.

```bash run
[ "${MCP_FIXTURE_READY:-false}" = true ] \
  || { echo "FAIL [1.3]: the fixture is not ready (see the first block); no forward registered"; return 1; }
wanaku forwards add --host "${WANAKU_SERVER_URL}" --no-auth \
  --name "${TEST_MCP_FORWARD}" --service "${TEST_MCP_URL}" --namespace "${TEST_MCP_NAMESPACE}" \
  || { echo "FAIL [1.3]: forward registration failed"; return 1; }
MCP_CREATED_FORWARD=true
for ATTEMPT in $(seq 1 30); do
  OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain) \
    || { echo "FAIL [1.3]: discovery query failed"; return 1; }
  if echo "${OUTPUT}" | grep -Eq '^restartService([[:space:]]|$)'; then
    echo "PASS [1.3]: restartService discovered"
    break
  fi
  [ "${ATTEMPT}" -lt 30 ] || { echo "FAIL [1.3]: fixture restartService not discovered; check server-to-fixture reachability"; return 1; }
  wanaku forwards refresh --host "${WANAKU_SERVER_URL}" --no-auth --name "${TEST_MCP_FORWARD}" > /dev/null 2>&1 || true
  sleep 1
done
```

Do not register another copy if discovery fails; fix connectivity and refresh this forward.

**Expected result:** When no fixture URL was supplied, the installed fixture starts and the output includes
`PASS [1.3]: launched fixture accepts MCP initialization`. Otherwise the supplied fixture is reused.
The test fixture is reachable from the selected instance, the run's forward is created,
and `restartService` is discovered. Step 1.4 also verifies the MCP listener through the CLI.

### Step 1.4: Set the MCP endpoint

```bash run
export MCP_URI="${WANAKU_MCP_BASE_URL}/${MCP_NAMESPACE}/mcp"
for ATTEMPT in $(seq 1 30); do
  OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain) \
    || { echo "FAIL [1.4]: MCP listener unavailable"; return 1; }
  if echo "${OUTPUT}" | grep -Eq '^restartService([[:space:]]|$)'; then
    echo "PASS [1.4]: MCP listener lists fixture restartService"
    break
  fi
  [ "${ATTEMPT}" -lt 30 ] || { echo "FAIL [1.4]: fixture restartService missing from test namespace; stop before MCP tests"; return 1; }
  sleep 1
done
```

**Expected result:**

- The output includes `PASS [1.4]: MCP listener lists fixture restartService`; no FAIL is printed.

---

## Phase 2: Tool Operations

### Test 2.1: List the tools of the namespace

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS [2.1]: tool list exit 0" || echo "FAIL [2.1]: tool list exit ${EXIT_CODE}"
for TOOL in restartService scaleDeployment escalateTicket; do
  echo "${OUTPUT}" | grep -q "^${TOOL} " && echo "PASS [2.1]: '${TOOL}' listed" || echo "FAIL [2.1]: '${TOOL}' not listed"
done
```

**Expected result:**

- The output includes `PASS [2.1]: tool list exit 0`.
- The output includes `PASS [2.1]: 'restartService' listed`.
- The output includes `PASS [2.1]: 'scaleDeployment' listed`.
- The output includes `PASS [2.1]: 'escalateTicket' listed`.
- The output contains no line starting with `FAIL`.

### Test 2.2: Call a tool with its required string parameters

The fixture has no tool with a successful one-parameter call independent of forwarded headers. Use both
required parameters of `restartService` to verify successful invocation.

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && [ "${OUTPUT}" = "SUCCESS: Service 'nginx' restarted on server 'test-server'." ]; then
  echo "PASS [2.2]: restartService returned the restart confirmation"
else
  echo "FAIL [2.2]: exit ${EXIT_CODE}, output '${OUTPUT}'"
fi
```

**Expected result:**

- The output includes `PASS [2.2]: restartService returned the restart confirmation`.
- The output contains no line starting with `FAIL`.

### Test 2.3: Call a tool without its required argument

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "Missing required argument: serverId"; then
  echo "PASS [2.3]: missing 'serverId' rejected"
else
  echo "FAIL [2.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [2.3]: missing 'serverId' rejected`.
- The output contains no line starting with `FAIL`.

### Test 2.4: Call an unknown tool

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name non-existent-tool-12345 --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "tool not found: non-existent-tool-12345"; then
  echo "PASS [2.4]: unknown tool rejected"
else
  echo "FAIL [2.4]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [2.4]: unknown tool rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 3: Resource Operations

### Test 3.1: List the resources of the namespace

```bash run
OUTPUT=$(wanaku mcp resource list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "^safetyLimits .*config://policies/safety-limits"; then
  echo "PASS [3.1]: safetyLimits listed with its URI"
else
  echo "FAIL [3.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [3.1]: safetyLimits listed with its URI`.
- The output contains no line starting with `FAIL`.

### Test 3.2: Read a resource

```bash run
RESOURCE_URI="${TEST_RESOURCE_URI:-config://policies/safety-limits}"
OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --no-auth --resource-uri "${RESOURCE_URI}" --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "max_db_replicas" \
  && echo "${OUTPUT}" | grep -q "blocked_services" && echo "${OUTPUT}" | grep -q "payment-gateway"; then
  echo "PASS [3.2]: resource content returned"
else
  echo "FAIL [3.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [3.2]: resource content returned`.
- The output contains no line starting with `FAIL`.

### Test 3.3: Read an unknown resource

Wanaku Server looks up the URI in its registry before it forwards the request.

```bash run
OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --no-auth --resource-uri "demo://non-existent-12345" --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "resource not found: demo://non-existent-12345"; then
  echo "PASS [3.3]: unknown resource rejected"
else
  echo "FAIL [3.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [3.3]: unknown resource rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 4: Prompt Operations

### Test 4.1: List the prompts of the namespace

```bash run
OUTPUT=$(wanaku mcp prompt list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null)
for PROMPT in summarizeIncident draftEscalation; do
  echo "${OUTPUT}" | grep -q "^${PROMPT} " && echo "PASS [4.1]: '${PROMPT}' listed" || echo "FAIL [4.1]: '${PROMPT}' not listed"
done
```

**Expected result:**

- The output includes `PASS [4.1]: 'summarizeIncident' listed`.
- The output includes `PASS [4.1]: 'draftEscalation' listed`.
- The output contains no line starting with `FAIL`.

### Test 4.2: Get a prompt without arguments

Both fixture prompts require arguments; neither is a zero-argument prompt. Verify that an argument-free
request is rejected, then verify successful rendering in Test 4.3.

```bash run
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name summarizeIncident --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q -- "-32602"; then
  echo "PASS [4.2]: required prompt arguments rejected when absent"
else
  echo "FAIL [4.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.2]: required prompt arguments rejected when absent`.
- The output contains no line starting with `FAIL`.

### Test 4.3: Get prompts with arguments

```bash run
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name summarizeIncident \
  --arg serverId=test-server --arg incident=memory-pressure --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "server test-server" \
  && echo "${OUTPUT}" | grep -q "memory-pressure"; then
  echo "PASS [4.3]: summarizeIncident rendered with both arguments"
else
  echo "FAIL [4.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name draftEscalation \
  --arg urgency=high --arg reason=memory-pressure --plain 2>/dev/null)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "urgency=high" \
  && echo "${OUTPUT}" | grep -q "memory-pressure"; then
  echo "PASS [4.3]: draftEscalation rendered with both arguments"
else
  echo "FAIL [4.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.3]: summarizeIncident rendered with both arguments`.
- The output includes `PASS [4.3]: draftEscalation rendered with both arguments`.
- The output contains no line starting with `FAIL`.

### Test 4.4: Get a prompt without one required argument

```bash run
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name summarizeIncident \
  --arg serverId=test-server --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q -- "-32602"; then
  echo "PASS [4.4]: missing 'incident' rejected"
else
  echo "FAIL [4.4]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.4]: missing 'incident' rejected`.
- The output contains no line starting with `FAIL`.

### Test 4.5: Get an unknown prompt

```bash run
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --no-auth --name non-existent-prompt-12345 --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "prompt not found: non-existent-prompt-12345"; then
  echo "PASS [4.5]: unknown prompt rejected"
else
  echo "FAIL [4.5]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [4.5]: unknown prompt rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 5: Namespaces and Governance

### Test 5.1: A namespace shows only its own entries

`MCP_ISOLATED_NAMESPACE` is governed, but it has no forward.

```bash run
ISO_URI="${WANAKU_MCP_BASE_URL}/${MCP_ISOLATED_NAMESPACE}/mcp"
for KIND in tool resource prompt; do
  OUTPUT=$(wanaku mcp "${KIND}" list --uri "${ISO_URI}" --no-auth --plain 2>/dev/null)
  echo "${OUTPUT}" | grep -q "^No ${KIND}s found" \
    && echo "PASS [5.1]: no ${KIND}s in ${MCP_ISOLATED_NAMESPACE}" || echo "FAIL [5.1]: ${KIND}s leaked: ${OUTPUT}"
done
```

**Expected result:**

- The output includes `PASS [5.1]: no tools in <MCP_ISOLATED_NAMESPACE>`.
- The output includes `PASS [5.1]: no resources in <MCP_ISOLATED_NAMESPACE>`.
- The output includes `PASS [5.1]: no prompts in <MCP_ISOLATED_NAMESPACE>`.
- The output contains no line starting with `FAIL`.

### Test 5.2: A tool of another namespace cannot be called

```bash run
OUTPUT=$(wanaku mcp tool --uri "${ISO_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "tool not found: restartService"; then
  echo "PASS [5.2]: 'restartService' is not callable from ${MCP_ISOLATED_NAMESPACE}"
else
  echo "FAIL [5.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [5.2]: 'restartService' is not callable from <MCP_ISOLATED_NAMESPACE>`.
- The output contains no line starting with `FAIL`.

### Test 5.3: The fail-safe default denies calls in a namespace that is not governed

The governance check runs before the tool lookup. Listing is not governed.

```bash run
UNGOV_URI="${WANAKU_MCP_BASE_URL}/${MCP_UNGOVERNED_NAMESPACE}/mcp"
wanaku mcp tool list --uri "${UNGOV_URI}" --no-auth --plain > /dev/null 2>&1 \
  && echo "PASS [5.3]: listing is allowed in ${MCP_UNGOVERNED_NAMESPACE}" || echo "FAIL [5.3]: listing failed"

OUTPUT=$(wanaku mcp tool --uri "${UNGOV_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "No governance policy permits this action."; then
  echo "PASS [5.3]: tools/call denied by the fail-safe default"
else
  echo "FAIL [5.3]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [5.3]: listing is allowed in <MCP_UNGOVERNED_NAMESPACE>`.
- The output includes `PASS [5.3]: tools/call denied by the fail-safe default`.
- The output contains no line starting with `FAIL`.

### Test 5.4: A disabled tool is hidden from MCP clients

The enable and disable routes have no CLI command, so this test uses `curl`.

```bash run
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/restartService/disable?namespace=${MCP_NAMESPACE}")
[ "${HTTP_CODE}" = "200" ] && echo "PASS [5.4]: restartService disabled" || echo "FAIL [5.4]: disable returned HTTP ${HTTP_CODE}"

wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null | grep -q "^restartService " \
  && echo "FAIL [5.4]: disabled tool still listed" || echo "PASS [5.4]: disabled tool not listed"

OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
echo "${OUTPUT}" | grep -q "tool not found: restartService" \
  && echo "PASS [5.4]: disabled tool cannot be called" || echo "FAIL [5.4]: output: ${OUTPUT}"

# The management API still lists the entry
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain | grep -q "^restartService" \
  && echo "PASS [5.4]: management API still lists restartService" || echo "FAIL [5.4]: restartService missing from management API"
```

**Expected result:**

- The output includes `PASS [5.4]: restartService disabled`.
- The output includes `PASS [5.4]: disabled tool not listed`.
- The output includes `PASS [5.4]: disabled tool cannot be called`.
- The output includes `PASS [5.4]: management API still lists restartService`.
- The output contains no line starting with `FAIL`.

### Test 5.5: An enabled tool is visible again

```bash run
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${WANAKU_SERVER_URL}/api/v1/tools/restartService/enable?namespace=${MCP_NAMESPACE}")
[ "${HTTP_CODE}" = "200" ] && echo "PASS [5.5]: restartService enabled" || echo "FAIL [5.5]: enable returned HTTP ${HTTP_CODE}"

OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService --param serverId=test-server --param service=nginx --plain 2>/dev/null)
[ "${OUTPUT}" = "SUCCESS: Service 'nginx' restarted on server 'test-server'." ] && echo "PASS [5.5]: restartService callable again" || echo "FAIL [5.5]: output '${OUTPUT}'"
```

**Expected result:**

- The output includes `PASS [5.5]: restartService enabled`.
- The output includes `PASS [5.5]: restartService callable again`.
- The output contains no line starting with `FAIL`.

### Test 5.6: Evaluator updates change MCP behavior and rollback restores it

This scenario requires the CLI commands from `wanaku-barn#235` and the server revision API from
`wanaku#1879`. It runs only against the **builtin server owned by this plan**. Existing mode is BLOCKED:
evaluator updates replace the global configuration, so the plan must not reconfigure somebody else's server.

The deterministic processor from [the block fixture](../fixtures/evaluator-block/README.md) always blocks with
`mcp-cli-evaluator-block`. Its compiled component is embedded below, so the worker needs only `base64`, already
available in its Linux image. The `passthrough` engine makes no network or LLM calls. The evaluator matches only
this run's namespace. Capture the original active revision before changing anything, and restore its configuration
on success and failure. Cleanup restores it only while the active revision is the one this run created, which it
recognizes by this run's temporary processor path; otherwise it reports a conflict and leaves the active revision in
place. Rollback creates a new revision; the original revision's ID must not become active again.
Run Phase 8 even if restoration fails: it retries the restoration while the server runs, then removes the server and
its data. If the run continues after a restoration failure, the restart in Test 6.2 replaces the retained evaluator
state with the startup configuration.

```bash run
if [ "${WANAKU_SERVER_MODE}" != builtin ]; then
  echo "BLOCKED [5.6]: evaluator mutation requires the builtin server owned by this plan"
else
  MCP_EVALUATOR_DATA_DIR=$(mktemp -d "${WANAKU_SERVER_DATA_DIR}/evaluator-cli.XXXXXX")
  # Also used by Phase 8 if the step is interrupted before its EXIT trap completes.
  mcp_restore_evaluators() {
    # The configuration changes only after the original revision is captured, so there is nothing to restore before.
    jq -e '.data.revision.id' "${MCP_EVALUATOR_DATA_DIR}/original.json" > /dev/null 2>&1 \
      || { rm -rf "${MCP_EVALUATOR_DATA_DIR}"; return 0; }
    local current original restored
    current=$(curl --connect-timeout 2 --max-time 10 -fsS \
      "${WANAKU_SERVER_URL}/api/v1/evaluators/revisions/active") || return 1
    original=$(jq -er '.data.revision.id' "${MCP_EVALUATOR_DATA_DIR}/original.json") || return 1
    # Restoring the saved configuration is sufficient; revision IDs are immutable history.
    if ! printf '%s' "${current}" | jq -e --slurpfile saved "${MCP_EVALUATOR_DATA_DIR}/original.json" \
      '.data.evaluators == $saved[0].data.evaluators' > /dev/null; then
      # Only this run's update references its temporary processor; anything else is a concurrent change.
      if ! printf '%s' "${current}" | jq -e --arg path "${MCP_EVALUATOR_DATA_DIR}/block.wasm" \
        '.data.evaluators | length == 1 and .[0].processor.path == $path' > /dev/null; then
        echo "FAIL [5.6]: the active evaluator revision was not created by this run; refusing restoration"
        return 1
      fi
      wanaku evaluators revisions activate "${original}" \
        --expected-revision "$(printf '%s' "${current}" | jq -er '.data.revision.id')" \
        --host "${WANAKU_SERVER_URL}" --no-auth --plain || return 1
      restored=$(curl --connect-timeout 2 --max-time 10 -fsS \
        "${WANAKU_SERVER_URL}/api/v1/evaluators/revisions/active") || return 1
      printf '%s' "${restored}" | jq -e --slurpfile saved "${MCP_EVALUATOR_DATA_DIR}/original.json" \
        '.data.evaluators == $saved[0].data.evaluators' > /dev/null || return 1
    fi
    rm -rf "${MCP_EVALUATOR_DATA_DIR}"
  }
  (
    set -eu
    trap 'result=$?; trap - EXIT; if ! mcp_restore_evaluators; then echo "FAIL [5.6]: evaluator restoration failed; run Phase 8"; result=1; fi; exit "${result}"' EXIT
    curl --connect-timeout 2 --max-time 10 -fsS \
      "${WANAKU_SERVER_URL}/api/v1/evaluators/revisions/active" > "${MCP_EVALUATOR_DATA_DIR}/original.json"
    ORIGINAL_REVISION=$(jq -er '.data.revision.id' "${MCP_EVALUATOR_DATA_DIR}/original.json")
    jq -e '.data.evaluators | type == "array"' "${MCP_EVALUATOR_DATA_DIR}/original.json" > /dev/null
    BEFORE=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService \
      --param serverId=test-server --param service=nginx --plain)
    [ "${BEFORE}" = "SUCCESS: Service 'nginx' restarted on server 'test-server'." ]
    base64 -d > "${MCP_EVALUATOR_DATA_DIR}/block.wasm" <<'WASM'
AGFzbQ0AAQAHGgFCAgFAAQZyZWFzb25zAQAEAAVibG9jawEACh4BABl3YW5ha3U6ZXZhbHVhdG9yL3Jlc3BvbnNlBQAGCgEBAAAFYmxvY2sBSABhc20BAAAABQMBAAEHCgEGbWVtb3J5AgALHQEAQQALF21jcC1jbGktZXZhbHVhdG9yLWJsb2NrAA4EbmFtZQAHBnJlYXNvbgIEAQAAAAYMAQACAQAGbWVtb3J5CAcBAQAAAQMAAg0BAQEHcmVzcG9uZAAAAc8BAGFzbQEAAAABHwNgAn9/AGAEf39/fwF/YA5/f39/f39/f39/f39/fwACEAEEaG9zdAdyZXNwb25kAAADAwIBAgUDAQABBgcBfwFBgAgLBx8DBm1lbW9yeQIAB3JlYWxsb2MAAQhldmFsdWF0ZQACCiICFwEBfyMAIQQjACADakEHakF4cSQAIAQLCABBAEEXEAALADoEbmFtZQAKCXByb2Nlc3NvcgEKAQAHcmVzcG9uZAISAQECAwRzaXplBAdwb2ludGVyBwcBAARuZXh0AgsBAAEBBGhvc3QSAQYlAwACAQIGbWVtb3J5AAABAgdyZWFsbG9jAAABAghldmFsdWF0ZQdRBGtzbwJzc3ACcgYGbWV0aG9kcwluYW1lc3BhY2VzCXRvb2wtbmFtZQEJYXJndW1lbnRzAwpsbG0tcmVzdWx0cw9jb252ZXJzYXRpb24taWQBCxgBABJldmFsdWF0aW9uLWNvbnRleHQDBAAHCgFAAQNjdHgFAQAICgEAAAICAwEEAQYLDgEACGV2YWx1YXRlAQEAAPABDmNvbXBvbmVudC1uYW1lAScAAAMAD3Jlc3BvbmQtbG93ZXJlZAEHcmVhbGxvYwIIZXZhbHVhdGUBGgACAgANcmVhc29uLW1lbW9yeQEGbWVtb3J5ARYAEQIABnJlYXNvbgEJcHJvY2Vzc29yASQAEgMAD3JlYXNvbi1pbnN0YW5jZQEEaG9zdAIIaW5zdGFuY2UBCwEBAAdyZXNwb25kAUEDBQEPb3B0aW9uYWwtc3RyaW5nAghhcmd1bWVudAMJYXJndW1lbnRzBAdjb250ZXh0BQ5wdWJsaWMtY29udGV4dAEMBQEACHJlc3BvbnNl
WASM
    jq -n --arg namespace "${MCP_NAMESPACE}" --arg path "${MCP_EVALUATOR_DATA_DIR}/block.wasm" \
      '{evaluators: [{name: "mcp-cli-block", trigger: {method: "tools/call", namespace: $namespace},
        engine: {type: "passthrough"}, processor: {path: $path}}]}' \
      > "${MCP_EVALUATOR_DATA_DIR}/block.json"
    wanaku evaluators update "${MCP_EVALUATOR_DATA_DIR}/block.json" --expected-revision "${ORIGINAL_REVISION}" \
      --host "${WANAKU_SERVER_URL}" --no-auth --plain
    curl --connect-timeout 2 --max-time 10 -fsS \
      "${WANAKU_SERVER_URL}/api/v1/evaluators/revisions/active" > "${MCP_EVALUATOR_DATA_DIR}/blocked.json"
    BLOCK_REVISION=$(jq -er '.data.revision.id' "${MCP_EVALUATOR_DATA_DIR}/blocked.json")
    [ "${BLOCK_REVISION}" -gt "${ORIGINAL_REVISION}" ]
    jq -e --arg namespace "${MCP_NAMESPACE}" \
      '.data.evaluators | length == 1 and .[0].name == "mcp-cli-block" and .[0].trigger.namespace == $namespace' \
      "${MCP_EVALUATOR_DATA_DIR}/blocked.json" > /dev/null
    set +e
    BLOCKED=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService \
      --param serverId=test-server --param service=nginx --plain 2>&1)
    BLOCK_EXIT=$?
    set -e
    [ "${BLOCK_EXIT}" -eq 1 ]
    printf '%s' "${BLOCKED}" | grep -Fq 'mcp-cli-evaluator-block'
    echo "PASS [5.6]: CLI evaluator update blocks the previously successful MCP call"
    wanaku evaluators revisions activate "${ORIGINAL_REVISION}" --expected-revision "${BLOCK_REVISION}" \
      --host "${WANAKU_SERVER_URL}" --no-auth --plain
    curl --connect-timeout 2 --max-time 10 -fsS \
      "${WANAKU_SERVER_URL}/api/v1/evaluators/revisions/active" > "${MCP_EVALUATOR_DATA_DIR}/restored.json"
    jq -e --argjson blocked "${BLOCK_REVISION}" --slurpfile saved "${MCP_EVALUATOR_DATA_DIR}/original.json" \
      '.data.revision.id > $blocked and .data.evaluators == $saved[0].data.evaluators' \
      "${MCP_EVALUATOR_DATA_DIR}/restored.json" > /dev/null
    AFTER=$(wanaku mcp tool --uri "${MCP_URI}" --no-auth --name restartService \
      --param serverId=test-server --param service=nginx --plain)
    [ "${AFTER}" = "${BEFORE}" ]
    echo "PASS [5.6]: CLI rollback creates a new revision and restores the successful MCP call"
  )
  EVALUATOR_RESULT=$?
  [ "${EVALUATOR_RESULT}" -eq 0 ] || echo "FAIL [5.6]: evaluator round trip failed (exit ${EVALUATOR_RESULT})"
  (exit "${EVALUATOR_RESULT}")
fi
```

**Expected result:** Builtin mode prints both `PASS [5.6]` messages with no `FAIL` lines. A real MCP call
succeeds before the update, fails with the processor's unique marker after the update, and succeeds with the same
result after rollback. REST verification confirms rollback created a newer revision containing the saved evaluator
configuration. The temporary fixture is removed only after configuration restoration succeeds. Existing mode is
reported BLOCKED, never PASS.

---

## Phase 6: Forwards and Restart

### Test 6.1: The forward is listed with its address

```bash run
TAB=$(printf '\t')
wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain \
  | grep -q "^${TEST_MCP_FORWARD}${TAB}${TEST_MCP_URL}" \
  && echo "PASS [6.1]: forward listed with its address" \
  || echo "FAIL [6.1]: forward ${TEST_MCP_FORWARD} not listed with ${TEST_MCP_URL}"
```

**Expected result:**

- The output includes `PASS [6.1]: forward listed with its address`.
- The output contains no line starting with `FAIL`.

### Test 6.2: Forwards survive a restart

No automated test restarts Wanaku Server and checks that the registry is restored. This test does that in builtin
mode. In existing mode, the plan must not restart the server, so the test prints `SKIP`.

The test stops the server with `SIGTERM`, starts it again with the same `WANAKU_PERSIST_PATH`, and checks that the
forward and its tools are restored from `registry.json`.

```bash run
if [ "${MCP_CREATED_SERVER:-false}" != true ]; then
  echo "SKIP [6.2]: the server is not owned by this run (existing mode)"
else
  [ -f "${WANAKU_SERVER_DATA_DIR}/registry.json" ] && echo "PASS [6.2]: registry.json written" \
    || echo "FAIL [6.2]: registry.json missing in ${WANAKU_SERVER_DATA_DIR}"
  kill -TERM "${WANAKU_SERVER_PID}" 2>/dev/null || true
  wait "${WANAKU_SERVER_PID}" 2>/dev/null || true
  WANAKU_MGMT_LISTEN="0.0.0.0:${WANAKU_SERVER_MGMT_PORT}" \
  WANAKU_PERSIST_BACKEND=file WANAKU_PERSIST_PATH="${WANAKU_SERVER_DATA_DIR}" \
    "${WANAKU_SERVER_BIN}" --wanaku-config "${WANAKU_SERVER_CONFIG}" \
    < /dev/null >> "${WANAKU_SERVER_LOG}" 2>&1 &
  WANAKU_SERVER_PID=$!
  F2_READY=false
  for ATTEMPT in $(seq 1 30); do
    if curl --connect-timeout 1 --max-time 1 -fsS "${WANAKU_SERVER_URL}/healthz" > /dev/null 2>&1; then
      F2_READY=true
      break
    fi
    sleep 1
  done
  [ "${F2_READY}" = true ] && echo "PASS [6.2]: server ready after restart" \
    || { echo "FAIL [6.2]: server not ready after restart"; tail -n 20 "${WANAKU_SERVER_LOG}"; }
  TAB=$(printf '\t')
  wanaku forwards list --host "${WANAKU_SERVER_URL}" --no-auth --plain \
    | grep -q "^${TEST_MCP_FORWARD}${TAB}${TEST_MCP_URL}" \
    && echo "PASS [6.2]: forward restored after restart" || echo "FAIL [6.2]: forward lost after restart"
  F2_TOOLS=false
  for ATTEMPT in $(seq 1 30); do
    if wanaku mcp tool list --uri "${MCP_URI}" --no-auth --plain 2>/dev/null | grep -Eq '^restartService([[:space:]]|$)'; then
      F2_TOOLS=true
      break
    fi
    sleep 1
  done
  [ "${F2_TOOLS}" = true ] && echo "PASS [6.2]: tools served again after restart" \
    || echo "FAIL [6.2]: restartService not served after restart"
fi
```

**Expected result:**

- In builtin mode, the output includes `PASS [6.2]: registry.json written`.
- In builtin mode, the output includes `PASS [6.2]: server ready after restart`.
- In builtin mode, the output includes `PASS [6.2]: forward restored after restart`.
- In builtin mode, the output includes `PASS [6.2]: tools served again after restart`.
- In existing mode, the output includes `SKIP [6.2]: the server is not owned by this run (existing mode)`.
- The output contains no line starting with `FAIL`.

---

## Phase 7: Endpoint and Connection Errors

### Test 7.1: The bare `/mcp` path is rejected

Wanaku Server returns the JSON-RPC error `-32600` for an MCP request without a namespace.

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/mcp" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -q "invalid MCP endpoint path: use /{namespace}/mcp"; then
  echo "PASS [7.1]: bare /mcp rejected"
else
  echo "FAIL [7.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [7.1]: bare /mcp rejected`.
- The output contains no line starting with `FAIL`.

### Test 7.2: An unreachable server fails without a stack trace

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${MCP_UNREACHABLE_URI}" --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && ! echo "${OUTPUT}" | grep -q "at .*(.*\.java:"; then
  echo "PASS [7.2]: unreachable server fails gracefully"
else
  echo "FAIL [7.2]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
echo "${OUTPUT}" | grep -q "Connection refused" \
  || echo "WARN [7.2]: message is not the friendly 'Connection refused' text: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [7.2]: unreachable server fails gracefully`.
- The output contains no line starting with `FAIL`.
- The output contains `Connection refused`, or includes `WARN [7.2]: message is not the friendly`.

---

## Phase 8: Cleanup

### Step 8.1: Cleanup

Remove only resources recorded as created by this run, including after partial setup. Never stop an existing
Wanaku Server or delete its persistence/configuration. Do not use port-wide process killing on a shared machine.

If Test 5.6 left its evaluator directory, because it was interrupted or could not restore the evaluator
configuration, the block first tries again to restore that configuration and prints a `FAIL` line if that fails. It
skips this when the builtin server of this run no longer runs: the evaluator revisions are in the server data
directory, which the block removes. The cleanup continues either way.

The removal commands ignore errors, such as a resource that is already gone. The block then checks the result with
the CLI list commands while the server still runs, and prints a `PASS` or `FAIL` line for each check: the run's
forward and namespaces, and the tools, resources and prompts that the fixture forward registers. In builtin mode,
when this run started no server or its server no longer runs, nothing can remain: the registry is in the server data
directory, which the block removes.

```bash run
CLEANUP_FAILED=0
if [ -n "${MCP_EVALUATOR_DATA_DIR:-}" ] && [ -d "${MCP_EVALUATOR_DATA_DIR}" ] \
  && { [ "${WANAKU_SERVER_MODE}" != builtin ] || kill -0 "${WANAKU_SERVER_PID}" 2>/dev/null; }; then
  mcp_restore_evaluators \
    || { echo "FAIL [8.1]: evaluator configuration restoration failed"; CLEANUP_FAILED=1; }
fi
# timeout needs an executable, so invoke the route selected in S.1 directly, as Step 1.1 does.
cleanup_cli() {
  if [ "${WANAKU_CLI_MODE}" = jar ]; then
    timeout --kill-after=2s 10s java -jar "${CLI_JAR}" "$@"
  else
    timeout --kill-after=2s 10s "${WANAKU_CLI_EXEC}" "$@"
  fi
}
if [ "${MCP_CREATED_FORWARD:-false}" = true ]; then
  cleanup_cli forwards remove --host "${WANAKU_SERVER_URL}" --no-auth \
    --name "${TEST_MCP_FORWARD}" --plain > /dev/null 2>&1 || true
fi
for NS in $(printf '%s' "${MCP_CREATED_NAMESPACES}"); do
  cleanup_cli namespaces delete "${NS}" --host "${WANAKU_SERVER_URL}" --no-auth --plain > /dev/null 2>&1 || true
done

# $1 = registry kind, $2 = space-separated names that must not be in the first column of the --plain list
cleanup_check_absent() {
  CLEANUP_LIST=$(cleanup_cli "$1" list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>/dev/null)
  CLEANUP_RC=$?
  if [ "${CLEANUP_RC}" -ne 0 ]; then
    echo "FAIL [8.1]: ${1} list failed (exit ${CLEANUP_RC})"; CLEANUP_FAILED=1
  elif echo "${CLEANUP_LIST}" | awk -F'\t' -v names=" $2 " \
    '$1 != "" && index(names, " " $1 " ") { found = 1 } END { exit !found }'; then
    echo "FAIL [8.1]: ${1} created by this run still listed:"; echo "${CLEANUP_LIST}"; CLEANUP_FAILED=1
  else
    echo "PASS [8.1]: no ${1} of this run remain"
  fi
}
if [ "${WANAKU_SERVER_MODE}" = builtin ] \
  && ! { [ "${MCP_CREATED_SERVER:-false}" = true ] && kill -0 "${WANAKU_SERVER_PID}" 2>/dev/null; }; then
  for KIND in forwards namespaces tools resources prompts; do
    echo "PASS [8.1]: no ${KIND} of this run remain (no server of this run is running; the registry is in its data directory)"
  done
else
  if [ "${MCP_CREATED_FORWARD:-false}" = true ]; then
    cleanup_check_absent forwards "${TEST_MCP_FORWARD}"
    # The entries of the fixture (fixtures/test-mcp-server)
    cleanup_check_absent tools "restartService scaleDeployment escalateTicket echoAuthHeader"
    cleanup_check_absent resources "safetyLimits syslog"
    cleanup_check_absent prompts "summarizeIncident draftEscalation"
  else
    for KIND in forwards tools resources prompts; do
      echo "PASS [8.1]: no ${KIND} of this run remain (this run registered no forward)"
    done
  fi
  if [ -n "${MCP_CREATED_NAMESPACES// /}" ]; then
    cleanup_check_absent namespaces "${MCP_CREATED_NAMESPACES}"
  else
    echo "PASS [8.1]: no namespaces of this run remain (this run created no namespace)"
  fi
fi

if [ "${MCP_CREATED_FIXTURE:-false}" = true ]; then
  kill "${MCP_FIXTURE_PID}" 2>/dev/null || true
  wait "${MCP_FIXTURE_PID}" 2>/dev/null || true
fi
if [ -n "${MCP_FIXTURE_DATA_DIR:-}" ]; then
  rm -rf "${MCP_FIXTURE_DATA_DIR}"
fi
if { [ "${MCP_CREATED_FIXTURE:-false}" = true ] && kill -0 "${MCP_FIXTURE_PID}" 2>/dev/null; } \
  || { [ -n "${MCP_FIXTURE_DATA_DIR:-}" ] && [ -e "${MCP_FIXTURE_DATA_DIR}" ]; }; then
  echo "FAIL [8.1]: the launched fixture is still running or its files remain"; CLEANUP_FAILED=1
elif [ "${MCP_CREATED_FIXTURE:-false}" = true ]; then
  echo "PASS [8.1]: fixture cleanup done (launched fixture stopped and its files removed)"
else
  echo "PASS [8.1]: fixture cleanup done (this run launched no fixture, so none was stopped)"
fi

if [ "${WANAKU_SERVER_MODE}" = builtin ]; then
  if [ "${MCP_CREATED_SERVER:-false}" = true ]; then
    kill "${WANAKU_SERVER_PID}" 2>/dev/null || true
    wait "${WANAKU_SERVER_PID}" 2>/dev/null || true
  fi
  if [ -n "${MCP_CREATED_SERVER_DATA_DIR:-}" ]; then
    rm -rf "${MCP_CREATED_SERVER_DATA_DIR}"
  fi
  if { [ "${MCP_CREATED_SERVER:-false}" = true ] && kill -0 "${WANAKU_SERVER_PID}" 2>/dev/null; } \
    || { [ -n "${MCP_CREATED_SERVER_DATA_DIR:-}" ] && [ -e "${MCP_CREATED_SERVER_DATA_DIR}" ]; }; then
    echo "FAIL [8.1]: the builtin server is still running or its configuration and data remain"; CLEANUP_FAILED=1
  elif [ "${MCP_CREATED_SERVER:-false}" = true ]; then
    echo "PASS [8.1]: server cleanup done (builtin server stopped, configuration and data removed)"
  else
    echo "PASS [8.1]: server cleanup done (this run started no server)"
  fi
elif curl --connect-timeout 1 --max-time 2 -fsS "${WANAKU_SERVER_URL}/healthz" > /dev/null 2>&1; then
  echo "PASS [8.1]: server cleanup done (existing server still reachable)"
else
  echo "FAIL [8.1]: the existing server is not reachable after cleanup"; CLEANUP_FAILED=1
fi
[ "${CLEANUP_FAILED}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS [8.1]: no forwards of this run remain`.
- The output includes `PASS [8.1]: no namespaces of this run remain`.
- The output includes `PASS [8.1]: no tools of this run remain`.
- The output includes `PASS [8.1]: no resources of this run remain`.
- The output includes `PASS [8.1]: no prompts of this run remain`.
- The output includes `PASS [8.1]: fixture cleanup done`.
- The output includes `PASS [8.1]: server cleanup done`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

## Test Summary Matrix

| Phase | Test ID | Test Name                                                                             | Priority |
|-------|---------|---------------------------------------------------------------------------------------|----------|
| S     | S.1-S.2 | CLI invocation and server mode                                                        | High     |
| 0     | 0.1     | Prerequisites                                                                         | High     |
| 1     | 1.1-1.4 | Server with test governance, fixture forward, namespaces                              | Critical |
| 2     | 2.1-2.4 | Tool list, call, missing arg, unknown tool                                            | Critical |
| 3     | 3.1-3.3 | Resource list, read, unknown resource                                                 | Critical |
| 4     | 4.1-4.5 | Prompt list, get, get with args, missing arg, unknown prompt                          | Critical |
| 5     | 5.1-5.6 | Namespace isolation, governance, disable/enable, evaluator update and rollback                             | Critical |
| 6     | 6.1-6.2 | Forward listing, forwards survive a restart (builtin mode)                            | Critical |
| 7     | 7.1-7.2 | Bare `/mcp`, unreachable server                                                       | High     |
| 8     | 8.1     | Cleanup                                                                               | Critical |
