# Resources tests

The `resources-tests` module tests MCP resources that Wanaku Server discovers from a forward. `McpResourceITCase`
registers the [test MCP server](../fixtures/test-mcp-server.md) as a forward. Then it lists and reads its static
resource (`config://policies/safety-limits`) and its resource template (`logs://{server_id}/syslog`) through the MCP
endpoint of Wanaku.

## Prerequisites

| Artifact | Property |
|----------|----------|
| `wanaku-server` binary | `wanaku.test.server.binary` |
| Test MCP server JAR | none (built at `fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar`) |

## Run the tests

```bash
mvn -DskipTests package -f fixtures/test-mcp-server/pom.xml
mvn verify -pl resources-tests -am \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

## Next steps

- [Test MCP server](../fixtures/test-mcp-server.md)
- [Integration test modules](modules.md)
