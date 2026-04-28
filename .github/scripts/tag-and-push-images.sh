#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"
OWNER_LOWERCASE="${GITHUB_REPOSITORY_OWNER,,}"

IMAGES=(
  "ghcr.io/${OWNER_LOWERCASE}/peep-bot-backend"
)

for IMAGE in "${IMAGES[@]}"; do
  echo "Tagging ${IMAGE}:latest as ${IMAGE}:${VERSION}"
  docker tag "${IMAGE}:latest" "${IMAGE}:${VERSION}"
  echo "Pushing ${IMAGE}:${VERSION}"
  docker push "${IMAGE}:${VERSION}"
done
