# Semantic router tests

The `semantic-router-tests` module has two test classes:

- `SemanticRouterITCase` starts a Wanaku Server and a Wanaku Semantic Router (WSR) with local routes. It tests routing
  through Wanaku, audit delivery to the Wanaku audit receiver, the audit settings of the runtime and the catalog,
  recovery after the audit receiver comes back, and the removal of the forward at a graceful shutdown.
- `CloudRunSemanticCliITCase` tests the input checks of `wanaku cloud run semantic` without a cluster.

Routing uses a local deterministic model fixture. The tests do not need an external model service or an API key.

## Prerequisites

| Artifact | Property |
|----------|----------|
| `wanaku-server` binary | `wanaku.test.server.binary` |
| WSR JAR with dependencies (`wanaku-semantic-router-<version>-jar-with-dependencies.jar`) | `wanaku.test.semantic-router.jar` |
| `wanaku` CLI with the `cloud run semantic` command (`CloudRunSemanticCliITCase` only) | `wanaku.test.cli.path` |

When you do not set `wanaku.test.semantic-router.jar`, the tests look for a `wanaku-semantic-router*.jar` in the
artifacts directory (`wanaku.test.artifacts.dir`, default `artifacts`).

## Run the tests

Run:

```bash
mvn verify -pl semantic-router-tests -am \
  -Dwanaku.test.server.binary=/absolute/path/to/wanaku-server \
  -Dwanaku.test.semantic-router.jar=/absolute/path/to/wanaku-semantic-router-<version>-jar-with-dependencies.jar
```

The local routes, properties and dependencies of the router are in
[`semantic-router-tests/src/test/resources/support/`](../../semantic-router-tests/src/test/resources/support).

## Skips and failures

When an artifact is missing, the tests log a WARN message and skip. When an artifact is present but does not start,
or does not have a feature that the tests need, the tests fail.

In GitHub CI, the `full-integration-test` workflow builds WSR from source. It copies the JAR with dependencies of the version in the WSR `pom.xml` to
`artifacts/wanaku-semantic-router/`. The workflow fails when an expected `SemanticRouterITCase` scenario is missing,
skipped or failed, also for each case of a parameterized test
([`check-semantic-router-test-results.py`](../../.github/scripts/check-semantic-router-test-results.py)).

## Next steps

- [Run the integration tests in GitHub CI](run-in-github-ci.md)
- [Integration test modules](modules.md)
