// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "../setup/render";
import { LoginHero, prefersFullPageLogin } from "@/components/login/LoginHero";

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/login",
}));

vi.mock("@/lib/hooks", () => ({
  useCurrentUser: () => ({ data: undefined }),
}));

vi.mock("@/lib/authLoopGuard", () => ({
  isAuthLoopTripped: () => false,
  noteAuthSuccess: vi.fn(),
}));

const isBackendReachable = vi.fn<() => Promise<boolean>>();
vi.mock("@/lib/api", () => ({
  isBackendReachable: () => isBackendReachable(),
}));

const OAUTH_URL_RE = /\/api\/oauth2\/authorization\/discord$/;

function setPointer(coarse: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: coarse && query.includes("coarse"),
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
    onchange: null,
  }));
}

async function clickContinue() {
  const button = await screen.findByText(/continue with Discord/i);
  fireEvent.click(button);
  // let the async onContinue handler settle
  await Promise.resolve();
  await Promise.resolve();
}

describe("prefersFullPageLogin", () => {
  it("is true for a coarse (touch) pointer", () => {
    const win = { matchMedia: (q: string) => ({ matches: q.includes("coarse") }) } as unknown as Window;
    expect(prefersFullPageLogin(win)).toBe(true);
  });

  it("is false for a fine (mouse) pointer", () => {
    const win = { matchMedia: () => ({ matches: false }) } as unknown as Window;
    expect(prefersFullPageLogin(win)).toBe(false);
  });
});

describe("LoginHero — one authorization request per click", () => {
  let assignSpy: ReturnType<typeof vi.fn>;
  let openSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    process.env.NEXT_PUBLIC_API_MODE = "live";
    isBackendReachable.mockResolvedValue(true);
    assignSpy = vi.fn();
    // jsdom's location.assign throws "Not implemented"; replace it with a spy.
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...window.location, assign: assignSpy, origin: "https://event.tylercash.dev" },
    });
    openSpy = vi.fn();
    window.open = openSpy as unknown as typeof window.open;
  });

  afterEach(() => {
    cleanup();
    delete process.env.NEXT_PUBLIC_API_MODE;
    vi.clearAllMocks();
  });

  it("mobile (coarse pointer): full-page redirect, never opens a popup", async () => {
    setPointer(true);
    renderWithProviders(<LoginHero />);

    await clickContinue();

    expect(openSpy).not.toHaveBeenCalled();
    expect(assignSpy).toHaveBeenCalledTimes(1);
    expect(assignSpy.mock.calls[0][0]).toMatch(OAUTH_URL_RE);
  });

  it("desktop (fine pointer): opens the popup once and navigates it, no page redirect", async () => {
    setPointer(false);
    const popup = { location: { replace: vi.fn(), origin: "about:blank" }, closed: false, close: vi.fn() };
    openSpy.mockReturnValue(popup);
    renderWithProviders(<LoginHero />);

    await clickContinue();

    expect(openSpy).toHaveBeenCalledTimes(1);
    // The popup is opened blank (synchronously, preserving the gesture) then navigated.
    expect(popup.location.replace).toHaveBeenCalledTimes(1);
    expect(popup.location.replace.mock.calls[0][0]).toMatch(OAUTH_URL_RE);
    expect(assignSpy).not.toHaveBeenCalled();
  });

  it("desktop with backend down: closes popup and shows the error modal, no navigation", async () => {
    setPointer(false);
    isBackendReachable.mockResolvedValue(false);
    const popup = { location: { replace: vi.fn(), origin: "about:blank" }, closed: false, close: vi.fn() };
    openSpy.mockReturnValue(popup);
    renderWithProviders(<LoginHero />);

    await clickContinue();

    expect(popup.location.replace).not.toHaveBeenCalled();
    expect(popup.close).toHaveBeenCalledTimes(1);
    expect(assignSpy).not.toHaveBeenCalled();
    await screen.findByText(/peepbot is dead/i);
  });
});
