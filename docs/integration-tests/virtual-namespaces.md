# Virtual namespace tests

Use these tests to check the confirmed contracts of
[Wanaku virtual namespaces](https://github.com/wanaku-ai/wanaku/issues/2287).
[Test issue #335](https://github.com/wanaku-ai/wanaku-tests/issues/335) tracks the implementation and the remaining cases.

## Run the tests

Build the fixtures as described in [Run locally](run-locally.md). Supply a server binary that implements virtual
namespaces. Run the tests from the repository root:

```shell
mvn verify -pl router-tests,mcp-forwarding-tests,governance-tests -am \
  -Dit.test='VirtualNamespace*ITCase' -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dwanaku.test.server.binary=/path/to/wanaku-server
```

The tests skip when required infrastructure is absent. They do not skip when the server lacks virtual namespace
support. Such a server must fail the applicable assertions. Membership tests use a separate managed registry.

## Acceptance boundaries

The suites cover these contracts:

| Suite | Coverage |
|-------|----------|
| `VirtualNamespaceMembershipITCase` | API membership, invalid members, DNS names, configuration, snapshots, and restart |
| `VirtualNamespaceForwardingITCase` | Qualified tools, prompts, resource names, owner dispatch, isolation, live membership, forward removal, and enable/disable |
| `VirtualNamespaceCredentialITCase` | Concrete credential restrictions, upstream header injection, response redaction, and broker audit |
| `VirtualNamespacePolicyITCase` | Both policy scopes, correlated matched-rule audit evidence, and upstream dispatch |

The issue comments replace the proposed first-member collision rule with concrete namespace qualifiers.
The MCP endpoint is `/virtual-ns/{name}/mcp`. Members must be existing concrete namespaces.
Credentials must retain the concrete owner namespace and the owning forward ID.

The following decisions remain open in the upstream issue. Do not use a proposed behavior as an acceptance rule.

| Decision | Required tests after the decision |
|----------|-----------------------------------|
| Resource addressing | Duplicate resource URIs, qualified reads, template expansion, and original upstream URI recovery |
| Combined policy result | Virtual and concrete allow, deny, no-match, and error combinations; upstream counters for blocked requests |
| Direct registration | Forward, tool, resource, prompt, and agent registration through API and startup paths |
| Membership lifecycle | Empty and duplicate members, deletion of a referenced namespace, and concrete/virtual conversion |
| Name uniqueness | Concrete and virtual namespaces with the same name |
| A2A semantics | Discovery, qualified agent addresses, Agent Cards, message and task routing, governance, and audit |

A2A coverage is required for feature acceptance. Its explicit endpoint is `/virtual-ns/{name}/a2a`.
The comments do not yet define agent selection or task routing through that endpoint.
The existing [A2A proxy tests](a2a.md) provide concrete routing cases to extend after this decision.

Full acceptance also requires both namespace identities in audit, API schema checks, and admin UI checks.
Keep unfinished cases in issue #335. A successful compile or a run with skipped tests does not prove feature acceptance.

## Next steps

- [Run locally](run-locally.md)
- [Governance tests](governance.md)
- [A2A proxy tests](a2a.md)
