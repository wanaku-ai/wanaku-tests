# Test Plan: Wanaku Semantic Router on OpenShift

## Overview

This test plan verifies the Wanaku Semantic Router (WSR) on OpenShift with Wanaku Server. A WSR runtime runs one
semantic route as one MCP tool. The route asks an expert (the TypeSafe AI adapter) to classify the request, and then
runs a fixed Kamelet branch. The runtime registers itself with Wanaku Server as an MCP forward when it starts, and
removes the forward when it stops. Wanaku Server discovers the tool from the forward, governs the calls and serves the
tool to MCP clients at `/<namespace>/mcp`.

The plan deploys the runtime with the `WanakuSemanticRouter` custom resource and the `spec.source.configMapRef` source.
The ConfigMap holds the same support route as the local `semantic-router-tests` module (`SemanticRouterITCase`): the
tool `route_support` sends billing requests to `Billing demo response`, technical requests to
`Technical demo response`, and all other requests to `No suitable support action.`. The `semanticRoute` source of the
operator sample (`samples/wanaku-semantic-router.yaml`) needs a route that is published in Wanaku Barn. This plan does
not test the publication flow.

**Components under test:**

| Component | How the plan uses it |
|-----------|----------------------|
| Wanaku operator | Reconciles `WanakuRouter` and `WanakuSemanticRouter` |
| Wanaku Server | Deployment `<router>-server`. Management API through the `<router>` Route, MCP through the `<router>-mcp` Route. The runtime registers with `http://server-<router>:8080` (`WSR_REGISTRATION_URL`). |
| Wanaku Barn | Enabled (`spec.barn.enabled: true`). The `configMapRef` source does not read from Barn. Barn is enabled because the operator creates the `<router>-server-config` ConfigMap (`wanaku.yaml`) only when Barn is enabled, and Phase 7 changes that file. The operator then also sets `WSR_BARN_URL`. |
| WSR runtime | Image `quay.io/wanaku/wanaku-semantic-router`. Deployment and Service `<name>-wsr`. MCP on port 8090, status and health endpoints on port 8091. |
| Expert provider | A TypeSafe AI compatible endpoint. Phases 7 and 8 call it. They are skipped when `TYPESAFE_API_KEY` is not set. |

Authentication is disabled in this plan. The runtime calls the internal `server-<router>` Service, which oauth2-proxy
does not protect.

> [!IMPORTANT]
> **Governance.** Wanaku Server is fail-safe. It denies every `tools/call` request that no action policy and no
> evaluator explicitly allows. The error is `No governance policy permits this action.` (`governance_no_match`) from the
> action-policy filter, or `evaluator: no_match` from the evaluator filter. Listing requests are not governed.
>
> The `WanakuRouter` CRD has no field for the governance posture. Phase 7 adds a test `governance` section to the
> operator-generated `<router>-server-config` ConfigMap and restarts the Server, as in
> [camel-integration-capability.md](camel-integration-capability.md). **This is a test-only workaround, not a
> supported operator API.**

> [!IMPORTANT]
> **Semantic audit ingestion.** The runtime sends its semantic audit records to
> `http://server-<router>:8080/api/v1/audit/semantic`. Wanaku Server accepts them only when
> `WANAKU_AUDIT_INGESTION_ENABLED=true`. Phase 3 sets this variable through `spec.server.env` of the `WanakuRouter`.

Every step is fully automatable.

**Hard timeout rule:** Any single operation (oc wait, polling loop, port-forward setup, etc.) that takes longer than **3 minutes** MUST be considered broken. Mark the test as FAIL immediately and abort — do not wait longer. All `--timeout` values in this plan MUST NOT exceed `180s`.

> [!NOTE]
> The WSR startup probe allows 10 minutes, because a runtime resolves Maven dependencies at startup. The route in this
> plan uses only Camel components that the WSR distribution contains, so a runtime starts in less than 3 minutes on a
> normal cluster. A slow first image pull is an environment problem. Pre-pull `${WSR_IMAGE}` if necessary.

## Prerequisites

### Required tools

| Tool | Minimum version | Verify command |
|------|-----------------|----------------|
| `oc` | 4.12+ | `oc version --client` |
| `helm` | 3.x | `helm version --short` |
| `curl` | any (HTTP-level checks only) | `curl --version` |
| `jq` | 1.6+ | `jq --version` |
| `wanaku` CLI | build from source | `${WANAKU_CLI} --version` |

### Environment variables

Step 1 of [common/namespace-setup.md](common/namespace-setup.md) derives a unique `WANAKU_NAMESPACE` for this run.
Step S.2 sets the other variables. All image tags default to `latest` and can be overridden.

| Variable | Default | Description |
|----------|---------|-------------|
| `WSR_IMAGE` | `quay.io/wanaku/wanaku-semantic-router:latest` | WSR runtime image (the operator default) |
| `WSR_CAMEL_VERSION` | `4.23.0` | The Camel version of the WSR image. The route manifest (`camel.version`) must match it. |
| `TYPESAFE_API_KEY` | not set | Credential of the expert provider. When it is not set, the plan stores a placeholder value in the Secret and skips the routing and semantic audit tests. |
| `TYPESAFE_BASE_URL` | not set | Optional base URL of a TypeSafe AI compatible provider (`camel.component.typesafe-ai.base-url`). The local `SemanticRouterITCase` uses a deterministic fixture provider through this property. |
| `TYPESAFE_MODEL` | not set | Optional model (`camel.component.typesafe-ai.model`). Without it, the Camel TypeSafe AI component uses its default model. |

`WANAKU_CLI_TLS_OPTS` and `CURL_TLS_OPTS` are not quoted in the commands below on purpose: each holds zero or one
option.

### Helper: wait for resource deletion

The setup phase defines `wait_for_deletion` using [common/wait-for-deletion.md](common/wait-for-deletion.md).

### Helper: wait for a condition of a custom resource

The setup phase defines `wait_cr_condition` and `check` (Step S.3).

### Helper: wait for a tool in Wanaku Server

Wanaku Server discovers the tools when the forward is registered. The function `wait_for_tool` (Step S.4) polls the
tool list of the management API.

---

## Phase S: Setup

### Step S.1: Verify prerequisites

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
for CMD in oc helm curl jq; do
  if ! command -v "${CMD}" > /dev/null 2>&1; then
    echo "FAIL: ${CMD} is not installed"
    FAIL=1
  else
    echo "PASS: ${CMD} found at $(command -v "${CMD}")"
  fi
