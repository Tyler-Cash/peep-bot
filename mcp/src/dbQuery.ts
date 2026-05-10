import { Client } from "pg";
import { run } from "./core.js";

export interface DbTarget {
  host: string;
  port: number;
  user: string;
  password: string;
  database: string;
  source: "testcontainer" | "compose" | "env";
  containerName?: string;
}

interface DockerContainer {
  ID: string;
  Image: string;
  Names: string;
  Ports: string;
  Labels: string;
  State: string;
}

async function dockerPs(): Promise<DockerContainer[]> {
  const { stdout, code } = await run("docker", [
    "ps",
    "--no-trunc",
    "--format",
    "{{json .}}",
  ]);
  if (code !== 0) return [];
  return stdout
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line) as DockerContainer;
      } catch {
        return null;
      }
    })
    .filter((c): c is DockerContainer => c !== null);
}

function parsePublishedPort(ports: string, internalPort: number): number | null {
  const re = new RegExp(`(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|\\[::\\]):(\\d+)->${internalPort}/tcp`);
  const m = re.exec(ports);
  return m ? Number(m[1]) : null;
}

export async function discoverTarget(): Promise<DbTarget | { error: string }> {
  if (process.env.PEEP_BOT_DB_URL) {
    const u = new URL(process.env.PEEP_BOT_DB_URL);
    return {
      host: u.hostname,
      port: Number(u.port || 5432),
      user: decodeURIComponent(u.username),
      password: decodeURIComponent(u.password),
      database: u.pathname.replace(/^\//, "") || "postgres",
      source: "env",
    };
  }
  const containers = await dockerPs();
  if (containers.length === 0) {
    return {
      error:
        "No running docker containers (or `docker ps` failed). Set PEEP_BOT_DB_URL to a JDBC-style URL to override.",
    };
  }
  const tcPg = containers.find(
    (c) =>
      c.Labels.includes("org.testcontainers=true") &&
      /postgres|pgvector/i.test(c.Image),
  );
  const composePg = containers.find(
    (c) =>
      c.Labels.includes("com.docker.compose.project") &&
      /postgres|pgvector/i.test(c.Image),
  );
  const pick = tcPg ?? composePg;
  if (!pick) {
    return {
      error: `No Postgres container found in 'docker ps'. Saw: ${containers
        .map((c) => `${c.Names}(${c.Image})`)
        .join(", ")}`,
    };
  }
  const port = parsePublishedPort(pick.Ports, 5432);
  if (!port) {
    return {
      error: `Found Postgres container ${pick.Names} but no published 5432/tcp port. Ports: ${pick.Ports}`,
    };
  }
  if (pick === tcPg) {
    return {
      host: "127.0.0.1",
      port,
      user: "test",
      password: "test",
      database: "test",
      source: "testcontainer",
      containerName: pick.Names,
    };
  }
  return {
    host: "127.0.0.1",
    port,
    user: "peepbot",
    password: "peepbot",
    database: "peepbot",
    source: "compose",
    containerName: pick.Names,
  };
}

const WRITE_KEYWORDS = [
  "INSERT",
  "UPDATE",
  "DELETE",
  "TRUNCATE",
  "DROP",
  "ALTER",
  "CREATE",
  "GRANT",
  "REVOKE",
  "COMMENT",
  "VACUUM",
  "REINDEX",
];

function isWriteSql(sql: string): boolean {
  const upper = sql.toUpperCase().replace(/--.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
  for (const kw of WRITE_KEYWORDS) {
    if (new RegExp(`\\b${kw}\\b`).test(upper)) return true;
  }
  return false;
}

function fmtRows(rows: Record<string, unknown>[], maxRows: number): string {
  if (rows.length === 0) return "(0 rows)";
  const truncated = rows.slice(0, maxRows);
  const cols = Object.keys(truncated[0] ?? {});
  if (cols.length === 0) return `(${rows.length} rows, no columns)`;
  const widths = cols.map((c) =>
    Math.max(c.length, ...truncated.map((r) => String(r[c] ?? "").length)),
  );
  const sep = widths.map((w) => "-".repeat(Math.min(w, 32))).join("-+-");
  const header = cols.map((c, i) => c.padEnd(Math.min(widths[i], 32))).join(" | ");
  const lines = [header, sep];
  for (const r of truncated) {
    lines.push(
      cols
        .map((c, i) => {
          const v = r[c];
          const s = v === null || v === undefined ? "" : String(v);
          return s.length > 32 ? s.slice(0, 29) + "..." : s.padEnd(Math.min(widths[i], 32));
        })
        .join(" | "),
    );
  }
  if (rows.length > maxRows) {
    lines.push(`... (${rows.length - maxRows} more rows)`);
  }
  lines.push(`(${rows.length} rows)`);
  return lines.join("\n");
}

export async function dbQuery(opts: {
  sql: string;
  database?: string;
  allowWrite?: boolean;
  maxRows?: number;
}): Promise<string> {
  const target = await discoverTarget();
  if ("error" in target) return target.error;
  if (!opts.allowWrite && isWriteSql(opts.sql)) {
    return "Refused: SQL contains a write keyword (INSERT/UPDATE/DELETE/etc). Pass allowWrite=true to override.";
  }
  const db = opts.database ?? target.database;
  const client = new Client({
    host: target.host,
    port: target.port,
    user: target.user,
    password: target.password,
    database: db,
    statement_timeout: 10_000,
    query_timeout: 10_000,
  });
  await client.connect();
  try {
    let resultRows: Record<string, unknown>[] = [];
    let rowCount = 0;
    if (opts.allowWrite) {
      const r = await client.query(opts.sql);
      resultRows = (r.rows as Record<string, unknown>[]) ?? [];
      rowCount = r.rowCount ?? 0;
    } else {
      await client.query("BEGIN READ ONLY");
      try {
        const r = await client.query(opts.sql);
        resultRows = (r.rows as Record<string, unknown>[]) ?? [];
        rowCount = r.rowCount ?? 0;
      } finally {
        await client.query("ROLLBACK");
      }
    }
    const header = `connected: ${target.source}${target.containerName ? ` (${target.containerName})` : ""} -> ${target.host}:${target.port}/${db}`;
    return `${header}\n\n${fmtRows(resultRows, opts.maxRows ?? 100)}${
      rowCount && resultRows.length === 0 ? `\n(${rowCount} rows affected)` : ""
    }`;
  } finally {
    await client.end().catch(() => {});
  }
}

export async function dbInfo(): Promise<string> {
  const target = await discoverTarget();
  if ("error" in target) return target.error;
  const lines = [
    `source: ${target.source}`,
    `container: ${target.containerName ?? "(n/a)"}`,
    `host: ${target.host}`,
    `port: ${target.port}`,
    `user: ${target.user}`,
    `default database: ${target.database}`,
  ];
  return lines.join("\n");
}
