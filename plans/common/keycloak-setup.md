# Procedure: Keycloak Setup on OpenShift

Reusable steps for deploying and configuring Keycloak as the OIDC provider for Wanaku on OpenShift.

## Prerequisites

- Namespace created (see [namespace-setup.md](namespace-setup.md))
- `WANAKU_NAMESPACE` environment variable set
- `WANAKU_REPO_ROOT` environment variable set (path to the wanaku repository root)
- `jq` available
- `openssl` available (generates passwords that are not set)
- `curl` available (only for Keycloak readiness polling — all Wanaku/Keycloak API calls use the CLI)
- `wanaku` CLI available on `PATH`, **or** the project built with `mvn verify` (to use the jar directly)

## Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `WANAKU_NAMESPACE` | Target namespace created for this run | `wanaku-test-<run-id>` |
| `WANAKU_REPO_ROOT` | Path to the wanaku repository root | `.` |
| `WANAKU_CLI` | Command to invoke the Wanaku CLI (see step 1) | `wanaku` |
| `WANAKU_KEYCLOAK_ADMIN_CLI` | Command to invoke the Keycloak admin CLI, which provides the `realm`, `credentials` and `users` commands (see step 1) | `wanaku-keycloak-admin` |
| `KEYCLOAK_ADMIN_USER` | Keycloak admin username | `admin` |
| `KEYCLOAK_ADMIN_PASS` | Keycloak admin password. Do not commit it. | Generated at run time |
| `KEYCLOAK_IMAGE` | Keycloak container image | `quay.io/keycloak/keycloak:26.7` |
| `WANAKU_AUTH_SECRET_NAME` | Name of the Secret that `WanakuRouter.spec.auth.secretName` references (see step 8) | `wanaku-oidc` |

The procedure generates the passwords and client secrets that are not set. The values exist only in the shell that runs
the procedure. Keycloak keeps its data on a PersistentVolumeClaim in `WANAKU_NAMESPACE`. If you run the procedure again
in a namespace from an earlier run, export the values of that run first (`KEYCLOAK_ADMIN_PASS`, `WANAKU_TEST_PASS`).
New random values do not change the accounts that exist. Alternatively, delete the namespace and use a new one.

## Phase 1: Keycloak Setup on OpenShift

### Step 1: Set variables

```bash
export KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
# The procedure deploys this Keycloak, so a random password is generated when none is set
export KEYCLOAK_ADMIN_PASS="${KEYCLOAK_ADMIN_PASS:-$(openssl rand -hex 16)}"
export KEYCLOAK_IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.7}"
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"

# Option A: use the installed CLIs (an existing value is kept)
export WANAKU_CLI="${WANAKU_CLI:-wanaku}"
export WANAKU_KEYCLOAK_ADMIN_CLI="${WANAKU_KEYCLOAK_ADMIN_CLI:-wanaku-keycloak-admin}"

# Option B: use the jars from a local build (after running `mvn verify` from the repo root)
# export WANAKU_CLI="java -jar ${WANAKU_REPO_ROOT}/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
# export WANAKU_KEYCLOAK_ADMIN_CLI="java -jar ${WANAKU_REPO_ROOT}/apps/wanaku-keycloak-admin/target/quarkus-app/quarkus-run.jar"
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 2: Deploy Keycloak

```bash
DOMAIN=$(oc get ingresses.config.openshift.io cluster -o jsonpath='{.spec.domain}')
KC_HOST="keycloak-${WANAKU_NAMESPACE}.${DOMAIN}"
cat <<'KEYCLOAK_EOF' | sed "s|__KEYCLOAK_IMAGE__|${KEYCLOAK_IMAGE}|g; s|__ADMIN_USER__|${KEYCLOAK_ADMIN_USER}|g; s|__ADMIN_PASS__|${KEYCLOAK_ADMIN_PASS}|g; s|__KEYCLOAK_HOST__|${KC_HOST}|g" | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  labels:
    app: keycloak
  name: keycloak
