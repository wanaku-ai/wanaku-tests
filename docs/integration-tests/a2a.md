# A2A proxy integration tests

The 11 integration tests run Wanaku's managed A2A 0.3 JSON-RPC listener against standalone Camel Integration Capability processes. They cover Agent Card rewriting, message forwarding, task lookup, upstream cancellation errors, policy rejection, unsupported operations/options, required fields, live address updates, namespace isolation, route removal, task-not-found errors, registration validation and invalid protocol envelopes/versions.

Run with the server built from [Wanaku PR #2071](https://github.com/wanaku-ai/wanaku/pull/2071) and a CIC fat JAR:

```shell
mvn verify -pl a2a-tests -am \
  -Dwanaku.test.server.a2a-enabled=true \
  -Dwanaku.test.server.mcp-id-filter=true \
  -Dwanaku.test.server.binary=/path/to/wanaku-server \
  -Dwanaku.test.camel-capability.jar=/path/to/camel-integration-capability.jar
```

The module is disabled by default because released servers may not recognize A2A filters. Enabling the A2A listener automatically enables these tests. Missing binaries use the framework's infrastructure assumptions and skip threshold.

The [Barn template](https://github.com/wanaku-ai/wanaku-barn/pull/166) uses `camel-a2a`. Current Camel A2A targets protocol 1.0. This suite uses a deterministic 0.3 fixture implemented with Camel `platform-http` and `jsonpath` routes to verify legacy behavior on servers supporting 0.3 or both 0.3 and 1.0. Unsupported-version checks request 2.0 and accept the legacy Invalid Request or newer VersionNotSupportedError response. The suite does not establish interoperability with Camel's native A2A 1.0 component. No MCP forward is registered.

The fixture exposes a synchronous completed task. Cancellation returns the A2A `TaskNotCancelable` error. Task IDs deliberately collide between upstreams to test namespace routing; the fixture is not a general agent or durable task store. Test message text is a fixed JSON-safe string.

To use an external Wanaku instance, specify its management, MCP and A2A ports:

```shell
mvn verify -pl a2a-tests -am \
  -Dwanaku.test.external.mgmt.port=8080 \
  -Dwanaku.test.external.mcp.port=8081 \
  -Dwanaku.test.external.a2a.port=8084 \
  -Dwanaku.test.camel-capability.jar=/path/to/camel-integration-capability.jar
```

Set the external server's public A2A origin to `http://localhost:8084`, allow private upstreams, and configure the policy supplied in this module's POM: allow agent actions and deny `SendMessage` when `/message/messageId` equals `denied-message`. The harness cannot change an external server's configuration. Existing external CIC MCP endpoints are not used; each upstream fixture requires its own process.
