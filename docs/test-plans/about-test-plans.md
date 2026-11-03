# About the test plans

The test plans test Wanaku end to end, in the environments where people run it. They complement the integration
tests: the integration tests check single features with local processes in GitHub CI, and the test plans check that
the components work together after a real deployment, for example on OpenShift with the Wanaku operator.

## Integration tests and test plans

| | Integration tests | Test plans |
|---|---|---|
| Format | Java (JUnit, `*ITCase`) | Markdown, in [`plans/`](../../plans) |
| Runner | Maven failsafe, in GitHub CI | [Midnight Saci](https://orpiske.github.io/midnight-saci/) |
| Environment | Local processes started by `test-common` | A real environment: a Wanaku Server on the host, or OpenShift |
| Scope | One feature or one module | A complete user workflow across components |
| When to run | For each Wanaku pull request | Before a release, and after changes to deployment, the operator or the CLI |

## How Saci runs a plan

Midnight Saci is an AI test engineer. It reads a Markdown plan and runs it step by step against the environment.

- The **backend** stores the runs and gives each run to a worker.
- The **worker** is a container that runs the commands of the plan. The
  [Saci worker image](saci-worker-image.md) of this repository has the Wanaku binaries, the CLI and the test MCP
  server.
- An **LLM** with tool calling runs the steps and compares the output with the expected result of each step. Each
  verdict cites the evidence from the run.
- An **environment** of the backend configuration gives the parameters of a run, for example the server mode or the
  OpenShift API URL.

Saci follows the plan as written. It does not repair the system or the plan. A run ends with an outcome, such as
`passed`, `failed`, `blocked` or `inconclusive`. The example configuration can send a report and start an
investigation of a failure (see [Run a test plan](run-a-test-plan.md)).

## Structure of a plan

A plan has phases, and each phase has steps or tests. Each step has a command block and an expected result. Steps
that several plans share are in [`plans/common/`](../../plans/common) (see [Common procedures](common-steps.md)). The
rules for writing a plan are in [Write a test plan](write-a-test-plan.md).

## Next steps

- [Test plan catalog](catalog.md)
- [Run a test plan](run-a-test-plan.md)
- [Write a test plan](write-a-test-plan.md)
