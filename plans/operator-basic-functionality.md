# Test Plan: Wanaku Operator Basic Functionality on OpenShift

## Overview

This test plan verifies the basic functionality of the Wanaku Kubernetes operator on OpenShift. It covers operator
installation, the `WanakuRouter` lifecycle, the resources that the operator creates, Wanaku Server behavior through
the generated Routes, authentication through oauth2-proxy, update reconciliation, and the deletion cascade. Wanaku Barn
and `WanakuServiceCatalog` are covered by [operator-service-catalog.md](operator-service-catalog.md).

**Components under test:**

| Component       | How the plan uses it                                                                                                                                                                                 |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Wanaku operator | Installed with Helm. Reconciles `WanakuRouter`.                                                                                                                                                      |
| Wanaku Server   | Deployed by the operator (`spec.server`). Deployment `<router>-server`, Service `server-<router>` (ports 8080 management, 8081 MCP, 8083 inference, 8084 A2A), Routes `<router>` and `<router>-mcp`. |
| oauth2-proxy    | Deployed by the operator in Phase 6 (`spec.auth.enabled: true`). Deployment `<router>-oauth2-proxy`, Service `oauth2-proxy-<router>` (ports 4180 MCP, 4181 management).                              |
| Keycloak        | OIDC provider for Phase 6 (see [common/keycloak-setup.md](common/keycloak-setup.md)).                                                                                                                |

The plan does not call tools. Wanaku Server only gets tools from forwards, and the forward workflow on OpenShift is
covered by [camel-integration-capability.md](camel-integration-capability.md).

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

> [!NOTE]
> `docs/operator.md` recommends a wait of 300 seconds for `Ready`, because the Server startup probe allows 150
> seconds and image pulls add time. On a slow cluster, pre-pull the images or record the timeout as an environment
> problem, not as an operator failure.

## Prerequisites

### Required tools

| Tool            | Minimum version              | Verify command            |
|-----------------|------------------------------|---------------------------|
| `oc`            | 4.12+                        | `oc version --client`     |
| `helm`          | 3.x                          | `helm version --short`    |
| `curl`          | any (HTTP-level checks only) | `curl --version`          |
| `jq`            | 1.6+                         | `jq --version`            |
| `openssl`       | any (cookie secret)          | `openssl version`         |
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

All later phases require a successful login and identity verification in Step 0.1. Use a Saci run policy that
stops on failure so a rejected identity cannot proceed to namespace creation or deployment.

Step S.0 reuses the current directory when it contains the Wanaku Barn operator chart and realm configuration;
otherwise it downloads those assets from `wanaku-ai/wanaku-barn`. Set `WANAKU_BARN_REF` to a branch, tag or commit
(default: `main`) to select the source version. To use an existing checkout instead, explicitly set
`WANAKU_REPO_ROOT` to its root; an invalid explicit path fails rather than triggering a download. Downloaded
sources are isolated in a temporary directory and removed after Helm teardown in Step 10.1.

Supply `WANAKU_KEYCLOAK_ADMIN_CLI` if its executable is not named `wanaku-keycloak-admin`. Both CLIs must already
exist in the worker; the plan does not build or download executable binaries.

All image tags default to `latest` and can be overridden. The setup phase sets the environment variables after
Step 1.1 creates and verifies the unique `WANAKU_NAMESPACE` for this run.

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

### Step S.0: Prepare the operator Helm chart and realm configuration

Reuse a supplied Wanaku Barn checkout, or download its source archive from the official repository. Keep the
chart available through Helm teardown. Run this block in the shared Bash session.

```bash run
WANAKU_DOWNLOADED_SOURCE_DIR=""
export WANAKU_BARN_REF="${WANAKU_BARN_REF:-main}"
if [ -n "${WANAKU_REPO_ROOT+x}" ]; then
  if [ ! -r "${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
      || [ ! -r "${WANAKU_REPO_ROOT}/deploy/auth/wanaku-config.json" ]; then
    echo "FAIL: supplied WANAKU_REPO_ROOT lacks the operator chart or realm configuration"
    return 1
  fi
  export WANAKU_REPO_ROOT
  echo "PASS: existing Wanaku Barn source assets reused"
elif [ -r "./apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
    && [ -r "./deploy/auth/wanaku-config.json" ]; then
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
  if [ ! -r "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml" ] \
      || [ ! -r "${WANAKU_DOWNLOADED_SOURCE_DIR}/repo/deploy/auth/wanaku-config.json" ]; then
    echo "FAIL: downloaded source lacks the operator chart or realm configuration"
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
for CMD in oc helm curl jq openssl tar; do
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

if ! ${WANAKU_KEYCLOAK_ADMIN_CLI:-wanaku-keycloak-admin} --help > /dev/null 2>&1; then
  echo "FAIL: the Keycloak admin CLI does not run"
  FAIL=1
else
  echo "PASS: the Keycloak admin CLI runs"
fi

for ASSET in "${WANAKU_REPO_ROOT:-.}/deploy/auth/wanaku-config.json" \
    "${WANAKU_REPO_ROOT:-.}/apps/wanaku-operator/deploy/helm/wanaku-operator/Chart.yaml"; do
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
- The output includes `PASS: openssl found at`.
- The output includes `PASS: tar found at`.
- The output includes `PASS: the Wanaku CLI runs`.
- The output includes `PASS: the Keycloak admin CLI runs`.
- The output includes `PASS: required deployment asset is readable:` for the realm configuration and operator chart.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step S.2: Set environment variables

All image tags default to `latest` and can be overridden.

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-test-router}"
# Wanaku CLI: an installed binary, or "java -jar ${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
export WANAKU_CLI="${WANAKU_CLI:-wanaku}"
# The operator creates edge-terminated Routes. Set these to empty values when the ingress certificate is trusted.
export WANAKU_CLI_TLS_OPTS="${WANAKU_CLI_TLS_OPTS---insecure}"
export CURL_TLS_OPTS="${CURL_TLS_OPTS--k}"
# Isolate credentials per test run to avoid contention (see #1697)
export WANAKU_CREDENTIALS="${WANAKU_CREDENTIALS:-/tmp/wanaku-creds-${WANAKU_NAMESPACE}}"
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

---

## Phase 1: Environment Setup (continued)

### Step 1.2: Deploy Keycloak

Follow [common/keycloak-setup.md](common/keycloak-setup.md).

Keycloak is used only in Phase 6. After completion,
verify these variables are set:

Run this block in the shared Bash session.

```bash run
for VAR_NAME in KEYCLOAK_URL WANAKU_CLIENT_SECRET WANAKU_AUTH_SECRET_NAME WANAKU_AUTH_ISSUER_URL WANAKU_AUTH_INTERNAL_ISSUER_URL WANAKU_TEST_USER WANAKU_TEST_PASS; do
  eval "VAL=\${${VAR_NAME}}"
  if [ -z "${VAL}" ]; then
    echo "FAIL: ${VAR_NAME} is not set"
    return 1
  fi
  echo "PASS: ${VAR_NAME} is set"
