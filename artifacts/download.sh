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
    local extract_dir

    echo "Downloading ${name}..."
    curl -fSL -o "${zip_file}" "${url}"

    echo "Extracting ${name}..."
    # Extract into a unique temp directory so parallel extractions never write
    # to the same target file at the same time, then move the result in place.
    extract_dir="$(mktemp -d "${SCRIPT_DIR}/.extract-${name}.XXXXXX")"
    unzip -o -d "${extract_dir}" "${zip_file}"
    cp -a "${extract_dir}/." "${SCRIPT_DIR}/"

    rm -rf "${extract_dir}" "${zip_file}"
    echo "${name} ready."
}

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

# Wanaku server binary (optional — downloaded from wanaku releases)
download_server_binary() {
    local os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    local arch="$(uname -m)"
    case "$arch" in
        arm64) arch="aarch64" ;;
        x86_64) arch="x86_64" ;;
    esac

    local server_tag="${TAG}"
    local binary_name="wanaku-server-${os}-${arch}"
    local server_url="https://github.com/wanaku-ai/wanaku/releases/download/${server_tag}/${binary_name}"

    echo "Downloading wanaku-server binary (${binary_name})..."
    if curl -fSL -o "${SCRIPT_DIR}/${binary_name}" "${server_url}" 2>/dev/null; then
        chmod +x "${SCRIPT_DIR}/${binary_name}"
        echo "Wanaku server binary ready: ${binary_name}"
    else
        echo "Wanaku server binary not available at ${server_url} (this is optional)"
    fi
}

# Run all downloads in parallel and fail if any of them fails.
# Each download uses its own uniquely-named temp file, so parallel runs don't
# clash; total time drops from the sum of all downloads to the slowest one.
pids=()
download_and_extract "${ROUTER_URL}" "wanaku-router-backend" &
pids+=($!)
download_and_extract "${HTTP_URL}" "wanaku-tool-service-http" &
pids+=($!)
download_and_extract "${CLI_URL}" "wanaku-cli" &
pids+=($!)
download_and_extract "${FILE_PROVIDER_URL}" "wanaku-provider-file" &
pids+=($!)
download_jar "${CIC_URL}" "camel-integration-capability" "camel-integration-capability-main-${VERSION}-jar-with-dependencies.jar" &
pids+=($!)
download_server_binary &
pids+=($!)

failed=0
for pid in "${pids[@]}"; do
    if ! wait "${pid}"; then
        failed=1
    fi
done

if [ "${failed}" -ne 0 ]; then
    echo "One or more artifact downloads failed" >&2
    exit 1
fi

echo ""
echo "All artifacts downloaded to ${SCRIPT_DIR}"
