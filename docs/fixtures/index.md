# Fixtures and mocks

The fixtures are small servers and components that the integration tests and the test plans use instead of real
backends. They make the results reproducible, and they need no credentials or external services.

## Which fixture each test uses

| Fixture | Used by |
|---------|---------|
| [Test MCP server](test-mcp-server.md) | `router-tests`, `mcp-forwarding-tests`, `resources-tests`, the Saci worker image and the MCP plans |
| [Governance capture server](governance-capture-server.md) | `governance-tests` |
| [Evaluator block](evaluator-block.md) | The [`mcp-cli-commands.md`](../../plans/mcp-cli-commands.md) plan |
| [CIC route fixtures](cic-route-fixtures.md) | `camel-integration-capability-tests`, `a2a-tests` |
| [In-test mocks](in-test-mocks.md) | `governance-tests`, `semantic-router-tests` and the shared framework |

## Pages in this section

- [Test MCP server](test-mcp-server.md): an MCP server with infrastructure remediation tools, resources and prompts.
- [Governance capture server](governance-capture-server.md): an upstream MCP server that counts the governed requests.
- [Evaluator block](evaluator-block.md): a WebAssembly evaluator component that blocks every action.
- [CIC route fixtures](cic-route-fixtures.md): the Camel routes that the CIC and A2A tests run.
- [In-test mocks](in-test-mocks.md): the LLM and OPA stubs, process managers and proxies of the tests.

## Next steps

- [Integration test modules](../integration-tests/modules.md)
- [Test plans](../test-plans/index.md)
