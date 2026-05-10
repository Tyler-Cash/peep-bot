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
  astGrepRewrite,
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
  getTsClient,
  getJavaClient,
  isJdtlsAvailable,
  isLspSupported,
  langOf,
  uriToRelative,
  hoverToText,
} from "./lsp.js";
import type {
  Diagnostic,
  CallHierarchyItem,
  SymbolInformation,
  WorkspaceSymbol,
} from "vscode-languageserver-protocol";
import { readAllReports, formatSummary } from "./junit.js";
import { testAffinityReport, testMocksReport } from "./testInfra.js";
import { dbInfo, dbQuery } from "./dbQuery.js";

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
      "Word-boundary text search for usages of a name. Text-only; matches anywhere the name appears as a word (including comments and docs). For Java/TS/TSX/JS/JSX, prefer lsp_references — it resolves true semantic references across imports and rebinding. This tool is the right fallback for .yaml / .md / .sql / .properties or when you specifically want to find string occurrences (e.g. Spring property names referenced in YAML).",
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

// ---------- LSP helpers ----------

const SYMBOL_KINDS: Record<number, string> = {
  1: "file", 2: "module", 3: "namespace", 4: "package", 5: "class", 6: "method",
  7: "property", 8: "field", 9: "constructor", 10: "enum", 11: "interface",
  12: "function", 13: "variable", 14: "constant", 15: "string", 16: "number",
  17: "boolean", 18: "array", 19: "object", 20: "key", 21: "null",
  22: "enum-member", 23: "struct", 24: "event", 25: "operator", 26: "type-param",
};
function symbolKindName(k: number | undefined): string {
  return k !== undefined && SYMBOL_KINDS[k] ? SYMBOL_KINDS[k] : "?";
}

const SEVERITY_RANK: Record<string, number> = {
  error: 1, warning: 2, info: 3, hint: 4, all: 5,
};
function severityName(s: number | undefined): "error" | "warning" | "info" | "hint" {
  if (s === 1) return "error";
  if (s === 2) return "warning";
  if (s === 3) return "info";
  return "hint";
}
function formatDiagnostics(path: string, diags: Diagnostic[]): string {
  const lines = [`${path}: ${diags.length} diagnostic${diags.length === 1 ? "" : "s"}`];
  for (const d of diags) {
    const sev = severityName(d.severity);
    const where = `${d.range.start.line + 1}:${d.range.start.character + 1}`;
    const code = d.code !== undefined ? ` [${d.code}]` : "";
    const src = d.source ? ` (${d.source})` : "";
    lines.push(`  ${sev}${code}${src} at ${where}: ${d.message.split("\n")[0].slice(0, 240)}`);
  }
  return lines.join("\n");
}
function summarizeAllDiags(
  client: Awaited<ReturnType<typeof getTsClient>>,
  minSev: number,
): string[] {
  const out: string[] = [];
  for (const [uri, diags] of client.allDiagnostics()) {
    const filtered = diags.filter((d) => SEVERITY_RANK[severityName(d.severity)] >= minSev);
    if (filtered.length === 0) continue;
    out.push(formatDiagnostics(uriToRelative(uri), filtered));
  }
  return out;
}

// Pre-warm LSP servers on first MCP startup so the first user-facing call
// doesn't pay the cold-start cost. Background, non-blocking.
function prewarmLsp() {
  getTsClient().catch(() => {});
  if (isJdtlsAvailable() && !process.env.PEEP_BOT_DISABLE_JDTLS) {
    getJavaClient().catch(() => {});
  }
}
prewarmLsp();

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

const lspInputSchemaWithDecl = {
  ...lspInputSchema,
  includeDeclaration: z.boolean().optional(),
};

const lspInputSchemaWithDirection = {
  ...lspInputSchema,
  direction: z.enum(["incoming", "outgoing"]).optional(),
};

