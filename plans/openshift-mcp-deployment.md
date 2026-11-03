# Test Plan: Wanaku MCP Deployment on OpenShift

## Overview

This plan verifies operator deployment of Wanaku Server on OpenShift and authenticated MCP operations through
its Routes. It deploys Keycloak, oauth2-proxy and the infra-remediation MCP fixture, registers the fixture as a
forward, and checks discovery, authentication, namespace isolation and governance behavior.

For CLI help, option validation and tool/resource/prompt operations against a builtin or existing server, use
[mcp-cli-commands.md](mcp-cli-commands.md). This plan runs independently and does not launch a builtin server.

The operator deploys Wanaku Server from a `WanakuRouter` resource (`spec.server`) with authentication enabled
(`spec.auth`). The operator creates:

- the `<router>-server` Deployment and the `server-<router>` Service (ports 8080 management, 8081 MCP, 8083, 8084)
- the `<router>-oauth2-proxy` Deployment (4180 MCP proxy, 4181 management proxy)
- the Route `<router>` for the management API and the Route `<router>-mcp` for the MCP listener (edge TLS)

The plan deploys the MCP test fixture in the cluster and registers it as a forward with the CLI. This replaces
the removed `WanakuCapability` resource. The operator also offers `WanakuIntegration` for the Camel Integration
Capability; this plan does not need it. Barn is not deployed.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `oc` | 4.12+ | `oc version --client` |
| `helm` | 3.x | `helm version --short` |
| `wanaku` CLI | installed executable or local build | `wanaku --version` |
| `wanaku-keycloak-admin` | installed executable or local build | `wanaku-keycloak-admin --version` |
| `java` | 21+, only for direct jar invocation | `java -version` |
| `openssl` | any | `openssl version` |
| `curl`, `jq` | any / 1.6+ | `jq --version` |

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

### Environment and procedure dependencies

- Set `OPENSHIFT_API_URL` and `OPENSHIFT_SA_TOKEN` before Step 10.1. The login procedure uses the cluster API URL
  and a service account token; the account needs permission to create the namespace and install the operator.
- The Keycloak procedure imports `deploy/auth/wanaku-config.json` and the operator procedure installs the Helm chart
  `apps/wanaku-operator/deploy/helm/wanaku-operator`, both from Wanaku Barn. Step S.2 reuses the current directory
  when it contains them; otherwise it downloads them from `wanaku-ai/wanaku-barn`. Set `WANAKU_BARN_REF` to a branch,
  tag or commit (default: `main`) to select the source version. To use an existing checkout instead, explicitly set
  `WANAKU_REPO_ROOT` to its root; an invalid explicit path fails rather than triggering a download. Downloaded
  sources are isolated in a temporary directory and removed in Step 15.1.
- Make `wanaku-keycloak-admin` available on `PATH`, or configure `WANAKU_KEYCLOAK_ADMIN_CLI` as described in
  [common/keycloak-setup.md](common/keycloak-setup.md). Jar invocations require built artifacts and Java 21+.
  Step S.1 selects the main CLI independently, using `CLI_JAR` when set.
- Step 11.1 creates a unique `WANAKU_NAMESPACE`. Step S.3 then sets the deployment environment and an isolated
  `WANAKU_CREDENTIALS` directory. Keep the selected CLI function, credentials and exported procedure outputs in
  the shared shell for subsequent steps.
- The cluster must be able to pull `WANAKU_SERVER_IMAGE`, the operator images, Keycloak, oauth2-proxy and
  `TEST_MCP_IMAGE` (the infra-remediation fixture). Step S.3 supplies the deployment image defaults.
- Step S.4 defines `wait_for_deletion` from [common/wait-for-deletion.md](common/wait-for-deletion.md).

### Shared session and timeouts

Run command blocks in Saci's shared Bash session, including procedure blocks, so the `wanaku` function, helper
functions and exported variables remain available to later steps. Source each block in that session; a top-level
`return 1` fails the block without terminating the session. Do not execute blocks as separate scripts or enable
`set -e` globally.

