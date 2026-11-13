# Test plan catalog

The test plans in [`plans/`](../../plans) and the environment that each one needs. The environments are the ones of
[`plans/saci.example.yaml`](../../plans/saci.example.yaml) (see [Run a test plan](run-a-test-plan.md)).

| Area | Plan | Environment | Scope |
|------|------|-------------|-------|
| CLI | [mcp-cli-commands.md](../../plans/mcp-cli-commands.md) | `builtin` or `wanaku-local-existing` | MCP operations, governance and forwards across a restart, against a builtin or existing server. |
| Keycloak Admin CLI and auth | [cli-auth-commands.md](../../plans/cli-auth-commands.md) | `openshift` | `wanaku-keycloak-admin` round trips and `wanaku auth` login through oauth2-proxy. |
| OpenShift | [operator-basic-functionality.md](../../plans/operator-basic-functionality.md) | `openshift` | Operator installation, `WanakuRouter` lifecycle, authentication and deletion. |
| OpenShift | [openshift-mcp-deployment.md](../../plans/openshift-mcp-deployment.md) | `openshift` | Authenticated MCP operations through the Ingresses. |
| Barn on OpenShift | [operator-service-catalog.md](../../plans/operator-service-catalog.md) | `openshift` | Barn deployed by the operator, `WanakuServiceCatalog`, and the CLI service catalog lifecycle from `init` to deploy, remove and restore, templates and negative tests. |
| CIC on OpenShift | [camel-integration-capability.md](../../plans/camel-integration-capability.md) | `openshift` | `WanakuIntegration`, self-registration, catalog source and plain Deployment. |
| Semantic Router on OpenShift | [semantic-router-openshift.md](../../plans/semantic-router-openshift.md) | `openshift` | `WanakuSemanticRouter` deployment, routing, audit and deletion. |
| Cloud CLI on OpenShift | [cloud-run-semantic.md](../../plans/cloud-run-semantic.md) | `openshift` | `wanaku cloud run semantic`: stored target, rejected submissions, ConfigMap with `items`, file tree in the runtime, registration, status, failed start and deletion. |

[cli-auth-commands.md](../../plans/cli-auth-commands.md) also has a manual option for a local Docker Compose
deployment. Saci does not run that option.

The plans that create OpenShift resources use a new namespace for each run, so several plans can run on the same
cluster.

## Next steps

- [Run a test plan](run-a-test-plan.md)
- [Credentials](credentials.md)
- [Write a test plan](write-a-test-plan.md)
