# Credentials

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

## Next steps

- [Run a test plan](run-a-test-plan.md)
- [Write a test plan](write-a-test-plan.md)
