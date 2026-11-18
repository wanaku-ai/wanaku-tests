"""Regression checks for artifact collection from source builds."""

import shutil
import subprocess
import tempfile
import unittest
import zipfile
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
        self.jar = self.target / "wanaku-semantic-router-0.4.0-SNAPSHOT.jar"
        self.write_jar(self.jar)
        libraries = self.target / "lib"
        libraries.mkdir()
        self.dependency = libraries / "dependency.jar"
        self.dependency.write_bytes(b"dependency contents")

    def write_jar(self, path):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nMain-Class: org.wanaku.wsr.Application\n\n",
            )

    def collect(self):
        return subprocess.run(
            ["bash", str(SCRIPT)], cwd=self.root, capture_output=True, text=True, timeout=15
        )

    def assert_distribution(self):
        result = self.collect()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        destination = self.root / "artifacts" / "wanaku-semantic-router"
        self.assertEqual(self.jar.read_bytes(), (destination / "wanaku-semantic-router.jar").read_bytes())
        self.assertEqual(self.dependency.read_bytes(), (destination / "lib" / "dependency.jar").read_bytes())

    def test_collects_thin_jar_and_libraries(self):
        self.assert_distribution()

    def test_collects_thin_distribution_when_fat_jar_also_exists(self):
        fat = self.target / "wanaku-semantic-router-0.4.0-SNAPSHOT-jar-with-dependencies.jar"
        self.write_jar(fat)
        with zipfile.ZipFile(fat, "a") as archive:
            archive.writestr("fat-only.class", b"fat jar contents")
        self.assert_distribution()

    def test_rejects_fat_jar_without_thin_jar(self):
        self.jar.rename(self.target / "wanaku-semantic-router-0.4.0-SNAPSHOT-jar-with-dependencies.jar")
        result = self.collect()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Expected one WSR thin executable JAR, found 0", result.stderr)

    def test_rejects_multiple_thin_executables(self):
        self.write_jar(self.target / "wanaku-semantic-router-older.jar")
        result = self.collect()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Expected one WSR thin executable JAR, found 2", result.stderr)

    def test_rejects_missing_or_empty_libraries(self):
        self.dependency.unlink()
        for missing in (False, True):
            with self.subTest(missing=missing):
                if missing:
                    shutil.rmtree(self.target / "lib")
                result = self.collect()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("WSR dependency distribution missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
