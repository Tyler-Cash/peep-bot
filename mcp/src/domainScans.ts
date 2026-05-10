import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { REPO_ROOT, RG_BIN, run, relativeToRepo } from "./core.js";
import { listEndpoints } from "./endpoints.js";

const LIFECYCLE_GLOB = "backend/src/main/java/dev/tylercash/event/lifecycle/**/*.java";

interface LifecycleListener {
  fqcn: string;
  file: string;
  eventType: string | null;
  dependencies: string[];
  retryAnnotations: string[];
  componentAnnotations: string[];
}

async function listJavaFiles(glob: string): Promise<string[]> {
  const args = ["--files", "--no-messages", "-g", glob, REPO_ROOT];
  const { stdout } = await run(RG_BIN, args);
  return stdout.split("\n").filter(Boolean);
}

function extractClassName(src: string): { name: string; fromIndex: number } | null {
  const m = /\n[ \t]*(?:public\s+)?(?:abstract\s+|final\s+)?class\s+(\w+)/.exec("\n" + src);
  if (!m) return null;
  return { name: m[1], fromIndex: m.index };
}

function extractPackage(src: string): string {
  const m = /^\s*package\s+([\w.]+)\s*;/m.exec(src);
  return m ? m[1] : "";
}

function extractAnnotations(src: string, before: number): string[] {
  const upTo = src.slice(0, before);
  const re = /@(\w+)(\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\))?/g;
  const out: string[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(upTo)) !== null) {
    const args = (m[3] ?? "").replace(/\s+/g, " ").trim();
    out.push(args ? `@${m[1]}(${args.length > 80 ? args.slice(0, 77) + "..." : args})` : `@${m[1]}`);
  }
  return out;
}

function isLifecycleListenerClass(src: string, classFromIdx: number): boolean {
  const head = src.slice(classFromIdx, classFromIdx + 1500);
  if (/implements\s+[\w<>?,\s]*(?:DurableEventListener|EventBusListener)/.test(head)) return true;
  if (/extends\s+[\w<>?,\s]*(?:BaseEventBusListener|AbstractDurableEventListener)/.test(head)) return true;
  if (/@(?:DurableEventListener|EventBusListener)\b/.test(extractAnnotations(src, classFromIdx).join(" "))) return true;
  return false;
}

function eventTypeOf(src: string): string | null {
  const m = /eventType\s*\(\s*\)\s*\{\s*return\s+(\w+)\.class/.exec(src);
  if (m) return m[1];
  // implements DurableEventListener<EventLifecycleEvent.EventCreated> or EventBusListener<X>
  const g = /(?:DurableEventListener|EventBusListener)<([\w.]+)>/.exec(src);
  if (!g) return null;
  // Take the rightmost segment (e.g. EventLifecycleEvent.EventCreated -> EventCreated)
  return g[1].split(".").pop()!;
}

function constructorDependencies(src: string, className: string): string[] {
  const re = new RegExp(
    `(?:public\\s+)?${className}\\s*\\(([^)]*)\\)\\s*\\{`,
  );
  const m = re.exec(src);
  if (!m) {
    const out: string[] = [];
    const fieldRe = /private\s+final\s+([\w.<>?,\s]+?)\s+\w+\s*[=;]/g;
    let fm: RegExpExecArray | null;
    while ((fm = fieldRe.exec(src)) !== null) {
      out.push(fm[1].replace(/\s+/g, " ").trim());
    }
    return out;
  }
  return m[1]
    .split(",")
    .map((p) => p.trim())
    .filter(Boolean)
    .map((p) => p.replace(/^@\w+(\s*\([^)]*\))?\s+/, ""))
    .map((p) => p.replace(/\s+\w+$/, "").trim());
}

