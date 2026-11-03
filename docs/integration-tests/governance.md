# Governance tests

The `governance-tests` module tests the governance pipeline of the Wanaku Server: action policies, the LLM and OPA
evaluators, posture modes, startup validation and the audit trail. Each test starts its own Wanaku Server with the
configuration that it needs, so the module does not support an external server.

## Prerequisites

| Artifact | Property | Used by |
|----------|----------|---------|
| `wanaku-server` binary with governance support | `wanaku.test.server.binary` | All tests |
| [Governance capture server](../../fixtures/governance-capture-server) JAR | none (built at `fixtures/governance-capture-server/target/quarkus-app/quarkus-run.jar`) | All tests |
| Safety review WASM action (`safety_review_action.wasm`) | `wanaku.test.evaluator.wasm` | Evaluator and audit tests |
| OPA WASM action (`opa_allow_action.wasm`) | `wanaku.test.opa.wasm` | `Opa*ITCase` |
| Native OPA binary | `wanaku.test.opa.binary` | `OpaPolicyITCase` |

When you do not set `wanaku.test.evaluator.wasm` or `wanaku.test.opa.wasm`, the tests look for the actions in
`actions/dist/` of the Wanaku checkout that contains the server binary (`<wanaku>/target/<profile>/wanaku-server`).

The evaluator tests do not call a real LLM. The deterministic LLM stub of `test-common` (`DeterministicLlmStub`)
returns a deny verdict when the request contains a marker string, and an allow verdict when it does not.

Build the capture fixture first:

```bash
mvn -DskipTests package -f fixtures/governance-capture-server/pom.xml
```

## Run the audit tests

```bash
mvn verify -pl governance-tests -am -Dit.test='Audit*ITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server \
  -Dwanaku.test.evaluator.wasm=/path/to/wanaku/actions/dist/safety_review_action.wasm
```

The audit tests cover decision and administrative events, query filters, redaction, retention, durability across a
restart, and storage failures that do not stop the traffic.

## Run the action-policy lifecycle test

`ActionPolicyLifecycleITCase` tests one persisted workflow through the management API and MCP: startup with an allow
posture, activation of a deny policy at run time, retention of a rejected candidate, rollback to an earlier revision,
and a restart of the process. The focused `ActionPolicy*ITCase` classes test the selectors, the precedence and the
validation in detail.

```bash
mvn verify -pl governance-tests -am -Dit.test='ActionPolicyLifecycleITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server
```

The test restarts the server and keeps its persistence directory. The capture fixture stays alive across the restart,
and the test registers its forward again before the last MCP requests.

## Run the OPA tests

The OPA tests need a Wanaku Server with the OPA evaluator, its `opa_allow_action.wasm`, and a native OPA binary.
`OpaPolicyITCase` starts a real OPA process. `OpaContractITCase` and `OpaGovernanceITCase` use a local HTTP stub
instead.

```bash
mvn verify -pl governance-tests -am -Dit.test='Opa*ITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku/target/debug/wanaku-server \
  -Dwanaku.test.opa.wasm=/path/to/wanaku/actions/dist/opa_allow_action.wasm \
  -Dwanaku.test.opa.binary=/path/to/opa
```

`wanaku.test.opa.wasm` is a different property from `wanaku.test.evaluator.wasm` (the safety review action).

## Skips and failures

A missing server binary, capture fixture or WASM action makes the tests skip. A configured path that is not valid,
and a behavior that does not match, make the tests fail. In GitHub CI, the `full-integration-test` workflow builds all
these artifacts, installs OPA 1.21.1 when the Wanaku source has the OPA action, and fails when an OPA test does not
run.

## Next steps

- [Fixtures and mocks](../fixtures/index.md)
- [Run the integration tests in GitHub CI](run-in-github-ci.md)
