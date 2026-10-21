# Test Plan: CLI Auth Commands (Keycloak Administration, Auth Login and the oauth2-proxy Boundary)

## Overview

This test plan verifies the authentication commands of the two CLIs from this repository:

- `wanaku-keycloak-admin users` and `wanaku-keycloak-admin credentials`: one round trip for a Keycloak user (add, list,
  set-password, remove) and one for a service client (add, show, regenerate, remove). The unit tests in `wanaku-barn`
  (`UsersCommandsTest`, `CredentialsCommandsTest` and `RealmCommandsTest`) cover the CRUD logic and the error cases.
  This plan keeps only the end-to-end smoke tests against a real Keycloak.
- `wanaku auth login`, `auth status`, `auth token` and the `--token` / `--no-auth` options: the login modes that the
  CLI supports now, and the use of the resulting token against **Wanaku Server behind oauth2-proxy**.

Wanaku Server has no built-in authentication. Two oauth2-proxy instances protect it: one in front of the management
API (port `8080`) and one in front of the MCP listener (port `8081`). The proxies validate Keycloak tokens. The Java
router and its OIDC proxy login are gone. `wanaku auth login` now has these modes (see
`apps/wanaku-cli/.../commands/auth/AuthLogin.java`):

| Mode | Command | Token endpoint |
|------|---------|----------------|
| Keycloak base URL and realm | `--auth-server <keycloak> --realm wanaku` | `<keycloak>/realms/wanaku` (OIDC discovery) |
| Full issuer URL | `--auth-server <keycloak>/realms/wanaku` | The issuer itself. The realm is not stored. |
| Issuer discovered through Wanaku Server | `--auth-server <mcp-origin> --realm ""` | Wanaku Server serves `/.well-known/openid-configuration` when `WANAKU_AUTH_ISSUER` is set. The CLI reads the issuer from it and then uses the Keycloak token endpoint. |
| Stored token | `--api-token <token>` | None. The CLI stores the token as it is. |

The plan also verifies the RFC 9728 protected-resource metadata
(`/.well-known/oauth-protected-resource/<namespace>/mcp`) that Wanaku Server serves when `WANAKU_AUTH_ISSUER` is set.

**Components under test:**

| Component | Needed | Role in this plan |
|-----------|--------|-------------------|
| Keycloak (realm `wanaku`) | Yes | Users, clients, tokens |
| Wanaku Server behind oauth2-proxy | Yes (Phase 1, and Phases 6 onwards) | Accepts or rejects the tokens |
| Wanaku Barn | No | Barn has no authentication |

Phases 2 to 5 need Keycloak only. Every step is fully automatable.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `java` | 21+ | `java -version` |
| `jq` | 1.6+ | `jq --version` |
| `curl` | any | `curl --version` |
| `docker compose` or `podman compose` | v2 | `docker compose version` (environment option A only) |
| `oc` | 4.x | `oc version --client` (environment option B only) |
| `openssl` | any | `openssl version` |

### Environment variables

The executable block is in Step S.1 below.

Phase 1 sets these variables:

| Variable | Description |
|----------|-------------|
| `KEYCLOAK_URL` | Keycloak base URL, without the realm path |
| `WANAKU_CLIENT_SECRET` | Secret of the confidential `wanaku-mcp-router` client. `wanaku auth login` reads it from the environment. |
| `WANAKU_SERVER_URL` | Management API **behind the management proxy**. Use it as `--host`. |
| `WANAKU_MCP_BASE_URL` | MCP listener **behind the MCP proxy**. The MCP endpoint is `${WANAKU_MCP_BASE_URL}/<namespace>/mcp`. |
| `EXPECT_PUBLIC_CLIENT_LOGIN` | `pass` when the realm enables direct access grants for the public `mcp-client` client, `fail` when it does not |

### CLI invocation

Use the jars from a local build (`mvn -DskipTests package`, see [common/start-barn.md](common/start-barn.md) step 1),
or installed binaries. Define shell functions. Do not put the full command in one variable, because zsh does not split
it into words.

The executable block is in Step S.2 below.

The `users`, `credentials` and `realm` commands are in the standalone `wanaku-keycloak-admin` binary. The `auth`
commands are in the `wanaku` CLI. The Keycloak realm defaults to `wanaku` for both CLIs.

---

## Phase S: Setup

### Step S.1: Check prerequisites

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
# Checkout of wanaku-ai/wanaku (environment option A only)
export WANAKU_SERVER_REPO_ROOT="${WANAKU_SERVER_REPO_ROOT:-../wanaku}"
export KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
# KEYCLOAK_ADMIN_PASS has no default. Option A reads it from the environment.
# Option B generates it in common/keycloak-setup.md when it is not set.
export WANAKU_REALM="${WANAKU_REALM:-wanaku}"
export WANAKU_TEST_USER="${WANAKU_TEST_USER:-alice}"
export WANAKU_TEST_PASS="${WANAKU_TEST_PASS:-$(openssl rand -hex 16)}"
export WANAKU_TEST_EMAIL="${WANAKU_TEST_EMAIL:-alice@example.com}"
export TEST_USERNAME="${TEST_USERNAME:-testuser-811}"
export TEST_PASSWORD="${TEST_PASSWORD:-$(openssl rand -hex 16)}"
export TEST_NEW_PASSWORD="${TEST_NEW_PASSWORD:-$(openssl rand -hex 16)}"
export TEST_EMAIL="${TEST_EMAIL:-testuser811@example.com}"
export TEST_CLIENT_ID="${TEST_CLIENT_ID:-test-service-811}"
export WANAKU_MCP_NAMESPACE="${WANAKU_MCP_NAMESPACE:-default}"
# Isolate credentials per test run to avoid contention (see #1697)
export WANAKU_CREDENTIALS="${WANAKU_CREDENTIALS:-/tmp/wanaku-creds-auth-$$}"
export CREDENTIALS_FILE="${WANAKU_CREDENTIALS}"
```

**Expected result:**

- The commands complete without errors (inferred).

### Step S.2: Check prerequisites

```bash run
CLI_JAR="${CLI_JAR:-${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar}"
KEYCLOAK_ADMIN_JAR="${KEYCLOAK_ADMIN_JAR:-${WANAKU_REPO_ROOT}/apps/wanaku-keycloak-admin/target/quarkus-app/quarkus-run.jar}"
wanaku() { java -jar "${CLI_JAR}" "$@"; }
wanaku-keycloak-admin() { java -jar "${KEYCLOAK_ADMIN_JAR}" "$@"; }

