#!/bin/bash
#
# Downloads pre-built Wanaku artifacts from GitHub releases.
# Usage: ./artifacts/download.sh <version>
# Examples:
#   ./artifacts/download.sh 0.1.0              # release
#   ./artifacts/download.sh 0.1.1-SNAPSHOT     # snapshot (early-access)
#

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 <version>" >&2
    echo "Example: $0 0.1.0" >&2
    exit 1
fi

VERSION="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$VERSION" == *-SNAPSHOT ]]; then
    TAG="early-access"
    OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
    ARCH="$(uname -m)"
    case "$ARCH" in
        arm64) ARCH="aarch64" ;;
    esac
    PLATFORM="-${OS}-${ARCH}"
else
    TAG="v${VERSION}"
    PLATFORM=""
fi

ROUTER_URL="https://github.com/wanaku-ai/wanaku/releases/download/${TAG}/wanaku-router-backend-${VERSION}.zip"
HTTP_URL="https://github.com/wanaku-ai/wanaku/releases/download/${TAG}/wanaku-tool-service-http-${VERSION}.zip"
CLI_URL="https://github.com/wanaku-ai/wanaku/releases/download/${TAG}/wanaku-cli-${VERSION}${PLATFORM}.zip"
FILE_PROVIDER_URL="https://github.com/wanaku-ai/wanaku-examples/releases/download/${TAG}/wanaku-provider-file-${VERSION}.zip"
CIC_URL="https://github.com/wanaku-ai/camel-integration-capability/releases/download/${TAG}/camel-integration-capability-main-${VERSION}-jar-with-dependencies.jar"

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

download_jar "${CIC_URL}" "camel-integration-capability" "camel-integration-capability-main-${VERSION}-jar-with-dependencies.jar"

echo ""
echo "All artifacts downloaded to ${SCRIPT_DIR}"
