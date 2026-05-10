#!/usr/bin/env node
// Smoke test for jdtls-backed MCP tools. Allow several minutes for cold start.
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

function head(text, n = 25) {
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
    clientInfo: { name: "smoke-jdtls", version: "0" },
  });
  notify("notifications/initialized", {});

  const FILE = "backend/src/main/java/dev/tylercash/event/event/EventService.java";

  console.log(`--- lsp_hover (path=${FILE} 32:13) ---`);
  const t0 = Date.now();
  // EventService.java line 35 (0-idx 34), col 14 (0-idx 13) is the 'E' in `class EventService`.
  // The wrapper expects 0-indexed. Decl is at line 35 col 14 in the file (1-indexed).
  const hover = await call("lsp_hover", { path: FILE, line: 34, character: 13, lang: "java" });
  console.log(`elapsed: ${Date.now() - t0}ms (cold: includes jdtls init)`);
  console.log(head(hover, 12));

  console.log("\n--- lsp_references(name='EventService', lang='java') ---");
  const t1 = Date.now();
  console.log(head(await call("lsp_references", { name: "EventService", lang: "java" }), 30));
  console.log(`elapsed: ${Date.now() - t1}ms`);

  console.log("\n--- lsp_definition(name='EventService', lang='java') ---");
  console.log(head(await call("lsp_definition", { name: "EventService", lang: "java" }), 5));

  console.log("\n--- lsp_references warm (second call) ---");
  const t2 = Date.now();
  await call("lsp_references", { name: "EventService", lang: "java" });
  console.log(`elapsed: ${Date.now() - t2}ms`);

  child.kill();
}

main().catch((e) => {
  console.error(e);
  child.kill();
  process.exit(1);
});
