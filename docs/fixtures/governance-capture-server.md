# Governance capture server

The governance capture server in
[`fixtures/governance-capture-server`](../../fixtures/governance-capture-server) is an upstream MCP server for the
governance tests. It counts each governed request that reaches it. A test then knows if Wanaku forwarded a request,
or if governance stopped it in the proxy.

## What it exposes

The entity names are stable, so the action-policy rules of a test can select them by name.

| Kind | Names |
|------|-------|
| Tools | `capture_tool` (argument `payload`), `capture_typed_tool` (optional JSON `value`), `capture_isolated_tool`, `capture_star*tool`, `capture_question?tool` |
| Resources | `capture://resource/data`, `capture://resource/database`, `capture://resource/%64ata` |
| Prompts | `capture_prompt` (argument `topic`), `capture_optional_prompt` (optional `topic`) |

The tool and resource names with `*`, `?` and `%64` test that the policy selectors match them literally.

`GET /capture/counts` returns the counters as JSON: `toolCalls`, `resourceReads` and `promptGets`. The REST endpoint
does not use MCP, so a test can read the counters also when Wanaku denied the request.

## Build and run

```bash
mvn -DskipTests package -f fixtures/governance-capture-server/pom.xml
```

The tests start the JAR at `fixtures/governance-capture-server/target/quarkus-app/quarkus-run.jar` on a free port.
To run it by hand, run `java -jar target/quarkus-app/quarkus-run.jar` in the fixture directory. It listens on port
8282 by default.

## Next steps

- [Governance tests](../integration-tests/governance.md)
- [Fixtures and mocks](index.md)
