# A2A proxy tests

The `a2a-tests` module tests the managed A2A 0.3 JSON-RPC listener of the Wanaku Server
([wanaku-ai/wanaku#2071](https://github.com/wanaku-ai/wanaku/pull/2071)). The upstream agents run in standalone Camel
Integration Capability (CIC) processes.

## Coverage

The tests cover Agent Card rewriting, message forwarding, task lookup, upstream cancellation errors, policy
rejection, unsupported operations and options, required fields, live address updates, namespace isolation, route
removal, task-not-found errors, registration validation, and invalid protocol envelopes and versions.

## Run the tests

You need a Wanaku Server with the A2A listener and a CIC fat JAR:

```shell
mvn verify -pl a2a-tests -am \
  -Dwanaku.test.server.a2a-enabled=true \
  -Dwanaku.test.server.mcp-id-filter=true \
  -Dwanaku.test.server.binary=/path/to/wanaku-server \
  -Dwanaku.test.camel-capability.jar=/path/to/camel-integration-capability.jar
```

Maven skips the module by default, because older servers do not know the A2A filters and stop at startup. Set
`wanaku.test.server.a2a-enabled=true` or `wanaku.test.external.a2a.port` to run it. In GitHub CI, the
`full-integration-test` workflow sets `wanaku.test.server.a2a-enabled=true` when the server supports A2A.

The module POM sets the action policy of the server: it allows agent actions, and denies `SendMessage` when
`/message/messageId` is `denied-message`.

## The A2A fixture

The [Barn template](https://github.com/wanaku-ai/wanaku-barn/pull/166) uses `camel-a2a`, and the current Camel A2A
component uses protocol 1.0. This suite uses a deterministic 0.3 fixture instead. The fixture is a set of Camel
`platform-http` and `jsonpath` routes. It tests the legacy behavior on servers that support 0.3, or both 0.3 and 1.0.
The suite does not test interoperability with the native Camel A2A 1.0 component, and it registers no MCP forward.

- The fixture returns a synchronous, completed task.
- A cancel request returns the A2A `TaskNotCancelable` error.
- The task IDs of the two upstreams are the same on purpose, to test the namespace routing.
- The test message text is a fixed JSON-safe string.
- The unsupported-version checks request version 2.0. They accept the legacy Invalid Request error and the newer
  `VersionNotSupportedError`.

## Use an external server

Give the management, MCP and A2A ports of the running server:

```shell
mvn verify -pl a2a-tests -am \
  -Dwanaku.test.external.mgmt.port=8080 \
  -Dwanaku.test.external.mcp.port=8081 \
  -Dwanaku.test.external.a2a.port=8084 \
  -Dwanaku.test.camel-capability.jar=/path/to/camel-integration-capability.jar
```

The tests cannot change the configuration of an external server. Configure the server yourself:

- Set the public A2A origin to `http://localhost:8084`.
- Allow private upstreams.
- Use the action policy of the module POM.

The tests do not use an external CIC. Each upstream fixture needs its own process.

## Next steps

- [Debug with external instances](debug-with-external-instances.md)
- [Integration test modules](modules.md)
