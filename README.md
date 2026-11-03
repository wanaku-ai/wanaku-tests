# Wanaku tests

Integration tests and end-to-end test plans for [Wanaku](https://github.com/wanaku-ai/wanaku), the governed action
proxy for AI agents.

- **Integration tests**: Java tests that start the Wanaku Server, capabilities and fixtures as real processes. They
  run in GitHub CI for each Wanaku pull request.
- **Test plans**: Markdown plans that [Midnight Saci](https://orpiske.github.io/midnight-saci/) runs end to end in
  real environments, for example OpenShift.

## Test your Wanaku pull request

Add a comment with `/wanaku-integration-tests` to your pull request in wanaku-ai/wanaku. The results come back as a
comment on the pull request. See
[Run the integration tests in GitHub CI](docs/integration-tests/run-in-github-ci.md).

## Documentation

The documentation for contributors is in [`docs/`](docs/index.md):

- [Integration tests](docs/integration-tests/index.md): run the tests in CI and locally, and write new tests.
- [Test plans](docs/test-plans/index.md): run and write the end-to-end test plans.
- [Fixtures and mocks](docs/fixtures/index.md): the servers and components that the tests use.
- [Load tests](docs/load-tests/index.md): k6 load tests for the MCP request path.
