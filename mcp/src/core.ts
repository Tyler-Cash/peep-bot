import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, resolve, isAbsolute, relative } from "node:path";
import { rgPath } from "@vscode/ripgrep";

const __dirname = dirname(fileURLToPath(import.meta.url));

export const REPO_ROOT = process.env.PEEP_BOT_ROOT
  ? resolve(process.env.PEEP_BOT_ROOT)
  : resolve(__dirname, "..", "..");

export const AST_GREP_BIN = resolve(
  __dirname,
  "..",
  "node_modules",
  ".bin",
  "ast-grep",
);
export const RG_BIN: string = rgPath;

export type LangKey = "java" | "ts" | "tsx" | "js" | "jsx";
export type SymbolKind = "class" | "interface" | "function" | "method" | "any";

export const ALL_LANGS: LangKey[] = ["java", "ts", "tsx", "js", "jsx"];

const LANG_BY_EXT: Record<string, LangKey> = {
  java: "java",
  ts: "ts",
  tsx: "tsx",
  js: "js",
  jsx: "jsx",
  mjs: "js",
  cjs: "js",
};

export function detectLang(path: string): LangKey | null {
  const ext = path.split(".").pop()?.toLowerCase() ?? "";
  return LANG_BY_EXT[ext] ?? null;
}

export function resolveInRepo(path: string): string {
  const abs = isAbsolute(path) ? path : resolve(REPO_ROOT, path);
  const rel = relative(REPO_ROOT, abs);
  if (rel.startsWith("..")) {
    throw new Error(`Path escapes repo root: ${path}`);
  }
  return abs;
}

export function relativeToRepo(path: string): string {
  return isAbsolute(path) ? relative(REPO_ROOT, path) || path : path;
}

export interface AstGrepMatch {
  file: string;
  range: {
    start: { line: number; column: number };
    end: { line: number; column: number };
  };
  text: string;
  metaVariables?: { single?: Record<string, { text: string }> };
  ruleId?: string;
}

export function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function run(
  cmd: string,
  args: string[],
  opts: { cwd?: string; input?: string; timeoutMs?: number } = {},
): Promise<{ stdout: string; stderr: string; code: number }> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(cmd, args, { cwd: opts.cwd ?? REPO_ROOT });
    let stdout = "";
    let stderr = "";
    let killed = false;
    const timer = opts.timeoutMs
      ? setTimeout(() => {
          killed = true;
          child.kill("SIGKILL");
        }, opts.timeoutMs)
      : null;
    child.stdout.on("data", (b) => (stdout += b.toString()));
    child.stderr.on("data", (b) => (stderr += b.toString()));
    child.on("error", (err) => {
      if (timer) clearTimeout(timer);
      reject(err);
    });
    child.on("close", (code) => {
      if (timer) clearTimeout(timer);
      if (killed) {
        resolvePromise({
          stdout,
          stderr: stderr + "\n[killed by timeout]",
          code: 124,
        });
      } else {
        resolvePromise({ stdout, stderr, code: code ?? 0 });
      }
    });
    if (opts.input !== undefined) child.stdin.end(opts.input);
  });
}

function parseStream(stdout: string): AstGrepMatch[] {
  const matches: AstGrepMatch[] = [];
  for (const line of stdout.split("\n")) {
    const t = line.trim();
    if (!t || !t.startsWith("{")) continue;
    try {
      matches.push(JSON.parse(t));
    } catch {
      // ignore malformed lines
    }
  }
  return matches;
}

export async function astGrepScan(args: {
  rules: string;
  paths?: string[];
  globs?: string[];
}): Promise<AstGrepMatch[]> {
  const cliArgs = ["scan", "--inline-rules", args.rules, "--json=stream"];
  for (const g of args.globs ?? []) cliArgs.push("--globs", g);
  for (const p of args.paths ?? []) cliArgs.push(p);
  const { stdout, stderr, code } = await run(AST_GREP_BIN, cliArgs);
  if (code !== 0 && !stdout && stderr) {
    throw new Error(`ast-grep scan failed: ${stderr.slice(0, 500)}`);
  }
  return parseStream(stdout);
}

