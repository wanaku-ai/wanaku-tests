# Test Plan: Camel Integration Capability on OpenShift

## Overview

This test plan verifies the Camel Integration Capability (CIC) on OpenShift with Wanaku Server. The CIC loads Apache
Camel routes, serves the `ai-tool:` routes as MCP tools, and is registered with Wanaku Server as an MCP forward.
Wanaku Server discovers the tools from the forward and serves them to MCP clients at `/<namespace>/mcp`.

The plan covers the three supported ways to run a CIC on OpenShift:

1. **`WanakuIntegration` with routes from a ConfigMap** (Phases 4 to 6). The operator creates the `<name>-cic`
   Deployment and Service. The runtime registers itself as a forward (self-registration) and removes the forward when
   it stops.
2. **`WanakuServiceCatalog` + `WanakuIntegration` with a service catalog source** (Phase 7). The operator deploys the
   packaged catalog to Wanaku Barn. The runtime downloads the catalog from Barn.
3. **A plain `Deployment` registered with `wanaku forwards add`** (Phase 8), as described in `docs/operator.md`
   ("Registering Capability Services").

The removed `WanakuCamelRoute` and `WanakuCapability` CRDs are not tested. `WanakuIntegration` replaces them.

**Components under test:**

| Component | How the plan uses it |
|-----------|----------------------|
| Wanaku operator | Reconciles `WanakuRouter`, `WanakuServiceCatalog` and `WanakuIntegration` |
| Wanaku Server | Deployment `<router>-server`. Management API through the `<router>` Route, MCP through the `<router>-mcp` Route. The runtimes register with `http://server-<router>:8080`. |
| Wanaku Barn | Required: `spec.barn.enabled: true`. Stores the service catalog. The runtimes use `http://barn-<router>:8080` (`REGISTRATION_URL`). |
| CIC runtime | Image `quay.io/wanaku/camel-integration-capability`. MCP and health endpoints on port 9090. |

Authentication is disabled in this plan. [operator-basic-functionality.md](operator-basic-functionality.md) covers
oauth2-proxy. The runtimes call the internal `server-<router>` Service, which oauth2-proxy does not protect, so
registration works the same way when authentication is enabled.

> [!IMPORTANT]
> **Governance.** Wanaku Server is fail-safe. It denies every `tools/call`, `resources/read` and `prompts/get` request
> that no action policy and no evaluator explicitly allows. The error is
> `No governance policy permits this action.` (`governance_no_match`) from the action-policy filter, or
> `evaluator: no_match` from the evaluator filter. Listing requests are not governed.
>
> The governance posture is startup configuration in `wanaku.yaml`. The `WanakuRouter` CRD has no field for it, and no
> environment variable sets it. The operator mounts the `<router>-server-config` ConfigMap as
> `/etc/wanaku/wanaku.yaml` when Barn is enabled, and writes only the `plugins` section. Phase 5 adds a test
> `governance` section to that ConfigMap and restarts the Server. **This is a test-only workaround, not a supported
> operator API.** The operator writes the ConfigMap again when its own content changes (for example when Barn is
> disabled and enabled), and the governance section is then lost.

Every step is fully automatable.

**Hard timeout rule:** Any single operation (oc wait, polling loop, port-forward setup, etc.) that takes longer than **3 minutes** MUST be considered broken. Mark the test as FAIL immediately and abort — do not wait longer. All `--timeout` values in this plan MUST NOT exceed `180s`.

> [!NOTE]
> The CIC startup probe allows 10 minutes, because a runtime resolves Maven dependencies at startup. The routes in
> this plan need no extra dependencies, so a runtime starts in less than 3 minutes on a normal cluster. A slow first
> image pull is an environment problem. Pre-pull `${CIC_IMAGE}` if necessary.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `oc` | 4.12+ | `oc version --client` |
| `helm` | 3.x | `helm version --short` |
| `curl` | any (HTTP-level checks only) | `curl --version` |
| `jq` | 1.6+ | `jq --version` |
| `wanaku` CLI | build from source | `${WANAKU_CLI} --version` |

### Prerequisite check script



### Environment variables

Step 1 of [common/namespace-setup.md](common/namespace-setup.md) derives a unique `WANAKU_NAMESPACE`
for this run. The setup phase sets these variables. All image tags default to `latest` and can be overridden.



`WANAKU_CLI_TLS_OPTS` and `CURL_TLS_OPTS` are not quoted in the commands below on purpose: each holds zero or one
option.

### Helper: wait for resource deletion

The setup phase defines `wait_for_deletion` using [common/wait-for-deletion.md](common/wait-for-deletion.md).

### Helper: wait for a condition of a custom resource



### Helper: wait for a tool in Wanaku Server

Wanaku Server discovers the tools when the forward is registered. The function polls the tool list of the management
API.



---

## Phase S: Setup

### Step S.1: Verify prerequisites

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
for CMD in oc helm curl jq; do
  if ! command -v "${CMD}" > /dev/null 2>&1; then
    echo "FAIL: ${CMD} is not installed"
    FAIL=1
  else
    echo "PASS: ${CMD} found at $(command -v "${CMD}")"
  fi
