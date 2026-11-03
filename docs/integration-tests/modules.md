# Integration test modules

Each Maven module of this repository tests one area of Wanaku. All modules need the `wanaku-server` binary
(`wanaku.test.server.binary`). This page lists what else each module needs and what it covers.

## Modules

| Module | Covers | Also needs |
|--------|--------|------------|
| `router-tests` | Namespaces, forwards, prompts, enable and disable of entries, evaluator revisions, MCP error responses, server info, and the CLI for namespaces, forwards and evaluators | [Test MCP server](../fixtures/test-mcp-server.md); the `wanaku` CLI for the `*CliITCase` classes |
| `mcp-forwarding-tests` | MCP forwarding to an upstream MCP server, upstream errors, and header forwarding | [Test MCP server](../fixtures/test-mcp-server.md). The module sets `wanaku.test.server.forward-headers=Authorization`. |
| `resources-tests` | Resources and resource templates through MCP | [Test MCP server](../fixtures/test-mcp-server.md) |
| `governance-tests` | Action policies, LLM and OPA evaluators, posture modes, startup validation and the audit trail | See [Governance tests](governance.md) |
| `camel-integration-capability-tests` | Camel Integration Capability tools in the default and a custom namespace, and through the CLI | See [CIC tests](camel-integration-capability.md) |
| `semantic-router-tests` | Semantic routing through Wanaku, audit delivery, and the `wanaku cloud run semantic` CLI | See [Semantic router tests](semantic-router.md) |
| `a2a-tests` | The managed A2A JSON-RPC proxy | See [A2A proxy tests](a2a.md) |

`test-common` is the shared framework. It has no tests of its own that need a server. See
[Test framework](framework.md).

The `http-capability-tests/` directory is not a module of the build. Maven does not build or run it.

## Test classes

The test classes end in `ITCase`, so the Maven failsafe plugin runs them in the `verify` phase. To find the classes
of a module, run:

```bash
find router-tests -name '*ITCase.java'
```

To run one class, see [Run the tests](run-locally.md#run-the-tests).

## Next steps

- [Run the integration tests on your machine](run-locally.md)
- [Test framework](framework.md)
- [Fixtures and mocks](../fixtures/index.md)
