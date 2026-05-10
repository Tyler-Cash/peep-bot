#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { existsSync, readFileSync, statSync } from "node:fs";
import { resolve, relative } from "node:path";
import {
  REPO_ROOT,
  RG_BIN,
  ALL_LANGS,
  KIND_TABLE,
  LangKey,
  SymbolKind,
  AstGrepMatch,
  detectLang,
  resolveInRepo,
  relativeToRepo,
  run,
  astGrepRun,
  astGrepScan,
  buildRule,
  fmtMatch,
  dedupe,
  textResult,
  truncateLines,
  escapeRegex,
} from "./core.js";
import { cache } from "./cache.js";
import { listEndpoints } from "./endpoints.js";
import { dbSchemaTool } from "./dbSchema.js";
import { repoOverview } from "./overview.js";
import {
  getClientForFile,
  isLspSupported,
  langOf,
  uriToRelative,
  hoverToText,
} from "./lsp.js";

const server = new McpServer({ name: "peep-bot-mcp", version: "0.2.0" });

// ---------- file/code outline ----------

async function outlineMatches(absFile: string, lang: LangKey): Promise<AstGrepMatch[]> {
  const kindList = Object.values(KIND_TABLE[lang]).flat();
  const rules = kindList
    .map((k, i) => buildRule({ id: `outline-${i}`, language: lang, kind: k }))
    .join("\n---\n");
  return astGrepScan({ rules, paths: [absFile] });
}

server.registerTool(
  "outline_file",
  {
    description:
      "List top-level declarations (classes, interfaces, methods, functions) in a file. Java + TS/JS.",
    inputSchema: {
      path: z.string().describe("Path to the file (repo-relative or absolute)."),
    },
  },
  async ({ path }) => {
    const abs = resolveInRepo(path);
    if (!existsSync(abs)) return textResult(`File not found: ${path}`);
    const lang = detectLang(abs);
    if (!lang) return textResult(`Unsupported file extension: ${path}`);
    const matches = await cache.wrap(
      ["outline_file", abs],
      { watch: [abs], ttlMs: 5 * 60_000 },
      () => outlineMatches(abs, lang),
    );
    if (matches.length === 0) return textResult(`No declarations found in ${path}.`);
    const sorted = [...matches].sort((a, b) => a.range.start.line - b.range.start.line);
    const lines = sorted.map((m) => {
      const name = m.metaVariables?.single?.N?.text;
      const file = relativeToRepo(m.file);
      const head = m.text.split("\n")[0].trim().slice(0, 160);
      return `${file}:${m.range.start.line + 1}  ${name ? `[${name}] ` : ""}${head}`;
    });
    return textResult(lines.join("\n"));
  },
);

server.registerTool(
  "outline_directory",
  {
    description:
      "Recursive outline of declarations across all source files matching a glob (default: '<dir>/**/*.{java,ts,tsx,js,jsx}'). Returns one declaration per line.",
    inputSchema: {
      dir: z.string().describe("Directory (repo-relative or absolute)."),
      glob: z.string().optional().describe("Optional glob to override the default."),
    },
  },
  async ({ dir, glob }) => {
    const abs = resolveInRepo(dir);
    if (!existsSync(abs)) return textResult(`Directory not found: ${dir}`);
    const langs = ALL_LANGS;
    const ruleBlocks: string[] = [];
    let counter = 0;
    for (const lang of langs) {
      for (const kind of Object.values(KIND_TABLE[lang]).flat()) {
        ruleBlocks.push(
          buildRule({ id: `outline-${counter++}`, language: lang, kind }),
        );
      }
    }
    const matches = await cache.wrap(
      ["outline_directory", abs, glob ?? ""],
      { watch: [abs], ttlMs: 60_000 },
      () =>
        astGrepScan({
          rules: ruleBlocks.join("\n---\n"),
          paths: [abs],
          globs: glob ? [glob] : undefined,
        }),
    );
    if (matches.length === 0) return textResult("No declarations found.");
    const sorted = [...matches].sort(
      (a, b) =>
        a.file.localeCompare(b.file) || a.range.start.line - b.range.start.line,
    );
    const lines = sorted.map((m) => {
      const name = m.metaVariables?.single?.N?.text;
      const file = relativeToRepo(m.file);
      const head = m.text.split("\n")[0].trim().slice(0, 120);
      return `${file}:${m.range.start.line + 1}  ${name ? `[${name}] ` : ""}${head}`;
    });
    return textResult(truncateLines(lines, 500));
  },
);

// ---------- find_symbol ----------

