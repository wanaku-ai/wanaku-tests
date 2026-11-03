# Run the integration tests on your machine

Run the integration tests locally to reproduce a CI failure or to develop a new test. The tests start the Wanaku
Server and the other components as processes on your machine, so you must build or download them first.

## Prerequisites

- Java 21 or later and Maven 3.9 or later.
- A `wanaku-server` binary. Build it in a checkout of [wanaku-ai/wanaku](https://github.com/wanaku-ai/wanaku) with
  `cargo build --bin wanaku-server`. The binary is `target/debug/wanaku-server`.
- The artifacts of the modules that you run. The [module list](modules.md) shows what each module needs.

## Build the fixtures

Most modules use the two fixture servers of this repository. Build them once, and again after you change them:

```bash
mvn -DskipTests package -f fixtures/test-mcp-server/pom.xml
mvn -DskipTests package -f fixtures/governance-capture-server/pom.xml
```

## Run the tests

Give the path of the server binary with `wanaku.test.server.binary`. Use an absolute path, or a path that starts
with `~/`. A relative path starts from the directory of each module, not from the repository root.

Run all the modules:

```bash
mvn verify -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

Run one module. The `-am` option also builds `test-common`:

```bash
mvn verify -pl router-tests -am \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

Run one test class or one test method with `it.test`. Add `-Dfailsafe.failIfNoSpecifiedTests=false`, because the
other modules have no test with that name:

```bash
mvn verify -pl router-tests -am \
  -Dit.test='ForwardsCrudITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

Give the other artifacts with their system properties. For example, the tests whose names end in `CliITCase` use the
`wanaku` CLI. The tests use `wanaku` from the `PATH`. To use another CLI, set `wanaku.test.cli.path` to a binary, to
a JAR, or to the `quarkus-run.jar` of a [wanaku-barn](https://github.com/wanaku-ai/wanaku-barn) build
(`apps/wanaku-cli/target/quarkus-app/quarkus-run.jar`):

```bash
mvn verify -pl router-tests -am \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server \
  -Dwanaku.test.cli.path=/path/to/wanaku-barn/apps/wanaku-cli/target/quarkus-app/quarkus-run.jar
```

The [module list](modules.md) gives the properties of each module. The governance, semantic router and A2A modules
have their own pages.

## Missing artifacts

When an artifact that a test needs is missing, the test skips and the log shows a WARN message. When an artifact is
present but does not start, or does not have a feature that the test needs, the test fails. The build also fails when
more than 30% of the tests of a module skip. See
[Skipped tests](run-in-github-ci.md#skipped-tests).

## Read the logs

Each module writes its logs to `<module>/target/logs/`. There is one log file for each process that a test starts,
for example the Wanaku Server and each capability. The failsafe reports are in
`<module>/target/failsafe-reports/`.

## Next steps

- [Debug with external instances](debug-with-external-instances.md)
- [Integration test modules](modules.md)
- [Troubleshooting](troubleshooting.md)
