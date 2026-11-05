# Procedure: OpenShift Login

Reusable steps for logging in to an OpenShift cluster using a service account token.

## Prerequisites

- `oc` CLI installed
- `OPENSHIFT_API_URL` environment variable set (e.g. `https://api.cluster.example.com:6443`)
- `OPENSHIFT_SA_TOKEN` environment variable set (service account token)
- Optional: `OPENSHIFT_APPS_DOMAIN` environment variable (e.g. `apps.cluster.example.com`). The default comes from the console URL.

## Phase 1: OpenShift Login

### Step 1: Log in to OpenShift

```bash run
oc login "${OPENSHIFT_API_URL}" --token="${OPENSHIFT_SA_TOKEN}" --insecure-skip-tls-verify=true
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 2: Verify login

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash run
OC_USER=$(oc whoami 2>/dev/null)
if [ -z "${OC_USER}" ]; then
  echo "FAIL: not logged in to ${OPENSHIFT_API_URL}"
  return 1
fi
echo "PASS: logged in as ${OC_USER}"
# Expected: the service account name (e.g. system:serviceaccount:<namespace>:<sa-name>)

oc whoami --show-server
# Expected: prints the cluster API URL

oc version
# Expected: prints client and server version info

# The operator plans use OpenShift Routes
oc api-resources --api-group=route.openshift.io -o name | grep -q "^routes"
echo "route-api-available=$?"
# Expected: route-api-available=0

# The operator plans expose the router through Ingresses with hosts on the cluster apps domain.
# Set OPENSHIFT_APPS_DOMAIN to override the value from the console URL.
if [ -z "${OPENSHIFT_APPS_DOMAIN:-}" ]; then
  CONSOLE_HOST=$(oc whoami --show-console 2>/dev/null | sed -E 's#^https?://##; s#/.*$##')
  case "${CONSOLE_HOST}" in
    console-openshift-console.*) OPENSHIFT_APPS_DOMAIN="${CONSOLE_HOST#console-openshift-console.}" ;;
  esac
fi
if [ -z "${OPENSHIFT_APPS_DOMAIN:-}" ]; then
  echo "FAIL: cannot find the apps domain. Set OPENSHIFT_APPS_DOMAIN."
  return 1
fi
export OPENSHIFT_APPS_DOMAIN
echo "PASS: apps domain is ${OPENSHIFT_APPS_DOMAIN}"
```

**Expected result:**

- The output includes `PASS: logged in as <OC_USER>`.
- The output includes `route-api-available=0`.
- The output includes `PASS: apps domain is <OPENSHIFT_APPS_DOMAIN>`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.
- `oc whoami` prints the service account name.
- `oc whoami --show-server` prints the cluster API URL.
- `oc version` prints client and server version info.