export async function lifecycleRegistry(): Promise<string> {
  const files = await listJavaFiles(LIFECYCLE_GLOB);
  const listeners: LifecycleListener[] = [];
  for (const f of files) {
    const src = readFileSync(f, "utf8");
    const cls = extractClassName(src);
    if (!cls) continue;
    if (!isLifecycleListenerClass(src, cls.fromIndex)) continue;
    const pkg = extractPackage(src);
    const anns = extractAnnotations(src, cls.fromIndex);
    listeners.push({
      fqcn: pkg ? `${pkg}.${cls.name}` : cls.name,
      file: relativeToRepo(f),
      eventType: eventTypeOf(src),
      dependencies: constructorDependencies(src, cls.name),
      retryAnnotations: anns.filter((a) => /Retry|Backoff|Resilience4j|CircuitBreaker/i.test(a)),
      componentAnnotations: anns.filter((a) => /^@(Component|Service|EventBusListener|Order|Slf4j|RequiredArgsConstructor|Transactional)/.test(a)),
    });
  }
  if (listeners.length === 0) return "No lifecycle listeners found (looked under backend/src/main/java/.../lifecycle/).";

  const byEvent = new Map<string, LifecycleListener[]>();
  for (const l of listeners) {
    const key = l.eventType ?? "(unknown)";
    if (!byEvent.has(key)) byEvent.set(key, []);
    byEvent.get(key)!.push(l);
  }

  const lines: string[] = [];
  lines.push(`Lifecycle listener registry: ${listeners.length} listener${listeners.length === 1 ? "" : "s"} across ${byEvent.size} event type${byEvent.size === 1 ? "" : "s"}`);
  lines.push("");
  for (const [event, members] of [...byEvent.entries()].sort()) {
    lines.push(`## ${event} (${members.length})`);
    for (const l of members) {
      lines.push(`  ${l.fqcn}  (${l.file})`);
      if (l.componentAnnotations.length > 0) lines.push(`    ${l.componentAnnotations.join(" ")}`);
      if (l.retryAnnotations.length > 0) lines.push(`    retry: ${l.retryAnnotations.join(" ")}`);
      if (l.dependencies.length > 0) {
        lines.push(`    deps: ${l.dependencies.slice(0, 6).join(", ")}${l.dependencies.length > 6 ? ` (+${l.dependencies.length - 6})` : ""}`);
      }
    }
    lines.push("");
  }
  return lines.join("\n");
}

// ---------- Discord interaction map ----------

const DISCORD_GLOBS = [
  "backend/src/main/java/dev/tylercash/event/discord/**/*.java",
  "backend/src/main/java/dev/tylercash/event/contract/**/*.java",
];

interface DiscordSurface {
  kind: string;
  identifier: string;
  className: string;
  file: string;
}

export async function discordInteractionMap(): Promise<string> {
  const files: string[] = [];
  for (const g of DISCORD_GLOBS) files.push(...(await listJavaFiles(g)));
  const surfaces: DiscordSurface[] = [];
  for (const f of files) {
    const src = readFileSync(f, "utf8");
    const cls = extractClassName(src);
    if (!cls) continue;
    const fileRel = relativeToRepo(f);

    for (const m of src.matchAll(/Commands\.slash\(\s*"([^"]+)"/g)) {
      surfaces.push({ kind: "slash-command", identifier: m[1], className: cls.name, file: fileRel });
    }
    for (const m of src.matchAll(/new\s+SubcommandData\(\s*"([^"]+)"/g)) {
      surfaces.push({ kind: "subcommand", identifier: m[1], className: cls.name, file: fileRel });
    }
    for (const m of src.matchAll(/Button\.(?:primary|secondary|success|danger|link)\(\s*"([^"]+)"/g)) {
      surfaces.push({ kind: "button", identifier: m[1], className: cls.name, file: fileRel });
    }
    for (const m of src.matchAll(/Modal\.create\(\s*"([^"]+)"/g)) {
      surfaces.push({ kind: "modal", identifier: m[1], className: cls.name, file: fileRel });
    }
    for (const m of src.matchAll(/public\s+(?:void|[\w<>?]+)\s+(on(?:Button|Modal|SlashCommand|StringSelect|EntitySelect)Interaction)\b/g)) {
      surfaces.push({ kind: "handler-override", identifier: m[1], className: cls.name, file: fileRel });
    }
  }

  if (surfaces.length === 0) return "No Discord interaction surfaces found (looked under discord/ and contract/).";
  const byKind = new Map<string, DiscordSurface[]>();
  for (const s of surfaces) {
    if (!byKind.has(s.kind)) byKind.set(s.kind, []);
    byKind.get(s.kind)!.push(s);
  }
  const lines = [`Discord interaction map: ${surfaces.length} surface${surfaces.length === 1 ? "" : "s"}`];
  lines.push("");
  for (const [kind, members] of [...byKind.entries()].sort()) {
    lines.push(`## ${kind} (${members.length})`);
    const seen = new Set<string>();
    for (const s of members) {
      const key = `${s.kind}:${s.identifier}:${s.className}`;
      if (seen.has(key)) continue;
      seen.add(key);
      lines.push(`  ${s.identifier}  -> ${s.className}  (${s.file})`);
    }
    lines.push("");
  }
  return lines.join("\n");
}

