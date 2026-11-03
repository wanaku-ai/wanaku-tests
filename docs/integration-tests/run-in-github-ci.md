# Run the integration tests in GitHub CI

GitHub CI is the normal way to run the integration tests. The `Full Integration Test (From Source)` workflow builds
every Wanaku component from source, runs all the test modules, and posts the results on the pull request. Use this
page to start a run and to read its results.

## Test a Wanaku pull request

The `Dispatch Integration Tests` workflow of wanaku-ai/wanaku
([`dispatch-integration-tests.yml`](https://github.com/wanaku-ai/wanaku/blob/main/.github/workflows/dispatch-integration-tests.yml))
starts the tests for a pull request in two ways:

- **Automatic.** When a pull request to `main` or to a release branch (for example `0.3.x`) opens, gets new commits,
  or becomes ready for review, the workflow waits for a maintainer to approve the `integration-tests` environment.
  Then it starts the tests. Draft pull requests and changes to Markdown or `docs/` only do not start a run.
- **On request.** Add a comment that contains `/wanaku-integration-tests` to the pull request. You must have write
  access to wanaku-ai/wanaku. The workflow adds an "eyes" reaction to your comment when it starts the tests.

In both cases, the workflow runs `full-integration-test.yml` from the `main` branch of this repository. It builds the
Wanaku Server from the head branch of the pull request (also from a fork), and the other components from their
`main` branches. A new request for the same pull request cancels an earlier request that has not started the tests yet.

## Read the results

When the run ends, the workflow adds an "Integration Test Results" comment to the pull request. A later run edits
the same comment. The comment has a link to the run, the Wanaku repository and branch, and one row for each test
module with the number of passed, failed, errored and skipped tests. When a test fails, the comment also lists the
failed tests.

The run page also has:

- The job summary, with the repositories and branches of each component.
- The `full-integration-test-reports` artifact, with the failsafe reports (`**/target/failsafe-reports/`) and the
  process logs (`**/target/logs/`) of each module. GitHub keeps the artifact for 14 days.

The run fails when a test fails. It also fails when too many tests skip (see [Skipped tests](#skipped-tests)), when a
semantic router scenario does not run, or, for a Wanaku Server with OPA support, when an OPA scenario does not run.

## Run from your own branches

Run the workflow by hand to test branches that are not in a Wanaku pull request, for example a change in two
repositories at the same time. Run it on your fork of wanaku-tests.

Build only Wanaku from a branch of your fork. The other components use `main`:

```bash
gh workflow run full-integration-test.yml \
  -f wanaku_repo=<your github ID>/wanaku \
  -f wanaku_branch=my-feature-branch \
  -R <your github ID>/wanaku-tests
```

Build all components from a release branch:

```bash
gh workflow run full-integration-test.yml \
  -f wanaku_branch=0.2.x \
  -f sdk_branch=0.2.x \
  -f cic_branch=0.2.x \
  -f barn_branch=0.2.x \
  -R <your github ID>/wanaku-tests
```

To post the results on a pull request, also set `pr_number` and `source_repo`. The workflow posts the comment with a
GitHub App token from the `INTEGRATION_APP_*` secrets, so this works only where those secrets exist.

### Workflow inputs

| Input | Default | Description |
|-------|---------|-------------|
| `wanaku_repo` | `wanaku-ai/wanaku` | Wanaku repository (Rust server) |
| `wanaku_branch` | `main` | Wanaku branch |
| `sdk_repo` | `wanaku-ai/wanaku-capabilities-java-sdk` | Capabilities Java SDK repository |
| `sdk_branch` | `main` | SDK branch |
| `cic_repo` | `wanaku-ai/camel-integration-capability` | Camel Integration Capability repository |
| `cic_branch` | `main` | CIC branch |
| `wsr_repo` | `wanaku-ai/wanaku-semantic-router` | Semantic Router repository |
| `wsr_branch` | `main` | Semantic Router branch |
| `barn_repo` | `wanaku-ai/wanaku-barn` | Wanaku Barn repository (CLI and Java components) |
| `barn_branch` | `main` | Barn branch |
| `pr_number` | empty | Pull request that gets the results comment. Empty means no comment. |
| `source_repo` | `wanaku-ai/wanaku` | Repository of that pull request (`owner/repo`) |

## What the workflow builds

The workflow clones each component at its branch, then:

1. Builds the Capabilities Java SDK, the Semantic Router, Wanaku Barn (which supplies the `wanaku` CLI) and the Camel
   Integration Capability with Maven.
2. Builds the Wanaku Server debug binary (`cargo build --bin wanaku-server`) and the evaluator WASM actions.
3. Installs OPA 1.21.1, when the Wanaku source has the OPA evaluator action (`actions/opa-allow`).
4. Builds the two fixtures: [`fixtures/test-mcp-server`](../../fixtures/test-mcp-server) and
   [`fixtures/governance-capture-server`](../../fixtures/governance-capture-server).
5. Runs `mvn verify` on all modules with the paths to these artifacts.

The workflow reads the `default.yaml` of the Wanaku source. When the pipeline has the `wanaku_mcp_id` filter, the
workflow sets `wanaku.test.server.mcp-id-filter=true`. When it has the `wanaku_a2a` filter, the workflow sets
`wanaku.test.server.a2a-enabled=true`. Older servers stop at startup when they get a filter that they do not know, so
the workflow sets these properties only when the server supports them.

## Skipped tests

A test skips, and does not fail, when the infrastructure that it needs is not available: for example, when the server
binary or a fixture JAR is missing. To make sure that a missing artifact does not hide a problem, the build fails
when more than 30% of the tests in a module skip. Set `wanaku.test.skip.threshold` (0 to 100) to change the limit.
Tests that have the `@KnownLimitation` annotation are not counted.

## Other workflows

The `Integration Tests` workflow (`integration-tests.yml`) runs the tests against released artifacts instead of a
source build. A Wanaku build starts it with a `wanaku-build-completed` repository dispatch event. You can also start it
by hand with a `version` input. It keeps the results in the `integration-test-reports` artifact.

## Next steps

- [Run the integration tests on your machine](run-locally.md)
- [Integration test modules](modules.md)
- [Troubleshooting](troubleshooting.md)
