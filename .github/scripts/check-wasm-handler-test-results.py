"""Require every WASM tool handler scenario on handler-capable source builds."""

import sys
from collections import Counter
from pathlib import Path

import defusedxml.ElementTree as ET
from defusedxml.common import DefusedXmlException


EXPECTED = {
    "returnsCompleteResultsAndListsRuntimeTools",
    "dispatchPreservesTypedArgumentsAndToolErrors",
    "targetPolicyDenialPreventsInvocation",
    "targetValidationPreventsInvocation",
    "scopedCatalogPreservesIdentitySchemaAndProvenance",
    "replacementRemovalAndRestartPreserveLifecycle",
    "missingGrantDeniesDispatch",
    "executionLimitsFailWithoutPoisoningServer",
    "recursiveDispatchFailsPromptly",
}


def check_reports(reports):
    files = list(reports.glob("TEST-*.WasmToolHandlerITCase.xml"))
    if len(files) != 1:
        return [f"WasmToolHandlerITCase: expected one report, found {len(files)}"]
    try:
        cases = list(ET.parse(files[0], forbid_dtd=True).getroot().iter("testcase"))
    except (ET.ParseError, DefusedXmlException) as error:
        return [f"WasmToolHandlerITCase: invalid report: {error}"]
    errors = []
    counts = Counter(case.get("name", "") for case in cases)
    for name in sorted(EXPECTED):
        if counts[name] != 1:
            errors.append(f"{name}: expected one invocation, found {counts[name]}")
    for case in cases:
        name = case.get("name", "")
        if name not in EXPECTED:
            errors.append(f"Unexpected WASM handler scenario: {name}; update the CI contract")
        if any(case.find(outcome) is not None for outcome in ("skipped", "failure", "error")):
            errors.append(f"WasmToolHandlerITCase: {name} did not pass")
    return errors


def main():
    errors = check_reports(Path(sys.argv[1]))
    for error in errors:
        print(f"::error::{error}")
    if errors:
        return 1
    print(f"All {len(EXPECTED)} WASM handler scenarios executed without skips or failures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