// ---------- MSW <-> Spring endpoint diff ----------

interface MswHandler {
  method: string;
  path: string;
  file: string;
}

function parseMswHandlers(): MswHandler[] {
  const root = resolve(REPO_ROOT, "frontend/src/mocks");
  if (!existsSync(root)) return [];
  const out: MswHandler[] = [];
  const visit = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const abs = resolve(dir, entry);
      const st = statSync(abs);
      if (st.isDirectory()) {
        visit(abs);
      } else if (abs.endsWith(".ts") || abs.endsWith(".tsx")) {
        const src = readFileSync(abs, "utf8");
        // Match either http.get("/path", ...) or http.get(API("/path"), ...).
        // The API(...) wrapper prefixes with the api base path; we add /api back here.
        const re = /http\.(get|post|put|patch|delete|all)\(\s*(?:API\s*\(\s*)?['"`]([^'"`]+)['"`]/g;
        for (const m of src.matchAll(re)) {
          const method = m[1].toUpperCase();
          let path = m[2];
          // If matched inside API("/x"), prepend /api so it lines up with Spring's context path.
          const startOfMatch = m.index ?? 0;
          const window = src.slice(Math.max(0, startOfMatch - 10), startOfMatch + 20);
          if (/API\s*\(/.test(window) && !path.startsWith("/api")) {
            path = `/api${path.startsWith("/") ? "" : "/"}${path}`;
          }
          out.push({ method, path, file: relativeToRepo(abs) });
        }
      }
    }
  };
  visit(root);
  return out;
}

function normalizePath(p: string): string {
  // Treat {id}, :id, [^/]+, [0-9]+, [a-z]+ (MSW regex segments), and (.*)
  // as the same kind of placeholder so MSW-style and Spring-style paths line up.
  return p
    .replace(/\{[^}]+\}/g, "$1")
    .replace(/:[^/]+/g, "$1")
    .replace(/\[[^\]]+\]\+/g, "$1")
    .replace(/\[[^\]]+\]\*/g, "$1")
    .replace(/\\d\+/g, "$1")
    .replace(/\\w\+/g, "$1")
    .replace(/\(.*?\)/g, "$1")
    .replace(/\?$/, "")
    .replace(/\/$/, "");
}

export async function mswSpringDiff(): Promise<string> {
  const endpointsText = await listEndpoints({ side: "backend" });
  const lines = endpointsText.split("\n");
  const real: { method: string; path: string; location: string }[] = [];
  const re = /^([A-Z]+)\s+(\S+)\s+→.*\[(.+)\]/;
  for (const line of lines) {
    const m = re.exec(line);
    if (!m) continue;
    real.push({ method: m[1], path: m[2], location: m[3] });
  }

  const mocks = parseMswHandlers();

  const realSet = new Set(real.map((r) => `${r.method} ${normalizePath(r.path)}`));
  const mockSet = new Set(mocks.map((m) => `${m.method} ${normalizePath(m.path)}`));

  const onlyInMocks = mocks.filter((m) => !realSet.has(`${m.method} ${normalizePath(m.path)}`));
  const onlyInBackend = real.filter((r) => !mockSet.has(`${r.method} ${normalizePath(r.path)}`));

  const out: string[] = [];
  out.push(`MSW vs Spring endpoint diff: ${real.length} backend, ${mocks.length} MSW handlers`);
  out.push("");
  out.push(`# In MSW but no matching Spring endpoint (${onlyInMocks.length})`);
  out.push("These mocks may be stale; if hit by frontend code in live mode they will 404.");
  for (const m of onlyInMocks) out.push(`  ${m.method} ${m.path}  (${m.file})`);
  out.push("");
  out.push(`# In Spring but no MSW handler (${onlyInBackend.length})`);
  out.push("Frontend running in mock mode will fail to call these unless a passthrough is configured.");
  for (const r of onlyInBackend) out.push(`  ${r.method} ${r.path}  [${r.location}]`);
  return out.join("\n");
}
