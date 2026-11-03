# Common procedures

The procedures in [`plans/common/`](../../plans/common) are steps that several plans share. A plan links a procedure
and says which variables must be set before and after it. The setup of the OpenShift service account is in
[`plans/setup/`](../../plans/setup).

## Local procedures

| Procedure | What it does |
|-----------|--------------|
| [start-server.md](../../plans/common/start-server.md) | Builds and starts Wanaku Server locally, with no authentication and no Kubernetes. |
| [start-barn.md](../../plans/common/start-barn.md) | Builds and starts the Wanaku Barn backend next to Wanaku Server. |
| [start-test-mcp-server.md](../../plans/common/start-test-mcp-server.md) | Starts the MCP reference server (`@modelcontextprotocol/server-everything`) and registers it with Wanaku Server as a forward. |
| [start-cic.md](../../plans/common/start-cic.md) | Runs a local Camel Integration Capability and registers it as a forward. |
| [container-runtime.md](../../plans/common/container-runtime.md) | Finds and configures a local container runtime (Docker or Podman). |
| [service-template-checks.md](../../plans/common/service-template-checks.md) | Checks that are the same for every built-in service template. |

## OpenShift procedures

| Procedure | What it does |
|-----------|--------------|
| [openshift-login.md](../../plans/common/openshift-login.md) | Logs in to an OpenShift cluster with the service account token. |
| [namespace-setup.md](../../plans/common/namespace-setup.md) | Creates the test namespace, with a unique name for each run. |
| [operator-deployment.md](../../plans/common/operator-deployment.md) | Installs the Wanaku operator with Helm. |
| [keycloak-setup.md](../../plans/common/keycloak-setup.md) | Deploys and configures Keycloak as the OIDC provider. |
| [oidc-login-verification.md](../../plans/common/oidc-login-verification.md) | Logs in against Keycloak and calls Wanaku Server through oauth2-proxy. |
| [wait-for-deletion.md](../../plans/common/wait-for-deletion.md) | Polls until a Kubernetes resource is deleted. |
| [cleanup.md](../../plans/common/cleanup.md) | Removes all the Wanaku test resources from OpenShift. |

## Next steps

- [Write a test plan](write-a-test-plan.md)
- [Test plan catalog](catalog.md)