done
${WANAKU_CLI:-wanaku} --help > /dev/null 2>&1 && echo "PASS: the Wanaku CLI runs" || { echo "FAIL: the Wanaku CLI does not run"; FAIL=1; }
[ "${FAIL}" -eq 0 ] && echo "PASS: all prerequisites met" || { echo "FAIL: one or more prerequisites missing"; exit 1; }
```

**Expected result:**

- The output includes `PASS: oc found at`.
- The output includes `PASS: helm found at`.
- The output includes `PASS: curl found at`.
- The output includes `PASS: jq found at`.
- The output includes `PASS: the Wanaku CLI runs`.
- The output includes `PASS: all prerequisites met`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Step S.2: Set environment variables

```bash run
export WANAKU_REPO_ROOT="${WANAKU_REPO_ROOT:-.}"
export WANAKU_SERVER_IMAGE="${WANAKU_SERVER_IMAGE:-quay.io/wanaku/wanaku-server:latest}"
export WANAKU_BARN_IMAGE="${WANAKU_BARN_IMAGE:-quay.io/wanaku/wanaku-barn-backend:latest}"
export WSR_IMAGE="${WSR_IMAGE:-quay.io/wanaku/wanaku-semantic-router:latest}"
export WSR_CAMEL_VERSION="${WSR_CAMEL_VERSION:-4.23.0}"
export WANAKU_ROUTER_NAME="${WANAKU_ROUTER_NAME:-wanaku-test-router}"
# The WanakuSemanticRouter name. It is also the forward name (spec.forward.name is not set).
export WSR_NAME="${WSR_NAME:-support-semantic}"
# The tool name comes from tool.name in semantic-router.properties
export WSR_TOOL="route_support"
# Wanaku namespace of the forward (spec.forward.namespace) and of the test governance configuration (Phase 7)
export WSR_FORWARD_NAMESPACE="default"
# Wanaku CLI: an installed binary, or "java -jar <wanaku-barn>/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar"
export WANAKU_CLI="${WANAKU_CLI:-wanaku}"
# The operator creates edge-terminated Routes. Set these to empty values when the ingress certificate is trusted.
export WANAKU_CLI_TLS_OPTS="${WANAKU_CLI_TLS_OPTS---insecure}"
export CURL_TLS_OPTS="${CURL_TLS_OPTS--k}"
# Phases 7 and 8 call the expert provider only when a credential is set
if [ -n "${TYPESAFE_API_KEY}" ]; then export WSR_ROUTING_ENABLED=true; else export WSR_ROUTING_ENABLED=false; fi
echo "WSR_ROUTING_ENABLED=${WSR_ROUTING_ENABLED}"
```

**Expected result:**

- The output includes `WSR_ROUTING_ENABLED=true` or `WSR_ROUTING_ENABLED=false`.
- The commands exit with code 0 (inferred).

### Step S.3: Define condition and assertion helpers

```bash run
wait_cr_condition() {
  # $1 = kind, $2 = name, $3 = condition type, $4 = expected status, $5 = expected reason (optional)
  WCC_END=$(( $(date +%s) + 180 ))
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

# Compares a value with the expected value. Sets FAIL=1 on a mismatch. Each test resets FAIL=0 first.
check() { # $1 = description, $2 = actual, $3 = expected
  if [ "$2" = "$3" ]; then echo "PASS: $1 = $2"; else echo "FAIL: $1 = '$2', expected '$3'"; FAIL=1; fi
}
```

**Expected result:**

- The block defines the functions without errors (inferred).

### Step S.4: Define tool discovery helper

```bash run
wait_for_tool() {
  # $1 = tool name
  for i in $(seq 1 40); do
    if ${WANAKU_CLI} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>/dev/null \
        | grep -q "$1"; then
      echo "PASS: tool $1 is registered in Wanaku Server (attempt ${i})"
      return 0
    fi
    sleep 3
  done
  echo "FAIL: tool $1 not registered in Wanaku Server"
  return 1
}
```

**Expected result:**

- The block defines the function without errors (inferred).

### Step S.5: Define deletion helper

Follow [common/wait-for-deletion.md](common/wait-for-deletion.md).

**Expected result:**

- The block defines the function without errors (inferred).

## Phase 0: OpenShift Login

### Step 0.0: Log in to OpenShift

Follow [common/openshift-login.md](common/openshift-login.md).

to log in to the target OpenShift cluster using a service account token.


**Expected result:**

- All invoked procedure steps pass (inferred).

---

## Phase 1: Environment Setup

### Step 1.0: Prepare the namespace

Follow [common/namespace-setup.md](common/namespace-setup.md).

to create and verify the namespace. This plan does not
need Keycloak.


**Expected result:**

- All invoked procedure steps pass (inferred).

---

## Phase 2: Operator Installation

### Step 2.0: Install the operator

Follow [common/operator-deployment.md](common/operator-deployment.md).

to install the operator via Helm and verify CRDs, RBAC, and health endpoints. The procedure also verifies the
`wanakusemanticrouters.wanaku.ai` CRD and the `SEMANTIC_ROUTER` controller.


**Expected result:**

- All invoked procedure steps pass (inferred).

---

## Phase 3: WanakuRouter Setup

### Test 3.1: Create a WanakuRouter with Wanaku Barn and semantic audit ingestion

**Description:** `spec.server.env` sets `WANAKU_AUDIT_INGESTION_ENABLED=true`, so that Wanaku Server accepts the
semantic audit records of the runtime (Phase 8).

Run this block as a separate `bash` script, not in the shared session.

```bash
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuRouter
metadata:
  name: ${WANAKU_ROUTER_NAME}
spec:
  imagePullPolicy: Always
  server:
    image: ${WANAKU_SERVER_IMAGE}
    env:
      - name: WANAKU_AUDIT_INGESTION_ENABLED
        value: "true"
  barn:
    enabled: true
    image: ${WANAKU_BARN_IMAGE}
    storage:
      size: 1Gi
EOF

oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"
# Expected output: wanakurouter.wanaku.ai/wanaku-test-router condition met
wait_cr_condition wanakurouter "${WANAKU_ROUTER_NAME}" BarnAvailable True Available || exit 1

INGESTION=$(oc get deployment "${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="WANAKU_AUDIT_INGESTION_ENABLED")].value}')
[ "${INGESTION}" = "true" ] && echo "PASS: semantic audit ingestion is enabled on the Server" \
  || { echo "FAIL: WANAKU_AUDIT_INGESTION_ENABLED is '${INGESTION}'"; exit 1; }
```

> [!NOTE]
> NOTE: verify that the operator puts the `spec.server.env` entries on the first container of the
> `<router>-server` Deployment. When authentication is enabled, the Server container can have another index.

**Expected result:**

- The output includes `PASS: wanakurouter/<WANAKU_ROUTER_NAME> condition BarnAvailable=True (reason Available)`.
- The output includes `PASS: semantic audit ingestion is enabled on the Server`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 3.2: Read the Route URLs and verify the Server is reachable

```bash run
export WANAKU_ROUTER_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
export WANAKU_ROUTER_MCP_URL="https://$(oc get route "${WANAKU_ROUTER_NAME}-mcp" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.spec.host}')"
echo "management: ${WANAKU_ROUTER_URL}"
echo "mcp:        ${WANAKU_ROUTER_MCP_URL}"

# curl: HTTP-level reachability check before the CLI connects
CODE=$(curl -s ${CURL_TLS_OPTS} -o /dev/null -w '%{http_code}' "${WANAKU_ROUTER_URL}/health")
[ "${CODE}" = "200" ] && echo "PASS: Wanaku Server /health returns 200" || echo "FAIL: /health returned HTTP ${CODE}"

${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain \
  && echo "PASS: the CLI reaches the management API" || echo "FAIL: forwards list failed"
```

**Expected result:**

- The output includes `PASS: Wanaku Server /health returns 200`.
- The output includes `PASS: the CLI reaches the management API`.
- The output contains no line starting with `FAIL:`.

---

## Phase 4: WanakuSemanticRouter Deployment

### Test 4.1: Create the credential Secret and the route ConfigMap

**Description:** The Secret `wsr-llm-credentials` holds the expert credential (key `api-key`), as in the operator
sample. When `TYPESAFE_API_KEY` is not set, the Secret holds a placeholder value: the runtime needs the variable at
startup, but it calls the provider only when the tool runs.

The ConfigMap `wsr-support-files` holds the support route of `semantic-router-tests/src/test/resources/support`. The
operator mounts the ConfigMap at `/opt/wsr/routes` and sets `WSR_ROUTES_REF` to `file:/opt/wsr/routes/<main>`. A
ConfigMap has no subdirectories, so the manifest uses flat references: `main=support.camel.yaml`, and the Kamelets as a
list of files (`kamelets=` accepts a comma-separated list of files in one directory). The billing Kamelet has no
`action_failure` branch, because this plan does not test action failures.

> [!NOTE]
> NOTE: verify that the runtime accepts the ConfigMap mount. Kubernetes mounts each ConfigMap key as a file symlink
> into the `..data` directory. WSR rejects only directory symlinks for Kamelets, and it reads the files through
> `Files.isRegularFile`, which follows links.

Run this block as a separate `bash` script, not in the shared session.

```bash
# The Secret value is not printed
oc create secret generic wsr-llm-credentials \
  --from-literal=api-key="${TYPESAFE_API_KEY:-placeholder-routing-tests-are-skipped}" \
  --dry-run=client -o yaml | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label secret wsr-llm-credentials wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"

FILES=$(mktemp -d)
cat > "${FILES}/semantic-router.properties" <<PROPS
contract.version=1
camel.version=${WSR_CAMEL_VERSION}
main=support.camel.yaml
kamelets=wsr-billing-action.kamelet.yaml,wsr-technical-action.kamelet.yaml
dependencies=support.dependencies.txt
input.profile=message-to-string/v1
expert.bean=supportExpert
tool.name=${WSR_TOOL}
evaluation=department
configuration=service.properties
tool.tags=wsr-support
PROPS
cat > "${FILES}/support.dependencies.txt" <<DEPS
camel:core
org.apache.camel:camel-semantic
camel:direct
org.apache.camel:camel-kamelet
org.apache.camel:camel-ai-tool
org.apache.camel:camel-mcp-server
org.apache.camel:camel-platform-http-main
mvn:org.apache.camel:camel-typesafe-ai:${WSR_CAMEL_VERSION}
DEPS
: > "${FILES}/service.properties"
cat > "${FILES}/support.camel.yaml" <<'ROUTE'
- beans:
    - name: recordContinuation
      type: org.apache.camel.semantic.SemanticAuditDecision
      properties:
        fields:
          action: continue
          operation: tools/call
          target: route_support
          reasonCode: classified
- semantic:
    evaluation:
      department:
        operation: choice
        expert: supportExpert
        state: "${body}"
        parameters:
          instructions: Select the support action that can handle this request.
          criteria:
            billing: Questions about invoices, payments, and refunds.
            technical: Questions about bugs, outages, and technical problems.
            no_match: Requests that neither support action can handle.
- route:
    id: support-mcp-entry
    from:
      uri: ai-tool:route_support
      parameters:
        description: Route a support request to billing or technical support.
        tags: wsr-support
        parameter.message: string
        parameter.message.required: "true"
        parameter.message.description: The support request to evaluate.
      steps:
        - setBody:
            simple: "${header.message}"
        - to: direct:route-support
- route:
    id: support-dispatch
    from:
      uri: direct:route-support
      steps:
        - to: direct:classify-support
        - process:
            ref: recordContinuation
        - choice:
            when:
              - simple: "${exchangeProperty.department} == 'billing'"
                steps:
                  - to: kamelet:wsr-billing-action
              - simple: "${exchangeProperty.department} == 'technical'"
                steps:
                  - to: kamelet:wsr-technical-action
            otherwise:
              steps:
                - setBody:
                    constant: No suitable support action.
- route:
    id: support-classification
    from:
      uri: direct:classify-support
      steps:
        - setProperty:
            name: department
            expression:
              language:
                language: semantic
                expression: ref:department
ROUTE
for KIND in billing technical; do
  case "${KIND}" in
    billing) TITLE="Billing support"; ANSWER="Billing demo response"; TOPIC="invoices, payments, and refunds" ;;
    technical) TITLE="Technical support"; ANSWER="Technical demo response"; TOPIC="bugs, outages, and technical problems" ;;
  esac
  cat > "${FILES}/wsr-${KIND}-action.kamelet.yaml" <<KAMELET
apiVersion: camel.apache.org/v1
kind: Kamelet
metadata:
  name: wsr-${KIND}-action
  labels:
    camel.apache.org/kamelet.type: action
  annotations:
    barn.wanaku.ai/semantic-action: "true"
    barn.wanaku.ai/routing-description: Questions about ${TOPIC}.
    barn.wanaku.ai/contract-profile: message-to-string-v1
spec:
  definition:
    title: ${TITLE}
    description: Return a local ${KIND} demonstration response.
    type: object
    properties: {}
  types:
    in:
      mediaType: text/plain
      schema:
        type: string
    out:
      mediaType: text/plain
      schema:
        type: string
  dependencies:
    - camel:core
  template:
    from:
      uri: kamelet:source
      steps:
        - setBody:
            constant: ${ANSWER}
        - to: kamelet:sink
KAMELET
done

oc create configmap wsr-support-files --from-file="${FILES}" --dry-run=client -o yaml \
  | oc apply -n "${WANAKU_NAMESPACE}" -f -
oc label configmap wsr-support-files wanaku-test=true --overwrite -n "${WANAKU_NAMESPACE}"
rm -rf "${FILES}"

FAIL=0
KEYS=$(oc get configmap wsr-support-files -n "${WANAKU_NAMESPACE}" -o json | jq -r '.data | keys | join(",")')
check "ConfigMap keys" "${KEYS}" "semantic-router.properties,service.properties,support.camel.yaml,support.dependencies.txt,wsr-billing-action.kamelet.yaml,wsr-technical-action.kamelet.yaml"
oc get secret wsr-llm-credentials -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.api-key}' | grep -q . \
  && echo "PASS: Secret wsr-llm-credentials has the api-key key" || { echo "FAIL: Secret key api-key missing"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: ConfigMap keys =` with the expected value.
