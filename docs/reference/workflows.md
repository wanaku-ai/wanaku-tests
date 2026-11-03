# Workflows

The GitHub Actions workflows of this repository are in
[`.github/workflows/`](../../.github/workflows). This page lists what starts each one and what it does.

## Tests

| Workflow | File | Starts on | Does |
|----------|------|-----------|------|
| Full Integration Test (From Source) | `full-integration-test.yml` | Manual run; a Wanaku pull request (through `dispatch-integration-tests.yml` of wanaku-ai/wanaku) | Builds all components from source, runs all modules, and posts the results on the pull request. See [Run in GitHub CI](../integration-tests/run-in-github-ci.md). |
| Integration Tests | `integration-tests.yml` | `wanaku-build-completed` repository dispatch; manual run with a `version` | Downloads released artifacts with `artifacts/download.sh` and runs all modules. |

## Builds and checks

| Workflow | File | Starts on | Does |
|----------|------|-----------|------|
| PR Builds | `pr-builds.yml` | Pull requests to `main`, except changes to `.github/`, Markdown and `docs/` only | `mvn -DskipTests package`. The build applies Spotless. |
| Build Main | `main-build.yml` | Pushes to `main`, with the same path filter; manual run | `mvn -DskipTests package`. |
| Static Analysis | `static-analysis.yml` | Pull requests to `main` | Runs PMD and SpotBugs and makes a report. |
| Post Static Analysis Comment | `static-analysis-comment.yml` | The end of a Static Analysis run | Posts the report as a comment on the pull request, and edits it on later runs. |
| Docs | `docs.yml` | Pushes to `main` and pull requests that change Markdown | Checks the links and anchors with lychee, and lints the Markdown with markdownlint. |

## Images

| Workflow | File | Starts on | Does |
|----------|------|-----------|------|
| Test MCP Server Container | `fixture-container.yml` | Changes to `fixtures/test-mcp-server/`; manual run | Builds the [test MCP server](../fixtures/test-mcp-server.md) image. Publishes `quay.io/wanaku/test-mcp-server` from `main`. |
| Saci Worker Container | `saci-container.yml` | Changes to `saci/`; a successful Test MCP Server Container run on `main`; manual run | Builds and checks the [Saci worker image](../test-plans/saci-worker-image.md). Publishes `quay.io/wanaku/wanaku-saci-worker` from `main`. |

## Next steps

- [Run the integration tests in GitHub CI](../integration-tests/run-in-github-ci.md)
- [System properties](system-properties.md)
