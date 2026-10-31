# Procedure: Cleanup and Teardown

Reusable steps for cleaning up all Wanaku test resources from OpenShift.

All steps are idempotent. You can run them again after a partial or failed cleanup.

## Prerequisites

- `oc` CLI installed and logged in (see [openshift-login.md](openshift-login.md))
- `WANAKU_NAMESPACE` environment variable set to the namespace created for this test run
- `helm` CLI installed (only for step 7)

## Phase 1: Cleanup and Teardown

### Step 1: Delete the runtime custom resources

Delete the `WanakuIntegration` and `WanakuSemanticRouter` resources first. Their runtimes remove their forwards from
Wanaku Server when they stop, so the router must still be running.

```bash run
oc delete wanakuintegration --all -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
echo "integrations-deleted=$?"
# Expected: integrations-deleted=0

oc delete wanakusemanticrouter --all -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
echo "semantic-routers-deleted=$?"
# Expected: semantic-routers-deleted=0
```

**Expected result:**

- The output includes `integrations-deleted=0`.
- The output includes `semantic-routers-deleted=0`.

### Step 2: Delete WanakuServiceCatalog resources

The operator removes the deployed catalogs from Wanaku Barn before it releases each resource. Delete the catalogs
before the router, so that Barn is still available.

```bash run
oc delete wanakuservicecatalog --all -n "${WANAKU_NAMESPACE}" --ignore-not-found=true --timeout=90s
echo "service-catalogs-deleted=$?"
# Expected: service-catalogs-deleted=0
```

If the command times out because the router is already gone, remove the finalizers:

```bash
for SC in $(oc get wanakuservicecatalog -n "${WANAKU_NAMESPACE}" -o name 2>/dev/null); do
  oc patch "${SC}" -n "${WANAKU_NAMESPACE}" --type=merge -p '{"metadata":{"finalizers":null}}' 2>/dev/null || true
done
```

**Expected result:**

- The output includes `service-catalogs-deleted=0`.

### Step 3: Delete WanakuRouter resources

```bash run
oc delete wanakurouter --all -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
echo "routers-deleted=$?"
# Expected: routers-deleted=0
```

**Expected result:**

- The output includes `routers-deleted=0`.

### Step 4: Wait for the operator-managed resources to be removed

The managed Deployments, Services, ConfigMaps, Routes and PVCs have an owner reference to their custom resource.
Kubernetes garbage collection removes them. The operator labels its Deployments with
`app.kubernetes.io/managed-by=wanaku-operator`.

```bash run
oc wait --for=delete deployment -l app.kubernetes.io/managed-by=wanaku-operator \
  -n "${WANAKU_NAMESPACE}" --timeout=90s 2>/dev/null || true

REMAINING=$(oc get deployments -l app.kubernetes.io/managed-by=wanaku-operator \
  -n "${WANAKU_NAMESPACE}" -o name 2>/dev/null | wc -l | tr -d ' ')

if [ "${REMAINING}" != "0" ]; then
  echo "WARN: ${REMAINING} operator-managed deployments still present"
  oc get deployments -l app.kubernetes.io/managed-by=wanaku-operator -n "${WANAKU_NAMESPACE}" -o name
else
  echo "PASS: operator-managed deployments cleaned up"
fi

# The Server PVC (<router>-server-data) and the Barn PVC (<router>-barn-data) are owned by the router
oc get pvc -n "${WANAKU_NAMESPACE}" -o name 2>/dev/null | grep -v "keycloak-data-pvc" || echo "PASS: no Wanaku PVCs remain"
```

**Expected result:**

- The output includes `PASS: operator-managed deployments cleaned up` or `WARN: <REMAINING> operator-managed deployments still present`.

### Step 5: Delete test ConfigMaps and Secrets

Test plans label the ConfigMaps and Secrets that they create with `wanaku-test=true`.

```bash run
oc delete configmap -l wanaku-test=true -n "${WANAKU_NAMESPACE}" --ignore-not-found=true 2>/dev/null || true
oc delete secret -l wanaku-test=true -n "${WANAKU_NAMESPACE}" --ignore-not-found=true 2>/dev/null || true
oc delete secret "${WANAKU_AUTH_SECRET_NAME:-wanaku-oidc}" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true 2>/dev/null || true
echo "PASS: test ConfigMaps and Secrets cleaned up"
```

**Expected result:**

- The output includes `PASS: test ConfigMaps and Secrets cleaned up`.

### Step 6: Delete Keycloak

```bash run
oc delete deployment keycloak -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete service keycloak -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete route keycloak -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete pvc keycloak-data-pvc -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
echo "PASS: Keycloak resources deleted"
```

**Expected result:**

- The output includes `PASS: Keycloak resources deleted`.

### Step 7: Uninstall the operator Helm release

Helm removes the namespace-prefixed ClusterRoles and ClusterRoleBindings of the release. Helm does not remove the
CRDs.

```bash run
helm uninstall wanaku-operator --namespace "${WANAKU_NAMESPACE}" 2>/dev/null || true
echo "PASS: Helm release uninstalled"
```

**Expected result:**

- The output includes `PASS: Helm release uninstalled`.

### Step 8: Delete CRDs (optional, only if full cleanup is desired)

```bash run
# WARNING: This removes CRDs cluster-wide. Only do this if no other namespace uses them.
# Uncomment the following lines for a full cleanup:
#
# oc delete crd wanakurouters.wanaku.ai wanakuservicecatalogs.wanaku.ai \
#   wanakusemanticrouters.wanaku.ai wanakuintegrations.wanaku.ai --ignore-not-found=true
# echo "PASS: CRDs deleted"

echo "SKIP: CRD deletion (uncomment to enable)"
```

**Expected result:**

- The output includes `SKIP: CRD deletion (uncomment to enable)`.

### Step 9: Delete the namespace (optional)

The test service account lives in `wanaku-test-infra`, not in `${WANAKU_NAMESPACE}`, so deleting the test namespace does not affect the shared test infrastructure. Only delete the namespace when it was created for this specific run. If you intentionally reuse a shared namespace, leave the deletion commented out and skip namespace cleanup for that shared environment.

```bash run
# WARNING: This deletes everything in the namespace.
# Uncomment the following line for a full cleanup:
#
# oc delete project "${WANAKU_NAMESPACE}" --ignore-not-found=true
# echo "PASS: namespace ${WANAKU_NAMESPACE} deleted"

echo "SKIP: namespace deletion (uncomment to enable)"
```

**Expected result:**

- The output includes `SKIP: namespace deletion (uncomment to enable)`.

### Step 10: Verify cleanup

```bash run
echo "--- Remaining resources in ${WANAKU_NAMESPACE} ---"
oc get all -n "${WANAKU_NAMESPACE}" 2>/dev/null || echo "(namespace may have been deleted)"
echo "--- Remaining Wanaku custom resources ---"
oc get wanakurouter,wanakuservicecatalog,wanakusemanticrouter,wanakuintegration \
  -n "${WANAKU_NAMESPACE}" 2>/dev/null || echo "(none, or the CRDs were deleted)"
echo "--- Wanaku CRDs ---"
oc get crd | grep wanaku || echo "(no Wanaku CRDs)"
echo "--- Helm releases ---"
helm list --namespace "${WANAKU_NAMESPACE}" 2>/dev/null || echo "(namespace may have been deleted)"
```

**Expected result:**

- The commands exit with code 0 (inferred).
