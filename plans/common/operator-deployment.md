# Procedure: Operator Deployment via Helm

Reusable steps for installing the Wanaku operator on OpenShift using Helm.

## Prerequisites

- Namespace created (see [namespace-setup.md](namespace-setup.md))
- `WANAKU_NAMESPACE` environment variable set
- Helm 3.x installed
- The repository root is available at `WANAKU_REPO_ROOT`

## Variables

| Variable                      | Description                                                              | Example                                 |
|-------------------------------|--------------------------------------------------------------------------|-----------------------------------------|
| `WANAKU_NAMESPACE`            | Target namespace created for this run                                    | `wanaku-test-<run-id>`                  |
| `WANAKU_REPO_ROOT`            | Path to the wanaku-barn repository root (it contains the operator chart) | `/path/to/wanaku-barn`                  |
| `WANAKU_OPERATOR_IMAGE`       | Operator container image                                                 | `quay.io/wanaku/wanaku-operator:latest` |
| `WANAKU_OPERATOR_PULL_POLICY` | Image pull policy of the operator                                        | `Always`                                |

## Phase 1: Operator Deployment via Helm

### Step 1: Set the repository root

```bash run
# Set this to the root of the wanaku-barn git checkout
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_OPERATOR_IMAGE="${WANAKU_OPERATOR_IMAGE:-quay.io/wanaku/wanaku-operator:latest}"
export WANAKU_OPERATOR_PULL_POLICY="${WANAKU_OPERATOR_PULL_POLICY:-Always}"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 2: Verify the Helm chart exists

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash run
CHART_DIR="${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator"
if [ ! -f "${CHART_DIR}/Chart.yaml" ]; then
  echo "FAIL: Helm chart not found at ${CHART_DIR}"
  return 1
fi
echo "PASS: Helm chart found"
```

**Expected result:**

- The output includes `PASS: Helm chart found`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 3: Install the operator via Helm

The chart installs the four CRDs from its `crds/` directory. By default, every controller watches only the release
namespace (`JOSDK_WATCH_CURRENT`).

```bash run
helm install wanaku-operator \
  "${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator" \
  --namespace "${WANAKU_NAMESPACE}" \
  --set app.image="${WANAKU_OPERATOR_IMAGE}" \
  --set app.imagePullPolicy="${WANAKU_OPERATOR_PULL_POLICY}"
```

**Expected output:** Helm prints a success message containing `STATUS: deployed`.

Verify the release status:

```bash run
helm status wanaku-operator --namespace "${WANAKU_NAMESPACE}" -o json | jq -r '.info.status' | grep -q "deployed"
echo "helm-status=$?"
# Expected: helm-status=0
```

**Expected result:**

- The output includes `helm-status=0`.
- Helm prints a success message containing `STATUS: deployed`.

### Step 4: Wait for the operator deployment to become available

```bash run
oc wait deployment/wanaku-operator \
  --for=condition=Available \
  --timeout=100s \
  --namespace "${WANAKU_NAMESPACE}"
```

**Expected output:** `deployment.apps/wanaku-operator condition met`

**Expected result:**

- `deployment.apps/wanaku-operator condition met`

### Step 5: Verify the operator pod is running

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash run
OPERATOR_POD_STATUS=$(oc get pods -l app.kubernetes.io/name=wanaku-operator \
  -n "${WANAKU_NAMESPACE}" -o jsonpath='{.items[0].status.phase}')
if [ "${OPERATOR_POD_STATUS}" != "Running" ]; then
  echo "FAIL: operator pod status is '${OPERATOR_POD_STATUS}', expected 'Running'"
  return 1
fi
echo "PASS: operator pod is Running"

OPERATOR_IMAGE=$(oc get deployment wanaku-operator -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.spec.template.spec.containers[0].image}')
if [ "${OPERATOR_IMAGE}" != "${WANAKU_OPERATOR_IMAGE}" ]; then
  echo "FAIL: operator image is '${OPERATOR_IMAGE}', expected '${WANAKU_OPERATOR_IMAGE}'"
  return 1
fi
echo "PASS: operator image is ${OPERATOR_IMAGE}"

