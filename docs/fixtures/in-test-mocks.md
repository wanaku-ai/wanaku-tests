# In-test mocks

Some stand-ins run inside the test JVM, or are started by a process manager of `test-common`. They make the tests
reproducible and keep them away from paid or external services.

| Mock | Where | What it does |
|------|-------|--------------|
| `MockMcpServerManager` | `test-common` (`managers/`) | Starts a fixture JAR (the [test MCP server](test-mcp-server.md) or the [governance capture server](governance-capture-server.md)) as a process on a free port. |
| `DeterministicLlmStub` | `test-common` (`stub/`) | An OpenAI-compatible `/v1/chat/completions` endpoint for the LLM evaluator. It returns a deny verdict when the request contains a marker string. Tests can make it fail with an HTTP error, return a fixed content, delay the responses or refuse connections. It counts the calls. |
| `OpaServerManager` | `test-common` (`managers/`) | Starts a native OPA process for each scenario, and loads the policies and data through the OPA REST API. Needs `wanaku.test.opa.binary`. |
| `OpaHttpStub` | `governance-tests` | A local HTTP stub of the OPA protocol for the OPA contract and governance tests. |
| `SemanticHttpFixtures` | `semantic-router-tests` | A deterministic model provider for the TypeSafe AI adapter of the Semantic Router, and a proxy in front of the Wanaku audit receiver. |
| `SessionIdProxy` | `test-common` (`client/`) | A small HTTP proxy in front of the Wanaku MCP endpoint. It adds the `Mcp-Session-Id` header that the MCP test client needs. |

`PostgresServiceManager` (`test-common/services/`) starts PostgreSQL with Testcontainers. No current test uses it.

## Next steps

- [Governance tests](../integration-tests/governance.md)
- [Test framework](../integration-tests/framework.md)
- [Fixtures and mocks](index.md)
