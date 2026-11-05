# Test Plan: Wanaku Operator Service Catalog on OpenShift

## Overview

This test plan verifies Wanaku Barn deployed by the Wanaku operator on OpenShift, and the service catalog
functionality on top of it. It covers the Barn resources that the operator creates for a `WanakuRouter` with
`spec.barn.enabled: true`, `WanakuServiceCatalog` management, the service catalog lifecycle with the `wanaku` CLI
(initialization, route authoring, rule generation, validation, packaging, deployment and listing, a removal and restore
round trip, templates, deployment instructions and negative tests), disabling and enabling Barn, the removal of the
deployed catalogs and the deletion of the Barn resources.

**Components under test:**

| Component       | How the plan uses it                                                                                                                                      |
|-----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Wanaku operator | Installed with Helm. Reconciles `WanakuRouter` and `WanakuServiceCatalog`.                                                                                |
| Wanaku Server   | Deployed by the operator (`spec.server`). The management Ingress `<router>-management` serves the Barn plugin proxy. Authentication is disabled.                        |
| Wanaku Barn     | Deployed by the operator because `spec.barn.enabled` is `true`. Deployment `<router>-barn`, Service `barn-<router>` (port 8080 to container port 8180). |

Barn is not exposed outside the cluster. The `wanaku service` commands reach Barn through the plugin proxy path of
Wanaku Server (Test 3.7), so they use `--host "${WANAKU_ROUTER_URL}"`. The plan uses `oc port-forward` (Step S.5) only
to check Barn directly.

A deployed catalog does not create tools by itself. Barn only stores it. Serving a catalog through a Camel
Integration Capability runtime is covered by [camel-integration-capability.md](camel-integration-capability.md). The
detailed checks for catalog versioning and removal are in the Barn unit tests `CatalogVersioningTest` and
`CatalogRemovalTest`. This plan does not repeat them.

Every step is fully automatable.

When Saci runs this plan, invoked procedures execute before their parent step. A parent's `Follow` instruction
refers to those completed procedure steps; do not locate the Markdown file in the worker or repeat the procedure.
Assess the parent criteria using the preceding procedure results and any additional state checks needed for evidence.

Run command blocks in Saci's shared Bash session, including procedure blocks, so helper functions and exported
variables remain available to later tests. Source each block in that session; a top-level `return 1` fails the
block without terminating the session. Do not execute blocks as separate scripts or enable `set -e` globally.

**Hard timeout rule:** Saci stops a command block that runs longer than 120 seconds (`limits.operation_timeout_secs`),
and a stopped block can lose the shared Bash session. Any single operation (oc wait, polling loop, port-forward setup,
etc.) that takes longer than **100 seconds** MUST be considered broken. Mark the test as FAIL immediately and abort —
do not wait longer. All `--timeout` values and polling deadlines in this plan MUST NOT exceed `100s`. A test that waits
for several slow operations splits them into separate blocks, so that each block finishes within the Saci limit.

## Prerequisites

### Required tools

| Tool         | Minimum version   | Verify command            |
|--------------|-------------------|---------------------------|
| `oc`         | 4.12+             | `oc version --client`     |
| `helm`       | 3.x               | `helm version --short`    |
| `curl`       | any               | `curl --version`          |
| `jq`         | 1.6+              | `jq --version`            |
| `tar`        | any               | `tar --version`           |
| `unzip`      | any               | `unzip -v`                |
| `base64`     | any (coreutils)   | `base64 --version`        |
| `wanaku` CLI | build from source | `${WANAKU_CLI} --version` |

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

All later phases require a successful login and identity verification in Step 0.1. Use a Saci run policy that
stops on failure so a rejected identity cannot proceed to namespace creation or deployment.

Step S.0 reuses the current directory when it contains the Wanaku Barn operator chart; otherwise it downloads it
from `wanaku-ai/wanaku-barn`. Set `WANAKU_BARN_REF` to a branch, tag or commit (default: `main`) to select the source
version. To use an existing checkout instead, explicitly set `WANAKU_REPO_ROOT` to its root; an invalid explicit path
fails rather than triggering a download. Downloaded sources are isolated in a temporary directory and removed in
Step 13.1.

All image tags default to `latest` and can be overridden.

Each run uses a new namespace and therefore a new Barn. Barn reserves the name of a removed catalog until a restore
(see Phase 6), so use unique catalog names when you run against a shared Barn.

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

Create and verify the namespace.

Follow [common/namespace-setup.md](common/namespace-setup.md).

**Expected result:**

- The namespace is created and verified.

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
for CMD in oc helm curl jq tar unzip base64; do
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

ASSET="${WANAKU_REPO_ROOT:-.}/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml"
if [ -r "${ASSET}" ]; then
  echo "PASS: required deployment asset is readable: ${ASSET}"
else
  echo "FAIL: required deployment asset is missing or unreadable: ${ASSET}"
  FAIL=1
fi