**Hard timeout rule:** Saci stops a command block that runs longer than 120 seconds (`limits.operation_timeout_secs`),
and a stopped block can lose the shared Bash session. Any single operation (oc wait, polling loop, rollout status,
etc.) that takes longer than **100 seconds** MUST be considered broken. Mark the test as FAIL and do not wait longer.
All `--timeout` values and polling deadlines in this plan MUST NOT exceed `100s`, and each block finishes within the
Saci limit.

### Known limitations

- Wanaku Server denies `tools/call`, `resources/read` and `prompts/get` unless governance permits them. Listing
  requests are not governed. Test 14.1 checks the fail-safe denial of the default deployment. Step 14.2 then gives
  the Server a custom `wanaku.yaml` through `spec.server.configMapRef`, and Test 14.3 checks that the calls succeed.
  `spec.server.configMapRef` requires a Wanaku operator that includes wanaku-ai/wanaku-barn#370.
- Tool, resource and prompt names are unique across the server registry. Use this plan's fresh deployment rather
  than registering the same fixture twice in an existing server.

## Phase S: CLI Setup

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

## Phase 10: OpenShift Login (MANUAL)

### Step 10.1: Log in to OpenShift (MANUAL)

Follow [common/openshift-login.md](common/openshift-login.md).

**Expected result:**

- All procedure steps passed, and `oc whoami` shows the service account.

---

## Phase 11: Deploy the Wanaku Stack

### Step 11.1: Create the namespace

Follow [common/namespace-setup.md](common/namespace-setup.md).

**Expected result:**

- The current namespace is the value of `WANAKU_NAMESPACE`.

## Phase T: Setup for OpenShift

### Step S.2: Prepare the operator Helm chart and realm configuration

Reuse a supplied Wanaku Barn checkout, or download its source archive from the official repository. Keep the
chart available through the cleanup. Run this block in the shared Bash session.

```bash run
WANAKU_DOWNLOADED_SOURCE_DIR=""
export WANAKU_BARN_REF="${WANAKU_BARN_REF:-main}"
if [ -n "${WANAKU_REPO_ROOT+x}" ]; then
  if [ ! -r "${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
      || [ ! -r "${WANAKU_REPO_ROOT}/deploy/auth/wanaku-config.json" ]; then
    echo "FAIL [S.2]: supplied WANAKU_REPO_ROOT lacks the operator chart or realm configuration"
    return 1
  fi
  export WANAKU_REPO_ROOT
  echo "PASS [S.2]: existing Wanaku Barn source assets reused"
elif [ -r "./apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
    && [ -r "./deploy/auth/wanaku-config.json" ]; then
  export WANAKU_REPO_ROOT="${PWD}"
  echo "PASS [S.2]: existing Wanaku Barn source assets reused"
else
  for CMD in curl tar; do
    command -v "${CMD}" > /dev/null 2>&1 || { echo "FAIL [S.2]: ${CMD} is required to download source assets"; return 1; }
  done
  WANAKU_DOWNLOADED_SOURCE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/wanaku-operator-source.XXXXXX") || return 1
  mkdir "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo" || return 1
  curl --fail --location --connect-timeout 10 --max-time 90 \
    "https://github.com/wanaku-ai/wanaku-barn/archive/${WANAKU_BARN_REF}.tar.gz" \
    --output "${WANAKU_DOWNLOADED_SOURCE_DIR}/source.tar.gz" \
    || { echo "FAIL [S.2]: Wanaku Barn source archive download failed"; return 1; }
  tar -xzf "${WANAKU_DOWNLOADED_SOURCE_DIR}/source.tar.gz" \
    --strip-components=1 -C "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo" \
    || { echo "FAIL [S.2]: Wanaku Barn source archive extraction failed"; return 1; }
  if [ ! -r "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
      || [ ! -r "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo/deploy/auth/wanaku-config.json" ]; then
    echo "FAIL [S.2]: downloaded source lacks the operator chart or realm configuration"
    return 1
  fi
  export WANAKU_REPO_ROOT="${WANAKU_DOWNLOADED_SOURCE_DIR}/repo"
  echo "PASS [S.2]: Wanaku Barn source assets downloaded"
fi
```

