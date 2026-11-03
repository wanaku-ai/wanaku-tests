# Debug with external instances

The tests can use a Wanaku Server or a Camel Integration Capability (CIC) that is already running, instead of
starting their own. Use this to attach a debugger to the server, or to read its log while a test runs.

## Use a running server and CIC

Start the server and the CIC yourself. Then give their ports and URL to the tests:

```bash
mvn verify -pl camel-integration-capability-tests \
  -Dwanaku.test.external.mgmt.port=8080 \
  -Dwanaku.test.external.mcp.port=8081 \
  -Dwanaku.test.external.cic.url=http://localhost:9000/mcp
```

| Property | Effect |
|----------|--------|
| `wanaku.test.external.mgmt.port` | Use the management API of a running server on this port. Set it together with `wanaku.test.external.mcp.port`. |
| `wanaku.test.external.mcp.port` | Use the MCP endpoint of a running server on this port. |
| `wanaku.test.external.cic.url` | Use a running CIC at this MCP URL. |
| `wanaku.test.external.a2a.port` | Use the A2A listener of a running server on this port (A2A tests only). |

You can set only the server properties, and the tests start the CIC. You can also set only the CIC property, and the
tests start the server.

## Limits

- The tests do not configure an external server. Start the server with the configuration that the tests need.
- For the `mcp-forwarding-tests` module, start the server with `WANAKU_FORWARD_HEADERS=Authorization`. If you do not,
  `McpHeaderForwardingITCase` fails with "Missing required argument: authorization".
- The `governance-tests` module does not support an external server, because its tests change the configuration,
  restart the server and inject storage failures. Its tests skip.

## Next steps

- [Run the integration tests on your machine](run-locally.md)
- [Troubleshooting](troubleshooting.md)