- The output includes `PASS: Secret wsr-llm-credentials has the api-key key`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.2: Create the WanakuSemanticRouter

**Description:** The resource follows the operator sample, with the `configMapRef` source. The ConfigMap source does
not select an expert, so `spec.properties` enables the `supportExpert` bean with the TypeSafe AI adapter
(`wsr.experts`, `wsr.expert.supportExpert.class`). The operator passes each property to the runtime as `-p KEY=VALUE`.
The API key stays in the Secret: the property holds only the reference `{{env:TYPESAFE_API_KEY}}`.

Run this block as a separate `bash` script, not in the shared session.

```bash
OPTIONAL_PROPERTIES=""
[ -n "${TYPESAFE_BASE_URL}" ] && OPTIONAL_PROPERTIES="${OPTIONAL_PROPERTIES}
    camel.component.typesafe-ai.base-url: \"${TYPESAFE_BASE_URL}\""
[ -n "${TYPESAFE_MODEL}" ] && OPTIONAL_PROPERTIES="${OPTIONAL_PROPERTIES}
    camel.component.typesafe-ai.model: \"${TYPESAFE_MODEL}\""

cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuSemanticRouter
metadata:
  name: ${WSR_NAME}
spec:
  routerRef: ${WANAKU_ROUTER_NAME}
  image: ${WSR_IMAGE}
  imagePullPolicy: Always
  source:
    configMapRef:
      name: wsr-support-files
      main: support.camel.yaml
  forward:
    namespace: ${WSR_FORWARD_NAMESPACE}
  env:
    - name: TYPESAFE_API_KEY
      valueFrom:
        secretKeyRef:
          name: wsr-llm-credentials
          key: api-key
  properties:
    wsr.experts: supportExpert
    wsr.expert.supportExpert.class: org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter
    camel.component.typesafe-ai.api-key: "{{env:TYPESAFE_API_KEY}}"${OPTIONAL_PROPERTIES}
EOF

oc get wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" -o name
[ $? -eq 0 ] && echo "PASS: WanakuSemanticRouter ${WSR_NAME} exists" || { echo "FAIL: WanakuSemanticRouter not created"; exit 1; }
```

**Expected result:**

- The output includes `PASS: WanakuSemanticRouter <WSR_NAME> exists`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.3: Verify the WanakuSemanticRouter becomes Ready

**Description:** `Ready` requires `Reconciled` (reason `ReconcileSucceeded`), `Available` (the runtime pod is ready)
and `Registered` (Wanaku Server has an available forward named `${WSR_NAME}` with the `wsr.instance` label). The pod
is ready only after the runtime registered the forward. While the pod is not ready, `Registered` has the reason
`WaitingForRuntime`, and the operator checks again every 30 seconds.

