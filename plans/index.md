# Test Plans

These test plans are written for execution with [Midnight Saci](https://github.com/orpiske/midnight-saci).

The Markdown format must be compliant with Saci (see https://github.com/orpiske/midnight-saci/tree/main/docs/templates)

The plans cover end-to-end behavior across components. Unit tests and the Java integration tests in this repository
cover the behavior of single features.

| Area | Plan | Scope |
|------|------|-------|
| CLI | [mcp-cli-commands.md](mcp-cli-commands.md) | MCP operations, governance and forwards across a restart, against a builtin or existing server. |
| Keycloak Admin CLI and auth | [cli-auth-commands.md](cli-auth-commands.md) | `wanaku-keycloak-admin` round trips and `wanaku auth` login through oauth2-proxy. |
| OpenShift | [operator-basic-functionality.md](operator-basic-functionality.md) | Operator installation, `WanakuRouter` lifecycle, authentication and deletion. |
| OpenShift | [openshift-mcp-deployment.md](openshift-mcp-deployment.md) | Authenticated MCP operations through the Routes. |
| Barn | [cli-service-catalog-lifecycle.md](cli-service-catalog-lifecycle.md) | Service catalog from `init` to a tool call through a CIC, and one service template end to end. |
| CIC on OpenShift | [camel-integration-capability.md](camel-integration-capability.md) | `WanakuIntegration`, self-registration, catalog source and plain Deployment. |
| Semantic Router on OpenShift | [semantic-router-openshift.md](semantic-router-openshift.md) | `WanakuSemanticRouter` deployment, routing, audit and deletion. |
