# Test Plan: Service Account Setup for Test Plans

Running operator test plans with your personal (admin) credentials is dangerous. This guide creates a dedicated service account with only the permissions the test plans require.

The service account lives in a shared `wanaku-test-infra` namespace, separate from the per-run namespace where each test plan executes. This keeps the test infrastructure stable while allowing multiple plans to reuse the same cluster without sharing a test namespace.

No permission change is needed for that split: the service account already has cluster-scoped access to create and delete per-run namespaces/projects, plus the namespaced permissions the plans use inside those namespaces.

## Prerequisites

- `oc` CLI logged in as a cluster admin (one-time setup)

## Phase 1: Quick Start

### Step 1.1: Create service account credentials

From the repository root:

```bash run
# Create the service account and RBAC
./plans/setup/create-service-account.sh

# Save the token for CI or local use
export OPENSHIFT_SA_TOKEN=$(oc create token wanaku-test-runner -n wanaku-test-infra --duration=8760h)
```

**Expected result:**

- The setup script creates the service account and RBAC and the token command exits with code 0 (inferred).

### Step 1.2: Log in as the service account

Then log in as the service account instead of your admin user:

```bash run
oc login <cluster-api-url> --token=${OPENSHIFT_SA_TOKEN}
oc whoami
# Expected: system:serviceaccount:wanaku-test-infra:wanaku-test-runner
```

**Expected result:**

- The output includes `system:serviceaccount:wanaku-test-infra:wanaku-test-runner`.

## What the Service Account Can Do

| Allowed | Not Allowed |
|---|---|
| Create/delete test namespaces | Manage nodes or machines |
| Install/uninstall the operator Helm chart | Manage PVs or StorageClasses |
| CRUD on deployments, services, ingresses, PVCs | Manage SecurityContextConstraints |
| CRUD on Wanaku custom resources (CRDs) | Manage OLM operators |
| Read pod logs, exec into pods | Impersonate other users |
| Manage ClusterRoles/Bindings (for Helm) | Access cluster infrastructure |

The runner needs the permissions delegated by the operator chart, including `WanakuIntegration`,
`WanakuSemanticRouter` and their status/finalizer subresources. Otherwise Helm cannot create the
operator's ClusterRoles. After updating this setup script, an administrator must rerun
`./plans/setup/create-service-account.sh` to apply the updated role to an existing test service account. Existing tokens continue to use that role; do not run the
bootstrap from the service-account test session.

## Token Management

The `create-service-account.sh` script prints a token valid for 1 year. For CI systems, store it as a secret (e.g., GitHub Actions `OPENSHIFT_SA_TOKEN`). Reuse the same service-account namespace across runs unless you intentionally want to recreate the infrastructure.

## Phase 2: Token Rotation

### Step 2.1: Rotate the token

To rotate the token:

```bash run
oc create token wanaku-test-runner -n wanaku-test-infra --duration=8760h
```

**Expected result:**

- The token command exits with code 0 (inferred).

## Phase 3: Teardown

### Step 3.1: Remove the service account

To remove the service account and all RBAC resources:

```bash run
./plans/setup/teardown-service-account.sh
```

**Expected result:**

- The teardown script exits with code 0 (inferred).
