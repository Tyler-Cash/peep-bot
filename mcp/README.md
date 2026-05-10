# peep-bot-mcp

A [Model Context Protocol](https://modelcontextprotocol.io) server that exposes
AST-aware code navigation and project-knowledge tools over the Peep Bot
codebase. Designed for AI agents (Claude Code, Cursor, etc.) that benefit from
structural queries instead of grepping blind.

The server uses [ast-grep](https://ast-grep.github.io) for structural queries
(Java + TypeScript/JavaScript), bundled [ripgrep](https://github.com/BurntSushi/ripgrep)
for text search, the [TypeScript language server](https://github.com/typescript-language-server/typescript-language-server)
for true semantic navigation (TS/TSX/JS/JSX), and project-specific scanners
for Spring annotations and Liquibase changelogs. Everything ships as npm
dependencies — no system installs beyond Node 20+.

## Tools

### Code navigation

| Tool | Purpose |
|------|---------|
| `outline_file(path)` | Top-level declarations in a single file. |
| `outline_directory(dir, glob?)` | Recursive outline across a directory. |
| `find_symbol(name, kind?, lang?, glob?)` | Locate a definition by AST node kind. Exact name match. |
| `find_references(name, glob?)` | Word-boundary text search for usages (text-only). |
| `ast_search(pattern, lang, glob?)` | Raw ast-grep pattern. `$VAR` for nodes, `$$$` for variadics. |
| `grep(pattern, glob?, ignoreCase?)` | Regex text search via ripgrep. |
| `lsp_references({path,line,character} \| {name,glob?,lang?})` | True semantic references. Routes by file extension: tsserver for TS/TSX/JS/JSX, jdtls for Java. |
| `lsp_definition({path,line,character} \| {name,glob?,lang?})` | Goto-definition. Picks the right server by extension. |
| `lsp_hover({path,line,character} \| {name,glob?,lang?})` | Hover info (signature, javadoc/jsdoc, inferred type). |

### Project knowledge

| Tool | Purpose |
|------|---------|
| `repo_overview()` | Structured summary: CLAUDE.md excerpt, file counts by stack, depth-2 layout. Good first call. |
| `list_endpoints(side?)` | All HTTP endpoints. Backend: Spring annotations with class-level base path applied. Frontend: filesystem-derived Next.js page/route paths. |
| `db_schema()` | Reduces the Liquibase changelog (following includes) into the current logical schema. |
| `run_test(side, pattern?, timeoutMs?)` | Run backend (`./gradlew test --tests`) or frontend (`npm run test`) tests. Returns a structured summary parsed from JUnit XML (failed tests with class, name, message, stack head) plus the last 80 lines of stdout. |
| `test_affinity(groupsOnly?)` | Approximate Spring `@SpringBootTest` context-cache groups. Hashes class-level Spring test annotations + `@MockBean` field set with one-level base-class inheritance and groups classes that share a hash. Members of a group probably reuse a cached `ApplicationContext` at runtime — gold for cross-class mock pollution / "who shares my context?" questions. |
| `test_mocks(type?)` | `@MockBean` / `@MockitoBean` / `@SpyBean` topology. With no args: all mocked types ranked by # of test classes mocking them. With `type='<fqn or simpleName>'`: just the test classes that mock that type. |
| `db_info()` | Discover the active Postgres: prefers a Testcontainers-managed pgvector container (the `SharedPostgres` used by the backend test suite), falls back to the docker-compose dev DB. Override with `PEEP_BOT_DB_URL`. |
| `db_query(sql, database?, allowWrite?, maxRows?)` | Run SQL against the discovered Postgres. Read-only by default (wraps in `BEGIN READ ONLY` / `ROLLBACK`, refuses write keywords). Use `database='test_<classname>'` to target a per-class isolated DB created by `SharedPostgres.registerIsolatedDatabase`. Statement timeout 10s. |

### Resources

The server exposes these files as MCP resources for clients that prefer
resource-style access:

- `peep://CLAUDE.md`
- `peep://README.md`
- `peep://docker-compose.yml`
- `peep://backend/application.yaml`
- `peep://backend/db.changelog-master.yaml`

## Caching

Results from `outline_*`, `find_symbol`, `ast_search`, `list_endpoints`,
`db_schema`, and `repo_overview` are cached in-memory for the life of the
server process. Each entry has a TTL (30s–5min depending on the tool) and is
also invalidated when the watched files' `mtime` changes — so you get speedups
within a session without staleness across edits. In the smoke test, repeat
calls drop from a subprocess spawn (~hundreds of ms) to ~3ms.

## Install & build

```bash
cd mcp
npm install
npm run build
```

## Smoke test

```bash
node scripts/smoke.mjs
```

Initializes the server over stdio, lists tools and resources, and runs a few
queries against the live tree.

## Wiring it into Claude Code

The repo ships a project-scoped `.mcp.json` at the root, so any dev opening
this repo in Claude Code is prompted to enable the `peep-bot` MCP on first use
— no manual install. The `mcp/bin/launch.sh` launcher runs `npm install` and
`tsc` automatically the first time it's invoked, and rebuilds whenever a
source file is newer than the compiled output.

To wire it up manually instead (e.g. user-scope, for use across multiple
checkouts):

```bash
claude mcp add peep-bot -- bash /abs/path/to/mcp/bin/launch.sh
```

Or add to `~/.claude.json` / a custom `.mcp.json`:

```json
{
  "mcpServers": {
    "peep-bot": {
      "command": "bash",
      "args": ["/abs/path/to/mcp/bin/launch.sh"]
    }
  }
}
```

`PEEP_BOT_ROOT` defaults to the parent of the `mcp/` directory.

## Notes & gotchas

- **Java patterns need modifiers.** ast-grep's Java grammar requires complete
  declarations. `class $N { $$$ }` won't match real Java; use
  `public class $N { $$$ }`. `find_symbol`, `outline_file`, and
  `outline_directory` work around this with kind-based YAML rules
  (`scan --inline-rules`). `ast_search` forwards the pattern verbatim.
- **`find_references` is text-only.** No semantic resolution — pair with
  `find_symbol` to disambiguate. For real semantic references prefer
  `lsp_references`, which routes to tsserver (TS/JS) or jdtls (Java) and
  resolves true symbols (cross-file imports, destructured rebinding, generic
  type args, etc.).
- **First LSP call is slow; subsequent calls are fast.**
  - TS: ~8s cold (tsserver project-load fallback timeout) → ~10–30ms warm.
  - Java: ~18–35s cold (jdtls init + Gradle project import) → ~120–200ms warm.

  The MCP server lazy-spawns one persistent language-server process per
  language and reuses it for the lifetime of the MCP server. The cold path
  waits for a ready signal (`$/progress` end for tsserver, `language/status`
  with `type: "ServiceReady"` for jdtls), with a hard-cap fallback (8s for
  TS, 180s for Java) if no signal arrives.
- **Java LSP needs jdtls.** First launch downloads the jdtls snapshot tarball
  (~50 MB) into `mcp/.cache/jdtls/` via `mcp/scripts/install-jdtls.sh` (called
  from `mcp/bin/launch.sh`). The workspace cache lives at
  `mcp/.cache/jdtls-workspace/`. Skip jdtls install with
  `PEEP_BOT_DISABLE_JDTLS=1`. Override the Java project root with
  `PEEP_BOT_JAVA_ROOT=<abs path>` (defaults to `<repo>/backend`) — pointing at
  the actual Gradle root keeps jdtls from importing stray sub-projects under
  the repo (e.g. `.claude/worktrees/agent-*/backend/`).
- **Liquibase coverage.** `db_schema` handles `createTable`, `addColumn`,
  `dropColumn`, `renameColumn`, `modifyDataType`, `dropTable`, `renameTable`,
  and follows `include` directives. Index/constraint/raw-SQL changesets are
  recorded as notes but not applied to the column model.
- **Path traversal.** Any path argument must resolve inside the configured
  repo root; the server rejects paths that escape it.
- **`run_test` is foreground.** Long-running test invocations block until
  complete or timeout; use the `timeoutMs` arg to cap.