**Expected result:**

- The output includes `PASS [S.2]: existing Wanaku Barn source assets reused` or `PASS [S.2]: Wanaku Barn source assets downloaded`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step S.3: Set OpenShift environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-mcp-test}"
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WANAKU_OAUTH2_PROXY_IMAGE="${WANAKU_OAUTH2_PROXY_IMAGE:-}"   # empty: operator default
export TEST_MCP_IMAGE="${TEST_MCP_IMAGE:-quay.io/wanaku/test-mcp-server:latest}"
export MCP_E2E_NAMESPACE="${MCP_E2E_NAMESPACE:-mcp-e2e}"
# Set to "--insecure" when the cluster Route certificates are not trusted
export WANAKU_TLS_FLAG="${WANAKU_TLS_FLAG:-}"
# Isolate CLI credentials per test run (see #1697)
export WANAKU_CREDENTIALS="${WANAKU_CREDENTIALS:-/tmp/wanaku-creds-${WANAKU_NAMESPACE}}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step S.4: Define the deletion helper

Follow [common/wait-for-deletion.md](common/wait-for-deletion.md).

**Expected result:**

- The block defines the function without errors (inferred).

## Phase 11b: Deploy the Wanaku Stack (continued)

### Step 11.2: Deploy Keycloak

Follow [common/keycloak-setup.md](common/keycloak-setup.md).

For this plan, run the procedure's blocks that set output variables in the shared shell, including the block
that exports `WANAKU_CLIENT_SECRET`, even where the procedure says to use a separate script. The variables must
remain available for the checks below and for the oauth2-proxy Secret.

After completion, verify the variables:

Run this block in the shared Bash session.

```bash run
for VAR_NAME in KEYCLOAK_URL WANAKU_CLIENT_SECRET WANAKU_TEST_USER WANAKU_TEST_PASS; do
  eval "VAL=\${${VAR_NAME}}"
  [ -n "${VAL}" ] && echo "PASS [11.2]: ${VAR_NAME} is set" || { echo "FAIL [11.2]: ${VAR_NAME} is not set"; return 1; }
done
```

**Expected result:**

- The output includes `PASS [11.2]: KEYCLOAK_URL is set`.
- The output includes `PASS [11.2]: WANAKU_CLIENT_SECRET is set`.
- The output includes `PASS [11.2]: WANAKU_TEST_USER is set`.
- The output includes `PASS [11.2]: WANAKU_TEST_PASS is set`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.3: Install the operator

Follow [common/operator-deployment.md](common/operator-deployment.md).

**Expected result:**

- The operator is available and the procedure checks pass.

### Step 11.4: Create the oauth2-proxy Secret

The proxies use the `wanaku-mcp-router` client. The Secret needs the keys `client-secret` and `cookie-secret`.

Run this block in the shared Bash session.

```bash run
oc create secret generic "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" \
  --from-literal=client-secret="${WANAKU_CLIENT_SECRET}" \
  --from-literal=cookie-secret="$(openssl rand -hex 16)" 2>/dev/null \
  || oc get secret "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" > /dev/null
[ $? -eq 0 ] && echo "PASS [11.4]: oauth2-proxy Secret exists" || { echo "FAIL [11.4]: Secret not created"; return 1; }
```

**Expected result:**

- The output includes `PASS [11.4]: oauth2-proxy Secret exists`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.5: Create the WanakuRouter

Run this block in the shared Bash session.

```bash run
{
cat <<EOF
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  server:
    image: ${WANAKU_SERVER_IMAGE}
    imagePullPolicy: Always
  auth:
    enabled: true
    issuerUrl: ${KEYCLOAK_URL}/realms/wanaku
    internalIssuerUrl: http://keycloak:8080/realms/wanaku
    clientId: wanaku-mcp-router
    secretName: ${WANAKU_ROUTER_NAME}-oidc
EOF
[ -n "${WANAKU_OAUTH2_PROXY_IMAGE}" ] && echo "    image: ${WANAKU_OAUTH2_PROXY_IMAGE}"
} | oc apply -n "${WANAKU_NAMESPACE}" -f -

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}" \
  && echo "PASS [11.5]: WanakuRouter is Ready" || { echo "FAIL [11.5]: WanakuRouter not Ready"; return 1; }
```