server.registerTool(
  "lsp_references",
  {
    description:
      "True (semantic) references via a language server (typescript-language-server for TS/TSX/JS/JSX, jdtls for Java). Supply either an exact position (path, line, character - 0-indexed) or a symbol name. When given a name, set lang='ts'|'java'|'auto' (default auto: try Java glob first, then TS) or pass an explicit glob. Set includeDeclaration=false to exclude the definition site from results. First Java call costs ~18-35s while jdtls indexes the project; subsequent calls are fast.",
    inputSchema: lspInputSchemaWithDecl,
  },
  async (args) => {
    const pos = await resolvePosition(args);
    if ("error" in pos) return textResult(pos.error);
    const client = await getClientForFile(pos.absPath);
    const uri = await client.ensureOpen(pos.absPath);
    const refs = await client.references(
      uri,
      { line: pos.line, character: pos.character },
      { includeDeclaration: args.includeDeclaration ?? true },
    );
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
  "lsp_workspace_symbol",
  {
    description:
      "Fuzzy 'find me everything matching <query>' across the project via a language server. Set lang='ts'|'java' to query a single server, or 'auto'/'both' (default) to merge results from both. Returns kind, name, container, and location — much broader than find_symbol's exact-name match. Each server applies its own fuzzy/substring matching.",
    inputSchema: {
      query: z.string(),
      lang: z.enum(["ts", "java", "auto", "both"]).optional(),
      limit: z.number().int().positive().optional(),
    },
  },
  async ({ query, lang, limit }) => {
    const which = lang ?? "auto";
    const max = limit ?? 200;
    const sources: Promise<{ name: string; result: (SymbolInformation | WorkspaceSymbol)[] }>[] = [];
    if (which === "ts" || which === "auto" || which === "both") {
      sources.push(
        getTsClient().then(async (c) => ({ name: "ts", result: await c.workspaceSymbol(query) })),
      );
    }
    if ((which === "java" || which === "auto" || which === "both") && isJdtlsAvailable()) {
      sources.push(
        getJavaClient().then(async (c) => ({ name: "java", result: await c.workspaceSymbol(query) })),
      );
    }
    const all = await Promise.all(sources);
    const lines: string[] = [];
    for (const { name, result } of all) {
      lines.push(`# ${name} (${result.length} hits)`);
      for (const sym of result.slice(0, max)) {
        const loc = (sym as { location?: { uri?: string; range?: { start: { line: number; character: number } } } }).location;
        const uri = loc?.uri;
        const range = loc?.range;
        const where = uri
          ? range
            ? `${uriToRelative(uri)}:${range.start.line + 1}:${range.start.character + 1}`
            : uriToRelative(uri)
          : "(no location)";
        const container = sym.containerName ? ` [${sym.containerName}]` : "";
        const kind = symbolKindName(sym.kind);
        lines.push(`  ${kind} ${sym.name}${container}  ${where}`);
      }
      if (result.length > max) lines.push(`  ... ${result.length - max} more`);
    }
    if (lines.length === 0) return textResult("No matches.");
    return textResult(truncateLines(lines, 500));
  },
);

server.registerTool(
  "lsp_call_hierarchy",
  {
    description:
      "Who calls X (incoming) or what does X call (outgoing). Two-step LSP query: prepares a call-hierarchy item at the position, then asks for incoming or outgoing calls. Direction defaults to 'incoming' which is the usual 'find callers' question — strictly more focused than lsp_references because it filters out non-call references like type annotations, imports, or generic args. Same position resolution as lsp_references.",
    inputSchema: lspInputSchemaWithDirection,
  },
  async (args) => {
    const direction = args.direction ?? "incoming";
    const pos = await resolvePosition(args);
    if ("error" in pos) return textResult(pos.error);
    const client = await getClientForFile(pos.absPath);
    const uri = await client.ensureOpen(pos.absPath);
    const items = await client.prepareCallHierarchy(uri, {
      line: pos.line,
      character: pos.character,
    });
    if (items.length === 0) return textResult("Position is not a call-hierarchy target.");
    const lines: string[] = [];
    for (const item of items) {
      const itemLoc = `${uriToRelative(item.uri)}:${item.range.start.line + 1}:${item.range.start.character + 1}`;
      lines.push(`${item.name}  (${itemLoc})`);
      if (direction === "incoming") {
        const calls = await client.incomingCalls(item);
        if (calls.length === 0) {
          lines.push("  (no incoming calls)");
          continue;
        }
        lines.push(`  incoming (${calls.length}):`);
        for (const ic of calls.slice(0, 100)) {
          const from = ic.from;
          const where = `${uriToRelative(from.uri)}:${from.range.start.line + 1}:${from.range.start.character + 1}`;
          const callSites = ic.fromRanges
            .slice(0, 3)
            .map((r) => `${r.start.line + 1}:${r.start.character + 1}`)
            .join(", ");
          lines.push(`    ${from.name}${from.detail ? ` ${from.detail}` : ""}  ${where}${callSites ? `  (calls at ${callSites})` : ""}`);
        }
        if (calls.length > 100) lines.push(`    ... ${calls.length - 100} more`);
      } else {
        const calls = await client.outgoingCalls(item);
        if (calls.length === 0) {
          lines.push("  (no outgoing calls)");
          continue;
        }
        lines.push(`  outgoing (${calls.length}):`);
        for (const oc of calls.slice(0, 100)) {
          const to = oc.to;
          const where = `${uriToRelative(to.uri)}:${to.range.start.line + 1}:${to.range.start.character + 1}`;
          lines.push(`    ${to.name}${to.detail ? ` ${to.detail}` : ""}  ${where}`);
        }
        if (calls.length > 100) lines.push(`    ... ${calls.length - 100} more`);
      }
    }
    return textResult(lines.join("\n"));
  },
);

server.registerTool(
  "lsp_diagnostics",
  {
    description:
      "Compile / type / lint diagnostics for a file as the language server currently sees them (TS via tsserver, Java via jdtls). Captures the publishDiagnostics notifications the LSPs already emit. Useful as a cheap 'did my edit break anything?' check before running a full build. Pass either `path` (single file) or omit for a workspace-wide summary of files that currently have any diagnostics. The file must be opened first — calling lsp_hover/references/definition on a file opens it. lang controls which server to consult when no path is given.",
    inputSchema: {
      path: z.string().optional(),
      lang: z.enum(["ts", "java", "both"]).optional(),
      severity: z.enum(["error", "warning", "info", "hint", "all"]).optional(),
    },
  },
  async ({ path, lang, severity }) => {
    const sevFilter = severity ?? "all";
    const minSev = SEVERITY_RANK[sevFilter];
    if (path) {
      const abs = resolveInRepo(path);
      if (!existsSync(abs)) return textResult(`File not found: ${path}`);
      if (!isLspSupported(abs)) return textResult(`Unsupported file extension: ${path}`);
      const client = await getClientForFile(abs);
      const uri = await client.ensureOpen(abs);
      // Give the server a moment to publish diagnostics for this file.
      await new Promise((r) => setTimeout(r, 800));
      const diags = client
        .diagnosticsFor(uri)
        .filter((d) => SEVERITY_RANK[severityName(d.severity)] >= minSev);
      if (diags.length === 0) return textResult(`No ${sevFilter !== "all" ? sevFilter + " " : ""}diagnostics for ${path}.`);
      return textResult(formatDiagnostics(path, diags));
    }
    // Workspace summary across whichever servers are running.
    const which = lang ?? "both";
    const out: string[] = [];
    if (which === "ts" || which === "both") {
      const ts = await getTsClient();
      out.push(...summarizeAllDiags(ts, minSev));
    }
    if ((which === "java" || which === "both") && isJdtlsAvailable()) {
      const java = await getJavaClient();
      out.push(...summarizeAllDiags(java, minSev));
    }
    if (out.length === 0) return textResult("No diagnostics outstanding (or no files have been opened yet).");
    return textResult(truncateLines(out, 300));
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

// ---------- ast_rewrite (codemod) ----------

server.registerTool(
  "ast_rewrite",
  {
    description:
      "AST-aware bulk rewrite via ast-grep. Pattern/rewrite use $VAR for single nodes and $$$ for variadics — same syntax as ast_search. Java patterns generally need full modifiers (e.g. 'public class $N { $$$ }'). By default returns a preview diff (apply=false). Pass apply=true to write changes in place. Far safer than regex codemods for things like 'rename a static final to an instance field allocated in @BeforeEach' or 'turn this method call chain into a builder'.",
    inputSchema: {
      pattern: z.string(),
      rewrite: z.string(),
      lang: z.enum(["java", "ts", "tsx", "js", "jsx"]),
      glob: z.string().optional(),
      apply: z.boolean().optional(),
    },
  },
  async ({ pattern, rewrite, lang, glob, apply }) => {
    const { stdout, stderr, code } = await astGrepRewrite({
      pattern,
      rewrite,
      lang,
      apply: apply ?? false,
      globs: glob ? [glob] : undefined,
    });
    if (code !== 0 && !stdout) {
      return textResult(`ast-grep failed (code ${code}):\n${stderr.slice(0, 1000)}`);
    }
    const header = apply
      ? "[applied — files modified in place]"
      : "[preview — no files modified; pass apply=true to write]";
    if (!stdout.trim()) return textResult(`${header}\n(no matches)`);
    return textResult(`${header}\n${truncateLines(stdout.split("\n"), 600)}`);
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
      args = [
        "run",
        "test",
        "--silent",
        "--",
        "--reporter=junit",
        "--outputFile=test-results/junit/vitest.xml",
      ];
      if (pattern) args.push(pattern);
      cwd = resolve(REPO_ROOT, "frontend");
    }
    if (!existsSync(cwd)) return textResult(`Working directory does not exist: ${cwd}`);
    const startMs = Date.now();
    const { stdout, stderr, code } = await run(cmd, args, { cwd, timeoutMs: tmo });
    const summary = readAllReports(startMs);
    const formatted = formatSummary(summary);
    const tail = (stdout + (stderr ? `\n[stderr]\n${stderr}` : ""))
      .split("\n")
      .slice(-80)
      .join("\n");
    const head = `exit=${code}\n${formatted}`;
    if (summary.total === 0) {
      // No JUnit reports parsed (e.g. compile failure before tests ran). Fall back to tail.
      return textResult(`${head}\n\n[no JUnit reports found — falling back to tail]\n${tail}`);
    }
    return textResult(`${head}\n\n[last 80 lines of stdout/stderr]\n${tail}`);
  },
);

// ---------- test infrastructure (backend) ----------

server.registerTool(
  "test_affinity",
  {
    description:
      "Approximate Spring @SpringBootTest context-cache groups across the backend test suite. Hashes class-level Spring test annotations (with one-level inheritance from a base class in the same suite) plus the @MockBean/@MockitoBean field set, and groups classes that share a hash. Members of the same group probably reuse a cached ApplicationContext at runtime, so cross-class mock pollution and 'who shares a context with me' questions resolve directly. Approximation: doesn't follow @ContextConfiguration initializers or @DynamicPropertySource bodies.",
    inputSchema: {
      groupsOnly: z
        .boolean()
        .optional()
        .describe("If true, list only group hashes and class names (compact)."),
    },
  },
  async ({ groupsOnly }) => textResult(await testAffinityReport({ groupsOnly })),
);

server.registerTool(
  "test_mocks",
  {
    description:
      "@MockBean / @MockitoBean / @SpyBean topology across the backend test suite. With no args: all mocked types ranked by how many test classes mock them (cross-class pollution risk). With type='<fqn or simpleName>': just the test classes that mock that type. Inheritance from a same-suite base class is resolved one level.",
    inputSchema: {
      type: z
        .string()
        .optional()
        .describe("Mocked type to focus on (simple name or fully-qualified name)."),
    },
  },
  async ({ type }) => textResult(await testMocksReport({ type })),
);

// ---------- live DB query ----------

server.registerTool(
  "db_info",
  {
    description:
      "Discover the running Postgres instance for this repo: prefers a Testcontainers-managed pgvector container (the SharedPostgres used by the backend test suite), falls back to the docker-compose dev DB. Returns host/port/user/database. Override with PEEP_BOT_DB_URL if you need a different target.",
    inputSchema: {},
  },
  async () => textResult(await dbInfo()),
);

server.registerTool(
  "db_query",
  {
    description:
      "Run a SQL query against the discovered Postgres (Testcontainer first, then docker-compose; PEEP_BOT_DB_URL overrides). Read-only by default — wraps the query in BEGIN READ ONLY / ROLLBACK and refuses anything containing write keywords (INSERT/UPDATE/DELETE/TRUNCATE/DROP/ALTER/etc). Set allowWrite=true to bypass. Use database='test_<classname>' to target a per-class isolated DB created by SharedPostgres.registerIsolatedDatabase. Statement timeout 10s.",
    inputSchema: {
      sql: z.string(),
      database: z.string().optional(),
      allowWrite: z.boolean().optional(),
      maxRows: z.number().int().positive().optional(),
    },
  },
  async ({ sql, database, allowWrite, maxRows }) =>
    textResult(await dbQuery({ sql, database, allowWrite, maxRows })),
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
