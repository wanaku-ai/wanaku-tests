#!/bin/bash
#
# Collects build outputs from source checkouts into the artifacts/ directory
# in the layout expected by the test framework (TestConfiguration).
#
# Expected source layout (created by the CI workflow):
#   source/wanaku-barn/                  -> wanaku-cli (native binary or Quarkus app)
#   source/wanaku-semantic-router/       -> wanaku-semantic-router fat JAR
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

# Semantic Router (fat JAR of the version in the checked-out pom.xml)
WSR_DIR="$SOURCE_DIR/wanaku-semantic-router"
WSR_VERSION=$(mvn -q -f "$WSR_DIR/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout)
WSR_JAR="$WSR_DIR/target/wanaku-semantic-router-${WSR_VERSION}-jar-with-dependencies.jar"

if [ -f "$WSR_JAR" ]; then
    echo "Collecting WSR from ${WSR_JAR}"
    mkdir -p "${ARTIFACTS_DIR}/wanaku-semantic-router"
    cp "$WSR_JAR" "${ARTIFACTS_DIR}/wanaku-semantic-router/wanaku-semantic-router.jar"
else
    echo "::error::Could not find WSR fat JAR ${WSR_JAR}"
    exit 1
fi

echo ""
echo "Collected artifacts:"
find "$ARTIFACTS_DIR" -maxdepth 2 -type f -name "*.jar" | sort