**Expected result:**

- The output includes `PASS [11.5]: WanakuRouter is Ready`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.6: Verify the generated resources

```bash run
R="${WANAKU_ROUTER_NAME}"
for DEP in "${R}-server" "${R}-oauth2-proxy"; do
  oc rollout status "deployment/${DEP}" --timeout=50s -n "${WANAKU_NAMESPACE}" > /dev/null \
    && echo "PASS [11.6]: deployment ${DEP} available" || echo "FAIL [11.6]: deployment ${DEP} not available"
done

PORTS=$(oc get service "server-${R}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[*].port}')
for PORT in 8080 8081; do
  echo " ${PORTS} " | grep -q " ${PORT} " && echo "PASS [11.6]: server-${R} exposes ${PORT}" \
    || echo "FAIL [11.6]: server-${R} ports are '${PORTS}'"
done

for ROUTE in "${R}" "${R}-mcp"; do
  oc get route "${ROUTE}" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
    && echo "PASS [11.6]: route ${ROUTE} exists" || echo "FAIL [11.6]: route ${ROUTE} missing"
done

oc get deployment "${R}-barn" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
  && echo "FAIL [11.6]: Barn deployed although spec.barn is not enabled" || echo "PASS [11.6]: Barn not deployed"

REASON=$(oc get wanakurouter "${R}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="AuthConfigured")].reason}')
[ "${REASON}" = "Configured" ] && echo "PASS [11.6]: AuthConfigured=Configured" \
  || echo "FAIL [11.6]: AuthConfigured reason is '${REASON}'"
```

**Expected result:**

- The output includes `PASS [11.6]: deployment <R>-server available`.
- The output includes `PASS [11.6]: deployment <R>-oauth2-proxy available`.
- The output includes `PASS [11.6]: server-<R> exposes 8080`.
- The output includes `PASS [11.6]: server-<R> exposes 8081`.
- The output includes `PASS [11.6]: route <R> exists`.
- The output includes `PASS [11.6]: route <R>-mcp exists`.
- The output includes `PASS [11.6]: Barn not deployed`.
- The output includes `PASS [11.6]: AuthConfigured=Configured`.
- The output contains no line starting with `FAIL`.

### Step 11.7: Set the URL variables

```bash run
export WANAKU_SERVER_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_MCP_BASE_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
# common/oidc-login-verification.md uses WANAKU_ROUTER_URL
export WANAKU_ROUTER_URL="${WANAKU_SERVER_URL}"
export MCP_URI="${WANAKU_MCP_BASE_URL}/default/mcp"

STATUS_MCP=$(oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.endpoints.mcp}')
[ "${STATUS_MCP}" = "${WANAKU_MCP_BASE_URL}/default/mcp" ] && echo "PASS [11.7]: status.endpoints.mcp is ${STATUS_MCP}" \
  || echo "FAIL [11.7]: status.endpoints.mcp is '${STATUS_MCP}', expected ${WANAKU_MCP_BASE_URL}/default/mcp"
```

**Expected result:**

- The output includes `PASS [11.7]: status.endpoints.mcp is <STATUS_MCP>`.
- The output contains no line starting with `FAIL`.

### Step 11.8: Wait for health

`/health` does not require a token. `curl` is used to test HTTP-level readiness. Add `-k` to the `curl` commands of
this plan when `WANAKU_TLS_FLAG` is `--insecure`.

Run this block in the shared Bash session.

