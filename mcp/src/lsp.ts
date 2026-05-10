import { spawn, ChildProcess } from "node:child_process";
import { resolve, dirname } from "node:path";
import { pathToFileURL, fileURLToPath } from "node:url";
import { readFileSync, existsSync, readdirSync, mkdirSync } from "node:fs";
import { platform, arch } from "node:os";
import {
  StreamMessageReader,
  StreamMessageWriter,
  createMessageConnection,
  MessageConnection,
} from "vscode-jsonrpc/node.js";
import type {
  Location,
  LocationLink,
  Hover,
  MarkupContent,
} from "vscode-languageserver-protocol";
import { REPO_ROOT } from "./core.js";

const __dirname = dirname(fileURLToPath(import.meta.url));

export type LspLang = "ts" | "tsx" | "js" | "jsx" | "java";

const LANG_ID: Record<LspLang, string> = {
  ts: "typescript",
  tsx: "typescriptreact",
  js: "javascript",
  jsx: "javascriptreact",
  java: "java",
};

function detectLspLang(absPath: string): LspLang | null {
  const ext = absPath.split(".").pop()?.toLowerCase() ?? "";
  if (ext === "ts" || ext === "tsx" || ext === "js" || ext === "jsx") {
    return ext as LspLang;
  }
  if (ext === "java") return "java";
  if (ext === "mjs" || ext === "cjs") return "js";
  return null;
}

interface LspPosition {
  line: number;
  character: number;
}

interface LspClientOptions {
  command: string;
  args: string[];
  rootUri: string;
  /** Hard cap on how long to wait for project-load before assuming ready. */
  readyTimeoutMs: number;
  /** Inspect each notification; return true when project load is complete. */
  isReadyNotification?: (method: string, params: unknown) => boolean;
  /** Extra initializationOptions to send to the server. */
  initializationOptions?: unknown;
  /** Extra capabilities to merge into the initialize request. */
  extraCapabilities?: Record<string, unknown>;
}

class LspClient {
  private child: ChildProcess;
  private connection: MessageConnection;
  private opened = new Set<string>();
  private initPromise: Promise<void>;
  private shutDown = false;
  private projectLoaded = false;
  private projectLoadedPromise: Promise<void>;
  private resolveProjectLoaded!: () => void;

  constructor(private opts: LspClientOptions) {
    this.child = spawn(opts.command, opts.args, { cwd: REPO_ROOT });
    this.child.stderr?.on("data", () => {});
    const reader = new StreamMessageReader(this.child.stdout!);
    const writer = new StreamMessageWriter(this.child.stdin!);
    this.connection = createMessageConnection(reader, writer);
    this.projectLoadedPromise = new Promise((res) => {
      this.resolveProjectLoaded = res;
    });
    const markReady = () => {
      if (!this.projectLoaded) {
        this.projectLoaded = true;
        this.resolveProjectLoaded();
      }
    };
    // tsserver default: $/progress end signals project load.
    this.connection.onNotification("$/progress", (params: any) => {
      if (params?.value?.kind === "end") markReady();
      if (opts.isReadyNotification?.("$/progress", params)) markReady();
    });
    this.connection.onUnhandledNotification((n) => {
      if (opts.isReadyNotification?.(n.method, n.params)) markReady();
    });
    this.connection.onRequest(() => null);
    this.connection.listen();
    this.initPromise = this.initialize();
    setTimeout(markReady, opts.readyTimeoutMs).unref();
  }

  private async initialize(): Promise<void> {
    await this.connection.sendRequest("initialize", {
      processId: process.pid,
      rootUri: this.opts.rootUri,
      initializationOptions: this.opts.initializationOptions,
      capabilities: {
        textDocument: {
          synchronization: { dynamicRegistration: false, didSave: false },
          references: { dynamicRegistration: false },
          definition: { dynamicRegistration: false, linkSupport: false },
          hover: {
            dynamicRegistration: false,
            contentFormat: ["markdown", "plaintext"],
          },
        },
        workspace: { workspaceFolders: true },
        window: { workDoneProgress: true },
        ...(this.opts.extraCapabilities ?? {}),
      },
      workspaceFolders: [{ uri: this.opts.rootUri, name: "root" }],
    });
    this.connection.sendNotification("initialized", {});
  }

