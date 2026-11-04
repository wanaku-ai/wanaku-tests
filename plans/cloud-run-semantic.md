# Test Plan: Wanaku Cloud Run Semantic on OpenShift

## Overview

This test plan verifies `wanaku cloud run semantic` (wanaku-ai/wanaku-barn#255, wanaku-ai/wanaku-barn#271) on
OpenShift. The command takes a semantic route for the Wanaku Semantic Router (WSR) and the files that the
`semantic-router.properties` manifest next to it references. It checks the files, writes them into the
`<name>-semantic` ConfigMap, and creates the `<name>` `WanakuSemanticRouter`, which the operator runs next to the
target `WanakuRouter`. ConfigMap keys cannot hold `/`, so each relative path becomes a key with `--` separators, and
`spec.source.configMapRef.items` maps each key back to its path. The runtime reads the file tree in WSR local-files
mode, registers the forward `tool.name` with Wanaku Server, and removes the forward when it stops.

The plan covers the cluster side of the command: the stored cloud target, rejected submissions that create nothing, the
ConfigMap and the resource that the command creates, the file tree in the runtime pod (the Kamelet directory must be a
real directory), the registration, `wanaku cloud status semantic`, a failed start and `wanaku cloud delete semantic`.
`CloudRunSemanticCliITCase` (module `semantic-router-tests`) covers the documented input rules of the command without
a cluster. [semantic-router-openshift.md](semantic-router-openshift.md) covers routing, governance and audit of a WSR
runtime.

**Components under test:**

| Component | How the plan uses it |
|-----------|----------------------|
| Wanaku CLI | `wanaku cloud target`, `wanaku cloud run semantic`, `wanaku cloud status semantic` and `wanaku cloud delete semantic`. The `semantic` commands need wanaku-ai/wanaku-barn#271. The commands use the current kubeconfig context, which Phase 0 sets. |
| Wanaku operator | Reconciles `WanakuRouter` and `WanakuSemanticRouter`. The operator image and the CRD need `configMapRef.items` (wanaku-ai/wanaku-barn#271). |
| Wanaku Server | Deployment `<router>-server`. Management API through the `<router>` Route, MCP through the `<router>-mcp` Route. The runtime registers with `http://server-<router>:8080` (`WSR_REGISTRATION_URL`). |
| WSR runtime | Image `${WSR_IMAGE}`. Deployment and Service `<name>-wsr`. MCP on port 8090, status and health endpoints on port 8091. The operator probes need the health endpoints of wanaku-ai/wanaku-semantic-router#17. |

Authentication is disabled in this plan. The runtime reads its files from the ConfigMap, so the plan does not enable
Wanaku Barn.

> [!IMPORTANT]
> **Expert settings.** A WSR runtime in local-files mode needs deployment settings for the expert that the route names
> in `expert.bean`: `wsr.experts`, `wsr.expert.<bean>.class` and the provider settings, such as the credential
> reference `camel.component.typesafe-ai.api-key`. WSR checks `wsr.experts` before it starts, and stops with
> `Invalid deployment configuration. Missing setting: wsr.experts` when it is not set. The route files cannot carry
> these settings: WSR accepts only `action.*` properties in the `configuration` file. The operator passes
> `spec.properties` and `spec.env` to the runtime, but `wanaku cloud run semantic` has no option that sets them.
> Phase 7 adds them to the resource with `oc patch`, as [semantic-router-openshift.md](semantic-router-openshift.md)
> sets them in its resource. **This is a test-only workaround.** Phase 11 runs the documented command without it and
> records the result as a known issue.

Every step is fully automatable.

Run command blocks in Saci's shared Bash session, including procedure blocks, so helper functions and exported
variables remain available to later tests. Source each block in that session; a top-level `return 1` fails the
block without terminating the session. Do not execute blocks as separate scripts or enable `set -e` globally.

**Hard timeout rule:** Saci stops a command block that runs longer than 120 seconds (`limits.operation_timeout_secs`),
and a stopped block can lose the shared Bash session. Any single operation (oc wait, polling loop, port-forward setup,
etc.) that takes longer than **100 seconds** MUST be considered broken. Mark the test as FAIL immediately and abort —
do not wait longer. All `--timeout` values and polling deadlines in this plan MUST NOT exceed `100s`. A test that waits
for several slow operations splits them into separate blocks, so that each block finishes within the Saci limit.

> [!NOTE]
> The WSR startup probe allows 10 minutes, because a runtime resolves Maven dependencies at startup. The route in this
> plan uses only Camel components that the WSR distribution contains, so a runtime starts in less than 3 minutes on a
> normal cluster. Phase 7 waits for the first start in two blocks. A slow first image pull is an environment problem.
> Pre-pull `${WSR_IMAGE}` if necessary.

## Prerequisites

### Required tools

| Tool            | Minimum version              | Verify command            |
|-----------------|------------------------------|---------------------------|
| `oc`            | 4.12+                        | `oc version --client`     |
| `helm`          | 3.x                          | `helm version --short`    |
| `curl`          | any (HTTP-level checks only) | `curl --version`          |
| `jq`            | 1.6+                         | `jq --version`            |
| `tar`           | any (source archive)         | `tar --version`           |
| `wanaku` CLI    | build from source            | `${WANAKU_CLI} --version` |

### Environment variables

Before running this plan, an administrator must provision the `wanaku-test-runner` service account and its RBAC
using [the service account setup guide](setup/service-account-setup.md). The local bootstrap script is
`setup/create-service-account.sh`, relative to this plan directory. Provisioning and token generation are
one-time administrative prerequisites; do not run the bootstrap script as part of this test plan.

Provide these inputs to the test environment before Step 0.1:

| Variable              | Required | Purpose                                                                                 |
|-----------------------|----------|-----------------------------------------------------------------------------------------|
| `OPENSHIFT_API_URL`   | Yes      | Target OpenShift API URL.                                                               |
| `OPENSHIFT_SA_TOKEN`  | Yes      | Secret token for the provisioned `wanaku-test-runner` service account. Do not print it. |
| `WANAKU_SA_NAMESPACE` | No       | Namespace containing that service account; defaults to `wanaku-test-infra`.             |

`WANAKU_SA_NAMESPACE` identifies the persistent service-account infrastructure. It is separate from
`WANAKU_NAMESPACE`, the unique per-run test namespace created in Step 1.1.

The command, the operator and the runtime must include the changes under test:

- Set `WANAKU_BARN_REF` to the wanaku-ai/wanaku-barn#271 commit under test (or a later commit), and build
  `WANAKU_CLI` and `WANAKU_OPERATOR_IMAGE` from that commit. With the defaults, Test 2.2 fails until the change is in
  `main`, because the CRD has no `configMapRef.items`.
- Set `WSR_IMAGE` to an image with the health endpoints of wanaku-ai/wanaku-semantic-router#17. Without them, the
  startup probe never passes and Test 7.2 fails after 200 seconds.
- Set `WSR_CAMEL_VERSION` to the Camel version that the WSR image was built with (`CatalogLoader.CAMEL_VERSION`).
  Otherwise WSR stops at startup with `Incompatible artifact setting: camel.version`.

Step S.2 sets the other variables.

| Variable                | Default                                                 | Description |
|-------------------------|---------------------------------------------------------|-------------|
| `WANAKU_CLI`            | `wanaku`                                                | A Wanaku CLI with `wanaku cloud run semantic`, for example `java -jar <wanaku-barn>/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar` |
| `WANAKU_OPERATOR_IMAGE` | `quay.io/wanaku/wanaku-operator:latest`                 | An operator image that mounts `configMapRef.items` |
| `WANAKU_BARN_REF`       | `main`                                                  | The Wanaku Barn branch, tag or commit of the operator chart and its CRDs (Step S.0) |
| `WSR_IMAGE`             | `quay.io/wanaku/wanaku-semantic-router:latest`          | A WSR image that serves the health endpoints |
| `WSR_MISSING_IMAGE`     | `quay.io/wanaku/wanaku-semantic-router:does-not-exist` | An image that cannot be pulled (Phase 9) |
| `WSR_CAMEL_VERSION`     | `4.23.0`                                                | The Camel version of the WSR image. The route manifest (`camel.version`) must match it. |
| `TYPESAFE_API_KEY`      | not set                                                 | Optional credential of the expert provider. The plan does not call the provider. When the variable is not set, the Secret of Phase 7 holds a placeholder value. |

Step S.0 reuses the current directory when it contains the Wanaku Barn operator chart; otherwise it downloads those
assets from `wanaku-ai/wanaku-barn`. Set `WANAKU_BARN_REF` to a branch, tag or commit (default: `main`) to select
the source version. To use an existing checkout instead, explicitly set `WANAKU_REPO_ROOT` to its root; an invalid
explicit path fails rather than triggering a download. Downloaded sources are isolated in a temporary directory and
removed after Helm teardown in the cleanup phase.

`WANAKU_CLI_TLS_OPTS` and `CURL_TLS_OPTS` are not quoted in the commands below on purpose: each holds zero or one
option.

---

## Phase 0: OpenShift Login

### Step 0.1: Log in to OpenShift

Log in to the target OpenShift cluster using the token for the provisioned `wanaku-test-runner` service account.

Follow [common/openshift-login.md](common/openshift-login.md).

After the login procedure, verify the exact identity before creating the test namespace. Reject personal accounts
and other service accounts. Run this block in the shared Bash session.

```bash run
EXPECTED_IDENTITY="system:serviceaccount:${WANAKU_SA_NAMESPACE:-wanaku-test-infra}:wanaku-test-runner"
if ! ACTUAL_IDENTITY=$(oc whoami 2>/dev/null); then
  echo "FAIL: cannot verify the logged-in service account"
  return 1
fi
if [ "${ACTUAL_IDENTITY}" != "${EXPECTED_IDENTITY}" ]; then
  echo "FAIL: logged-in identity is not the configured wanaku-test-runner service account"
  return 1
fi
echo "PASS: logged in as the configured wanaku-test-runner service account"
```

**Expected result:**

- The procedure logs in to the target OpenShift cluster using a service account token.
- The output includes `PASS: logged in as the configured wanaku-test-runner service account`.
- The output contains no line starting with `FAIL:`.
- The identity verification script exits with code 0.

---

## Phase 1: Environment Setup

### Step 1.1: Create namespace

Create and verify the namespace. The procedure also makes it the namespace of the current kubeconfig context.

Follow [common/namespace-setup.md](common/namespace-setup.md).

**Expected result:**

- The namespace is created and verified.

---

## Phase S: Setup

### Step S.0: Prepare the operator Helm chart

Reuse a supplied Wanaku Barn checkout, or download its source archive from the official repository. Keep the
chart available through Helm teardown. Run this block in the shared Bash session.

```bash run
WANAKU_DOWNLOADED_SOURCE_DIR=""
export WANAKU_BARN_REF="${WANAKU_BARN_REF:-main}"
if [ -n "${WANAKU_REPO_ROOT+x}" ]; then
  if [ ! -r "${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ]; then
    echo "FAIL: supplied WANAKU_REPO_ROOT lacks the operator chart"
    return 1
  fi
  export WANAKU_REPO_ROOT
  echo "PASS: existing Wanaku Barn source assets reused"
elif [ -r "./apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ]; then
  export WANAKU_REPO_ROOT="${PWD}"
  echo "PASS: existing Wanaku Barn source assets reused"
else
  for CMD in curl tar; do
    command -v "${CMD}" > /dev/null 2>&1 || { echo "FAIL: ${CMD} is required to download source assets"; return 1; }
  done
  WANAKU_DOWNLOADED_SOURCE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/wanaku-operator-source.XXXXXX") || return 1
  mkdir "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo" || return 1
  curl --fail --location --connect-timeout 10 --max-time 90 \
    "https://github.com/wanaku-ai/wanaku-barn/archive/${WANAKU_BARN_REF}.tar.gz" \
    --output "${WANAKU_DOWNLOADED_SOURCE_DIR}/source.tar.gz" \
    || { echo "FAIL: Wanaku Barn source archive download failed"; return 1; }
  tar -xzf "${WANAKU_DOWNLOADED_SOURCE_DIR}/source.tar.gz" \
    --strip-components=1 -C "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo" \
    || { echo "FAIL: Wanaku Barn source archive extraction failed"; return 1; }
  if [ ! -r "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ]; then
    echo "FAIL: downloaded source lacks the operator chart"
    return 1
  fi
  export WANAKU_REPO_ROOT="${WANAKU_DOWNLOADED_SOURCE_DIR}/repo"
  echo "PASS: Wanaku Barn source assets downloaded"
fi
```

**Expected result:**

- The output includes `PASS: existing Wanaku Barn source assets reused` or `PASS: Wanaku Barn source assets downloaded`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test S.1: Prerequisite check script

Run this block in the shared Bash session.

```bash run
FAIL=0
for CMD in oc helm curl jq tar; do
  if ! command -v "${CMD}" > /dev/null 2>&1; then
    echo "FAIL: ${CMD} is not installed"
    FAIL=1
  else
    echo "PASS: ${CMD} found at $(command -v "${CMD}")"
  fi
done

if ! ${WANAKU_CLI:-wanaku} --help > /dev/null 2>&1; then
  echo "FAIL: the Wanaku CLI (${WANAKU_CLI:-wanaku}) does not run"
  FAIL=1
else
  echo "PASS: the Wanaku CLI runs"
fi

# A CLI without the command prints the help of "cloud run" instead, also with exit code 0
if ${WANAKU_CLI:-wanaku} cloud run semantic --help 2>&1 | grep -q "Usage: wanaku cloud run semantic"; then
  echo "PASS: the Wanaku CLI has the cloud run semantic command"
else
  echo "FAIL: the Wanaku CLI (${WANAKU_CLI:-wanaku}) has no cloud run semantic command"
  FAIL=1
fi

for ASSET in "${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml"; do
  if [ -r "${ASSET}" ]; then
    echo "PASS: required deployment asset is readable: ${ASSET}"
  else
    echo "FAIL: required deployment asset is missing or unreadable: ${ASSET}"
    FAIL=1
  fi
done

[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || { echo "FAIL: one or more prerequisites missing"; return 1; }
```

**Expected result:**

- The output includes `PASS: oc found at`.
- The output includes `PASS: helm found at`.
- The output includes `PASS: curl found at`.
- The output includes `PASS: jq found at`.
- The output includes `PASS: tar found at`.
- The output includes `PASS: the Wanaku CLI runs`.
- The output includes `PASS: the Wanaku CLI has the cloud run semantic command`.
- The output includes `PASS: required deployment asset is readable:` for the operator chart.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step S.2: Set environment variables

All image tags default to `latest` and can be overridden. The cloud commands store the target in
`$WANAKU_HOME/cloud.properties`, so the plan uses a `WANAKU_HOME` of its own and does not change the target of other
runs.

```bash run
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WSR_IMAGE="${WSR_IMAGE:-quay.io/wanaku/wanaku-semantic-router:latest}"
# A tag that does not exist: the failed start test (Phase 9) needs an image that cannot be pulled
export WSR_MISSING_IMAGE="${WSR_MISSING_IMAGE:-quay.io/wanaku/wanaku-semantic-router:does-not-exist}"
export WSR_CAMEL_VERSION="${WSR_CAMEL_VERSION:-4.23.0}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-test-router}"
# The command derives the resource name from the route file name: support.camel.yaml gives support
export WSR_NAME="support"
# The forward name defaults to tool.name in semantic-router.properties
export WSR_TOOL="route_support"
# Wanaku CLI: an installed binary, or "java -jar ${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
export WANAKU_CLI="${WANAKU_CLI:-wanaku}"
# The operator creates edge-terminated Routes. Set these to empty values when the ingress certificate is trusted.
export WANAKU_CLI_TLS_OPTS="${WANAKU_CLI_TLS_OPTS---insecure}"
export CURL_TLS_OPTS="${CURL_TLS_OPTS--k}"
# The local files of this run: the semantic route tree and WANAKU_HOME
WSR_CLOUD_RUN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/wanaku-cloud-run.XXXXXX") || return 1
export WSR_CLOUD_RUN_DIR
export WANAKU_HOME="${WSR_CLOUD_RUN_DIR}/home"
export WSR_SOURCE_DIR="${WSR_CLOUD_RUN_DIR}/catalog/support"
export WSR_ROUTE="${WSR_SOURCE_DIR}/support.camel.yaml"
echo "PASS: the local files of this run are in ${WSR_CLOUD_RUN_DIR}"
```

**Expected result:**

- The output includes `PASS: the local files of this run are in`.
- The commands exit with code 0.

### Step S.3: Define the wait_for_deletion helper

Define the `wait_for_deletion` function.

Follow [common/wait-for-deletion.md](common/wait-for-deletion.md).

**Expected result:**

- The block defines the `wait_for_deletion` function without errors (inferred).

### Step S.4: Define the custom resource condition helpers

The function polls a condition until it has the expected status and, optionally, the expected reason. It fails after
100 seconds. The functions are exported so that blocks running in a child `bash` process can use them.

```bash run
wait_cr_condition() {
  # $1 = kind, $2 = name, $3 = condition type, $4 = expected status, $5 = expected reason (optional)
  WCC_END=$(( $(date +%s) + 100 ))
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

export -f wait_cr_condition check
```

**Expected result:**

- The block defines and exports the `wait_cr_condition` and `check` functions without errors (inferred).

### Step S.5: Define tool discovery helper

The deadline includes the time of the CLI calls, which start a JVM when `WANAKU_CLI` is a JAR.

```bash run
wait_for_tool() {
  # $1 = tool name
  WFT_END=$(( $(date +%s) + 75 ))
  WFT_ATTEMPT=0
  while [ "$(date +%s)" -lt "${WFT_END}" ]; do
    WFT_ATTEMPT=$(( WFT_ATTEMPT + 1 ))
    if ${WANAKU_CLI} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>/dev/null \
        | grep -q "$1"; then
      echo "PASS: tool $1 is registered in Wanaku Server (attempt ${WFT_ATTEMPT})"
      return 0
    fi
    sleep 3
  done
  echo "FAIL: tool $1 not registered in Wanaku Server"
  return 1
}

export -f wait_for_tool
```

**Expected result:**

- The block defines and exports the function without errors (inferred).

---

## Phase 2: Operator Installation

### Step 2.1: Install the operator

Install the operator via Helm and verify CRDs, RBAC, and health endpoints.

Follow [common/operator-deployment.md](common/operator-deployment.md).

**Expected result:**

- The operator is installed via Helm, and the procedure verifies CRDs, RBAC, and health endpoints.

### Test 2.2: Verify that the WanakuSemanticRouter CRD has configMapRef.items

**Description:** The API server removes the fields that a CRD does not define. With an older CRD, the resource that
the command creates loses `spec.source.configMapRef.items`, and the runtime does not find its files. Helm does not
upgrade CRDs that exist (see Step 6 of [common/operator-deployment.md](common/operator-deployment.md)).

Run this block in the shared Bash session.

```bash run
ITEMS_TYPE=$(oc get crd wanakusemanticrouters.wanaku.ai -o json | jq -r '.spec.versions[] | select(.name=="v1alpha1")
  | .schema.openAPIV3Schema.properties.spec.properties.source.properties.configMapRef.properties.items.type // empty')
if [ "${ITEMS_TYPE}" = "array" ]; then
  echo "PASS: the WanakuSemanticRouter CRD has spec.source.configMapRef.items"
else
  echo "FAIL: the WanakuSemanticRouter CRD has no spec.source.configMapRef.items"
  echo "Apply the CRDs of the chart: oc apply -f ${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator/crds/"
  return 1
fi
```

**Expected result:**

- The output includes `PASS: the WanakuSemanticRouter CRD has spec.source.configMapRef.items`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 3: WanakuRouter Setup

### Test 3.1: Create a WanakuRouter

Run this block in the shared Bash session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  imagePullPolicy: Always
  server:
    image: ${WANAKU_SERVER_IMAGE}
EOF

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}" \
  || { echo "FAIL: the WanakuRouter ${WANAKU_ROUTER_NAME} is not Ready"; return 1; }
echo "PASS: the WanakuRouter ${WANAKU_ROUTER_NAME} is Ready"
```

**Expected result:**

- The output includes `PASS: the WanakuRouter <WANAKU_ROUTER_NAME> is Ready`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.2: Read the Route URLs and verify the Server is reachable

Run this block in the shared Bash session so that the exported `WANAKU_ROUTER_URL` and `WANAKU_ROUTER_MCP_URL`
variables are available to all later steps.

```bash run
export WANAKU_ROUTER_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_ROUTER_MCP_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
echo "management: ${WANAKU_ROUTER_URL}"
echo "mcp:        ${WANAKU_ROUTER_MCP_URL}"

# curl: HTTP-level reachability check before the CLI connects
CODE=$(curl -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/health")
[ "${CODE}" = "200" ] && echo "PASS: Wanaku Server /health returns 200" || { echo "FAIL: /health returned HTTP ${CODE}"; return 1; }

${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain
[ $? -eq 0 ] && echo "PASS: the CLI reaches the management API" || { echo "FAIL: forwards list failed"; return 1; }
```

**Expected result:**

- The output includes `PASS: Wanaku Server /health returns 200`.
- The output includes `PASS: the CLI reaches the management API`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 4: Cloud Target and Route Files

### Step 4.1: Write the semantic route files

**Description:** The files are the support route of the `semantic-router-tests` module, in the layout of the WSR
local-files mode: `semantic-router.properties` is next to the route, and its references are relative to the
`catalog` directory that `main=support/support.camel.yaml` implies. `kamelets=support/kamelets` names a directory.
The tool `route_support` sends billing requests to `Billing demo response`, technical requests to
`Technical demo response`, and all other requests to `No suitable support action.`. The plan does not call the tool.

Run this block in the shared Bash session.

```bash run
mkdir -p "${WSR_SOURCE_DIR}/kamelets" || return 1
cat > "${WSR_SOURCE_DIR}/semantic-router.properties" <<PROPS
contract.version=1
camel.version=${WSR_CAMEL_VERSION}
main=support/support.camel.yaml
kamelets=support/kamelets
dependencies=support/support.dependencies.txt
input.profile=message-to-string/v1
expert.bean=supportExpert
tool.name=${WSR_TOOL}
evaluation=department
configuration=support/service.properties
tool.tags=wsr-support
PROPS
cat > "${WSR_SOURCE_DIR}/support.dependencies.txt" <<DEPS
camel:core
org.apache.camel:camel-semantic
camel:direct
org.apache.camel:camel-kamelet
org.apache.camel:camel-ai-tool
org.apache.camel:camel-mcp-server
org.apache.camel:camel-platform-http-main
mvn:org.apache.camel:camel-typesafe-ai:${WSR_CAMEL_VERSION}
DEPS
: > "${WSR_SOURCE_DIR}/service.properties"
cat > "${WSR_ROUTE}" <<'ROUTE'
- beans:
    - name: recordContinuation
      type: org.apache.camel.semantic.SemanticAuditDecision
      properties:
        fields:
          action: continue
          operation: tools/call
          target: route_support
          reasonCode: classified
- semantic:
    evaluation:
      department:
        operation: choice
        expert: supportExpert
        state: "${body}"
        parameters:
          instructions: Select the support action that can handle this request.
          criteria:
            billing: Questions about invoices, payments, and refunds.
            technical: Questions about bugs, outages, and technical problems.
            no_match: Requests that neither support action can handle.
- route:
    id: support-mcp-entry
    from:
      uri: ai-tool:route_support
      parameters:
        description: Route a support request to billing or technical support.
        tags: wsr-support
        parameter.message: string
        parameter.message.required: "true"
        parameter.message.description: The support request to evaluate.
      steps:
        - setBody:
            simple: "${header.message}"
        - to: direct:route-support
- route:
    id: support-dispatch
    from:
      uri: direct:route-support
      steps:
        - to: direct:classify-support
        - process:
            ref: recordContinuation
        - choice:
            when:
              - simple: "${exchangeProperty.department} == 'billing'"
                steps:
                  - to: kamelet:wsr-billing-action
              - simple: "${exchangeProperty.department} == 'technical'"
                steps:
                  - to: kamelet:wsr-technical-action
            otherwise:
              steps:
                - setBody:
                    constant: No suitable support action.
- route:
    id: support-classification
    from:
      uri: direct:classify-support
      steps:
        - setProperty:
            name: department
            expression:
              language:
                language: semantic
                expression: ref:department
ROUTE
for KIND in billing technical; do
  case "${KIND}" in
    billing) TITLE="Billing support"; ANSWER="Billing demo response"; TOPIC="invoices, payments, and refunds" ;;
    technical) TITLE="Technical support"; ANSWER="Technical demo response"; TOPIC="bugs, outages, and technical problems" ;;
  esac
  cat > "${WSR_SOURCE_DIR}/kamelets/wsr-${KIND}-action.kamelet.yaml" <<KAMELET
apiVersion: camel.apache.org/v1
kind: Kamelet
metadata:
  name: wsr-${KIND}-action
  labels:
    camel.apache.org/kamelet.type: action
  annotations:
    barn.wanaku.ai/semantic-action: "true"
    barn.wanaku.ai/routing-description: Questions about ${TOPIC}.
    barn.wanaku.ai/contract-profile: message-to-string-v1
spec:
  definition:
    title: ${TITLE}
    description: Return a local ${KIND} demonstration response.
    type: object
    properties: {}
  types:
    in:
      mediaType: text/plain
      schema:
        type: string
    out:
      mediaType: text/plain
      schema:
        type: string
  dependencies:
    - camel:core
  template:
    from:
      uri: kamelet:source
      steps:
        - setBody:
            constant: ${ANSWER}
        - to: kamelet:sink
KAMELET
done

# Each relative path becomes a ConfigMap key with "--" separators; items map the keys back (key=path, sorted)
export WSR_EXPECTED_ITEMS="support--kamelets--wsr-billing-action.kamelet.yaml=support/kamelets/wsr-billing-action.kamelet.yaml,\
support--kamelets--wsr-technical-action.kamelet.yaml=support/kamelets/wsr-technical-action.kamelet.yaml,\
support--semantic-router.properties=support/semantic-router.properties,\
support--service.properties=support/service.properties,\
support--support.camel.yaml=support/support.camel.yaml,\
support--support.dependencies.txt=support/support.dependencies.txt"

FILES=$(cd "${WSR_CLOUD_RUN_DIR}/catalog" && find support -type f | LC_ALL=C sort | paste -sd, -)
echo "${FILES}"
FAIL=0
check "route files" "${FILES}" "$(echo "${WSR_EXPECTED_ITEMS}" | sed 's/[^,=]*=//g')"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: route files =` with the six files of the route tree.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.2: Store the cloud target

**Description:** `wanaku cloud target set` stores the namespace and the router in `$WANAKU_HOME/cloud.properties`.
The plan stores no context, so the commands use the current kubeconfig context. The later commands take the target
from the file. In plain mode, `wanaku cloud target show` prints its values without line breaks (it prints them with
`WanakuPrinter.printValue`, which prints one raw value). The test finds the values anywhere in the output, and prints
a `WARN:` line while the line breaks are missing.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud target set --namespace "${WANAKU_NAMESPACE}" --router "${WANAKU_ROUTER_NAME}" --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "target set exit code" "${EXIT_CODE}" "0"
[ -s "${WANAKU_HOME}/cloud.properties" ] && echo "PASS: the target is stored in ${WANAKU_HOME}/cloud.properties" \
  || { echo "FAIL: ${WANAKU_HOME}/cloud.properties was not written"; FAIL=1; }

OUTPUT=$(${WANAKU_CLI} cloud target show --plain 2>&1)
echo "${OUTPUT}"
for VALUE in "context: (current kubeconfig context)" "namespace: ${WANAKU_NAMESPACE}" "router: ${WANAKU_ROUTER_NAME}"; do
  echo "${OUTPUT}" | grep -qF "${VALUE}" && echo "PASS: target show prints '${VALUE}'" \
    || { echo "FAIL: target show does not print '${VALUE}'"; FAIL=1; }
done
[ "$(echo "${OUTPUT}" | grep -c '^router: ')" = "1" ] && echo "PASS: target show prints one value per line" \
  || echo "WARN: target show prints its values without line breaks in plain mode"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: target set exit code =` with the expected value.
- The output includes `PASS: the target is stored in <WANAKU_HOME>/cloud.properties`.
- The output includes `PASS: target show prints 'context: (current kubeconfig context)'`.
- The output includes `PASS: target show prints 'namespace: <WANAKU_NAMESPACE>'`.
- The output includes `PASS: target show prints 'router: <WANAKU_ROUTER_NAME>'`.
- The output includes `PASS: target show prints one value per line` or `WARN: target show prints its values without line breaks in plain mode`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 5: Rejected Submissions

### Test 5.1: A route without its manifest is rejected before submission

**Description:** The command checks the files before it contacts the cluster. A problem gives exit code 2 and
creates nothing. `CloudRunSemanticCliITCase` covers the other documented input rules.

Run this block in the shared Bash session.

```bash run
mkdir -p "${WSR_CLOUD_RUN_DIR}/no-manifest" && cp "${WSR_ROUTE}" "${WSR_CLOUD_RUN_DIR}/no-manifest/" || return 1
OUTPUT=$(${WANAKU_CLI} cloud run semantic "${WSR_CLOUD_RUN_DIR}/no-manifest/support.camel.yaml" --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "2"
echo "${OUTPUT}" | grep -q "needs semantic-router.properties in the same directory" \
  && echo "PASS: the command reports the missing manifest" \
  || { echo "FAIL: the command does not report the missing manifest"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: the command reports the missing manifest`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.2: A router that does not exist is an unavailable target

**Description:** `--router` overrides the stored router for one command. The command checks that the router exists
and is ready before it submits anything. A missing router gives exit code 3.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud run semantic "${WSR_ROUTE}" --router does-not-exist --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "3"
echo "${OUTPUT}" | grep -qF "WanakuRouter does-not-exist does not exist in namespace ${WANAKU_NAMESPACE}" \
  && echo "PASS: the command reports the missing router" \
  || { echo "FAIL: the command does not report the missing router"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: the command reports the missing router`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.3: The rejected submissions created nothing

**Description:** The command labels the ConfigMap and the resource that it creates with
`app.kubernetes.io/managed-by=wanaku-cli`.

Run this block in the shared Bash session.

```bash run
RESOURCES=$(oc get wanakusemanticrouter -n "${WANAKU_NAMESPACE}" -o name) \
  || { echo "FAIL: cannot list the WanakuSemanticRouter resources"; return 1; }
CONFIGMAPS=$(oc get configmap -l app.kubernetes.io/managed-by=wanaku-cli -n "${WANAKU_NAMESPACE}" -o name) \
  || { echo "FAIL: cannot list the ConfigMaps"; return 1; }
FAIL=0
check "WanakuSemanticRouter resources" "${RESOURCES}" ""
check "ConfigMaps managed by the CLI" "${CONFIGMAPS}" ""
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: WanakuSemanticRouter resources =` with an empty value.
- The output includes `PASS: ConfigMaps managed by the CLI =` with an empty value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 6: Submission

### Test 6.1: Submit the semantic route without waiting

**Description:** The command takes the namespace and the router from the stored target. `--image` and
`--image-pull-policy` select the WSR image under test. `--no-wait` returns after the submission, so that the
following tests check what the command created before the runtime starts.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud run semantic "${WSR_ROUTE}" --image "${WSR_IMAGE}" --image-pull-policy Always \
  --no-wait --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "0"
for LINE in "Submitted WanakuSemanticRouter ${WSR_NAME} in namespace ${WANAKU_NAMESPACE}" \
    "Follow it with: wanaku cloud status semantic ${WSR_NAME}"; do
  echo "${OUTPUT}" | grep -qF "${LINE}" && echo "PASS: the command prints '${LINE}'" \
    || { echo "FAIL: the command does not print '${LINE}'"; FAIL=1; }
done
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: the command prints 'Submitted WanakuSemanticRouter support in namespace <WANAKU_NAMESPACE>'`.
- The output includes `PASS: the command prints 'Follow it with: wanaku cloud status semantic support'`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.2: Verify the route files ConfigMap

**Description:** The `<name>-semantic` ConfigMap holds every file of the route tree under its key. The command makes
the resource the owner of the ConfigMap, so that Kubernetes deletes the ConfigMap with the resource.

Run this block in the shared Bash session.

```bash run
CM_JSON=$(oc get configmap "${WSR_NAME}-semantic" -n "${WANAKU_NAMESPACE}" -o json) \
  || { echo "FAIL: ConfigMap ${WSR_NAME}-semantic not found"; return 1; }
FAIL=0
check "ConfigMap keys" "$(echo "${CM_JSON}" | jq -r '.data | keys | join(",")')" \
  "$(echo "${WSR_EXPECTED_ITEMS}" | sed 's/=[^,]*//g')"
check "ConfigMap label app.kubernetes.io/managed-by" \
  "$(echo "${CM_JSON}" | jq -r '.metadata.labels["app.kubernetes.io/managed-by"]')" "wanaku-cli"
check "ConfigMap owner" "$(echo "${CM_JSON}" | jq -r '.metadata.ownerReferences[0] | .kind + "/" + .name')" \
  "WanakuSemanticRouter/${WSR_NAME}"
check "ConfigMap owner uid" "$(echo "${CM_JSON}" | jq -r '.metadata.ownerReferences[0].uid')" \
  "$(oc get wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.metadata.uid}')"
# Each value is the content of the local file
for FILE in support.camel.yaml semantic-router.properties kamelets/wsr-billing-action.kamelet.yaml; do
  KEY="support--$(echo "${FILE}" | sed 's|/|--|g')"
  [ "$(echo "${CM_JSON}" | jq -r --arg K "${KEY}" '.data[$K]')" = "$(cat "${WSR_SOURCE_DIR}/${FILE}")" ] \
    && echo "PASS: ${KEY} holds support/${FILE}" || { echo "FAIL: ${KEY} does not hold support/${FILE}"; FAIL=1; }
done
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: ConfigMap keys =` with the expected value.
- The output includes `PASS: ConfigMap label app.kubernetes.io/managed-by =` with the expected value.
- The output includes `PASS: ConfigMap owner =` with the expected value.
- The output includes `PASS: ConfigMap owner uid =` with the expected value.
- The output includes `PASS: support--support.camel.yaml holds support/support.camel.yaml`.
- The output includes `PASS: support--semantic-router.properties holds support/semantic-router.properties`.
- The output includes `PASS: support--kamelets--wsr-billing-action.kamelet.yaml holds support/kamelets/wsr-billing-action.kamelet.yaml`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.3: Verify the WanakuSemanticRouter

**Description:** The resource refers to the ConfigMap. `main` is the path of the route, and `items` maps each key to
its path. The forward name is the `tool.name` of the manifest.

Run this block in the shared Bash session.

```bash run
CR_JSON=$(oc get wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" -o json) \
  || { echo "FAIL: WanakuSemanticRouter ${WSR_NAME} not found"; return 1; }
cr_json() { echo "${CR_JSON}" | jq -r "$1"; }
FAIL=0
check "spec.routerRef" "$(cr_json '.spec.routerRef')" "${WANAKU_ROUTER_NAME}"
check "spec.source.configMapRef.name" "$(cr_json '.spec.source.configMapRef.name')" "${WSR_NAME}-semantic"
check "spec.source.configMapRef.main" "$(cr_json '.spec.source.configMapRef.main')" "support/support.camel.yaml"
check "spec.source.configMapRef.items" \
  "$(cr_json '[.spec.source.configMapRef.items[]? | .key + "=" + .path] | sort | join(",")')" "${WSR_EXPECTED_ITEMS}"
check "spec.forward.name (tool.name)" "$(cr_json '.spec.forward.name')" "${WSR_TOOL}"
check "spec.forward.namespace" "$(cr_json '.spec.forward.namespace')" "default"
check "spec.image" "$(cr_json '.spec.image')" "${WSR_IMAGE}"
check "spec.imagePullPolicy" "$(cr_json '.spec.imagePullPolicy')" "Always"
check "label app.kubernetes.io/managed-by" "$(cr_json '.metadata.labels["app.kubernetes.io/managed-by"]')" "wanaku-cli"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: spec.routerRef =` with the expected value.
- The output includes `PASS: spec.source.configMapRef.name =` with the expected value.
- The output includes `PASS: spec.source.configMapRef.main =` with the expected value.
- The output includes `PASS: spec.source.configMapRef.items =` with the expected value.
- The output includes `PASS: spec.forward.name (tool.name) =` with the expected value.
- The output includes `PASS: spec.forward.namespace =` with the expected value.
- The output includes `PASS: spec.image =` with the expected value.
- The output includes `PASS: spec.imagePullPolicy =` with the expected value.
- The output includes `PASS: label app.kubernetes.io/managed-by =` with the expected value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.4: Verify that the operator mounts the file tree

**Description:** The operator creates the `<name>-wsr` Deployment. The `routes` volume places each ConfigMap key at
its item path below `/opt/wsr/routes`, and `WSR_ROUTES_REF` names the path of the route. The runtime registers the
forward under the name in `WSR_NAME`.

Run this block in the shared Bash session.

```bash run
for i in $(seq 1 20); do
  oc get deployment "${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 && break
  sleep 3
done
DEPLOY_JSON=$(oc get deployment "${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" -o json) \
  || { echo "FAIL: Deployment ${WSR_NAME}-wsr was not created"; return 1; }
deploy_json() { echo "${DEPLOY_JSON}" | jq -r "$1"; }
deploy_env() { deploy_json ".spec.template.spec.containers[0].env[] | select(.name==\"$1\") | .value"; }
FAIL=0
check "routes volume ConfigMap" \
  "$(deploy_json '.spec.template.spec.volumes[] | select(.name=="routes") | .configMap.name')" "${WSR_NAME}-semantic"
check "routes volume items" \
  "$(deploy_json '[.spec.template.spec.volumes[] | select(.name=="routes") | .configMap.items[]? | .key + "=" + .path] | sort | join(",")')" \
  "${WSR_EXPECTED_ITEMS}"
check "routes mount path" \
  "$(deploy_json '.spec.template.spec.containers[0].volumeMounts[] | select(.name=="routes") | .mountPath')" "/opt/wsr/routes"
check "WSR_ROUTES_REF" "$(deploy_env WSR_ROUTES_REF)" "file:/opt/wsr/routes/support/support.camel.yaml"
check "WSR_NAME (forward name)" "$(deploy_env WSR_NAME)" "${WSR_TOOL}"
check "WSR_NAMESPACE" "$(deploy_env WSR_NAMESPACE)" "default"
check "WSR_REGISTRATION_URL" "$(deploy_env WSR_REGISTRATION_URL)" "http://server-${WANAKU_ROUTER_NAME}:8080"
check "image" "$(deploy_json '.spec.template.spec.containers[0].image')" "${WSR_IMAGE}"
check "imagePullPolicy" "$(deploy_json '.spec.template.spec.containers[0].imagePullPolicy')" "Always"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: routes volume ConfigMap =` with the expected value.
- The output includes `PASS: routes volume items =` with the expected value.
- The output includes `PASS: routes mount path =` with the expected value.
- The output includes `PASS: WSR_ROUTES_REF =` with the expected value.
- The output includes `PASS: WSR_NAME (forward name) =` with the expected value.
- The output includes `PASS: WSR_NAMESPACE =` with the expected value.
- The output includes `PASS: WSR_REGISTRATION_URL =` with the expected value.
- The output includes `PASS: image =` with the expected value.
- The output includes `PASS: imagePullPolicy =` with the expected value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 7: Runtime Start With Expert Settings

### Test 7.1: Add the expert settings to the resource (test-only workaround)

**Description:** The command cannot set the expert settings (see the Overview). The test adds them to the resource
as [semantic-router-openshift.md](semantic-router-openshift.md) sets them: `spec.properties` enables the
`supportExpert` bean with the TypeSafe AI adapter, and `spec.env` reads `TYPESAFE_API_KEY` from the
`wsr-llm-credentials` Secret. The property holds only the reference `{{env:TYPESAFE_API_KEY}}`. When
`TYPESAFE_API_KEY` is not set, the Secret holds a placeholder value: the runtime needs the variable at startup, but
it calls the provider only when the tool runs. **This is a test-only workaround.** Running the command again for this
resource replaces its spec and removes the settings, so the plan does not run it again for `support` before Phase 10.

Run this block in the shared Bash session.

```bash run
# The Secret value is not printed
oc create secret generic wsr-llm-credentials \
  --from-literal=api-key="${TYPESAFE_API_KEY:-placeholder-the-plan-does-not-call-the-provider}" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f - > /dev/null \
  || { echo "FAIL: Secret wsr-llm-credentials was not created"; return 1; }
oc label secret wsr-llm-credentials wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}" > /dev/null \
  || { echo "FAIL: Secret wsr-llm-credentials was not labelled for cleanup"; return 1; }

oc patch wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec": {
  "env": [{"name": "TYPESAFE_API_KEY", "valueFrom": {"secretKeyRef": {"name": "wsr-llm-credentials", "key": "api-key"}}}],
  "properties": {
    "wsr.experts": "supportExpert",
    "wsr.expert.supportExpert.class": "org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter",
    "camel.component.typesafe-ai.api-key": "{{env:TYPESAFE_API_KEY}}"}}}' \
  || { echo "FAIL: the expert settings were not added to ${WSR_NAME}"; return 1; }

# The operator passes each property to the runtime as -p KEY=VALUE
ARGS=""
for i in $(seq 1 20); do
  ARGS=$(oc get deployment "${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.template.spec.containers[0].args}')
  echo "${ARGS}" | jq -e 'any(.[]; . == "wsr.experts=supportExpert")' > /dev/null 2>&1 && break
  sleep 3
done
echo "args: ${ARGS}"
echo "${ARGS}" | jq -e 'any(.[]; . == "wsr.experts=supportExpert")' > /dev/null 2>&1 \
  && echo "PASS: the runtime gets -p wsr.experts=supportExpert" \
  || { echo "FAIL: the Deployment does not pass wsr.experts to the runtime"; return 1; }
```

**Expected result:**

- The output includes `PASS: the runtime gets -p wsr.experts=supportExpert`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.2: Verify the WanakuSemanticRouter becomes Ready

**Description:** `Ready` requires `Reconciled`, `Available` (the runtime pod is ready) and `Registered` (Wanaku Server
has an available forward named `route_support` with the `wsr.instance` label). The runtime reads its files from the
ConfigMap mount. It would stop at startup if `support/kamelets` were a symbolic link to a directory.

The rollout keeps the pod of Phase 6, which has no expert settings and restarts, until the new pod is ready
(`maxUnavailable: 0`). Until then, `Ready` can report the `CrashLoopBackOff` of that old pod.

Run these blocks in the shared Bash session. The first block waits up to 100 seconds and prints the state. The second
block continues the wait. When the resource is not ready, it prints the log, the log of the previous run, the last
termination message (the WSR startup failure) and the events of the newest pod.

```bash run
if wait_cr_condition wanakusemanticrouter "${WSR_NAME}" Ready True > /dev/null; then
  echo "INFO: ${WSR_NAME} is Ready"
else
  echo "INFO: ${WSR_NAME} is not Ready yet: $(oc get wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}')"
fi
```

```bash run
wait_cr_condition wanakusemanticrouter "${WSR_NAME}" Ready True || {
  oc get pods -l "app=${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}"
  NEW_POD=$(oc get pods -l "app=${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{.items[-1:].metadata.name}')
  oc logs "${NEW_POD}" -n "${WANAKU_NAMESPACE}" --tail=40
  # A crash-looping container restarts: show the log and the startup failure of the last terminated run
  oc logs "${NEW_POD}" -n "${WANAKU_NAMESPACE}" --previous --tail=40 2>/dev/null
  echo "last termination message: $(oc get pod "${NEW_POD}" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath='{.status.containerStatuses[0].lastState.terminated.message}')"
  oc get events -n "${WANAKU_NAMESPACE}" --field-selector "involvedObject.name=${NEW_POD}" | tail -10
  return 1
}
condition() { # $1 = condition type; prints status/reason
  oc get wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath="{.status.conditions[?(@.type==\"$1\")].status}/{.status.conditions[?(@.type==\"$1\")].reason}"
}
FAIL=0
check "Reconciled" "$(condition Reconciled)" "True/ReconcileSucceeded"
check "Available" "$(condition Available)" "True/Available"
check "Registered" "$(condition Registered)" "True/Registered"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: wanakusemanticrouter/support condition Ready=True`.
- The output includes `PASS: Reconciled =` with the expected value.
- The output includes `PASS: Available =` with the expected value.
- The output includes `PASS: Registered =` with the expected value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 8: Runtime and Registration

### Test 8.1: Verify the file tree in the runtime pod

**Description:** A ConfigMap volume links its top-level entries to the `..data` directory. With `items`, the
directories below them, such as `support/kamelets`, are real directories. WSR rejects a Kamelet directory that is a
symbolic link. Without `items`, every key would be a file in `/opt/wsr/routes`.

Run this block in the shared Bash session.

```bash run
POD=$(oc get pods -l "app=${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" -o json \
  | jq -r '[.items[] | select(.metadata.deletionTimestamp == null) | select(any(.status.containerStatuses[]?; .ready))][0].metadata.name // empty')
[ -n "${POD}" ] || { echo "FAIL: no ready ${WSR_NAME}-wsr pod"; return 1; }
echo "pod: ${POD}"
OUTPUT=$(oc exec "${POD}" -n "${WANAKU_NAMESPACE}" -- sh -c '
cd /opt/wsr/routes || exit 1
for F in support/semantic-router.properties support/service.properties support/support.camel.yaml \
    support/support.dependencies.txt support/kamelets/wsr-billing-action.kamelet.yaml \
    support/kamelets/wsr-technical-action.kamelet.yaml; do
  if [ -f "$F" ]; then echo "PASS: file $F is mounted"; else echo "FAIL: file $F is not mounted"; fi
done
if [ -d support/kamelets ] && [ ! -L support/kamelets ]; then
  echo "PASS: the Kamelet directory support/kamelets is a real directory"
else
  echo "FAIL: the Kamelet directory support/kamelets is not a real directory"
fi
if ls -A | grep -q -- "--"; then echo "FAIL: ConfigMap keys are mounted as files"; else echo "PASS: no ConfigMap key is mounted as a file"; fi
ls -la /opt/wsr/routes /opt/wsr/routes/support/kamelets
' 2>&1)
echo "${OUTPUT}"
FAIL=0
echo "${OUTPUT}" | grep -q "^FAIL:" && FAIL=1
[ "$(oc exec "${POD}" -n "${WANAKU_NAMESPACE}" -- cat /opt/wsr/routes/support/support.camel.yaml)" = "$(cat "${WSR_ROUTE}")" ] \
  && echo "PASS: the mounted route is the local route file" \
  || { echo "FAIL: the mounted route differs from the local route file"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: file support/semantic-router.properties is mounted`.
- The output includes `PASS: file support/service.properties is mounted`.
- The output includes `PASS: file support/support.camel.yaml is mounted`.
- The output includes `PASS: file support/support.dependencies.txt is mounted`.
- The output includes `PASS: file support/kamelets/wsr-billing-action.kamelet.yaml is mounted`.
- The output includes `PASS: file support/kamelets/wsr-technical-action.kamelet.yaml is mounted`.
- The output includes `PASS: the Kamelet directory support/kamelets is a real directory`.
- The output includes `PASS: no ConfigMap key is mounted as a file`.
- The output includes `PASS: the mounted route is the local route file`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 8.2: Verify the runtime status endpoint

**Description:** The runtime publishes its status at `/api/v1/status` on port 8091. The Service does not expose that
port, so the test uses `oc port-forward`. `oc` chooses a free local port and prints it, so no other process can
answer on that port. For the ConfigMap source, `source` is `local-files` and `revision` is `local`.

Run this block in the shared Bash session.

```bash run
PF_LOG="${WSR_CLOUD_RUN_DIR}/port-forward.log"
oc port-forward "deployment/${WSR_NAME}-wsr" ":8091" -n "${WANAKU_NAMESPACE}" > "${PF_LOG}" 2>&1 &
PF_PID=$!
LOCAL_PORT=""
for i in $(seq 1 20); do
  LOCAL_PORT=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) .*/\1/p' "${PF_LOG}" | head -1)
  [ -n "${LOCAL_PORT}" ] && break
  sleep 1
done
STATUS=""
[ -n "${LOCAL_PORT}" ] && STATUS=$(curl -s --max-time 10 "http://127.0.0.1:${LOCAL_PORT}/api/v1/status")
kill "${PF_PID}" 2>/dev/null
[ -n "${STATUS}" ] || { echo "FAIL: no status from the runtime: $(cat "${PF_LOG}")"; return 1; }
echo "${STATUS}" | jq '{state, source, revision, forwardName}'
FAIL=0
check "runtime state" "$(echo "${STATUS}" | jq -r '.state')" "ready"
check "runtime source" "$(echo "${STATUS}" | jq -r '.source')" "local-files"
check "runtime revision" "$(echo "${STATUS}" | jq -r '.revision')" "local"
check "runtime forwardName" "$(echo "${STATUS}" | jq -r '.forwardName')" "${WSR_TOOL}"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: runtime state =` with the expected value.
- The output includes `PASS: runtime source =` with the expected value.
- The output includes `PASS: runtime revision =` with the expected value.
- The output includes `PASS: runtime forwardName =` with the expected value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 8.3: Verify the forward and the tool in Wanaku Server

**Description:** The runtime registered the forward `route_support` in the Wanaku namespace `default`, with its
in-cluster MCP address. Wanaku Server discovers the tool `route_support` from the forward and serves it at
`/default/mcp`. The CLI does not print forward labels, so `curl` reads `GET /api/v1/forwards/{name}`.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}"
FAIL=0
echo "${OUTPUT}" | grep -Eq "^${WSR_TOOL}[[:space:]]+http://[0-9.]+:8090/mcp" \
  && echo "PASS: forward ${WSR_TOOL} is listed with the runtime address" \
  || { echo "FAIL: forward ${WSR_TOOL} is not listed with the runtime address"; FAIL=1; }

FORWARD=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/forwards/${WSR_TOOL}")
echo "${FORWARD}" | jq '.data | {name, namespace, available, labels}'
check "forward namespace" "$(echo "${FORWARD}" | jq -r '.data.namespace')" "default"
check "forward available" "$(echo "${FORWARD}" | jq -r '.data.available')" "true"
check "forward label wsr.revision" "$(echo "${FORWARD}" | jq -r '.data.labels["wsr.revision"]')" "local"

wait_for_tool "${WSR_TOOL}" || FAIL=1
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "${WSR_TOOL}" && echo "PASS: ${WSR_TOOL} is listed at /default/mcp" \
  || { echo "FAIL: ${WSR_TOOL} is not listed at /default/mcp"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: forward route_support is listed with the runtime address`.
- The output includes `PASS: forward namespace =` with the expected value.
- The output includes `PASS: forward available =` with the expected value.
- The output includes `PASS: forward label wsr.revision =` with the expected value.
- The output includes `PASS: tool route_support is registered in Wanaku Server (attempt <N>)`.
- The output includes `PASS: route_support is listed at /default/mcp`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 8.4: Show the status with wanaku cloud status semantic

**Description:** The command prints the conditions and the endpoints of the resource, and exits with code 0 when the
resource is ready. Like `wanaku cloud target show`, it prints its values without line breaks in plain mode (Test 4.2).

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud status semantic "${WSR_NAME}" --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "0"
for VALUE in "Ready=True" "forwardName: ${WSR_TOOL}" "mcpAddress: http://${WSR_NAME}-wsr.${WANAKU_NAMESPACE}.svc:8090/mcp"; do
  echo "${OUTPUT}" | grep -qF "${VALUE}" && echo "PASS: status prints '${VALUE}'" \
    || { echo "FAIL: status does not print '${VALUE}'"; FAIL=1; }
done
[ "$(echo "${OUTPUT}" | grep -c '^Ready=')" = "1" ] && echo "PASS: status prints one value per line" \
  || echo "WARN: status prints its values without line breaks in plain mode"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: status prints 'Ready=True'`.
- The output includes `PASS: status prints 'forwardName: route_support'`.
- The output includes `PASS: status prints 'mcpAddress: http://support-wsr.<WANAKU_NAMESPACE>.svc:8090/mcp'`.
- The output includes `PASS: status prints one value per line` or `WARN: status prints its values without line breaks in plain mode`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 9: Failed Runtime Start

### Test 9.1: A runtime image that cannot be pulled makes the command fail

**Description:** `--name` and `--forward-name` give the resource and its forward other names, so that it does not
touch `support`. The image does not exist, so the pod waits with `ErrImagePull` or `ImagePullBackOff`. The operator
reports the reason in `Ready`, and the command stops waiting: it exits with code 1, and prints the reason, the state
of the pod and how to inspect the runtime. The operator checks a runtime that is not ready every 30 seconds, so the
command reports the failure before the timeout.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud run semantic "${WSR_ROUTE}" --name "${WSR_NAME}-bad-image" \
  --forward-name "${WSR_NAME}-bad-image" --image "${WSR_MISSING_IMAGE}" --timeout 90 --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "1"
echo "${OUTPUT}" | grep -qE "WanakuSemanticRouter ${WSR_NAME}-bad-image failed: Ready=False \((ErrImagePull|ImagePullBackOff)\)" \
  && echo "PASS: the command reports the image pull failure" \
  || { echo "FAIL: the command does not report the image pull failure"; FAIL=1; }
echo "${OUTPUT}" | grep -qE "Pod ${WSR_NAME}-bad-image-wsr-[^ ]*: (ErrImagePull|ImagePullBackOff)" \
  && echo "PASS: the command prints the state of the pod" \
  || { echo "FAIL: the command does not print the state of the pod"; FAIL=1; }
for LINE in "Inspect it with: wanaku cloud status semantic ${WSR_NAME}-bad-image" \
    "Read its logs with: kubectl logs deployment/${WSR_NAME}-bad-image-wsr"; do
  echo "${OUTPUT}" | grep -qF "${LINE}" && echo "PASS: the command prints '${LINE}'" \
    || { echo "FAIL: the command does not print '${LINE}'"; FAIL=1; }
done
check "spec.forward.name (--forward-name)" "$(oc get wanakusemanticrouter "${WSR_NAME}-bad-image" \
  -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.forward.name}')" "${WSR_NAME}-bad-image"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: the command reports the image pull failure`.
- The output includes `PASS: the command prints the state of the pod`.
- The output includes `PASS: the command prints 'Inspect it with: wanaku cloud status semantic support-bad-image'`.
- The output includes `PASS: the command prints 'Read its logs with: kubectl logs deployment/support-bad-image-wsr'`.
- The output includes `PASS: spec.forward.name (--forward-name) =` with the expected value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 9.2: wanaku cloud status semantic reports the failure

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud status semantic "${WSR_NAME}-bad-image" --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "1"
echo "${OUTPUT}" | grep -qE "Ready=False \((ErrImagePull|ImagePullBackOff)\)" \
  && echo "PASS: status reports the image pull failure" \
  || { echo "FAIL: status does not report the image pull failure"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: status reports the image pull failure`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 9.3: Delete the failed runtime with wanaku cloud delete semantic

**Description:** Kubernetes deletes the ConfigMap and the Deployment through their owner references.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud delete semantic "${WSR_NAME}-bad-image" --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "0"
echo "${OUTPUT}" | grep -qF "Deleted WanakuSemanticRouter ${WSR_NAME}-bad-image" \
  && echo "PASS: the command reports the deletion" || { echo "FAIL: the command does not report the deletion"; FAIL=1; }
wait_for_deletion configmap "${WSR_NAME}-bad-image-semantic" "${WANAKU_NAMESPACE}" 30 || FAIL=1
wait_for_deletion deployment "${WSR_NAME}-bad-image-wsr" "${WANAKU_NAMESPACE}" 30 || FAIL=1
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: the command reports the deletion`.
- `wait_for_deletion` reports `configmap/support-bad-image-semantic` as deleted.
- `wait_for_deletion` reports `deployment/support-bad-image-wsr` as deleted.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 10: Deletion

### Test 10.1: Delete the runtime with wanaku cloud delete semantic

**Description:** Kubernetes deletes the ConfigMap, the Deployment and the Service through their owner references.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud delete semantic "${WSR_NAME}" --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "0"
echo "${OUTPUT}" | grep -qF "Deleted WanakuSemanticRouter ${WSR_NAME}" \
  && echo "PASS: the command reports the deletion" || { echo "FAIL: the command does not report the deletion"; FAIL=1; }
wait_for_deletion wanakusemanticrouter "${WSR_NAME}" "${WANAKU_NAMESPACE}" 30 || FAIL=1
wait_for_deletion configmap "${WSR_NAME}-semantic" "${WANAKU_NAMESPACE}" 45 || FAIL=1
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: the command reports the deletion`.
- `wait_for_deletion` reports `wanakusemanticrouter/support` as deleted.
- `wait_for_deletion` reports `configmap/support-semantic` as deleted.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 10.2: Verify that the runtime removed its forward and its tool

**Description:** The pod has a termination grace period of 60 seconds. At shutdown, the runtime removes its forward,
because the `wsr.instance` label still belongs to it. Wanaku Server then no longer serves `route_support`. A list
counts only when the CLI could read it: an unreachable management API must not look like a removed forward.

Run this block in the shared Bash session.

```bash run
FAIL=0
wait_for_deletion deployment "${WSR_NAME}-wsr" "${WANAKU_NAMESPACE}" 30 || FAIL=1
GONE=false
END=$(( $(date +%s) + 50 ))
while [ "$(date +%s)" -lt "${END}" ]; do
  OUTPUT=$(${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
  if [ $? -eq 0 ] && ! echo "${OUTPUT}" | grep -q "^${WSR_TOOL}[[:space:]]"; then
    GONE=true
    break
  fi
  sleep 3
done
[ "${GONE}" = "true" ] && echo "PASS: forward ${WSR_TOOL} removed by the runtime at shutdown" \
  || { echo "FAIL: forward ${WSR_TOOL} is still registered, or the forward list cannot be read: ${OUTPUT}"; FAIL=1; }

OUTPUT=$(${WANAKU_CLI} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -ne 0 ]; then
  echo "FAIL: tools list failed (exit code ${EXIT_CODE}): ${OUTPUT}"
  FAIL=1
elif echo "${OUTPUT}" | grep -q "${WSR_TOOL}"; then
  echo "FAIL: ${WSR_TOOL} is still registered in Wanaku Server"
  FAIL=1
else
  echo "PASS: ${WSR_TOOL} is no longer registered in Wanaku Server"
fi
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- `wait_for_deletion` reports `deployment/support-wsr` as deleted.
- The output includes `PASS: forward route_support removed by the runtime at shutdown`.
- The output includes `PASS: route_support is no longer registered in Wanaku Server`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 10.3: Deleting a runtime that does not exist fails

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} cloud delete semantic "${WSR_NAME}" --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
FAIL=0
check "exit code" "${EXIT_CODE}" "1"
echo "${OUTPUT}" | grep -qF "WanakuSemanticRouter ${WSR_NAME} does not exist in namespace ${WANAKU_NAMESPACE}" \
  && echo "PASS: the command reports the missing resource" \
  || { echo "FAIL: the command does not report the missing resource"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: exit code =` with the expected value.
- The output includes `PASS: the command reports the missing resource`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 11: Documented Command Without Extra Settings

### Test 11.1: Record whether wanaku cloud run semantic starts the semantic route (known issue)

**Description:** The test runs the command as `docs/cloud-run.md` describes it: the command checks the files,
submits them, and waits until the operator reports `Ready=True`. It does not add the settings of Phase 7.
`--image` and `--image-pull-policy` select the WSR image under test, and `--timeout 90` keeps each wait within the
Saci limit. When the first wait ends without a result, the second block runs the command again: the files and the
resource do not change, so the command only waits again. When the runtime is ready, the command prints the forward,
the endpoints and how to remove the runtime.

Without the expert settings, WSR stops at startup (see the Overview). The operator reports `CrashLoopBackOff`, and the
command exits with code 1. WSR prints its usage text after the error, so the termination message that the command
prints holds the end of the usage text. The test reads the error in the log of the container instead.

The test does not fail on the known issue. It prints a `KNOWN ISSUE:` line while the command cannot configure the
expert, and an `INFO:` line when the command starts the semantic route. Any other result fails.

Run these blocks in the shared Bash session.

```bash run
WSR_DOC_OUTPUT=$(${WANAKU_CLI} cloud run semantic "${WSR_ROUTE}" --image "${WSR_IMAGE}" --image-pull-policy Always \
  --timeout 90 --plain 2>&1)
WSR_DOC_EXIT_CODE=$?
echo "${WSR_DOC_OUTPUT}"
echo "exit code: ${WSR_DOC_EXIT_CODE}"
```

```bash run
if [ "${WSR_DOC_EXIT_CODE}" -ne 0 ] && echo "${WSR_DOC_OUTPUT}" | grep -q "is not ready after 90 seconds"; then
  WSR_DOC_OUTPUT=$(${WANAKU_CLI} cloud run semantic "${WSR_ROUTE}" --image "${WSR_IMAGE}" --image-pull-policy Always \
    --timeout 90 --plain 2>&1)
  WSR_DOC_EXIT_CODE=$?
  echo "${WSR_DOC_OUTPUT}"
  echo "exit code: ${WSR_DOC_EXIT_CODE}"
fi
FAIL=0
if [ "${WSR_DOC_EXIT_CODE}" -eq 0 ]; then
  for LINE in "WanakuSemanticRouter ${WSR_NAME} is ready" "Forward: ${WSR_TOOL}" \
      "In-cluster MCP address: http://${WSR_NAME}-wsr.${WANAKU_NAMESPACE}.svc:8090/mcp" \
      "To stop and remove it, run: wanaku cloud delete semantic ${WSR_NAME}"; do
    echo "${WSR_DOC_OUTPUT}" | grep -qF "${LINE}" && echo "PASS: the command prints '${LINE}'" \
      || { echo "FAIL: the command does not print '${LINE}'"; FAIL=1; }
  done
  echo "INFO: the documented command starts the semantic route. The known issue appears fixed: remove the test-only workaround of Phase 7 and update this test."
elif echo "${WSR_DOC_OUTPUT}" | grep -qF "WanakuSemanticRouter ${WSR_NAME} failed: Ready=False (CrashLoopBackOff)" \
    && { oc logs "deployment/${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" --previous 2>/dev/null
         oc logs "deployment/${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" 2>/dev/null; } \
       | grep -q "Missing setting: wsr.experts"; then
  echo "KNOWN ISSUE: wanaku cloud run semantic gives the runtime no expert settings, and WSR stops at startup with 'Missing setting: wsr.experts' (exit code ${WSR_DOC_EXIT_CODE})"
else
  echo "FAIL: the command neither started ${WSR_NAME} nor failed with the known issue (exit code ${WSR_DOC_EXIT_CODE})"
  oc logs "deployment/${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" --tail=30 2>&1
  FAIL=1
fi
[ "${FAIL}" -eq 0 ] || return 1
```

> [!WARNING]
> **Known issue:** `wanaku cloud run semantic` has no option for the expert settings and their credential, and WSR
> needs them in local-files mode. Report the issue if it is still open. When the command can set them, use that in
> Phase 6 and in this test, remove the test-only workaround of Phase 7, and turn this test into a plain check of the
> `INFO:` branch.

**Expected result:**

- The output includes `KNOWN ISSUE: wanaku cloud run semantic gives the runtime no expert settings` or `INFO: the documented command starts the semantic route.`
- When the output includes the `INFO:` line, it also includes `PASS: the command prints 'WanakuSemanticRouter support is ready'`, `PASS: the command prints 'Forward: route_support'`, `PASS: the command prints 'In-cluster MCP address: http://support-wsr.<WANAKU_NAMESPACE>.svc:8090/mcp'` and `PASS: the command prints 'To stop and remove it, run: wanaku cloud delete semantic support'`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 12: Cleanup

### Step 12.0: Delete remaining resources and local files

Delete the leftover resources and the local files of this plan, by name. Then run the full teardown in Step 12.1.

```bash run
oc delete wanakusemanticrouter "${WSR_NAME:-support}" "${WSR_NAME:-support}-bad-image" \
  -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete configmap "${WSR_NAME:-support}-semantic" "${WSR_NAME:-support}-bad-image-semantic" \
  -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete secret wsr-llm-credentials -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
# Remove only the directory that Step S.2 created
case "${WSR_CLOUD_RUN_DIR}" in
  */wanaku-cloud-run.*) rm -rf "${WSR_CLOUD_RUN_DIR}" ;;
esac
echo "PASS: cloud run test resources deleted"
```

**Expected result:**

- The output includes `PASS: cloud run test resources deleted`.

---

### Step 12.1: Run full teardown

Follow [common/cleanup.md](common/cleanup.md).

**Expected result:**

- All invoked procedure steps pass (inferred).

### Step 12.2: Remove the downloaded sources (cleanup)

The Helm release is uninstalled, so the downloaded chart is no longer needed.

```bash run
case "${WANAKU_DOWNLOADED_SOURCE_DIR}" in
  */wanaku-operator-source.*) rm -rf "${WANAKU_DOWNLOADED_SOURCE_DIR}"; echo "PASS: downloaded sources removed" ;;
  *) echo "SKIP: no downloaded sources to remove" ;;
esac
```

**Expected result:**

- The output includes `PASS: downloaded sources removed` or `SKIP: no downloaded sources to remove`.

## Test Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | — | OpenShift login | Critical |
| 1 | — | Namespace setup | Critical |
| 2 | 2.1 | Operator installation | Critical |
| 2 | 2.2 | CRD with `configMapRef.items` | Critical |
| 3 | 3.1-3.2 | WanakuRouter, Routes reachable | Critical |
| 4 | 4.1 | Route files in the local-files layout | Critical |
| 4 | 4.2 | Stored cloud target | High |
| 5 | 5.1 | Missing manifest: exit code 2 | High |
| 5 | 5.2 | Missing router: exit code 3 | High |
| 5 | 5.3 | Rejected submissions create nothing | High |
| 6 | 6.1 | Submission with `--no-wait` | Critical |
| 6 | 6.2 | ConfigMap keys, content and owner | Critical |
| 6 | 6.3 | `WanakuSemanticRouter` with `items` and the `tool.name` forward | Critical |
| 6 | 6.4 | Deployment mounts the items, `WSR_ROUTES_REF` | Critical |
| 7 | 7.1 | Expert settings (test-only workaround) | Critical |
| 7 | 7.2 | `Ready`, `Registered` | Critical |
| 8 | 8.1 | File tree in the pod, real Kamelet directory | Critical |
| 8 | 8.2 | Runtime status endpoint (`local-files`) | High |
| 8 | 8.3 | Forward and tool in Wanaku Server | Critical |
| 8 | 8.4 | `wanaku cloud status semantic` | High |
| 9 | 9.1 | Image pull failure: exit code 1 and diagnosis | High |
| 9 | 9.2 | `wanaku cloud status semantic` of a failed runtime | Medium |
| 9 | 9.3 | `wanaku cloud delete semantic` of a failed runtime | Medium |
| 10 | 10.1 | `wanaku cloud delete semantic` | Critical |
| 10 | 10.2 | The runtime removes its forward and its tool | Critical |
| 10 | 10.3 | Deleting a missing runtime fails | Medium |
| 11 | 11.1 | Documented command without extra settings (known issue) | High |
| 12 | — | Cleanup | Critical |
| Setup | S.0 | Prepare the operator Helm chart | Setup |
| Setup | S.1 | Verify prerequisites | Setup |
| Setup | S.2 | Set environment variables | Setup |
| Setup | S.3 | Define deletion helper | Setup |
| Setup | S.4 | Define condition and assertion helpers | Setup |
| Setup | S.5 | Define tool discovery helper | Setup |