```bash run
for i in $(seq 1 10); do
  HTTP_CODE=$(curl -s --connect-timeout 3 --max-time 5 -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health" 2>/dev/null || echo "000")
  [ "${HTTP_CODE}" = "200" ] && { echo "PASS [11.8]: Wanaku Server healthy (attempt ${i})"; break; }
  [ "${i}" -eq 10 ] && { echo "FAIL [11.8]: health returned HTTP ${HTTP_CODE}"; return 1; }
  sleep 5
done
```

**Expected result:**

- The output includes `PASS [11.8]: Wanaku Server healthy`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 11.9: Verify that the proxies require a token

```bash run
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/api/v1/tools")
[ "${HTTP_CODE}" = "401" ] && echo "PASS [11.9]: management API returns 401 without a token" \
  || echo "FAIL [11.9]: management API returned HTTP ${HTTP_CODE}"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${MCP_URI}" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
[ "${HTTP_CODE}" = "401" ] && echo "PASS [11.9]: MCP endpoint returns 401 without a token" \
  || echo "FAIL [11.9]: MCP endpoint returned HTTP ${HTTP_CODE}"

# The OAuth protected-resource metadata is public. The operator sets WANAKU_AUTH_ISSUER. Wanaku Server names its own
# MCP host as the authorization server and serves the issuer in its /.well-known/oauth-authorization-server document.
META=$(curl -s "${WANAKU_MCP_BASE_URL}/.well-known/oauth-protected-resource/default/mcp")
echo "${META}" | jq -e '.authorization_servers | length > 0' > /dev/null 2>&1 \
  && echo "PASS [11.9]: protected-resource metadata lists an authorization server" || echo "FAIL [11.9]: metadata: ${META}"
DISCOVERY=$(curl -s "${WANAKU_MCP_BASE_URL}/.well-known/oauth-authorization-server")
[ "$(echo "${DISCOVERY}" | jq -r '.issuer' 2>/dev/null)" = "${KEYCLOAK_URL}/realms/wanaku" ] \
  && echo "PASS [11.9]: protected-resource metadata names the issuer" || echo "FAIL [11.9]: discovery: ${DISCOVERY}"
```

**Expected result:**

- The output includes `PASS [11.9]: management API returns 401 without a token`.
- The output includes `PASS [11.9]: MCP endpoint returns 401 without a token`.
- The output includes `PASS [11.9]: protected-resource metadata lists an authorization server`.
- The output includes `PASS [11.9]: protected-resource metadata names the issuer`.
- The output contains no line starting with `FAIL`.

### Step 11.10: Log in

Follow [common/oidc-login-verification.md](common/oidc-login-verification.md).

Run its login and fallback blocks in the shared shell, overriding its separate-script instructions, so
`LOGIN_FAILED`, any updated `WANAKU_CLIENT_SECRET` and the selected `wanaku` function remain available.

Then read the access token for the
`wanaku mcp` commands. These commands do not read the stored credentials, so they need `--token`.

Run this block in the shared shell so the selected `wanaku` invocation is available and `MCP_TOKEN` survives
for the later MCP commands. Do not print the token.

```bash run
MCP_TOKEN=$(wanaku auth token --get --unmask 2>/dev/null)
[ -n "${MCP_TOKEN}" ] && echo "PASS [11.10]: access token available" || { echo "FAIL [11.10]: no access token"; return 1; }
```

**Expected result:**

- The output includes `PASS [11.10]: access token available`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

---

## Phase 12: Register an MCP Server as a Forward

### Step 12.1: Deploy the infra-remediation MCP fixture

Run this block in the shared Bash session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: test-mcp
spec:
  replicas: 1
  selector:
    matchLabels:
      app: test-mcp
  template:
    metadata:
      labels:
        app: test-mcp
    spec:
      containers:
        - name: test-mcp-server
          image: ${TEST_MCP_IMAGE}
          env:
            - name: QUARKUS_HTTP_PORT
              value: "8181"
            - name: QUARKUS_HTTP_HOST
              value: "0.0.0.0"
          ports:
            - containerPort: 8181
          readinessProbe:
            tcpSocket:
              port: 8181
            periodSeconds: 5
            failureThreshold: 60
---
apiVersion: v1
kind: Service
metadata:
  name: test-mcp
