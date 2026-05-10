import { readFileSync, statSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { parse as parseYaml } from "yaml";
import { REPO_ROOT } from "./core.js";
import { cache } from "./cache.js";

interface ColumnDef {
  name: string;
  type: string;
  constraints?: Record<string, unknown>;
  defaultValue?: string;
}

interface TableState {
  columns: Map<string, ColumnDef>;
  // Order of columns as added.
  order: string[];
}

interface Schema {
  tables: Map<string, TableState>;
  notes: string[];
}

const CHANGELOG_RESOURCES = resolve(
  REPO_ROOT,
  "backend/src/main/resources",
);
const MASTER = resolve(
  CHANGELOG_RESOURCES,
  "db/changelog/db.changelog-master.yaml",
);

interface YamlChange {
  [op: string]: any;
}

interface YamlChangeSet {
  changeSet?: { id?: string; author?: string; changes?: YamlChange[] };
  include?: { file?: string };
  includeAll?: { path?: string };
}

interface YamlChangeLog {
  databaseChangeLog?: YamlChangeSet[];
}

function loadYaml(path: string): YamlChangeLog | null {
  try {
    const text = readFileSync(path, "utf8");
    return parseYaml(text) as YamlChangeLog;
  } catch {
    return null;
  }
}

function flattenChangeSets(rootPath: string, visited: Set<string>): YamlChangeSet[] {
  if (visited.has(rootPath)) return [];
  visited.add(rootPath);
  const doc = loadYaml(rootPath);
  if (!doc?.databaseChangeLog) return [];
  const out: YamlChangeSet[] = [];
  for (const entry of doc.databaseChangeLog) {
    if (entry.include?.file) {
      // file path is relative to the resources root
      const incl = resolve(CHANGELOG_RESOURCES, entry.include.file);
      out.push(...flattenChangeSets(incl, visited));
    } else {
      out.push(entry);
    }
  }
  return out;
}

function tableState(schema: Schema, name: string): TableState {
  let s = schema.tables.get(name);
  if (!s) {
    s = { columns: new Map(), order: [] };
    schema.tables.set(name, s);
  }
  return s;
}

function applyChange(schema: Schema, change: YamlChange): void {
  const op = Object.keys(change)[0];
  const body = change[op];
  switch (op) {
    case "createTable": {
      const name = body.tableName;
      if (!name) return;
      const state = tableState(schema, name);
      for (const colWrap of body.columns ?? []) {
        const col = colWrap.column ?? colWrap;
        const def: ColumnDef = {
          name: col.name,
          type: col.type,
          constraints: col.constraints,
          defaultValue:
            col.defaultValue ??
            col.defaultValueBoolean ??
            col.defaultValueNumeric ??
            col.defaultValueComputed,
        };
        if (!state.columns.has(def.name)) state.order.push(def.name);
        state.columns.set(def.name, def);
      }
      break;
    }
    case "addColumn": {
      const state = tableState(schema, body.tableName);
      for (const colWrap of body.columns ?? []) {
        const col = colWrap.column ?? colWrap;
        const def: ColumnDef = {
          name: col.name,
          type: col.type,
          constraints: col.constraints,
          defaultValue:
            col.defaultValue ??
            col.defaultValueBoolean ??
            col.defaultValueNumeric ??
            col.defaultValueComputed,
        };
        if (!state.columns.has(def.name)) state.order.push(def.name);
        state.columns.set(def.name, def);
      }
      break;
    }
    case "dropColumn": {
      const state = tableState(schema, body.tableName);
      const cols: string[] = body.columnName
        ? [body.columnName]
        : (body.columns ?? []).map((c: any) => (c.column ?? c).name);
      for (const name of cols) {
        state.columns.delete(name);
        state.order = state.order.filter((n) => n !== name);
      }
      break;
    }
    case "renameColumn": {
      const state = tableState(schema, body.tableName);
      const oldName = body.oldColumnName;
      const newName = body.newColumnName;
      const col = state.columns.get(oldName);
      if (col) {
        state.columns.delete(oldName);
        col.name = newName;
        state.columns.set(newName, col);
        state.order = state.order.map((n) => (n === oldName ? newName : n));
      }
      break;
    }
    case "modifyDataType": {
      const state = tableState(schema, body.tableName);
      const col = state.columns.get(body.columnName);
      if (col) col.type = body.newDataType;
      break;
    }
    case "dropTable": {
      schema.tables.delete(body.tableName);
      break;
    }
    case "renameTable": {
      const state = schema.tables.get(body.oldTableName);
      if (state) {
        schema.tables.delete(body.oldTableName);
        schema.tables.set(body.newTableName, state);
      }
      break;
    }
    case "addPrimaryKey":
    case "addForeignKeyConstraint":
    case "addUniqueConstraint":
    case "createIndex":
    case "addNotNullConstraint":
    case "dropNotNullConstraint":
    case "sql":
      // Tracked as a note rather than column-level change.
      schema.notes.push(`${op}: ${JSON.stringify(body).slice(0, 160)}`);
      break;
    default:
      schema.notes.push(`(unhandled) ${op}`);
  }
}

function buildSchema(): Schema {
  const schema: Schema = { tables: new Map(), notes: [] };
  const sets = flattenChangeSets(MASTER, new Set());
  for (const set of sets) {
    for (const change of set.changeSet?.changes ?? []) {
      applyChange(schema, change);
    }
  }
  return schema;
}

function formatSchema(schema: Schema): string {
  if (schema.tables.size === 0) {
    return "No tables found in changelog.";
  }
  const out: string[] = [];
  const tableNames = Array.from(schema.tables.keys()).sort();
  for (const name of tableNames) {
    const state = schema.tables.get(name)!;
    out.push(`## ${name}`);
    for (const col of state.order) {
      const def = state.columns.get(col);
      if (!def) continue;
      const cons = def.constraints
        ? Object.entries(def.constraints)
            .map(([k, v]) => `${k}=${v}`)
            .join(", ")
        : "";
      const dflt = def.defaultValue !== undefined ? ` default=${def.defaultValue}` : "";
      out.push(`  ${def.name.padEnd(28)} ${String(def.type).padEnd(20)}${dflt}${cons ? `  (${cons})` : ""}`);
    }
    out.push("");
  }
  return out.join("\n").trimEnd();
}

export async function dbSchemaTool(): Promise<string> {
  let watch: string[] = [MASTER];
  try {
    statSync(MASTER);
  } catch {
    return `Master changelog not found at ${MASTER}`;
  }
  // Watch the changelog directory mtime as a coarse invalidator.
  try {
    watch.push(dirname(MASTER));
  } catch {
    // ignore
  }
  return cache.wrap(["dbSchema"], { watch, ttlMs: 60_000 }, async () => {
    const schema = buildSchema();
    return formatSchema(schema);
  });
}
