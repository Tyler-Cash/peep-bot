import type { Cache } from "swr";

const KEY = "peepbot.swr.v1";

type Persisted = Record<string, unknown>;

export function localStorageProvider(): Cache {
  const map = new Map<string, unknown>();
  if (typeof window !== "undefined") {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) {
        const data = JSON.parse(raw) as Persisted;
        for (const k of Object.keys(data)) map.set(k, data[k]);
      }
    } catch {
      /* ignore corrupt cache */
    }
    const flush = () => {
      try {
        const obj: Persisted = {};
        map.forEach((v, k) => {
          if (!k.startsWith("$swr$")) obj[k] = v;
        });
        localStorage.setItem(KEY, JSON.stringify(obj));
      } catch {
        /* over quota — ignore */
      }
    };
    window.addEventListener("beforeunload", flush);
    window.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "hidden") flush();
    });
  }
  return map as unknown as Cache;
}
