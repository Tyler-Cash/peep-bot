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

### LSP semantic navigation

Backed by `typescript-language-server` (TS/TSX/JS/JSX) and `jdtls` (Java).
Cold start is paid once per server lifetime: tsserver ~8s, jdtls ~18–35s
(jdtls workspace cached at `mcp/.cache/jdtls-workspace/`). Subsequent calls
are 10–200ms. Both servers are pre-warmed in the background on MCP startup.

| Tool | Purpose |
|------|---------|
| `lsp_references({path,line,character} \| {name,glob?,lang?,includeDeclaration?})` | True semantic references. Routes by extension. |
| `lsp_definition(...)` | Goto-definition. |
| `lsp_hover(...)` | Hover info (signature, javadoc/jsdoc, inferred type). |
| `lsp_call_hierarchy({...,direction?})` | Who calls X (incoming) or what X calls (outgoing). Strictly more focused than references. |
| `lsp_workspace_symbol(query, lang?, limit?)` | Fuzzy 'find me everything matching query' across the project. Both servers consulted by default. |
| `lsp_diagnostics(path?, lang?, severity?)` | Compile/type/lint diagnostics. Per-file or workspace-wide. |

### Code navigation

| Tool | Purpose |
|------|---------|
| `outline_file(path)` | Top-level declarations in a single file. |
| `outline_directory(dir, glob?)` | Recursive outline across a directory. |
| `find_symbol(name, kind?, lang?, glob?)` | Locate a definition by AST node kind. Exact name match. |
| `find_references(name, glob?)` | Word-boundary text search for usages (text-only). |
| `ast_search(pattern, lang, glob?)` | Raw ast-grep pattern. `$VAR` for nodes, `$$$` for variadics. |
| `ast_rewrite(pattern, rewrite, lang, glob?, apply?)` | AST-aware bulk codemod. Preview by default; `apply=true` writes in place. Far safer than regex codemods for structural changes. |
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

### Project synthesizers (peep-bot specific)

| Tool | Purpose |
|------|---------|
| `lifecycle_registry()` | Derived registry of `DurableEventListener` implementations grouped by event type, with retry/component annotations and constructor deps. Replaces the hand-maintained `docs/event-state-machine.md` table. |
| `discord_interaction_map()` | Slash commands, subcommands, button IDs, modal IDs, and listener handler overrides discovered by scanning JDA usage in `discord/` and `contract/`. |
| `msw_spring_diff()` | Diff frontend MSW handlers against Spring endpoints. Surfaces stale mocks (will 404 in live mode) and Spring endpoints lacking a mock (frontend will fail in mock mode). |
| `migration_lint()` | Lint Liquibase changesets for risky patterns: NOT NULL adds without defaults, FKs without indexes, drops without rollback, raw-SQL `CREATE INDEX` without `CONCURRENTLY`. |
| `spring_property(key, profiles?)` | Resolve a Spring property under an explicit profile list, walking `application.yaml` + `application-{profile}.yaml` in Spring's merge order. Reports value + source file + matching `@ConfigurationProperties` bindings. Approximation — doesn't follow env vars, CLI args, or `@PropertySource`. |
| `spring_properties_list(profiles?, prefix?)` | List resolved properties under the given profiles, optionally filtered by prefix. |

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

## Troubleshooting

- **Java LSP returns empty references / hover.** Almost always because jdtls is
  importing extra Gradle projects under the workspace root (typically stale
  `.claude/worktrees/agent-*/backend/`). The MCP points jdtls at
  `<repo>/backend` by default to avoid this; set `PEEP_BOT_JAVA_ROOT=<abs>`
  if your project root is somewhere else.
- **`db_query` says no Postgres found.** Either no Testcontainer is running
  (start a backend test) or no docker-compose Postgres (`docker compose up -d`),
  or `docker ps` requires sudo on this host. Override with
  `PEEP_BOT_DB_URL=postgresql://user:pass@host:port/db`.
- **`db_query` returns "Refused: ... write keyword".** Read-only by default
  to avoid mutating live test state. Pass `allowWrite=true` to override.
- **First Java tool call hangs for ~30s.** jdtls cold start. Subsequent calls
  are fast (the client is reused for the MCP server lifetime). Pre-warmed in
  background on startup.
- **`run_test` on backend prints exit=0 with no JUnit summary.** Compile
  failure before tests ran — falls back to stdout tail. Check `[stderr]`
  section for the Java compile error.
- **`.cache/` is large.** jdtls tarball (~50MB) plus the workspace
  (~200MB once indexed). Wipe with
  `rm -rf mcp/.cache/jdtls-workspace` to force re-index; wipe
  `mcp/.cache/jdtls/` + the tarball to force a fresh download.

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
