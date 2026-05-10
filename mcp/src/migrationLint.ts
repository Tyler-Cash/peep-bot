import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { parse as parseYaml } from "yaml";
import { REPO_ROOT, relativeToRepo } from "./core.js";

const CHANGELOG_RESOURCES = resolve(REPO_ROOT, "backend/src/main/resources");
const MASTER = resolve(CHANGELOG_RESOURCES, "db/changelog/db.changelog-master.yaml");

interface YamlChangeLog {
  databaseChangeLog?: ChangeSetEntry[];
}

interface ChangeSetEntry {
  changeSet?: {
    id?: string;
    author?: string;
    changes?: any[];
    rollback?: any;
  };
  include?: { file?: string };
  includeAll?: { path?: string };
}

interface Finding {
  severity: "error" | "warning" | "info";
  file: string;
  changeSetId: string;
  message: string;
}

function loadYaml(path: string): YamlChangeLog | null {
  if (!existsSync(path)) return null;
  try {
    return parseYaml(readFileSync(path, "utf8")) as YamlChangeLog;
  } catch {
    return null;
  }
}

function collectChangeSets(): { file: string; changeSet: NonNullable<ChangeSetEntry["changeSet"]> }[] {
  const out: { file: string; changeSet: NonNullable<ChangeSetEntry["changeSet"]> }[] = [];
  const visit = (path: string) => {
    const log = loadYaml(path);
    if (!log?.databaseChangeLog) return;
    const here = relativeToRepo(path);
    for (const entry of log.databaseChangeLog) {
      if (entry.changeSet) {
        out.push({ file: here, changeSet: entry.changeSet });
      } else if (entry.include?.file) {
        visit(resolve(CHANGELOG_RESOURCES, entry.include.file));
      } else if (entry.includeAll?.path) {
        const dir = resolve(CHANGELOG_RESOURCES, entry.includeAll.path);
        if (existsSync(dir) && statSync(dir).isDirectory()) {
          for (const f of readdirSync(dir).sort()) {
            if (f.endsWith(".yaml") || f.endsWith(".yml")) visit(resolve(dir, f));
          }
        }
      }
    }
  };
  visit(MASTER);
  return out;
}

function buildIndexedColumnSet(
  sets: { file: string; changeSet: NonNullable<ChangeSetEntry["changeSet"]> }[],
): Set<string> {
  const out = new Set<string>();
  const add = (table: string, columnNames: string) => {
    for (const c of columnNames.split(",")) out.add(`${table}.${c.trim()}`);
  };
  for (const { changeSet } of sets) {
    for (const ch of changeSet.changes ?? []) {
      const op = Object.keys(ch)[0];
      const body = ch[op];
      if (op === "createIndex") {
        if (body?.tableName && body?.columns) {
          const cols = (body.columns as any[])
            .map((c: any) => c?.column?.name ?? c?.name)
            .filter(Boolean)
            .join(",");
          if (cols) add(body.tableName, cols);
        }
      } else if (op === "addColumn" || op === "createTable") {
        for (const col of (body?.columns ?? []) as any[]) {
          const c = col?.column;
          if (c?.constraints?.primaryKey) add(body.tableName, c.name);
        }
      } else if (op === "addPrimaryKey") {
        if (body?.tableName && body?.columnNames) add(body.tableName, body.columnNames);
      } else if (op === "addUniqueConstraint") {
        if (body?.tableName && body?.columnNames) add(body.tableName, body.columnNames);
      } else if (op === "sql" && typeof body?.sql === "string") {
        const m = /create\s+(?:unique\s+)?index[^(]*\bon\s+(\w+)\s*\(([^)]+)\)/i.exec(body.sql);
        if (m) add(m[1], m[2].replace(/\s+/g, ""));
      }
    }
  }
  return out;
}

function isNotNull(col: any): boolean {
  return col?.constraints?.nullable === false;
}

function hasDefault(col: any): boolean {
  return (
    col?.defaultValue !== undefined ||
    col?.defaultValueComputed !== undefined ||
    col?.defaultValueDate !== undefined ||
    col?.defaultValueNumeric !== undefined ||
    col?.defaultValueBoolean !== undefined ||
    col?.valueComputed !== undefined
  );
}

