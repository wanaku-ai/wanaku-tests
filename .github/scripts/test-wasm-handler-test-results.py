"""Regression checks for the required WASM handler execution gate."""

import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "wasm_handler_gate", Path(__file__).with_name("check-wasm-handler-test-results.py")
)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class WasmHandlerExecutionGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.reports = Path(self.directory.name)
        self.report = self.reports / "TEST-ai.wanaku.test.governance.WasmToolHandlerITCase.xml"
        self.suite = ET.Element("testsuite")
        for name in sorted(gate.EXPECTED):
            ET.SubElement(self.suite, "testcase", name=name)

    def write_report(self):
        ET.ElementTree(self.suite).write(self.report)

    def test_requires_every_scenario_once(self):
        self.write_report()
        self.assertEqual([], gate.check_reports(self.reports))
        self.suite.remove(self.suite[0])
        self.write_report()
        self.assertTrue(gate.check_reports(self.reports))

    def test_rejects_skips_failures_and_errors(self):
        for outcome in ("skipped", "failure", "error"):
            with self.subTest(outcome=outcome):
                marker = ET.SubElement(self.suite[0], outcome)
                self.write_report()
                self.assertTrue(gate.check_reports(self.reports))
                self.suite[0].remove(marker)

    def test_rejects_missing_empty_or_malformed_reports(self):
        self.assertTrue(gate.check_reports(self.reports))
        ET.ElementTree(ET.Element("testsuite")).write(self.report)
        self.assertTrue(gate.check_reports(self.reports))
        self.report.write_text("<testsuite>")
        self.assertTrue(gate.check_reports(self.reports))

    def test_rejects_duplicate_reports_and_scenarios(self):
        self.write_report()
        second = self.reports / "TEST-other.WasmToolHandlerITCase.xml"
        second.write_bytes(self.report.read_bytes())
        self.assertTrue(gate.check_reports(self.reports))
        second.unlink()
        ET.SubElement(self.suite, "testcase", name=self.suite[0].get("name"))
        self.write_report()
        self.assertTrue(gate.check_reports(self.reports))

    def test_rejects_unregistered_scenarios(self):
        ET.SubElement(self.suite, "testcase", name="unregisteredScenario")
        self.write_report()
        self.assertTrue(gate.check_reports(self.reports))

    def test_rejects_dtd_and_entity_payloads(self):
        secret = self.reports / "secret.txt"
        secret.write_text("synthetic-secret-must-not-be-read")
        declarations = (
            '<!DOCTYPE testsuite>',
            '<!DOCTYPE testsuite [<!ENTITY expansion "expanded">]>',
            f'<!DOCTYPE testsuite [<!ENTITY external SYSTEM "{secret.as_uri()}">]>',
        )
        for declaration in declarations:
            with self.subTest(declaration=declaration):
                self.report.write_text(declaration + '<testsuite><testcase name="blocked"/></testsuite>')
                errors = gate.check_reports(self.reports)
                self.assertTrue(errors)
                self.assertIn("invalid report", errors[0])
                self.assertNotIn(secret.read_text(), " ".join(errors))


if __name__ == "__main__":
    unittest.main()
