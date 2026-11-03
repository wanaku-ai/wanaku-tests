# CIC route fixtures

The Camel Integration Capability (CIC) tests and the A2A tests start CIC with Camel routes from the fixture
directories of their modules. `TestFixtures.load()` copies a fixture directory to `target/test-fixtures/` and replaces
each `${VAR}` placeholder with a value from the test.

## Fixtures

| Fixture | Module | Use |
|---------|--------|-----|
| [`simple-tool`](../../camel-integration-capability-tests/src/test/resources/fixtures/simple-tool) | `camel-integration-capability-tests` | The `echo-tool` route (`ai-tool:echo`, tag `wanaku`). It returns the `message` argument twice, with a separator. All CIC test classes use it. |
| [`a2a-agent`](../../a2a-tests/src/test/resources/fixtures/a2a-agent) | `a2a-tests` | The deterministic A2A 0.3 agent. See [A2A proxy tests](../integration-tests/a2a.md#the-a2a-fixture). |

Each CIC fixture has a `routes.camel.yaml` and, optionally, a `dependencies.txt` that the test passes to CIC. Some
fixtures also have a `rules.yaml` from the old CIC format. The current CIC does not read it.

The `file-resource`, `postgres-tool` and `multi-instance-tool` directories of `camel-integration-capability-tests`
are not used by the current tests.

## Add a route fixture

1. Add a directory under `src/test/resources/fixtures/` of the module, with a `routes.camel.yaml`.
2. Tag each `ai-tool` route with `wanaku`. CIC exposes only tagged routes.
3. Use `${VAR}` placeholders for values that the test knows only at run time, and give them to `TestFixtures.load()`.

## Next steps

- [Camel Integration Capability tests](../integration-tests/camel-integration-capability.md)
- [Fixtures and mocks](index.md)
