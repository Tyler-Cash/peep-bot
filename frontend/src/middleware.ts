import { NextResponse, type NextRequest } from "next/server";

const PUBLIC_PATHS = ["/login"];

export function middleware(req: NextRequest) {
  const mode = process.env.NEXT_PUBLIC_API_MODE ?? "mock";
  if (mode === "mock") return NextResponse.next();

  const { pathname } = req.nextUrl;
  const hasSession = req.cookies.has("SESSION");

  // Already authenticated — bounce away from login page
  if (pathname.startsWith("/login") && hasSession) {
    const url = req.nextUrl.clone();
    url.pathname = "/";
    return NextResponse.redirect(url);
  }

  if (PUBLIC_PATHS.some((p) => pathname.startsWith(p))) return NextResponse.next();

  if (!hasSession) {
    const url = req.nextUrl.clone();
    url.pathname = "/login";
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/|favicon.ico|mockServiceWorker.js).*)"],
};
