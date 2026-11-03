# Test a Wanaku pull request

This tutorial runs the integration tests for your pull request in wanaku-ai/wanaku, reads the results, and
reproduces a failure on your machine. You need write access to wanaku-ai/wanaku to start a run with a comment. Without
it, a maintainer starts the run for you.

## 1. Request the tests

Open your pull request in wanaku-ai/wanaku and add this comment:

```text
/wanaku-integration-tests
```

**Result:** an "eyes" reaction appears on your comment. The `Dispatch Integration Tests` workflow of wanaku-ai/wanaku
starts the `Full Integration Test (From Source)` workflow in wanaku-tests.

A pull request that is not a draft also starts a run automatically, after a maintainer approves it. See
[Run in GitHub CI](../integration-tests/run-in-github-ci.md#test-a-wanaku-pull-request).

## 2. Read the results comment

Wait until the run ends. A full run builds all the components, so it can take a long time (the job limit is 90
minutes).

**Result:** an "Integration Test Results" comment appears on the pull request. It has one row for each test module,
with the passed, failed, errored and skipped tests, and a link to the run. A later run edits the same comment.

## 3. Find the cause of a failure

When a test fails, the comment lists it. Open the run from the link in the comment.

1. Download the `full-integration-test-reports` artifact.
2. Open `<module>/target/failsafe-reports/` for the assertion that failed.
3. Open `<module>/target/logs/` for the output of the Wanaku Server and the other processes of that test.

**Result:** you know which test failed and what the server logged at that time.

## 4. Reproduce the failure locally

Build your server branch, build the fixtures, and run the failed test:

```bash
(cd /path/to/wanaku && cargo build --bin wanaku-server)
mvn -DskipTests package -f fixtures/test-mcp-server/pom.xml
mvn -DskipTests package -f fixtures/governance-capture-server/pom.xml
mvn verify -pl router-tests -am \
  -Dit.test='ForwardsCrudITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

Replace `router-tests` and `ForwardsCrudITCase` with the module and the class of the failed test.

**Result:** the test fails on your machine in the same way. Fix the code, and run the test again until it passes.
Then push the fix and request the tests again.

## Next steps

- [Run the integration tests on your machine](../integration-tests/run-locally.md)
- [Troubleshooting](../integration-tests/troubleshooting.md)
- [Integration test modules](../integration-tests/modules.md)
