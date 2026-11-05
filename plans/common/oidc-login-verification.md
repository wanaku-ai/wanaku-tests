# Procedure: OIDC Login Verification

Reusable steps for verifying OIDC authentication end to end: log in against Keycloak, then call Wanaku Server through
the oauth2-proxy that protects its management API.

Wanaku Server has no built-in authentication. When `WanakuRouter.spec.auth.enabled` is `true`, the operator deploys
oauth2-proxy (Deployment `<router>-oauth2-proxy`, Service `oauth2-proxy-<router>`) and points the
`<router>-management` and `<router>-mcp` Ingresses at it. The proxies validate the tokens.

The `wanaku auth login` command authenticates directly against Keycloak (`<auth-server>/realms/<realm>`, realm
`wanaku` by default) using the confidential `wanaku-mcp-router` client. oauth2-proxy accepts only tokens carrying that
client's audience, so the client secret must be available in `WANAKU_CLIENT_SECRET` (set by
[keycloak-setup.md](keycloak-setup.md), step 8b).

## Prerequisites

- Keycloak deployed and configured (see [keycloak-setup.md](keycloak-setup.md))
- WanakuRouter CR created with `spec.auth.enabled: true`, and Ready
- `WANAKU_ROUTER_URL` environment variable set to the management URL (e.g. `https://<host of Ingress <router>-management>`).
  OpenShift creates edge-terminated Routes from the Ingresses, so use `https`.
- Optional: `WANAKU_CLI_TLS_OPTS` set to `--insecure` when the cluster ingress certificate is not trusted
  (default: empty)
- `WANAKU_TEST_USER` and `WANAKU_TEST_PASS` environment variables set
- `KEYCLOAK_URL` and `WANAKU_CLIENT_SECRET` environment variables set
- `WANAKU_CREDENTIALS` exported to a per-run path (see [#1697](https://github.com/wanaku-ai/wanaku/issues/1697)) to isolate credential storage across concurrent test runs

## Phase 1: OIDC Login Verification

### Step 1: Verify the OIDC login works

Log in as the test user against Keycloak with the `wanaku-mcp-router` client.

Note: `--password` is a boolean flag that prompts for input. Pipe the password via stdin.
Use the same optional TLS flag for Keycloak login and the subsequent proxy requests.

```bash
echo "${WANAKU_TEST_PASS}" | ${WANAKU_CLI:-wanaku} auth login \
  --auth-server "${KEYCLOAK_URL}" ${WANAKU_CLI_TLS_OPTS} \
  --realm wanaku \
  --client-secret "${WANAKU_CLIENT_SECRET}" \
  --username "${WANAKU_TEST_USER}" \
  --password \
  --plain 2>&1

LOGIN_EXIT=$?

if [ "${LOGIN_EXIT}" -ne 0 ]; then
  echo "FAIL: OIDC login failed (exit code ${LOGIN_EXIT})"
  LOGIN_FAILED=true
else
  echo "PASS: OIDC login works for user '${WANAKU_TEST_USER}'"
  LOGIN_FAILED=false
fi
```

**Expected result:**

- The output includes `PASS: OIDC login works for user '<WANAKU_TEST_USER>'`, or `FAIL: OIDC login failed` and Step 2 then applies the workaround.

### Step 2: Workaround: regenerate the router client secret if login fails

If the stored `wanaku-mcp-router` secret does not match Keycloak (for example after the realm was re-imported),
regenerate it, re-read it and log in again:

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash
if [ "${LOGIN_FAILED}" = "true" ]; then
  echo "WARN: wanaku-mcp-router secret may be stale — regenerating via CLI"

  ${WANAKU_KEYCLOAK_ADMIN_CLI:-wanaku-keycloak-admin} credentials regenerate \
    --keycloak-url "${KEYCLOAK_URL}" ${WANAKU_CLI_TLS_OPTS} \
    --admin-username "${KEYCLOAK_ADMIN_USER}" \
    --admin-password "${KEYCLOAK_ADMIN_PASS}" \
    --client-id wanaku-mcp-router \
    --plain 2>&1

  # With --show-secret --plain only the secret is written to stdout
  export WANAKU_CLIENT_SECRET=$(${WANAKU_KEYCLOAK_ADMIN_CLI:-wanaku-keycloak-admin} credentials show \
    --keycloak-url "${KEYCLOAK_URL}" ${WANAKU_CLI_TLS_OPTS} \
    --admin-username "${KEYCLOAK_ADMIN_USER}" \
    --admin-password "${KEYCLOAK_ADMIN_PASS}" \
    --client-id wanaku-mcp-router \
    --show-secret \
    --plain 2>/dev/null)

  echo "${WANAKU_TEST_PASS}" | ${WANAKU_CLI:-wanaku} auth login \
    --auth-server "${KEYCLOAK_URL}" ${WANAKU_CLI_TLS_OPTS} \
    --realm wanaku \
    --client-secret "${WANAKU_CLIENT_SECRET}" \
    --username "${WANAKU_TEST_USER}" \
    --password \
    --plain 2>&1

  if [ $? -ne 0 ]; then
    echo "FAIL: OIDC login still failing after secret regeneration"
    return 1
  fi
  echo "PASS: OIDC login works after secret regeneration"
fi
```

> Note: the oauth2-proxy instances in front of the router use the same client secret. After you regenerate it, update
> the `client-secret` key of the Secret that `spec.auth.secretName` names (`${WANAKU_AUTH_SECRET_NAME}`, see
> [keycloak-setup.md](keycloak-setup.md), step 9). The operator restarts the proxies when the Secret changes.

**Expected result:**

- If `LOGIN_FAILED` is `true`, the output includes `PASS: OIDC login works after secret regeneration`; otherwise the workaround is not needed.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 3: Verify the token is accepted by the router's management proxy

The CLI sends the token that `wanaku auth login` stored.

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash
# WANAKU_CLI_TLS_OPTS is not quoted on purpose: it holds zero or one option
OUTPUT=$(${WANAKU_CLI:-wanaku} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --plain 2>&1)

if [ $? -ne 0 ]; then
  echo "FAIL: management proxy rejected the token issued to wanaku-mcp-router"
  echo "${OUTPUT}"
  return 1
fi
echo "PASS: token accepted by the management proxy at ${WANAKU_ROUTER_URL}"
```

**Expected result:**

- The output includes `PASS: token accepted by the management proxy at <WANAKU_ROUTER_URL>`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 4: Verify the management proxy rejects requests without a token

`--no-auth` makes the CLI send no token. The proxy must answer `401`, so the command must fail.

Run this block in the shared Bash session so variables and helper functions remain available.
A failing block uses `return 1` to preserve the session for diagnostics and cleanup.

```bash
OUTPUT=$(${WANAKU_CLI:-wanaku} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)

if [ $? -eq 0 ]; then
  echo "FAIL: management proxy accepted a request without a token"
  echo "${OUTPUT}"
  return 1
fi
echo "PASS: management proxy rejected the request without a token"
```

**Expected result:**

- The output includes `PASS: management proxy rejected the request without a token`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

## Output Variables

This procedure does not set new variables. After step 1, the CLI credential store (`WANAKU_CREDENTIALS`) contains the
token of `WANAKU_TEST_USER`. Read it with `${WANAKU_CLI:-wanaku} auth token --get --unmask --plain`.