  async ensureOpen(absPath: string): Promise<string> {
    await this.initPromise;
    const uri = pathToFileURL(absPath).toString();
    if (!this.opened.has(uri)) {
      const lang = detectLspLang(absPath);
      if (!lang) {
        throw new Error(`Unsupported file extension for LSP: ${absPath}`);
      }
      const text = readFileSync(absPath, "utf8");
      this.connection.sendNotification("textDocument/didOpen", {
        textDocument: {
          uri,
          languageId: LANG_ID[lang],
          version: 1,
          text,
        },
      });
      this.opened.add(uri);
    }
    return uri;
  }

  async references(uri: string, pos: LspPosition): Promise<Location[]> {
    await this.initPromise;
    await this.projectLoadedPromise;
    const result = await this.connection.sendRequest<Location[] | null>(
      "textDocument/references",
      {
        textDocument: { uri },
        position: pos,
        context: { includeDeclaration: true },
      },
    );
    return result ?? [];
  }

  async definition(
    uri: string,
    pos: LspPosition,
  ): Promise<Location[] | LocationLink[]> {
    await this.initPromise;
    await this.projectLoadedPromise;
    const result = await this.connection.sendRequest<
      Location | Location[] | LocationLink[] | null
    >("textDocument/definition", {
      textDocument: { uri },
      position: pos,
    });
    if (!result) return [];
    return Array.isArray(result) ? result : [result];
  }

  async hover(uri: string, pos: LspPosition): Promise<Hover | null> {
    await this.initPromise;
    await this.projectLoadedPromise;
    return await this.connection.sendRequest<Hover | null>(
      "textDocument/hover",
      { textDocument: { uri }, position: pos },
    );
  }

  async shutdown(): Promise<void> {
    if (this.shutDown) return;
    this.shutDown = true;
    try {
      await Promise.race([
        this.connection.sendRequest("shutdown", null),
        new Promise((r) => setTimeout(r, 500)),
      ]);
      this.connection.sendNotification("exit");
    } catch {
      /* ignore */
    }
    this.child.kill();
  }
}

// ---------- TypeScript client ----------

let tsClient: LspClient | null = null;

export async function getTsClient(): Promise<LspClient> {
  if (!tsClient) {
    const bin = resolve(
      __dirname,
      "..",
      "node_modules",
      ".bin",
      "typescript-language-server",
    );
    if (!existsSync(bin)) {
      throw new Error(`typescript-language-server not found at ${bin}`);
    }
    tsClient = new LspClient({
      command: bin,
      args: ["--stdio"],
      rootUri: pathToFileURL(REPO_ROOT).toString(),
      readyTimeoutMs: 8000,
    });
    registerCleanup(() => tsClient);
  }
  return tsClient;
}

// ---------- Java (jdtls) client ----------

const JDTLS_HOME = resolve(__dirname, "..", ".cache", "jdtls");
const JDTLS_WORKSPACE = resolve(__dirname, "..", ".cache", "jdtls-workspace");

function findLauncherJar(): string | null {
  const pluginsDir = resolve(JDTLS_HOME, "plugins");
  if (!existsSync(pluginsDir)) return null;
  const jar = readdirSync(pluginsDir).find(
    (f) => f.startsWith("org.eclipse.equinox.launcher_") && f.endsWith(".jar"),
  );
  return jar ? resolve(pluginsDir, jar) : null;
}

function jdtlsConfigDir(): string {
  const p = platform();
  const a = arch();
  if (p === "darwin") return resolve(JDTLS_HOME, a === "arm64" ? "config_mac_arm" : "config_mac");
  if (p === "win32") return resolve(JDTLS_HOME, "config_win");
  return resolve(JDTLS_HOME, a === "arm64" ? "config_linux_arm" : "config_linux");
}

export function isJdtlsAvailable(): boolean {
  return findLauncherJar() !== null;
}

let javaClient: LspClient | null = null;

