#!/usr/bin/env node
// Smoke test for LSP-backed MCP tools.
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

function head(text, n = 12) {
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
    clientInfo: { name: "smoke-lsp", version: "0" },
  });
  notify("notifications/initialized", {});

  const tools = await send("tools/list", {});
  const lspTools = tools.result.tools
    .map((t) => t.name)
    .filter((n) => n.startsWith("lsp_"));
  console.log("LSP tools registered:");
  for (const n of lspTools) console.log(`  - ${n}`);

  console.log("\n--- lsp_references(name='ApiError', glob='frontend/src/**/*.ts') ---");
  const t0 = Date.now();
  const refs = await call("lsp_references", {
    name: "ApiError",
    glob: "frontend/src/**/*.ts",
  });
  console.log(`elapsed: ${Date.now() - t0}ms`);
  console.log(head(refs, 20));

  console.log("\n--- lsp_definition(name='ApiError', glob='frontend/src/**/*.ts') ---");
  console.log(head(await call("lsp_definition", { name: "ApiError", glob: "frontend/src/**/*.ts" }), 5));

  console.log("\n--- lsp_hover(name='ApiError', glob='frontend/src/**/*.ts') ---");
  console.log(head(await call("lsp_hover", { name: "ApiError", glob: "frontend/src/**/*.ts" }), 12));

  console.log("\n--- lsp_references warm cache (second call) ---");
  const t1 = Date.now();
  await call("lsp_references", { name: "ApiError", glob: "frontend/src/**/*.ts" });
  console.log(`elapsed: ${Date.now() - t1}ms`);

  child.kill();
}

main().catch((e) => {
  console.error(e);
  child.kill();
  process.exit(1);
});
