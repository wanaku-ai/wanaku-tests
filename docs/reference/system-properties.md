# System properties

The integration tests read their configuration from Java system properties. Give them to Maven with `-D`, for example
`mvn verify -Dwanaku.test.server.binary=/path/to/wanaku-server`. Use absolute paths: a relative path starts from the
directory of each module.

## Artifacts

| Property | Default | Description |
|----------|---------|-------------|
| `wanaku.test.server.binary` | none | Path of the `wanaku-server` binary. Without it, the tests that need a managed server skip. |
| `wanaku.test.cli.path` | `wanaku` | The `wanaku` CLI: a binary, a JAR, or a Quarkus `quarkus-run.jar`. |
| `wanaku.test.camel-capability.jar` | first JAR in `artifacts/camel-integration-capability*/` | Camel Integration Capability JAR with dependencies. |
| `wanaku.test.semantic-router.jar` | first `wanaku-semantic-router*.jar` in the artifacts directory | Semantic Router JAR with dependencies. |
| `wanaku.test.evaluator.wasm` | `actions/dist/safety_review_action.wasm` of the server checkout | Safety review WASM action for the evaluator tests. |
| `wanaku.test.opa.wasm` | `actions/dist/opa_allow_action.wasm` of the server checkout | OPA WASM action for the OPA tests. |
| `wanaku.test.opa.binary` | none | Native OPA binary for `OpaPolicyITCase`. |
| `wanaku.test.artifacts.dir` | `artifacts` | Directory where the tests look for the CIC and Semantic Router JARs. |

The "server checkout" is the Wanaku directory two levels above the server binary
(`<wanaku>/target/<profile>/wanaku-server`).

## Server configuration

These properties change the configuration that the tests give to a managed server.

| Property | Default | Description |
|----------|---------|-------------|
| `wanaku.test.server.mcp-id-filter` | `false` | Adds the `wanaku_mcp_id` filter to the pipeline. Set it for servers that include [wanaku-ai/wanaku#1849](https://github.com/wanaku-ai/wanaku/pull/1849). Older servers stop at startup with this filter. |
| `wanaku.test.server.a2a-enabled` | `false` | Turns on the managed A2A listener and the A2A filters, and runs the `a2a-tests` module. |
| `wanaku.test.server.forward-headers` | not set | Comma-separated list of request headers that the server can forward to upstream MCP servers (`WANAKU_FORWARD_HEADERS`). Header forwarding is denied by default ([wanaku-ai/wanaku#873](https://github.com/wanaku-ai/wanaku/issues/873)). `mcp-forwarding-tests` sets it to `Authorization`. |
| `wanaku.test.server.governance` | `false` | Adds the `wanaku_action_policy` and `wanaku_evaluator` filters to the pipeline. |
| `wanaku.test.server.action-policy` | not set | Action policy (JSON) for the `action_policy` key of `wanaku.yaml`. `a2a-tests` sets it in its POM. |

## External instances

| Property | Description |
|----------|-------------|
| `wanaku.test.external.mgmt.port` | Management API port of a running server. Set it with `wanaku.test.external.mcp.port`. |
| `wanaku.test.external.mcp.port` | MCP port of a running server. |
| `wanaku.test.external.a2a.port` | A2A port of a running server. Also runs the `a2a-tests` module. |
| `wanaku.test.external.cic.url` | MCP URL of a running Camel Integration Capability. |

See [Debug with external instances](../integration-tests/debug-with-external-instances.md).

## Test behavior

| Property | Default | Description |
|----------|---------|-------------|
| `wanaku.test.timeout` | `60` | Default timeout, in seconds, for process startup and waits. |
| `wanaku.test.skip.threshold` | `30` | Highest percentage of skipped tests in a module (0 to 100). Above it, the build fails. |
| `wanaku.log.level` | `INFO` | Log level of the test framework in modules whose `logback-test.xml` reads it (`resources-tests`, `camel-integration-capability-tests`). |

## Next steps

- [Run the integration tests on your machine](../integration-tests/run-locally.md)
- [Workflows](workflows.md)
