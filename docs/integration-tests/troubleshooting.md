# Troubleshooting

Each section below starts with a symptom. It then gives the cause and the fix.

## All tests of a module skip

The log shows `Server binary not available, skipping infrastructure setup`.

The tests cannot find the `wanaku-server` binary. Set `wanaku.test.server.binary` to an absolute path. A relative
path starts from the directory of the module, not from the repository root.

## Some tests skip with a WARN message

An artifact that the test needs is missing, for example a fixture JAR, the CIC JAR, a WASM action or the CLI. The
WARN message names the artifact and the path. Build or download the artifact, or give its path with its system
property. See [Integration test modules](modules.md).

## The build fails with too many skipped tests

More than 30% of the tests of a module skipped, so `SkipThresholdExtension` failed the build. Fix the missing
artifacts. To run a subset on purpose, set `wanaku.test.skip.threshold` to a higher value. See
[Skipped tests](run-in-github-ci.md#skipped-tests).

## `wanaku: command not found`

The CLI tests use `wanaku` from the `PATH`. Install the CLI, or set `wanaku.test.cli.path` to the CLI binary or JAR.
See [Run the tests](run-locally.md#run-the-tests).

## The server stops at startup with an unknown filter

The tests added a pipeline filter that your server does not know. Do not set `wanaku.test.server.mcp-id-filter` or
`wanaku.test.server.a2a-enabled` for a server that does not have these filters.

## Port already in use, or processes stay after a run

A test run that stopped early can leave `wanaku-server` or Java fixture processes. Find them with
`pgrep -fl wanaku-server` and `pgrep -fl quarkus-run.jar`, and stop them. The tests get free ports for each run, so
the usual cause is a process that still holds a fixed port that you gave, for example an external server port.

## `McpHeaderForwardingITCase` fails with "Missing required argument: authorization"

The tests use an external server that does not forward the `Authorization` header. Start the server with
`WANAKU_FORWARD_HEADERS=Authorization`. See [Debug with external instances](debug-with-external-instances.md).

## Next steps

- [Run the integration tests on your machine](run-locally.md)
- [Test framework](framework.md)
