import { readdirSync, readFileSync, statSync } from "node:fs";
import { resolve, relative, join, sep } from "node:path";
import { REPO_ROOT, RG_BIN, run, relativeToRepo } from "./core.js";
import { cache } from "./cache.js";

interface JavaEndpoint {
  method: string; // GET / POST / ANY
  path: string;
  file: string;
  line: number;
  handler?: string;
}

interface NextRoute {
  kind: "page" | "route";
  urlPath: string;
  file: string;
  methods?: string[]; // for route handlers
}

const SPRING_MAPPING_RE =
  /@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\s*\(([\s\S]*?)\))?/;

function extractPath(annotationArgs: string | undefined): string | null {
  if (!annotationArgs) return "";
  const trimmed = annotationArgs.trim();
  // value = "/x" or path = "/x"
  const named =
    trimmed.match(/(?:value|path)\s*=\s*"([^"]*)"/) ??
    trimmed.match(/(?:value|path)\s*=\s*\{\s*"([^"]*)"/);
  if (named) return named[1];
  // first quoted string
  const positional = trimmed.match(/"([^"]*)"/);
  if (positional) return positional[1];
  return null;
}

function joinPath(base: string, child: string): string {
  if (!base) return child || "/";
  if (!child) return base || "/";
  const b = base.endsWith("/") ? base.slice(0, -1) : base;
  const c = child.startsWith("/") ? child : `/${child}`;
  return b + c;
}

async function scanSpringEndpoints(): Promise<JavaEndpoint[]> {
  const javaRoot = resolve(REPO_ROOT, "backend/src/main/java");
  // Use rg to find all mapping annotations with surrounding context.
  const args = [
    "--line-number",
    "--no-heading",
    "-U", // multiline
    "--multiline-dotall",
    "-e",
    "@(Get|Post|Put|Delete|Patch|Request)Mapping(\\([^)]*\\))?",
    javaRoot,
  ];
  const { stdout } = await run(RG_BIN, args);
  if (!stdout) return [];

  // Parse rg output: file:line:content
  const byFile = new Map<string, { line: number; text: string }[]>();
  for (const raw of stdout.split("\n")) {
    if (!raw) continue;
    const m = raw.match(/^([^:]+):(\d+):(.*)$/);
    if (!m) continue;
    const file = m[1];
    const line = parseInt(m[2], 10);
    const text = m[3];
    let arr = byFile.get(file);
    if (!arr) {
      arr = [];
      byFile.set(file, arr);
    }
    arr.push({ line, text });
  }

  const endpoints: JavaEndpoint[] = [];
  for (const [file, hits] of byFile) {
    let source: string;
    try {
      source = readFileSync(file, "utf8");
    } catch {
      continue;
    }
    const lines = source.split("\n");

    // Class-level mapping: first @RequestMapping that decorates a class.
    let basePath = "";
    for (let i = 0; i < lines.length; i++) {
      if (/\bclass\s+\w+/.test(lines[i])) {
        // look back up to 20 lines for @RequestMapping
        for (let j = i - 1; j >= Math.max(0, i - 20); j--) {
          const lm = lines[j].match(SPRING_MAPPING_RE);
          if (lm && lm[1] === "Request") {
            const p = extractPath(lm[2]);
            if (p !== null) basePath = p;
            break;
          }
          if (lines[j].trim() === "" || lines[j].trim().startsWith("@") || lines[j].trim().startsWith("import"))
            continue;
          break;
        }
        break;
      }
    }

    for (const { line } of hits) {
      // Build a window in case the annotation spans multiple lines.
      const startIdx = line - 1;
      let window = lines[startIdx] ?? "";
      // Concatenate continuation if parens unbalanced.
      const balance = (s: string) =>
        (s.match(/\(/g) ?? []).length - (s.match(/\)/g) ?? []).length;
      let cur = startIdx;
      while (balance(window) > 0 && cur < lines.length - 1) {
        cur++;
        window += " " + lines[cur];
      }
      const m = window.match(SPRING_MAPPING_RE);
      if (!m) continue;
      const verb = m[1] === "Request" ? "ANY" : m[1].toUpperCase();
      const annotPath = extractPath(m[2]) ?? "";

      // If this is the class-level @RequestMapping, skip — already used as base.
      const followsClass = (() => {
        for (let j = cur + 1; j < Math.min(lines.length, cur + 20); j++) {
          if (/\bclass\s+\w+/.test(lines[j])) return true;
          if (lines[j].trim() === "") continue;
          if (lines[j].trim().startsWith("@")) continue;
          break;
        }
        return false;
      })();
      if (followsClass) continue;

      // Handler name: look at next non-annotation line for method signature.
      let handler: string | undefined;
      for (let j = cur + 1; j < Math.min(lines.length, cur + 10); j++) {
        const t = lines[j].trim();
        if (t === "" || t.startsWith("@")) continue;
        const sig = t.match(/\b(?:public|private|protected)?\s*(?:static\s+)?(?:[\w<>\[\],\s.]+?\s+)?(\w+)\s*\(/);
        if (sig) {
          handler = sig[1];
        }
        break;
      }

      endpoints.push({
        method: verb,
        path: joinPath("/api" + (basePath || ""), annotPath),
        file,
        line,
        handler,
      });
    }
  }
  endpoints.sort(
    (a, b) =>
      a.path.localeCompare(b.path) ||
      a.method.localeCompare(b.method) ||
      a.file.localeCompare(b.file),
  );
  return endpoints;
}

function walkDir(dir: string, out: string[]): void {
  let entries: string[];
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const name of entries) {
    if (name === "node_modules" || name === ".next" || name === ".next-live")
      continue;
    const p = join(dir, name);
    let s;
    try {
      s = statSync(p);
    } catch {
      continue;
    }
    if (s.isDirectory()) walkDir(p, out);
    else out.push(p);
  }
}

