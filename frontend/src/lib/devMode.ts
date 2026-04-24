const KEY = "peepbot-devmode";

export function isDevModeActive(): boolean {
  if (typeof window === "undefined") return false;
  return window.sessionStorage.getItem(KEY) === "1";
}

export function activateDevMode(): void {
  window.sessionStorage.setItem(KEY, "1");
}

export function deactivateDevMode(): void {
  window.sessionStorage.removeItem(KEY);
}
