# Wanaku tests documentation

This documentation is for Wanaku contributors. It explains how to run and write the tests that check Wanaku as a
system: the components, deployed together, doing real work.

The repository has two kinds of tests. The **integration tests** are Java tests that start the Wanaku components as
real processes. They run in GitHub CI for each Wanaku pull request, and the results come back on the pull request.
The **test plans** are Markdown plans that [Midnight Saci](https://orpiske.github.io/midnight-saci/) runs end to end
in real environments, for example OpenShift. Both kinds use the **fixtures and mocks** of this repository.

## Choose your path

| You want to | Start with | Then read |
|---|---|---|
| Check that your Wanaku pull request breaks nothing | [Test a Wanaku pull request](get-started/test-a-wanaku-pr.md) | [Run in GitHub CI](integration-tests/run-in-github-ci.md) |
| Reproduce a CI failure on your machine | [Run locally](integration-tests/run-locally.md) | [Debug with external instances](integration-tests/debug-with-external-instances.md), [Troubleshooting](integration-tests/troubleshooting.md) |
| Add a test for a new server feature | [Write an integration test](integration-tests/write-an-integration-test.md) | [Test framework](integration-tests/framework.md), [Fixtures and mocks](fixtures/index.md) |
| Test a release on OpenShift end to end | [About the test plans](test-plans/about-test-plans.md) | [Run a test plan](test-plans/run-a-test-plan.md), [Test plan catalog](test-plans/catalog.md) |
| Add a test plan | [Write a test plan](test-plans/write-a-test-plan.md) | [Common procedures](test-plans/common-steps.md), [Credentials](test-plans/credentials.md) |
| Measure the MCP request path | [Run load tests](load-tests/run-load-tests.md) | [Test MCP server](fixtures/test-mcp-server.md) |

## Sections

- [Get started](get-started/index.md): an overview, and a first run for your pull request.
- [Integration tests](integration-tests/index.md): run the Java tests in CI and locally, and write new tests.
- [Test plans](test-plans/index.md): run and write the end-to-end test plans for Midnight Saci.
- [Fixtures and mocks](fixtures/index.md): the servers and components that the tests use.
- [Load tests](load-tests/index.md): the k6 load tests for the MCP request path.
- [Reference](reference/index.md): system properties and CI workflows.
- [Contributing](contributing/index.md): write and check this documentation.

## Get help

Open an issue in [wanaku-ai/wanaku-tests](https://github.com/wanaku-ai/wanaku-tests/issues) for a problem with the
tests, and in [wanaku-ai/wanaku](https://github.com/wanaku-ai/wanaku/issues) for a problem with Wanaku itself.
