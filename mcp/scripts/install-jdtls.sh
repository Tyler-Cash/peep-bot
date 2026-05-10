#!/usr/bin/env bash
# Idempotent jdtls install. Downloads the latest snapshot tarball into
# .cache/jdtls/ and exits. Re-running is a no-op once the launcher jar exists.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MCP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE="$MCP_DIR/.cache"
DEST="$CACHE/jdtls"
URL="${JDTLS_URL:-https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz}"

LOG() { echo "[install-jdtls] $*" >&2; }

if compgen -G "$DEST/plugins/org.eclipse.equinox.launcher_*.jar" >/dev/null; then
  LOG "jdtls already installed at $DEST"
  exit 0
fi

mkdir -p "$CACHE"
TARBALL="$CACHE/jdtls.tar.gz"
if [ ! -s "$TARBALL" ]; then
  LOG "downloading $URL ..."
  curl -fSL -o "$TARBALL" "$URL"
fi

mkdir -p "$DEST"
LOG "extracting into $DEST ..."
tar -xzf "$TARBALL" -C "$DEST"

if compgen -G "$DEST/plugins/org.eclipse.equinox.launcher_*.jar" >/dev/null; then
  LOG "installed."
else
  LOG "extraction did not produce a launcher jar — aborting."
  exit 1
fi
