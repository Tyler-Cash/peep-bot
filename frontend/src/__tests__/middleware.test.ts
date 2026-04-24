import { describe, it, expect, vi, beforeEach } from "vitest";

const mockRedirect = vi.fn((url: URL) => ({ type: "redirect", url }));
const mockNext = vi.fn(() => ({ type: "next" }));

vi.mock("next/server", () => ({
  NextResponse: { redirect: mockRedirect, next: mockNext },
}));

beforeEach(() => { mockRedirect.mockClear(); mockNext.mockClear(); });

function makeReq(pathname: string, cookies: string[] = []) {
  return {
    nextUrl: {
      pathname,
      clone: () => ({ pathname }),
    },
    cookies: { has: (name: string) => cookies.includes(name) },
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any;
}

describe("middleware (live mode)", () => {
  beforeEach(() => { process.env.NEXT_PUBLIC_API_MODE = "live"; });

  it("redirects unauthenticated user on protected route to /login", async () => {
    const { middleware } = await import("@/middleware");
    middleware(makeReq("/"));
    expect(mockRedirect).toHaveBeenCalledOnce();
    expect(mockRedirect.mock.calls[0][0].pathname).toBe("/login");
  });

  it("allows unauthenticated user through /login", async () => {
    const { middleware } = await import("@/middleware");
    middleware(makeReq("/login"));
    expect(mockNext).toHaveBeenCalledOnce();
  });

  it("redirects authenticated user on /login to /", async () => {
    const { middleware } = await import("@/middleware");
    middleware(makeReq("/login", ["SESSION"]));
    expect(mockRedirect).toHaveBeenCalledOnce();
    expect(mockRedirect.mock.calls[0][0].pathname).toBe("/");
  });

  it("allows authenticated user through protected route", async () => {
    const { middleware } = await import("@/middleware");
    middleware(makeReq("/", ["SESSION"]));
    expect(mockNext).toHaveBeenCalledOnce();
  });
});