done
```

**Expected result:**

- The output includes `PASS: KEYCLOAK_URL is set`.
- The output includes `PASS: WANAKU_CLIENT_SECRET is set`.
- The output includes `PASS: WANAKU_AUTH_SECRET_NAME is set`.
- The output includes `PASS: WANAKU_AUTH_ISSUER_URL is set`.
- The output includes `PASS: WANAKU_AUTH_INTERNAL_ISSUER_URL is set`.
- The output includes `PASS: WANAKU_TEST_USER is set`.
- The output includes `PASS: WANAKU_TEST_PASS is set`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 2: Operator Installation

### Step 2.1: Install the operator

Install the operator via Helm and verify CRDs, RBAC, and health endpoints.

Follow [common/operator-deployment.md](common/operator-deployment.md).

**Expected result:**

- The operator is installed via Helm, and the procedure verifies CRDs, RBAC, and health endpoints.


---

## Phase 3: WanakuRouter Lifecycle

### Test 3.1: Create a WanakuRouter without authentication

**Description:** Create a `WanakuRouter`. Authentication stays disabled until Phase 6.

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
```

**Verification - CR accepted and Ready:**

```bash run
oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o name
echo "router-cr-exists=$?"
# Expected: router-cr-exists=0

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}"
# Expected output: wanakurouter.wanaku.ai/wanaku-test-router condition met
```

**Expected result:**

- The output includes `router-cr-exists=0`.
- The output reports the `WanakuRouter` condition is met.

### Test 3.2: Verify the router conditions

**Description:** `Ready` is `True` only when every other condition is `True`. Without authentication, `AuthConfigured`
is `True` with the reason `AuthDisabled`, and the operator records one `Warning` event.

Run this block in the shared Bash session.

```bash run
FAIL=0
for EXPECTED in "Reconciled:True:ReconcileSucceeded" "ServerAvailable:True:Available" \
    "AuthConfigured:True:AuthDisabled" "ExposureReady:True:Exposed" "Ready:True:"; do
  TYPE=$(echo "${EXPECTED}" | cut -d: -f1)
  STATUS=$(echo "${EXPECTED}" | cut -d: -f2)
  REASON=$(echo "${EXPECTED}" | cut -d: -f3)
  wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" "${TYPE}" "${STATUS}" "${REASON}" || FAIL=1
done

# Without auth there is no AuthProxyAvailable condition
[ -z "$(router_jsonpath '{.status.conditions[?(@.type=="AuthProxyAvailable")].status}')" ] \
  && echo "PASS: no AuthProxyAvailable condition without auth" \
  || { echo "FAIL: AuthProxyAvailable condition present without auth"; FAIL=1; }

GEN=$(router_jsonpath '{.metadata.generation}')
OBS=$(router_jsonpath '{.status.observedGeneration}')
[ "${GEN}" = "${OBS}" ] && echo "PASS: observedGeneration=${OBS}" \
  || { echo "FAIL: observedGeneration=${OBS}, generation=${GEN}"; FAIL=1; }

oc get events -n "${WANAKU_NAMESPACE}" --field-selector "involvedObject.name=${WANAKU_ROUTER_NAME},type=Warning" \
  -o jsonpath='{.items[*].reason}' | grep -q "AuthDisabled" \
  && echo "PASS: Warning event AuthDisabled recorded" \
  || echo "WARN: no AuthDisabled Warning event found (events may have expired)"

[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Reconciled=True (reason ReconcileSucceeded)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition ServerAvailable=True (reason Available)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthConfigured=True (reason AuthDisabled)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition ExposureReady=True (reason Exposed)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=True`.
- The output includes `PASS: no AuthProxyAvailable condition without auth`.
- The output includes `PASS: observedGeneration=`.
- The output includes `PASS: Warning event AuthDisabled recorded` or `WARN: no AuthDisabled Warning event found (events may have expired)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.3: Verify the Wanaku Server Deployment

**Description:** The operator creates the `<router>-server` Deployment with the requested image, the global pull
policy, the persistence settings and the `/healthz` probes on the named `management` port.

Run this block in the shared Bash session.

```bash run
DEPLOYMENT="${WANAKU_ROUTER_NAME}-server"
oc wait "deployment/${DEPLOYMENT}" --for=condition=Available --timeout=100s -n "${WANAKU_NAMESPACE}"