# Admin command with the Keycloak connection options: kc <group> <command> [options]
kc() {
  KC_GROUP="$1"; KC_CMD="$2"; shift 2
  wanaku-keycloak-admin "${KC_GROUP}" "${KC_CMD}" \
    --keycloak-url "${KEYCLOAK_URL}" \
    --admin-username "${KEYCLOAK_ADMIN_USER}" \
    --admin-password "${KEYCLOAK_ADMIN_PASS}" "$@"
}
```

**Expected result:**

- The commands complete without errors (inferred).

## Phase 0: Prerequisites

### Test 0.1: Verify tools and jars

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
for CMD in java jq curl openssl; do
  if command -v "${CMD}" > /dev/null 2>&1; then
    echo "PASS: ${CMD} found"
  else
    echo "FAIL: ${CMD} is not installed"
    FAIL=1
  fi
done
for FILE in "${CLI_JAR}" "${KEYCLOAK_ADMIN_JAR}"; do
  if [ -f "${FILE}" ]; then
    echo "PASS: ${FILE} exists"
  else
    echo "FAIL: ${FILE} not found"
    FAIL=1
  fi
done
[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || { echo "FAIL: prerequisites not met"; exit 1; }
```

**Expected result:**

- The output includes `PASS: java found`.
- The output includes `PASS: jq found`.
- The output includes `PASS: curl found`.
- The output includes `PASS: openssl found`.
- The output includes `PASS: <CLI_JAR> exists`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: <KEYCLOAK_ADMIN_JAR> exists`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

## Phase 1: Environment Setup

Select one option. Both options give a Keycloak realm `wanaku` and a Wanaku Server that only the two oauth2-proxy
instances expose.

### Step 1.0: Select and prepare one environment

#### Option A: Local Docker Compose (no cluster)

Use steps 1 to 4 of the
[authenticated local deployment guide](https://github.com/wanaku-ai/wanaku/blob/main/deploy/auth/README.md)
(`deploy/auth/README.md` in the `wanaku-ai/wanaku` repository). Do not do its step 3 (create a user). Step 1.3 of this
plan creates the test user.

Export the Keycloak admin credentials and the `wanaku-mcp-router` client secret of your Compose deployment before
you run the plan. The plan does not contain default values for them. Use the values that the Compose project uses
(`KC_BOOTSTRAP_ADMIN_USERNAME`, `KC_BOOTSTRAP_ADMIN_PASSWORD` and `WANAKU_MCPROUTER_SECRET`). Do not commit them.
Then set the variables:

```bash
for VAR_NAME in KEYCLOAK_ADMIN_USER KEYCLOAK_ADMIN_PASS WANAKU_MCPROUTER_SECRET; do
  eval "VAL=\${${VAR_NAME}}"
  [ -n "${VAL}" ] && echo "PASS: ${VAR_NAME} is set" || { echo "FAIL: ${VAR_NAME} is not set"; exit 1; }
done
export KEYCLOAK_URL="http://localhost:8543"
export WANAKU_CLIENT_SECRET="${WANAKU_MCPROUTER_SECRET}"
export WANAKU_SERVER_URL="http://localhost:4180"   # management proxy
export WANAKU_MCP_BASE_URL="http://localhost:4181" # MCP proxy
# The bundled wanaku-realm.json enables direct access grants for mcp-client
export EXPECT_PUBLIC_CLIENT_LOGIN="pass"
```

The Compose project sets `WANAKU_AUTH_ISSUER=http://localhost:8543/realms/wanaku` on the server.

#### Option B: OpenShift with the operator

1. Follow [common/openshift-login.md](common/openshift-login.md) and [common/namespace-setup.md](common/namespace-setup.md).
2. Follow [common/operator-deployment.md](common/operator-deployment.md).
3. Follow [common/keycloak-setup.md](common/keycloak-setup.md) steps 1 to 9. After completion, `KEYCLOAK_URL`,
   `WANAKU_CLIENT_SECRET`, `WANAKU_AUTH_SECRET_NAME`, `WANAKU_AUTH_ISSUER_URL` and `WANAKU_AUTH_INTERNAL_ISSUER_URL`
   must be set. Do not do step 10 (create a user). Step 1.3 of this plan creates the test user.
4. Create a `WanakuRouter` with `spec.auth.enabled: true`:

Run this block as a separate `bash` script, not in the shared session.

```bash
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-auth}"

cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: wanaku.ai/v1alpha1
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  auth:
    enabled: true
    issuerUrl: "${WANAKU_AUTH_ISSUER_URL}"
    internalIssuerUrl: "${WANAKU_AUTH_INTERNAL_ISSUER_URL}"
    secretName: "${WANAKU_AUTH_SECRET_NAME}"
EOF

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=300s -n "${WANAKU_NAMESPACE}" \
  && echo "PASS: WanakuRouter is Ready" || { echo "FAIL: WanakuRouter is not Ready"; exit 1; }

AUTH_COND=$(oc get "wanakurouter/${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="AuthConfigured")].reason}')
[ "${AUTH_COND}" = "Configured" ] && echo "PASS: AuthConfigured reason is Configured" \
  || echo "FAIL: AuthConfigured reason is '${AUTH_COND}'"

# The operator creates the Route <router> for the management API and <router>-mcp for MCP
export WANAKU_SERVER_URL="http://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_MCP_BASE_URL="http://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
# deploy/auth/wanaku-config.json of this repository disables direct access grants for mcp-client
export EXPECT_PUBLIC_CLIENT_LOGIN="fail"
```

In the operator deployment, the MCP proxy listens on port `4180` and the management proxy on port `4181`. The Docker
Compose setup uses the opposite assignment. The plan uses only the URLs, so this difference has no effect.

**Expected result:**

- For Option B (OpenShift), the output includes `PASS: WanakuRouter is Ready`; Option A uses the local Compose deployment instead.
- For Option B (OpenShift), the output includes `PASS: AuthConfigured reason is Configured`; Option A uses the local Compose deployment instead.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 1.1: Verify that the variables are set and Keycloak responds

Run this block as a separate `bash` script, not in the shared session.

```bash
for VAR_NAME in KEYCLOAK_URL KEYCLOAK_ADMIN_USER KEYCLOAK_ADMIN_PASS WANAKU_CLIENT_SECRET WANAKU_SERVER_URL WANAKU_MCP_BASE_URL EXPECT_PUBLIC_CLIENT_LOGIN; do
  eval "VAL=\${${VAR_NAME}}"
  [ -n "${VAL}" ] && echo "PASS: ${VAR_NAME} is set" || { echo "FAIL: ${VAR_NAME} is not set"; exit 1; }
done

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${KEYCLOAK_URL}/realms/${WANAKU_REALM}" 2>/dev/null || echo "000")
[ "${HTTP_CODE}" = "200" ] && echo "PASS: realm ${WANAKU_REALM} is accessible" \
  || { echo "FAIL: realm ${WANAKU_REALM} not accessible (HTTP ${HTTP_CODE})"; exit 1; }
```

