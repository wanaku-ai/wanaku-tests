# Test MCP server

The test MCP server in [`fixtures/test-mcp-server`](../../fixtures/test-mcp-server) is a mock "infrastructure
auto-remediation" MCP (Model Context Protocol) server, built with Quarkus and the `quarkus-mcp-server-http` extension.
It gives the tests real tools, resources and prompts to discover through a Wanaku forward.

These tests and plans use it:

- The `router-tests`, `mcp-forwarding-tests` and `resources-tests` modules. They start the JAR at
  `fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar` with `MockMcpServerManager`.
- The test plans [`mcp-cli-commands.md`](../../plans/mcp-cli-commands.md) and
  [`openshift-mcp-deployment.md`](../../plans/openshift-mcp-deployment.md). The
  [Saci worker image](../test-plans/saci-worker-image.md) contains it as `test-mcp-server`. The common procedure
  [`start-test-mcp-server.md`](../../plans/common/start-test-mcp-server.md) uses another server, the MCP reference
  server `@modelcontextprotocol/server-everything`.

## What it exposes

### Resources

| URI | Description |
|---|---|
| `config://policies/safety-limits` | Static JSON with `max_db_replicas` and `blocked_services` |
| `logs://{server_id}/syslog` | Dynamic template returning mock syslog entries for a given server |

### Tools

| Tool | Parameters | Behavior |
|---|---|---|
| `restartService` | `serverId`, `service` | Returns a simulated restart confirmation |
| `scaleDeployment` | `target`, `replicas` | Returns success if replicas <= 5; returns a policy-block error otherwise |
| `escalateTicket` | `reason`, `urgency` (low/medium/high) | Returns a mock ticket ID |
| `echoAuthHeader` | `marker` | Returns the `Authorization` header of the request and the marker, for the header forwarding tests |

### Prompts

| Prompt | Description |
|---|---|
| `summarizeIncident` | Summarizes a server incident for the ops team |
| `draftEscalation` | Drafts an escalation message for on-call engineers |

## Build

```shell
./mvnw clean install -B
```

Requires Java 21+.

## Run via HTTP transport

```shell
java -jar target/quarkus-app/quarkus-run.jar
```

The server starts on `http://localhost:8181` and exposes the MCP endpoint at `/mcp`.

## Container

Run these commands from `fixtures/test-mcp-server`:

```shell
./mvnw package -DskipTests
podman build -f src/main/docker/Dockerfile.jvm -t test-mcp-server:local .
podman run --rm -p 8181:8181 test-mcp-server:local
```

Docker can be used in place of Podman. The container listens on port 8181.

The `fixture-container.yml` GitHub Actions workflow builds the container on both
AMD64 and ARM64 runners. Pull requests and fork builds only build the images locally;
publishing is restricted to pushes and manual runs on the upstream `main` branch.

For upstream `main` builds, the workflow first publishes immutable
architecture-specific images for the current commit and then creates both
multi-architecture manifests from those same images:

- `quay.io/wanaku/test-mcp-server:latest`
- `quay.io/wanaku/test-mcp-server:sha-<full Git commit SHA>`

The architecture-specific source images for each run are
`sha-<full Git commit SHA>-amd64` and
`sha-<full Git commit SHA>-arm64`. Both the commit-SHA manifest and
`latest` are assembled from that same pair, so overlapping workflow runs
cannot mix architecture images from different commits.

It can also be triggered manually:

```shell
gh workflow run fixture-container.yml --ref main -R wanaku-ai/wanaku-tests
```

Publishing requires repository secrets `QUAY_USERNAME` and `QUAY_PASSWORD` for an
account with write access to `quay.io/wanaku/test-mcp-server`.

## OpenShift

Select the target project with `oc project <project>`, then deploy from
`fixtures/test-mcp-server`:

```shell
export WANAKU_NAMESPACE="$(oc project -q)"
[ -n "${OPENSHIFT_APPS_DOMAIN:-}" ] || { echo "Set OPENSHIFT_APPS_DOMAIN to the cluster apps domain."; exit 1; }
export TEST_MCP_SERVER_HOST="test-mcp-server-${WANAKU_NAMESPACE}.${OPENSHIFT_APPS_DOMAIN}"
sed "s|__TEST_MCP_SERVER_HOST__|${TEST_MCP_SERVER_HOST}|g" deploy/openshift.yaml | oc apply -f -
oc rollout status deployment/test-mcp-server
```

The Ingress exposes the MCP endpoint at `https://${TEST_MCP_SERVER_HOST}/mcp`.
OpenShift uses the cluster ingress certificate and redirects HTTP to HTTPS. Use
`--insecure` with an HTTP client when the certificate is not trusted. For
example, this command sends an MCP initialize request:

```shell
curl --insecure --silent --show-error --request POST \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --header 'Mcp-Protocol-Version: 2025-03-26' \
  --data '{"jsonrpc":"2.0","id":"fixture-init","method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' \
  "https://${TEST_MCP_SERVER_HOST}/mcp"
```

The Deployment uses `quay.io/wanaku/test-mcp-server:latest`. To use a specific
image tag or a different registry, change the `image` field in
`deploy/openshift.yaml` before applying it. For an existing deployment:

```shell
oc set image deployment/test-mcp-server test-mcp-server=<registry>/test-mcp-server:<tag>
oc rollout status deployment/test-mcp-server
```

The Service exposes port 8181 within the project. OpenShift assigns the
container's user ID. TCP probes check that the
HTTP listener is available because this fixture has no health endpoint.

When a new image is published under the same tag, restart the deployment to
pull it:

```shell
oc rollout restart deployment/test-mcp-server
oc rollout status deployment/test-mcp-server
```

## Use it from an MCP client

Add to your `claude_desktop_config.json` or `.claude/settings.json`:

```json
{
  "mcpServers": {
    "infra-remediation": {
      "type": "streamable-http",
      "url": "https://<test-mcp-server-host>/mcp"
    }
  }
}
```

## Next steps

- [Fixtures and mocks](index.md)
- [MCP forwarding and resources tests](../integration-tests/modules.md)
