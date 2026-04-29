#!/bin/bash
#
# Downloads pre-built Wanaku artifacts from GitHub releases.
# Usage: ./artifacts/download.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ROUTER_URL="https://github.com/wanaku-ai/wanaku/releases/download/v0.1.0/wanaku-router-backend-0.1.0.zip"
HTTP_URL="https://github.com/wanaku-ai/wanaku/releases/download/v0.1.0/wanaku-tool-service-http-0.1.0.zip"
CLI_URL="https://github.com/wanaku-ai/wanaku/releases/download/v0.1.0/wanaku-cli-0.1.0.zip"
FILE_PROVIDER_URL="https://github.com/wanaku-ai/wanaku-examples/releases/download/v0.1.0/wanaku-provider-file-0.1.0.zip"
CIC_URL="https://github.com/wanaku-ai/camel-integration-capability/releases/download/v0.1.0/camel-integration-capability-main-0.1.0-jar-with-dependencies.jar"

download_and_extract() {
    local url="$1"
    local name="$2"
    local zip_file="${SCRIPT_DIR}/${name}.zip"

    echo "Downloading ${name}..."
    curl -fSL -o "${zip_file}" "${url}"

    echo "Extracting ${name}..."
    unzip -o -d "${SCRIPT_DIR}" "${zip_file}"

    rm -f "${zip_file}"
    echo "${name} ready."
}

download_and_extract "${ROUTER_URL}" "wanaku-router-backend"
download_and_extract "${HTTP_URL}" "wanaku-tool-service-http"
download_and_extract "${CLI_URL}" "wanaku-cli"
download_and_extract "${FILE_PROVIDER_URL}" "wanaku-provider-file"

# CIC is a single fat JAR (not a ZIP) — download directly
download_jar() {
    local url="$1"
    local dir="$2"
    local filename="$3"

    mkdir -p "${SCRIPT_DIR}/${dir}"

    echo "Downloading ${filename}..."
    curl -fSL -o "${SCRIPT_DIR}/${dir}/${filename}" "${url}"

    echo "${filename} ready."
}

download_jar "${CIC_URL}" "camel-integration-capability" "camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar"

echo ""
echo "All artifacts downloaded to ${SCRIPT_DIR}"