**Expected result:**

- The output includes `PASS: KEYCLOAK_URL is set`.
- The output includes `PASS: KEYCLOAK_ADMIN_USER is set`.
- The output includes `PASS: KEYCLOAK_ADMIN_PASS is set`.
- The output includes `PASS: WANAKU_CLIENT_SECRET is set`.
- The output includes `PASS: WANAKU_SERVER_URL is set`.
- The output includes `PASS: WANAKU_MCP_BASE_URL is set`.
- The output includes `PASS: EXPECT_PUBLIC_CLIENT_LOGIN is set`.
- The output includes `PASS: realm <WANAKU_REALM> is accessible`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 1.2: The proxies protect Wanaku Server

This test checks HTTP status codes, so it uses `curl`. The health endpoints do not need a token. The API and MCP
endpoints return `401` without a token. They do not redirect to a login page.

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health")
[ "${CODE}" = "200" ] && echo "PASS: /health is public (200)" || echo "FAIL: /health returned ${CODE}"

CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/api/v1/tools")
[ "${CODE}" = "401" ] && echo "PASS: /api/v1/tools without token returns 401" || echo "FAIL: /api/v1/tools returned ${CODE}"

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_MCP_BASE_URL}/${WANAKU_MCP_NAMESPACE}/mcp" \
  -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
[ "${CODE}" = "401" ] && echo "PASS: MCP endpoint without token returns 401" || echo "FAIL: MCP endpoint returned ${CODE}"
```

**Expected result:**

- The output includes `PASS: /health is public (200)`.
- The output includes `PASS: /api/v1/tools without token returns 401`.
- The output includes `PASS: MCP endpoint without token returns 401`.
- The output contains no line starting with `FAIL:`.

### Test 1.3: Create the auth login test user

Run this block as a separate `bash` script, not in the shared session.

```bash
kc users add --username "${WANAKU_TEST_USER}" --password "${WANAKU_TEST_PASS}" --email "${WANAKU_TEST_EMAIL}" 2>&1
# The password is generated per run. Set it again in case the user exists from an earlier run.
kc users set-password --username "${WANAKU_TEST_USER}" --password "${WANAKU_TEST_PASS}" 2>&1
kc users list --plain 2>&1 | grep -q "^${WANAKU_TEST_USER}[[:space:]]" \
  && echo "PASS: user '${WANAKU_TEST_USER}' exists" \
  || { echo "FAIL: user '${WANAKU_TEST_USER}' not found"; exit 1; }
```

If the user exists from an earlier run, the `add` command fails with `User exists with same email`. The `set-password`
command then sets the password of this run, and the `list` check still passes.

**Expected result:**

- The output includes `PASS: user '<WANAKU_TEST_USER>' exists`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

## Phase 2: Users Round Trip

The unit tests in `wanaku-barn` (`UsersCommandsTest`) cover the error cases of the `users` commands. This phase is a
smoke test against a real Keycloak.

### Test 2.1: Add a user and find it in the list

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(kc users add --username "${TEST_USERNAME}" --password "${TEST_PASSWORD}" --email "${TEST_EMAIL}" 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "User '${TEST_USERNAME}' created successfully"; then
  echo "PASS: user '${TEST_USERNAME}' created"
else
  echo "FAIL: users add failed (exit code ${EXIT_CODE})"; echo "${OUTPUT}"; exit 1
fi

kc users list --plain 2>&1 | grep -q "^${TEST_USERNAME}[[:space:]]${TEST_EMAIL}" \
  && echo "PASS: user '${TEST_USERNAME}' found with its email" \
  || { echo "FAIL: user '${TEST_USERNAME}' not found after creation"; exit 1; }
```

**Expected output (plain list):** a tab-separated table with the header `username	email	enabled`.

**Expected result:**

- The output includes `PASS: user '<TEST_USERNAME>' created`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: user '<TEST_USERNAME>' found with its email`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 2.2: Set the password of the user

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(kc users set-password --username "${TEST_USERNAME}" --password "${TEST_NEW_PASSWORD}" 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Password updated for user '${TEST_USERNAME}'"; then
  echo "PASS: password updated"
else
  echo "FAIL: set-password failed (exit code ${EXIT_CODE})"; echo "${OUTPUT}"; exit 1
fi
```

**Expected result:**

