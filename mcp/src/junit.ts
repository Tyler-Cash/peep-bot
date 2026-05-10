import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { XMLParser } from "fast-xml-parser";
import { REPO_ROOT } from "./core.js";

export interface FailureDetail {
  classname: string;
  name: string;
  message: string;
  type: string;
  stack: string;
}

export interface TestSummary {
  total: number;
  passed: number;
  failed: number;
  errored: number;
  skipped: number;
  durationMs: number;
  failures: FailureDetail[];
}

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "",
  textNodeName: "text",
  parseAttributeValue: false,
  parseTagValue: false,
});

const REPORT_DIRS = [
  "backend/build/test-results/test",
  "backend/build/test-results/e2eTest",
  "frontend/test-results/junit",
];

function asArray<T>(v: T | T[] | undefined): T[] {
  if (v === undefined) return [];
  return Array.isArray(v) ? v : [v];
}

function trimStack(s: string, maxLines = 20): string {
  const lines = s.split("\n");
  if (lines.length <= maxLines) return s.trim();
  return lines.slice(0, maxLines).join("\n").trim() + `\n... (${lines.length - maxLines} more lines)`;
}

function extractFailureNodes(testcase: any): { node: any; type: "failure" | "error" }[] {
  const out: { node: any; type: "failure" | "error" }[] = [];
  for (const f of asArray(testcase.failure)) out.push({ node: f, type: "failure" });
  for (const e of asArray(testcase.error)) out.push({ node: e, type: "error" });
  return out;
}

function parseSuiteFile(absPath: string): TestSummary {
  const xml = readFileSync(absPath, "utf8");
  const root = parser.parse(xml);
  const suites = asArray(root.testsuites?.testsuite ?? root.testsuite);
  const summary: TestSummary = {
    total: 0,
    passed: 0,
    failed: 0,
    errored: 0,
    skipped: 0,
    durationMs: 0,
    failures: [],
  };
  for (const suite of suites) {
    summary.durationMs += Number(suite.time ?? 0) * 1000;
    for (const tc of asArray(suite.testcase)) {
      summary.total++;
      const failureNodes = extractFailureNodes(tc);
      const skipped = "skipped" in tc;
      if (skipped) {
        summary.skipped++;
      } else if (failureNodes.length > 0) {
        for (const { node, type } of failureNodes) {
          if (type === "error") summary.errored++;
          else summary.failed++;
          summary.failures.push({
            classname: tc.classname ?? suite.name ?? "",
            name: tc.name ?? "",
            message: node.message ?? "",
            type: node.type ?? "",
            stack: trimStack(node.text ?? ""),
          });
        }
      } else {
        summary.passed++;
      }
    }
  }
  return summary;
}

function mergeSummaries(a: TestSummary, b: TestSummary): TestSummary {
  return {
    total: a.total + b.total,
    passed: a.passed + b.passed,
    failed: a.failed + b.failed,
    errored: a.errored + b.errored,
    skipped: a.skipped + b.skipped,
    durationMs: a.durationMs + b.durationMs,
    failures: [...a.failures, ...b.failures],
  };
}

const EMPTY: TestSummary = {
  total: 0,
  passed: 0,
  failed: 0,
  errored: 0,
  skipped: 0,
  durationMs: 0,
  failures: [],
};

export function readReports(dirAbs: string, sinceMs?: number): TestSummary {
  if (!existsSync(dirAbs)) return EMPTY;
  let merged: TestSummary = { ...EMPTY, failures: [] };
  for (const f of readdirSync(dirAbs)) {
    if (!f.endsWith(".xml")) continue;
    const abs = resolve(dirAbs, f);
    if (sinceMs !== undefined) {
      try {
        if (statSync(abs).mtimeMs < sinceMs) continue;
      } catch {
        continue;
      }
    }
    try {
      merged = mergeSummaries(merged, parseSuiteFile(abs));
    } catch {
      // skip malformed report
    }
  }
  return merged;
}

export function readAllReports(sinceMs?: number): TestSummary {
  let merged: TestSummary = { ...EMPTY, failures: [] };
  for (const rel of REPORT_DIRS) {
    merged = mergeSummaries(merged, readReports(resolve(REPO_ROOT, rel), sinceMs));
  }
  return merged;
}

export function formatSummary(s: TestSummary, opts: { maxFailures?: number } = {}): string {
  const max = opts.maxFailures ?? 20;
  const headline = `total=${s.total} passed=${s.passed} failed=${s.failed} errored=${s.errored} skipped=${s.skipped} duration=${(s.durationMs / 1000).toFixed(1)}s`;
  if (s.failures.length === 0) return headline;
  const shown = s.failures.slice(0, max);
  const lines = [headline, "", `failures (${s.failures.length}):`];
  for (const f of shown) {
    lines.push("");
    lines.push(`✗ ${f.classname} > ${f.name}`);
    if (f.type) lines.push(`  type: ${f.type}`);
    if (f.message) lines.push(`  message: ${f.message.split("\n")[0].slice(0, 240)}`);
    if (f.stack) {
      const stackLines = f.stack.split("\n").slice(0, 8);
      lines.push("  stack:");
      for (const sl of stackLines) lines.push(`    ${sl}`);
    }
  }
  if (s.failures.length > shown.length) {
    lines.push("");
    lines.push(`... ${s.failures.length - shown.length} more failures`);
  }
  return lines.join("\n");
}
