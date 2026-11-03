# Overview of the Wanaku tests

This repository tests Wanaku as a system. The unit tests of each Wanaku repository test the code of one component.
The tests here start the real components and check that they work together.

## Two kinds of tests

| | Integration tests | Test plans |
|---|---|---|
| What | Java tests in Maven modules (`*ITCase`) | Markdown plans in `plans/` |
| Runs in | GitHub CI, for each Wanaku pull request | A real environment, with [Midnight Saci](https://orpiske.github.io/midnight-saci/) |
| Checks | One feature at a time, with local processes | A complete workflow after a real deployment, for example on OpenShift |
| Read | [Integration tests](../integration-tests/index.md) | [Test plans](../test-plans/index.md) |

Both kinds use the [fixtures and mocks](../fixtures/index.md) of this repository: small MCP servers, a WebAssembly
evaluator, Camel routes and stubs for the LLM and OPA. They make the results reproducible, and they need no
credentials or paid services.

## Repository map

```text
wanaku-tests/
├── docs/                          contributor documentation (you are here)
├── test-common/                   shared framework: base classes, clients, process managers, stubs
├── router-tests/                  namespaces, forwards, prompts, evaluator revisions, CLI
├── mcp-forwarding-tests/          MCP forwarding and header forwarding
├── resources-tests/               resources and resource templates
├── governance-tests/              action policies, evaluators, OPA, audit
├── camel-integration-capability-tests/   Camel Integration Capability tools
├── semantic-router-tests/         Semantic Router routing and audit, cloud run CLI
├── a2a-tests/                     managed A2A proxy
├── fixtures/                      test-mcp-server, governance-capture-server, evaluator-block
├── plans/                         Saci test plans; plans/common: shared procedures; plans/setup: OpenShift setup
├── saci/                          Saci worker image
└── load/                          k6 load tests
```

## Next steps

- [Test a Wanaku pull request](test-a-wanaku-pr.md)
- [Integration test modules](../integration-tests/modules.md)
- [About the test plans](../test-plans/about-test-plans.md)
