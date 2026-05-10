#!/usr/bin/env node
// Minimal MCP stdio smoke test.
import { spawn } from "node:child_process";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const SERVER = resolve(__dirname, "..", "dist", "index.js");

const child = spawn("node", [SERVER], { stdio: ["pipe", "pipe", "inherit"] });

let buffer = "";
const pending = new Map();
let nextId = 1;

child.stdout.on("data", (chunk) => {
  buffer += chunk.toString();
  let nl;
  while ((nl = buffer.indexOf("\n")) !== -1) {
    const line = buffer.slice(0, nl).trim();
    buffer = buffer.slice(nl + 1);
    if (!line) continue;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      continue;
    }
    if (msg.id != null && pending.has(msg.id)) {
      const { resolve: r } = pending.get(msg.id);
      pending.delete(msg.id);
      r(msg);
    }
  }
});

function send(method, params) {
  const id = nextId++;
  const msg = { jsonrpc: "2.0", id, method, params };
  return new Promise((resolvePromise) => {
    pending.set(id, { resolve: resolvePromise });
    child.stdin.write(JSON.stringify(msg) + "\n");
  });
}

function notify(method, params) {
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method, params }) + "\n");
}

function head(text, n = 10) {
  return (text ?? "").split("\n").slice(0, n).join("\n");
}

async function call(name, args) {
  const r = await send("tools/call", { name, arguments: args });
  return r.result?.content?.[0]?.text ?? "(empty)";
}

async function main() {
  await send("initialize", {
    protocolVersion: "2024-11-05",
    capabilities: {},
    clientInfo: { name: "smoke", version: "0" },
  });
  notify("notifications/initialized", {});

  const tools = await send("tools/list", {});
  console.log("tools:");
  for (const t of tools.result.tools) {
    console.log(`  - ${t.name}`);
  }

  const resources = await send("resources/list", {});
  console.log("\nresources:");
  for (const r of resources.result?.resources ?? []) {
    console.log(`  - ${r.uri} (${r.name})`);
  }

  console.log("\n--- find_symbol(GalleryController, class) ---");
  console.log(head(await call("find_symbol", { name: "GalleryController", kind: "class" }), 4));

  console.log("\n--- outline_directory(backend/src/main/java/dev/tylercash/event/discord) ---");
  console.log(
    head(
      await call("outline_directory", {
        dir: "backend/src/main/java/dev/tylercash/event/discord",
      }),
      8,
    ),
  );

  console.log("\n--- list_endpoints(both) ---");
  console.log(head(await call("list_endpoints", { side: "both" }), 25));

  console.log("\n--- db_schema ---");
  console.log(head(await call("db_schema", {}), 25));

  console.log("\n--- repo_overview ---");
  console.log(head(await call("repo_overview", {}), 12));

  console.log("\n--- caching: second find_symbol call (should be served from cache) ---");
  const t0 = Date.now();
  await call("find_symbol", { name: "GalleryController", kind: "class" });
  console.log(`elapsed: ${Date.now() - t0}ms`);

  console.log("\n--- resource read: peep://CLAUDE.md ---");
  const r1 = await send("resources/read", { uri: "peep://CLAUDE.md" });
  console.log(head(r1.result?.contents?.[0]?.text ?? "", 4));

  child.kill();
}

main().catch((e) => {
  console.error(e);
  child.kill();
  process.exit(1);
});