> [!NOTE]
> NOTE: verify that `${WSR_IMAGE}` serves the health endpoints `/api/v1/health/started`, `/api/v1/health/ready` and
> `/api/v1/health/live` on port 8091. The operator probes use them. The local `wanaku-semantic-router` checkout serves
> only `/api/v1/status` on that port. Without the health endpoints, `Available` stays `False` and the startup probe
> restarts the pod.

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
wait_cr_condition wanakusemanticrouter "${WSR_NAME}" Reconciled True ReconcileSucceeded || FAIL=1
wait_cr_condition wanakusemanticrouter "${WSR_NAME}" Available True || FAIL=1
wait_cr_condition wanakusemanticrouter "${WSR_NAME}" Registered True Registered || FAIL=1
wait_cr_condition wanakusemanticrouter "${WSR_NAME}" Ready True || FAIL=1
if [ "${FAIL}" -ne 0 ]; then
  oc get pods -l "app=${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}"
  oc logs "deployment/${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" --tail=40
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: wanakusemanticrouter/<WSR_NAME> condition Reconciled=True (reason ReconcileSucceeded)`.
- The output includes `PASS: wanakusemanticrouter/<WSR_NAME> condition Available=True`.
- The output includes `PASS: wanakusemanticrouter/<WSR_NAME> condition Registered=True (reason Registered)`.
- The output includes `PASS: wanakusemanticrouter/<WSR_NAME> condition Ready=True`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.4: Verify the runtime Deployment and Service

**Description:** The operator creates the `<name>-wsr` Deployment and Service. It passes the registration settings to
the runtime as `WSR_*` environment variables, and the properties as `-p` options after the `runtime` command, in key
order.

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
DEPLOY="${WSR_NAME}-wsr"
wsr_jsonpath() { oc get deployment "${DEPLOY}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
wsr_env() { wsr_jsonpath "{.spec.template.spec.containers[0].env[?(@.name==\"$1\")].value}"; }

check "image" "$(wsr_jsonpath '{.spec.template.spec.containers[0].image}')" "${WSR_IMAGE}"
check "replicas" "$(wsr_jsonpath '{.spec.replicas}')" "1"
check "WSR_REGISTRATION_URL" "$(wsr_env WSR_REGISTRATION_URL)" "http://server-${WANAKU_ROUTER_NAME}:8080"
check "WSR_BARN_URL" "$(wsr_env WSR_BARN_URL)" "http://barn-${WANAKU_ROUTER_NAME}:8080"
check "WSR_NAME" "$(wsr_env WSR_NAME)" "${WSR_NAME}"
check "WSR_NAMESPACE" "$(wsr_env WSR_NAMESPACE)" "${WSR_FORWARD_NAMESPACE}"
check "WSR_MCP_ADDRESS" "$(wsr_env WSR_MCP_ADDRESS)" "http://${DEPLOY}.${WANAKU_NAMESPACE}.svc:8090/mcp"
check "WSR_BIND" "$(wsr_env WSR_BIND)" "0.0.0.0"
check "WSR_MCP_PORT" "$(wsr_env WSR_MCP_PORT)" "8090"
check "WSR_STATUS_PORT" "$(wsr_env WSR_STATUS_PORT)" "8091"
check "WSR_DATA_DIR" "$(wsr_env WSR_DATA_DIR)" "/home/wsr/data"
check "WSR_ROUTES_REF" "$(wsr_env WSR_ROUTES_REF)" "file:/opt/wsr/routes/support.camel.yaml"
check "TYPESAFE_API_KEY from Secret" \
  "$(wsr_jsonpath '{.spec.template.spec.containers[0].env[?(@.name=="TYPESAFE_API_KEY")].valueFrom.secretKeyRef.name}')" \
  "wsr-llm-credentials"
[ -z "$(wsr_env WSR_SEMANTIC_ROUTE)" ] && echo "PASS: no WSR_SEMANTIC_ROUTE with the configMapRef source" \
  || { echo "FAIL: WSR_SEMANTIC_ROUTE is set"; FAIL=1; }

ARGS=$(wsr_jsonpath '{.spec.template.spec.containers[0].args}')
echo "args: ${ARGS}"
check "first argument" "$(echo "${ARGS}" | jq -r '.[0]')" "runtime"
echo "${ARGS}" | jq -e 'index("wsr.experts=supportExpert") != null' > /dev/null \
  && echo "PASS: args contain -p wsr.experts=supportExpert" || { echo "FAIL: wsr.experts property missing"; FAIL=1; }

check "readiness probe" "$(wsr_jsonpath '{.spec.template.spec.containers[0].readinessProbe.httpGet.path}')" "/api/v1/health/ready"
check "startup probe" "$(wsr_jsonpath '{.spec.template.spec.containers[0].startupProbe.httpGet.path}')" "/api/v1/health/started"
check "liveness probe" "$(wsr_jsonpath '{.spec.template.spec.containers[0].livenessProbe.httpGet.path}')" "/api/v1/health/live"
check "strategy" "$(wsr_jsonpath '{.spec.strategy.type}')" "RollingUpdate"
check "maxUnavailable" "$(wsr_jsonpath '{.spec.strategy.rollingUpdate.maxUnavailable}')" "0"
check "routes volume" "$(wsr_jsonpath '{.spec.template.spec.volumes[?(@.name=="routes")].configMap.name}')" "wsr-support-files"
check "owner kind" "$(wsr_jsonpath '{.metadata.ownerReferences[0].kind}')" "WanakuSemanticRouter"

svc_jsonpath() { oc get service "${DEPLOY}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
check "Service port" "$(svc_jsonpath '{.spec.ports[0].port}')" "8090"
check "Service port name" "$(svc_jsonpath '{.spec.ports[0].name}')" "mcp"
check "publishNotReadyAddresses" "$(svc_jsonpath '{.spec.publishNotReadyAddresses}')" "true"
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: image =` with the expected value.
- The output includes `PASS: replicas =` with the expected value.
- The output includes `PASS: WSR_REGISTRATION_URL =` with the expected value.
- The output includes `PASS: WSR_BARN_URL =` with the expected value.
- The output includes `PASS: WSR_NAME =` with the expected value.
- The output includes `PASS: WSR_NAMESPACE =` with the expected value.
- The output includes `PASS: WSR_MCP_ADDRESS =` with the expected value.
- The output includes `PASS: WSR_BIND =` with the expected value.
- The output includes `PASS: WSR_MCP_PORT =` with the expected value.
- The output includes `PASS: WSR_STATUS_PORT =` with the expected value.
- The output includes `PASS: WSR_DATA_DIR =` with the expected value.
- The output includes `PASS: WSR_ROUTES_REF =` with the expected value.
- The output includes `PASS: TYPESAFE_API_KEY from Secret =` with the expected value.
- The output includes `PASS: no WSR_SEMANTIC_ROUTE with the configMapRef source`.
- The output includes `PASS: first argument =` with the expected value.
- The output includes `PASS: args contain -p wsr.experts=supportExpert`.
- The output includes `PASS: readiness probe =` with the expected value.
- The output includes `PASS: startup probe =` with the expected value.
- The output includes `PASS: liveness probe =` with the expected value.
- The output includes `PASS: strategy =` with the expected value.
- The output includes `PASS: maxUnavailable =` with the expected value.
- The output includes `PASS: routes volume =` with the expected value.
- The output includes `PASS: owner kind =` with the expected value.
- The output includes `PASS: Service port =` with the expected value.
- The output includes `PASS: Service port name =` with the expected value.
- The output includes `PASS: publishNotReadyAddresses =` with the expected value.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 4.5: Verify the resource status and the runtime status endpoint

