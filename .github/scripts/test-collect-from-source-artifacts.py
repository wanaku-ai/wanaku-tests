"""Regression checks for artifact collection from source builds."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("collect-from-source-artifacts.sh").resolve()


class SourceArtifactCollectionTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        source = self.root / "source"
        (source / "wanaku-barn").mkdir(parents=True)
        (source / "camel-integration-capability").mkdir()
        self.target = source / "wanaku-semantic-router" / "target"
        self.target.mkdir(parents=True)
        (self.target.parent / "pom.xml").write_text(
            "<project><version>0.4.0-SNAPSHOT</version></project>"
        )
        self.jar = self.target / "wanaku-semantic-router-0.4.0-SNAPSHOT-jar-with-dependencies.jar"
        self.jar.write_bytes(b"current fat jar contents")
        binary_directory = self.root / "bin"
        binary_directory.mkdir()
        maven = binary_directory / "mvn"
        maven.write_text(
            '#!/bin/sh\n'
            '[ "$1" = "-q" ] && [ "$2" = "-f" ] && [ -f "$3" ] || exit 1\n'
            '[ "$4" = "help:evaluate" ] && [ "$5" = "-Dexpression=project.version" ] || exit 1\n'
            '[ "$6" = "-DforceStdout" ] || exit 1\n'
            'printf "0.4.0-SNAPSHOT"\n'
        )
        maven.chmod(0o755)
        self.environment = {**os.environ, "PATH": str(binary_directory) + os.pathsep + os.environ["PATH"]}

    def collect(self):
        return subprocess.run(
            ["bash", str(SCRIPT)], cwd=self.root, env=self.environment,
            capture_output=True, text=True, timeout=15
        )

    def assert_distribution(self):
        result = self.collect()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        destination = self.root / "artifacts" / "wanaku-semantic-router"
        self.assertEqual(self.jar.read_bytes(), (destination / "wanaku-semantic-router.jar").read_bytes())
        self.assertFalse((destination / "lib").exists())

    def test_collects_fat_jar_without_libraries(self):
        self.assert_distribution()

    def test_collects_fat_jar_when_thin_jar_also_exists(self):
        (self.target / "wanaku-semantic-router-0.4.0-SNAPSHOT.jar").write_bytes(b"thin jar contents")
        self.assert_distribution()

    def test_ignores_stale_version_fat_jar(self):
        (self.target / "wanaku-semantic-router-0.3.0-jar-with-dependencies.jar").write_bytes(b"stale jar")
        self.assert_distribution()

    def test_does_not_copy_thin_distribution_libraries(self):
        libraries = self.target / "lib"
        libraries.mkdir()
        (libraries / "dependency.jar").write_bytes(b"thin distribution dependency")
        self.assert_distribution()

    def test_rejects_missing_current_fat_jar_even_when_other_artifacts_exist(self):
        self.jar.unlink()
        (self.target / "wanaku-semantic-router-0.4.0-SNAPSHOT.jar").write_bytes(b"thin jar")
        (self.target / "wanaku-semantic-router-0.3.0-jar-with-dependencies.jar").write_bytes(b"stale jar")
        result = self.collect()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Could not find WSR fat JAR", result.stdout)
        self.assertIn(self.jar.name, result.stdout)


if __name__ == "__main__":
    unittest.main()