- The output includes `PASS: password updated`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 2.3: Remove the user and check that it is not in the list

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(kc users remove --username "${TEST_USERNAME}" 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "deleted successfully"; then
  echo "PASS: user '${TEST_USERNAME}' removed"
else
  echo "FAIL: users remove failed (exit code ${EXIT_CODE})"; echo "${OUTPUT}"; exit 1
fi

kc users list --plain 2>&1 | grep -q "^${TEST_USERNAME}[[:space:]]" \
  && { echo "FAIL: removed user still present"; exit 1; } \
  || echo "PASS: removed user no longer in list"
```

**Expected result:**

- The output includes `PASS: user '<TEST_USERNAME>' removed`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: removed user no longer in list`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

## Phase 3: Service Client Round Trip

The unit tests in `wanaku-barn` (`CredentialsCommandsTest`) cover the error cases and the filter for internal Keycloak
clients. This phase is a smoke test against a real Keycloak.

### Test 3.1: Add a service client

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(kc credentials add --client-id "${TEST_CLIENT_ID}" --description "Test service client for issue 811" 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Client '${TEST_CLIENT_ID}' created successfully"; then
  echo "PASS: service client '${TEST_CLIENT_ID}' created"
else
  echo "FAIL: credentials add failed (exit code ${EXIT_CODE})"; echo "${OUTPUT}"; exit 1
fi
```

**Expected result:**

- The output includes `PASS: service client '<TEST_CLIENT_ID>' created`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.2: With --show-secret --plain, stdout contains only the secret

```bash run
SECRET=$(kc credentials show --client-id "${TEST_CLIENT_ID}" --show-secret --plain 2>/dev/null)
if [ -n "${SECRET}" ] && ! echo "${SECRET}" | grep -q "[[:space:]]"; then
  echo "PASS: secret retrieved (length: ${#SECRET})"
else
  echo "FAIL: stdout is empty or contains more than the secret: '${SECRET}'"
fi
```

**Expected result:**

- The output includes `PASS: secret retrieved (length: ${#SECRET})`.
- The output contains no line starting with `FAIL:`.

### Test 3.3: Regenerate gives a different secret

Run this block as a separate `bash` script, not in the shared session.

```bash
ORIGINAL_SECRET=$(kc credentials show --client-id "${TEST_CLIENT_ID}" --show-secret --plain 2>/dev/null)
OUTPUT=$(kc credentials regenerate --client-id "${TEST_CLIENT_ID}" --show-secret 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "^New Secret: ."; then
  echo "PASS: secret regenerated"
else
  echo "FAIL: credentials regenerate failed (exit code ${EXIT_CODE})"; echo "${OUTPUT}"; exit 1
fi

NEW_SECRET=$(kc credentials show --client-id "${TEST_CLIENT_ID}" --show-secret --plain 2>/dev/null)
if [ -z "${ORIGINAL_SECRET}" ] || [ -z "${NEW_SECRET}" ]; then
  echo "FAIL: could not compare secrets"; exit 1
elif [ "${ORIGINAL_SECRET}" != "${NEW_SECRET}" ]; then
  echo "PASS: regenerated secret differs from original"
else
  echo "FAIL: regenerated secret is the same as the original"; exit 1
fi
```

Do not regenerate the `wanaku-mcp-router` secret in this plan. The oauth2-proxy instances use it. A new value breaks
the browser login of the proxies until they get the new secret.

**Expected result:**

- The output includes `PASS: secret regenerated`.
- The output includes `PASS: regenerated secret differs from original`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.4: Remove the service client and check that it is not in the list

```bash run
OUTPUT=$(kc credentials remove --client-id "${TEST_CLIENT_ID}" 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Client '${TEST_CLIENT_ID}' deleted successfully"; then
  echo "PASS: client '${TEST_CLIENT_ID}' removed"
else
  echo "FAIL: credentials remove failed (exit code ${EXIT_CODE})"; echo "${OUTPUT}"
fi

kc credentials list --plain 2>&1 | grep -q "^${TEST_CLIENT_ID}[[:space:]]" \
  && echo "FAIL: removed client still present" || echo "PASS: removed client no longer in list"
```

**Expected result:**

- The output includes `PASS: client '<TEST_CLIENT_ID>' removed`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: removed client no longer in list`.
- The output contains no line starting with `FAIL:`.

## Phase 4: Negative Test — Admin Commands

### Test 4.1: Wrong admin credentials

```bash run
OUTPUT=$(wanaku-keycloak-admin users list --keycloak-url "${KEYCLOAK_URL}" \
  --admin-username "wrong-admin" --admin-password "wrong-$(openssl rand -hex 8)" --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "Failed to obtain admin token"; then
  echo "PASS: wrong admin credentials rejected (exit code ${EXIT_CODE})"
else
  echo "FAIL: wrong admin credentials should have been rejected"; echo "${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: wrong admin credentials rejected`.
- The output contains no line starting with `FAIL:`.

## Phase 5: Auth Login — Keycloak Base URL and Realm

These tests authenticate directly against Keycloak. Wanaku Server is not used. The CLI uses the confidential
`wanaku-mcp-router` client by default and reads its secret from `WANAKU_CLIENT_SECRET`.

### Test 5.1: Back up and clear the credentials file

```bash run
[ -f "${CREDENTIALS_FILE}" ] && cp "${CREDENTIALS_FILE}" "${CREDENTIALS_FILE}.bak-auth-test"
rm -f "${CREDENTIALS_FILE}"
echo "PASS: credentials file cleared"
```

**Expected result:**

- The output includes `PASS: credentials file cleared`.

### Test 5.2: Login with --realm succeeds

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(echo "${WANAKU_TEST_PASS}" | wanaku auth login \
  --auth-server "${KEYCLOAK_URL}" \
  --realm "${WANAKU_REALM}" \
  --username "${WANAKU_TEST_USER}" \
  --password \
  --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Successfully authenticated and stored credentials"; then
  echo "PASS: auth login with --realm '${WANAKU_REALM}' succeeded"
else
  echo "FAIL: auth login with --realm failed (exit code ${EXIT_CODE})"; echo "${OUTPUT}"; exit 1
fi
```

**Expected result:**

- The output includes `PASS: auth login with --realm '<WANAKU_REALM>' succeeded`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.3: The credentials file has the expected entries

```bash run
for ENTRY in "auth.realm=${WANAKU_REALM}" "client.id=wanaku-mcp-router" "auth.mode=token"; do
  grep -q "^${ENTRY}$" "${CREDENTIALS_FILE}" && echo "PASS: ${ENTRY}" || echo "FAIL: ${ENTRY} not stored"
done
for KEY in api.token refresh.token token.expiry auth.server.url; do
  VALUE=$(grep "^${KEY}=" "${CREDENTIALS_FILE}" | sed "s/^${KEY}=//")
  [ -n "${VALUE}" ] && echo "PASS: ${KEY} stored" || echo "FAIL: ${KEY} missing"
done
```

**Expected result:**

- The output includes `PASS: auth.realm=<WANAKU_REALM>`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output includes `PASS: client.id=wanaku-mcp-router`.
- The output includes `PASS: auth.mode=token`.
- The output includes `PASS: api.token stored`.
- The output includes `PASS: refresh.token stored`.
- The output includes `PASS: token.expiry stored`.
- The output includes `PASS: auth.server.url stored`.
- The output contains no line starting with `FAIL:`.

### Test 5.4: auth status reports the session

```bash run
OUTPUT=$(wanaku auth status --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] \
  && echo "${OUTPUT}" | grep -q "Realm: ${WANAKU_REALM}" \
  && echo "${OUTPUT}" | grep -q "Client ID: wanaku-mcp-router (confidential)" \
  && echo "${OUTPUT}" | grep -q "Has Credentials: true"; then
  echo "PASS: auth status reports the stored session"
else
  echo "FAIL: unexpected auth status output (exit code ${EXIT_CODE})"; echo "${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: auth status reports the stored session`.
- The output contains no line starting with `FAIL:`.

### Test 5.5: auth token --get --unmask --plain writes only the token

Run this block as a separate `bash` script, not in the shared session.

```bash
TOKEN=$(wanaku auth token --get --unmask --plain 2>/dev/null)
if [ -n "${TOKEN}" ] && ! echo "${TOKEN}" | grep -q "[[:space:]]" && [ "$(echo "${TOKEN}" | tr -cd '.' | wc -c)" -eq 2 ]; then
  echo "PASS: stdout contains one JWT (length: ${#TOKEN})"
else
  echo "FAIL: captured token is empty or contains more than the token: '${TOKEN}'"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: stdout contains one JWT (length: ${#TOKEN})`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

## Phase 6: Token Accepted by the Proxies

oauth2-proxy accepts tokens whose audience contains `wanaku-mcp-router` (the proxy client ID) or one of the extra
audiences `mcp-client` and `wanaku-mcp-client`. The `wanaku-mcp-router` client has an audience mapper, so the token
from Phase 5 is accepted.

### Test 6.1: The stored token works with the management API

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | head -1 | grep -q "^name"; then
  echo "PASS: stored token accepted by the management proxy"
else
  echo "FAIL: management proxy rejected the stored token (exit code ${EXIT_CODE})"; echo "${OUTPUT}"; exit 1
fi
```

**Expected result:**

- The output includes `PASS: stored token accepted by the management proxy`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 6.2: The token works with the MCP endpoint

The `wanaku mcp` commands do not read the stored credentials. Pass the token with `--token`.

```bash run
TOKEN=$(wanaku auth token --get --unmask --plain 2>/dev/null)
wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/${WANAKU_MCP_NAMESPACE}/mcp" --token "${TOKEN}" > /dev/null 2>&1
EXIT_CODE=$?
[ "${EXIT_CODE}" -eq 0 ] && echo "PASS: token accepted by the MCP proxy" \
  || echo "FAIL: MCP proxy rejected the token (exit code ${EXIT_CODE})"

OUTPUT=$(wanaku mcp tool list --uri "${WANAKU_MCP_BASE_URL}/${WANAKU_MCP_NAMESPACE}/mcp" 2>&1)
echo "${OUTPUT}" | grep -q "401" && echo "PASS: MCP call without --token is rejected (401)" \
  || echo "FAIL: MCP call without --token was not rejected: ${OUTPUT}"
```

A new registry has no tools, so an empty list is a valid result.

**Expected result:**

- The output includes `PASS: token accepted by the MCP proxy`.
- The output includes `PASS: MCP call without --token is rejected (401)`.
- The output contains no line starting with `FAIL:`.

### Test 6.3: The raw token works with curl

This test checks the HTTP status code that the proxy returns for a bearer token.

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer ${TOKEN}" "${WANAKU_SERVER_URL}/api/v1/tools")
[ "${CODE}" = "200" ] && echo "PASS: raw token accepted (HTTP 200)" || echo "FAIL: raw token rejected (HTTP ${CODE})"
```

**Expected result:**

- The output includes `PASS: raw token accepted (HTTP 200)`.
- The output contains no line starting with `FAIL:`.

### Test 6.4: A token of a client that the proxy does not trust is rejected with guidance

`admin-cli` tokens do not carry an accepted audience.

```bash run
ORIGINAL_CREDENTIALS="${WANAKU_CREDENTIALS}"
export WANAKU_CREDENTIALS="${ORIGINAL_CREDENTIALS}-admin-cli"

echo "${WANAKU_TEST_PASS}" | wanaku auth login --auth-server "${KEYCLOAK_URL}" --realm "${WANAKU_REALM}" \
  --client-id admin-cli --client-secret "" --username "${WANAKU_TEST_USER}" --password --plain > /dev/null 2>&1
OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --plain 2>&1)
EXIT_CODE=$?

rm -f "${WANAKU_CREDENTIALS}"
export WANAKU_CREDENTIALS="${ORIGINAL_CREDENTIALS}"

if [ "${EXIT_CODE}" -ne 0 ] \
  && echo "${OUTPUT}" | grep -q "Authentication rejected by the server (status: 401" \
  && echo "${OUTPUT}" | grep -q "wanaku-mcp-router"; then
  echo "PASS: admin-cli token rejected; the CLI names the correct client"
else
  echo "FAIL: unexpected result for an admin-cli token (exit code ${EXIT_CODE})"; echo "${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: admin-cli token rejected; the CLI names the correct client`.
- The output contains no line starting with `FAIL:`.

### Test 6.5: Login with the public mcp-client client

`--client-secret ""` overrides `WANAKU_CLIENT_SECRET`, so the CLI sends only the client ID. The realm of
option A enables direct access grants for `mcp-client`. The realm of option B (`deploy/auth/wanaku-config.json`)
does not.

```bash run
ORIGINAL_CREDENTIALS="${WANAKU_CREDENTIALS}"
export WANAKU_CREDENTIALS="${ORIGINAL_CREDENTIALS}-mcp-client"
OUTPUT=$(echo "${WANAKU_TEST_PASS}" | wanaku auth login --auth-server "${KEYCLOAK_URL}" --realm "${WANAKU_REALM}" \
  --client-id mcp-client --client-secret "" --username "${WANAKU_TEST_USER}" --password --plain 2>&1)
EXIT_CODE=$?

if [ "${EXPECT_PUBLIC_CLIENT_LOGIN}" = "pass" ]; then
  if [ "${EXIT_CODE}" -eq 0 ] && wanaku tools list --host "${WANAKU_SERVER_URL}" --plain > /dev/null 2>&1; then
    echo "PASS: mcp-client login succeeded and its token is accepted"
  else
    echo "FAIL: mcp-client login or API call failed (exit code ${EXIT_CODE})"; echo "${OUTPUT}"
  fi
else
  if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "Client not allowed for direct access grants"; then
    echo "PASS: mcp-client login rejected by the realm (expected for this realm)"
  else
    echo "FAIL: unexpected mcp-client login result (exit code ${EXIT_CODE})"; echo "${OUTPUT}"
  fi
fi
rm -f "${WANAKU_CREDENTIALS}"
export WANAKU_CREDENTIALS="${ORIGINAL_CREDENTIALS}"
```

**Expected result:**

- The output includes `PASS: mcp-client login succeeded and its token is accepted`.
- The output includes `PASS: mcp-client login rejected by the realm (expected for this realm)`.
- The output contains no line starting with `FAIL:`.

## Phase 7: Auth Login — Full Issuer URL and Realm Persistence

### Test 7.1: Login with a full issuer URL does not store a realm

```bash run
rm -f "${CREDENTIALS_FILE}"
OUTPUT=$(echo "${WANAKU_TEST_PASS}" | wanaku auth login \
  --auth-server "${KEYCLOAK_URL}/realms/${WANAKU_REALM}" \
  --username "${WANAKU_TEST_USER}" --password --plain 2>&1)
EXIT_CODE=$?
STORED_REALM=$(grep "^auth.realm=" "${CREDENTIALS_FILE}" 2>/dev/null || true)
STORED_TOKEN=$(grep "^api.token=" "${CREDENTIALS_FILE}" 2>/dev/null | sed 's/^api.token=//')
if [ "${EXIT_CODE}" -eq 0 ] && [ -z "${STORED_REALM}" ] && [ -n "${STORED_TOKEN}" ]; then
  echo "PASS: token stored and no realm entry"
else
  echo "FAIL: exit ${EXIT_CODE}, realm '${STORED_REALM}', token length ${#STORED_TOKEN}"; echo "${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: token stored and no realm entry`.
- The output contains no line starting with `FAIL:`.

### Test 7.2: Login with --realm stores the realm again

```bash run
echo "${WANAKU_TEST_PASS}" | wanaku auth login --auth-server "${KEYCLOAK_URL}" --realm "${WANAKU_REALM}" \
  --username "${WANAKU_TEST_USER}" --password --plain > /dev/null 2>&1
grep -q "^auth.realm=${WANAKU_REALM}$" "${CREDENTIALS_FILE}" \
  && echo "PASS: realm stored after login with --realm" || echo "FAIL: realm not stored"
```

**Expected result:**

- The output includes `PASS: realm stored after login with --realm`.
- The output contains no line starting with `FAIL:`.

### Test 7.3: A new login with an issuer URL clears the stored realm

```bash run
echo "${WANAKU_TEST_PASS}" | wanaku auth login --auth-server "${KEYCLOAK_URL}/realms/${WANAKU_REALM}" \
  --username "${WANAKU_TEST_USER}" --password --plain > /dev/null 2>&1
grep -q "^auth.realm=" "${CREDENTIALS_FILE}" \
  && echo "FAIL: realm still present after login with an issuer URL" \
  || echo "PASS: stored realm cleared"
```

**Expected result:**

- The output includes `PASS: stored realm cleared`.
- The output contains no line starting with `FAIL:`.

## Phase 8: Issuer Discovery Through Wanaku Server

When `WANAKU_AUTH_ISSUER` is set, Wanaku Server serves OAuth metadata on the MCP listener. The MCP proxy lets
`/.well-known/*`, `/authorize`, `/token` and `/register` through without a token. These tests use `curl` because they
check the HTTP metadata documents.

### Test 8.1: Protected-resource metadata (RFC 9728)

```bash run
META=$(curl -s "${WANAKU_MCP_BASE_URL}/.well-known/oauth-protected-resource/${WANAKU_MCP_NAMESPACE}/mcp")
echo "${META}" | jq -e --arg origin "${WANAKU_MCP_BASE_URL}" \
  '(.authorization_servers | length) == 1 and .authorization_servers[0] == $origin and .bearer_methods_supported == ["header"]' \
  > /dev/null && echo "PASS: metadata names the MCP origin as authorization server" \
  || echo "FAIL: unexpected metadata: ${META}"

META=$(curl -s "${WANAKU_MCP_BASE_URL}/.well-known/oauth-protected-resource/auth-test-ns/mcp")
echo "${META}" | jq -e --arg r "${WANAKU_MCP_BASE_URL}/auth-test-ns/mcp" '.resource == $r' > /dev/null \
  && echo "PASS: resource is the namespace endpoint" || echo "FAIL: unexpected resource: ${META}"
```

**Expected output (namespace `auth-test-ns`):**
`{"resource":"<mcp-origin>/auth-test-ns/mcp","authorization_servers":["<mcp-origin>"],"bearer_methods_supported":["header"]}`.
For the `default` namespace, the server reports the resource `<mcp-origin>/mcp`, not `<mcp-origin>/default/mcp`.

The metadata uses `http://` and the `Host` header of the request. Behind a TLS-terminating Route, the scheme in the
document can differ from the public scheme.

**Expected result:**

- The output includes `PASS: metadata names the MCP origin as authorization server`.
- The output includes `PASS: resource is the namespace endpoint`.
- The output contains no line starting with `FAIL:`.

### Test 8.2: OpenID configuration points to the Keycloak issuer

```bash run
DISCOVERY=$(curl -s "${WANAKU_MCP_BASE_URL}/.well-known/openid-configuration")
echo "${DISCOVERY}" | jq -e --arg iss "${KEYCLOAK_URL}/realms/${WANAKU_REALM}" --arg origin "${WANAKU_MCP_BASE_URL}" \
  '.issuer == $iss and .token_endpoint == ($origin + "/token") and .code_challenge_methods_supported == ["S256"]' \
  > /dev/null && echo "PASS: discovery document names the Keycloak issuer" \
  || echo "FAIL: unexpected discovery document: ${DISCOVERY}"
```

In option B, the issuer is `WANAKU_AUTH_ISSUER_URL`. Replace the `--arg iss` value if it differs from
`${KEYCLOAK_URL}/realms/${WANAKU_REALM}`.

**Expected result:**

- The output includes `PASS: discovery document names the Keycloak issuer`.
- The output contains no line starting with `FAIL:`.

### Test 8.3: Login through the MCP origin with a blank realm

A blank `--realm` makes the CLI use `--auth-server` as the issuer. The CLI reads the issuer from the Wanaku Server
document and then requests the token from Keycloak.

```bash run
rm -f "${CREDENTIALS_FILE}"
OUTPUT=$(echo "${WANAKU_TEST_PASS}" | wanaku auth login \
  --auth-server "${WANAKU_MCP_BASE_URL}" --realm "" \
  --username "${WANAKU_TEST_USER}" --password --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -eq 0 ] \
  && ! grep -q "^auth.realm=" "${CREDENTIALS_FILE}" \
  && wanaku tools list --host "${WANAKU_SERVER_URL}" --plain > /dev/null 2>&1; then
  echo "PASS: login through the MCP origin works; no realm stored; token accepted"
else
  echo "FAIL: login through the MCP origin (exit code ${EXIT_CODE})"; echo "${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: login through the MCP origin works; no realm stored; token accepted`.
- The output contains no line starting with `FAIL:`.

### Test 8.4: The management origin is not an issuer

The management proxy does not let `/.well-known/*` through, so discovery fails. The CLI prints a hint.

```bash run
OUTPUT=$(echo "${WANAKU_TEST_PASS}" | wanaku auth login --auth-server "${WANAKU_SERVER_URL}" --realm "" \
  --username "${WANAKU_TEST_USER}" --password --plain 2>&1)
EXIT_CODE=$?
if [ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "Unable to download OpenID Provider metadata" \
  && echo "${OUTPUT}" | grep -q "not at Wanaku"; then
  echo "PASS: login against the management origin fails with a hint"
else
  echo "FAIL: unexpected result (exit code ${EXIT_CODE})"; echo "${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: login against the management origin fails with a hint`.
- The output contains no line starting with `FAIL:`.

### Test 8.5: Metadata without an issuer (optional)

Run this test only when a local Wanaku Server without `WANAKU_AUTH_ISSUER` is available (see
[common/start-server.md](common/start-server.md)). Set `WANAKU_UNPROTECTED_MCP_BASE_URL` to its MCP base URL.

```bash
if [ -z "${WANAKU_UNPROTECTED_MCP_BASE_URL}" ]; then
  echo "SKIP: WANAKU_UNPROTECTED_MCP_BASE_URL not set"
else
  META=$(curl -s "${WANAKU_UNPROTECTED_MCP_BASE_URL}/.well-known/oauth-protected-resource/default/mcp")
  echo "${META}" | jq -e '.authorization_servers == []' > /dev/null \
    && echo "PASS: no authorization server without WANAKU_AUTH_ISSUER" || echo "FAIL: unexpected metadata: ${META}"
fi
```

The server returns HTTP `200` with an empty `authorization_servers` list. It does not return `404`.

**Expected result:**

- The output includes `PASS: no authorization server without WANAKU_AUTH_ISSUER` or `SKIP: WANAKU_UNPROTECTED_MCP_BASE_URL not set`.

## Phase 9: Token Refresh

### Test 9.1: An expired token is refreshed automatically

Run this block as a separate `bash` script, not in the shared session.

```bash
rm -f "${CREDENTIALS_FILE}"
echo "${WANAKU_TEST_PASS}" | wanaku auth login --auth-server "${KEYCLOAK_URL}" --realm "${WANAKU_REALM}" \
  --username "${WANAKU_TEST_USER}" --password --plain > /dev/null 2>&1 || { echo "FAIL: login failed"; exit 1; }

# Force token expiry
sed "s/^token.expiry=.*/token.expiry=1000000000/" "${CREDENTIALS_FILE}" > "${CREDENTIALS_FILE}.tmp" \
  && mv "${CREDENTIALS_FILE}.tmp" "${CREDENTIALS_FILE}"

OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --plain 2>&1)
EXIT_CODE=$?
NEW_EXPIRY=$(grep "^token.expiry=" "${CREDENTIALS_FILE}" | sed 's/^token.expiry=//')
if [ "${EXIT_CODE}" -eq 0 ] && [ -n "${NEW_EXPIRY}" ] && [ "${NEW_EXPIRY}" != "1000000000" ]; then
  echo "PASS: token refreshed (new expiry: ${NEW_EXPIRY}) and the request succeeded"
else
  echo "FAIL: token not refreshed (exit code ${EXIT_CODE}, expiry ${NEW_EXPIRY})"; echo "${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: token refreshed (new expiry: <NEW_EXPIRY>) and the request succeeded`. Replace each `<VARIABLE>` placeholder with that variable’s run-time value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 9.2: The realm is preserved after the refresh

```bash run
grep -q "^auth.realm=${WANAKU_REALM}$" "${CREDENTIALS_FILE}" \
  && echo "PASS: realm preserved after token refresh" || echo "FAIL: realm changed or cleared after refresh"
```

**Expected result:**

- The output includes `PASS: realm preserved after token refresh`.
- The output contains no line starting with `FAIL:`.

## Phase 10: Auth Login — Negative Tests

### Test 10.1: Wrong password

```bash run
OUTPUT=$(echo "wrong-$(openssl rand -hex 8)" | wanaku auth login --auth-server "${KEYCLOAK_URL}" --realm "${WANAKU_REALM}" \
  --username "${WANAKU_TEST_USER}" --password --plain 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "Invalid user credentials" \
  && echo "PASS: wrong password rejected" || echo "FAIL: wrong password not rejected (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: wrong password rejected`.
- The output contains no line starting with `FAIL:`.

### Test 10.2: Unreachable auth server

```bash run
OUTPUT=$(echo "${WANAKU_TEST_PASS}" | wanaku auth login --auth-server "http://localhost:59999" \
  --realm "${WANAKU_REALM}" --username "${WANAKU_TEST_USER}" --password --plain 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "Connection refused" \
  && echo "PASS: unreachable auth server reported" || echo "FAIL: unexpected result (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: unreachable auth server reported`.
- The output contains no line starting with `FAIL:`.

### Test 10.3: --api-token stores a token; an invalid token is rejected by the proxy

```bash run
GOOD_TOKEN=$(wanaku auth token --get --unmask --plain 2>/dev/null)

wanaku auth login --api-token "${GOOD_TOKEN}" --plain 2>&1 | grep -q "Successfully stored authentication credentials" \
  && wanaku tools list --host "${WANAKU_SERVER_URL}" --plain > /dev/null 2>&1 \
  && echo "PASS: --api-token stores a usable token" || echo "FAIL: --api-token with a valid token"

wanaku auth login --api-token "not-a-valid-token" --plain > /dev/null 2>&1
OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --plain 2>&1)
echo "${OUTPUT}" | grep -q "Authentication rejected by the server (status: 401" \
  && echo "PASS: invalid stored token rejected by the proxy" || echo "FAIL: unexpected output: ${OUTPUT}"
```

**Expected result:**

- The output includes `PASS: --api-token stores a usable token`.
- The output includes `PASS: invalid stored token rejected by the proxy`.
- The output contains no line starting with `FAIL:`.

## Phase 11: CLI --token and --no-auth Options

### Test 11.1: Obtain a token

This block runs in the shared session, so Test 11.2 can use `TOKEN`.

```bash run
rm -f "${CREDENTIALS_FILE}"
echo "${WANAKU_TEST_PASS}" | wanaku auth login --auth-server "${KEYCLOAK_URL}" --realm "${WANAKU_REALM}" \
  --username "${WANAKU_TEST_USER}" --password --plain > /dev/null 2>&1
TOKEN=$(wanaku auth token --get --unmask --plain 2>/dev/null)
rm -f "${CREDENTIALS_FILE}"
[ -n "${TOKEN}" ] && echo "PASS: token obtained (length: ${#TOKEN}); stored credentials removed" \
  || echo "FAIL: could not obtain a token"
```

**Expected result:**

- The output includes `PASS: token obtained (length: ${#TOKEN}); stored credentials removed`.
- The output contains no line starting with `FAIL:`.

### Test 11.2: Management commands accept --token without stored credentials

```bash run
for GROUP in tools resources prompts namespaces forwards; do
  OUTPUT=$(wanaku "${GROUP}" list --host "${WANAKU_SERVER_URL}" --token "${TOKEN}" --plain 2>&1)
  EXIT_CODE=$?
  [ "${EXIT_CODE}" -eq 0 ] && echo "PASS: ${GROUP} list with --token" \
    || { echo "FAIL: ${GROUP} list with --token (exit code ${EXIT_CODE})"; echo "${OUTPUT}"; }
done
```

**Expected result:**

- The output includes `PASS: tools list with --token`.
- The output includes `PASS: resources list with --token`.
- The output includes `PASS: prompts list with --token`.
- The output includes `PASS: namespaces list with --token`.
- The output includes `PASS: forwards list with --token`.
- The output contains no line starting with `FAIL:`.

### Test 11.3: --no-auth fails against the protected server

```bash run
OUTPUT=$(wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "status: 401" \
  && echo "PASS: --no-auth rejected by the management proxy (401)" \
  || echo "FAIL: --no-auth should fail against the protected server (exit code ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: --no-auth rejected by the management proxy (401)`.
- The output contains no line starting with `FAIL:`.

### Test 11.4: An unreachable host reports a connection error, not an auth warning

The CLI always sends a stored or given token. It does not probe an OIDC endpoint first.

```bash run
OUTPUT=$(wanaku tools list --host "http://localhost:59999" --token "dummy-token" --plain 2>&1)
if echo "${OUTPUT}" | grep -qi "OIDC endpoint\|authentication headers will not be sent"; then
  echo "FAIL: legacy auth probe message found: ${OUTPUT}"
elif echo "${OUTPUT}" | grep -q "Unable to connect to Wanaku service"; then
  echo "PASS: unreachable host reported as a connection error"
else
  echo "FAIL: unexpected output: ${OUTPUT}"
fi
```

**Expected result:**

- The output includes `PASS: unreachable host reported as a connection error`.
- The output contains no line starting with `FAIL:`.

## Phase 12: Admin UI Login Through the Management Proxy (PKCE)

The management proxy protects the admin UI with a browser session. It uses the authorization code flow with PKCE
(`OAUTH2_PROXY_CODE_CHALLENGE_METHOD=S256`). These tests use `curl` because they check redirects. In option B with
`spec.server.variant: headless`, there is no admin UI. Skip this phase then.

### Test 12.1: Without a session, /admin/ shows the proxy sign-in page

```bash run
CODE=$(curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/admin/")
BODY=$(curl -s "${WANAKU_SERVER_URL}/admin/")
if [ "${CODE}" = "403" ] && echo "${BODY}" | grep -q "Sign in with"; then
  echo "PASS: /admin/ without a session shows the oauth2-proxy sign-in page (403)"
else
  echo "FAIL: /admin/ returned HTTP ${CODE}"
fi
```

**Expected result:**

- The output includes `PASS: /admin/ without a session shows the oauth2-proxy sign-in page (403)`.
- The output contains no line starting with `FAIL:`.

### Test 12.2: The login redirect contains a PKCE challenge

```bash run
LOCATION=$(curl -sI "${WANAKU_SERVER_URL}/oauth2/start?rd=%2Fadmin%2F" | grep -i "^location:" | tr -d '\r' | sed 's/^[Ll]ocation: //')
echo "${LOCATION}" | grep -q "^${KEYCLOAK_URL}/realms/${WANAKU_REALM}/protocol/openid-connect/auth" \
  && echo "PASS: redirect goes to the Keycloak authorization endpoint" || echo "FAIL: unexpected redirect: ${LOCATION}"
echo "${LOCATION}" | grep -q "code_challenge=" && echo "${LOCATION}" | grep -q "code_challenge_method=S256" \
  && echo "PASS: redirect has code_challenge with method S256" || echo "FAIL: PKCE parameters missing"
echo "${LOCATION}" | grep -q "client_id=wanaku-mcp-router" \
  && echo "PASS: redirect uses the wanaku-mcp-router client" || echo "FAIL: unexpected client_id"
```

In option B, the redirect uses `WANAKU_AUTH_ISSUER_URL`. Replace the first check if it differs from
`${KEYCLOAK_URL}/realms/${WANAKU_REALM}`.

**Expected result:**

- The output includes `PASS: redirect goes to the Keycloak authorization endpoint`.
- The output includes `PASS: redirect has code_challenge with method S256`.
- The output includes `PASS: redirect uses the wanaku-mcp-router client`.
- The output contains no line starting with `FAIL:`.

## Phase 13: Cleanup

### Step 13.1: Remove the test users and the test client (idempotent)

```bash run
for USER_NAME in "${TEST_USERNAME}" "${WANAKU_TEST_USER}"; do
  kc users remove --username "${USER_NAME}" > /dev/null 2>&1 || true
done
kc credentials remove --client-id "${TEST_CLIENT_ID}" > /dev/null 2>&1 || true

kc users list --plain 2>&1 | grep -qE "^(${TEST_USERNAME}|${WANAKU_TEST_USER})[[:space:]]" && echo "FAIL: test user still present" \
  || echo "PASS: test users removed"
kc credentials list --plain 2>&1 | grep -q "^${TEST_CLIENT_ID}[[:space:]]" && echo "FAIL: test client still present" \
  || echo "PASS: test client removed"
```

**Expected result:**

- The output includes `PASS: test users removed`.
- The output includes `PASS: test client removed`.
- The output contains no line starting with `FAIL:`.

### Step 13.2: Restore the credentials file

```bash run
rm -f "${CREDENTIALS_FILE}" "${CREDENTIALS_FILE}-admin-cli" "${CREDENTIALS_FILE}-mcp-client"
if [ -f "${CREDENTIALS_FILE}.bak-auth-test" ]; then
  mv "${CREDENTIALS_FILE}.bak-auth-test" "${CREDENTIALS_FILE}"
  echo "PASS: original credentials restored"
else
  echo "PASS: no backup to restore"
fi
```

**Expected result:**

- The output includes `PASS: original credentials restored`.
- The output includes `PASS: no backup to restore`.

### Step 13.3: Remove the environment

- Option A: `docker compose -p wanaku-auth -f deploy/auth/docker-compose-auth.yml down -v` from the
  `wanaku-ai/wanaku` checkout. The `-v` option also removes the Keycloak and registry volumes.
- Option B: `oc delete wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true`, then
  follow [common/cleanup.md](common/cleanup.md).

**Expected result:**

- The stated setup or shutdown instructions complete successfully (inferred).

## Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | 0.1 | Tools and jars | High |
| 1 | 1.1–1.3 | Environment variables, proxies return 401 without token, auth test user | Critical |
| 2 | 2.1–2.3 | Users round trip (add and list, set-password, remove and list) | Critical |
| 3 | 3.1–3.4 | Service client round trip (add, plain secret, regenerate, remove) | Critical |
| 4 | 4.1 | Admin negative test (wrong admin credentials) | High |
| 5 | 5.1–5.5 | Auth login with Keycloak base URL and realm, stored entries, auth status, token output | Critical |
| 6 | 6.1–6.5 | Token accepted by management and MCP proxies, admin-cli rejected with guidance, public mcp-client | Critical |
| 7 | 7.1–7.3 | Full issuer URL login and realm persistence | High |
| 8 | 8.1–8.5 | RFC 9728 metadata, discovery document, login through the MCP origin, management origin rejected | Critical |
| 9 | 9.1–9.2 | Automatic token refresh, realm preserved | Critical |
| 10 | 10.1–10.3 | Auth login negative tests (wrong password, unreachable auth server), --api-token | High |
| 11 | 11.1–11.4 | --token on management commands, --no-auth rejected, unreachable host | Critical |
| 12 | 12.1–12.2 | Admin UI sign-in page and PKCE redirect | High |
| 13 | 13.1–13.3 | Cleanup | Critical |
| S | S.1 | Check prerequisites | Setup |
| S | S.2 | Check prerequisites | Setup |
| 1 | 1.0 | Select and prepare one environment | Setup |