spec:
  selector:
    app: test-mcp
  ports:
    - port: 8181
      targetPort: 8181
EOF
oc rollout status deployment/test-mcp --timeout=100s -n "${WANAKU_NAMESPACE}" \
  && echo "PASS [12.1]: infra-remediation MCP fixture running" || { echo "FAIL [12.1]: infra-remediation MCP fixture not ready"; return 1; }
```

**Expected result:**

- The output includes `PASS [12.1]: infra-remediation MCP fixture running`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 12.2: Register the forward

The CLI uses the stored token from step 11.10. Do not pass `--no-auth`.

```bash run
wanaku forwards add --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} \
  --name infra-remediation --service "http://test-mcp:8181/mcp" --namespace default --plain
[ $? -eq 0 ] && echo "PASS [12.2]: forward 'infra-remediation' registered" || echo "FAIL [12.2]: forward not registered"
```

**Expected result:**

- The output includes `PASS [12.2]: forward 'infra-remediation' registered`.
- The output contains no line starting with `FAIL`.

### Step 12.3: Verify the discovery

```bash run
OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain 2>&1)
echo "${OUTPUT}" | grep -q "^restartService" && echo "PASS [12.3]: tool 'restartService' discovered" \
  || echo "FAIL [12.3]: 'restartService' not discovered: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [12.3]: tool 'restartService' discovered`.
- The output contains no line starting with `FAIL`.

### Step 12.4: Create a second namespace

```bash run
wanaku namespaces create "${MCP_E2E_NAMESPACE}" --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain > /dev/null \
  && echo "PASS [12.4]: namespace ${MCP_E2E_NAMESPACE} created" || echo "FAIL [12.4]: namespace not created"
```

**Expected result:**

- The output includes `PASS [12.4]: namespace <MCP_E2E_NAMESPACE> created`.
- The output contains no line starting with `FAIL`.

---

## Phase 13: MCP CLI Through the MCP Route

### Test 13.1: List tools, resources and prompts with a token

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^restartService " && echo "PASS [13.1]: restartService listed through the Route" || echo "FAIL [13.1]: ${OUTPUT}"

