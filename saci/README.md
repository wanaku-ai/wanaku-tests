# Saci test image

[Midnight Saci](https://github.com/orpiske/midnight-saci) runs Markdown test
plans in a worker container. The [`Containerfile`](Containerfile) in this
directory extends the Saci worker image with the Wanaku binaries and the test
MCP server fixture, so a plan can start a Wanaku server, connect it to an MCP
server and test it with the CLI.

## Contents

The image is `quay.io/orpiske/saci-worker` with these additions:

| Program | Path | Source |
| --- | --- | --- |
| `wanaku-server` | `/usr/local/bin/wanaku-server` | `/usr/local/bin/wanaku-server` of `WANAKU_IMAGE` |
| `wanaku` | `/usr/local/bin/wanaku` | The `wanaku-cli` archive of a [wanaku-barn release](https://github.com/wanaku-ai/wanaku-barn/releases/tag/early-access) |
| `test-mcp-server` | `/usr/local/bin/test-mcp-server` | `/deployments` of `FIXTURE_IMAGE`, the [`fixtures/test-mcp-server`](../fixtures/test-mcp-server) image |
| `oc`, `kubectl` | `/usr/local/bin/oc`, `/usr/local/bin/kubectl` | The OpenShift client archive for `OC_VERSION` from [mirror.openshift.com](https://mirror.openshift.com/pub/openshift-v4/clients/ocp/) |
| `helm` | `/usr/local/bin/helm` | The Helm `HELM_VERSION` archive from [get.helm.sh](https://get.helm.sh) |
| `openssl` | `/usr/bin/openssl` | The Fedora `openssl` package |

On Linux x86_64, the image contains the native CLI. On other architectures,
the image contains the JVM CLI. The image also contains a Java runtime for
the test MCP server, and Node.js with `npm`.

`test-mcp-server` listens on port 8181 and serves MCP at
`http://127.0.0.1:8181/mcp`. The
[fixture README](../fixtures/test-mcp-server/README.md) lists its tools and
resources. To use a different port, run
`QUARKUS_HTTP_PORT=<port> test-mcp-server`.

The base image also supplies `bash`, `curl`, `jq`, `git` and the other
[worker utilities](https://github.com/orpiske/midnight-saci/blob/main/docs/worker.md#the-image).
The image runs as UID 10001 in group 0, and `HOME` is `/workspace`.

## Build the image

Run this command from the repository root:

```sh
docker build -f saci/Containerfile -t localhost/wanaku-saci-worker:latest saci
```

You can use `podman` instead of `docker`. The build does not use files from
the build context.

| Build argument | Default | Description |
| --- | --- | --- |
| `SACI_IMAGE` | `quay.io/orpiske/saci-worker:latest` | The Saci worker image to extend. Set it to `localhost/saci-worker:latest` to use a local `make image` build of Midnight Saci. |
| `WANAKU_IMAGE` | `quay.io/wanaku/wanaku-server:early-access` | The image that supplies `wanaku-server`. |
| `WANAKU_CLI_RELEASE` | `early-access` | The wanaku-barn release that supplies the CLI. |
| `WANAKU_CLI_VERSION` | `0.3.0-SNAPSHOT` | The CLI version in the release archive names. |
| `FIXTURE_IMAGE` | `quay.io/wanaku/test-mcp-server:latest` | The image that supplies the test MCP server. |
| `OC_VERSION` | `stable` | The OpenShift client version directory, for example `stable-4.19` or `4.19.10`. |
| `HELM_VERSION` | `v3.19.0` | The Helm version. |

The build verifies the SHA-256 checksums of the Wanaku CLI, OpenShift client and Helm archives.

To test a server that you built from source, build the server image in your
Wanaku checkout first. Then give that image to the build:

```sh
(cd /path/to/wanaku && docker build -t localhost/wanaku-server:saci .)
docker build -f saci/Containerfile \
  --build-arg WANAKU_IMAGE=localhost/wanaku-server:saci \
  -t localhost/wanaku-saci-worker:latest saci
```

To test a changed fixture, build its image as the
[fixture README](../fixtures/test-mcp-server/README.md#container) shows. Then
set `FIXTURE_IMAGE` to that image.

## Published image

The `Saci Worker Container` workflow
([`saci-container.yml`](../.github/workflows/saci-container.yml)) builds the
image on x86_64 and AArch64 runners. Each build starts `wanaku-server` and
`test-mcp-server` and runs the CLI in the image as UID 123456. The workflow
also runs after the `Test MCP Server Container` workflow succeeds on `main`,
so the image uses the newest published fixture. After a push to `main`, the workflow
publishes `quay.io/wanaku/wanaku-saci-worker:latest` and a
`sha-<commit>` tag. Each tag is a multi-architecture manifest. Pull requests
build and check the image but do not publish it.

## Use the image

Set `worker.image` in the Saci configuration:

```yaml
worker:
  image: quay.io/wanaku/wanaku-saci-worker:latest
```

For a local build, use `localhost/wanaku-saci-worker:latest`.

The shell session of a run keeps background processes between steps. A
setup step can start the server, and later steps can use it:

````markdown
## Phase 1: Setup

### Step 1.1: Start the Wanaku server

Start the server in the background and wait for the management API:

```bash
wanaku-server >wanaku-server.log 2>&1 &
for _ in $(seq 1 50); do
  curl -fsS -o /dev/null http://127.0.0.1:8080/healthz && break
  sleep 0.2
done
curl -sS -w '\nstatus %{http_code}\n' http://127.0.0.1:8080/healthz
```

**Expected result:**

- The output ends with `status 200`.

## Phase 2: CLI

### Test 2.1: List the tools

```bash
wanaku tools list --no-auth
```

**Expected result:**

- The command exits with status 0.
````

The server stores its registry in `$HOME/.wanaku/server`, in the run workspace.
Each run starts with an empty registry.
