# Camel Integration Capability tests

The `camel-integration-capability-tests` module tests the
[Camel Integration Capability](https://github.com/wanaku-ai/camel-integration-capability) (CIC) with the Wanaku
Server. CIC exposes Apache Camel routes as MCP tools. The tests start CIC with an echo tool route, register it as a
forward, and use the tool through MCP and the CLI.

## Test classes

| Class | What it tests |
|-------|---------------|
| `EchoToolDefaultNamespaceITCase` | The echo tool appears in the `default` namespace, answers a call, and goes away when CIC stops. A call after that fails. |
| `EchoToolCustomNamespaceITCase` | The same lifecycle in the `integration-test` namespace. |
| `EchoToolCliITCase` | `wanaku forwards add`, the tool list, the tool details and `wanaku forwards remove` through the CLI. |

## Prerequisites

| Artifact | Property |
|----------|----------|
| `wanaku-server` binary | `wanaku.test.server.binary` |
| CIC JAR with dependencies (`camel-integration-capability-main-*-jar-with-dependencies.jar`) | `wanaku.test.camel-capability.jar` |
| `wanaku` CLI (`EchoToolCliITCase` only) | `wanaku.test.cli.path` |

When you do not set `wanaku.test.camel-capability.jar`, the tests use the first JAR in an
`artifacts/camel-integration-capability*` directory.

## Run the tests

```bash
mvn verify -pl camel-integration-capability-tests -am \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server \
  -Dwanaku.test.camel-capability.jar=/path/to/camel-integration-capability-main-jar-with-dependencies.jar
```

To use a CIC that already runs, set `wanaku.test.external.cic.url`. See
[Debug with external instances](debug-with-external-instances.md).

The routes come from the [CIC route fixtures](../../camel-integration-capability-tests/src/test/resources/fixtures).

## Next steps

- [Integration test modules](modules.md)
- [Run the integration tests on your machine](run-locally.md)