server.registerTool(
  "find_symbol",
  {
    description:
      "Find definitions of a named symbol (class/interface/function/method) by AST node kind. Exact name match.",
    inputSchema: {
      name: z.string().describe("Symbol name (exact)."),
      kind: z.enum(["class", "interface", "function", "method", "any"]).optional(),
      lang: z.enum(["java", "ts", "tsx", "js", "jsx", "any"]).optional(),
      glob: z.string().optional().describe("Optional glob to restrict files."),
    },
  },
  async ({ name, kind = "any", lang = "any", glob }) => {
    const langs: LangKey[] = lang === "any" ? ALL_LANGS : [lang as LangKey];
    const kinds: Exclude<SymbolKind, "any">[] =
      kind === "any" ? ["class", "interface", "function", "method"] : [kind];

    const ruleBlocks: string[] = [];
    let counter = 0;
    for (const l of langs) {
      for (const k of kinds) {
        for (const nodeKind of KIND_TABLE[l][k] ?? []) {
          ruleBlocks.push(
            buildRule({
              id: `find-${counter++}`,
              language: l,
              kind: nodeKind,
              nameRegex: `^${escapeRegex(name)}$`,
            }),
          );
        }
      }
    }
    if (ruleBlocks.length === 0) return textResult("No applicable rules.");

    const matches = await cache.wrap(
      ["find_symbol", name, kind, lang, glob ?? ""],
      { ttlMs: 60_000 },
      () =>
        astGrepScan({
          rules: ruleBlocks.join("\n---\n"),
          globs: glob ? [glob] : undefined,
        }),
    );
    const unique = dedupe(matches).sort(
      (a, b) =>
        a.file.localeCompare(b.file) || a.range.start.line - b.range.start.line,
    );
    if (unique.length === 0) return textResult(`No definitions of '${name}' found.`);
    return textResult(unique.map(fmtMatch).join("\n"));
  },
);

// ---------- find_references / grep / ast_search ----------

server.registerTool(
  "find_references",
  {
    description:
      "Word-boundary text search for usages of a name. Fast but text-only; pair with find_symbol for the definition site.",
    inputSchema: {
      name: z.string(),
      glob: z.string().optional(),
    },
  },
  async ({ name, glob }) => {
    const args = ["--line-number", "--column", "--no-heading", "-w", name];
    if (glob) args.push("-g", glob);
    args.push(REPO_ROOT);
    const { stdout, code } = await run(RG_BIN, args);
    if (code !== 0 && !stdout) return textResult(`No references to '${name}' found.`);
    const lines = stdout
      .split("\n")
      .filter(Boolean)
      .map((l) => (l.startsWith(REPO_ROOT) ? l.slice(REPO_ROOT.length + 1) : l));
    return textResult(truncateLines(lines, 200));
  },
);

server.registerTool(
  "ast_search",
  {
    description:
      "Run an ast-grep pattern. $VAR for single nodes, $$$ for variadics. Java patterns generally need modifiers (e.g. 'public class $N { $$$ }').",
    inputSchema: {
      pattern: z.string(),
      lang: z.enum(["java", "ts", "tsx", "js", "jsx"]),
      glob: z.string().optional(),
    },
  },
  async ({ pattern, lang, glob }) => {
    const matches = await cache.wrap(
      ["ast_search", pattern, lang, glob ?? ""],
      { ttlMs: 60_000 },
      () => astGrepRun({ pattern, lang, globs: glob ? [glob] : undefined }),
    );
    if (matches.length === 0) return textResult("No matches.");
    const lines = matches.map(fmtMatch);
    return textResult(truncateLines(lines, 200));
  },
);

server.registerTool(
  "grep",
  {
    description: "Ripgrep-backed text search.",
    inputSchema: {
      pattern: z.string(),
      glob: z.string().optional(),
      ignoreCase: z.boolean().optional(),
    },
  },
  async ({ pattern, glob, ignoreCase }) => {
    const args = ["--line-number", "--column", "--no-heading"];
    if (ignoreCase) args.push("-i");
    if (glob) args.push("-g", glob);
    args.push(pattern, REPO_ROOT);
    const { stdout, code } = await run(RG_BIN, args);
    if (code !== 0 && !stdout) return textResult("No matches.");
    const lines = stdout
      .split("\n")
      .filter(Boolean)
      .map((l) => (l.startsWith(REPO_ROOT) ? l.slice(REPO_ROOT.length + 1) : l));
    return textResult(truncateLines(lines, 200));
  },
);

// ---------- LSP-backed tools (TypeScript/JavaScript) ----------

const GLOB_DEFAULTS = {
  ts: "frontend/src/**/*.{ts,tsx,js,jsx}",
  java: "backend/src/main/java/**/*.java",
} as const;

type LspLangArg = "ts" | "java" | "auto";

