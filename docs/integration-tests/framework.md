# Test framework

The `test-common` module is the shared framework of the integration tests. It starts the processes that a test
needs, gives the test clients for the management API, MCP and the CLI, and stops the processes at the end. This page
explains how a test run works, so that you can read a test and find the cause of a failure.

## Lifecycle

```text
mvn verify (one module)
└── SharedInfrastructure          once for each module
    └── Wanaku Server             started once, shared by all test classes of the module
        └── test class
            └── test method       new RouterClient and MCP client
                └── capabilities  CIC, fixture servers: started and stopped by the test or its base class
```

1. The first test class of a module starts the shared infrastructure (`SharedInfrastructureExtension`). It reads the
   system properties (`TestConfiguration.fromSystemProperties()`), creates a temporary data directory and starts one
   Wanaku Server. If the server binary is not available, it starts nothing, and the tests skip.
2. Before each test, `BaseIntegrationTest` creates a `RouterClient` for the management API and an MCP client for the
   `default` namespace.
3. The test, or the base class of its module, starts the capabilities and fixtures that it needs, and registers them
   as forwards.
4. After each test, the base class removes the forwards and stops the capabilities.
5. After the last test class, the shared infrastructure stops the server.

Some tests in `governance-tests`, `router-tests` and `semantic-router-tests` start their own servers with a special
configuration instead of the shared server.

## The Wanaku Server process

`WanakuServerManager` starts the `wanaku-server` binary:

- It gets free ports for the management API and the MCP endpoint (and the A2A listener, when A2A is on).
- It writes a pipeline configuration and a `wanaku.yaml` to temporary files, and gives them with `--pipeline-config`
  and `--wanaku-config`. The pipeline has only the filters that the configured server supports (see
  [What the workflow builds](run-in-github-ci.md#what-the-workflow-builds)).
- It sets environment variables such as `WANAKU_MGMT_LISTEN`, `WANAKU_PERSIST_BACKEND` and, when set,
  `WANAKU_FORWARD_HEADERS`.
- It waits until `GET /healthz` on the management port answers.

The server has no authentication, so the clients do not send an access token.

## Processes and logs

Each process manager (`WanakuServerManager`, `CamelCapabilityManager`, `MockMcpServerManager`,
`SemanticRouterManager`, `OpaServerManager`) extends `ProcessManager`. `ProcessManager` starts the process, writes
its output to a log file in `<module>/target/logs/`, runs the health check, and stops the process.

## Skips and the skip threshold

A test checks its infrastructure with `assumeThat`. If something is missing, the test skips instead of failing.
`SkipThresholdExtension` fails the module when more than 30% of its tests skip. See
[Skipped tests](run-in-github-ci.md#skipped-tests).

## Next steps

- [Write an integration test](write-an-integration-test.md)
- [Integration test modules](modules.md)
- [Fixtures and mocks](../fixtures/index.md)
