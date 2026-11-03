# Integration tests

The integration tests are JUnit tests that the Maven failsafe plugin runs. Each test starts the Wanaku components
that it needs as real processes, and talks to them through the management API, the MCP endpoint and the CLI. The
normal way to run them is GitHub CI, which builds every component from source and posts the results on the Wanaku
pull request.

## Pages in this section

- [Run in GitHub CI](run-in-github-ci.md): start a run for a pull request or your own branches, and read the results.
- [Run locally](run-locally.md): run all modules, one module or one test on your machine.
- [Debug with external instances](debug-with-external-instances.md): run the tests against a server or CIC that
  already runs.
- [Integration test modules](modules.md): what each module covers and the artifacts that it needs.
- [Test framework](framework.md): how `test-common` starts and stops the processes.
- [Write an integration test](write-an-integration-test.md): add a test class or a module.
- [Governance tests](governance.md): action policies, evaluators, OPA and audit.
- [Semantic router tests](semantic-router.md): routing and audit delivery with the Wanaku Semantic Router.
- [A2A proxy tests](a2a.md): the managed A2A JSON-RPC proxy.
- [Camel Integration Capability tests](camel-integration-capability.md): CIC tools through MCP and the CLI.
- [Resources tests](resources.md): resources and resource templates through MCP.
- [Troubleshooting](troubleshooting.md): skipped tests, missing artifacts and leftover processes.

## Next steps

- [Run the integration tests in GitHub CI](run-in-github-ci.md)
- [Fixtures and mocks](../fixtures/index.md)
