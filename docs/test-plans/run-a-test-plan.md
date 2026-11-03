# Run a test plan

This page tells you how to run a plan of this repository with Midnight Saci. For the installation of Saci, read the
[Saci quickstart](https://orpiske.github.io/midnight-saci/get-started/quickstart.html).

## Prerequisites

- The `saci` command line and an OpenAI-compatible model with tool calling. See
  [Run with a real model](https://orpiske.github.io/midnight-saci/get-started/run-with-a-real-model.html).
- Podman or Docker, for the worker containers.
- For the OpenShift plans: an OpenShift cluster and a service account token (see
  [Prepare OpenShift](#prepare-openshift)).

## Configure the backend

Start from [`plans/saci.example.yaml`](../../plans/saci.example.yaml). It defines:

- The worker image, `quay.io/wanaku/wanaku-saci-worker:latest`. For a local build of the image, use
  `localhost/wanaku-saci-worker:latest`. See [Saci worker image](saci-worker-image.md).
- The model, from the `SACI_LLM_BASE_URL`, `SACI_LLM_MODEL` and `SACI_LLM_API_KEY` environment variables.
- The environments that a run can use:

  | Environment | Use |
  |-------------|-----|
  | `builtin` | The plan starts its own Wanaku Server in the worker (`WANAKU_SERVER_MODE=builtin`). |
  | `wanaku-local-existing` | The plan uses a Wanaku Server and a test MCP server that run on the host, in containers. The file shows the `podman run` commands. |
  | `openshift` | The plan deploys to OpenShift with `OPENSHIFT_API_URL` and `OPENSHIFT_SA_TOKEN`. |

- Optional outcome actions (`email-report`, `investigate`) and an `investigations` block. They need the Saci script
  runner MCP server. Remove the `actions` block if you do not have it.

Copy the file, change it for your setup, and start the backend:

```bash
saci backend start --config saci.yaml
```

## Set the credentials

The plans do not contain credentials. Export the values that a plan needs before you start the backend, or give them
as `env:` parameters of an environment. See [Credentials](credentials.md).

## Prepare OpenShift

The OpenShift plans use a dedicated service account, not your own credentials. Create it once as a cluster admin, and
save its token in `OPENSHIFT_SA_TOKEN`. The steps are in
[`plans/setup/service-account-setup.md`](../../plans/setup/service-account-setup.md).

## Run a plan

Run a plan in an environment, and follow it until it ends:

```bash
saci run plans/mcp-cli-commands.md --environment builtin --follow
```

Then read the result:

```bash
saci report <run-id>
```

The report shows each unit, its attempts, its criteria and the evidence. For more commands, read
[Run and follow plans](https://orpiske.github.io/midnight-saci/run-tests/run-and-follow.html) and
[Investigate failures](https://orpiske.github.io/midnight-saci/run-tests/investigate-failures.html).

The [Test plan catalog](catalog.md) shows the environment that each plan needs.

## Next steps

- [Test plan catalog](catalog.md)
- [Credentials](credentials.md)
- [Write a test plan](write-a-test-plan.md)
