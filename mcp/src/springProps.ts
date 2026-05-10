import { existsSync, readFileSync, readdirSync } from "node:fs";
import { resolve, basename } from "node:path";
import { parse as parseYaml } from "yaml";
import { REPO_ROOT, RG_BIN, run, relativeToRepo } from "./core.js";

const RESOURCES = resolve(REPO_ROOT, "backend/src/main/resources");

interface ResolvedProperty {
  key: string;
  value: unknown;
  source: string;
}

function flatten(obj: unknown, prefix = "", out: Record<string, unknown> = {}): Record<string, unknown> {
  if (obj === null || obj === undefined) return out;
  if (typeof obj !== "object" || Array.isArray(obj)) {
    out[prefix.replace(/\.$/, "")] = obj;
    return out;
  }
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const next = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) {
      flatten(v, next, out);
    } else {
      out[next] = v;
    }
  }
  return out;
}

function loadYamlFlat(absPath: string): { flat: Record<string, unknown>; ok: boolean } {
  if (!existsSync(absPath)) return { flat: {}, ok: false };
  try {
    const parsed = parseYaml(readFileSync(absPath, "utf8")) as unknown;
    return { flat: flatten(parsed ?? {}), ok: true };
  } catch {
    return { flat: {}, ok: false };
  }
}

function listApplicationYamls(): { profile: string | null; abs: string }[] {
  if (!existsSync(RESOURCES)) return [];
  const out: { profile: string | null; abs: string }[] = [];
  for (const f of readdirSync(RESOURCES)) {
    const abs = resolve(RESOURCES, f);
    const m = /^application(?:-([\w-]+))?\.(yaml|yml)$/.exec(f);
    if (!m) continue;
    out.push({ profile: m[1] ?? null, abs });
  }
  return out;
}

export async function springPropertyResolve(opts: {
  key: string;
  profiles?: string;
}): Promise<string> {
  const profileList = opts.profiles
    ? opts.profiles.split(",").map((p) => p.trim()).filter(Boolean)
    : [];
  const yamls = listApplicationYamls();
  const base = yamls.find((y) => y.profile === null);
  if (!base) return `application.yaml not found under ${relativeToRepo(RESOURCES)}.`;

  const merged: Record<string, ResolvedProperty> = {};
  const apply = (entry: { profile: string | null; abs: string }) => {
    const { flat, ok } = loadYamlFlat(entry.abs);
    if (!ok) return;
    for (const [k, v] of Object.entries(flat)) {
      merged[k] = {
        key: k,
        value: v,
        source: `${relativeToRepo(entry.abs)}${entry.profile ? ` (profile=${entry.profile})` : ""}`,
      };
    }
  };
  apply(base);
  for (const p of profileList) {
    const entry = yamls.find((y) => y.profile === p);
    if (entry) apply(entry);
  }

  const direct = merged[opts.key];
  const prefix = opts.key + ".";
  const subtree = Object.entries(merged)
    .filter(([k]) => k === opts.key || k.startsWith(prefix))
    .sort(([a], [b]) => a.localeCompare(b));

  const cpRefs = await findConfigurationPropertiesPrefix(opts.key);

  const out: string[] = [];
  out.push(`profiles: [${profileList.join(", ") || "(none)"}]`);
  const order = [base, ...profileList.map((p) => yamls.find((y) => y.profile === p)).filter(Boolean)];
  out.push(`merge order: ${order.map((e) => relativeToRepo((e as { abs: string }).abs)).join(" -> ")}`);
  out.push("");
  if (direct) {
    out.push(`# direct hit: ${opts.key}`);
    out.push(`  value : ${formatValue(direct.value)}`);
    out.push(`  source: ${direct.source}`);
  } else if (subtree.length > 0) {
    out.push(`# no exact key '${opts.key}' but ${subtree.length} key${subtree.length === 1 ? "" : "s"} under prefix:`);
    for (const [k, p] of subtree) {
      out.push(`  ${k}`);
      out.push(`    value : ${formatValue(p.value)}`);
      out.push(`    source: ${p.source}`);
    }
  } else {
    out.push(`# no YAML entry for '${opts.key}' under these profiles`);
  }

  if (cpRefs.length > 0) {
    out.push("");
    out.push(`# @ConfigurationProperties bindings matching this prefix:`);
    for (const ref of cpRefs) out.push(`  ${ref}`);
  }

  return out.join("\n");
}

function formatValue(v: unknown): string {
  if (v === null || v === undefined) return "(null)";
  if (typeof v === "string") return JSON.stringify(v);
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return JSON.stringify(v);
}

async function findConfigurationPropertiesPrefix(key: string): Promise<string[]> {
  const args = [
    "--line-number",
    "--no-heading",
    "-g",
    "backend/src/main/java/**/*.java",
    `@ConfigurationProperties\\s*\\(`,
    REPO_ROOT,
  ];
  const { stdout } = await run(RG_BIN, args);
  const out: string[] = [];
  for (const line of stdout.split("\n")) {
    if (!line) continue;
    const firstColon = line.indexOf(":");
    const colon = line.indexOf(":", firstColon + 1);
    const file = line.slice(0, firstColon);
    const text = line.slice(colon + 1);
    const m = /@ConfigurationProperties\s*\(\s*(?:prefix\s*=\s*)?"([^"]+)"/.exec(text);
    if (!m) continue;
    const prefix = m[1];
    if (key === prefix || key.startsWith(prefix + ".") || prefix.startsWith(key + ".")) {
      out.push(`${prefix}  -> ${relativeToRepo(file)}`);
    }
  }
  return out;
}

export async function springPropertyList(opts: { profiles?: string; prefix?: string }): Promise<string> {
  const profileList = opts.profiles
    ? opts.profiles.split(",").map((p) => p.trim()).filter(Boolean)
    : [];
  const yamls = listApplicationYamls();
  const base = yamls.find((y) => y.profile === null);
  if (!base) return `application.yaml not found.`;
  const merged: Record<string, ResolvedProperty> = {};
  const apply = (entry: { profile: string | null; abs: string }) => {
    const { flat } = loadYamlFlat(entry.abs);
    for (const [k, v] of Object.entries(flat)) {
      merged[k] = {
        key: k,
        value: v,
        source: `${basename(entry.abs)}${entry.profile ? ` [${entry.profile}]` : ""}`,
      };
    }
  };
  apply(base);
  for (const p of profileList) {
    const entry = yamls.find((y) => y.profile === p);
    if (entry) apply(entry);
  }
  const filter = opts.prefix ?? "";
  const entries = Object.entries(merged)
    .filter(([k]) => !filter || k === filter || k.startsWith(filter + "."))
    .sort(([a], [b]) => a.localeCompare(b));
  if (entries.length === 0) return `No properties match '${filter}' under profiles=[${profileList.join(",")}].`;
  const lines = [`Properties under profiles=[${profileList.join(",") || "(none)"}], prefix='${filter || "(any)"}': ${entries.length} keys`];
  lines.push("");
  for (const [k, v] of entries) {
    lines.push(`${k} = ${formatValue(v.value)}  (${v.source})`);
  }
  return lines.join("\n");
}
