import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import { REPO_ROOT, RG_BIN, run, relativeToRepo } from "./core.js";

const TEST_GLOBS = ["backend/src/test/**/*.java"];

// Annotations that contribute to Spring's test ApplicationContext cache key.
// (Approximation — Spring's exact key uses MergedContextConfiguration; we don't
// resolve initializers/customizers/programmatic property sources.)
const CONTEXT_ANNOTATIONS = new Set([
  "SpringBootTest",
  "WebMvcTest",
  "DataJpaTest",
  "JdbcTest",
  "JsonTest",
  "DataMongoTest",
  "DataRedisTest",
  "RestClientTest",
  "ContextConfiguration",
  "TestPropertySource",
  "ActiveProfiles",
  "Import",
  "ImportTestResources",
  "AutoConfigureMockMvc",
  "AutoConfigureWebMvc",
  "AutoConfigureTestDatabase",
  "AutoConfigureWebTestClient",
  "DirtiesContext",
  "BootstrapWith",
]);

const MOCK_FIELD_ANNOTATIONS = new Set([
  "MockBean",
  "MockitoBean",
  "SpyBean",
  "MockitoSpyBean",
]);

export interface TestClassInfo {
  file: string;
  fqcn: string;
  simpleName: string;
  packageName: string;
  parent: string | null;
  classAnnotations: Annotation[];
  mocks: MockField[];
  inheritedAnnotations: Annotation[];
  inheritedMocks: MockField[];
}

export interface Annotation {
  name: string;
  args: string;
}

export interface MockField {
  annotation: string;
  type: string;
  name: string;
}

async function listTestFiles(): Promise<string[]> {
  const args = ["--files", "--no-messages"];
  for (const g of TEST_GLOBS) args.push("-g", g);
  args.push(REPO_ROOT);
  const { stdout } = await run(RG_BIN, args);
  return stdout
    .split("\n")
    .filter(Boolean)
    .filter((p) => /\.java$/.test(p) && /(Test|IT|TestBase|IntegrationTest)\.java$/.test(p));
}

function findClassStart(src: string): number {
  const m = /\n[ \t]*(?:public\s+)?(?:abstract\s+|final\s+)?class\s+\w+/.exec("\n" + src);
  return m ? m.index : -1;
}

function parseClassAnnotations(src: string, classDeclIdx: number): Annotation[] {
  const upTo = src.slice(0, classDeclIdx);
  const anns: Annotation[] = [];
  const re = /@(\w+)(\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\))?/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(upTo)) !== null) {
    anns.push({ name: m[1], args: (m[3] ?? "").replace(/\s+/g, " ").trim() });
  }
  return anns.filter((a) => CONTEXT_ANNOTATIONS.has(a.name));
}

function parseMocks(src: string): MockField[] {
  const out: MockField[] = [];
  const re =
    /@(MockBean|MockitoBean|SpyBean|MockitoSpyBean)(?:\s*\([^)]*\))?\s+(?:(?:private|protected|public|final|static|@\w+(?:\([^)]*\))?)\s+)*([\w.<>?,\s]+?)\s+(\w+)\s*[;=]/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    out.push({
      annotation: m[1],
      type: m[2].replace(/\s+/g, " ").trim(),
      name: m[3],
    });
  }
  return out;
}

function parsePackage(src: string): string {
  const m = /^\s*package\s+([\w.]+)\s*;/m.exec(src);
  return m ? m[1] : "";
}

function parseClassName(src: string, classDeclIdx: number): string {
  const m = /class\s+(\w+)/.exec(src.slice(classDeclIdx));
  return m ? m[1] : "";
}

function parseExtends(src: string, classDeclIdx: number): string | null {
  const head = src.slice(classDeclIdx, classDeclIdx + 400);
  const m = /class\s+\w+\s+extends\s+([\w.]+)/.exec(head);
  if (!m) return null;
  return m[1].split(".").pop() ?? null;
}

async function buildTestClassInfo(absPath: string): Promise<TestClassInfo | null> {
  const src = readFileSync(absPath, "utf8");
  const classIdx = findClassStart(src);
  if (classIdx < 0) return null;
  const simpleName = parseClassName(src, classIdx);
  if (!simpleName) return null;
  const packageName = parsePackage(src);
  const fqcn = packageName ? `${packageName}.${simpleName}` : simpleName;
  return {
    file: relativeToRepo(absPath),
    fqcn,
    simpleName,
    packageName,
    parent: parseExtends(src, classIdx),
    classAnnotations: parseClassAnnotations(src, classIdx),
    mocks: parseMocks(src),
    inheritedAnnotations: [],
    inheritedMocks: [],
  };
}

function mergeInheritance(infos: TestClassInfo[]): TestClassInfo[] {
  const bySimpleName = new Map<string, TestClassInfo>();
  for (const i of infos) bySimpleName.set(i.simpleName, i);
  for (const i of infos) {
    if (!i.parent) continue;
    const p = bySimpleName.get(i.parent);
    if (!p) continue;
    i.inheritedAnnotations = p.classAnnotations;
    i.inheritedMocks = p.mocks;
  }
  return infos;
}

