import { createHash } from "node:crypto";
import { statSync } from "node:fs";

interface Entry<T> {
  value: T;
  // mtime ms of files that gate this entry; if any has changed, evict.
  mtimes: Map<string, number>;
  expiresAt: number;
}

export interface CacheOptions {
  /** Files (absolute paths) whose mtime should invalidate this entry. */
  watch?: string[];
  /** TTL ms. Default 5 minutes. */
  ttlMs?: number;
}

const DEFAULT_TTL_MS = 5 * 60 * 1000;
const MAX_ENTRIES = 256;

export class Cache {
  private store = new Map<string, Entry<unknown>>();

  private safeMtime(path: string): number {
    try {
      return statSync(path).mtimeMs;
    } catch {
      return -1;
    }
  }

  private valid(entry: Entry<unknown>): boolean {
    if (Date.now() > entry.expiresAt) return false;
    for (const [path, recorded] of entry.mtimes) {
      if (this.safeMtime(path) !== recorded) return false;
    }
    return true;
  }

  async wrap<T>(
    keyParts: unknown[],
    opts: CacheOptions,
    compute: () => Promise<T>,
  ): Promise<T> {
    const key = createHash("sha1")
      .update(JSON.stringify(keyParts))
      .digest("hex");
    const existing = this.store.get(key) as Entry<T> | undefined;
    if (existing && this.valid(existing)) {
      // Refresh LRU position.
      this.store.delete(key);
      this.store.set(key, existing);
      return existing.value;
    }
    const value = await compute();
    const mtimes = new Map<string, number>();
    for (const f of opts.watch ?? []) mtimes.set(f, this.safeMtime(f));
    const entry: Entry<T> = {
      value,
      mtimes,
      expiresAt: Date.now() + (opts.ttlMs ?? DEFAULT_TTL_MS),
    };
    this.store.set(key, entry);
    while (this.store.size > MAX_ENTRIES) {
      const oldest = this.store.keys().next().value;
      if (oldest === undefined) break;
      this.store.delete(oldest);
    }
    return value;
  }

  clear(): void {
    this.store.clear();
  }
}

export const cache = new Cache();