OUTPUT=$(wanaku mcp resource list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^safetyLimits " && echo "PASS [13.1]: safetyLimits listed" || echo "FAIL [13.1]: ${OUTPUT}"

OUTPUT=$(wanaku mcp prompt list --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^summarizeIncident " && echo "PASS [13.1]: summarizeIncident listed" || echo "FAIL [13.1]: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.1]: restartService listed through the Route`.
- The output includes `PASS [13.1]: safetyLimits listed`.
- The output includes `PASS [13.1]: summarizeIncident listed`.
- The output contains no line starting with `FAIL`.

### Test 13.2: The MCP Route rejects a request without a token

```bash run
wanaku mcp tool list --uri "${MCP_URI}" ${WANAKU_TLS_FLAG} --plain > /dev/null 2>&1
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "PASS [13.2]: request without token rejected (exit ${EXIT_CODE})" \
  || echo "FAIL [13.2]: request without token succeeded"
```

**Expected result:**

- The output includes `PASS [13.2]: request without token rejected`.
- The output contains no line starting with `FAIL`.

### Test 13.3: Namespace isolation through the Route

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/${MCP_E2E_NAMESPACE}/mcp" \
  --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>/dev/null)
echo "${OUTPUT}" | grep -q "^No tools found" && echo "PASS [13.3]: ${MCP_E2E_NAMESPACE} has no tools" \
  || echo "FAIL [13.3]: tools leaked: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.3]: <MCP_E2E_NAMESPACE> has no tools`.
- The output contains no line starting with `FAIL`.

### Test 13.4: The bare `/mcp` path is rejected

The MCP Route sends all paths to the MCP proxy. Wanaku Server rejects `/mcp` without a namespace.

```bash run
OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/mcp" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} --plain 2>&1)
echo "${OUTPUT}" | grep -q "invalid MCP endpoint path" && echo "PASS [13.4]: bare /mcp rejected" \
  || echo "FAIL [13.4]: output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [13.4]: bare /mcp rejected`.
- The output contains no line starting with `FAIL`.

---

## Phase 14: Governed Calls on OpenShift

### Test 14.1: Calls get the fail-safe denial

The operator starts Wanaku Server without a governance configuration. The default posture denies `tools/call`,
`resources/read` and `prompts/get`.

```bash run
OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 1 ] && echo "${OUTPUT}" | grep -Eq "No governance policy permits this action.|evaluator: no_match"; then
  echo "PASS [14.1]: tools/call denied by the fail-safe default"
else
  echo "FAIL [14.1]: exit ${EXIT_CODE}, output: ${OUTPUT}"
fi

OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --resource-uri config://policies/safety-limits --plain 2>&1)
echo "${OUTPUT}" | grep -Eq "No governance policy permits this action.|evaluator: no_match" \
  && echo "PASS [14.1]: resources/read denied" || echo "FAIL [14.1]: output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS [14.1]: tools/call denied by the fail-safe default`.
- The output includes `PASS [14.1]: resources/read denied`.
- The output contains no line starting with `FAIL`.

### Step 14.2: Allow calls with a custom wanaku.yaml

The operator reads a custom `wanaku.yaml` from the ConfigMap that `spec.server.configMapRef` names. This step
creates a ConfigMap with the posture `no_match: allow` and references it from the router. The operator copies the
file into the `<router>-server-config` ConfigMap and restarts the Server. The forward from Phase 12 stays registered,
because the Server keeps its registry on its PVC.

Run this block in the shared Bash session.

```bash run
GOVERNANCE_CM="${WANAKU_ROUTER_NAME}-governance"
GOVERNANCE_YAML='governance:
  default:
    mode: enforce
    no_match: allow
    on_failure: deny
'
oc create configmap "${GOVERNANCE_CM}" -n "${WANAKU_NAMESPACE}" --from-literal=wanaku.yaml="${GOVERNANCE_YAML}" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f - > /dev/null \
  || { echo "FAIL [14.2]: cannot create ConfigMap ${GOVERNANCE_CM}"; return 1; }
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p "{\"spec\":{\"server\":{\"configMapRef\":{\"name\":\"${GOVERNANCE_CM}\"}}}}" > /dev/null \
  || { echo "FAIL [14.2]: cannot set spec.server.configMapRef"; return 1; }

# Wait until the operator mounts the file in the Server pod template
for i in $(seq 1 20); do
  MOUNTED=$(oc get deployment "${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath='{.spec.template.spec.volumes[?(@.name=="server-config")].configMap.name}')
  [ "${MOUNTED}" = "${WANAKU_ROUTER_NAME}-server-config" ] && break
  sleep 3
done
[ "${MOUNTED}" = "${WANAKU_ROUTER_NAME}-server-config" ] && echo "PASS [14.2]: the Server mounts ${MOUNTED}" \
  || { echo "FAIL [14.2]: the Server does not mount the configuration (volume '${MOUNTED}')"; return 1; }
oc get configmap "${WANAKU_ROUTER_NAME}-server-config" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}' \
  | grep -q "no_match: allow" && echo "PASS [14.2]: the managed ConfigMap holds the custom posture" \
  || echo "FAIL [14.2]: the managed ConfigMap does not hold the custom posture"
oc rollout status "deployment/${WANAKU_ROUTER_NAME}-server" --timeout=90s -n "${WANAKU_NAMESPACE}" > /dev/null \
  && echo "PASS [14.2]: the Server restarted with the custom configuration" \
  || { echo "FAIL [14.2]: the Server rollout did not finish"; return 1; }
```

**Expected result:**

- The output includes `PASS [14.2]: the Server mounts <WANAKU_ROUTER_NAME>-server-config`.
- The output includes `PASS [14.2]: the managed ConfigMap holds the custom posture`.
- The output includes `PASS [14.2]: the Server restarted with the custom configuration`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Test 14.3: Calls succeed when governance allows them

Run this block in the shared Bash session.

```bash run
for i in $(seq 1 10); do
  HTTP_CODE=$(curl -s --connect-timeout 3 --max-time 5 -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health" 2>/dev/null || echo "000")
  [ "${HTTP_CODE}" = "200" ] && break
  [ "${i}" -eq 10 ] && { echo "FAIL [14.3]: health returned HTTP ${HTTP_CODE}"; return 1; }
  sleep 5
done

OUTPUT=$(wanaku mcp tool --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --name restartService --param serverId=test-server --param service=nginx --plain 2>&1)
echo "${OUTPUT}" | grep -qxF "SUCCESS: Service 'nginx' restarted on server 'test-server'." \
  && echo "PASS [14.3]: restartService called through the Route" || echo "FAIL [14.3]: '${OUTPUT}'"
OUTPUT=$(wanaku mcp prompt --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --name draftEscalation --arg urgency=high --arg reason=memory-pressure --plain 2>&1)
echo "${OUTPUT}" | grep -q "urgency=high" && echo "PASS [14.3]: draftEscalation rendered" || echo "FAIL [14.3]: '${OUTPUT}'"
OUTPUT=$(wanaku mcp resource --uri "${MCP_URI}" --token "${MCP_TOKEN}" ${WANAKU_TLS_FLAG} \
  --resource-uri config://policies/safety-limits --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && ! echo "${OUTPUT}" | grep -Eq "No governance policy permits this action.|evaluator: no_match"; then
  echo "PASS [14.3]: resources/read allowed"
else
  echo "FAIL [14.3]: resources/read exit ${EXIT_CODE}, output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS [14.3]: restartService called through the Route`.
- The output includes `PASS [14.3]: draftEscalation rendered`.
- The output includes `PASS [14.3]: resources/read allowed`.
- The output contains no line starting with `FAIL`.

---

## Phase 15: Cleanup

### Step 15.1: Remove the test data and workloads

```bash run
wanaku forwards remove --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --name infra-remediation --plain > /dev/null 2>&1 || true
wanaku namespaces delete "${MCP_E2E_NAMESPACE}" --host "${WANAKU_SERVER_URL}" ${WANAKU_TLS_FLAG} --plain > /dev/null 2>&1 || true
oc delete deployment test-mcp -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete service test-mcp -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete configmap "${WANAKU_ROUTER_NAME}-governance" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-server" "${WANAKU_NAMESPACE}" 45 || true
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-oauth2-proxy" "${WANAKU_NAMESPACE}" 45 || true
oc delete secret "${WANAKU_ROUTER_NAME}-oidc" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
wanaku auth logout > /dev/null 2>&1 || true
rm -rf "${WANAKU_CREDENTIALS}" 2>/dev/null || true
if [ -n "${WANAKU_DOWNLOADED_SOURCE_DIR}" ]; then
  rm -rf "${WANAKU_DOWNLOADED_SOURCE_DIR}"
  unset WANAKU_DOWNLOADED_SOURCE_DIR
fi
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 15.2: Full cleanup

Clean up Keycloak, the operator and the namespace.

Follow [common/cleanup.md](common/cleanup.md).

**Expected result:**

- All cleanup procedure steps satisfy their expected results.

---

## Test Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| S | S.1 | CLI invocation | High |
| 10 | 10.1 | OpenShift login (MANUAL) | Critical |
| 11 | 11.1 | Create the namespace | Critical |
| T | S.3-S.4 | OpenShift environment and deletion helper | High |
| 11b | 11.2-11.10 | Keycloak, operator, authenticated WanakuRouter, Routes and login | Critical |
| 12 | 12.1-12.4 | In-cluster MCP fixture registered as a forward | Critical |
| 13 | 13.1-13.4 | MCP discovery, token requirement, isolation and bare `/mcp` rejection | Critical |
| 14 | 14.1-14.3 | Fail-safe denial; custom wanaku.yaml through `spec.server.configMapRef`; governed success path | High |
| 15 | 15.1-15.2 | Cleanup | Critical |
