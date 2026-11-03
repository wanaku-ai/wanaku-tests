# Integration tests

The integration tests are JUnit tests that the Maven failsafe plugin runs. Each test starts the Wanaku components
that it needs as real processes, and talks to them through the management API and the MCP endpoint.

## Pages in this section

- [A2A proxy tests](a2a.md): the managed A2A JSON-RPC proxy, with agents hosted by the Camel Integration Capability.
- [Camel Integration Capability tests](camel-integration-capability.md): Camel tools, file resources, PostgreSQL and
  multiple CIC instances.
- [Resources tests](resources.md): file resources through the REST API, MCP and the CLI.

## Next steps

- [Fixtures and mocks](../fixtures/index.md)
- [Test plans](../test-plans/index.md)