**Description:** The resource status describes the registration: `status.forwardName`, `status.mcpAddress` and
`status.wanakuEndpoint`. Test 6.1 covers `status.wanakuEndpoint`. The runtime publishes its own status at
`/api/v1/status` on port 8091. The Service does not expose that port, so the test uses `oc port-forward`. For the
ConfigMap source, `source` is `local-files` and `revision` is `local`. The `digest` is a SHA-256 value of the snapshot
resources.

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
cr_jsonpath() { oc get wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath="$1"; }
check "status.forwardName" "$(cr_jsonpath '{.status.forwardName}')" "${WSR_NAME}"
check "status.mcpAddress" "$(cr_jsonpath '{.status.mcpAddress}')" "http://${WSR_NAME}-wsr.${WANAKU_NAMESPACE}.svc:8090/mcp"
check "status.observedGeneration" "$(cr_jsonpath '{.status.observedGeneration}')" "$(cr_jsonpath '{.metadata.generation}')"
echo "status.wanakuEndpoint=$(cr_jsonpath '{.status.wanakuEndpoint}')"

LOCAL_PORT=18091
oc port-forward "deployment/${WSR_NAME}-wsr" "${LOCAL_PORT}:8091" -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 &
PF_PID=$!
STATUS=""
for i in $(seq 1 20); do
  STATUS=$(curl -s "http://127.0.0.1:${LOCAL_PORT}/api/v1/status") && [ -n "${STATUS}" ] && break
  sleep 1
done
kill "${PF_PID}" 2>/dev/null
echo "${STATUS}" | jq '{state, source, revision, digest, forwardName, audit: {enabled: .audit.enabled}}'

check "runtime state" "$(echo "${STATUS}" | jq -r '.state')" "ready"
check "runtime source" "$(echo "${STATUS}" | jq -r '.source')" "local-files"
check "runtime revision" "$(echo "${STATUS}" | jq -r '.revision')" "local"
check "runtime forwardName" "$(echo "${STATUS}" | jq -r '.forwardName')" "${WSR_NAME}"
check "runtime audit enabled" "$(echo "${STATUS}" | jq -r '.audit.enabled')" "true"
echo "${STATUS}" | jq -r '.digest' | grep -qE '^[a-f0-9]{64}$' \
  && echo "PASS: runtime digest is a SHA-256 value" || { echo "FAIL: runtime digest is not a SHA-256 value"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: status.forwardName =` with the expected value.
- The output includes `PASS: status.mcpAddress =` with the expected value.
- The output includes `PASS: status.observedGeneration =` with the expected value.
- The output includes `PASS: runtime state =` with the expected value.
- The output includes `PASS: runtime source =` with the expected value.
- The output includes `PASS: runtime revision =` with the expected value.
- The output includes `PASS: runtime forwardName =` with the expected value.
- The output includes `PASS: runtime audit enabled =` with the expected value.
- The output includes `PASS: runtime digest is a SHA-256 value`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 5: Registration in Wanaku Server

### Test 5.1: Verify the forward in Wanaku Server

**Description:** The runtime registered the forward `${WSR_NAME}` in the Wanaku namespace `default`. The forward has
the labels `wsr.instance` (the runtime instance), `wsr.revision` (`local`) and `wsr.digest`. The CLI does not print
forward labels, so `curl` reads `GET /api/v1/forwards/{name}`.

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "^${WSR_NAME}[[:space:]]*http://${WSR_NAME}-wsr.${WANAKU_NAMESPACE}.svc:8090/mcp" \
  && echo "PASS: forward ${WSR_NAME} is listed with the runtime address" \
  || { echo "FAIL: forward ${WSR_NAME} not listed"; exit 1; }

FORWARD=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/forwards/${WSR_NAME}")
echo "${FORWARD}" | jq '.data | {name, namespace, available, labels}'
FAIL=0
check "forward namespace" "$(echo "${FORWARD}" | jq -r '.data.namespace')" "${WSR_FORWARD_NAMESPACE}"
check "forward available" "$(echo "${FORWARD}" | jq -r '.data.available')" "true"
check "forward label wsr.revision" "$(echo "${FORWARD}" | jq -r '.data.labels["wsr.revision"]')" "local"
[ -n "$(echo "${FORWARD}" | jq -r '.data.labels["wsr.instance"] // empty')" ] \
  && echo "PASS: forward label wsr.instance is set" || { echo "FAIL: forward label wsr.instance missing"; FAIL=1; }
echo "${FORWARD}" | jq -r '.data.labels["wsr.digest"] // empty' | grep -qE '^[a-f0-9]{64}$' \
  && echo "PASS: forward label wsr.digest is a SHA-256 value" || { echo "FAIL: forward label wsr.digest missing"; FAIL=1; }
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: forward <WSR_NAME> is listed with the runtime address`.
- The output includes `PASS: forward namespace =` with the expected value.
- The output includes `PASS: forward available =` with the expected value.
- The output includes `PASS: forward label wsr.revision =` with the expected value.
- The output includes `PASS: forward label wsr.instance is set`.
- The output includes `PASS: forward label wsr.digest is a SHA-256 value`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 5.2: Verify the discovered tool

**Description:** The runtime serves one tool, `route_support`. Wanaku Server lists it in the management API and serves
it at `/default/mcp`.

Run this block as a separate `bash` script, not in the shared session.

```bash
wait_for_tool "${WSR_TOOL}" || exit 1

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/${WSR_FORWARD_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "${WSR_TOOL}" && echo "PASS: ${WSR_TOOL} is listed at /${WSR_FORWARD_NAMESPACE}/mcp" \
  || { echo "FAIL: ${WSR_TOOL} not listed at /${WSR_FORWARD_NAMESPACE}/mcp"; exit 1; }
```

**Expected result:**

- The output includes `PASS: tool route_support is registered in Wanaku Server (attempt <N>)`.
- The output includes `PASS: route_support is listed at /default/mcp`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 6: Known Issue

### Test 6.1: Record status.wanakuEndpoint for the default namespace (known issue)

**Description:** For the forward namespace `default`, the operator copies the router `status.endpoints.mcp` value into
`status.wanakuEndpoint`. The router publishes that value as `https://<mcp-route-host>/mcp`, without a Wanaku
namespace. An earlier review found that Wanaku Server rejects the bare `/mcp` path: MCP clients must use
`${WANAKU_ROUTER_MCP_URL}/default/mcp`. For other namespaces, the operator replaces `/mcp` with `/<namespace>/mcp`.

This test records the current value and the Server response. It does not fail on the known issue. It prints a
`KNOWN ISSUE:` line while the issue is open, and an `INFO:` line when the endpoint works.

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
ROUTER_MCP_ENDPOINT=$(oc get wanakurouter "${WANAKU_ROUTER_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.endpoints.mcp}')
WSR_ENDPOINT=$(oc get wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.status.wanakuEndpoint}')
echo "router status.endpoints.mcp:   ${ROUTER_MCP_ENDPOINT}"
echo "WSR status.wanakuEndpoint:     ${WSR_ENDPOINT}"
check "router status.endpoints.mcp" "${ROUTER_MCP_ENDPOINT}" "${WANAKU_ROUTER_MCP_URL}/mcp"
check "status.wanakuEndpoint (default namespace)" "${WSR_ENDPOINT}" "${ROUTER_MCP_ENDPOINT}"

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WSR_ENDPOINT}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}" | tail -5
if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "${WSR_TOOL}"; then
  echo "INFO: ${WSR_ENDPOINT} serves ${WSR_TOOL}. The known issue appears fixed. Update this test."
else
  echo "KNOWN ISSUE: status.wanakuEndpoint ${WSR_ENDPOINT} has no Wanaku namespace and Wanaku Server rejects it (exit code ${EXIT_CODE}). Use ${WANAKU_ROUTER_MCP_URL}/${WSR_FORWARD_NAMESPACE}/mcp."