function nextUrlFromFile(appRoot: string, file: string): string | null {
  const rel = relative(appRoot, file);
  if (!rel) return null;
  // strip filename (page.tsx / route.ts)
  const segs = rel.split(sep).slice(0, -1);
  const url = segs
    .filter((s) => !s.startsWith("(") || !s.endsWith(")")) // strip route groups
    .map((s) => {
      if (s.startsWith("[[...") && s.endsWith("]]")) return `:${s.slice(5, -2)}*?`;
      if (s.startsWith("[...") && s.endsWith("]")) return `:${s.slice(4, -1)}*`;
      if (s.startsWith("[") && s.endsWith("]")) return `:${s.slice(1, -1)}`;
      return s;
    })
    .join("/");
  return "/" + url;
}

function scanNextRoutes(): NextRoute[] {
  const appRoot = resolve(REPO_ROOT, "frontend/src/app");
  try {
    statSync(appRoot);
  } catch {
    return [];
  }
  const all: string[] = [];
  walkDir(appRoot, all);
  const routes: NextRoute[] = [];
  for (const file of all) {
    const base = file.split(sep).pop() ?? "";
    if (/^page\.(tsx?|jsx?|mdx?)$/.test(base)) {
      const url = nextUrlFromFile(appRoot, file);
      if (url !== null) routes.push({ kind: "page", urlPath: url, file });
    } else if (/^route\.(tsx?|jsx?)$/.test(base)) {
      const url = nextUrlFromFile(appRoot, file);
      if (url === null) continue;
      let source = "";
      try {
        source = readFileSync(file, "utf8");
      } catch {
        continue;
      }
      const methods: string[] = [];
      for (const verb of ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"]) {
        const re = new RegExp(
          `export\\s+(?:async\\s+)?(?:function\\s+${verb}\\b|const\\s+${verb}\\s*=)`,
        );
        if (re.test(source)) methods.push(verb);
      }
      routes.push({ kind: "route", urlPath: url, file, methods });
    }
  }
  routes.sort((a, b) => a.urlPath.localeCompare(b.urlPath));
  return routes;
}

export async function listEndpoints(opts: {
  side?: "backend" | "frontend" | "both";
}): Promise<string> {
  const side = opts.side ?? "both";

  const javaRoot = resolve(REPO_ROOT, "backend/src/main/java");
  const appRoot = resolve(REPO_ROOT, "frontend/src/app");

  const backend =
    side === "backend" || side === "both"
      ? await cache.wrap(["endpoints:backend"], { watch: [javaRoot], ttlMs: 30_000 }, () =>
          scanSpringEndpoints(),
        )
      : [];
  const frontend =
    side === "frontend" || side === "both"
      ? await cache.wrap(["endpoints:frontend"], { watch: [appRoot], ttlMs: 30_000 }, async () =>
          scanNextRoutes(),
        )
      : [];

  const out: string[] = [];
  if (backend.length) {
    out.push("# Backend (Spring) endpoints");
    for (const e of backend) {
      const handler = e.handler ? `  → ${e.handler}` : "";
      out.push(
        `${e.method.padEnd(6)} ${e.path}${handler}    [${relativeToRepo(e.file)}:${e.line}]`,
      );
    }
    out.push("");
  }
  if (frontend.length) {
    out.push("# Frontend (Next.js) routes");
    for (const r of frontend) {
      if (r.kind === "page") {
        out.push(`PAGE   ${r.urlPath}    [${relativeToRepo(r.file)}]`);
      } else {
        const m = (r.methods ?? []).join(",") || "?";
        out.push(`ROUTE  [${m}] ${r.urlPath}    [${relativeToRepo(r.file)}]`);
      }
    }
  }
  if (out.length === 0) return "No endpoints found.";
  return out.join("\n");
}