export async function astGrepRun(args: {
  pattern: string;
  lang: LangKey;
  paths?: string[];
  globs?: string[];
}): Promise<AstGrepMatch[]> {
  const cliArgs = [
    "run",
    "--pattern",
    args.pattern,
    "--lang",
    args.lang,
    "--json=stream",
  ];
  for (const g of args.globs ?? []) cliArgs.push("--globs", g);
  for (const p of args.paths ?? []) cliArgs.push(p);
  const { stdout, stderr, code } = await run(AST_GREP_BIN, cliArgs);
  if (
    code !== 0 &&
    !stdout &&
    stderr &&
    !stderr.includes("Pattern contains")
  ) {
    throw new Error(`ast-grep run failed: ${stderr.slice(0, 500)}`);
  }
  return parseStream(stdout);
}

export async function astGrepRewrite(args: {
  pattern: string;
  rewrite: string;
  lang: LangKey;
  globs?: string[];
  paths?: string[];
  apply: boolean;
}): Promise<{ stdout: string; stderr: string; code: number }> {
  const cliArgs = [
    "run",
    "--pattern",
    args.pattern,
    "--rewrite",
    args.rewrite,
    "--lang",
    args.lang,
  ];
  if (args.apply) cliArgs.push("--update-all");
  for (const g of args.globs ?? []) cliArgs.push("--globs", g);
  for (const p of args.paths ?? []) cliArgs.push(p);
  return await run(AST_GREP_BIN, cliArgs);
}

export const KIND_TABLE: Record<
  LangKey,
  Record<Exclude<SymbolKind, "any">, string[]>
> = {
  java: {
    class: ["class_declaration", "record_declaration", "enum_declaration"],
    interface: ["interface_declaration"],
    function: [],
    method: ["method_declaration", "constructor_declaration"],
  },
  ts: {
    class: ["class_declaration"],
    interface: ["interface_declaration"],
    function: ["function_declaration"],
    method: ["method_definition", "method_signature"],
  },
  tsx: {
    class: ["class_declaration"],
    interface: ["interface_declaration"],
    function: ["function_declaration"],
    method: ["method_definition", "method_signature"],
  },
  js: {
    class: ["class_declaration"],
    interface: [],
    function: ["function_declaration"],
    method: ["method_definition"],
  },
  jsx: {
    class: ["class_declaration"],
    interface: [],
    function: ["function_declaration"],
    method: ["method_definition"],
  },
};

export function buildRule(opts: {
  id: string;
  language: LangKey;
  kind: string;
  nameRegex?: string;
}): string {
  const lines = [
    `id: ${opts.id}`,
    `language: ${opts.language}`,
    `severity: hint`,
    `message: match`,
    `rule:`,
    `  kind: ${opts.kind}`,
  ];
  if (opts.nameRegex !== undefined) {
    lines.push(`  has:`);
    lines.push(`    field: name`);
    lines.push(`    regex: "${opts.nameRegex.replace(/"/g, '\\"')}"`);
  }
  return lines.join("\n");
}

export function fmtMatch(m: AstGrepMatch): string {
  const file = relativeToRepo(m.file);
  const text = m.text.replace(/\s+/g, " ").trim().slice(0, 200);
  return `${file}:${m.range.start.line + 1}:${m.range.start.column + 1}  ${text}`;
}

export function dedupe(matches: AstGrepMatch[]): AstGrepMatch[] {
  const seen = new Set<string>();
  const out: AstGrepMatch[] = [];
  for (const m of matches) {
    const k = `${m.file}:${m.range.start.line}:${m.range.start.column}`;
    if (!seen.has(k)) {
      seen.add(k);
      out.push(m);
    }
  }
  return out;
}

export function textResult(text: string) {
  return { content: [{ type: "text" as const, text }] };
}

export function truncateLines(lines: string[], max: number): string {
  if (lines.length <= max) return lines.join("\n");
  return `${lines.slice(0, max).join("\n")}\n... (${lines.length - max} more)`;
}
