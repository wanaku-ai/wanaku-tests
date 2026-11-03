# Write an integration test

Add an integration test when a change in Wanaku needs a test with real processes: the server, a capability or a
fixture. Put the test in the module of its area. Add a new module only for a new area.

## Add a test class

1. Name the class `<Something>ITCase`, so that the failsafe plugin runs it.
2. Extend the base class of the module, for example `RouterTestBase` or `CamelCapabilityTestBase`. Most base classes
   extend `BaseIntegrationTest`, which gives the shared server, a `RouterClient` and an MCP client.
   `GovernanceTestBase` and `SemanticRouterTestBase` start their own servers instead.
3. Check the infrastructure that the test needs with `assumeThat`, not `assertThat`. A missing artifact then skips the
   test instead of failing it. Log a WARN message that names the missing artifact and its path.
4. Start the processes in `@BeforeEach` or in the test, and stop them in `@AfterEach`. Do not catch and hide
   exceptions in setup and teardown.
5. Use AssertJ for assertions. Use Awaitility only for an operation that is asynchronous, for example a tool that
   appears after a forward registration. Do not add retries to make a test pass.
6. Get ports with `PortUtils.findAvailablePort()`. Do not use fixed ports.

Each test must be easy to read on its own. Use long, descriptive names, and keep the setup explicit.

## Add a module

1. Create the directory with a `pom.xml` that inherits the parent POM.
2. Add the module to the `<modules>` list of the parent `pom.xml`.
3. Add a `<Area>TestBase` class that extends `BaseIntegrationTest`.
4. Add the module to [Integration test modules](modules.md), with the artifacts and properties that it needs.

## Code style

The build applies Spotless with the Palantir Java Format when it compiles. Wildcard imports are not allowed. The
`Static Analysis` workflow runs PMD and SpotBugs on each pull request and posts a report as a comment.

## Next steps

- [Test framework](framework.md)
- [Fixtures and mocks](../fixtures/index.md)
- [Run the integration tests on your machine](run-locally.md)
