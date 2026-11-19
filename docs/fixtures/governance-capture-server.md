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

`GET /capture/authorization` returns non-secret observation labels for the last `capture_tool` and `capture_prompt`
requests, as `tool` and `prompt`. It never returns received headers. The initial label is `absent`. Authorization
observation and echo are disabled by default. Normal tool and prompt calls still work in this mode.

Credential tests explicitly set `-Dcapture.synthetic-authorization=true`. They supply the synthetic tokens through
`CAPTURE_SYNTHETIC_PRIMARY_TOKEN` and `CAPTURE_SYNTHETIC_SECONDARY_TOKEN`. Each configured token must start with
`synthetic-`. For a loopback request with an exact matching Bearer header, the fixture records `primary` or
`secondary`. It appends only that configured synthetic token to the MCP result, so tests can check response
redaction. It does not retain the received header. Unmatched values produce `unmatched` and are never echoed.
Requests without a header produce `absent`. Requests outside loopback produce `remote` and cannot enable echo.
The fixture binds to `127.0.0.1` by default. Managed tests select a JVM loopback address and use that same address
for the fixture bind, health checks, client URLs, and credential origins. Do not send real credentials to this fixture.

## Build and run

```bash
mvn package -f fixtures/governance-capture-server/pom.xml
```

The tests start the JAR at `fixtures/governance-capture-server/target/quarkus-app/quarkus-run.jar` on a free port.
To run it by hand, run `java -jar target/quarkus-app/quarkus-run.jar` in the fixture directory. It listens on port
8282 on loopback by default.

## Next steps

- [Governance tests](../integration-tests/governance.md)
- [Fixtures and mocks](index.md)