function affinityHashOf(i: TestClassInfo): string {
  const allAnns = [...i.classAnnotations, ...i.inheritedAnnotations]
    .map((a) => `${a.name}(${a.args})`)
    .sort();
  const allMocks = [...i.mocks, ...i.inheritedMocks]
    .map((m) => `${m.annotation}:${m.type}`)
    .sort();
  const blob = `ANN\n${allAnns.join("\n")}\nMOCKS\n${allMocks.join("\n")}\nPARENT:${i.parent ?? ""}`;
  return createHash("sha256").update(blob).digest("hex").slice(0, 12);
}

async function loadAll(): Promise<TestClassInfo[]> {
  const files = await listTestFiles();
  const infos: TestClassInfo[] = [];
  for (const f of files) {
    const i = await buildTestClassInfo(f);
    if (i) infos.push(i);
  }
  return mergeInheritance(infos);
}

export async function testAffinityReport(opts: { groupsOnly?: boolean } = {}): Promise<string> {
  const infos = await loadAll();
  const springInfos = infos.filter(
    (i) => i.classAnnotations.length + i.inheritedAnnotations.length > 0,
  );
  const groups = new Map<string, TestClassInfo[]>();
  for (const i of springInfos) {
    const h = affinityHashOf(i);
    if (!groups.has(h)) groups.set(h, []);
    groups.get(h)!.push(i);
  }
  const sorted = [...groups.entries()].sort((a, b) => b[1].length - a[1].length);
  const lines: string[] = [];
  lines.push(
    `Spring test affinity (approximate context-cache groups): ${sorted.length} groups across ${springInfos.length} classes`,
  );
  lines.push(
    "Members of the same group probably share a cached Spring ApplicationContext at runtime.",
  );
  lines.push("Plain unit tests (no Spring annotations on class or its parent) are omitted.");
  lines.push("");
  for (const [hash, members] of sorted) {
    lines.push(`### group ${hash} (${members.length} class${members.length === 1 ? "" : "es"})`);
    const sample = members[0];
    const anns = [...sample.classAnnotations, ...sample.inheritedAnnotations]
      .map((a) => `@${a.name}${a.args ? `(${a.args.length > 80 ? a.args.slice(0, 77) + "..." : a.args})` : ""}`)
      .join(" ");
    if (anns) lines.push(`  annotations: ${anns}`);
    if (sample.parent) lines.push(`  parent (sample): ${sample.parent}`);
    const mockCount = sample.mocks.length + sample.inheritedMocks.length;
    if (mockCount > 0) lines.push(`  mocks (sample): ${mockCount}`);
    if (!opts.groupsOnly) {
      for (const m of members) {
        lines.push(`  - ${m.fqcn}  (${m.file})`);
      }
    } else {
      lines.push(`  - ${members.map((m) => m.simpleName).join(", ")}`);
    }
    lines.push("");
  }
  return lines.join("\n");
}

export async function testMocksReport(opts: { type?: string } = {}): Promise<string> {
  const infos = await loadAll();
  const byType = new Map<string, { fqcn: string; file: string; annotation: string; inherited: boolean }[]>();
  for (const i of infos) {
    for (const m of i.mocks) {
      if (!byType.has(m.type)) byType.set(m.type, []);
      byType.get(m.type)!.push({ fqcn: i.fqcn, file: i.file, annotation: m.annotation, inherited: false });
    }
    for (const m of i.inheritedMocks) {
      if (!byType.has(m.type)) byType.set(m.type, []);
      byType.get(m.type)!.push({ fqcn: i.fqcn, file: i.file, annotation: m.annotation, inherited: true });
    }
  }
  if (opts.type) {
    const hits = byType.get(opts.type) ?? [];
    if (hits.length === 0) return `No tests mock '${opts.type}'.`;
    const lines = [`Tests that mock ${opts.type} (${hits.length}):`];
    for (const h of hits) {
      lines.push(`  @${h.annotation}${h.inherited ? " (inherited)" : ""}  ${h.fqcn}`);
    }
    return lines.join("\n");
  }
  const sortedTypes = [...byType.entries()].sort((a, b) => b[1].length - a[1].length);
  const lines: string[] = [];
  lines.push(`@MockBean / @MockitoBean topology: ${sortedTypes.length} distinct types mocked across the suite`);
  lines.push("Cross-class mock pollution risk: types listed by # of test classes mocking them.");
  lines.push("");
  const top = sortedTypes.slice(0, 60);
  for (const [type, mockers] of top) {
    const ownCount = mockers.filter((m) => !m.inherited).length;
    const inh = mockers.filter((m) => m.inherited).length;
    lines.push(`${type} — ${mockers.length} class${mockers.length === 1 ? "" : "es"} (own=${ownCount}, inherited=${inh})`);
    for (const m of mockers.slice(0, 8)) {
      lines.push(`  @${m.annotation}${m.inherited ? " (inh)" : ""}  ${m.fqcn}`);
    }
    if (mockers.length > 8) lines.push(`  ... ${mockers.length - 8} more`);
  }
  if (sortedTypes.length > top.length) {
    lines.push("");
    lines.push(`... ${sortedTypes.length - top.length} more types omitted (use type='<fqn>' to inspect)`);
  }
  return lines.join("\n");
}