spec:
  replicas: 1
  selector:
    matchLabels:
      app: keycloak
  template:
    metadata:
      labels:
        app: keycloak
    spec:
      containers:
        - name: keycloak
          image: __KEYCLOAK_IMAGE__
          imagePullPolicy: Always
          ports:
            - containerPort: 8080
              protocol: TCP
          env:
            - name: KC_BOOTSTRAP_ADMIN_USERNAME
              value: "__ADMIN_USER__"
            - name: KC_BOOTSTRAP_ADMIN_PASSWORD
              value: "__ADMIN_PASS__"
            - name: KC_HOSTNAME
              value: "https://__KEYCLOAK_HOST__"
            - name: KC_HOSTNAME_STRICT
              value: "false"
          args:
            - "start-dev"
          volumeMounts:
            - name: keycloak-data
              mountPath: /opt/keycloak/data
      volumes:
        - name: keycloak-data
          persistentVolumeClaim:
            claimName: keycloak-data-pvc
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: keycloak-data-pvc
  labels:
    app: keycloak
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  labels:
    app: keycloak
  name: keycloak
spec:
  ports:
    - name: 8080-tcp
      protocol: TCP
      port: 8080
      targetPort: 8080
  selector:
    app: keycloak
  sessionAffinity: None
  type: ClusterIP
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: keycloak
spec:
  tls:
    - {}
  rules:
    - host: __KEYCLOAK_HOST__
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: keycloak
                port:
                  number: 8080
KEYCLOAK_EOF
```

**Expected result:**

- The commands exit with code 0 (inferred).

### Step 3: Wait for Keycloak to be ready

```bash
oc wait --for=condition=ready pod -l app=keycloak \
  --timeout=300s \
  -n "${WANAKU_NAMESPACE}"
```

**Expected output:** `pod/keycloak-... condition met`

**Expected result:**

- `pod/keycloak-... condition met`

### Step 4: Retrieve the Keycloak external host

```bash
export KEYCLOAK_HOST=$(oc get ingress keycloak -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.rules[0].host}')
export KEYCLOAK_URL="https://${KEYCLOAK_HOST}"
echo "Keycloak URL: ${KEYCLOAK_URL}"
```

**Expected result:**

- The output includes `PASS: Keycloak host is <KEYCLOAK_HOST>`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

**Verification:**

Run this block as a separate `bash` script, not in the shared session.

```bash
if [ -z "${KEYCLOAK_HOST}" ]; then
  echo "FAIL: could not retrieve Keycloak ingress host"
  exit 1
