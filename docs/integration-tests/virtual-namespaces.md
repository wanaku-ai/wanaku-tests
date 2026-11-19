# Virtual namespace tests

Use these tests to check the confirmed contracts of
[Wanaku virtual namespaces](https://github.com/wanaku-ai/wanaku/issues/2287).
[Test issue #335](https://github.com/wanaku-ai/wanaku-tests/issues/335) tracks the initial coverage.
[Follow-up issue #337](https://github.com/wanaku-ai/wanaku-tests/issues/337) tracks fixture safety, audit identities,
load rejection, and virtual A2A integration coverage.

## Run the tests

Use [Run in GitHub CI](run-in-github-ci.md) to run Full Integration Test (From Source) on your fork.
Select the test branch with `--ref`. Select a Wanaku source revision that implements virtual namespaces.
The workflow builds the fixtures and runs the module tests. It also runs the capture fixture unit tests.

The tests skip when required infrastructure is absent. They do not skip when the server lacks virtual namespace
support. Such a server must fail the applicable assertions. Membership tests use a separate managed registry.

## Acceptance boundaries

The suites cover these contracts:

| Suite | Coverage |
|-------|----------|
| `VirtualNamespaceMembershipITCase` | API membership, invalid members, DNS names, explicit load rejection, snapshots, and restart |
| `VirtualNamespaceForwardingITCase` | Qualified tools, prompts, resource names, owner dispatch, isolation, live membership, forward removal, and enable/disable |
| `VirtualNamespaceCredentialITCase` | Concrete credential restrictions, upstream header injection, response redaction, and broker audit |
| `VirtualNamespacePolicyITCase` | Both policy scopes, virtual and concrete audit identities, correlated matched rules, and upstream dispatch |
| `VirtualNamespaceA2aITCase` | Qualified discovery and Agent Cards, message and task owner isolation, live agent changes, dual-scope governance, and audit identities |

The issue comments replace the proposed first-member collision rule with concrete namespace qualifiers.
The MCP endpoint is `/virtual-ns/{name}/mcp`. Members must be existing concrete namespaces.
Credentials must retain the concrete owner namespace and the owning forward ID.

The initial issue left several design questions open. This suite does not establish full acceptance for the
following cases. Check the implementation contract before adding assertions.

| Decision | Required tests after the decision |
|----------|-----------------------------------|
| Resource addressing | Duplicate resource URIs, qualified reads, template expansion, and original upstream URI recovery |
| Combined policy result | Virtual and concrete allow, deny, no-match, and error combinations; upstream counters for blocked requests |
| Direct registration | Forward, tool, resource, prompt, and agent registration through API and startup paths |
| Membership lifecycle | Empty and duplicate members, deletion of a referenced namespace, and concrete/virtual conversion |
| Name uniqueness | Concrete and virtual namespaces with the same name |

See [A2A proxy tests](a2a.md) for virtual discovery, protocol and Agent Card routes, protocol scope, and integration
coverage.

## Coverage in Wanaku

The Wanaku implementation has additional tests. These checks belong with the server and UI source, rather
than duplicate browser or schema harnesses in this repository.

- [OpenAPI and runtime membership validation](https://github.com/wanaku-ai/wanaku/blob/ebc20283d78aff646b9217d4b093f1a9b60c085b/server/src/openapi.rs):
  `virtual_namespace_members_match_schema_and_runtime_validation` checks the schema, the `targets` alias, invalid
  updates, retained state, and deletion of a referenced namespace.
- [Namespace browser tests](https://github.com/wanaku-ai/wanaku/blob/ebc20283d78aff646b9217d4b093f1a9b60c085b/tests/e2e/ui/tests/namespaces.spec.ts):
  virtual create, edit, and delete; the badge, membership, and qualified endpoint; and concrete-only member and
  direct-registration selectors.

The links identify the implementation revision used for the initial integration runs. They do not replace CI
validation of the revision under test. A successful compile or a run with skipped tests does not prove feature acceptance.

## Next steps

- [Run in GitHub CI](run-in-github-ci.md)
- [Governance tests](governance.md)
- [A2A proxy tests](a2a.md)