done
${WANAKU_CLI:-wanaku} --help > /dev/null 2>&1 && echo "PASS: the Wanaku CLI runs" || { echo "FAIL: the Wanaku CLI does not run"; FAIL=1; }
[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || { echo "FAIL: one or more prerequisites missing"; exit 1; }
```

**Expected result:**

- The output includes `PASS: oc found at`.
- The output includes `PASS: helm found at`.
- The output includes `PASS: curl found at`.
- The output includes `PASS: jq found at`.
- The output includes `PASS: the Wanaku CLI runs`.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step S.2: Set environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WANAKU_BARN_IMAGE="${WANAKU_BARN_IMAGE:-quay.io/wanaku/wanaku-barn-backend:latest}"
export CIC_IMAGE="${CIC_IMAGE:-quay.io/wanaku/camel-integration-capability:latest}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-test-router}"
# Wanaku namespace for the catalog-based runtime (Phase 7). The ConfigMap runtime uses "default".
export CIC_CATALOG_NAMESPACE="${CIC_CATALOG_NAMESPACE:-cic-catalog-ns}"
# Wanaku namespaces that the test governance configuration allows (Phase 5)
export CIC_GOVERNED_NAMESPACES="${CIC_GOVERNED_NAMESPACES:-default,${CIC_CATALOG_NAMESPACE}}"
# Wanaku CLI: an installed binary, or "java -jar ${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
export WANAKU_CLI="${WANAKU_CLI:-wanaku}"
# The operator creates edge-terminated Routes. Set these to empty values when the ingress certificate is trusted.
export WANAKU_CLI_TLS_OPTS="${WANAKU_CLI_TLS_OPTS---insecure}"
export CURL_TLS_OPTS="${CURL_TLS_OPTS--k}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step S.3: Define condition and assertion helpers

```bash run
wait_cr_condition() {
  # $1 = kind, $2 = name, $3 = condition type, $4 = expected status, $5 = expected reason (optional)
  WCC_END=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt "${WCC_END}" ]; do
    WCC_STATUS=$(oc get "$1" "$2" -n "${WANAKU_NAMESPACE}" \
      -o jsonpath="{.status.conditions[?(@.type==\"$3\")].status}" 2>/dev/null)
    WCC_REASON=$(oc get "$1" "$2" -n "${WANAKU_NAMESPACE}" \
      -o jsonpath="{.status.conditions[?(@.type==\"$3\")].reason}" 2>/dev/null)
    if [ "${WCC_STATUS}" = "$4" ] && { [ -z "$5" ] || [ "${WCC_REASON}" = "$5" ]; }; then
      echo "PASS: $1/$2 condition $3=${WCC_STATUS} (reason ${WCC_REASON})"
      return 0
    fi
    sleep 3
  done
  echo "FAIL: $1/$2 condition $3 is '${WCC_STATUS}' (reason '${WCC_REASON}'), expected '$4' ${5:+(reason $5)}"
  oc get "$1" "$2" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.conditions}'; echo
  return 1
}

# Compares a value with the expected value. Sets FAIL=1 on a mismatch. Each test resets FAIL=0 first.
check() { # $1 = description, $2 = actual, $3 = expected
  if [ "$2" = "$3" ]; then echo "PASS: $1 = $2"; else echo "FAIL: $1 = '$2', expected '$3'"; FAIL=1; fi
}
```

**Expected result:**

- The block defines the functions without errors (inferred).

### Step S.4: Define tool discovery helper

```bash run
wait_for_tool() {
  # $1 = tool name
  for i in $(seq 1 40); do
    if ${WANAKU_CLI} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>/dev/null \
        | grep -q "$1"; then
      echo "PASS: tool $1 is registered in Wanaku Server (attempt ${i})"
      return 0
    fi
    sleep 3
  done
  echo "FAIL: tool $1 not registered in Wanaku Server"
  return 1
}
```

**Expected result:**

- The block defines the function without errors (inferred).

### Step S.5: Define deletion helper

Follow [common/wait-for-deletion.md](common/wait-for-deletion.md).

**Expected result:**

- The block defines the function without errors (inferred).

## Phase 0: OpenShift Login

### Step 0.0: Log in to OpenShift

Follow [common/openshift-login.md](common/openshift-login.md).

to log in to the target OpenShift cluster using a service account token.


**Expected result:**

- All invoked procedure steps pass (inferred).

---

## Phase 1: Environment Setup

### Step 1.0: Prepare the namespace

Follow [common/namespace-setup.md](common/namespace-setup.md).

to create and verify the namespace. This plan does not
need Keycloak.


**Expected result:**

- All invoked procedure steps pass (inferred).

---

## Phase 2: Operator Installation

### Step 2.0: Install the operator

Follow [common/operator-deployment.md](common/operator-deployment.md).

to install the operator via Helm and verify CRDs, RBAC, and health endpoints.


**Expected result:**

- All invoked procedure steps pass (inferred).

---

## Phase 3: WanakuRouter Setup

### Test 3.1: Create a WanakuRouter with Wanaku Barn

Run this block as a separate `bash` script, not in the shared session.

```bash
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  imagePullPolicy: Always
  server:
    image: ${WANAKU_SERVER_IMAGE}
  barn:
    enabled: true
    image: ${WANAKU_BARN_IMAGE}
    storage:
      size: 1Gi
EOF

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"
# Expected output: wanakurouter.wanaku.ai/wanaku-test-router condition met
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" BarnAvailable True Available || exit 1
```

**Expected result:**

- The commands exit with code 0.

### Test 3.2: Read the Route URLs and verify the Server is reachable

Run this block as a separate `bash` script, not in the shared session.

```bash
export WANAKU_ROUTER_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_ROUTER_MCP_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
echo "management: ${WANAKU_ROUTER_URL}"
echo "mcp:        ${WANAKU_ROUTER_MCP_URL}"