fi
echo "PASS: Keycloak host is ${KEYCLOAK_HOST}"
```

### Step 5: Wait for Keycloak to respond

Run this block as a separate `bash` script, not in the shared session.

```bash
MAX_RETRIES=30
RETRY_INTERVAL=10
for i in $(seq 1 ${MAX_RETRIES}); do
  HTTP_CODE=$(curl -s -L -k -o /dev/null -w "%{http_code}" "${KEYCLOAK_URL}/realms/master" 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "PASS: Keycloak is responding (attempt ${i})"
    break
  fi
  if [ "${i}" -eq "${MAX_RETRIES}" ]; then
    echo "FAIL: Keycloak not responding after ${MAX_RETRIES} attempts"
    exit 1
  fi
  echo "Waiting for Keycloak... (attempt ${i}, HTTP ${HTTP_CODE})"
  sleep ${RETRY_INTERVAL}
done
```

**Expected result:**

- The output includes `PASS: Keycloak is responding`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 6: Import the Wanaku realm using the CLI

The Keycloak admin CLI (`wanaku-keycloak-admin`) imports a full realm configuration that includes:
- The `wanaku` realm with all settings
- The `wanaku-service` client (confidential, service account enabled)
- The `wanaku-mcp-router` client (confidential, used by the oauth2-proxy instances and by `wanaku auth login`)
- The `mcp-client` client (public, for MCP clients)
- All required roles, scopes, and service accounts

The realm configuration sets the client secrets with `${VAR:default}` placeholders. The CLI replaces each placeholder
with the value of the environment variable, so export random values before the import. Do not use the defaults in the
realm file.

```bash
export WANAKU_SERVICE_SECRET="${WANAKU_SERVICE_SECRET:-$(openssl rand -hex 16)}"
export WANAKU_MCP_ROUTER_SECRET="${WANAKU_MCP_ROUTER_SECRET:-$(openssl rand -hex 16)}"
${WANAKU_KEYCLOAK_ADMIN_CLI} realm create \
  --keycloak-url "${KEYCLOAK_URL}" \
  --admin-username "${KEYCLOAK_ADMIN_USER}" \
  --admin-password "${KEYCLOAK_ADMIN_PASS}" \
  --insecure \
  --config "${WANAKU_REPO_ROOT}/deploy/auth/wanaku-config.json"
```

**Expected output:** `Realm imported successfully from <path>/deploy/auth/wanaku-config.json`

**Expected result:**

- The output includes `PASS: wanaku realm is accessible`.
- `Realm imported successfully from <path>/deploy/auth/wanaku-config.json`
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

**Verification:**

Run this block as a separate `bash` script, not in the shared session.

```bash
HTTP_CODE=$(curl -s -L -k -o /dev/null -w "%{http_code}" "${KEYCLOAK_URL}/realms/wanaku" 2>/dev/null || echo "000")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: wanaku realm not accessible (HTTP ${HTTP_CODE})"
  exit 1
fi
echo "PASS: wanaku realm is accessible"
```

### Step 7: Retrieve the OIDC client secret for wanaku-service

Step 6 sets the `wanaku-service` client secret from `WANAKU_SERVICE_SECRET`.

Retrieve the actual secret using the CLI:

Run this block as a separate `bash` script, not in the shared session.

```bash
CREDENTIALS_OUTPUT=$(${WANAKU_KEYCLOAK_ADMIN_CLI} credentials show \
  --keycloak-url "${KEYCLOAK_URL}" \
  --admin-username "${KEYCLOAK_ADMIN_USER}" \
  --admin-password "${KEYCLOAK_ADMIN_PASS}" \
  --client-id wanaku-service \
  --insecure \
  --show-secret \
  --plain 2>&1)

# With --show-secret --plain the command writes only the secret to stdout
export WANAKU_OIDC_SECRET="${CREDENTIALS_OUTPUT}"

if [ -z "${WANAKU_OIDC_SECRET}" ] || [ "${WANAKU_OIDC_SECRET}" = "null" ]; then
  echo "FAIL: could not retrieve OIDC secret"
  echo "${CREDENTIALS_OUTPUT}"
  exit 1
fi
echo "PASS: OIDC secret retrieved (length: ${#WANAKU_OIDC_SECRET})"
```

**Expected result:**

- The output includes `PASS: OIDC secret retrieved`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 7b: Retrieve the wanaku-mcp-router client secret for CLI logins

`wanaku auth login` uses the confidential `wanaku-mcp-router` client, because oauth2-proxy only accepts tokens carrying
that client's audience. Export its secret so the CLI picks it up through `WANAKU_CLIENT_SECRET`:

Run this block as a separate `bash` script, not in the shared session.

```bash
export WANAKU_CLIENT_SECRET=$(${WANAKU_KEYCLOAK_ADMIN_CLI} credentials show \
  --keycloak-url "${KEYCLOAK_URL}" \
  --admin-username "${KEYCLOAK_ADMIN_USER}" \
  --admin-password "${KEYCLOAK_ADMIN_PASS}" \
  --client-id wanaku-mcp-router \
  --insecure \
  --show-secret \
  --plain 2>/dev/null)

if [ -z "${WANAKU_CLIENT_SECRET}" ]; then
  echo "FAIL: could not retrieve the wanaku-mcp-router client secret"
  exit 1
fi
echo "PASS: wanaku-mcp-router secret retrieved (length: ${#WANAKU_CLIENT_SECRET})"
```

**Expected result:**

- The output includes `PASS: wanaku-mcp-router secret retrieved`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 8: Create the authentication Secret for the WanakuRouter

When `WanakuRouter.spec.auth.enabled` is `true`, the operator deploys oauth2-proxy in front of Wanaku Server. The
proxies read two keys from the Secret that `spec.auth.secretName` names:

| Key | Content |
|-----|---------|
| `client-secret` | The secret of the `wanaku-mcp-router` client (`spec.auth.clientId`, default `wanaku-mcp-router`) |
| `cookie-secret` | 16, 24 or 32 bytes, or the base64 encoding of such a value |

The operator does not deploy the proxies while the Secret is missing or not valid. The `AuthConfigured` condition of
the router then has the status `False`.

`openssl rand -hex 16` writes 32 hexadecimal characters, which is a valid 32-byte cookie secret. The command uses
`oc apply`, so you can run it again.

Run this block as a separate `bash` script, not in the shared session.

```bash
export WANAKU_AUTH_SECRET_NAME="${WANAKU_AUTH_SECRET_NAME:-wanaku-oidc}"

oc create secret generic "${WANAKU_AUTH_SECRET_NAME}" \
  --from-literal=client-secret="${WANAKU_CLIENT_SECRET}" \
  --from-literal=cookie-secret="$(openssl rand -hex 16)" \
  --dry-run=client -o yaml \
  | oc apply -n "${WANAKU_NAMESPACE}" -f -

for KEY in client-secret cookie-secret; do
  VALUE=$(oc get secret "${WANAKU_AUTH_SECRET_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath="{.data.${KEY}}" 2>/dev/null)
  if [ -z "${VALUE}" ]; then
    echo "FAIL: Secret ${WANAKU_AUTH_SECRET_NAME} has no ${KEY} key"
    exit 1
  fi
done
echo "PASS: Secret ${WANAKU_AUTH_SECRET_NAME} has the client-secret and cookie-secret keys"
```

Set the issuer URLs for the `WanakuRouter` resource:

```bash
export WANAKU_AUTH_ISSUER_URL="${KEYCLOAK_URL}/realms/wanaku"
export WANAKU_AUTH_INTERNAL_ISSUER_URL="http://keycloak:8080/realms/wanaku"
echo "PASS: issuerUrl=${WANAKU_AUTH_ISSUER_URL} internalIssuerUrl=${WANAKU_AUTH_INTERNAL_ISSUER_URL}"
```

**Expected result:**

- The output includes `PASS: Secret <WANAKU_AUTH_SECRET_NAME> has the client-secret and cookie-secret keys`.
- The output includes `PASS: issuerUrl=<WANAKU_AUTH_ISSUER_URL> internalIssuerUrl=<WANAKU_AUTH_INTERNAL_ISSUER_URL>`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

### Step 9: Create a test user

The realm import creates clients and roles but no regular users. Create a test user that
subsequent steps use to call Wanaku Server through the oauth2-proxy.

```bash
export WANAKU_TEST_USER="${WANAKU_TEST_USER:-alice}"
export WANAKU_TEST_PASS="${WANAKU_TEST_PASS:-$(openssl rand -hex 16)}"
export WANAKU_TEST_EMAIL="${WANAKU_TEST_EMAIL:-alice@example.com}"

${WANAKU_KEYCLOAK_ADMIN_CLI} users add \
  --keycloak-url "${KEYCLOAK_URL}" \
  --insecure \
  --admin-username "${KEYCLOAK_ADMIN_USER}" \
  --admin-password "${KEYCLOAK_ADMIN_PASS}" \
  --username "${WANAKU_TEST_USER}" \
  --password "${WANAKU_TEST_PASS}" \
  --email "${WANAKU_TEST_EMAIL}"
```

**Expected result:**

- The output includes `PASS: test user '<WANAKU_TEST_USER>' created`.
- The output contains no line starting with `FAIL`.
- The commands exit with code 0.

**Verification:**

Run this block as a separate `bash` script, not in the shared session.

```bash
if [ $? -ne 0 ]; then
  echo "FAIL: could not create test user"
  exit 1
fi
echo "PASS: test user '${WANAKU_TEST_USER}' created"
```

### Step 10: Verify OIDC login

The `wanaku auth login` command authenticates directly against Keycloak (`<auth-server>/realms/<realm>`, realm `wanaku`
by default) using the `wanaku-mcp-router` client and the secret from `WANAKU_CLIENT_SECRET`. The router does not need
to be deployed for the login itself. The oauth2-proxy in front of Wanaku Server accepts the resulting token. Wanaku
Server itself does not validate tokens.

- To verify the token end to end against the router, follow [common/oidc-login-verification.md](oidc-login-verification.md) **after the router is created**.
- To log in without a router:

  ```bash
  echo "${WANAKU_TEST_PASS}" | ${WANAKU_CLI:-wanaku} auth login \
    --auth-server "${KEYCLOAK_URL}" \
    --insecure \
    --realm wanaku \
    --username "${WANAKU_TEST_USER}" \
    --password \
    --plain 2>&1
  ```

**Expected result:**

- The login command exits with code 0 when the login-without-a-router alternative is used (inferred).

## Output Variables

Use these values in the router:

```yaml
spec:
  auth:
    enabled: true
    issuerUrl: ${WANAKU_AUTH_ISSUER_URL}
    internalIssuerUrl: ${WANAKU_AUTH_INTERNAL_ISSUER_URL}
    clientId: wanaku-mcp-router
    secretName: ${WANAKU_AUTH_SECRET_NAME}
```

After completing this procedure, the following variables are set and available for subsequent steps:

| Variable | Description |
|----------|-------------|
| `KEYCLOAK_HOST` | External hostname of Keycloak (from OpenShift Ingress) |
| `KEYCLOAK_URL` | Full URL (`https://<host>`) |
| `WANAKU_OIDC_SECRET` | Client secret for the `wanaku-service` client |
| `WANAKU_SERVICE_SECRET` | Generated client secret for the `wanaku-service` client, used for the realm import |
| `WANAKU_MCP_ROUTER_SECRET` | Generated client secret for the `wanaku-mcp-router` client, used for the realm import |
| `WANAKU_CLIENT_SECRET` | Client secret for the `wanaku-mcp-router` client, read by `wanaku auth login` |
| `WANAKU_TEST_USER` | Username for authenticated CLI operations (default: `alice`) |
| `WANAKU_TEST_PASS` | Password for the test user (default: generated at run time) |
| `WANAKU_TEST_EMAIL` | Email for the test user (default: `alice@example.com`) |
| `KEYCLOAK_ADMIN_USER` | Keycloak admin username (default: `admin`) |
| `KEYCLOAK_ADMIN_PASS` | Keycloak admin password (default: generated at run time) |
| `WANAKU_AUTH_SECRET_NAME` | Secret with the `client-secret` and `cookie-secret` keys for `spec.auth.secretName` (default: `wanaku-oidc`) |
| `WANAKU_AUTH_ISSUER_URL` | Public issuer URL for `spec.auth.issuerUrl` (`${KEYCLOAK_URL}/realms/wanaku`) |
| `WANAKU_AUTH_INTERNAL_ISSUER_URL` | In-cluster issuer URL for `spec.auth.internalIssuerUrl` (`http://keycloak:8080/realms/wanaku`) |
