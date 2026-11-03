# Test Plans

These test plans are written for execution with [Midnight Saci](https://github.com/orpiske/midnight-saci).

The Markdown format must be compliant with Saci (see <https://github.com/orpiske/midnight-saci/tree/main/docs/templates>)

The plans cover end-to-end behavior across components. Unit tests and the Java integration tests in this repository
cover the behavior of single features.

| Area | Plan | Scope |
|------|------|-------|
| CLI | [mcp-cli-commands.md](../../plans/mcp-cli-commands.md) | MCP operations, governance and forwards across a restart, against a builtin or existing server. |
| Keycloak Admin CLI and auth | [cli-auth-commands.md](../../plans/cli-auth-commands.md) | `wanaku-keycloak-admin` round trips and `wanaku auth` login through oauth2-proxy. |
| OpenShift | [operator-basic-functionality.md](../../plans/operator-basic-functionality.md) | Operator installation, `WanakuRouter` lifecycle, authentication and deletion. |
| OpenShift | [openshift-mcp-deployment.md](../../plans/openshift-mcp-deployment.md) | Authenticated MCP operations through the Routes. |
| Barn on OpenShift | [operator-service-catalog.md](../../plans/operator-service-catalog.md) | Barn deployed by the operator, `WanakuServiceCatalog`, and the CLI service catalog lifecycle from `init` to deploy, remove and restore, templates and negative tests. |
| CIC on OpenShift | [camel-integration-capability.md](../../plans/camel-integration-capability.md) | `WanakuIntegration`, self-registration, catalog source and plain Deployment. |
| Semantic Router on OpenShift | [semantic-router-openshift.md](../../plans/semantic-router-openshift.md) | `WanakuSemanticRouter` deployment, routing, audit and deletion. |
| Cloud CLI on OpenShift | [cloud-run-semantic.md](../../plans/cloud-run-semantic.md) | `wanaku cloud run semantic`: stored target, rejected submissions, ConfigMap with `items`, file tree in the runtime, registration, status, failed start and deletion. |

## Credentials

The plans do not contain passwords, tokens or client secrets. They get each credential in one of two ways:

- **Generated at run time.** When a plan creates the account itself, it generates a random value with
  `openssl rand -hex 16` if the variable is not set. This applies to `KEYCLOAK_ADMIN_PASS` when
  [common/keycloak-setup.md](../../plans/common/keycloak-setup.md) deploys Keycloak, and to the passwords of the test users
  (`WANAKU_TEST_PASS`, `TEST_PASSWORD`, `TEST_NEW_PASSWORD`). It also applies to the client secrets that the realm
  import sets (`WANAKU_SERVICE_SECRET`, `WANAKU_MCP_ROUTER_SECRET`).
- **Supplied from the environment.** When a plan uses a system that already exists, export the credential before the
  run. The plan fails when the variable is not set.

| Variable | Used by | Source |
|----------|---------|--------|
| `OPENSHIFT_SA_TOKEN` | OpenShift plans | Environment. See [setup/service-account-setup.md](../../plans/setup/service-account-setup.md). |
| `KEYCLOAK_ADMIN_PASS` | Plans that use Keycloak | Generated on OpenShift. Environment for the local Compose option of [cli-auth-commands.md](../../plans/cli-auth-commands.md). |
| `WANAKU_MCPROUTER_SECRET` | [cli-auth-commands.md](../../plans/cli-auth-commands.md), local Compose option | Environment. Use the value of the Compose deployment. |
| `TYPESAFE_API_KEY` | [semantic-router-openshift.md](../../plans/semantic-router-openshift.md), [cloud-run-semantic.md](../../plans/cloud-run-semantic.md) | Environment (optional). |

To set the values, export them in the shell that runs Saci, or pass them as `env:` parameters of an environment in
[saci.example.yaml](../../plans/saci.example.yaml), as the `openshift` environment does for `OPENSHIFT_SA_TOKEN`. To keep them in a file, put
the `export` lines in a file that ends in `.env` (for example `plans/secrets.env`) and run `source plans/secrets.env`
before the run. Git ignores files that end in `.env`. Do not commit credentials.