# All four controllers watch the release namespace
for CONTROLLER in ROUTER SERVICE_CATALOG SEMANTIC_ROUTER INTEGRATION; do
  ENV_NAME="QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_${CONTROLLER}_NAMESPACES"
  VALUE=$(oc get deployment wanaku-operator -n "${WANAKU_NAMESPACE}" \
    -o jsonpath="{.spec.template.spec.containers[0].env[?(@.name==\"${ENV_NAME}\")].value}")
  if [ "${VALUE}" != "JOSDK_WATCH_CURRENT" ]; then
    echo "FAIL: ${ENV_NAME} is '${VALUE}', expected 'JOSDK_WATCH_CURRENT'"
    return 1
  fi
  echo "PASS: ${ENV_NAME}=${VALUE}"
done
```

**Expected result:**

- The output includes `PASS: operator pod is Running`.
- The output includes `PASS: operator image is <WANAKU_OPERATOR_IMAGE>`.
- The output includes `PASS: QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_ROUTER_NAMESPACES=JOSDK_WATCH_CURRENT`.
- The output includes `PASS: QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_SERVICE_CATALOG_NAMESPACES=JOSDK_WATCH_CURRENT`.
- The output includes `PASS: QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_SEMANTIC_ROUTER_NAMESPACES=JOSDK_WATCH_CURRENT`.
- The output includes `PASS: QUARKUS_OPERATOR_SDK_CONTROLLERS_WANAKU_INTEGRATION_NAMESPACES=JOSDK_WATCH_CURRENT`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 6: Verify CRDs are installed

Helm installs the CRDs only when they do not exist yet. It does not upgrade them. If an earlier operator release
installed the CRDs, apply the current CRDs before you continue:
`oc apply -f "${WANAKU_REPO_ROOT}/apps/wanaku-operator/deploy/helm/wanaku-operator/crds/"`. A CRD that does not match
the operator causes an empty `status` on the custom resources.

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash run
for CRD in wanakurouters.wanaku.ai wanakuservicecatalogs.wanaku.ai wanakusemanticrouters.wanaku.ai wanakuintegrations.wanaku.ai; do
  if ! oc get crd "${CRD}" > /dev/null 2>&1; then
    echo "FAIL: CRD ${CRD} not found"
    return 1
  fi
  echo "PASS: CRD ${CRD} exists"
done

# The removed CRDs must not be installed by this chart
for CRD in wanakucapabilities.wanaku.ai wanakucamelroutes.wanaku.ai wanakucamelcodeexecutionengines.wanaku.ai; do
  if oc get crd "${CRD}" > /dev/null 2>&1; then
    echo "WARN: obsolete CRD ${CRD} is still installed (left over from an earlier operator version)"
  fi
done
```

**Expected result:**

- The output includes `PASS: CRD wanakurouters.wanaku.ai exists`.
- The output includes `PASS: CRD wanakuservicecatalogs.wanaku.ai exists`.
- The output includes `PASS: CRD wanakusemanticrouters.wanaku.ai exists`.
- The output includes `PASS: CRD wanakuintegrations.wanaku.ai exists`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 7: Verify RBAC resources were created

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash run
# Check ClusterRoles
for ROLE_SUFFIX in wanaku-router-cluster-role wanaku-service-catalog-cluster-role wanaku-semantic-router-cluster-role wanaku-integration-cluster-role josdk-crd-validating-cluster-role; do
  ROLE_NAME="${WANAKU_NAMESPACE}-${ROLE_SUFFIX}"
  if ! oc get clusterrole "${ROLE_NAME}" > /dev/null 2>&1; then
    echo "FAIL: ClusterRole ${ROLE_NAME} not found"
    return 1
  fi
  echo "PASS: ClusterRole ${ROLE_NAME} exists"
done

# Check the ClusterRoleBindings for the CRD validation role
for CRB in crd-validating-role-binding wanaku-router-crd-validating-role-binding \
    wanaku-service-catalog-crd-validating-role-binding wanaku-semantic-router-crd-validating-role-binding \
    wanaku-integration-crd-validating-role-binding; do
  if ! oc get clusterrolebinding "${WANAKU_NAMESPACE}-${CRB}" > /dev/null 2>&1; then
    echo "FAIL: ClusterRoleBinding ${WANAKU_NAMESPACE}-${CRB} not found"
    return 1
  fi
  echo "PASS: ClusterRoleBinding ${WANAKU_NAMESPACE}-${CRB} exists"
done