async function firstTextMatch(
  name: string,
  glob: string,
): Promise<{ absPath: string; line: number; character: number } | null> {
  const args = [
    "--line-number",
    "--column",
    "--no-heading",
    "-w",
    "-m",
    "1",
    "-g",
    glob,
    name,
    REPO_ROOT,
  ];
  const { stdout } = await run(RG_BIN, args);
  const line = stdout.split("\n").find(Boolean);
  if (!line) return null;
  const m = /^(.+?):(\d+):(\d+):/.exec(line);
  if (!m) return null;
  return {
    absPath: m[1],
    line: Number(m[2]) - 1,
    character: Number(m[3]) - 1,
  };
}

async function resolvePosition(args: {
  path?: string;
  line?: number;
  character?: number;
  name?: string;
  glob?: string;
  lang?: LspLangArg;
}): Promise<
  | { absPath: string; line: number; character: number }
  | { error: string }
> {
  if (args.path && typeof args.line === "number" && typeof args.character === "number") {
    const abs = resolveInRepo(args.path);
    if (!existsSync(abs)) return { error: `File not found: ${args.path}` };
    if (!isLspSupported(abs)) return { error: `Unsupported file extension: ${args.path}` };
    return { absPath: abs, line: args.line, character: args.character };
  }
  if (args.name) {
    let glob = args.glob;
    if (!glob) {
      const lang = args.lang ?? "auto";
      if (lang === "java") glob = GLOB_DEFAULTS.java;
      else if (lang === "ts") glob = GLOB_DEFAULTS.ts;
      else {
        // auto: try Java first if name looks like a Java identifier (heuristic: PascalCase or ends with common Java suffixes)
        // Otherwise default to TS. Cheap fallback: try Java glob, then TS glob.
        const javaHit = await firstTextMatch(args.name, GLOB_DEFAULTS.java);
        if (javaHit) return javaHit;
        glob = GLOB_DEFAULTS.ts;
      }
    }
    const hit = await firstTextMatch(args.name, glob);
    if (!hit) return { error: `No occurrence of '${args.name}' in ${glob}.` };
    if (!isLspSupported(hit.absPath))
      return { error: `Found '${args.name}' but file is unsupported: ${hit.absPath}` };
    return hit;
  }
  return { error: "Provide either (path, line, character) or (name, [glob], [lang])." };
}

const lspInputSchema = {
  path: z.string().optional(),
  line: z.number().int().nonnegative().optional(),
  character: z.number().int().nonnegative().optional(),
  name: z.string().optional(),
  glob: z.string().optional(),
  lang: z.enum(["ts", "java", "auto"]).optional(),
};

server.registerTool(
  "lsp_references",
  {
    description:
      "True (semantic) references via a language server (typescript-language-server for TS/TSX/JS/JSX, jdtls for Java). Supply either an exact position (path, line, character - 0-indexed) or a symbol name. When given a name, set lang='ts'|'java'|'auto' (default auto: try Java glob first, then TS) or pass an explicit glob. First Java call costs ~30-90s while jdtls indexes the project; subsequent calls are fast.",
    inputSchema: lspInputSchema,
  },
  async (args) => {
    const pos = await resolvePosition(args);
    if ("error" in pos) return textResult(pos.error);
    const client = await getClientForFile(pos.absPath);
    const uri = await client.ensureOpen(pos.absPath);
    const refs = await client.references(uri, {
      line: pos.line,
      character: pos.character,
    });
    if (refs.length === 0) return textResult("No references.");
    const lines = refs.map((r) => {
      const file = uriToRelative(r.uri);
      const l = r.range.start.line + 1;
      const c = r.range.start.character + 1;
      return `${file}:${l}:${c}`;
    });
    lines.sort();
    return textResult(truncateLines(lines, 300));
  },
);

server.registerTool(
  "lsp_definition",
  {
    description:
      "Jump to definition via a language server (typescript-language-server or jdtls, picked by file extension). Supply either an exact position or a symbol name + optional lang/glob.",
    inputSchema: lspInputSchema,
  },
  async (args) => {
    const pos = await resolvePosition(args);
    if ("error" in pos) return textResult(pos.error);
    const client = await getClientForFile(pos.absPath);
    const uri = await client.ensureOpen(pos.absPath);
    const defs = await client.definition(uri, {
      line: pos.line,
      character: pos.character,
    });
    if (defs.length === 0) return textResult("No definition found.");
    const lines = defs.map((d) => {
      const targetUri = "targetUri" in d ? d.targetUri : d.uri;
      const range = "targetRange" in d ? d.targetRange : d.range;
      const file = uriToRelative(targetUri);
      const l = range.start.line + 1;
      const c = range.start.character + 1;
      return `${file}:${l}:${c}`;
    });
    return textResult(lines.join("\n"));
  },
);

