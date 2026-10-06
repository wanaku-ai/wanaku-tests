#!/usr/bin/env bash
set -euo pipefail

IMAGE="${1:-quay.io/wanaku/test-mcp-server}"
IMAGE_TAG="${2:?usage: build-manifests.sh <image> <sha-tag>}"

ARCH_AMD64="${IMAGE}:${IMAGE_TAG}-amd64"
ARCH_ARM64="${IMAGE}:${IMAGE_TAG}-arm64"

create_manifest() {
  local tag="$1"

  echo "Creating multi-architecture manifest ${IMAGE}:${tag}"
  docker buildx imagetools create \
    --tag "${IMAGE}:${tag}" \
    "${ARCH_AMD64}" \
    "${ARCH_ARM64}"
}

verify_manifest() {
  local tag="$1"
  local output

  echo "Verifying ${IMAGE}:${tag}"
  output="$(docker buildx imagetools inspect "${IMAGE}:${tag}")"
  printf "%s\n" "${output}"

  grep -q "linux/amd64" <<< "${output}"
  grep -q "linux/arm64" <<< "${output}"
}

# Both the immutable SHA manifest and latest point to the exact same
# architecture-specific images produced by this workflow run.
create_manifest "${IMAGE_TAG}"
create_manifest "latest"

verify_manifest "${IMAGE_TAG}"
verify_manifest "latest"