dep_jsonpath() { oc get deployment "${DEPLOYMENT}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
FAIL=0

check "image" "$(dep_jsonpath '{.spec.template.spec.containers[0].image}')" "${WANAKU_SERVER_IMAGE}"
check "imagePullPolicy" "$(dep_jsonpath '{.spec.template.spec.containers[0].imagePullPolicy}')" "Always"
check "container name" "$(dep_jsonpath '{.spec.template.spec.containers[0].name}')" "wanaku-server"
check "WANAKU_MGMT_LISTEN" "$(dep_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_MGMT_LISTEN")].value}')" "0.0.0.0:8080"
check "WANAKU_PERSIST_BACKEND" "$(dep_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_PERSIST_BACKEND")].value}')" "file"
check "WANAKU_PERSIST_PATH" "$(dep_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_PERSIST_PATH")].value}')" "/data/registry"
for PROBE in livenessProbe readinessProbe startupProbe; do
  check "${PROBE} path" "$(dep_jsonpath "{.spec.template.spec.containers[0].${PROBE}.httpGet.path}")" "/healthz"
  check "${PROBE} port" "$(dep_jsonpath "{.spec.template.spec.containers[0].${PROBE}.httpGet.port}")" "management"
done
check "registry volume claim" "$(dep_jsonpath '{.spec.template.spec.volumes[?(@.name=="server-volume")].persistentVolumeClaim.claimName}')" "${WANAKU_ROUTER_NAME}-server-data"

