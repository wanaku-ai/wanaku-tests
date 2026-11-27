# Action-policy coverage map

This page maps the acceptance criteria of the action-policy feature
([wanaku#1870](https://github.com/wanaku-ai/wanaku/issues/1870)) to the integration tests that verify them. Use it to
find the test for a criterion, or to find the criteria that have no end-to-end test.

## Tickets

[wanaku-tests#228](https://github.com/wanaku-ai/wanaku-tests/issues/228) tracks this work. Each ticket added the test
classes in the table.

| Ticket | Upstream | Pull requests | Test classes |
|--------|----------|---------------|--------------|
| [#219](https://github.com/wanaku-ai/wanaku-tests/issues/219) pipeline order | wanaku#1900 | #235 | `ActionPolicyAllowITCase`, `ActionPolicyDenyITCase`, `ActionPolicyLabelSelectorITCase`, `ActionPolicyNamespaceScopingITCase`, `EvaluatorPipelineITCase` |
| [#220](https://github.com/wanaku-ai/wanaku-tests/issues/220) evaluator revisions | wanaku#1868 | #229 | `EvaluatorRevisionRecoveryITCase` (router-tests) |
| [#221](https://github.com/wanaku-ai/wanaku-tests/issues/221) selectors and predicates | wanaku#1902 | #239 | `ActionPolicySelectorITCase`, `ActionPolicyPredicateITCase`, `ActionPolicyValidationITCase` |
| [#222](https://github.com/wanaku-ai/wanaku-tests/issues/222) deny precedence | wanaku#1905 | #240 | `ActionPolicyPrecedenceITCase`, `EvaluatorPipelineITCase` |
| [#223](https://github.com/wanaku-ai/wanaku-tests/issues/223) request parsing | wanaku#1904 | #241 | `McpRequestParsingITCase` |
| [#224](https://github.com/wanaku-ai/wanaku-tests/issues/224) revision streams | wanaku#1901 | #242 | `RevisionStreamIsolationITCase` (router-tests) |
| [#225](https://github.com/wanaku-ai/wanaku-tests/issues/225) audit | wanaku#1869 | #243 | `AuditEventsITCase`, `AuditStorageITCase`, `AuditEvaluatorITCase` |
| [#226](https://github.com/wanaku-ai/wanaku-tests/issues/226) posture | wanaku#1872 | #304, #305, #306, #307 | `ActionPolicyPostureModeITCase`, `GovernancePostureDefaultsITCase`, `GovernanceStartupValidationITCase`, `LlmEvaluatorFailureITCase`, `EvaluatorPostureModeITCase`, `EvaluatorReadinessITCase` |
| [#227](https://github.com/wanaku-ai/wanaku-tests/issues/227) lifecycle | wanaku#1903 | #245 | `ActionPolicyLifecycleITCase` |

The governance-tests classes are in [`governance-tests`](../../governance-tests/src/test/java/ai/wanaku/test/governance). The router-tests classes are in
[`router-tests`](../../router-tests/src/test/java/ai/wanaku/test/router).

## Acceptance criteria

| Criterion of wanaku#1870 | Tests |
|--------------------------|-------|
| A documented action-policy schema is available | Not an integration test. The Wanaku documentation owns the schema. |
| Every rule has a stable identifier and an `allow` or `deny` effect | `ActionPolicyValidationITCase.acceptedPolicyPreservesStableIdsAndMetadataOnRetrieval` |
| Policies can match namespace and MCP method | `ActionPolicyNamespaceScopingITCase`, `ActionPolicySelectorITCase.selectorsMatchIndividuallyAndAsConjunction` |
| Policies can match tool, resource and prompt targets | `ActionPolicyDenyITCase`, `ActionPolicySelectorITCase` |
| Exact and validated glob target matching | `ActionPolicySelectorITCase.exactAndGlobNamesUseLiteralAndWildcardSemantics`, `ActionPolicyValidationITCase` |
| Registry-label matching | `ActionPolicyLabelSelectorITCase`, `ActionPolicySelectorITCase` |
| Bounded JSON Pointer predicates | `ActionPolicyPredicateITCase`, `McpRequestParsingITCase.preservesTypedArguments` |
| Invalid identifiers, selectors, pointers and predicates are rejected | `ActionPolicyValidationITCase.rejectsInvalidPolicyWithoutReplacingActiveState`, `GovernanceStartupValidationITCase` |
| All applicable rules are evaluated, and a deny wins over every allow | `ActionPolicyPrecedenceITCase`, `AuditEventsITCase` (all matched denies are recorded) |
| The decision does not depend on the rule order | `ActionPolicyPrecedenceITCase.activatedRuleOrderDoesNotChangeDenial` |
| Static allows do not bypass the evaluators | `EvaluatorPipelineITCase.staticPrecedenceControlsEvaluatorExecution` |
| Denials return stable reason codes and safe explanations | `ActionPolicyPrecedenceITCase`, `ActionPolicyDenyITCase` |
| Matched rule identifiers are available for audit | `AuditEventsITCase`, `ActionPolicyPostureModeITCase` |
| Policies load from `wanaku.yaml` | All governance-tests classes. `GovernanceTestBase` writes the policies into the generated `wanaku.yaml`. |
| Effective policies are visible in the management API | `ActionPolicyValidationITCase`, `ActionPolicyLifecycleITCase` |
| Unit tests cover each selector and predicate operator | Not an integration test. The Wanaku repository owns the unit tests. `ActionPolicyPredicateITCase` also tests each operator end to end. |
| Overlapping allows and denies in both declaration orders | `ActionPolicyPrecedenceITCase.activatedRuleOrderDoesNotChangeDenial` |
| Allowed and denied tool, resource and prompt operations | `ActionPolicyLifecycleITCase`, `ActionPolicyDenyITCase` |
| Documentation with configuration examples and decision semantics | Not an integration test. The Wanaku documentation owns it. |

The upstream design leaves out actor selectors and A2A adapters from the first version. No test covers them.

## Open work

- Reproducible WASM and processor failures (traps, fuel) of the posture work continue in
  [wanaku-tests#333](https://github.com/wanaku-ai/wanaku-tests/issues/333).

## CI evidence

The `full-integration-test` workflow builds every component from source and runs all the classes on this page. Run
[45135721630](https://github.com/wanaku-ai/wanaku-tests/actions/runs/45135721630) (2026-11-23, Wanaku `main` with one
dependency update) passed 167 tests in these classes, with no failures and no skips. To get new evidence, start the
workflow as [Run in GitHub CI](run-in-github-ci.md) describes.

## Next steps

- [Governance tests](governance.md)
- [Run in GitHub CI](run-in-github-ci.md)