# curl: HTTP-level reachability check before the CLI connects
CODE=$(curl -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/health")
[ "${CODE}" = "200" ] && echo "PASS: Wanaku Server /health returns 200" || { echo "FAIL: /health returned HTTP ${CODE}"; exit 1; }

${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain
[ $? -eq 0 ] && echo "PASS: the CLI reaches the management API" || { echo "FAIL: forwards list failed"; exit 1; }
```

**Expected result:**

- The output includes `PASS: Wanaku Server /health returns 200`.
- The output includes `PASS: the CLI reaches the management API`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 4: WanakuIntegration With Routes From a ConfigMap

### Test 4.1: Create the route ConfigMap

**Description:** The ConfigMap holds two `ai-tool:` routes. `cic-echo` has the tag `wanaku`. `cic-hidden` has the tag
`other`. The runtime serves only the routes whose tags match `spec.mcp.tags`.

```bash run
cat <<'EOF' | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: cic-echo-routes
  labels:
    wanaku-test: "true"
data:
  cic-echo.camel.yaml: |
    - route:
        id: cic-echo
        from:
          uri: ai-tool:cic-echo
          parameters:
            tags: wanaku
            description: "Echo back the input message duplicated with a separator"
            parameter.message: string
            parameter.message.description: "The message to echo back"
          steps:
            - setBody:
                expression:
                  simple:
                    expression: "${header.message}-${header.message}"
    - route:
        id: cic-hidden
        from:
          uri: ai-tool:cic-hidden
          parameters:
            tags: other
            description: "A tool that the tag filter must hide"
          steps:
            - setBody:
                constant: "hidden"
EOF

oc get configmap cic-echo-routes -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data}' | jq -r 'keys[]' | grep -q "^cic-echo.camel.yaml$"
echo "configmap-has-routes=$?"
# Expected: configmap-has-routes=0
```

**Expected result:**

- The output includes `configmap-has-routes=0`.

### Test 4.2: Create the WanakuIntegration

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuIntegration
metadata:
  name: cic-echo
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  image: ${CIC_IMAGE}
  imagePullPolicy: Always
  routes:
    configMapRef:
      name: cic-echo-routes
      main: cic-echo.camel.yaml
  mcp:
    port: 9090
    tags: wanaku
  registration:
    enabled: true
    namespace: default
EOF

oc get wanakuintegration cic-echo -n "${WANAKU_NAMESPACE}" -o name
echo "integration-cr-exists=$?"
# Expected: integration-cr-exists=0
```

**Expected result:**

- The output includes `integration-cr-exists=0`.

### Test 4.3: Verify the WanakuIntegration becomes Ready

**Description:** `Ready` requires `Reconciled`, `Available` (the runtime pod is ready) and `Registered` (Wanaku
Server has an available forward named `cic-echo` with the `cic.instance` label of the runtime).

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
wait_cr_condition wanakuintegration cic-echo Reconciled True ReconcileSucceeded || FAIL=1
wait_cr_condition wanakuintegration cic-echo Available True Available || FAIL=1
wait_cr_condition wanakuintegration cic-echo Registered True Registered || FAIL=1
wait_cr_condition wanakuintegration cic-echo Ready True || FAIL=1
[ "${FAIL}" -eq 0 ] || { oc logs deployment/cic-echo-cic -n "${WANAKU_NAMESPACE}" --tail=30; exit 1; }
```

**Expected result:**

- The commands exit with code 0.

### Test 4.4: Verify the runtime Deployment

**Description:** The operator creates the `<name>-cic` Deployment. It passes the routes, the tags and the
registration settings to the runtime as environment variables.

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
cic_jsonpath() { oc get deployment cic-echo-cic -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
cic_env() { cic_jsonpath "{.spec.template.spec.containers[0].env[?(@.name==\"$1\")].value}"; }

check "image" "$(cic_jsonpath '{.spec.template.spec.containers[0].image}')" "${CIC_IMAGE}"
check "REGISTRATION_URL (Barn)" "$(cic_env REGISTRATION_URL)" "http://barn-${WANAKU_ROUTER_NAME}:8080"
check "SERVICE_NAME" "$(cic_env SERVICE_NAME)" "cic-echo"
check "ROUTES_PATH" "$(cic_env ROUTES_PATH)" "file:///opt/cic/routes/cic-echo.camel.yaml"
check "MCP_PORT" "$(cic_env MCP_PORT)" "9090"
check "MCP_TAGS" "$(cic_env MCP_TAGS)" "wanaku"
check "WANAKU_REGISTER" "$(cic_env WANAKU_REGISTER)" "true"
check "WANAKU_URL" "$(cic_env WANAKU_URL)" "http://server-${WANAKU_ROUTER_NAME}:8080"
check "WANAKU_NAMESPACE" "$(cic_env WANAKU_NAMESPACE)" "default"
check "MCP_ADDRESS" "$(cic_env MCP_ADDRESS)" "http://cic-echo-cic.${WANAKU_NAMESPACE}.svc:9090/mcp"
check "readiness probe" "$(cic_jsonpath '{.spec.template.spec.containers[0].readinessProbe.httpGet.path}')" "/observe/health/ready"
check "liveness probe" "$(cic_jsonpath '{.spec.template.spec.containers[0].livenessProbe.httpGet.path}')" "/observe/health/live"
check "strategy" "$(cic_jsonpath '{.spec.strategy.type}')" "RollingUpdate"
check "maxUnavailable" "$(cic_jsonpath '{.spec.strategy.rollingUpdate.maxUnavailable}')" "0"
check "routes volume" "$(cic_jsonpath '{.spec.template.spec.volumes[?(@.name=="routes")].configMap.name}')" "cic-echo-routes"
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: image =` with the expected value.
- The output includes `PASS: REGISTRATION_URL (Barn) =` with the expected value.
- The output includes `PASS: SERVICE_NAME =` with the expected value.
- The output includes `PASS: ROUTES_PATH =` with the expected value.
- The output includes `PASS: MCP_PORT =` with the expected value.
- The output includes `PASS: MCP_TAGS =` with the expected value.
- The output includes `PASS: WANAKU_REGISTER =` with the expected value.
- The output includes `PASS: WANAKU_URL =` with the expected value.
- The output includes `PASS: WANAKU_NAMESPACE =` with the expected value.
- The output includes `PASS: MCP_ADDRESS =` with the expected value.
- The output includes `PASS: readiness probe =` with the expected value.
- The output includes `PASS: liveness probe =` with the expected value.
- The output includes `PASS: strategy =` with the expected value.
- The output includes `PASS: maxUnavailable =` with the expected value.
- The output includes `PASS: routes volume =` with the expected value.
- The commands exit with code 0.

### Test 4.5: Verify the runtime Service and the status

**Description:** The `<name>-cic` Service publishes the addresses of pods that are not ready, because Wanaku Server
discovers the tools while the runtime registers, before the pod is ready.

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
check "Service port" "$(oc get service cic-echo-cic -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[0].port}')" "9090"
check "Service port name" "$(oc get service cic-echo-cic -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[0].name}')" "mcp"
check "publishNotReadyAddresses" "$(oc get service cic-echo-cic -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.publishNotReadyAddresses}')" "true"

int_jsonpath() { oc get wanakuintegration cic-echo -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
check "status.forwardName" "$(int_jsonpath '{.status.forwardName}')" "cic-echo"
check "status.mcpAddress" "$(int_jsonpath '{.status.mcpAddress}')" "http://cic-echo-cic.${WANAKU_NAMESPACE}.svc:9090/mcp"
echo "status.wanakuEndpoint=$(int_jsonpath '{.status.wanakuEndpoint}')"
[ "${FAIL}" -eq 0 ] || exit 1
```

> [!WARNING]
> **Known issue:** for the `default` namespace, `status.wanakuEndpoint` is the router `status.endpoints.mcp` value
> (`https://<mcp-host>/mcp`). Wanaku Server rejects the bare `/mcp` path. MCP clients must use
> `${WANAKU_ROUTER_MCP_URL}/default/mcp`. Report the issue if it is still open.

**Expected result:**

- The output includes `PASS: Service port =` with the expected value.
- The output includes `PASS: Service port name =` with the expected value.
- The output includes `PASS: publishNotReadyAddresses =` with the expected value.
- The output includes `PASS: status.forwardName =` with the expected value.
- The output includes `PASS: status.mcpAddress =` with the expected value.
- The commands exit with code 0.

### Test 4.6: Verify the forward in Wanaku Server

**Description:** The runtime registered the forward `cic-echo` in the namespace `default`, with the `cic.instance`,
`cic.version` and `cic.routes-digest` labels. The CLI does not print forward labels, so `curl` reads
`GET /api/v1/forwards/{name}`.

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "^cic-echo[[:space:]]*http://cic-echo-cic.${WANAKU_NAMESPACE}.svc:9090/mcp" \
  && echo "PASS: forward cic-echo is listed with the runtime address" \
  || { echo "FAIL: forward cic-echo not listed"; exit 1; }

FORWARD=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/forwards/cic-echo")
echo "${FORWARD}" | jq '.data | {name, namespace, available, labels}'
FAIL=0
check "forward namespace" "$(echo "${FORWARD}" | jq -r '.data.namespace')" "default"
check "forward available" "$(echo "${FORWARD}" | jq -r '.data.available')" "true"
for LABEL in cic.instance cic.version cic.routes-digest; do
  [ "$(echo "${FORWARD}" | jq -r --arg L "${LABEL}" '.data.labels[$L] // empty')" != "" ] \
    && echo "PASS: forward label ${LABEL} is set" || { echo "FAIL: forward label ${LABEL} missing"; FAIL=1; }
done
export CIC_ECHO_INSTANCE=$(echo "${FORWARD}" | jq -r '.data.labels["cic.instance"]')
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: forward cic-echo is listed with the runtime address`.
- The output includes `PASS: forward label cic.instance is set`.
- The output includes `PASS: forward label cic.version is set`.
- The output includes `PASS: forward label cic.routes-digest is set`.
- The output contains no line starting with `FAIL:`.
- The output includes `PASS: forward namespace =` with the expected value.
- The output includes `PASS: forward available =` with the expected value.
- The commands exit with code 0.

### Test 4.7: Verify the discovered tools and the tag filter

Run this block as a separate `bash` script, not in the shared session.

```bash
wait_for_tool cic-echo || exit 1

OUTPUT=$(${WANAKU_CLI} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "cic-hidden" \
  && { echo "FAIL: cic-hidden is registered although its tag does not match spec.mcp.tags"; exit 1; } \
  || echo "PASS: the tag filter hides cic-hidden"

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "cic-echo" && echo "PASS: cic-echo is listed at /default/mcp" \
  || { echo "FAIL: cic-echo not listed at /default/mcp"; exit 1; }

# Namespace isolation: the tool is not served in another namespace
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/${CIC_CATALOG_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "cic-echo" \
  && { echo "FAIL: cic-echo is served in ${CIC_CATALOG_NAMESPACE}"; exit 1; } \
  || echo "PASS: cic-echo is not served in ${CIC_CATALOG_NAMESPACE}"
```

**Expected result:**

- The output includes `PASS: the tag filter hides cic-hidden`.
- The output includes `PASS: cic-echo is listed at /default/mcp`.
- The output includes `PASS: cic-echo is not served in <CIC_CATALOG_NAMESPACE>`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 5: Governance and Tool Invocation

### Test 5.1: Verify the fail-safe default denies the tool call

**Description:** Without a governance configuration, Wanaku Server denies `tools/call`. The CLI prints the denial and
exits with a non-zero code.

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(${WANAKU_CLI} mcp tool --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name cic-echo --param message=hello 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
if echo "${OUTPUT}" | grep -qE "No governance policy permits this action|evaluator: no_match"; then
  echo "PASS: the tool call is denied by the fail-safe governance default (exit code ${EXIT_CODE})"
else
  echo "FAIL: the tool call was not denied"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: the tool call is denied by the fail-safe governance default (exit code <EXIT_CODE>)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.2: Apply the test governance configuration (test-only workaround)

**Description:** Add a `governance` section to the operator-generated `wanaku.yaml` that allows unmatched actions only
in `${CIC_GOVERNED_NAMESPACES}`. The `plugins` section stays. The ConfigMap is mounted with `subPath`, so the running
pod does not see the change: restart the Server. The registry (forwards and tools) is on the PVC, so it survives the
restart.

Run this block as a separate `bash` script, not in the shared session.

```bash
CM="${WANAKU_ROUTER_NAME}-server-config"
GOV_FILE=$(mktemp)
oc get configmap "${CM}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}' > "${GOV_FILE}"
echo "" >> "${GOV_FILE}"
if ! grep -q "^governance:" "${GOV_FILE}"; then
  {
    echo "governance:"
    echo "  default:"
    echo "    mode: enforce"
    echo "    no_match: deny"
    echo "    on_failure: deny"
    echo "  namespaces:"
    for NS in $(echo "${CIC_GOVERNED_NAMESPACES}" | tr ',' ' '); do
      echo "    ${NS}:"
      echo "      no_match: allow"
    done
  } >> "${GOV_FILE}"
fi
cat "${GOV_FILE}"

oc set data "configmap/${CM}" -n "${WANAKU_NAMESPACE}" --from-file=wanaku.yaml="${GOV_FILE}"
rm -f "${GOV_FILE}"

oc get configmap "${CM}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}' | grep -q "^governance:" \
  && echo "PASS: governance section added" || { echo "FAIL: governance section missing"; exit 1; }
oc get configmap "${CM}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}' | grep -q "wanaku-barn-api" \
  && echo "PASS: plugins section kept" || { echo "FAIL: plugins section lost"; exit 1; }

oc rollout restart "deployment/${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}"
oc rollout status "deployment/${WANAKU_ROUTER_NAME}-server" --timeout=180s -n "${WANAKU_NAMESPACE}"
oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"

# An invalid governance section stops the Server at startup, so a Ready Server also validates the YAML
wait_for_tool cic-echo || exit 1
```

**Expected result:**

- The output includes `PASS: governance section added`.
- The output includes `PASS: plugins section kept`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.3: Invoke the tool through the MCP Route

**Description:** The route returns the message twice with a `-` separator.

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(${WANAKU_CLI} mcp tool --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name cic-echo --param message=hello 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "hello-hello"; then
  echo "PASS: cic-echo returned hello-hello"
else
  echo "FAIL: unexpected tool result (exit code ${EXIT_CODE})"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: cic-echo returned hello-hello`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.4: Negative test - invoke a tool that does not exist

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(${WANAKU_CLI} mcp tool --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name non-existent-tool-99999 --param key=value 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "PASS: invocation of a non-existent tool failed (exit code ${EXIT_CODE})"
else
  echo "FAIL: invocation of a non-existent tool succeeded"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: invocation of a non-existent tool failed (exit code <EXIT_CODE>)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.5: Verify the audit trail records the governance decisions

**Description:** Wanaku Server records each governance decision in the audit trail. The denied call of Test 5.1 has
`decision` `block` and `reason_code` `governance_no_match`. The CLI has no audit command, so `curl` reads
`GET /api/v1/audit/events`. The response is `{"data":{"events":[...],"limit":..,"offset":..,"total":..},"error":null}`.

Run this block as a separate `bash` script, not in the shared session.

```bash
# The list endpoint filters by target; one page holds up to 100 events by default
EVENTS=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/audit/events?target=cic-echo")
echo "${EVENTS}" | jq -e '.data.events' > /dev/null 2>&1 && echo "PASS: audit events endpoint returns events" \
  || { echo "FAIL: unexpected audit response: ${EVENTS}"; exit 1; }

BLOCKED=$(echo "${EVENTS}" | jq '[.data.events[] | select(.operation=="tools/call" and .target=="cic-echo"
  and .decision=="block" and .reason_code=="governance_no_match")] | length')
[ "${BLOCKED}" -ge 1 ] && echo "PASS: the denied cic-echo call is in the audit trail (${BLOCKED} event(s))" \
  || echo "FAIL: no blocked tools/call event for cic-echo"

echo "${EVENTS}" | jq -c '[.data.events[] | select(.target=="cic-echo") | {operation, decision, reason_code, namespace}]'
```

**Expected result:**

- The output includes `PASS: audit events endpoint returns events`.
- The output includes `PASS: the denied cic-echo call is in the audit trail (<BLOCKED> event(s))`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 6: Runtime Replacement and Forward Ownership

### Test 6.1: Replace the runtime pod

**Description:** A new pod registers the forward again with a new `cic.instance` label. The old pod does not remove
the forward at shutdown, because the label no longer matches its own identifier.

Run this block as a separate `bash` script, not in the shared session.

```bash
OLD_POD=$(oc get pods -l app=cic-echo-cic -n "${WANAKU_NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
oc rollout restart deployment/cic-echo-cic -n "${WANAKU_NAMESPACE}"
oc rollout status deployment/cic-echo-cic --timeout=180s -n "${WANAKU_NAMESPACE}"
wait_for_deletion pod "${OLD_POD}" "${WANAKU_NAMESPACE}" 120 || exit 1

wait_cr_condition wanakuintegration cic-echo Registered True Registered || exit 1

NEW_INSTANCE=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/forwards/cic-echo" | jq -r '.data.labels["cic.instance"]')
if [ -n "${NEW_INSTANCE}" ] && [ "${NEW_INSTANCE}" != "${CIC_ECHO_INSTANCE}" ]; then
  echo "PASS: the new runtime owns the forward (cic.instance ${CIC_ECHO_INSTANCE} -> ${NEW_INSTANCE})"
else
  echo "FAIL: cic.instance did not change ('${CIC_ECHO_INSTANCE}' -> '${NEW_INSTANCE}')"
  exit 1
fi

OUTPUT=$(${WANAKU_CLI} mcp tool --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name cic-echo --param message=again 2>&1)
echo "${OUTPUT}" | grep -q "again-again" && echo "PASS: the tool works after the replacement" \
  || { echo "FAIL: the tool does not work after the replacement: ${OUTPUT}"; exit 1; }
```

**Expected result:**

- The output includes `PASS: the new runtime owns the forward (cic.instance <CIC_ECHO_INSTANCE> -> <NEW_INSTANCE>)`.
- The output includes `PASS: the tool works after the replacement`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 7: Service Catalog Mode

### Test 7.1: Package the catalog and deploy it with WanakuServiceCatalog

Run this block as a separate `bash` script, not in the shared session.

```bash
export CIC_CATALOG="${CIC_CATALOG:-cic-hello-catalog}"
export CIC_CATALOG_SYSTEM="${CIC_CATALOG_SYSTEM:-cic-hello}"
TEMP_DIR=$(mktemp -d)
mkdir -p "${TEMP_DIR}/catalog/${CIC_CATALOG_SYSTEM}"
cat > "${TEMP_DIR}/catalog/index.properties" <<PROPS
catalog.name=${CIC_CATALOG}
catalog.description=Hello catalog for the CIC test plan
catalog.services=${CIC_CATALOG_SYSTEM}
catalog.routes.${CIC_CATALOG_SYSTEM}=${CIC_CATALOG_SYSTEM}/routes.camel.yaml
PROPS
cat > "${TEMP_DIR}/catalog/${CIC_CATALOG_SYSTEM}/routes.camel.yaml" <<'ROUTES'
- route:
    id: cic-catalog-greet
    from:
      uri: ai-tool:cic-catalog-greet
      parameters:
        description: "Greets a person by name"
        parameter.name: string
        parameter.name.description: "The name of the person to greet"
      steps:
        - setBody:
            expression:
              simple:
                expression: "Hello ${header.name} from the catalog"
ROUTES

${WANAKU_CLI} service package --path="${TEMP_DIR}/catalog" -o "${TEMP_DIR}/catalog.b64"
[ -s "${TEMP_DIR}/catalog.b64" ] && echo "PASS: catalog packaged" || { echo "FAIL: catalog not packaged"; exit 1; }

oc create configmap cic-hello-catalog-data --from-file=catalog.zip="${TEMP_DIR}/catalog.b64" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label configmap cic-hello-catalog-data wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"
rm -rf "${TEMP_DIR}"

cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: cic-test-service-catalogs
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: ${CIC_CATALOG}
      configMapRef: cic-hello-catalog-data
EOF

wait_cr_condition wanakuservicecatalog cic-test-service-catalogs Ready True ReconciliationSucceeded || exit 1
oc get wanakuservicecatalog cic-test-service-catalogs -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.deployedCatalogs[*]}' \
  | grep -qw "${CIC_CATALOG}" && echo "PASS: ${CIC_CATALOG} deployed to Barn" || { echo "FAIL: ${CIC_CATALOG} not deployed"; exit 1; }
```

**Expected result:**

- The output includes `PASS: catalog packaged`.
- The output includes `PASS: <CIC_CATALOG> deployed to Barn`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.2: Create a WanakuIntegration with the service catalog source

**Description:** The runtime downloads the catalog from Barn and registers its forward in the Wanaku namespace
`${CIC_CATALOG_NAMESPACE}`. The forward name is `cic-catalog-fwd` (`spec.registration.name`).

Run this block as a separate `bash` script, not in the shared session.

```bash
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuIntegration
metadata:
  name: cic-catalog
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  image: ${CIC_IMAGE}
  imagePullPolicy: Always
  routes:
    serviceCatalog:
      name: ${CIC_CATALOG}
      system: ${CIC_CATALOG_SYSTEM}
  registration:
    name: cic-catalog-fwd
    namespace: ${CIC_CATALOG_NAMESPACE}
EOF

FAIL=0
wait_cr_condition wanakuintegration cic-catalog Registered True Registered || FAIL=1
wait_cr_condition wanakuintegration cic-catalog Ready True || FAIL=1
[ "${FAIL}" -eq 0 ] || { oc logs deployment/cic-catalog-cic -n "${WANAKU_NAMESPACE}" --tail=30; exit 1; }
```

**Expected result:**

- The commands exit with code 0.

### Test 7.3: Verify the catalog environment and the status

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
cic_env() { oc get deployment cic-catalog-cic -n "${WANAKU_NAMESPACE}" \
  -o jsonpath="{.spec.template.spec.containers[0].env[?(@.name==\"$1\")].value}"; }
check "SERVICE_CATALOG" "$(cic_env SERVICE_CATALOG)" "${CIC_CATALOG}"
check "SERVICE_CATALOG_SYSTEM" "$(cic_env SERVICE_CATALOG_SYSTEM)" "${CIC_CATALOG_SYSTEM}"
check "SERVICE_NAME" "$(cic_env SERVICE_NAME)" "cic-catalog-fwd"
check "WANAKU_NAMESPACE" "$(cic_env WANAKU_NAMESPACE)" "${CIC_CATALOG_NAMESPACE}"
[ -z "$(cic_env ROUTES_PATH)" ] && echo "PASS: no ROUTES_PATH in catalog mode" || { echo "FAIL: ROUTES_PATH is set"; FAIL=1; }

check "status.forwardName" "$(oc get wanakuintegration cic-catalog -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.forwardName}')" "cic-catalog-fwd"
ENDPOINT=$(oc get wanakuintegration cic-catalog -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.wanakuEndpoint}')
check "status.wanakuEndpoint" "${ENDPOINT}" "${WANAKU_ROUTER_MCP_URL}/${CIC_CATALOG_NAMESPACE}/mcp"
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: no ROUTES_PATH in catalog mode`.
- The output contains no line starting with `FAIL:`.
- The output includes `PASS: SERVICE_CATALOG =` with the expected value.
- The output includes `PASS: SERVICE_CATALOG_SYSTEM =` with the expected value.
- The output includes `PASS: SERVICE_NAME =` with the expected value.
- The output includes `PASS: WANAKU_NAMESPACE =` with the expected value.
- The output includes `PASS: status.forwardName =` with the expected value.
- The output includes `PASS: status.wanakuEndpoint =` with the expected value.
- The commands exit with code 0.

### Test 7.4: Verify the catalog tool is served only in its namespace, and invoke it

Run this block as a separate `bash` script, not in the shared session.

```bash
wait_for_tool cic-catalog-greet || exit 1

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/${CIC_CATALOG_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "cic-catalog-greet" && echo "PASS: cic-catalog-greet listed in ${CIC_CATALOG_NAMESPACE}" \
  || { echo "FAIL: cic-catalog-greet not listed in ${CIC_CATALOG_NAMESPACE}"; exit 1; }

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "cic-catalog-greet" \
  && { echo "FAIL: cic-catalog-greet is also served in default"; exit 1; } \
  || echo "PASS: cic-catalog-greet is not served in default"

OUTPUT=$(${WANAKU_CLI} mcp tool --uri "${WANAKU_ROUTER_MCP_URL}/${CIC_CATALOG_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name cic-catalog-greet --param name=Alice 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "Hello Alice from the catalog" && echo "PASS: cic-catalog-greet returned the greeting" \
  || { echo "FAIL: unexpected result"; exit 1; }
```

**Expected result:**

- The output includes `PASS: cic-catalog-greet listed in <CIC_CATALOG_NAMESPACE>`.
- The output includes `PASS: cic-catalog-greet is not served in default`.
- The output includes `PASS: cic-catalog-greet returned the greeting`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 8: Plain Deployment Registered With `wanaku forwards add`

### Test 8.1: Deploy a CIC runtime without the operator

**Description:** The runtime does not register itself (`WANAKU_REGISTER` is not set). Its route serves the tool
`cic-manual-echo`. Wanaku Server stores tools by name, not by namespace and name: a second forward that serves a tool
with the same name replaces the first entry, and removing that forward removes the tool. Use a tool name that no other
forward serves.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: cic-manual-routes
  labels:
    wanaku-test: "true"
data:
  cic-manual.camel.yaml: |
    - route:
        id: cic-manual-echo
        from:
          uri: ai-tool:cic-manual-echo
          parameters:
            description: "Echo back the input message duplicated with a separator"
            parameter.message: string
            parameter.message.description: "The message to echo back"
          steps:
            - setBody:
                expression:
                  simple:
                    expression: "\${header.message}-\${header.message}"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cic-manual
  labels:
    wanaku-test: "true"
spec:
  replicas: 1
  selector:
    matchLabels:
      app: cic-manual
  template:
    metadata:
      labels:
        app: cic-manual
    spec:
      containers:
        - name: cic
          image: ${CIC_IMAGE}
          imagePullPolicy: Always
          env:
            - name: REGISTRATION_URL
              value: http://barn-${WANAKU_ROUTER_NAME}:8080
            - name: SERVICE_NAME
              value: cic-manual
            - name: ROUTES_PATH
              value: file:///routes/cic-manual.camel.yaml
            - name: MCP_PORT
              value: "9090"
            - name: DATA_DIR
              value: /data
          ports:
            - name: mcp
              containerPort: 9090
          readinessProbe:
            httpGet:
              path: /observe/health/ready
              port: mcp
            periodSeconds: 5
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
          volumeMounts:
            - name: routes
              mountPath: /routes
              readOnly: true
            - name: data
              mountPath: /data
      volumes:
        - name: routes
          configMap:
            name: cic-manual-routes
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: cic-manual
  labels:
    wanaku-test: "true"
spec:
  selector:
    app: cic-manual
  ports:
    - name: mcp
      port: 9090
      targetPort: mcp
EOF

oc wait deployment/cic-manual --for=condition=Available --timeout=180s -n "${WANAKU_NAMESPACE}"
echo "cic-manual-available=$?"
# Expected: cic-manual-available=0
```

**Expected result:**

- The output includes `cic-manual-available=0`.

### Test 8.2: Register the runtime as a forward and verify discovery

**Description:** `forwards add` requires `--name`, `--service` and `--namespace`. The command prints nothing on
success. Wanaku Server discovers the tools while it creates the forward.

Run this block as a separate `bash` script, not in the shared session.

```bash
${WANAKU_CLI} forwards add --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name cic-manual --service "http://cic-manual.${WANAKU_NAMESPACE}.svc:9090/mcp" --namespace "${CIC_CATALOG_NAMESPACE}"
[ $? -eq 0 ] && echo "PASS: forward cic-manual added" || { echo "FAIL: forwards add failed"; exit 1; }

FORWARD=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/forwards/cic-manual")
FAIL=0
check "cic-manual available" "$(echo "${FORWARD}" | jq -r '.data.available')" "true"
[ "${FAIL}" -eq 0 ] || { echo "${FORWARD}" | jq -r '.data.statusMessage'; exit 1; }

OUTPUT=$(${WANAKU_CLI} mcp tool --uri "${WANAKU_ROUTER_MCP_URL}/${CIC_CATALOG_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name cic-manual-echo --param message=manual 2>&1)
echo "${OUTPUT}" | grep -q "manual-manual" && echo "PASS: the manually registered tool works" \
  || { echo "FAIL: unexpected result: ${OUTPUT}"; exit 1; }
```

**Expected result:**

- The output includes `PASS: forward cic-manual added`.
- The output includes `PASS: the manually registered tool works`.
- The output contains no line starting with `FAIL:`.
- The output includes `PASS: cic-manual available =` with the expected value.
- The commands exit with code 0.

### Test 8.3: Negative test - forward to an unreachable address

**Description:** Wanaku Server stores a forward whose upstream is unreachable, but marks it unavailable with a status
message. It discovers no tools for it.

Run this block as a separate `bash` script, not in the shared session.

```bash
${WANAKU_CLI} forwards add --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name cic-unreachable --service "http://cic-does-not-exist.${WANAKU_NAMESPACE}.svc:9090/mcp" --namespace "${CIC_CATALOG_NAMESPACE}"

FORWARD=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/forwards/cic-unreachable")
FAIL=0
check "cic-unreachable available" "$(echo "${FORWARD}" | jq -r '.data.available')" "false"
[ -n "$(echo "${FORWARD}" | jq -r '.data.statusMessage // empty')" ] && echo "PASS: statusMessage explains the failure" \
  || { echo "FAIL: no statusMessage"; FAIL=1; }

${WANAKU_CLI} forwards remove --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --name cic-unreachable
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: statusMessage explains the failure`.
- The output contains no line starting with `FAIL:`.
- The output includes `PASS: cic-unreachable available =` with the expected value.
- The commands exit with code 0.

### Test 8.4: Remove the forward and verify its tools are removed

Run this block as a separate `bash` script, not in the shared session.

```bash
${WANAKU_CLI} forwards remove --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --name cic-manual
[ $? -eq 0 ] && echo "PASS: forward cic-manual removed" || { echo "FAIL: forwards remove failed"; exit 1; }

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/${CIC_CATALOG_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "cic-manual-echo" \
  && { echo "FAIL: cic-manual-echo is still served in ${CIC_CATALOG_NAMESPACE}"; exit 1; } \
  || echo "PASS: the tools of the removed forward are gone"

# The other forwards keep their tools
wait_for_tool cic-echo || exit 1

oc delete deployment cic-manual -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete service cic-manual -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: forward cic-manual removed`.
- The output includes `PASS: the tools of the removed forward are gone`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 9: WanakuIntegration Negative Tests

The operator unit tests cover the field validation of `WanakuIntegration`. This phase keeps two checks that need a
live cluster. Each test creates an invalid resource, verifies `Reconciled=False` with the reason `ValidationError`
and the expected message, verifies that no Deployment is created, and deletes the resource.

### Step 9.0: Define the negative test helper

```bash run
expect_integration_invalid() {
  # $1 = resource name, $2 = text expected in the Reconciled message. The manifest is read from stdin.
  oc apply -n "${WANAKU_NAMESPACE}" -f -
  EII_RESULT=0
  wait_cr_condition wanakuintegration "$1" Reconciled False ValidationError || EII_RESULT=1
  oc get wanakuintegration "$1" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath='{.status.conditions[?(@.type=="Reconciled")].message}' | grep -q "$2" \
    && echo "PASS: message contains '$2'" || { echo "FAIL: message does not contain '$2'"; EII_RESULT=1; }
  if oc get deployment "$1-cic" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1; then
    echo "FAIL: Deployment $1-cic was created for an invalid resource"
    EII_RESULT=1
  fi
  oc delete wanakuintegration "$1" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
  return ${EII_RESULT}
}
```

**Expected result:**

- The block defines the function without errors (inferred).

### Test 9.1: Missing routerRef

```bash run
expect_integration_invalid bad-no-router-ref "spec.routerRef is required" <<'EOF'
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuIntegration
metadata:
  name: bad-no-router-ref
spec:
  routes:
    routesRef: datastore://orders.camel.yaml
EOF
```

**Expected result:**

- The invalid resource `bad-no-router-ref has `Reconciled=False` with reason `ValidationError`, its message contains `spec.routerRef is required`, and it creates no Deployment.

### Test 9.2: Non-existent routerRef

```bash run
expect_integration_invalid bad-missing-router "does not exist" <<'EOF'
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuIntegration
metadata:
  name: bad-missing-router
spec:
  routerRef: non-existent-router
  routes:
    routesRef: datastore://orders.camel.yaml
EOF
```

**Expected result:**

- The invalid resource `bad-missing-router has `Reconciled=False` with reason `ValidationError`, its message contains `does not exist`, and it creates no Deployment.

---

## Phase 10: Deletion

### Test 10.1: Delete the WanakuIntegrations and verify the forwards are removed

**Description:** Kubernetes deletes the runtime Deployment and Service through the owner reference. The runtime
removes its own forward at shutdown, so Wanaku Server no longer serves the tools.

Run this block as a separate `bash` script, not in the shared session.

```bash
oc delete wanakuintegration cic-echo cic-catalog -n "${WANAKU_NAMESPACE}" --ignore-not-found=true

FAIL=0
for NAME in cic-echo cic-catalog; do
  wait_for_deletion deployment "${NAME}-cic" "${WANAKU_NAMESPACE}" 120 || FAIL=1
  wait_for_deletion service "${NAME}-cic" "${WANAKU_NAMESPACE}" 60 || FAIL=1
done

for FWD in cic-echo cic-catalog-fwd; do
  GONE=false
  for i in $(seq 1 30); do
    if ! ${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>/dev/null \
        | grep -q "^${FWD}[[:space:]]"; then
      GONE=true
      break
    fi
    sleep 3
  done
  [ "${GONE}" = "true" ] && echo "PASS: forward ${FWD} removed by the runtime at shutdown" \
    || { echo "FAIL: forward ${FWD} is still registered"; FAIL=1; }
done

OUTPUT=$(${WANAKU_CLI} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -qE "cic-echo|cic-catalog-greet" \
  && { echo "FAIL: CIC tools are still registered"; FAIL=1; } \
  || echo "PASS: no CIC tools remain"
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: forward cic-echo removed by the runtime at shutdown`.
- The output includes `PASS: forward cic-catalog-fwd removed by the runtime at shutdown`.
- The output includes `PASS: no CIC tools remain`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 10.2: Delete the WanakuServiceCatalog

```bash run
oc delete wanakuservicecatalog cic-test-service-catalogs -n "${WANAKU_NAMESPACE}" --timeout=120s --ignore-not-found=true
wait_for_deletion wanakuservicecatalog cic-test-service-catalogs "${WANAKU_NAMESPACE}" 60
```

**Expected result:**

- The commands exit with code 0 (inferred).

---

## Phase 11: Post-Test Verification

### Test 11.1: Verify the operator pod is healthy and the logs have no unexpected errors

```bash run
OPERATOR_POD=$(oc get pods -l app.kubernetes.io/name=wanaku-operator -n "${WANAKU_NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
RESTARTS=$(oc get pod "${OPERATOR_POD}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.containerStatuses[0].restartCount}')
[ "${RESTARTS}" = "0" ] && echo "PASS: operator has zero restarts" || echo "WARN: operator restarted ${RESTARTS} time(s)"

# Exclude the expected validation errors from Phase 9
UNEXPECTED=$(oc logs "${OPERATOR_POD}" -n "${WANAKU_NAMESPACE}" \
  | grep -iE "error|exception" \
  | grep -v "ValidationError" \
  | grep -iv "Failed to remove service catalog" \
  | grep -v "\-XX:")
if [ -n "${UNEXPECTED}" ]; then
  echo "WARN: unexpected errors in the operator log — review:"
  echo "${UNEXPECTED}" | tail -10
else
  echo "PASS: no unexpected errors in the operator log"
fi
```

**Expected result:**

- The output includes `PASS: operator has zero restarts` or `WARN: operator restarted ${RESTARTS} time(s)`.
- The output includes `PASS: no unexpected errors in the operator log` or `WARN: unexpected errors in the operator log — review:`.

---

## Phase 12: Cleanup

### Step 12.0: Delete remaining resources

Delete the leftover resources of this plan. Then run the full teardown in Step 12.1.

```bash run
oc delete deployment,service cic-manual -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete wanakuintegration --all -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete wanakuservicecatalog --all -n "${WANAKU_NAMESPACE}" --ignore-not-found=true --timeout=120s
oc delete configmap -l wanaku-test=true -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
echo "PASS: CIC test resources deleted"
```

**Expected result:**

- The output includes `PASS: CIC test resources deleted`.

---

### Step 12.1: Run full teardown

Follow [common/cleanup.md](common/cleanup.md).

**Expected result:**

- All invoked procedure steps pass (inferred).

## Test Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | — | OpenShift login | Critical |
| 1 | — | Namespace setup | Critical |
| 2 | — | Operator installation | Critical |
| 3 | 3.1-3.2 | WanakuRouter with Barn, Routes reachable | Critical |
| 4 | 4.1-4.3 | WanakuIntegration from a ConfigMap becomes Ready (Registered) | Critical |
| 4 | 4.4-4.5 | Runtime Deployment, Service and status | High |
| 4 | 4.6 | Self-registered forward with `cic.*` labels | Critical |
| 4 | 4.7 | Tool discovery, tag filter, namespace isolation | Critical |
| 5 | 5.1 | Fail-safe governance denies the tool call | Critical |
| 5 | 5.2 | Test governance configuration (test-only workaround) | Critical |
| 5 | 5.3-5.4 | Tool invocation through the MCP Route, unknown tool fails | Critical |
| 5 | 5.5 | Audit trail | Medium |
| 6 | 6.1 | Runtime replacement keeps the forward (ownership label) | High |
| 7 | 7.1 | WanakuServiceCatalog deploys the catalog to Barn | High |
| 7 | 7.2-7.4 | WanakuIntegration with a service catalog source in a custom namespace | High |
| 8 | 8.1-8.2 | Plain Deployment registered with `wanaku forwards add` | High |
| 8 | 8.3 | Unreachable forward marked unavailable | Medium |
| 8 | 8.4 | Forward removal removes its tools | High |
| 9 | 9.1-9.2 | WanakuIntegration validation errors | High |
| 10 | 10.1-10.2 | Deletion removes the runtimes and their forwards | Critical |
| 11 | 11.1 | Operator health and logs | Medium |
| 12 | — | Cleanup | Critical |
| Setup | S.1 | Verify prerequisites | Setup |
| Setup | S.2 | Set environment variables | Setup |
| Setup | S.3 | Define condition and assertion helpers | Setup |
| Setup | S.4 | Define tool discovery helper | Setup |
| Setup | S.5 | Define deletion helper | Setup |
| Setup | 0.0 | Log in to OpenShift | Setup |
| Setup | 1.0 | Prepare the namespace | Setup |
| Setup | 2.0 | Install the operator | Setup |
| Setup | 12.0 | Delete remaining resources | Setup |
| Setup | 9.0 | Define the negative test helper | Setup |
| Setup | 12.1 | Run full teardown | Setup |