[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: image =`.
- The output includes `PASS: imagePullPolicy =`.
- The output includes `PASS: container name =`.
- The output includes `PASS: WANAKU_MGMT_LISTEN =`.
- The output includes `PASS: WANAKU_PERSIST_BACKEND =`.
- The output includes `PASS: WANAKU_PERSIST_PATH =`.
- The output includes `PASS: livenessProbe path =`.
- The output includes `PASS: livenessProbe port =`.
- The output includes `PASS: readinessProbe path =`.
- The output includes `PASS: readinessProbe port =`.
- The output includes `PASS: startupProbe path =`.
- The output includes `PASS: startupProbe port =`.
- The output includes `PASS: registry volume claim =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.4: Verify the Wanaku Server Service

**Description:** The `server-<router>` ClusterIP Service exposes the four listener ports.

Run this block in the shared Bash session.

```bash run
SVC="server-${WANAKU_ROUTER_NAME}"
SVC_TYPE=$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.type}')
[ "${SVC_TYPE}" = "ClusterIP" ] && echo "PASS: ${SVC} is ClusterIP" || { echo "FAIL: ${SVC} type is '${SVC_TYPE}'"; return 1; }

PORTS=$(oc get service "${SVC}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{range .spec.ports[*]}{.name}={.port}{" "}{end}')
echo "ports: ${PORTS}"
for EXPECTED in management=8080 mcp=8081 inference=8083 a2a=8084; do
  echo " ${PORTS} " | grep -q " ${EXPECTED} " && echo "PASS: port ${EXPECTED}" || { echo "FAIL: port ${EXPECTED} missing"; return 1; }
done
```

**Expected result:**

- The output includes `PASS: <SVC> is ClusterIP`.
- The output includes `PASS: port management=8080`.
- The output includes `PASS: port mcp=8081`.
- The output includes `PASS: port inference=8083`.
- The output includes `PASS: port a2a=8084`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.6: Verify the PVCs

**Description:** The Server stores `registry.json` on the `<WANAKU_ROUTER_NAME>-server-data` PVC.

Run this block in the shared Bash session.

```bash run
FAIL=0
for PVC in "${WANAKU_ROUTER_NAME}-server-data"; do
  PHASE=$(oc get pvc "${PVC}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.phase}' 2>/dev/null)
  check "PVC ${PVC} phase" "${PHASE}" "Bound"
done
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: PVC <WANAKU_ROUTER_NAME>-server-data phase =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.8: Verify the OpenShift Routes

**Description:** Without `spec.exposure`, the operator creates two Routes on OpenShift. The `<router>` Route sends
`/` to the management port 8080. The `<router>-mcp` Route sends `/` to the MCP port 8081. Both use edge TLS
termination with a redirect from HTTP.

Run this block in the shared Bash session.

```bash run
FAIL=0
for ENTRY in "${WANAKU_ROUTER_NAME}:8080" "${WANAKU_ROUTER_NAME}-mcp:8081"; do
  ROUTE=$(echo "${ENTRY}" | cut -d: -f1)
  PORT=$(echo "${ENTRY}" | cut -d: -f2)
  route_jsonpath() { oc get route "${ROUTE}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
  [ -n "$(route_jsonpath '{.spec.host}')" ] && echo "PASS: Route ${ROUTE} has a host" \
    || { echo "FAIL: Route ${ROUTE} has no host"; FAIL=1; }
  check "Route ${ROUTE} service" "$(route_jsonpath '{.spec.to.name}')" "server-${WANAKU_ROUTER_NAME}"
  check "Route ${ROUTE} targetPort" "$(route_jsonpath '{.spec.port.targetPort}')" "${PORT}"
  check "Route ${ROUTE} path" "$(route_jsonpath '{.spec.path}')" "/"
  check "Route ${ROUTE} TLS" "$(route_jsonpath '{.spec.tls.termination}')" "edge"
  check "Route ${ROUTE} admitted" "$(route_jsonpath '{.status.ingress[0].conditions[?(@.type=="Admitted")].status}')" "True"
done
[ "${FAIL}" -eq 0 ] || return 1

export WANAKU_ROUTER_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_ROUTER_MCP_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
echo "management: ${WANAKU_ROUTER_URL}"
echo "mcp:        ${WANAKU_ROUTER_MCP_URL}"
```

**Expected result:**

- The output includes `PASS: Route <WANAKU_ROUTER_NAME> has a host`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> service =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> targetPort =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> path =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> TLS =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME> admitted =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp has a host`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp service =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp targetPort =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp path =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp TLS =`.
- The output includes `PASS: Route <WANAKU_ROUTER_NAME>-mcp admitted =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.9: Verify the status endpoints

**Description:** `status.host` contains the management URL. `status.endpoints` contains one URL per exposed listener
and, for the standard Server variant, the admin UI URL.

Run this block in the shared Bash session.

```bash run
FAIL=0
MCP_HOST=$(echo "${WANAKU_ROUTER_MCP_URL}" | sed 's|^https://||')
check "status.host" "$(router_jsonpath '{.status.host}')" "${WANAKU_ROUTER_URL}"
check "status.endpoints.management" "$(router_jsonpath '{.status.endpoints.management}')" "${WANAKU_ROUTER_URL}/"
check "status.endpoints.mcp" "$(router_jsonpath '{.status.endpoints.mcp}')" "${WANAKU_ROUTER_MCP_URL}/default/mcp"
check "status.endpoints.adminUi" "$(router_jsonpath '{.status.endpoints.adminUi}')" "${WANAKU_ROUTER_URL}/admin/"

STREAMABLE=$(router_jsonpath '{.status.streamableEndpoint}')
case "${STREAMABLE}" in
  "https://${MCP_HOST}/"*) echo "PASS: status.streamableEndpoint=${STREAMABLE}" ;;
  *) echo "FAIL: status.streamableEndpoint='${STREAMABLE}'"; FAIL=1 ;;
esac
[ "${FAIL}" -eq 0 ] || return 1
```

> [!NOTE]
> Since wanaku-ai/wanaku-barn#282, `status.endpoints.mcp` and `status.streamableEndpoint` name the endpoint of the
> `default` namespace (`https://<mcp-host>/default/mcp`), and `status.sseEndpoint` is empty. Wanaku Server rejects the
> bare `/mcp` path (Test 4.4).

**Expected result:**

- The output includes `PASS: status.host =`.
- The output includes `PASS: status.endpoints.management =`.
- The output includes `PASS: status.endpoints.mcp =`.
- The output includes `PASS: status.endpoints.adminUi =`.
- The output includes `PASS: status.streamableEndpoint=`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.10: Verify owner references

**Description:** All operator-managed resources have an owner reference to the `WanakuRouter`. Kubernetes uses it to
delete them in Phase 9.

Run this block in the shared Bash session.

```bash run
ROUTER_UID=$(router_jsonpath '{.metadata.uid}')
FAIL=0
for RESOURCE in "deployment/${WANAKU_ROUTER_NAME}-server" "service/server-${WANAKU_ROUTER_NAME}" \
    "pvc/${WANAKU_ROUTER_NAME}-server-data" \
    "route.route.openshift.io/${WANAKU_ROUTER_NAME}" "route.route.openshift.io/${WANAKU_ROUTER_NAME}-mcp"; do
  OWNER_UID=$(oc get "${RESOURCE}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.metadata.ownerReferences[0].uid}' 2>/dev/null)
  check "${RESOURCE} owner" "${OWNER_UID}" "${ROUTER_UID}"
done

# The operator labels the Deployments that it manages
for DEPLOYMENT in "${WANAKU_ROUTER_NAME}-server"; do
  check "${DEPLOYMENT} managed-by label" \
    "$(oc get deployment "${DEPLOYMENT}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.metadata.labels.app\.kubernetes\.io/managed-by}')" \
    "wanaku-operator"
done
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-server owner =`.
- The output includes `PASS: service/server-<WANAKU_ROUTER_NAME> owner =`.
- The output includes `PASS: pvc/<WANAKU_ROUTER_NAME>-server-data owner =`.
- The output includes `PASS: route.route.openshift.io/<WANAKU_ROUTER_NAME> owner =`.
- The output includes `PASS: route.route.openshift.io/<WANAKU_ROUTER_NAME>-mcp owner =`.
- The output includes `PASS: <WANAKU_ROUTER_NAME>-server managed-by label = wanaku-operator`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 4: Wanaku Server Through the Routes

Authentication is disabled in this phase. Use `--no-auth` for the CLI.

### Test 4.1: Verify the health endpoints

**Description:** `GET /health` and `GET /healthz` return `200` with the response envelope `{"data":{"status":"ok"},"error":null}`.
`curl` is used because this test checks HTTP-level behavior.

Run this block in the shared Bash session.

```bash run
for PATH_SUFFIX in /health /healthz; do
  BODY=$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -w '\n%{http_code}' "${WANAKU_ROUTER_URL}${PATH_SUFFIX}")
  CODE=$(echo "${BODY}" | tail -1)
  STATUS=$(echo "${BODY}" | sed '$d' | jq -r '.data.status' 2>/dev/null)
  if [ "${CODE}" = "200" ] && [ "${STATUS}" = "ok" ]; then
    echo "PASS: ${PATH_SUFFIX} returns 200 and status ok"
  else
    echo "FAIL: ${PATH_SUFFIX} returned HTTP ${CODE}, status '${STATUS}'"
    return 1
  fi
done
```

**Expected result:**

- The output includes `PASS: /health returns 200 and status ok`.
- The output includes `PASS: /healthz returns 200 and status ok`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.2: Verify the management API with the CLI

**Description:** The CLI lists the namespaces through the management Route. The `default` namespace always exists.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} namespaces list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "^default"; then
  echo "PASS: namespace 'default' listed through ${WANAKU_ROUTER_URL}"
else
  echo "FAIL: namespaces list failed (exit code ${EXIT_CODE})"
  return 1
fi
```

**Expected result:**

- The output includes `PASS: namespace 'default' listed through`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.3: Create a namespace, and reject an invalid name

**Description:** Namespace names are DNS labels. The Server rejects a name with uppercase letters or underscores.

Run this block in the shared Bash session.

```bash run
export OP_TEST_NAMESPACE="${OP_TEST_NAMESPACE:-op-test-ns}"

OUTPUT=$(${WANAKU_CLI} namespaces create "${OP_TEST_NAMESPACE}" --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "Namespace created: ${OP_TEST_NAMESPACE}" \
  && echo "PASS: namespace ${OP_TEST_NAMESPACE} created" \
  || { echo "FAIL: namespace ${OP_TEST_NAMESPACE} not created"; return 1; }

# Negative test
OUTPUT=$(${WANAKU_CLI} namespaces create "Bad_NS" --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "lowercase alphanumeric"; then
  echo "PASS: invalid namespace name rejected"
else
  echo "FAIL: invalid namespace name was not rejected (exit code ${EXIT_CODE})"
  return 1
fi
```

**Expected result:**

- The output includes `PASS: namespace <OP_TEST_NAMESPACE> created`.
- The output includes `PASS: invalid namespace name rejected`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.4: Verify the MCP endpoint through the MCP Route

**Description:** The MCP endpoint is `<mcp-route>/<namespace>/mcp` (Streamable HTTP). No forward exists, so the tool
list is empty. The bare `/mcp` path is rejected with the JSON-RPC error `-32600`.

Run this block in the shared Bash session.

```bash run
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS: MCP endpoint ${WANAKU_ROUTER_MCP_URL}/default/mcp answers" \
  || { echo "FAIL: MCP tool list failed (exit code ${EXIT_CODE})"; return 1; }

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/${OP_TEST_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
[ $? -eq 0 ] && echo "PASS: namespace endpoint /${OP_TEST_NAMESPACE}/mcp answers" \
  || { echo "FAIL: namespace endpoint failed: ${OUTPUT}"; return 1; }

# Negative test: bare /mcp
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "invalid MCP endpoint path"; then
  echo "PASS: bare /mcp rejected (-32600 invalid MCP endpoint path)"
else
  echo "FAIL: bare /mcp was not rejected (exit code ${EXIT_CODE}): ${OUTPUT}"
  return 1
fi
```

**Expected result:**

- The output includes `PASS: MCP endpoint <WANAKU_ROUTER_MCP_URL>/default/mcp answers`.
- The output includes `PASS: namespace endpoint /<OP_TEST_NAMESPACE>/mcp answers`.
- The output includes `PASS: bare /mcp rejected (-32600 invalid MCP endpoint path)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.5: Verify the admin UI

**Description:** The standard Server variant serves the admin UI at `/admin/`. `curl` is used because this test checks
HTTP-level routing.

Run this block in the shared Bash session.

```bash run
CODE=$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/admin/")
[ "${CODE}" = "200" ] && echo "PASS: admin UI returns 200" || { echo "FAIL: admin UI returned HTTP ${CODE}"; return 1; }
```

**Expected result:**

- The output includes `PASS: admin UI returns 200`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.7: Verify the Server registry survives a pod restart

**Description:** The Server writes `registry.json` to `/data/registry` on the `<WANAKU_ROUTER_NAME>-server-data` PVC. A
namespace created in Test 4.3 must still exist after the pod is replaced.

Run these blocks in the shared Bash session. The first block deletes the pod. The second waits for the replacement
pod and checks the namespace.

```bash run
OLD_POD=$(oc get pods -l "app=${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
oc delete pod "${OLD_POD}" -n "${WANAKU_NAMESPACE}" --wait=true --timeout=100s
```

```bash run
oc rollout status "deployment/${WANAKU_ROUTER_NAME}-server" --timeout=100s -n "${WANAKU_NAMESPACE}" \
  || { echo "FAIL: deployment/${WANAKU_ROUTER_NAME}-server did not roll out"; return 1; }
oc wait pod -l "app=${WANAKU_ROUTER_NAME}-server" --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}"

OUTPUT=$(${WANAKU_CLI} namespaces list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^${OP_TEST_NAMESPACE}" \
  && echo "PASS: namespace ${OP_TEST_NAMESPACE} persisted across the pod restart" \
  || { echo "FAIL: namespace ${OP_TEST_NAMESPACE} lost after the pod restart"; echo "${OUTPUT}"; return 1; }
```

**Expected result:**

- The output includes `PASS: namespace <OP_TEST_NAMESPACE> persisted across the pod restart`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 6: Authentication Through oauth2-proxy

### Test 6.1: Negative test - auth enabled without issuerUrl

**Description:** `spec.auth.issuerUrl` and `spec.auth.secretName` are required when `spec.auth.enabled` is `true`.
Validation fails before the operator changes any resource.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p "{\"spec\":{\"auth\":{\"enabled\":true,\"secretName\":\"${WANAKU_AUTH_SECRET_NAME}\"}}}"

wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Reconciled False ValidationError || return 1
router_jsonpath '{.status.conditions[?(@.type=="Reconciled")].message}' | grep -q "spec.auth.issuerUrl is required" \
  && echo "PASS: the message names spec.auth.issuerUrl" || echo "FAIL: unexpected validation message"
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Ready False ValidationError || return 1
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Reconciled=False (reason ValidationError)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=False (reason ValidationError)`.
- The output includes `PASS: the message names spec.auth.issuerUrl`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.2: Negative test - the auth Secret does not exist

**Description:** The operator does not deploy the oauth2-proxy Deployment while the Secret is missing.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p "{\"spec\":{\"auth\":{\"enabled\":true,\"issuerUrl\":\"${WANAKU_AUTH_ISSUER_URL}\",\"internalIssuerUrl\":\"${WANAKU_AUTH_INTERNAL_ISSUER_URL}\",\"clientId\":\"wanaku-mcp-router\",\"secretName\":\"op-missing-secret\"}}}"

wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" AuthConfigured False SecretNotFound || return 1
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Ready False || return 1

if oc get deployment "${WANAKU_ROUTER_NAME}-oauth2-proxy" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1; then
  echo "FAIL: oauth2-proxy Deployment exists although the Secret is missing"
  return 1
fi
echo "PASS: no oauth2-proxy Deployment without a valid Secret"
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthConfigured=False (reason SecretNotFound)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=False`.
- The output includes `PASS: no oauth2-proxy Deployment without a valid Secret`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.3: Negative test - invalid cookie secret

Run this block in the shared Bash session.

```bash run
oc create secret generic op-bad-cookie-secret \
  --from-literal=client-secret="${WANAKU_CLIENT_SECRET}" \
  --from-literal=cookie-secret="too-short" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label secret op-bad-cookie-secret wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"

oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p '{"spec":{"auth":{"secretName":"op-bad-cookie-secret"}}}'

wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" AuthConfigured False InvalidCookieSecret || return 1
```

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthConfigured=False (reason InvalidCookieSecret)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.4: Enable authentication with a valid Secret

**Description:** With the Secret from [common/keycloak-setup.md](common/keycloak-setup.md) step 9, the operator
deploys the two oauth2-proxy containers and points both Routes at the proxy Service.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p "{\"spec\":{\"auth\":{\"secretName\":\"${WANAKU_AUTH_SECRET_NAME}\"}}}"

wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" AuthConfigured True Configured || return 1
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" AuthProxyAvailable True Available || return 1
oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}"
```

**Verification - oauth2-proxy resources:**

Run this block in the shared Bash session.

```bash run
FAIL=0
PROXY="${WANAKU_ROUTER_NAME}-oauth2-proxy"
CONTAINERS=$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.template.spec.containers[*].name}')
for C in oauth2-proxy-mcp oauth2-proxy-mgmt; do
  echo " ${CONTAINERS} " | grep -q " ${C} " && echo "PASS: container ${C}" || { echo "FAIL: container ${C} missing"; FAIL=1; }
done
check "proxy client id" "$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="oauth2-proxy-mgmt")].env[?(@.name=="OAUTH2_PROXY_CLIENT_ID")].value}')" "wanaku-mcp-router"
check "proxy issuer" "$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="oauth2-proxy-mgmt")].env[?(@.name=="OAUTH2_PROXY_OIDC_ISSUER_URL")].value}')" "${WANAKU_AUTH_ISSUER_URL}"

PORTS=$(oc get service "oauth2-proxy-${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.ports[*].port}')
check "proxy Service ports" "${PORTS}" "4180 4181"

check "management Route service" "$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.to.name}')" "oauth2-proxy-${WANAKU_ROUTER_NAME}"
check "management Route port" "$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.port.targetPort}')" "4181"
check "MCP Route service" "$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.to.name}')" "oauth2-proxy-${WANAKU_ROUTER_NAME}"
check "MCP Route port" "$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.port.targetPort}')" "4180"

# The Server advertises the issuer to MCP clients
SERVER_ENV='{.spec.template.spec.containers[0].env[?(@.name=="%s")].value}'
check "WANAKU_AUTH_ISSUER" "$(oc get deployment "${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" -o jsonpath="$(printf "${SERVER_ENV}" WANAKU_AUTH_ISSUER)")" "${WANAKU_AUTH_ISSUER_URL}"
check "WANAKU_AUTH_UPSTREAM_ISSUER" "$(oc get deployment "${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" -o jsonpath="$(printf "${SERVER_ENV}" WANAKU_AUTH_UPSTREAM_ISSUER)")" "${WANAKU_AUTH_INTERNAL_ISSUER_URL}"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: proxy client id =`.
- The output includes `PASS: proxy issuer =`.
- The output includes `PASS: proxy Service ports =`.
- The output includes `PASS: management Route service =`.
- The output includes `PASS: management Route port =`.
- The output includes `PASS: MCP Route service =`.
- The output includes `PASS: MCP Route port =`.
- The output includes `PASS: WANAKU_AUTH_ISSUER =`.
- The output includes `PASS: WANAKU_AUTH_UPSTREAM_ISSUER =`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthConfigured=True (reason Configured)`.
- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition AuthProxyAvailable=True (reason Available)`.
- The output includes `PASS: container oauth2-proxy-mcp`.
- The output includes `PASS: container oauth2-proxy-mgmt`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.5: Verify unauthenticated requests through the proxies

**Description:** Health endpoints do not need a token. Management API and MCP requests without a token get `401`.
They are not redirected to the login page. `curl` is used because this test checks HTTP status codes.

Run this block in the shared Bash session.

```bash run
FAIL=0
check "/health without token" "$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/health")" "200"
check "/api/v1/namespaces without token" "$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/api/v1/namespaces")" "401"
check "/default/mcp without token" "$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}' \
  "${WANAKU_ROUTER_MCP_URL}/default/mcp")" "401"
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: /health without token =`.
- The output includes `PASS: /api/v1/namespaces without token =`.
- The output includes `PASS: /default/mcp without token =`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.6: Verify the OAuth protected-resource metadata

**Description:** When `WANAKU_AUTH_ISSUER` is set, the Server serves RFC 9728 metadata with a non-empty
`authorization_servers` list. The Server names its own MCP host as the authorization server and serves the discovery
document at `/.well-known/oauth-authorization-server`. Without `WANAKU_AUTH_ISSUER`, the list is empty. The proxy lets
`/.well-known/*` pass without a token.

Run this block in the shared Bash session.

```bash run
MCP_HOST=$(echo "${WANAKU_ROUTER_MCP_URL}" | sed 's|^https://||')
BODY=$(curl --connect-timeout 5 --max-time 15 -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_MCP_URL}/.well-known/oauth-protected-resource/default/mcp")
echo "${BODY}"
echo "${BODY}" | jq -e '.authorization_servers | length > 0' > /dev/null \
  && echo "PASS: authorization_servers is not empty" \
  || { echo "FAIL: authorization_servers is empty (WANAKU_AUTH_ISSUER not applied?)"; return 1; }
echo "${BODY}" | jq -r '.authorization_servers[0]' | grep -q "${MCP_HOST}" \
  && echo "PASS: authorization server is the MCP host" \
  || echo "FAIL: authorization server is not ${MCP_HOST}"
# The Server builds the URLs from the Host header with the http scheme, also behind the TLS Route
echo "${BODY}" | jq -r '.authorization_servers[0]' | grep -q '^https://' \
  || echo "WARN: authorization_servers uses http:// behind an edge-terminated Route (report if still open)"
```

**Expected result:**

- The output includes `PASS: authorization_servers is not empty`.
- The output includes `PASS: authorization server is the MCP host`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.7: Verify the OIDC login and the token through the management proxy

Use `WANAKU_ROUTER_URL` from Test 3.8.

Follow [common/oidc-login-verification.md](common/oidc-login-verification.md).

It logs in as `WANAKU_TEST_USER`, calls the management API with the token, and verifies that a request
without a token fails.

**Expected result:**

- The procedure logs in as `WANAKU_TEST_USER`, calls the management API with the token, and verifies that a request without a token fails.

### Test 6.8: Verify the token through the MCP proxy

The MCP CLI commands require an explicit token. Read the token saved by the preceding login without printing it.

Run this block in the shared Bash session.

```bash run
MCP_PROXY_TOKEN=$(${WANAKU_CLI} auth token --get --unmask --plain 2>/dev/null) \
  || { echo "FAIL: could not read the saved login token"; return 1; }
[ -n "${MCP_PROXY_TOKEN}" ] || { echo "FAIL: saved login token is empty"; return 1; }
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" \
  --token "${MCP_PROXY_TOKEN}" ${WANAKU_CLI_TLS_OPTS} --plain 2>&1)
[ $? -eq 0 ] && echo "PASS: MCP proxy accepts the token" || { echo "FAIL: MCP proxy rejected the token: ${OUTPUT}"; return 1; }

# Negative test: no token
OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/default/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
[ $? -ne 0 ] && echo "PASS: MCP proxy rejects requests without a token" \
  || { echo "FAIL: MCP proxy accepted a request without a token"; return 1; }
```

**Expected result:**

- The output includes `PASS: MCP proxy accepts the token`.
- The output includes `PASS: MCP proxy rejects requests without a token`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.9: Verify a Secret change restarts the proxies

**Description:** The operator stores the Secret `resourceVersion` in the `wanaku.ai/auth-secret-version` pod
annotation. A Secret change rolls the proxy pods.

Run this block in the shared Bash session.

```bash run
PROXY="${WANAKU_ROUTER_NAME}-oauth2-proxy"
OLD_VERSION=$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.spec.template.metadata.annotations.wanaku\.ai/auth-secret-version}')

oc create secret generic "${WANAKU_AUTH_SECRET_NAME}" \
  --from-literal=client-secret="${WANAKU_CLIENT_SECRET}" \
  --from-literal=cookie-secret="$(openssl rand -hex 16)" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -

NEW_VERSION="${OLD_VERSION}"
for i in $(seq 1 30); do
  NEW_VERSION=$(oc get deployment "${PROXY}" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath='{.spec.template.metadata.annotations.wanaku\.ai/auth-secret-version}')
  [ "${NEW_VERSION}" != "${OLD_VERSION}" ] && break
  sleep 3
done
[ "${NEW_VERSION}" != "${OLD_VERSION}" ] && echo "PASS: auth-secret-version changed (${OLD_VERSION} -> ${NEW_VERSION})" \
  || { echo "FAIL: auth-secret-version did not change"; return 1; }
oc rollout status "deployment/${PROXY}" --timeout=100s -n "${WANAKU_NAMESPACE}"
```

**Expected result:**

- The output includes `PASS: auth-secret-version changed`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 7: Update Reconciliation

### Test 7.1: Add an environment variable to the Server

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge \
  -p '{"spec":{"server":{"env":[{"name":"RUST_LOG","value":"info"}]}}}'

for i in $(seq 1 30); do
  VALUE=$(oc get deployment "${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" \
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="RUST_LOG")].value}')
  [ "${VALUE}" = "info" ] && break
  sleep 3
done
[ "${VALUE}" = "info" ] && echo "PASS: RUST_LOG added to the Server Deployment" || { echo "FAIL: RUST_LOG='${VALUE}'"; return 1; }
oc rollout status "deployment/${WANAKU_ROUTER_NAME}-server" --timeout=100s -n "${WANAKU_NAMESPACE}"
```

**Expected result:**

- The output includes `PASS: RUST_LOG added to the Server Deployment`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.2: Negative tests - invalid spec values

**Description:** An invalid `spec.server.variant` or `spec.exposure.type` sets `Reconciled=False` with the reason
`ValidationError`. The operator does not change the workloads.

Run this block in the shared Bash session.

```bash run
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec":{"server":{"variant":"invalid"}}}'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Reconciled False ValidationError || return 1
router_jsonpath '{.status.conditions[?(@.type=="Reconciled")].message}' | grep -q "spec.server.variant must be standard or headless" \
  && echo "PASS: variant validation message" || echo "FAIL: unexpected validation message"
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=json -p '[{"op":"remove","path":"/spec/server/variant"}]'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Ready True || return 1

oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"spec":{"exposure":{"type":"Bogus"}}}'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Reconciled False ValidationError || return 1
oc patch wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --type=json -p '[{"op":"remove","path":"/spec/exposure"}]'
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" Ready True || return 1
```

**Expected result:**

- The output includes `PASS: variant validation message`.
- For the invalid `spec.server.variant`, the output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Reconciled=False (reason ValidationError)`.
- After restoring `spec.server.variant`, the output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=True`.
- For the invalid `spec.exposure.type`, the output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Reconciled=False (reason ValidationError)`.
- After restoring `spec.exposure`, the output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition Ready=True`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.5: Verify the operator logs and restarts

```bash run
OPERATOR_POD=$(oc get pods -l app.kubernetes.io/name=wanaku-operator -n "${WANAKU_NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')
LOGS=$(oc logs "${OPERATOR_POD}" -n "${WANAKU_NAMESPACE}")
echo "${LOGS}" | grep -q "Starting router reconciliation for ${WANAKU_ROUTER_NAME}" \
  && echo "PASS: router reconciliation logged" || echo "FAIL: no router reconciliation in the logs"

RESTARTS=$(oc get pod "${OPERATOR_POD}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.containerStatuses[0].restartCount}')
[ "${RESTARTS}" = "0" ] && echo "PASS: operator has zero restarts" || echo "WARN: operator restarted ${RESTARTS} time(s)"
```

**Expected result:**

- The output includes `PASS: router reconciliation logged`.
- The output includes `PASS: operator has zero restarts` or `WARN: operator restarted`.
- The output contains no line starting with `FAIL:`.


---

## Phase 9: Deletion and Ownership Cascade

### Test 9.1: Delete the router and verify the managed resources are removed

Run these blocks in the shared Bash session. The first block deletes the router and waits for the workloads, Services
and Routes. The second waits for the PVC, which Kubernetes removes after its pod has stopped.

```bash run
oc delete wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}"

FAIL=0
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-server" "${WANAKU_NAMESPACE}" 90 || FAIL=1
wait_for_deletion deployment "${WANAKU_ROUTER_NAME}-oauth2-proxy" "${WANAKU_NAMESPACE}" 90 || FAIL=1
wait_for_deletion service "server-${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion service "oauth2-proxy-${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion route "${WANAKU_ROUTER_NAME}" "${WANAKU_NAMESPACE}" 60 || FAIL=1
wait_for_deletion route "${WANAKU_ROUTER_NAME}-mcp" "${WANAKU_NAMESPACE}" 60 || FAIL=1
[ "${FAIL}" -eq 0 ] || return 1
```

```bash run
FAIL=0
wait_for_deletion pvc "${WANAKU_ROUTER_NAME}-server-data" "${WANAKU_NAMESPACE}" 50 || FAIL=1
[ "${FAIL}" -eq 0 ] || return 1
```

**Expected result:**

- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-server deleted`.
- The output includes `PASS: deployment/<WANAKU_ROUTER_NAME>-oauth2-proxy deleted`.
- The output includes `PASS: service/server-<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: service/oauth2-proxy-<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: route/<WANAKU_ROUTER_NAME> deleted`.
- The output includes `PASS: route/<WANAKU_ROUTER_NAME>-mcp deleted`.
- The output includes `PASS: pvc/<WANAKU_ROUTER_NAME>-server-data deleted`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.


---

## Phase 10: Cleanup

### Step 10.1: Full teardown and local credential removal

Perform full teardown. Then remove the local credential store:

Follow [common/cleanup.md](common/cleanup.md).

```bash run
rm -rf "${WANAKU_CREDENTIALS}"
echo "PASS: local credentials removed"
if [ -n "${WANAKU_DOWNLOADED_SOURCE_DIR}" ]; then
  rm -rf "${WANAKU_DOWNLOADED_SOURCE_DIR}" || return 1
  unset WANAKU_DOWNLOADED_SOURCE_DIR
fi
echo "PASS: downloaded source assets removed or no download was owned"
```

**Expected result:**

- The output includes `PASS: local credentials removed`.
- The output includes `PASS: downloaded source assets removed or no download was owned`.


---

## Test Summary Matrix

| Phase | Test ID | Test Name                                                              | Priority |
|-------|---------|------------------------------------------------------------------------|----------|
| S     | S.0     | Prepare the operator Helm chart and realm configuration                | Critical |
| S     | S.1     | Prerequisite check script                                              | Critical |
| S     | S.2     | Environment variables                                                  | Critical |
| S     | S.3-S.4 | Deletion and condition helpers                                         | Critical |
| 0     | 0.1     | Log in to OpenShift                                                    | Critical |
| 0     | —       | OpenShift login                                                        | Critical |
| 1     | 1.1-1.2 | Environment setup (namespace, Keycloak, auth Secret)                   | Critical |
| 2     | 2.1     | Install the operator                                                   | Critical |
| 2     | —       | Operator installation, CRDs, RBAC and health                           | Critical |
| 3     | 3.1-3.2 | WanakuRouter Ready and conditions                                      | Critical |
| 3     | 3.3-3.6 | Server Deployment, Service and PVC                                     | Critical |
| 3     | 3.8-3.9 | Routes and status endpoints                                            | Critical |
| 3     | 3.10    | Owner references and managed-by labels                                 | High     |
| 4     | 4.1-4.2 | Health endpoints and management API through the Route                  | Critical |
| 4     | 4.3     | Namespace creation and DNS-label validation                            | High     |
| 4     | 4.4     | MCP endpoint `/<namespace>/mcp`, bare `/mcp` rejected                  | Critical |
| 4     | 4.5     | Admin UI                                                               | High     |
| 4     | 4.7     | Registry persistence across a pod restart                              | High     |
| 6     | 6.1-6.3 | Authentication negative tests (validation, Secret)                     | High     |
| 6     | 6.4-6.6 | oauth2-proxy resources, 401 without token, protected-resource metadata | Critical |
| 6     | 6.7-6.8 | OIDC login, token accepted by the management and MCP proxies           | Critical |
| 6     | 6.9     | Secret change restarts the proxies                                     | Medium   |
| 7     | 7.1-7.2 | Update reconciliation and spec validation                              | Medium   |
| 7     | 7.5     | Operator logs and restarts                                             | Medium   |
| 9     | 9.1     | Deletion and ownership cascade                                         | Critical |
| 10    | 10.1    | Full teardown and local credential removal                             | Critical |
| 10    | —       | Cleanup                                                                | Critical |
