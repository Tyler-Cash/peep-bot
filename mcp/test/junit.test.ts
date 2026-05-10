import { describe, it, expect } from "vitest";
import { mkdtempSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { readReports, formatSummary } from "../src/junit.js";

const PASS_XML = `<?xml version="1.0"?>
<testsuite name="dev.tylercash.event.FooTest" tests="2" failures="0" errors="0" skipped="0" time="0.123">
  <testcase classname="dev.tylercash.event.FooTest" name="ok" time="0.05"/>
  <testcase classname="dev.tylercash.event.FooTest" name="alsoOk" time="0.07"/>
</testsuite>`;

const FAIL_XML = `<?xml version="1.0"?>
<testsuite name="dev.tylercash.event.BarTest" tests="2" failures="1" errors="0" skipped="0" time="0.5">
  <testcase classname="dev.tylercash.event.BarTest" name="ok" time="0.1"/>
  <testcase classname="dev.tylercash.event.BarTest" name="broken" time="0.4">
    <failure message="expected: a but was: b" type="org.opentest4j.AssertionFailedError">
java.lang.AssertionError: expected: a but was: b
\tat dev.tylercash.event.BarTest.broken(BarTest.java:42)
    </failure>
  </testcase>
</testsuite>`;

const SKIP_XML = `<?xml version="1.0"?>
<testsuite name="dev.tylercash.event.SkipTest" tests="1" skipped="1" time="0.0">
  <testcase classname="dev.tylercash.event.SkipTest" name="todo">
    <skipped/>
  </testcase>
</testsuite>`;

function makeReportDir(): string {
  const dir = mkdtempSync(resolve(tmpdir(), "mcp-junit-"));
  mkdirSync(dir, { recursive: true });
  writeFileSync(resolve(dir, "TEST-pass.xml"), PASS_XML);
  writeFileSync(resolve(dir, "TEST-fail.xml"), FAIL_XML);
  writeFileSync(resolve(dir, "TEST-skip.xml"), SKIP_XML);
  return dir;
}

describe("junit XML parser", () => {
  it("counts passed/failed/skipped from a directory of suite files", () => {
    const dir = makeReportDir();
    const s = readReports(dir);
    expect(s.total).toBe(5);
    expect(s.passed).toBe(3);
    expect(s.failed).toBe(1);
    expect(s.skipped).toBe(1);
    expect(s.errored).toBe(0);
    expect(s.failures).toHaveLength(1);
    expect(s.failures[0].classname).toBe("dev.tylercash.event.BarTest");
    expect(s.failures[0].name).toBe("broken");
    expect(s.failures[0].message).toContain("expected: a");
    expect(s.failures[0].stack).toContain("BarTest.java:42");
  });

  it("respects sinceMs", () => {
    const dir = makeReportDir();
    const future = Date.now() + 60_000;
    const s = readReports(dir, future);
    expect(s.total).toBe(0);
  });

  it("formatSummary emits counts and failure details", () => {
    const dir = makeReportDir();
    const s = readReports(dir);
    const out = formatSummary(s);
    expect(out).toMatch(/total=5/);
    expect(out).toMatch(/failed=1/);
    expect(out).toContain("BarTest");
    expect(out).toContain("expected: a");
  });
});