fi
[ "${FAIL}" -eq 0 ] || exit 1
```

> [!WARNING]
> **Known issue:** the operator publishes `status.endpoints.mcp` as `<mcp-route>/mcp` without a namespace, and
> `WanakuSemanticRouter` `status.wanakuEndpoint` repeats it for the `default` namespace. Wanaku Server rejects that
> path. Report the issue if it is still open. When the operator changes the value (for example to
> `<mcp-route>/default/mcp`), the two `check` lines fail on purpose: update the expected values.

**Expected result:**

- The output includes `PASS: router status.endpoints.mcp =` with the expected value.
- The output includes `PASS: status.wanakuEndpoint (default namespace) =` with the expected value.
- The output includes `KNOWN ISSUE: status.wanakuEndpoint` or `INFO: <WSR_ENDPOINT> serves route_support. The known issue appears fixed. Update this test.`
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 7: Governance and Semantic Routing

### Test 7.1: Verify the fail-safe default denies the tool call

**Description:** Without a governance configuration, Wanaku Server denies `tools/call` before it forwards the call.
The expert provider is not called, so this test also runs when `TYPESAFE_API_KEY` is not set.

Run this block as a separate `bash` script, not in the shared session.

```bash
OUTPUT=$(${WANAKU_CLI} mcp tool --uri "${WANAKU_ROUTER_MCP_URL}/${WSR_FORWARD_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth \
  --name "${WSR_TOOL}" --param message=billing 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
if echo "${OUTPUT}" | grep -qE "No governance policy permits this action|evaluator: no_match"; then
  echo "PASS: the tool call is denied by the fail-safe governance default (exit code ${EXIT_CODE})"
else
  echo "FAIL: the tool call was not denied"
  exit 1
fi
```

**Expected result:**

- The output includes `PASS: the tool call is denied by the fail-safe governance default (exit code <EXIT_CODE>)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.2: Apply the test governance configuration (test-only workaround)

**Description:** Add a `governance` section to the operator-generated `wanaku.yaml` that allows unmatched actions only
in the Wanaku namespace `${WSR_FORWARD_NAMESPACE}`. The `plugins` section stays. The ConfigMap is mounted with
`subPath`, so the running pod does not see the change: restart the Server. The registry (forwards and tools) is on the
PVC, so it survives the restart. The runtime keeps running, so the `wsr.instance` label does not change.

Run this block as a separate `bash` script, not in the shared session.

```bash
CM="${WANAKU_ROUTER_NAME}-server-config"
GOV_FILE=$(mktemp)
oc get configmap "${CM}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}' > "${GOV_FILE}"
echo "" >> "${GOV_FILE}"
if ! grep -q "^governance:" "${GOV_FILE}"; then
  cat >> "${GOV_FILE}" <<GOV
governance:
  default:
    mode: enforce
    no_match: deny
    on_failure: deny
  namespaces:
    ${WSR_FORWARD_NAMESPACE}:
      no_match: allow
GOV
fi
cat "${GOV_FILE}"

oc set data "configmap/${CM}" -n "${WANAKU_NAMESPACE}" --from-file=wanaku.yaml="${GOV_FILE}"
rm -f "${GOV_FILE}"

oc get configmap "${CM}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}' | grep -q "^governance:" \
  && echo "PASS: governance section added" || { echo "FAIL: governance section missing"; exit 1; }
oc get configmap "${CM}" -n "${WANAKU_NAMESPACE}" -o jsonpath='{.data.wanaku\.yaml}' | grep -q "wanaku-barn-api" \
  && echo "PASS: plugins section kept" || { echo "FAIL: plugins section lost"; exit 1; }

oc rollout restart "deployment/${WANAKU_ROUTER_NAME}-server" -n "${WANAKU_NAMESPACE}"
oc rollout status "deployment/${WANAKU_ROUTER_NAME}-server" --timeout=180s -n "${WANAKU_NAMESPACE}"
oc wait "wanakurouter/${WANAKU_ROUTER_NAME}" --for=condition=Ready --timeout=180s -n "${WANAKU_NAMESPACE}"

# An invalid governance section stops the Server at startup, so a Ready Server also validates the YAML
wait_for_tool "${WSR_TOOL}" || exit 1
wait_cr_condition wanakusemanticrouter "${WSR_NAME}" Registered True Registered || exit 1
```

**Expected result:**

- The output includes `PASS: governance section added`.
- The output includes `PASS: plugins section kept`.
- The output includes `PASS: tool route_support is registered in Wanaku Server (attempt <N>)`.
- The output includes `PASS: wanakusemanticrouter/<WSR_NAME> condition Registered=True (reason Registered)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 7.3: Route billing, technical and unclassified requests through the MCP Route

**Description:** The test uses the three cases of `SemanticRouterITCase`. Each message starts with the case word, so
the deterministic fixture provider of the local test (it selects the label from the start of the text) and a real model
give the same label. The expected answers come from the Kamelets and from the `otherwise` branch of the route:

| Message | Expected label | Expected answer |
|---------|----------------|-----------------|
| `billing: I was charged twice on my last invoice and I need a refund` | `billing` | `Billing demo response` |
| `technical: the service has an outage and the login page shows an error` | `technical` | `Technical demo response` |
| `unclassified: what is the capital of France` | `no_match` | `No suitable support action.` |

The test is skipped when `TYPESAFE_API_KEY` is not set. A real model can classify a message differently from the
fixture. When the test fails with a real model, compare the label in the semantic audit records (Test 8.2) before you
report a defect.

Run this block as a separate `bash` script, not in the shared session.

```bash
if [ "${WSR_ROUTING_ENABLED}" != "true" ]; then
  echo "SKIP: TYPESAFE_API_KEY is not set -- skip Test 7.3 and Test 8.2"
  exit 0
fi

FAIL=0
invoke() { # $1 = message, $2 = expected answer
  OUTPUT=$(${WANAKU_CLI} mcp tool --uri "${WANAKU_ROUTER_MCP_URL}/${WSR_FORWARD_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth \
    --name "${WSR_TOOL}" --param "message=$1" 2>&1)
  EXIT_CODE=$?
  echo "${OUTPUT}"
  if [ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -qF "$2"; then
    echo "PASS: '$1' returned '$2'"
  else
    echo "FAIL: '$1' did not return '$2' (exit code ${EXIT_CODE})"
    FAIL=1
  fi
}
invoke "billing: I was charged twice on my last invoice and I need a refund" "Billing demo response"
invoke "technical: the service has an outage and the login page shows an error" "Technical demo response"
invoke "unclassified: what is the capital of France" "No suitable support action."
[ "${FAIL}" -eq 0 ] || { oc logs "deployment/${WSR_NAME}-wsr" -n "${WANAKU_NAMESPACE}" --tail=30; exit 1; }
```

**Expected result:**

- The output includes `PASS: 'billing: I was charged twice on my last invoice and I need a refund' returned 'Billing demo response'` or `SKIP: TYPESAFE_API_KEY is not set -- skip Test 7.3 and Test 8.2`.
- The output includes `PASS: 'technical: the service has an outage and the login page shows an error' returned 'Technical demo response'` or `SKIP: TYPESAFE_API_KEY is not set -- skip Test 7.3 and Test 8.2`.
- The output includes `PASS: 'unclassified: what is the capital of France' returned 'No suitable support action.'` or `SKIP: TYPESAFE_API_KEY is not set -- skip Test 7.3 and Test 8.2`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 8: Audit

### Test 8.1: Verify the governance decisions in the audit trail

**Description:** Wanaku Server records each governance decision. The denied call of Test 7.1 has `decision` `block`
and `reason_code` `governance_no_match`. When Test 7.3 ran, each allowed call has `decision` `allow` with the same
reason code, because the `no_match: allow` posture allowed it. The CLI has no audit command, so `curl` reads
`GET /api/v1/audit/events`. The response is
`{"data":{"events":[...],"limit":..,"offset":..,"total":..},"error":null}`.

Run this block as a separate `bash` script, not in the shared session.

```bash
FAIL=0
EVENTS=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/audit/events?target=${WSR_TOOL}&operation=tools/call")
echo "${EVENTS}" | jq -e '.data.events' > /dev/null 2>&1 && echo "PASS: audit events endpoint returns events" \
  || { echo "FAIL: unexpected audit response: ${EVENTS}"; exit 1; }
echo "${EVENTS}" | jq -c '[.data.events[] | {operation, target, decision, reason_code, namespace}]'

BLOCKED=$(echo "${EVENTS}" | jq '[.data.events[] | select(.decision=="block" and .reason_code=="governance_no_match")] | length')
[ "${BLOCKED}" -ge 1 ] && echo "PASS: the denied ${WSR_TOOL} call is in the audit trail (${BLOCKED} event(s))" \
  || { echo "FAIL: no blocked tools/call event for ${WSR_TOOL}"; FAIL=1; }

if [ "${WSR_ROUTING_ENABLED}" = "true" ]; then
  ALLOWED=$(echo "${EVENTS}" | jq '[.data.events[] | select(.decision=="allow" and .reason_code=="governance_no_match")] | length')
  [ "${ALLOWED}" -ge 3 ] && echo "PASS: the allowed ${WSR_TOOL} calls are in the audit trail (${ALLOWED} event(s))" \
    || { echo "FAIL: expected at least 3 allowed tools/call events, found ${ALLOWED}"; FAIL=1; }
else
  echo "SKIP: no allowed calls to check (TYPESAFE_API_KEY is not set)"
fi
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: audit events endpoint returns events`.
- The output includes `PASS: the denied route_support call is in the audit trail (<BLOCKED> event(s))`.
- The output includes `PASS: the allowed route_support calls are in the audit trail (<ALLOWED> event(s))` or `SKIP: no allowed calls to check (TYPESAFE_API_KEY is not set)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test 8.2: Verify the semantic evaluation and decision records

**Description:** The runtime captures the Camel semantic audit records and sends them to Wanaku Server
(`/api/v1/audit/semantic`). Delivery is asynchronous. Each call of Test 7.3 gives one pair: an `evaluation` record (the
label that the expert selected, expert `supportExpert`) and a `decision` record (the `recordContinuation` bean: action
`continue`, target `route_support`). The decision record refers to the evaluation record through
`source.evidence`. Wanaku Server stores the records with category `semantic`, decision `observed` and protocol
`camel-semantic`. The records contain no submitted content (`attributes.semantic.input` is not set). The test finds the
records by the `wsr.instance` label of the forward, as `SemanticRouterITCase` does.

Run this block as a separate `bash` script, not in the shared session.

```bash
if [ "${WSR_ROUTING_ENABLED}" != "true" ]; then
  echo "SKIP: TYPESAFE_API_KEY is not set -- no semantic records to check"
  exit 0
fi

INSTANCE=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/forwards/${WSR_NAME}" | jq -r '.data.labels["wsr.instance"]')
echo "wsr.instance=${INSTANCE}"
EVENTS=""
for i in $(seq 1 30); do
  EVENTS=$(curl -s ${CURL_TLS_OPTS} "${WANAKU_ROUTER_URL}/api/v1/audit/events?category=semantic&source_instance_id=${INSTANCE}")
  [ "$(echo "${EVENTS}" | jq '.data.events | length')" -ge 6 ] && break
  sleep 2
done
echo "${EVENTS}" | jq -c '[.data.events[] | {category, decision, protocol,
  semantic: .attributes.semantic | {category, result, expert, action, target},
  source: .source | {event_id, evidence, forward_name, namespace, catalog_revision}}]'

FAIL=0
ev() { echo "${EVENTS}" | jq -r "$1"; }
check "semantic record count" "$(ev '.data.events | length')" "6"
check "evaluation records" "$(ev '[.data.events[] | select(.attributes.semantic.category=="evaluation")] | length')" "3"
check "decision records" "$(ev '[.data.events[] | select(.attributes.semantic.category=="decision")] | length')" "3"
check "evaluation labels" "$(ev '[.data.events[] | select(.attributes.semantic.category=="evaluation") | .attributes.semantic.result.value] | sort | join(",")')" "billing,no_match,technical"
check "evaluation experts" "$(ev '[.data.events[] | select(.attributes.semantic.category=="evaluation") | .attributes.semantic.expert] | unique | join(",")')" "supportExpert"
check "decision actions" "$(ev '[.data.events[] | select(.attributes.semantic.category=="decision") | .attributes.semantic.action + ":" + .attributes.semantic.target] | unique | join(",")')" "continue:route_support"
check "stored category" "$(ev '[.data.events[].category] | unique | join(",")')" "semantic"
check "stored decision" "$(ev '[.data.events[].decision] | unique | join(",")')" "observed"
check "stored protocol" "$(ev '[.data.events[].protocol] | unique | join(",")')" "camel-semantic"
check "source forward_name" "$(ev '[.data.events[].source.forward_name] | unique | join(",")')" "${WSR_NAME}"
check "source namespace" "$(ev '[.data.events[].source.namespace] | unique | join(",")')" "${WSR_FORWARD_NAMESPACE}"
check "source catalog_revision" "$(ev '[.data.events[].source.catalog_revision] | unique | join(",")')" "local"
check "producer_asserted" "$(ev '[.data.events[].source.producer_asserted] | unique | map(tostring) | join(",")')" "true"
check "records with input" "$(ev '[.data.events[] | select(.attributes.semantic | has("input"))] | length')" "0"
# Each decision refers to exactly one evaluation of this runtime
check "decisions linked to an evaluation" "$(ev '([.data.events[] | select(.attributes.semantic.category=="evaluation") | .source.event_id]) as $ids
  | [.data.events[] | select(.attributes.semantic.category=="decision") | select((.source.evidence | length) == 1 and (.source.evidence[0] as $e | $ids | index($e)) != null)] | length')" "3"
echo "${EVENTS}" | grep -q "charged twice" && { echo "FAIL: a record contains submitted content"; FAIL=1; } \
  || echo "PASS: no record contains submitted content"
[ "${FAIL}" -eq 0 ] || exit 1
```

> [!NOTE]
> The expected counts assume that Test 7.3 ran once against this runtime instance. When you repeat Test 7.3, the
> counts grow by 2 for each call. With a real model, `evaluation labels` shows the labels that the model selected.

**Expected result:**

- The output includes `PASS: semantic record count =` with the expected value, or `SKIP: TYPESAFE_API_KEY is not set -- no semantic records to check`.
- The output includes `PASS: evaluation records =` with the expected value.
- The output includes `PASS: decision records =` with the expected value.
- The output includes `PASS: evaluation labels =` with the expected value.
- The output includes `PASS: evaluation experts =` with the expected value.
- The output includes `PASS: decision actions =` with the expected value.
- The output includes `PASS: stored category =` with the expected value.
- The output includes `PASS: stored decision =` with the expected value.
- The output includes `PASS: stored protocol =` with the expected value.
- The output includes `PASS: source forward_name =` with the expected value.
- The output includes `PASS: source namespace =` with the expected value.
- The output includes `PASS: source catalog_revision =` with the expected value.
- The output includes `PASS: producer_asserted =` with the expected value.
- The output includes `PASS: records with input =` with the expected value.
- The output includes `PASS: decisions linked to an evaluation =` with the expected value.
- The output includes `PASS: no record contains submitted content`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 9: Negative Test

### Test 9.1: A WanakuSemanticRouter with a non-existent routerRef does not become Ready

**Description:** The reconciler validates the resource before it creates a workload. When `spec.routerRef` names no
`WanakuRouter` in the namespace, it sets `Reconciled=False` with the reason `ValidationError` and the message
`WanakuRouter <routerRef> does not exist in namespace <namespace>`. `Ready` copies the first failed condition:
`Ready=False`, reason `ValidationError`, message `Reconciled: <message>`. The operator creates no Deployment and no
Service, and records a `Warning` event with the reason `ValidationError`.

Run this block as a separate `bash` script, not in the shared session.

```bash
cat <<EOF | oc apply -n "${WANAKU_NAMESPACE}" -f -
apiVersion: "wanaku.ai/v1alpha1"
kind: WanakuSemanticRouter
metadata:
  name: wsr-bad-router
spec:
  routerRef: non-existent-router
  source:
    configMapRef:
      name: wsr-support-files
      main: support.camel.yaml
EOF

FAIL=0
wait_cr_condition wanakusemanticrouter wsr-bad-router Reconciled False ValidationError || FAIL=1
wait_cr_condition wanakusemanticrouter wsr-bad-router Ready False ValidationError || FAIL=1
MESSAGE=$(oc get wanakusemanticrouter wsr-bad-router -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="Reconciled")].message}')
check "Reconciled message" "${MESSAGE}" "WanakuRouter non-existent-router does not exist in namespace ${WANAKU_NAMESPACE}"
READY_MESSAGE=$(oc get wanakusemanticrouter wsr-bad-router -n "${WANAKU_NAMESPACE}" \
  -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}')
check "Ready message" "${READY_MESSAGE}" "Reconciled: ${MESSAGE}"
for KIND in deployment service; do
  oc get "${KIND}" wsr-bad-router-wsr -n "${WANAKU_NAMESPACE}" > /dev/null 2>&1 \
    && { echo "FAIL: ${KIND} wsr-bad-router-wsr was created for an invalid resource"; FAIL=1; } \
    || echo "PASS: no ${KIND} for the invalid resource"
done
oc get events -n "${WANAKU_NAMESPACE}" --field-selector involvedObject.name=wsr-bad-router,reason=ValidationError -o name \
  | grep -q . && echo "PASS: a ValidationError warning event is recorded" \
  || echo "WARN: no ValidationError event found (the operator ignores event write failures)"

oc delete wanakusemanticrouter wsr-bad-router -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: wanakusemanticrouter/wsr-bad-router condition Reconciled=False (reason ValidationError)`.
- The output includes `PASS: wanakusemanticrouter/wsr-bad-router condition Ready=False (reason ValidationError)`.
- The output includes `PASS: Reconciled message =` with the expected value.
- The output includes `PASS: Ready message =` with the expected value.
- The output includes `PASS: no deployment for the invalid resource`.
- The output includes `PASS: no service for the invalid resource`.
- The output includes `PASS: a ValidationError warning event is recorded` or `WARN: no ValidationError event found (the operator ignores event write failures)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 10: Deletion

### Test 10.1: Delete the WanakuSemanticRouter and verify the forward and tool are removed

**Description:** Kubernetes deletes the runtime Deployment and Service through the owner reference. The pod has a
termination grace period of 60 seconds. At shutdown, the runtime removes its forward, because the `wsr.instance` label
still belongs to it. Wanaku Server then no longer serves `route_support`, as in
`SemanticRouterITCase.shouldDeregisterForwardAndToolOnGracefulShutdown`.

Run this block as a separate `bash` script, not in the shared session.

```bash
oc delete wanakusemanticrouter "${WSR_NAME}" -n "${WANAKU_NAMESPACE}" --ignore-not-found=true

FAIL=0
wait_for_deletion deployment "${WSR_NAME}-wsr" "${WANAKU_NAMESPACE}" 120 || FAIL=1
wait_for_deletion service "${WSR_NAME}-wsr" "${WANAKU_NAMESPACE}" 60 || FAIL=1

GONE=false
for i in $(seq 1 30); do
  if ! ${WANAKU_CLI} forwards list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>/dev/null \
      | grep -q "^${WSR_NAME}[[:space:]]"; then
    GONE=true
    break
  fi
  sleep 3
done
[ "${GONE}" = "true" ] && echo "PASS: forward ${WSR_NAME} removed by the runtime at shutdown" \
  || { echo "FAIL: forward ${WSR_NAME} is still registered"; FAIL=1; }

OUTPUT=$(${WANAKU_CLI} tools list --host "${WANAKU_ROUTER_URL}" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "${WSR_TOOL}" \
  && { echo "FAIL: ${WSR_TOOL} is still registered in Wanaku Server"; FAIL=1; } \
  || echo "PASS: ${WSR_TOOL} is no longer registered in Wanaku Server"

OUTPUT=$(${WANAKU_CLI} mcp tool list --uri "${WANAKU_ROUTER_MCP_URL}/${WSR_FORWARD_NAMESPACE}/mcp" ${WANAKU_CLI_TLS_OPTS} --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "${WSR_TOOL}" \
  && { echo "FAIL: ${WSR_TOOL} is still served at /${WSR_FORWARD_NAMESPACE}/mcp"; FAIL=1; } \
  || echo "PASS: ${WSR_TOOL} is no longer served at /${WSR_FORWARD_NAMESPACE}/mcp"
[ "${FAIL}" -eq 0 ] || exit 1
```

**Expected result:**

- The output includes `PASS: deployment/<WSR_NAME>-wsr deleted (<N>s)`.
- The output includes `PASS: service/<WSR_NAME>-wsr deleted (<N>s)`.
- The output includes `PASS: forward <WSR_NAME> removed by the runtime at shutdown`.
- The output includes `PASS: route_support is no longer registered in Wanaku Server`.
- The output includes `PASS: route_support is no longer served at /default/mcp`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

---

## Phase 11: Cleanup

### Step 11.0: Delete remaining resources

Delete the leftover resources of this plan. Then run the full teardown in Step 11.1.

```bash run
oc delete wanakusemanticrouter --all -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete configmap -l wanaku-test=true -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
oc delete secret -l wanaku-test=true -n "${WANAKU_NAMESPACE}" --ignore-not-found=true
echo "PASS: semantic router test resources deleted"
```

**Expected result:**

- The output includes `PASS: semantic router test resources deleted`.

---

### Step 11.1: Run full teardown

Follow [common/cleanup.md](common/cleanup.md).

**Expected result:**

- All invoked procedure steps pass (inferred).

## Test Summary Matrix

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 0 | — | OpenShift login | Critical |
| 1 | — | Namespace setup | Critical |
| 2 | — | Operator installation | Critical |
| 3 | 3.1-3.2 | WanakuRouter with Barn and audit ingestion, Routes reachable | Critical |
| 4 | 4.1-4.3 | WanakuSemanticRouter from a ConfigMap becomes Ready (Registered) | Critical |
| 4 | 4.4 | Runtime Deployment and Service | High |
| 4 | 4.5 | Resource status and runtime status endpoint | High |
| 5 | 5.1 | Self-registered forward with `wsr.*` labels | Critical |
| 5 | 5.2 | Tool discovery (`route_support`) | Critical |
| 6 | 6.1 | `status.wanakuEndpoint` without a namespace (known issue) | Medium |
| 7 | 7.1 | Fail-safe governance denies the tool call | Critical |
| 7 | 7.2 | Test governance configuration (test-only workaround) | Critical |
| 7 | 7.3 | Semantic routing: billing, technical, unclassified (skipped without `TYPESAFE_API_KEY`) | Critical |
| 8 | 8.1 | Governance decisions in the audit trail | Medium |
| 8 | 8.2 | Semantic evaluation and decision records (skipped without `TYPESAFE_API_KEY`) | High |
| 9 | 9.1 | Non-existent routerRef: `ValidationError`, not Ready | High |
| 10 | 10.1 | Deletion removes the runtime, its forward and its tool | Critical |
| 11 | — | Cleanup | Critical |
| Setup | S.1 | Verify prerequisites | Setup |
| Setup | S.2 | Set environment variables | Setup |
| Setup | S.3 | Define condition and assertion helpers | Setup |
| Setup | S.4 | Define tool discovery helper | Setup |
| Setup | S.5 | Define deletion helper | Setup |
| Setup | 0.0 | Log in to OpenShift | Setup |
| Setup | 1.0 | Prepare the namespace | Setup |
| Setup | 2.0 | Install the operator | Setup |
| Setup | 11.0 | Delete remaining resources | Setup |
| Setup | 11.1 | Run full teardown | Setup |