# Check the namespaced RoleBindings that grant the controller ClusterRoles (JOSDK_WATCH_CURRENT)
for RB in wanaku-operator-view wanaku-router-role-binding wanaku-service-catalog-role-binding \
    wanaku-semantic-router-role-binding wanaku-integration-role-binding; do
  if ! oc get rolebinding "${RB}" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1; then
    echo "FAIL: RoleBinding ${RB} not found"
    return 1
  fi
  echo "PASS: RoleBinding ${RB} exists"
done

# Check ServiceAccount
oc get serviceaccount wanaku-operator -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1
echo "service-account-exists=$?"
# Expected: service-account-exists=0

# The operator must be able to create Ingresses (needed for spec.exposure.host).
# This check needs impersonation rights. If your user cannot impersonate, record SKIP for this line.
oc auth can-i create ingresses.networking.k8s.io -n "${WANAKU_NAMESPACE}" \
  --as="system:serviceaccount:${WANAKU_NAMESPACE}:wanaku-operator" | grep -q "yes"
echo "can-create-ingress=$?"
# Expected: can-create-ingress=0
```

**Expected result:**

- The output includes `PASS: ClusterRole <WANAKU_NAMESPACE>-wanaku-router-cluster-role exists`.
- The output includes `PASS: ClusterRole <WANAKU_NAMESPACE>-wanaku-service-catalog-cluster-role exists`.
- The output includes `PASS: ClusterRole <WANAKU_NAMESPACE>-wanaku-semantic-router-cluster-role exists`.
- The output includes `PASS: ClusterRole <WANAKU_NAMESPACE>-wanaku-integration-cluster-role exists`.
- The output includes `PASS: ClusterRole <WANAKU_NAMESPACE>-josdk-crd-validating-cluster-role exists`.
- The output includes `PASS: ClusterRoleBinding <WANAKU_NAMESPACE>-crd-validating-role-binding exists`.
- The output includes `PASS: ClusterRoleBinding <WANAKU_NAMESPACE>-wanaku-router-crd-validating-role-binding exists`.
- The output includes `PASS: ClusterRoleBinding <WANAKU_NAMESPACE>-wanaku-service-catalog-crd-validating-role-binding exists`.
- The output includes `PASS: ClusterRoleBinding <WANAKU_NAMESPACE>-wanaku-semantic-router-crd-validating-role-binding exists`.
- The output includes `PASS: ClusterRoleBinding <WANAKU_NAMESPACE>-wanaku-integration-crd-validating-role-binding exists`.
- The output includes `PASS: RoleBinding wanaku-operator-view exists`.
- The output includes `PASS: RoleBinding wanaku-router-role-binding exists`.
- The output includes `PASS: RoleBinding wanaku-service-catalog-role-binding exists`.
- The output includes `PASS: RoleBinding wanaku-semantic-router-role-binding exists`.
- The output includes `PASS: RoleBinding wanaku-integration-role-binding exists`.
- The output includes `service-account-exists=0`.
- The output includes `can-create-ingress=0`, or this impersonation check is recorded as SKIP when the user cannot impersonate.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 8: Verify operator health endpoints

The operator Deployment uses the Quarkus health endpoints on port `8081` (`/q/health/started`, `/q/health/ready` and
`/q/health/live`) as probes. The pod is Ready only when the readiness probe succeeds. The operator image does not need
`curl` for this check.

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash run
for PROBE in livenessProbe readinessProbe startupProbe; do
  PROBE_PATH=$(oc get deployment wanaku-operator -n "${WANAKU_NAMESPACE}" \
    -o jsonpath="{.spec.template.spec.containers[0].${PROBE}.httpGet.path}")
  case "${PROBE_PATH}" in
    /q/health/*) echo "PASS: ${PROBE} uses ${PROBE_PATH}" ;;
    *) echo "FAIL: ${PROBE} path is '${PROBE_PATH}'"; return 1 ;;
  esac
done

oc wait pod -l app.kubernetes.io/name=wanaku-operator \
  --for=condition=Ready --timeout=100s -n "${WANAKU_NAMESPACE}"
echo "operator-ready=$?"
# Expected: operator-ready=0
```

**Expected result:**

- The output includes `PASS: livenessProbe uses <PROBE_PATH>`.
- The output includes `PASS: readinessProbe uses <PROBE_PATH>`.
- The output includes `PASS: startupProbe uses <PROBE_PATH>`.
- The output includes `operator-ready=0`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
