#!/usr/bin/env bash
set -euo pipefail

# Tag the existing :latest manifest in GHCR as :VERSION via buildx imagetools.
# Purely a registry-side operation — no `docker pull` or local image required,
# which means the release job doesn't have to round-trip the ~200MB image
# through the runner just to add another tag pointing at the same digest.
VERSION="$1"
OWNER_LOWERCASE="${GITHUB_REPOSITORY_OWNER,,}"

IMAGES=(
  "ghcr.io/${OWNER_LOWERCASE}/peep-bot-backend"
)

for IMAGE in "${IMAGES[@]}"; do
  echo "Tagging ${IMAGE}:latest as ${IMAGE}:${VERSION} (registry-side)"
  docker buildx imagetools create --tag "${IMAGE}:${VERSION}" "${IMAGE}:latest"
done
