// Circuit breaker for the /login ↔ / redirect loop.
//
// Symptom we're guarding against: the SWR cache (or any other client-side
// state) believes the user is logged in while the backend session is gone.
// `/` triggers a 401 → redirect to /login → /login sees cached user → bounces
// back to /. Without a counter this spins forever.
//
// We count redirects per tab in sessionStorage, with a sliding window so a
// genuine re-auth later doesn't inherit old failures.
const COUNT_KEY = "peepbot.authLoopCount";
const TS_KEY = "peepbot.authLoopTs";
const WINDOW_MS = 30_000;
const MAX_ATTEMPTS = 3;

function read(): number {
  if (typeof window === "undefined") return 0;
  const ts = Number(window.sessionStorage.getItem(TS_KEY) ?? "0");
  if (ts && Date.now() - ts > WINDOW_MS) {
    window.sessionStorage.removeItem(COUNT_KEY);
    window.sessionStorage.removeItem(TS_KEY);
    return 0;
  }
  return Number(window.sessionStorage.getItem(COUNT_KEY) ?? "0");
}

export function noteAuthFailure(): number {
  if (typeof window === "undefined") return 0;
  const next = read() + 1;
  window.sessionStorage.setItem(COUNT_KEY, String(next));
  window.sessionStorage.setItem(TS_KEY, String(Date.now()));
  return next;
}

export function noteAuthSuccess(): void {
  if (typeof window === "undefined") return;
  window.sessionStorage.removeItem(COUNT_KEY);
  window.sessionStorage.removeItem(TS_KEY);
}

export function isAuthLoopTripped(): boolean {
  return read() >= MAX_ATTEMPTS;
}
