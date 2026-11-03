# Writing Test Plans

Test plans are Markdown documents under `plans/` designed for execution by both humans and AI agents.

## Structure

A test plan has three layers:

1. **Main plan** (`plans/<name>.md`) — the test scenario with phases and assertions.
2. **Common steps** (`plans/common/*.md`) — reusable procedures shared across plans (Keycloak setup, namespace creation, cleanup).
3. **Environment variables** — all configurable values declared upfront so the same plan works across versions and clusters.

For OpenShift-based plans, the namespace/project name must default to a unique value per run so multiple plans can execute in the same cluster without colliding. If a shared namespace is required, it should be set explicitly by the operator of the test run.

## Phases, not scripts

Organize tests into numbered phases that run sequentially. Each phase groups related assertions. This makes it easy to skip phases, resume after failure, or run a subset.

```text
Phase 0: Manual prerequisites
Phase 1: Environment setup
Phase 2-N: Test scenarios
Phase N+1: Cleanup
```

## Every step must be verifiable

Each step needs: a command, an expected outcome, and a PASS/FAIL assertion. Avoid steps that only run a command without checking the result.

```bash
# Bad — runs but doesn't verify
oc apply -f my-resource.yaml

# Good — verifies the outcome
oc apply -f my-resource.yaml
oc wait myresource/name --for=condition=Ready --timeout=120s -n "${NAMESPACE}"
```

## Prefer polling over sleeping

Use `oc wait`, `oc rollout status`, or a polling loop instead of fixed `sleep` calls. Clusters vary in speed — a 10s sleep that works locally may fail in CI and waste time on fast clusters.

```bash
# Bad
sleep 15
oc get deployment foo

# Good — deterministic wait
oc wait --for=condition=Available deployment/foo --timeout=120s -n "${NAMESPACE}"

# Good — polling for deletion
while oc get deployment foo -n "${NAMESPACE}" > /dev/null 2>&1; do
  sleep 3
done
```

## Parametrize images and versions

Never hard-code image tags. Define environment variables with sensible defaults so the same plan can test different releases.

```bash
export MY_IMAGE="${MY_IMAGE:-quay.io/org/component:latest}"
```

Then use `${MY_IMAGE}` in CR specs and image assertions.

## Extract reusable steps into common docs

If a procedure appears in more than one plan (or is likely to), move it to `plans/common/`. The common doc should:

- List its prerequisites and required environment variables.
- Document output variables it sets for subsequent steps.
- Be self-contained — runnable without reading the main plan.

Reference common docs from the main plan with a link and a brief note on what variables must be set before and after:

```markdown
Follow [common/keycloak-setup.md](common/keycloak-setup.md). After completion,
`KEYCLOAK_URL` and `WANAKU_OIDC_SECRET` must be set.
```

## Keep the main plan lean

The main plan should only contain logic unique to that test scenario. If a step duplicates a common doc, replace it with a reference. This prevents drift between documents.

## Use the Wanaku CLI, not raw API clients

Test plans **must** use the Wanaku CLI for all interactions with Wanaku Server, Wanaku Barn and Keycloak. Use either a native binary installed on the system or — preferably — the CLI jar file built from the wanaku-barn codebase (`java -jar apps/wanaku-cli/target/quarkus-app/quarkus-run.jar`). For Keycloak administration (users, credentials, realm), use the standalone `wanaku-keycloak-admin` binary (`java -jar apps/wanaku-keycloak-admin/target/quarkus-app/quarkus-run.jar` in wanaku-barn).

Do **not** use `curl`, `wget`, `httpie`, or other HTTP clients to call Wanaku or Keycloak APIs unless the test is specifically oriented to verify HTTP-level behavior (e.g., checking raw HTTP status codes on health endpoints, testing Streamable HTTP headers, or verifying endpoint reachability before the CLI can connect), or the API has no CLI command (for example the enable/disable, audit and metrics routes of Wanaku Server).

```bash
# Bad — uses curl to list tools
curl -s "${WANAKU_SERVER_URL}/api/v1/tools" | jq '.data[].name'

# Good — uses the CLI
wanaku tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain

# Acceptable — specifically testing HTTP endpoint reachability
curl -s -o /dev/null -w "%{http_code}" "${WANAKU_SERVER_URL}/health"
```

## Shell compatibility

Use POSIX-compatible constructs. Avoid bash-only features like `${!VAR}` (indirect expansion) since the executor may use zsh or sh. If bash is required, wrap the block in `bash -c '...'`.

## Include negative tests

Verify that invalid inputs are rejected gracefully — missing required fields, references to non-existent resources, malformed data. These often catch real bugs.