[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || { echo "FAIL: one or more prerequisites missing"; return 1; }
```

**Expected result:**

- The output includes `PASS: oc found at`.
- The output includes `PASS: helm found at`.
- The output includes `PASS: curl found at`.
- The output includes `PASS: jq found at`.
- The output includes `PASS: tar found at`.
- The output includes `PASS: unzip found at`.
- The output includes `PASS: base64 found at`.
- The output includes `PASS: the Wanaku CLI runs`.
- The output includes `PASS: required deployment asset is readable:` for the operator chart.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step S.2: Set environment variables

All image tags default to `latest` and can be overridden.

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WANAKU_BARN_IMAGE="${WANAKU_BARN_IMAGE:-quay.io/wanaku/wanaku-barn-backend:latest}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-test-router}"
# Wanaku CLI: an installed binary, or "java -jar ${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
export WANAKU_CLI="${WANAKU_CLI:-wanaku}"
# OpenShift creates edge-terminated Routes from the operator Ingresses. Set these to empty values when the ingress certificate is trusted.
export WANAKU_CLI_TLS_OPTS="${WANAKU_CLI_TLS_OPTS---insecure}"
export CURL_TLS_OPTS="${CURL_TLS_OPTS--k}"
# Local port for "oc port-forward" to the Barn Service
export BARN_LOCAL_PORT="${BARN_LOCAL_PORT:-18180}"
# Catalog sources and packages of the CLI phases
export TEST_WORK_DIR="${TEST_WORK_DIR:-$(mktemp -d)}"
export TEST_CATALOG_NAME="${TEST_CATALOG_NAME:-test-catalog}"
export TEST_PACKAGE_OUTPUT="${TEST_PACKAGE_OUTPUT:-${TEST_WORK_DIR}/test-catalog.b64}"
export TEST_ECHO_CATALOG="${TEST_ECHO_CATALOG:-${TEST_CATALOG_NAME}-echo}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step S.3: Define the wait_for_deletion helper

Define the `wait_for_deletion` function.

Follow [common/wait-for-deletion.md](common/wait-for-deletion.md).

**Expected result:**

- The block defines the `wait_for_deletion` function without errors (inferred).

### Step S.4: Define the custom resource condition helpers

The function polls a condition until it has the expected status and, optionally, the expected reason. It fails after
100 seconds.

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

router_jsonpath() {
  oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"
}

# Compares a value with the expected value. Sets FAIL=1 on a mismatch. Each test resets FAIL=0 first.
check() { # $1 = description, $2 = actual, $3 = expected
  if [ "$2" = "$3" ]; then echo "PASS: $1 = $2"; else echo "FAIL: $1 = '$2', expected '$3'"; FAIL=1; fi
}
```

**Expected result:**

- The block defines the `wait_cr_condition`, `router_jsonpath` and `check` functions without errors (inferred).

### Step S.5: Define the Wanaku Barn port-forward helpers

Barn has no Route. The CLI reaches it through `oc port-forward`. The function polls the Barn readiness endpoint
instead of a fixed sleep.

```bash run
start_barn_port_forward() {
  oc port-forward "service/barn-${WANAKU_ROUTER_NAME}" "${BARN_LOCAL_PORT}:8080" \
    -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 &
  BARN_PF_PID=$!
  for i in $(seq 1 30); do
    # A port-forward that exited (for example, because the port is in use) must not count as ready
    if kill -0 "${BARN_PF_PID}" 2>/dev/null \
      && curl --connect-timeout 1 --max-time 2 -s -o /dev/null -f "http://localhost:${BARN_LOCAL_PORT}/q/health/ready" 2>/dev/null; then
      echo "PASS: Barn reachable at http://localhost:${BARN_LOCAL_PORT} (attempt ${i})"
      export WANAKU_BARN_URL="http://localhost:${BARN_LOCAL_PORT}"
      return 0
    fi
    sleep 1
  done
  echo "FAIL: port-forward to barn-${WANAKU_ROUTER_NAME} did not become ready"
  if kill -0 "${BARN_PF_PID}" 2>/dev/null; then
    # Shows which health check is down when Barn answers but is not ready
    curl --connect-timeout 1 --max-time 2 -s "http://localhost:${BARN_LOCAL_PORT}/q/health/ready" 2>/dev/null; echo
  else
    echo "The port-forward process exited. Check that port ${BARN_LOCAL_PORT} is free."
  fi
  stop_barn_port_forward
  return 1
}

stop_barn_port_forward() {
  if [ -n "${BARN_PF_PID}" ]; then
    kill "${BARN_PF_PID}" 2>/dev/null
    wait "${BARN_PF_PID}" 2>/dev/null
    unset BARN_PF_PID
  fi
}
```

**Expected result:**

- The block defines the `start_barn_port_forward` and `stop_barn_port_forward` functions without errors (inferred).

---

## Phase 2: Operator Installation

### Step 2.1: Install the operator

Install the operator via Helm and verify CRDs, RBAC, and health endpoints.

Follow [common/operator-deployment.md](common/operator-deployment.md).

**Expected result:**

- The operator is installed via Helm, and the procedure verifies CRDs, RBAC, and health endpoints.

---

## Phase 3: WanakuRouter With Wanaku Barn

### Test 3.1: Create a WanakuRouter with Wanaku Barn

**Description:** Create a `WanakuRouter` with `spec.barn.enabled: true`. Authentication stays disabled.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  exposure:
    listeners:
      management:
        host: ${WANAKU_ROUTER_NAME}-${WANAKU_NAMESPACE}.${OPENSHIFT_APPS_DOMAIN}
      mcp:
        host: ${WANAKU_ROUTER_NAME}-mcp-${WANAKU_NAMESPACE}.${OPENSHIFT_APPS_DOMAIN}
  imagePullPolicy: Always
  server:
    image: ${WANAKU_SERVER_IMAGE}
  barn:
    enabled: true
    image: ${WANAKU_BARN_IMAGE}
    storage:
      size: 1Gi
EOF
```

**Verification - CR accepted and Ready:**

```bash run
oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o name
echo "router-cr-exists=$?"
# Expected: router-cr-exists=0

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}"
# Expected output: wanakurouter.wanaku.ai/wanaku-test-router condition met
```

**Verification - Barn available:**

Run this block in the shared Bash session.

```bash run
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" BarnAvailable True Available || return 1
```

**Expected result:**

- The output includes `router-cr-exists=0`.
- The output reports the `WanakuRouter` condition is met.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition BarnAvailable=True (reason Available)`.

### Step 3.2: Read the management URL

The operator creates the `<router>-management` Ingress for the management API. The plugin proxy test and the negative test of
the service catalog API use it. Run this block in the shared Bash session.

```bash run
export WANAKU_ROUTER_URL="https://$(oc get ingress "${WANAKU_ROUTER_NAME}-management" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.rules[0].host}')"
[ "${WANAKU_ROUTER_URL}" != "https://" ] && echo "PASS: management URL is ${WANAKU_ROUTER_URL}" \
  || { echo "FAIL: the management Ingress has no host"; return 1; }
```

**Expected result:**

- The output includes `PASS: management URL is`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.3: Verify the Wanaku Barn Deployment and Service

**Description:** With `spec.barn.enabled: true`, the operator creates the `<router>-barn` Deployment and the
`barn-<router>` Service. Barn listens on 8180. The Service exposes 8080. The probes use the Quarkus health endpoints.

Run this block in the shared Bash session.

