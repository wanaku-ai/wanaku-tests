# Procedure: Namespace Setup

Reusable steps for creating and configuring the test namespace on OpenShift.
Use this shared setup from each OpenShift plan instead of re-defining the namespace derivation inline.

## Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `WANAKU_NAMESPACE_PREFIX` | Prefix used to derive a per-run namespace when one is not provided | `wanaku-test` |
| `WANAKU_TEST_RUN_ID` | Unique suffix for the namespace when one is not provided | `20260716-043000` |
| `WANAKU_NAMESPACE` | Target namespace for all Wanaku resources | `wanaku-test-<run-id>` |

## Phase 1: Namespace Setup

### Step 1: Export the namespace variable

```bash run
export WANAKU_TEST_RUN_ID="${WANAKU_TEST_RUN_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
export WANAKU_NAMESPACE_PREFIX="${WANAKU_NAMESPACE_PREFIX:-wanaku-test}"
export WANAKU_NAMESPACE="${WANAKU_NAMESPACE:-${WANAKU_NAMESPACE_PREFIX}-${WANAKU_TEST_RUN_ID}}"
```

This keeps each test run isolated by default while still allowing callers to override `WANAKU_NAMESPACE` when they intentionally want to reuse a namespace.

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 2: Create the namespace (project)

```bash run
if oc new-project "${WANAKU_NAMESPACE}" --display-name="Wanaku Operator Test" 2>/dev/null; then
  export WANAKU_NAMESPACE_CREATED=true
else
  # A repeated run of this step keeps the value of the first run
  export WANAKU_NAMESPACE_CREATED="${WANAKU_NAMESPACE_CREATED:-false}"
  oc project "${WANAKU_NAMESPACE}"
fi
echo "namespace-created=${WANAKU_NAMESPACE_CREATED}"
```

`WANAKU_NAMESPACE_CREATED` records if this run created the project. The cleanup procedure deletes the project only
when the value is `true`. A namespace that already exists, for example a shared namespace that the caller sets in
`WANAKU_NAMESPACE`, is not deleted. When you run this step again in the same session, the value of the first run
stays.

Verify that the current project is the new namespace:

```bash run
[ "$(oc project -q)" = "${WANAKU_NAMESPACE}" ]
echo "exit_code=$?"
# Expected: exit_code=0
```

**Expected result:**

- The output includes `namespace-created=true` or `namespace-created=false`.
- The output includes `exit_code=0`.

### Step 3: Verify current context targets the correct namespace

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash run
CURRENT_NS=$(oc project -q)
if [ "${CURRENT_NS}" != "${WANAKU_NAMESPACE}" ]; then
  echo "FAIL: current namespace is '${CURRENT_NS}', expected '${WANAKU_NAMESPACE}'"
  return 1
fi
echo "PASS: namespace is ${WANAKU_NAMESPACE}"
```

**Expected result:**

- The output includes `PASS: namespace is <WANAKU_NAMESPACE>`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 4: Verify the user has sufficient permissions

```bash run
oc auth can-i create deployments -n "${WANAKU_NAMESPACE}" | grep -q "yes"
echo "can-create-deployments=$?"

oc auth can-i create services -n "${WANAKU_NAMESPACE}" | grep -q "yes"
echo "can-create-services=$?"

oc auth can-i create persistentvolumeclaims -n "${WANAKU_NAMESPACE}" | grep -q "yes"
echo "can-create-pvcs=$?"

oc auth can-i create ingresses.networking.k8s.io -n "${WANAKU_NAMESPACE}" | grep -q "yes"
echo "can-create-ingresses=$?"

oc auth can-i create configmaps -n "${WANAKU_NAMESPACE}" | grep -q "yes"
echo "can-create-configmaps=$?"

oc auth can-i create secrets -n "${WANAKU_NAMESPACE}" | grep -q "yes"
echo "can-create-secrets=$?"

# Expected: all values should be 0
```

**Expected result:**

- The output includes `can-create-deployments=0`.
- The output includes `can-create-services=0`.
- The output includes `can-create-pvcs=0`.
- The output includes `can-create-ingresses=0`.
- The output includes `can-create-configmaps=0`.
- The output includes `can-create-secrets=0`.

## Output Variables

| Variable             | Description                                                                          |
|----------------------|--------------------------------------------------------------------------------------|
| `WANAKU_NAMESPACE`   | The namespace (OpenShift project) for this run. The current `oc` context targets it. |
| `WANAKU_TEST_RUN_ID` | The unique suffix of this run                                                        |
| `WANAKU_NAMESPACE_CREATED` | `true` when this run created the namespace. The cleanup deletes it only in that case. |
