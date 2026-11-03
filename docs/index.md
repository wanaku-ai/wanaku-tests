# Wanaku tests documentation

This documentation is for Wanaku contributors. It explains how to run and write the tests in this repository.

The repository has two kinds of tests. The **integration tests** are Java tests that run the Wanaku components as
real processes. Run them in GitHub CI. The **test plans** are Markdown plans that
[Midnight Saci](https://orpiske.github.io/midnight-saci/) runs end to end in real environments, for example OpenShift.
Both kinds use the **fixtures** in this repository.

## Sections

- [Integration tests](integration-tests/index.md): the Java integration test modules.
- [Test plans](test-plans/index.md): the end-to-end test plans for Midnight Saci.
- [Fixtures and mocks](fixtures/index.md): the servers and components that the tests use.
- [Load tests](load-tests/index.md): the k6 load tests for the MCP request path.
- [Reference](reference/index.md): system properties and CI workflows.