function fkColumns(body: any): { table: string; column: string }[] {
  const out: { table: string; column: string }[] = [];
  if (body?.baseTableName && body?.baseColumnNames) {
    for (const c of String(body.baseColumnNames).split(",")) {
      out.push({ table: body.baseTableName, column: c.trim() });
    }
  }
  return out;
}

function columnFkRefs(tableName: string, body: any): { table: string; column: string }[] {
  const out: { table: string; column: string }[] = [];
  for (const col of (body?.columns ?? []) as any[]) {
    const c = col?.column;
    if (c?.constraints?.foreignKeyName || c?.constraints?.references) {
      out.push({ table: tableName, column: c.name });
    }
  }
  return out;
}

export async function migrationLint(): Promise<string> {
  const sets = collectChangeSets();
  const indexed = buildIndexedColumnSet(sets);
  const findings: Finding[] = [];

  for (const { file, changeSet } of sets) {
    const id = changeSet.id ?? "(unnamed)";
    for (const ch of changeSet.changes ?? []) {
      const op = Object.keys(ch)[0];
      const body = ch[op];

      if (op === "addColumn") {
        const tableName = body?.tableName ?? "";
        for (const col of (body?.columns ?? []) as any[]) {
          const c = col?.column;
          if (!c) continue;
          if (isNotNull(c) && !hasDefault(c)) {
            findings.push({
              severity: "error",
              file,
              changeSetId: id,
              message: `addColumn ${tableName}.${c.name} is NOT NULL without a default — will fail on non-empty tables. Add defaultValue/defaultValueComputed, or split into 3 changesets (add nullable → backfill → set NOT NULL).`,
            });
          }
        }
      }

      if (op === "createTable") {
        for (const fk of columnFkRefs(body?.tableName ?? "", body)) {
          if (!indexed.has(`${fk.table}.${fk.column}`)) {
            findings.push({
              severity: "warning",
              file,
              changeSetId: id,
              message: `FK ${fk.table}.${fk.column} declared in createTable has no index — joins on this column will scan. Add a createIndex changeset.`,
            });
          }
        }
      }

      if (op === "addForeignKeyConstraint") {
        for (const fk of fkColumns(body)) {
          if (!indexed.has(`${fk.table}.${fk.column}`)) {
            findings.push({
              severity: "warning",
              file,
              changeSetId: id,
              message: `addForeignKeyConstraint on ${fk.table}.${fk.column} has no index. Add createIndex on the same column.`,
            });
          }
        }
      }

      if (op === "dropColumn" || op === "dropTable") {
        if (!changeSet.rollback) {
          findings.push({
            severity: "warning",
            file,
            changeSetId: id,
            message: `${op} on ${body?.tableName ?? "?"} has no rollback block — recovering from a bad deploy will require manual SQL.`,
          });
        }
      }

      if (op === "sql" && typeof body?.sql === "string") {
        const sql = body.sql.toLowerCase();
        if (/\bcreate\s+index\b/.test(sql) && !/concurrently/.test(sql)) {
          findings.push({
            severity: "warning",
            file,
            changeSetId: id,
            message: `Raw SQL CREATE INDEX without CONCURRENTLY — blocks writes during creation. Use 'CREATE INDEX CONCURRENTLY' for production-sized tables.`,
          });
        }
        if (/\bdrop\s+(table|column)\b/.test(sql) && !changeSet.rollback) {
          findings.push({
            severity: "warning",
            file,
            changeSetId: id,
            message: `Raw SQL drop without rollback block.`,
          });
        }
      }
    }
  }

  const ranked: Record<string, Finding[]> = { error: [], warning: [], info: [] };
  for (const f of findings) ranked[f.severity].push(f);

  const counts = `error=${ranked.error.length} warning=${ranked.warning.length} info=${ranked.info.length}`;
  if (findings.length === 0) return `Liquibase migration lint: clean (${sets.length} changesets scanned).\n${counts}`;
  const lines = [`Liquibase migration lint: ${findings.length} findings across ${sets.length} changesets`, counts, ""];
  for (const sev of ["error", "warning", "info"] as const) {
    if (ranked[sev].length === 0) continue;
    lines.push(`## ${sev}s (${ranked[sev].length})`);
    for (const f of ranked[sev]) {
      lines.push(`  ${f.file}  changeSet=${f.changeSetId}`);
      lines.push(`    ${f.message}`);
    }
    lines.push("");
  }
  return lines.join("\n");
}