server.registerTool(
  "lsp_hover",
  {
    description:
      "Hover info (signature, javadoc/jsdoc, inferred type) via a language server (typescript-language-server or jdtls, picked by file extension).",
    inputSchema: lspInputSchema,
  },
  async (args) => {
    const pos = await resolvePosition(args);
    if ("error" in pos) return textResult(pos.error);
    const client = await getClientForFile(pos.absPath);
    const uri = await client.ensureOpen(pos.absPath);
    const hover = await client.hover(uri, {
      line: pos.line,
      character: pos.character,
    });
    const text = hoverToText(hover).trim();
    if (!text) return textResult("(no hover info)");
    return textResult(text);
  },
);

// ---------- list_endpoints ----------

server.registerTool(
  "list_endpoints",
  {
    description:
      "List all HTTP endpoints: Spring annotations on the backend (with class-level base path applied) and Next.js routes/pages on the frontend.",
    inputSchema: {
      side: z.enum(["backend", "frontend", "both"]).optional(),
    },
  },
  async ({ side }) => textResult(await listEndpoints({ side })),
);

// ---------- db_schema ----------

server.registerTool(
  "db_schema",
  {
    description:
      "Reduce the Liquibase changelog into the current logical schema (tables and columns). Follows include directives.",
    inputSchema: {},
  },
  async () => textResult(await dbSchemaTool()),
);

// ---------- repo_overview ----------

server.registerTool(
  "repo_overview",
  {
    description:
      "Structured summary of the repo: CLAUDE.md excerpt, file counts by stack, and a depth-2 directory layout. Good first call for cold-start agents.",
    inputSchema: {},
  },
  async () => textResult(await repoOverview()),
);

// ---------- run_test ----------

server.registerTool(
  "run_test",
  {
    description:
      "Run tests. Backend: ./gradlew test --tests <pattern> (Java). Frontend: npm run test -- <pattern> (Vitest). Output is captured and truncated to last ~400 lines.",
    inputSchema: {
      side: z.enum(["backend", "frontend"]),
      pattern: z
        .string()
        .optional()
        .describe(
          "Test selector. Backend: a fully-qualified class or wildcard (e.g. 'dev.tylercash.event.*'). Frontend: a Vitest path/name pattern.",
        ),
      timeoutMs: z
        .number()
        .optional()
        .describe("Timeout in ms. Default 300000 (5 min)."),
    },
  },
  async ({ side, pattern, timeoutMs }) => {
    const tmo = timeoutMs ?? 300_000;
    let cmd: string;
    let args: string[];
    let cwd: string;
    if (side === "backend") {
      cmd = "./gradlew";
      args = ["--no-daemon", "--console=plain", "test"];
      if (pattern) args.push("--tests", pattern);
      cwd = resolve(REPO_ROOT, "backend");
    } else {
      cmd = "npm";
      args = ["run", "test", "--silent"];
      if (pattern) args.push("--", pattern);
      cwd = resolve(REPO_ROOT, "frontend");
    }
    if (!existsSync(cwd)) return textResult(`Working directory does not exist: ${cwd}`);
    const { stdout, stderr, code } = await run(cmd, args, { cwd, timeoutMs: tmo });
    const combined = (stdout + (stderr ? `\n[stderr]\n${stderr}` : "")).split("\n");
    const tail = combined.slice(-400).join("\n");
    return textResult(`exit=${code}\n${tail}`);
  },
);

// ---------- resources ----------

interface ResourceFile {
  uri: string;
  name: string;
  description: string;
  path: string;
}

const RESOURCE_FILES: ResourceFile[] = [
  {
    uri: "peep://CLAUDE.md",
    name: "CLAUDE.md",
    description: "Project conventions, commands, and gotchas.",
    path: resolve(REPO_ROOT, "CLAUDE.md"),
  },
  {
    uri: "peep://README.md",
    name: "README.md",
    description: "Project README.",
    path: resolve(REPO_ROOT, "README.md"),
  },
  {
    uri: "peep://docker-compose.yml",
    name: "docker-compose.yml",
    description: "Local infra (Postgres).",
    path: resolve(REPO_ROOT, "docker-compose.yml"),
  },
  {
    uri: "peep://backend/application.yaml",
    name: "application.yaml",
    description: "Backend default Spring config.",
    path: resolve(REPO_ROOT, "backend/src/main/resources/application.yaml"),
  },
  {
    uri: "peep://backend/db.changelog-master.yaml",
    name: "db.changelog-master.yaml",
    description: "Liquibase master changelog.",
    path: resolve(
      REPO_ROOT,
      "backend/src/main/resources/db/changelog/db.changelog-master.yaml",
    ),
  },
];

for (const r of RESOURCE_FILES) {
  if (!existsSync(r.path)) continue;
  server.registerResource(
    r.name,
    r.uri,
    { description: r.description, mimeType: "text/plain" },
    async () => ({
      contents: [
        {
          uri: r.uri,
          mimeType: "text/plain",
          text: readFileSync(r.path, "utf8"),
        },
      ],
    }),
  );
}

const transport = new StdioServerTransport();
await server.connect(transport);