```bash run
DEPLOYMENT="${WANAKU_ROUTER_NAME}-barn"
oc wait "deployment/${DEPLOYMENT}" --for=condition=Available --timeout=100s -n "${WANAKU_NAMESPACE}"

dep_jsonpath() { oc get deployment "${DEPLOYMENT}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
FAIL=0
check "Barn image" "$(dep_jsonpath '{.spec.template.spec.containers[0].image}')" "${WANAKU_BARN_IMAGE}"
check "Barn imagePullPolicy" "$(dep_jsonpath '{.spec.template.spec.containers[0].imagePullPolicy}')" "Always"
check "Barn liveness" "$(dep_jsonpath '{.spec.template.spec.containers[0].livenessProbe.httpGet.path}')" "/q/health/live"
check "Barn readiness" "$(dep_jsonpath '{.spec.template.spec.containers[0].readinessProbe.httpGet.path}')" "/q/health/ready"
check "Barn startup" "$(dep_jsonpath '{.spec.template.spec.containers[0].startupProbe.httpGet.path}')" "/q/health/started"
check "Barn data claim" "$(dep_jsonpath '{.spec.template.spec.volumes[?(@.name=="barn-volume")].persistentVolumeClaim.claimName}')" "${WANAKU_ROUTER_NAME}-barn-data"
check "Barn persistence folder" "$(dep_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_PERSISTENCE_INFINISPAN_BASE_FOLDER")].value}')" "/home/default/.wanaku/barn/"

SVC="barn-${WANAKU_ROUTER_NAME}"
check "Barn Service type" "$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.type}')" "ClusterIP"
check "Barn Service port" "$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[0].port}')" "8080"
check "Barn Service targetPort" "$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[0].targetPort}')" "8180"
check "status.barnUrl" "$(router_jsonpath '{.status.barnUrl}')" "http://barn-${WANAKU_ROUTER_NAME}:8080"

[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: Barn image =`.
- The output includes `PASS: Barn imagePullPolicy =`.
- The output includes `PASS: Barn liveness =`.
- The output includes `PASS: Barn readiness =`.
- The output includes `PASS: Barn startup =`.
- The output includes `PASS: Barn data claim =`.
- The output includes `PASS: Barn persistence folder =`.
- The output includes `PASS: Barn Service type =`.
- The output includes `PASS: Barn Service port =`.
- The output includes `PASS: Barn Service targetPort =`.
- The output includes `PASS: status.barnUrl =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.4: Verify the Barn PVC

**Description:** Barn stores its data on the `<router>-barn-data` PVC, with the size from `spec.barn.storage.size`.

Run this block in the shared Bash session.

```bash run
FAIL=0
for PVC in "${WANAKU_ROUTER_NAME}-barn-data"; do
  PHASE=$(oc get pvc "${PVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.phase}' 2>/dev/null)
  check "PVC ${PVC} phase" "${PHASE}" "Bound"
done
check "Barn PVC size" "$(oc get pvc "${WANAKU_ROUTER_NAME}-barn-data" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.resources.requests.storage}')" "1Gi"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: PVC <WANAKU_ROUTER_NAME>-barn-data phase =`.
- The output includes `PASS: Barn PVC size =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.5: Verify the Server configuration ConfigMap and its mount

**Description:** The operator writes `<router>-server-config`. Its `wanaku.yaml` points the `wanaku-barn` plugin
service `wanaku-barn-api` at the Barn Service. The Server Deployment mounts it as
`/etc/wanaku/wanaku.yaml` and records its hash in the pod template.

Run this block in the shared Bash session.

```bash run
CONFIG=$(oc get configmap "${WANAKU_ROUTER_NAME}-server-config" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}')
echo "${CONFIG}"
FAIL=0
for TEXT in "id: wanaku-barn" "wanaku-barn-api:" "target: http://barn-${WANAKU_ROUTER_NAME}:8080"; do
  echo "${CONFIG}" | grep -q "${TEXT}" && echo "PASS: wanaku.yaml contains '${TEXT}'" \
    || { echo "FAIL: wanaku.yaml does not contain '${TEXT}'"; FAIL=1; }
done
[ "${FAIL}" -eq 0 ] || return 1
```

Then check the mount in the Server Deployment. Run this block in the shared Bash session.

```bash run
DEPLOYMENT="${WANAKU_ROUTER_NAME}-server"
dep_jsonpath() { oc get deployment "${DEPLOYMENT}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
FAIL=0
# Barn is enabled, so the Server reads the operator-generated wanaku.yaml
check "wanaku.yaml mount" "$(dep_jsonpath '{.spec.template.spec.containers[0].volumeMounts[?(@.name=="server-config")].mountPath}')" "/etc/wanaku/wanaku.yaml"
[ -n "$(dep_jsonpath '{.spec.template.metadata.annotations.wanaku\.ai/server-config-hash}')" ] \
  && echo "PASS: pod template has the wanaku.ai/server-config-hash annotation" \
  || { echo "FAIL: wanaku.ai/server-config-hash annotation missing"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: wanaku.yaml contains 'id: wanaku-barn'`.
- The output includes `PASS: wanaku.yaml contains 'wanaku-barn-api:'`.
- The output includes `PASS: wanaku.yaml contains 'target: http://barn-<WANAKU_ROUTER_NAME>:8080'`.
- The output includes `PASS: wanaku.yaml mount =`.
- The output includes `PASS: pod template has the wanaku.ai/server-config-hash annotation`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.6: Verify the owner references of the Barn resources

**Description:** The Barn resources and the Server configuration ConfigMap have an owner reference to the
`WanakuRouter`. Kubernetes uses it to delete them in Phase 12.

Run this block in the shared Bash session.

```bash run
ROUTER_UID=$(router_jsonpath '{.metadata.uid}')
FAIL=0
for RESOURCE in "deployment/${WANAKU_ROUTER_NAME}-barn" "service/barn-${WANAKU_ROUTER_NAME}" \
    "configmap/${WANAKU_ROUTER_NAME}-server-config" "pvc/${WANAKU_ROUTER_NAME}-barn-data"; do
  OWNER_UID=$(oc get "${RESOURCE}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.metadata.ownerReferences[0].uid}' 2>/dev/null)
  check "${RESOURCE} owner" "${OWNER_UID}" "${ROUTER_UID}"
done

# The operator labels the Deployments that it manages
for DEPLOYMENT in "${WANAKU_ROUTER_NAME}-barn"; do
  check "${DEPLOYMENT} managed-by label" \
    "$(oc get deployment "${DEPLOYMENT}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.metadata.labels.app\.kubernetes\.io/managed-by}')" \
    "wanaku-operator"
done
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-barn owner =`.
- The output includes `PASS: service/barn-<WANAKU_ROUTER_NAME> owner =`.
- The output includes `PASS: configmap/<WANAKU_ROUTER_NAME>-server-config owner =`.
- The output includes `PASS: pvc/<WANAKU_ROUTER_NAME>-barn-data owner =`.
- The output includes `PASS: <WANAKU_ROUTER_NAME>-barn managed-by label = wanaku-operator`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.7: Verify the Barn plugin proxy

**Description:** Because Barn is enabled, the Server proxies `/api/plugins/wanaku-barn/wanaku-barn-api/...` to
`http://barn-<router>:8080`. `curl` is used because this test checks HTTP-level routing.

Run this block in the shared Bash session.

```bash run
CODE=$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' \
  "${WANAKU_ROUTER_URL}/api/plugins/wanaku-barn/wanaku-barn-api/api/v1/service-catalog")
[ "${CODE}" = "200" ] && echo "PASS: the Server proxies the Barn service catalog API" \
  || { echo "FAIL: Barn plugin proxy returned HTTP ${CODE} (404 'service wanaku-barn-api not found' means the wanaku.yaml is not loaded)"; return 1; }
```

**Expected result:**

- The output includes `PASS: the Server proxies the Barn service catalog API`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.8: Verify Barn with the CLI

Run this block in the shared Bash session.

```bash run
start_barn_port_forward || return 1

OUTPUT=$(${WANAKU_CLI} service catalog list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS: the CLI can list the Barn service catalogs" \
  || { echo "FAIL: service catalog list failed (exit code ${EXIT_CODE})"; stop_barn_port_forward; return 1; }

stop_barn_port_forward
```

**Expected result:**

- The output includes `PASS: Barn reachable at`.
- The output includes `PASS: the CLI can list the Barn service catalogs`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 4: WanakuServiceCatalog Lifecycle

### Test 4.1: Package a catalog and create the ConfigMap

**Description:** `wanaku service package` writes the Base64-encoded ZIP that the operator expects under the
`catalog.zip` key.

Run this block in the shared Bash session.

```bash run
export OP_CATALOG_NAME="${OP_CATALOG_NAME:-op-test-catalog}"
TEMP_DIR=$(mktemp -d)
mkdir -p "${TEMP_DIR}/catalog/${OP_CATALOG_NAME}"
cat > "${TEMP_DIR}/catalog/index.properties" <<PROPS
catalog.name=${OP_CATALOG_NAME}
catalog.description=Minimal catalog for operator verification
catalog.services=${OP_CATALOG_NAME}
catalog.routes.${OP_CATALOG_NAME}=${OP_CATALOG_NAME}/${OP_CATALOG_NAME}.camel.yaml
PROPS
cat > "${TEMP_DIR}/catalog/${OP_CATALOG_NAME}/${OP_CATALOG_NAME}.camel.yaml" <<'ROUTES'
- route:
    id: op-echo
    from:
      uri: ai-tool:op-echo
      parameters:
        description: "Echo back the input message"
        parameter.message: string
        parameter.message.description: "The message to echo back"
      steps:
        - setBody:
            simple: "${header.message}-${header.message}"
ROUTES

${WANAKU_CLI} service package --path="${TEMP_DIR}/catalog" -o "${TEMP_DIR}/catalog.b64"
[ -s "${TEMP_DIR}/catalog.b64" ] && echo "PASS: catalog packaged" || { echo "FAIL: catalog not packaged"; return 1; }

oc create configmap op-test-catalog-data --from-file=catalog.zip="${TEMP_DIR}/catalog.b64" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label configmap op-test-catalog-data wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"
# A ConfigMap without the catalog.zip key, for Test 4.5
oc create configmap op-test-catalog-nokey --from-literal=other.txt=x \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label configmap op-test-catalog-nokey wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"
rm -rf "${TEMP_DIR}"

oc get configmap op-test-catalog-data -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data}' | jq -r 'keys[]' | grep -q "^catalog.zip$"
echo "configmap-has-catalog-zip=$?"
# Expected: configmap-has-catalog-zip=0
```

**Expected result:**

- The output includes `PASS: catalog packaged`.
- The output includes `configmap-has-catalog-zip=0`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.2: Create a WanakuServiceCatalog

Run this block in the shared Bash session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-test-service-catalogs
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: ${OP_CATALOG_NAME}
      configMapRef: op-test-catalog-data
EOF

wait_cr_condition wanakuservicecatalog op-test-service-catalogs Ready True ReconciliationSucceeded || return 1

DEPLOYED=$(oc get wanakuservicecatalog op-test-service-catalogs -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.deployedCatalogs[*]}')
echo " ${DEPLOYED} " | grep -q " ${OP_CATALOG_NAME} " \
  && echo "PASS: ${OP_CATALOG_NAME} listed in status.deployedCatalogs" \
  || { echo "FAIL: status.deployedCatalogs is '${DEPLOYED}'"; return 1; }
```

**Expected result:**

- The output includes `PASS: wanakuservicecatalog/op-test-service-catalogs condition Ready=True (reason ReconciliationSucceeded)`.
- The output includes `PASS: <OP_CATALOG_NAME> listed in status.deployedCatalogs`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.3: Verify the catalog is stored in Barn

Run this block in the shared Bash session.

```bash run
start_barn_port_forward || return 1
OUTPUT=$(${WANAKU_CLI} service catalog list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
stop_barn_port_forward
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "^${OP_CATALOG_NAME}" \
  && echo "PASS: ${OP_CATALOG_NAME} is stored in Barn" \
  || { echo "FAIL: ${OP_CATALOG_NAME} not found in Barn"; return 1; }
```

**Expected result:**

- The output includes `PASS: Barn reachable at`.
- The output includes `PASS: <OP_CATALOG_NAME> is stored in Barn`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.4: Negative test - missing ConfigMap

Run this block in the shared Bash session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-bad-missing-configmap
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: missing-catalog
      configMapRef: this-configmap-does-not-exist
EOF

wait_cr_condition wanakuservicecatalog op-bad-missing-configmap Ready False DeploymentError || return 1
oc get wanakuservicecatalog op-bad-missing-configmap -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}' | grep -q "this-configmap-does-not-exist" \
  && echo "PASS: the message names the missing ConfigMap" || echo "FAIL: the message does not name the ConfigMap"
oc delete wanakuservicecatalog op-bad-missing-configmap -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: wanakuservicecatalog/op-bad-missing-configmap condition Ready=False (reason DeploymentError)`.
- The output includes `PASS: the message names the missing ConfigMap`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.5: Negative test - ConfigMap without the catalog.zip key

Run this block in the shared Bash session.

```bash run
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-bad-no-key
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: no-key-catalog
      configMapRef: op-test-catalog-nokey
EOF

wait_cr_condition wanakuservicecatalog op-bad-no-key Ready False DeploymentError || return 1
oc get wanakuservicecatalog op-bad-no-key -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}' | grep -q "catalog.zip" \
  && echo "PASS: the message names the missing catalog.zip key" || echo "FAIL: the message does not name catalog.zip"
oc delete wanakuservicecatalog op-bad-no-key -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: wanakuservicecatalog/op-bad-no-key condition Ready=False (reason DeploymentError)`.
- The output includes `PASS: the message names the missing catalog.zip key`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.6: Negative test - non-existent routerRef

Run this block in the shared Bash session.

```bash run
cat <<'EOF' | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-bad-router-ref
spec:
  routerRef: non-existent-router
  catalogs:
    - name: orphan-catalog
      configMapRef: op-test-catalog-data
EOF

wait_cr_condition wanakuservicecatalog op-bad-router-ref Ready False ValidationError || return 1
oc delete wanakuservicecatalog op-bad-router-ref -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: wanakuservicecatalog/op-bad-router-ref condition Ready=False (reason ValidationError)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 5: Service Catalog With the CLI — Author, Validate and Package

These tests follow a service catalog from its source files to a package, with the `wanaku` CLI. The validation
tests use the operator-managed Barn through the port-forward that Step 5.0 starts. Step 8.10 stops it.

### Step 5.0: Start the Barn port-forward

Run this block in the shared Bash session, so the port-forward stays available to Phases 5 to 8.

```bash run
start_barn_port_forward || return 1
```

**Expected result:**

- The output includes `PASS: Barn reachable at`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.1: Initialize a new service catalog

Run this block in the shared Bash session.

```bash run
cd "${TEST_WORK_DIR}"
${WANAKU_CLI} service init --plain --name="${TEST_CATALOG_NAME}" --services=system-a,system-b
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS: service init succeeded" \
  || { echo "FAIL: service init failed (exit code ${EXIT_CODE})"; return 1; }
CATALOG_DIR="${TEST_WORK_DIR}/${TEST_CATALOG_NAME}"
```

**Expected result:**

- The output includes `PASS: service init succeeded`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.2: Verify the catalog directory and index.properties

Run this block in the shared Bash session.

```bash run
[ -d "${CATALOG_DIR}" ] && echo "PASS: catalog directory exists" || { echo "FAIL: ${CATALOG_DIR} missing"; return 1; }
[ -f "${CATALOG_DIR}/index.properties" ] && echo "PASS: index.properties exists" || echo "FAIL: index.properties missing"
```

**Expected result:**

- The output includes `PASS: catalog directory exists`.
- The output includes `PASS: index.properties exists`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.3: Verify the service subdirectories

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

### Test 5.4: Verify the index.properties entries

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

### Step 5.5: Write the system-a route

The CIC serves a route as an MCP tool when the route starts from `ai-tool:<name>`. The tool arguments arrive as
message headers. These routes return the `message` argument with a prefix.

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

### Step 5.6: Write the system-b route

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

### Test 5.7: Generate the rules

`wanaku service expose` generates rules files from the route IDs. Rules files are optional since 0.3.0, because the
`ai-tool:` routes define the tools. The command is still supported.

```bash run
OUTPUT=$(${WANAKU_CLI} service expose --plain --path="${CATALOG_DIR}" --namespace=default 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Expose complete: 2 route(s) across 2 system(s)" \
  && echo "PASS: service expose succeeded" || echo "FAIL: service expose failed (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: service expose succeeded`.
- The output contains no line starting with `FAIL:`.

### Test 5.8: Verify the generated rules

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

### Test 5.9: Barn validates the catalog

`wanaku service validate` sends the package to Barn. Barn does not deploy it. A missing `catalog.icon` is a warning.

```bash run
OUTPUT=$(${WANAKU_CLI} service validate --plain --path="${CATALOG_DIR}" \
  --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service catalog '${TEST_CATALOG_NAME}' is valid" \
  && echo "PASS: catalog is valid" || echo "FAIL: catalog validation failed (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: catalog is valid`.
- The output contains no line starting with `FAIL:`.

### Test 5.10: Barn reports an invalid route (negative)

```bash run
BAD_DIR="${TEST_WORK_DIR}/invalid-catalog"
mkdir -p "${BAD_DIR}/sys"
printf 'catalog.name=invalid-catalog\ncatalog.description=Invalid\ncatalog.services=sys\ncatalog.routes.sys=sys/sys.camel.yaml\n' \
  > "${BAD_DIR}/index.properties"
printf -- '- route:\n    id: broken\n    steps: []\n' > "${BAD_DIR}/sys/sys.camel.yaml"

OUTPUT=$(${WANAKU_CLI} service validate --plain --path="${BAD_DIR}" --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "required property 'from' not found" \
  && echo "${OUTPUT}" | grep -q "is invalid" \
  && echo "PASS: invalid route reported" || echo "FAIL: invalid route not reported (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: invalid route reported`.
- The output contains no line starting with `FAIL:`.

### Test 5.11: Package the catalog

Run this block in the shared Bash session.

```bash run
${WANAKU_CLI} service package --plain --path="${CATALOG_DIR}" -o "${TEST_PACKAGE_OUTPUT}"
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && [ -s "${TEST_PACKAGE_OUTPUT}" ] && echo "PASS: package written to ${TEST_PACKAGE_OUTPUT}" \
  || { echo "FAIL: service package failed (exit code ${EXIT_CODE})"; return 1; }
```

**Expected result:**

- The output includes `PASS: package written to <TEST_PACKAGE_OUTPUT>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.12: The package is a Base64-encoded ZIP with all files

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

---

## Phase 6: Service Catalog With the CLI — Deploy, Remove and Restore

### Test 6.1: Deploy the catalog to Barn

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} service deploy --plain --path="${CATALOG_DIR}" --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service catalog '${TEST_CATALOG_NAME}' deployed successfully" \
  && echo "PASS: service deploy succeeded" || { echo "FAIL: service deploy failed (exit code ${EXIT_CODE})"; return 1; }
```

**Expected result:**

- The output includes `PASS: service deploy succeeded`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.2: The catalog is in the list

```bash run
OUTPUT=$(${WANAKU_CLI} service catalog list --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
echo "${OUTPUT}" | grep -q "^${TEST_CATALOG_NAME}[[:space:]]" \
  && echo "PASS: ${TEST_CATALOG_NAME} is in the catalog list" || echo "FAIL: ${TEST_CATALOG_NAME} not listed"
```

**Expected result:**

- The output includes `PASS: <TEST_CATALOG_NAME> is in the catalog list`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Test 6.3: Remove the catalog

Barn does not delete a removed catalog. It hides it, keeps its versions and reserves its name until a restore. Tests
6.3 to 6.5 run one round trip. The Barn unit tests `CatalogRemovalTest` and `CatalogVersioningTest` cover the other
cases.

```bash run
OUTPUT=$(${WANAKU_CLI} service catalog remove --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name="${TEST_CATALOG_NAME}" 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Successfully removed service catalog '${TEST_CATALOG_NAME}'" \
  && echo "PASS: catalog removed" || echo "FAIL: catalog remove failed (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: catalog removed`.
- The output contains no line starting with `FAIL:`.

### Test 6.4: The catalog is not listed, and it is in the removed list

```bash run
${WANAKU_CLI} service catalog list --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1 \
  | grep -q "^${TEST_CATALOG_NAME}[[:space:]]" \
  && echo "FAIL: removed catalog is still listed" || echo "PASS: removed catalog is not listed"

${WANAKU_CLI} service removed --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1 \
  | grep -q "^${TEST_CATALOG_NAME}[[:space:]]" \
  && echo "PASS: catalog is in the removed list" || echo "FAIL: catalog is not in the removed list"
```

**Expected result:**

- The output includes `PASS: removed catalog is not listed`.
- The output includes `PASS: catalog is in the removed list`.
- The output contains no line starting with `FAIL:`.

### Test 6.5: Restore the catalog

```bash run
OUTPUT=$(${WANAKU_CLI} service restore --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name="${TEST_CATALOG_NAME}" 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Restored '${TEST_CATALOG_NAME}'" \
  && echo "PASS: catalog restored" || echo "FAIL: restore failed (exit code ${EXIT_CODE})"
${WANAKU_CLI} service catalog list --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1 \
  | grep -q "^${TEST_CATALOG_NAME}[[:space:]]" \
  && echo "PASS: restored catalog is listed" || echo "FAIL: restored catalog is not listed"
```

**Expected result:**

- The output includes `PASS: catalog restored`.
- The output includes `PASS: restored catalog is listed`.
- The output contains no line starting with `FAIL:`.

---

## Phase 7: Service Templates and Deployment Instructions

### Test 7.1: List the templates

```bash run
OUTPUT=$(${WANAKU_CLI} service template list --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
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

### Test 7.2: Instantiate the echo-tool template

```bash run
OUTPUT=$(${WANAKU_CLI} service template instantiate --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name echo-tool --property "echo.separator= | " --service-name "${TEST_ECHO_CATALOG}" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service catalog created successfully from template 'echo-tool'" \
  && echo "PASS: echo-tool instantiated" || echo "FAIL: echo-tool instantiation failed (exit code ${EXIT_CODE})"

${WANAKU_CLI} service catalog list --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1 \
  | grep -q "^${TEST_ECHO_CATALOG}[[:space:]]" \
  && echo "PASS: ${TEST_ECHO_CATALOG} is listed" || echo "FAIL: ${TEST_ECHO_CATALOG} not listed"
```

**Expected result:**

- The output includes `PASS: echo-tool instantiated`.
- The output includes `PASS: <TEST_ECHO_CATALOG> is listed`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.

### Test 7.3: The instantiated catalog has the supplied value

```bash run
${WANAKU_CLI} service versions download --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name="${TEST_ECHO_CATALOG}" --version=1 --output="${TEST_WORK_DIR}/echo.zip" > /dev/null 2>&1
unzip -p "${TEST_WORK_DIR}/echo.zip" echo/service.properties | grep -q '^echo.separator=' \
  && unzip -p "${TEST_WORK_DIR}/echo.zip" echo/service.properties | grep '^echo.separator=' | grep -q '|' \
  && echo "PASS: echo.separator has the supplied value" || echo "FAIL: echo.separator was not replaced"
```

**Expected result:**

- The output includes `PASS: echo.separator has the supplied value`.
- The output contains no line starting with `FAIL:`.

### Test 7.4: Local instructions

`wanaku service instructions` supports the models `local` and `docker`. This test checks the `local` model.

> [!NOTE]
> The generated instructions still use the options of the gRPC-based capability (`--registration-url` of the router,
> `--grpc-port`, client credentials). They do not match the current CIC contract, which
> [camel-integration-capability.md](camel-integration-capability.md) verifies (Test 4.4). The test checks only the
> catalog and system names.

```bash run
OUTPUT=$(${WANAKU_CLI} service instructions --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
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

---

## Phase 8: Service Catalog CLI Negative Tests

### Test 8.1: Init without --name fails

```bash run
cd "${TEST_WORK_DIR}"
${WANAKU_CLI} service init --plain --services=a,b > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: init without --name rejected" || { echo "FAIL: init without --name accepted"; rm -rf a b; }
```

**Expected result:**

- The output includes `PASS: init without --name rejected`.
- The output contains no line starting with `FAIL:`.

### Test 8.2: Init without --services fails

```bash run
${WANAKU_CLI} service init --plain --name=should-fail > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: init without --services rejected" || { echo "FAIL: init without --services accepted"; rm -rf should-fail; }
```

**Expected result:**

- The output includes `PASS: init without --services rejected`.
- The output contains no line starting with `FAIL:`.

### Test 8.3: Init into an existing directory fails

```bash run
OUTPUT=$(${WANAKU_CLI} service init --plain --name="${TEST_CATALOG_NAME}" --services=x 2>&1)
[ $? -ne 0 ] && echo "${OUTPUT}" | grep -q "already exists" \
  && echo "PASS: init into an existing directory rejected" || echo "FAIL: init into an existing directory accepted"
```

**Expected result:**

- The output includes `PASS: init into an existing directory rejected`.
- The output contains no line starting with `FAIL:`.

### Test 8.4: Expose, package and deploy with a missing path fail

```bash run
for CMD in expose package; do
  OUTPUT=$(${WANAKU_CLI} service "${CMD}" --plain --path=/tmp/nonexistent-catalog-99999 2>&1)
  [ $? -ne 0 ] && echo "${OUTPUT}" | grep -q "index.properties not found" \
    && echo "PASS: ${CMD} with a missing path rejected" || echo "FAIL: ${CMD} with a missing path accepted"
done
${WANAKU_CLI} service deploy --plain --path=/tmp/nonexistent-catalog-99999 \
  --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: deploy with a missing path rejected" || echo "FAIL: deploy with a missing path accepted"
```

**Expected result:**

- The output includes `PASS: expose with a missing path rejected`.
- The output includes `PASS: package with a missing path rejected`.
- The output includes `PASS: deploy with a missing path rejected`.
- The output contains no line starting with `FAIL:`.

### Test 8.5: Commands against an unreachable host fail

```bash run
for ARGS in "service catalog list" "service template list"; do
  OUTPUT=$(${WANAKU_CLI} ${ARGS} --plain --host "http://localhost:59999" --no-auth 2>&1)
  [ $? -ne 0 ] && echo "${OUTPUT}" | grep -q "Unable to connect" \
    && echo "PASS: '${ARGS}' with an unreachable host rejected" || echo "FAIL: '${ARGS}' with an unreachable host accepted"
done
${WANAKU_CLI} service deploy --plain --path="${CATALOG_DIR}" --host "http://localhost:59999" --no-auth > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: deploy with an unreachable host rejected" || echo "FAIL: deploy with an unreachable host accepted"
```

**Expected result:**

- The output includes `PASS: 'service catalog list' with an unreachable host rejected`.
- The output includes `PASS: 'service template list' with an unreachable host rejected`.
- The output includes `PASS: deploy with an unreachable host rejected`.
- The output contains no line starting with `FAIL:`.

### Test 8.6: Instantiate an unknown template fails

```bash run
OUTPUT=$(${WANAKU_CLI} service template instantiate --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name nonexistent-template-99999 --property key=value 2>&1)
[ $? -ne 0 ] && echo "${OUTPUT}" | grep -q "does not exist" \
  && echo "PASS: unknown template rejected" || echo "FAIL: unknown template accepted"
```

**Expected result:**

- The output includes `PASS: unknown template rejected`.
- The output contains no line starting with `FAIL:`.

### Test 8.7: Instructions without --name, without --model, or for an unknown catalog fail

```bash run
${WANAKU_CLI} service instructions --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --model=local > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: instructions without --name rejected" || echo "FAIL: instructions without --name accepted"
${WANAKU_CLI} service instructions --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name="${TEST_CATALOG_NAME}" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: instructions without --model rejected" || echo "FAIL: instructions without --model accepted"
${WANAKU_CLI} service instructions --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name=nonexistent-catalog-99999 --model=local > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: instructions for an unknown catalog rejected" \
  || echo "FAIL: instructions for an unknown catalog accepted"
```

**Expected result:**

- The output includes `PASS: instructions without --name rejected`.
- The output includes `PASS: instructions without --model rejected`.
- The output includes `PASS: instructions for an unknown catalog rejected`.
- The output contains no line starting with `FAIL:`.

### Test 8.8: Catalog remove and restore with bad input fail

```bash run
${WANAKU_CLI} service catalog remove --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: remove without --name rejected" || echo "FAIL: remove without --name accepted"
${WANAKU_CLI} service catalog remove --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name=nonexistent-catalog-99999 > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: remove of an unknown catalog rejected" || echo "FAIL: remove of an unknown catalog accepted"
${WANAKU_CLI} service restore --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name=nonexistent-catalog-99999 > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: restore of an unknown catalog rejected" || echo "FAIL: restore of an unknown catalog accepted"
```

**Expected result:**

- The output includes `PASS: remove without --name rejected`.
- The output includes `PASS: remove of an unknown catalog rejected`.
- The output includes `PASS: restore of an unknown catalog rejected`.
- The output contains no line starting with `FAIL:`.

### Test 8.9: Wanaku Server does not serve `/api/v1/service-catalog`

This test confirms that Wanaku Server serves the Barn API only under its plugin proxy path (Test 3.7). With
`--direct-access`, the CLI calls `/api/v1/service-catalog` on its host without the plugin path.

```bash run
${WANAKU_CLI} service catalog list --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --direct-access \
  --no-auth > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: Wanaku Server does not serve the service catalog API" \
  || echo "FAIL: service catalog list --direct-access succeeded against Wanaku Server"
```

**Expected result:**

- The output includes `PASS: Wanaku Server does not serve the service catalog API`.
- The output contains no line starting with `FAIL:`.

### Step 8.10: Remove the CLI test catalogs and stop the port-forward

Run this block in the shared Bash session.

```bash run
for NAME in "${TEST_CATALOG_NAME}" "${TEST_ECHO_CATALOG}"; do
  ${WANAKU_CLI} service catalog remove --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth \
    --name="${NAME}" > /dev/null 2>&1 || true
done
OUTPUT=$(${WANAKU_CLI} service catalog list --plain --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
echo "${OUTPUT}" | grep -Eq "^(${TEST_CATALOG_NAME}|${TEST_ECHO_CATALOG})[[:space:]]" \
  && echo "FAIL: test catalogs still listed" || echo "PASS: test catalogs removed"
stop_barn_port_forward
```

**Expected result:**

- The output includes `PASS: test catalogs removed`.
- The output contains no line starting with `FAIL:`.

---

## Phase 9: Disable and Enable Wanaku Barn

### Test 9.1: Disable Wanaku Barn

**Description:** With `spec.barn.enabled: false`, the operator deletes the Barn Deployment, the Barn Service and the
Server configuration ConfigMap. It keeps the Barn PVC. A `WanakuServiceCatalog` for this router then fails validation.

Run these blocks in the shared Bash session. The first block disables Barn and waits for the deletions. The second
waits for the router. The third runs the negative catalog test.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec":{"barn":{"enabled":false}}}'

wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-barn" "${WANAKU_NAMESPACE}" 100 || return 1
wait_for_deletion service "barn-${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || return 1
wait_for_deletion configmap "${WANAKU_ROUTER_NAME}-server-config" "${WANAKU_NAMESPACE}" 60 || return 1

oc get pvc "${WANAKU_ROUTER_NAME}-barn-data" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
  && echo "PASS: Barn PVC kept" || { echo "FAIL: Barn PVC was deleted"; return 1; }
```

```bash run
oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}"
[ -z "$(router_jsonpath '{.status.conditions[?(@.type=="BarnAvailable")].status}')" ] \
  && echo "PASS: no BarnAvailable condition" || echo "FAIL: BarnAvailable condition still present"
[ -z "$(router_jsonpath '{.status.barnUrl}')" ] && echo "PASS: status.barnUrl removed" || echo "FAIL: status.barnUrl still set"
```

```bash run
# Negative test: a catalog needs Barn
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuServiceCatalog
metadata:
  name: op-bad-no-barn
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  catalogs:
    - name: no-barn-catalog
      configMapRef: op-test-catalog-data
EOF
wait_cr_condition wanakuservicecatalog op-bad-no-barn Ready False ValidationError || return 1
oc get wanakuservicecatalog op-bad-no-barn -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}' | grep -q "spec.barn.enabled must be true" \
  && echo "PASS: the message names spec.barn.enabled" || echo "FAIL: unexpected message"
oc delete wanakuservicecatalog op-bad-no-barn -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
```

**Expected result:**

- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-barn deleted`.
- The output includes `PASS: service/barn-<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: configmap/<WANAKU_ROUTER_NAME>-server-config deleted`.
- The output includes `PASS: Barn PVC kept`.
- The output includes `PASS: no BarnAvailable condition`.
- The output includes `PASS: status.barnUrl removed`.
- The output includes `PASS: wanakuservicecatalog/op-bad-no-barn condition Ready=False (reason ValidationError)`.
- The output includes `PASS: the message names spec.barn.enabled`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 9.2: Enable Wanaku Barn again and verify the data is kept

Run these blocks in the shared Bash session. The first block enables Barn and waits for the router. The second checks
the Barn data. If Barn is not reachable, it prints the Barn readiness response, the Barn pods, their recent events, and
the errors and warnings in the Barn log.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec":{"barn":{"enabled":true}}}'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" BarnAvailable True Available || return 1
oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}"
```

```bash run
start_barn_port_forward || {
  oc get pods -n "${WANAKU_NAMESPACE}" -o wide --request-timeout=5s | grep -E "^NAME|^${WANAKU_ROUTER_NAME}-barn-"
  oc get events -n "${WANAKU_NAMESPACE}" --sort-by=.metadata.creationTimestamp --request-timeout=5s \
    | grep -- "${WANAKU_ROUTER_NAME}-barn" | tail -n 10
  oc logs "deployment/${WANAKU_ROUTER_NAME}-barn" -n "${WANAKU_NAMESPACE}" --tail=300 \
    --request-timeout=5s --pod-running-timeout=5s | grep -E 'ERROR|WARN|Caused by' | tail -n 15
  return 1
}
OUTPUT=$(${WANAKU_CLI} service catalog list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
stop_barn_port_forward
echo "${OUTPUT}" | grep -q "^${OP_CATALOG_NAME}" \
  && echo "PASS: ${OP_CATALOG_NAME} is still in Barn after Barn was enabled again" \
  || { echo "FAIL: Barn data was lost"; echo "${OUTPUT}"; return 1; }
```

> [!WARNING]
> **Known issue:** wanaku-ai/wanaku-barn#287. Barn does not become ready after a restart on an existing store. When the
> Barn readiness response lists the Infinispan caches `catalog-version` and `catalog-version-counter` as failed, this
> test fails because of that issue: the `BarnAvailable` wait times out and the data check does not run. Any other
> cause is a new failure. Under the default policy, the run stops here, so Tests 10.1, 11.1 and 12.1 do not run.

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition BarnAvailable=True (reason Available)`.
- The output includes `PASS: Barn reachable at`.
- The output includes `PASS: <OP_CATALOG_NAME> is still in Barn after Barn was enabled again`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 10: Operator Logs

### Test 10.1: Verify the operator logs and restarts

```bash run
OPERATOR_POD=$(oc get pods -l app.kubernetes.io/name=wanaku-operator -n "${WANAKU_NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
LOGS=$(oc logs "${OPERATOR_POD}" -n "${WANAKU_NAMESPACE}")
echo "${LOGS}" | grep -q "Starting router reconciliation for ${WANAKU_ROUTER_NAME}" \
  && echo "PASS: router reconciliation logged" || echo "FAIL: no router reconciliation in the logs"
echo "${LOGS}" | grep -q "Starting service catalog reconciliation for op-test-service-catalogs" \
  && echo "PASS: service catalog reconciliation logged" || echo "FAIL: no service catalog reconciliation in the logs"

RESTARTS=$(oc get pod "${OPERATOR_POD}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.containerStatuses[0].restartCount}')
[ "${RESTARTS}" = "0" ] && echo "PASS: operator has zero restarts" || echo "WARN: operator restarted ${RESTARTS} time(s)"
```

**Expected result:**

- The output includes `PASS: router reconciliation logged`.
- The output includes `PASS: service catalog reconciliation logged`.
- The output includes `PASS: operator has zero restarts` or `WARN: operator restarted`.
- The output contains no line starting with `FAIL:`.

---

## Phase 11: Service Catalog Removal

### Test 11.1: Delete the WanakuServiceCatalog and verify Barn removes the catalog

**Description:** The operator removes the deployed catalogs from Barn before it releases the resource. Barn keeps a
removed catalog in its removed list, so it can be restored.

Run this block in the shared Bash session.

```bash run
oc delete wanakuservicecatalog op-test-service-catalogs -n "${WANAKU_NAMESPACE}" --timeout=60s
wait_for_deletion wanakuservicecatalog op-test-service-catalogs "${WANAKU_NAMESPACE}" 30 || return 1

start_barn_port_forward || return 1
ACTIVE=$(${WANAKU_CLI} service catalog list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
REMOVED=$(${WANAKU_CLI} service removed --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
stop_barn_port_forward

echo "${ACTIVE}" | grep -q "^${OP_CATALOG_NAME}" \
  && { echo "FAIL: ${OP_CATALOG_NAME} is still an active catalog"; return 1; } \
  || echo "PASS: ${OP_CATALOG_NAME} is no longer an active catalog"
echo "${REMOVED}" | grep -q "^${OP_CATALOG_NAME}" \
  && echo "PASS: ${OP_CATALOG_NAME} is in the Barn removed list" \
  || echo "WARN: ${OP_CATALOG_NAME} is not in the removed list"
```

**Expected result:**

- The output reports `PASS:` for deletion of `wanakuservicecatalog/op-test-service-catalogs`.
- The output includes `PASS: Barn reachable at`.
- The output includes `PASS: <OP_CATALOG_NAME> is no longer an active catalog`.
- The output includes `PASS: <OP_CATALOG_NAME> is in the Barn removed list` or `WARN: <OP_CATALOG_NAME> is not in the removed list`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 12: Deletion of the Barn Resources

### Test 12.1: Delete the router and verify the Barn resources are removed

Run these blocks in the shared Bash session. The first block deletes the router and waits for the Barn Deployment and
Service and the Server configuration ConfigMap. The second waits for the Barn PVC, which Kubernetes removes after its
pod has stopped.

```bash run
oc delete wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}"

FAIL=0
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-barn" "${WANAKU_NAMESPACE}" 90 || FAIL=1
wait_for_deletion service "barn-${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion configmap "${WANAKU_ROUTER_NAME}-server-config" "${WANAKU_NAMESPACE}" 60 || FAIL=1
[ "${FAIL}" -eq 0 ] || return 1
```

```bash run
FAIL=0
wait_for_deletion pvc "${WANAKU_ROUTER_NAME}-barn-data" "${WANAKU_NAMESPACE}" 50 || FAIL=1
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-barn deleted`.
- The output includes `PASS: service/barn-<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: configmap/<WANAKU_ROUTER_NAME>-server-config deleted`.
- The output includes `PASS: pvc/<WANAKU_ROUTER_NAME>-barn-data deleted`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 13: Cleanup

### Step 13.1: Full teardown and local file removal

Perform full teardown. Then remove the local files:

Follow [common/cleanup.md](common/cleanup.md).

```bash run
stop_barn_port_forward 2>/dev/null || true
cd "${HOME}"
rm -rf "${TEST_WORK_DIR}"
echo "PASS: temporary files removed"
if [ -n "${WANAKU_DOWNLOADED_SOURCE_DIR}" ]; then
  rm -rf "${WANAKU_DOWNLOADED_SOURCE_DIR}" || return 1
  unset WANAKU_DOWNLOADED_SOURCE_DIR
fi
echo "PASS: downloaded source assets removed or no download was owned"
```

**Expected result:**

- The output includes `PASS: temporary files removed`.
- The output includes `PASS: downloaded source assets removed or no download was owned`.

---

## Test Summary Matrix

| Phase | Test ID   | Test Name                                                                   | Priority |
|-------|-----------|-----------------------------------------------------------------------------|----------|
| S     | S.0       | Prepare the operator Helm chart                                             | Critical |
| S     | S.1       | Prerequisite check script                                                   | Critical |
| S     | S.2       | Environment variables                                                       | Critical |
| S     | S.3-S.5   | Deletion, condition and Barn port-forward helpers                           | Critical |
| 0     | 0.1       | Log in to OpenShift                                                         | Critical |
| 1     | 1.1       | Create the namespace                                                        | Critical |
| 2     | 2.1       | Install the operator                                                        | Critical |
| 3     | 3.1-3.2   | WanakuRouter with Barn Ready, management Ingress                            | Critical |
| 3     | 3.3-3.6   | Barn Deployment, Service and PVC, Server configuration, owner references    | Critical |
| 3     | 3.7-3.8   | Barn plugin proxy, Barn CLI access                                          | High     |
| 4     | 4.1-4.3   | WanakuServiceCatalog deploys a packaged catalog to Barn                     | High     |
| 4     | 4.4-4.6   | WanakuServiceCatalog negative tests                                         | High     |
| 5     | 5.0-5.12  | CLI: init, routes, expose, Barn validation, package                         | Critical |
| 6     | 6.1-6.5   | CLI: deploy and list, remove, removed list, restore                         | Critical |
| 7     | 7.1-7.4   | Templates (list, echo-tool instantiation, substituted value), instructions  | High     |
| 8     | 8.1-8.10  | CLI negative tests, removal of the CLI test catalogs                        | High     |
| 9     | 9.1-9.2   | Disable and enable Barn, data kept                                          | High     |
| 10    | 10.1      | Operator logs and restarts                                                  | Medium   |
| 11    | 11.1      | Catalog removed from Barn on CR deletion                                    | High     |
| 12    | 12.1      | Deletion of the Barn resources                                              | Critical |
| 13    | 13.1      | Cleanup                                                                     | Critical |
