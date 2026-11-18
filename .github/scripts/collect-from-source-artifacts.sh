#!/bin/bash
#
# Collects build outputs from source checkouts into the artifacts/ directory
# in the layout expected by the test framework (TestConfiguration).
#
# Expected source layout (created by the CI workflow):
#   source/wanaku-barn/                  -> wanaku-cli (native binary or Quarkus app)
#   source/wanaku-semantic-router/       -> executable thin JAR + adjacent lib/
#   source/camel-integration-capability/ -> camel-integration-capability-main fat JAR
#

set -euo pipefail

ARTIFACTS_DIR="$(pwd)/artifacts"
SOURCE_DIR="$(pwd)/source"

mkdir -p "$ARTIFACTS_DIR"

# CLI from wanaku-barn (may be a native binary or a Quarkus app)
CLI_NATIVE=$(find "$SOURCE_DIR/wanaku-barn" -type f -name "wanaku" \
    -path "*/wanaku-cli/target/*" ! -name "*.jar" 2>/dev/null | head -1)

if [ -n "$CLI_NATIVE" ]; then
    echo "Collecting CLI (native) from ${CLI_NATIVE}"
    mkdir -p "${ARTIFACTS_DIR}/wanaku-cli/bin"
    cp "$CLI_NATIVE" "${ARTIFACTS_DIR}/wanaku-cli/bin/wanaku"
    chmod +x "${ARTIFACTS_DIR}/wanaku-cli/bin/wanaku"
else
    CLI_QUARKUS=$(find "$SOURCE_DIR/wanaku-barn" -type d -name "quarkus-app" \
        -path "*/wanaku-cli/target/*" 2>/dev/null | head -1)
    if [ -n "$CLI_QUARKUS" ]; then
        echo "Collecting CLI (Quarkus) from ${CLI_QUARKUS}"
        mkdir -p "${ARTIFACTS_DIR}/wanaku-cli"
        cp -r "$CLI_QUARKUS"/* "${ARTIFACTS_DIR}/wanaku-cli/"
    else
        echo "::warning::Could not find CLI in wanaku-barn build output"
    fi
fi

# Camel Integration Capability (fat JAR from the -main module, not the -plugin module)
CIC_JAR=$(find "$SOURCE_DIR/camel-integration-capability" \
    -path "*/camel-integration-capability-main/target/*" \
    -name "*-jar-with-dependencies.jar" \
    2>/dev/null | head -1)

if [ -n "$CIC_JAR" ]; then
    echo "Collecting CIC from ${CIC_JAR}"
    mkdir -p "${ARTIFACTS_DIR}/camel-integration-capability"
    cp "$CIC_JAR" "${ARTIFACTS_DIR}/camel-integration-capability/"
else
    echo "::warning::Could not find CIC fat JAR"
fi

# WSR also builds a fat JAR. Select the thin JAR and preserve its relative lib/ classpath.
python3 - "$SOURCE_DIR/wanaku-semantic-router" "$ARTIFACTS_DIR/wanaku-semantic-router" <<'PYTHON'
import shutil
import sys
import zipfile
from pathlib import Path

source, destination = map(Path, sys.argv[1:])
candidates = []
for jar in source.glob("**/target/*.jar"):
    if jar.name.endswith("-jar-with-dependencies.jar"):
        continue
    with zipfile.ZipFile(jar) as archive:
        try:
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        except KeyError:
            continue
    if any(line.startswith("Main-Class:") for line in manifest.splitlines()):
        candidates.append(jar)
if len(candidates) != 1:
    sys.exit(f"::error::Expected one WSR thin executable JAR, found {len(candidates)}: {candidates}")
jar = candidates[0]
libraries = jar.parent / "lib"
if not libraries.is_dir() or not any(libraries.glob("*.jar")):
    sys.exit(f"::error::WSR dependency distribution missing: {libraries}")
destination.mkdir(parents=True, exist_ok=True)
shutil.copy2(jar, destination / "wanaku-semantic-router.jar")
shutil.copytree(libraries, destination / "lib", dirs_exist_ok=True)
print(f"Collected WSR distribution from {jar}")
PYTHON

echo ""
echo "Collected artifacts:"
find "$ARTIFACTS_DIR" -maxdepth 2 -type f -name "*.jar" | sort
