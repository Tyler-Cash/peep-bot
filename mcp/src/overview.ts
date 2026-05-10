import { readFileSync, readdirSync, statSync } from "node:fs";
import { resolve, join, relative } from "node:path";
import { REPO_ROOT, RG_BIN, run } from "./core.js";
import { cache } from "./cache.js";

const SKIP_DIRS = new Set([
  "node_modules",
  ".next",
  ".next-live",
  ".git",
  "build",
  "dist",
  "target",
  ".gradle",
  ".idea",
  ".vscode",
]);

interface DirSummary {
  path: string;
  files: number;
  langs: Record<string, number>;
  children: DirSummary[];
}

function summarize(dir: string, depth: number, maxDepth: number): DirSummary {
  const summary: DirSummary = {
    path: dir,
    files: 0,
    langs: {},
    children: [],
  };
  let entries: string[];
  try {
    entries = readdirSync(dir);
  } catch {
    return summary;
  }
  for (const name of entries) {
    if (SKIP_DIRS.has(name) || name.startsWith(".")) continue;
    const p = join(dir, name);
    let s;
    try {
      s = statSync(p);
    } catch {
      continue;
    }
    if (s.isDirectory()) {
      const child = summarize(p, depth + 1, maxDepth);
      summary.files += child.files;
      for (const [k, v] of Object.entries(child.langs)) {
        summary.langs[k] = (summary.langs[k] ?? 0) + v;
      }
      if (depth < maxDepth) summary.children.push(child);
    } else {
      summary.files += 1;
      const ext = name.split(".").pop()?.toLowerCase() ?? "";
      if (ext) summary.langs[ext] = (summary.langs[ext] ?? 0) + 1;
    }
  }
  return summary;
}

function formatTree(s: DirSummary, indent = 0): string[] {
  const out: string[] = [];
  const rel = relative(REPO_ROOT, s.path) || ".";
  const top = Object.entries(s.langs)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 4)
    .map(([k, v]) => `${k}=${v}`)
    .join(" ");
  out.push(`${"  ".repeat(indent)}${rel}/  (${s.files} files${top ? "; " + top : ""})`);
  for (const c of s.children) out.push(...formatTree(c, indent + 1));
  return out;
}

function readSafe(path: string, maxBytes = 8000): string | null {
  try {
    const buf = readFileSync(path, "utf8");
    return buf.length > maxBytes ? buf.slice(0, maxBytes) + "\n... (truncated)" : buf;
  } catch {
    return null;
  }
}

async function fileCount(globPattern: string): Promise<number> {
  const { stdout } = await run(RG_BIN, ["--files", "-g", globPattern, REPO_ROOT]);
  return stdout.split("\n").filter(Boolean).length;
}

export async function repoOverview(): Promise<string> {
  return cache.wrap(["repoOverview"], { ttlMs: 60_000 }, async () => {
    const out: string[] = [];
    out.push(`# ${relative("/home/tcash", REPO_ROOT) || REPO_ROOT}`);
    out.push("");

    const claudeMd = readSafe(resolve(REPO_ROOT, "CLAUDE.md"));
    if (claudeMd) {
      out.push("## CLAUDE.md (excerpt)");
      out.push(claudeMd);
      out.push("");
    } else {
      const readme = readSafe(resolve(REPO_ROOT, "README.md"));
      if (readme) {
        out.push("## README.md (excerpt)");
        out.push(readme);
        out.push("");
      }
    }

    out.push("## File counts by stack");
    const counts: [string, string][] = [
      ["Java", "**/*.java"],
      ["TypeScript", "**/*.{ts,tsx}"],
      ["JavaScript", "**/*.{js,jsx,mjs,cjs}"],
      ["Liquibase YAML", "backend/src/main/resources/db/changelog/**/*.yaml"],
      ["Test (Java)", "backend/src/test/**/*.java"],
      ["Test (TS)", "frontend/**/*.{test,spec}.{ts,tsx}"],
    ];
    for (const [label, glob] of counts) {
      const n = await fileCount(glob).catch(() => 0);
      out.push(`  ${label.padEnd(18)} ${n}`);
    }
    out.push("");

    out.push("## Top-level layout (depth ≤ 2)");
    const summary = summarize(REPO_ROOT, 0, 2);
    const lines = formatTree(summary).slice(1); // drop root
    out.push(...lines);

    return out.join("\n");
  });
}
