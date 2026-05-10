#!/usr/bin/env bash
# Launcher for peep-bot-mcp. Installs deps and builds the server on first run
# (or when sources are newer than the compiled output), then execs the server
# over stdio. Designed to be invoked from .mcp.json.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MCP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$MCP_DIR"

LOG() { echo "[peep-bot-mcp] $*" >&2; }

if ! command -v node >/dev/null 2>&1; then
  LOG "node is not on PATH; install Node 20+ and retry."
  exit 127
fi

# Install deps if missing.
if [ ! -d node_modules ] || [ ! -x node_modules/.bin/tsc ]; then
  LOG "installing dependencies (first run)..."
  npm install --silent --no-audit --no-fund >&2
fi

# Build if dist is missing or any src file is newer than the compiled entry.
needs_build=0
if [ ! -f dist/index.js ]; then
  needs_build=1
else
  newest_src=$(find src -type f -newer dist/index.js -print -quit 2>/dev/null || true)
  if [ -n "$newest_src" ]; then
    needs_build=1
  fi
fi

if [ "$needs_build" -eq 1 ]; then
  LOG "compiling TypeScript..."
  ./node_modules/.bin/tsc >&2
fi

# Install jdtls on first run (no-op if already present). Skip with PEEP_BOT_DISABLE_JDTLS=1.
if [ -z "${PEEP_BOT_DISABLE_JDTLS:-}" ]; then
  if ! compgen -G "$MCP_DIR/.cache/jdtls/plugins/org.eclipse.equinox.launcher_*.jar" >/dev/null; then
    bash "$MCP_DIR/scripts/install-jdtls.sh" >&2 || LOG "jdtls install failed; Java LSP tools will be unavailable."
  fi
fi

# Resolve PEEP_BOT_ROOT to the repo root (parent of mcp/) if not provided.
if [ -z "${PEEP_BOT_ROOT:-}" ]; then
  export PEEP_BOT_ROOT="$(cd "$MCP_DIR/.." && pwd)"
fi

exec node dist/index.js