## Components under test

Wanaku is now two processes. Each test plan must state which of them it needs:

| Component | Source | Role | Local URL | Health check |
|-----------|--------|------|-----------|--------------|
| **Wanaku Server** | [wanaku-ai/wanaku](https://github.com/wanaku-ai/wanaku) (Rust) | MCP endpoint, namespaces, forwards, discovered tools/resources/prompts, admin UI | management `http://localhost:8080`, MCP `http://localhost:8081/<namespace>/mcp` | `GET /health` |
| **Wanaku Barn** | [wanaku-ai/wanaku-barn](https://github.com/wanaku-ai/wanaku-barn) (Java) | Service catalogs, service templates, data stores, kamelets, semantic routers, audit, backup | `http://localhost:8180` | `GET /q/health/ready` |

Use these common procedures:

- [common/start-server.md](../../plans/common/start-server.md) — build and start Wanaku Server. Every local plan
  needs it.
- [common/start-barn.md](../../plans/common/start-barn.md) — build and start Barn. Only plans that use Barn APIs
  need it.
- [common/start-test-mcp-server.md](../../plans/common/start-test-mcp-server.md) — start an upstream MCP server
  and register it as a forward. Wanaku Server only discovers tools from forwards. It has no other way to add a tool.
- [common/start-cic.md](../../plans/common/start-cic.md) — serve a Barn service catalog through the Camel
  Integration Capability and register it as a forward.

Point each CLI command at the component that owns the API:

- `tools`, `resources`, `prompts`, `namespaces`, `forwards`: `--host "${WANAKU_SERVER_URL}"`
- `service`, `data-store`, `backup`: `--host "${WANAKU_BARN_URL}"`
- `mcp`: `--uri "${WANAKU_MCP_BASE_URL}/<namespace>/mcp"`

Keep these differences from the Java router in mind:

- Wanaku Server has no built-in authentication. Pass `--no-auth` to the CLI in local plans. Authentication is
  provided by oauth2-proxy in front of the server (the operator deploys it when `spec.auth.enabled` is `true`).
- Barn does not enforce authentication. The OIDC re-augmentation step (`-Dquarkus.launch.rebuild=true`) is obsolete.
  Do not add it to new plans.
- The MCP endpoint uses Streamable HTTP only, on port `8081`, at `/<namespace>/mcp`. There is no SSE endpoint, no
  `public` namespace and no `ns-0`..`ns-9` slots. Namespace names are DNS labels (lowercase letters, digits and
  hyphens, 1 to 63 characters).
- Tools, resources and prompts cannot be added with the CLI or the API. They come from forwards. They can be
  enabled, disabled or deleted, but the next discovery adds a deleted entry again.
- Wanaku Server governance is fail-safe. It denies `tools/call`, `resources/read` and `prompts/get` unless an action
  policy or an evaluator allows the action. Plans that invoke tools must start the server with the test governance
  configuration (see [common/start-server.md](../../plans/common/start-server.md), step 2b). Allow only the
  namespaces that the plan uses.
- There is no capability registration or discovery API (`/api/v1/capabilities`, `/api/v1/management/discovery`).
  Register MCP servers as forwards.
- Wanaku Server stores its registry in `registry.json` under `WANAKU_PERSIST_PATH`. Use a new directory per run so
  that runs do not share state.
- Wanaku Server does not export OpenTelemetry traces or Prometheus metrics. It exposes `GET /api/v1/metrics` and the
  audit trail (`GET /api/v1/audit/events`). Barn still supports OpenTelemetry.

## Cleanup must be idempotent

Use `--ignore-not-found=true` on all delete commands and `2>/dev/null || true` on wait commands. A cleanup phase that fails on missing resources makes re-runs painful.

## Checklist for new plans

- [ ] The plan states whether it needs Wanaku Server only, or Wanaku Server and Wanaku Barn
- [ ] Each CLI command points at the component that owns the API (`WANAKU_SERVER_URL` or `WANAKU_BARN_URL`)
- [ ] All configurable values are environment variables with defaults
- [ ] OpenShift namespace/project defaults are unique per run, not shared cluster-wide
- [ ] Common procedures reference shared docs, not inline copies
- [ ] Every step has a PASS/FAIL assertion
- [ ] No fixed `sleep` — use `oc wait`, `oc rollout status`, or polling
- [ ] Uses the Wanaku CLI — no `curl`/`wget`/`httpie` against Wanaku or Keycloak unless specifically testing HTTP behavior
- [ ] Negative tests are included
- [ ] Cleanup is idempotent
- [ ] Shell constructs are POSIX-compatible
