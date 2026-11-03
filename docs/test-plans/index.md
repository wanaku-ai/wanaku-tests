# Test plans

The test plans are Markdown documents in [`plans/`](../../plans) that
[Midnight Saci](https://orpiske.github.io/midnight-saci/) runs. They test Wanaku end to end in real environments, for
example OpenShift. They complement the integration tests, which test single features in GitHub CI.

## Pages in this section

- [About the test plans](about-test-plans.md): how the plans complement the integration tests, and how Saci runs them.
- [Run a test plan](run-a-test-plan.md): configure Saci, choose an environment, and read the result.
- [Test plan catalog](catalog.md): the plans and the environment that each one needs.
- [Credentials](credentials.md): how the plans get passwords, tokens and secrets.
- [Write a test plan](write-a-test-plan.md): the structure and the rules for a new plan.
- [Common procedures](common-steps.md): the shared steps in `plans/common/`.
- [Saci worker image](saci-worker-image.md): the worker image with the Wanaku binaries that runs the plans.

## Next steps

- [About the test plans](about-test-plans.md)
- [Fixtures and mocks](../fixtures/index.md)
