#!/bin/bash
#
# Collects build outputs from source checkouts into the artifacts/ directory
# in the layout expected by the test framework (TestConfiguration.findJar).
#
# Expected source layout (created by the CI workflow):
#   source/wanaku/             -> wanaku-router-backend, wanaku-tool-service-http, wanaku-cli
#   source/wanaku-examples/    -> wanaku-provider-file
#   source/camel-integration-capability/ -> camel-integration-capability-main fat JAR
#

set -euo pipefail

ARTIFACTS_DIR="$(pwd)/artifacts"
SOURCE_DIR="$(pwd)/source"

mkdir -p "$ARTIFACTS_DIR"

collect_quarkus_app() {
    local source_root="$1"
    local module_name="$2"
    local target_name="$3"

    local quarkus_app
    quarkus_app=$(find "$source_root" -type d -name "quarkus-app" \
        -path "*/${module_name}/target/*" 2>/dev/null | head -1)

    if [ -z "$quarkus_app" ]; then
        echo "::warning::Could not find quarkus-app for ${target_name} (module: ${module_name})"
        return 1
    fi

    echo "Collecting ${target_name} from ${quarkus_app}"
    mkdir -p "${ARTIFACTS_DIR}/${target_name}"
    cp -r "$quarkus_app"/* "${ARTIFACTS_DIR}/${target_name}/"
}

# Router and HTTP Tool Service from wanaku
collect_quarkus_app "$SOURCE_DIR/wanaku" "wanaku-router-backend" "wanaku-router-backend"
collect_quarkus_app "$SOURCE_DIR/wanaku" "wanaku-tool-service-http" "wanaku-tool-service-http"

# CLI from wanaku (may be a Quarkus app or a native binary)
CLI_NATIVE=$(find "$SOURCE_DIR/wanaku" -type f -name "wanaku" \
    -path "*/wanaku-cli/target/*" ! -path "*.jar" 2>/dev/null | head -1)

if [ -n "$CLI_NATIVE" ]; then
    echo "Collecting CLI (native) from ${CLI_NATIVE}"
    mkdir -p "${ARTIFACTS_DIR}/wanaku-cli/bin"
    cp "$CLI_NATIVE" "${ARTIFACTS_DIR}/wanaku-cli/bin/wanaku"
    chmod +x "${ARTIFACTS_DIR}/wanaku-cli/bin/wanaku"
else
    collect_quarkus_app "$SOURCE_DIR/wanaku" "wanaku-cli" "wanaku-cli" || true
fi

# File Provider from wanaku-examples
collect_quarkus_app "$SOURCE_DIR/wanaku-examples" "wanaku-provider-file" "wanaku-provider-file"

# Camel Integration Capability (fat JAR — may use assembly-plugin or shade-plugin)
CIC_JAR=$(find "$SOURCE_DIR/camel-integration-capability" -path "*/target/*" \
    \( -name "*-jar-with-dependencies.jar" -o -name "*-shaded.jar" -o -name "*-runner.jar" \) \
    2>/dev/null | head -1)

if [ -n "$CIC_JAR" ]; then
    echo "Collecting CIC from ${CIC_JAR}"
    mkdir -p "${ARTIFACTS_DIR}/camel-integration-capability"
    cp "$CIC_JAR" "${ARTIFACTS_DIR}/camel-integration-capability/"
else
    echo "::warning::Could not find CIC fat JAR"
fi

echo ""
echo "Collected artifacts:"
find "$ARTIFACTS_DIR" -maxdepth 2 -type f -name "*.jar" | sort