export async function getJavaClient(): Promise<LspClient> {
  if (javaClient) return javaClient;
  const launcher = findLauncherJar();
  if (!launcher) {
    throw new Error(
      `jdtls is not installed. Run mcp/scripts/install-jdtls.sh or set PEEP_BOT_DISABLE_JDTLS=1 to skip.`,
    );
  }
  const cfg = jdtlsConfigDir();
  if (!existsSync(JDTLS_WORKSPACE)) mkdirSync(JDTLS_WORKSPACE, { recursive: true });
  const javaCmd = process.env.JAVA_HOME
    ? resolve(process.env.JAVA_HOME, "bin", "java")
    : "java";
  const args = [
    "-Declipse.application=org.eclipse.jdt.ls.core.id1",
    "-Dosgi.bundles.defaultStartLevel=4",
    "-Declipse.product=org.eclipse.jdt.ls.core.product",
    "-Dlog.level=ERROR",
    "-Xms1g",
    "-Xmx2g",
    "--add-modules=ALL-SYSTEM",
    "--add-opens",
    "java.base/java.util=ALL-UNNAMED",
    "--add-opens",
    "java.base/java.lang=ALL-UNNAMED",
    "-jar",
    launcher,
    "-configuration",
    cfg,
    "-data",
    JDTLS_WORKSPACE,
  ];
  // Point jdtls at the actual Gradle project root (backend/), not the repo root —
  // otherwise it tries to import every nested project it finds (e.g. stale
  // .claude/worktrees/agent-*/backend/) as separate Java projects.
  const javaProjectRoot = process.env.PEEP_BOT_JAVA_ROOT
    ? resolve(process.env.PEEP_BOT_JAVA_ROOT)
    : resolve(REPO_ROOT, "backend");
  const javaProjectUri = pathToFileURL(javaProjectRoot).toString();
  javaClient = new LspClient({
    command: javaCmd,
    args,
    rootUri: javaProjectUri,
    readyTimeoutMs: 180_000,
    isReadyNotification: (method, params) => {
      if (method !== "language/status") return false;
      const p = params as { type?: string; message?: string } | undefined;
      return p?.type === "ServiceReady" || p?.type === "Started";
    },
    initializationOptions: {
      bundles: [],
      workspaceFolders: [javaProjectUri],
      settings: {
        java: {
          import: {
            gradle: { enabled: true },
            maven: { enabled: true },
            exclusions: ["**/.claude/**", "**/node_modules/**", "**/.git/**"],
          },
          configuration: { updateBuildConfiguration: "automatic" },
        },
      },
    },
    extraCapabilities: {},
  });
  registerCleanup(() => javaClient);
  return javaClient;
}

function registerCleanup(getter: () => LspClient | null) {
  const cleanup = () => {
    getter()?.shutdown().catch(() => {});
  };
  process.once("exit", cleanup);
  process.once("SIGINT", cleanup);
  process.once("SIGTERM", cleanup);
}

// ---------- helpers ----------

export function uriToRelative(uri: string): string {
  try {
    const abs = fileURLToPath(uri);
    if (abs.startsWith(REPO_ROOT + "/")) return abs.slice(REPO_ROOT.length + 1);
    return abs;
  } catch {
    return uri;
  }
}

export function isLspSupported(absPath: string): boolean {
  return detectLspLang(absPath) !== null;
}

export function langOf(absPath: string): LspLang | null {
  return detectLspLang(absPath);
}

export async function getClientForFile(absPath: string): Promise<LspClient> {
  const lang = detectLspLang(absPath);
  if (!lang) throw new Error(`Unsupported file extension for LSP: ${absPath}`);
  if (lang === "java") return getJavaClient();
  return getTsClient();
}

export function hoverToText(hover: Hover | null): string {
  if (!hover) return "";
  const c = hover.contents as
    | string
    | MarkupContent
    | (string | MarkupContent | { language: string; value: string })[];
  if (typeof c === "string") return c;
  if (Array.isArray(c)) {
    return c
      .map((part) => {
        if (typeof part === "string") return part;
        if ("value" in part) return part.value;
        return "";
      })
      .filter(Boolean)
      .join("\n\n");
  }
  if ("value" in c) return c.value;
  return "";
}
