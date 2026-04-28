# Error Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all 5 error states from the design handoff — 404, 500, 403, offline, and generic — wired into Next.js App Router's error boundaries and route conventions, each with the correct themed Peepo mascot variant.

**Architecture:** A shared `ErrorPage` shell component renders the mascot badge, eyebrow, headline, body, action buttons, and support line. Five preconfigured variants wrap it. Mascots live in `components/peepo/`. Next.js App Router error hooks (`not-found.tsx`, `error.tsx`) import the correct variant. The offline state is a client-side provider that watches `navigator.onLine` and renders a full-page takeover.

**Tech Stack:** Next.js 15 App Router, Tailwind CSS (tokens already configured), `useRouter` from `next/navigation`, `useEffect` / `useState` for offline detection.

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `frontend/src/components/peepo/PeepoConfused.tsx` | Mascot SVG for 404 — wonky eyes, floating `?` |
| Create | `frontend/src/components/peepo/PeepoBandaged.tsx` | Mascot SVG for 500 — x-eyes, bandage on head |
| Create | `frontend/src/components/peepo/PeepoLocked.tsx` | Mascot SVG for 403 — padlock badge above head |
| Create | `frontend/src/components/peepo/PeepoOffline.tsx` | Mascot SVG for offline — cord, fading wifi dashes |
| Create | `frontend/src/components/errors/ErrorPage.tsx` | Shared shell: logo + mascot badge + code sticker + headline + body + actions |
| Create | `frontend/src/app/not-found.tsx` | Next.js 404 boundary → `Error404` |
| Create | `frontend/src/app/error.tsx` | Next.js 500 boundary (client component) → `Error500` |
| Create | `frontend/src/app/403/page.tsx` | Guild-access-denied page → `Error403` |
| Create | `frontend/src/components/errors/OfflineProvider.tsx` | Client provider watching `navigator.onLine` |
| Modify | `frontend/src/app/layout.tsx` | Wrap children in `OfflineProvider` |

---

### Task 1: Create the four new Peepo mascot SVG components

**Files:**
- Create: `frontend/src/components/peepo/PeepoConfused.tsx`
- Create: `frontend/src/components/peepo/PeepoBandaged.tsx`
- Create: `frontend/src/components/peepo/PeepoLocked.tsx`
- Create: `frontend/src/components/peepo/PeepoOffline.tsx`

- [ ] **Step 1: Create PeepoConfused.tsx**

  ```tsx
  // frontend/src/components/peepo/PeepoConfused.tsx
  type Props = { size?: number; hue?: string };

  export function PeepoConfused({ size = 140, hue = "#7BC24F" }: Props) {
    return (
      <svg width={size} height={size} viewBox="0 0 80 80" aria-hidden>
        <ellipse cx="40" cy="50" rx="30" ry="22" fill={hue} />
        <ellipse cx="40" cy="58" rx="16" ry="10" fill="#fff" opacity="0.18" />
        <circle cx="28" cy="36" r="9" fill={hue} />
        <circle cx="52" cy="36" r="9" fill={hue} />
        {/* wonky eyes — one wide (dot), one squinty (line) */}
        <circle cx="28" cy="36" r="3.5" fill="#0E100D" />
        <circle cx="27" cy="35" r="1.1" fill="#fff" />
        <path d="M47 36q5 0 10 0" stroke="#0E100D" strokeWidth="2" fill="none" strokeLinecap="round" />
        {/* crooked mouth */}
        <path d="M34 52q3 -1.5 6 0 q3 1.5 6 -0.5" stroke="#0E100D" strokeWidth="2" fill="none" strokeLinecap="round" />
        <circle cx="22" cy="46" r="2.3" fill="#F4A9B6" opacity="0.75" />
        <circle cx="58" cy="46" r="2.3" fill="#F4A9B6" opacity="0.75" />
        {/* floating question mark */}
        <text x="60" y="22" fontFamily='"Space Grotesk", sans-serif' fontSize="18" fontWeight="800" fill="#0E100D" opacity="0.85">?</text>
      </svg>
    );
  }
  ```

- [ ] **Step 2: Create PeepoBandaged.tsx**

  ```tsx
  // frontend/src/components/peepo/PeepoBandaged.tsx
  type Props = { size?: number; hue?: string };

  export function PeepoBandaged({ size = 140, hue = "#7BC24F" }: Props) {
    return (
      <svg width={size} height={size} viewBox="0 0 80 80" aria-hidden>
        <ellipse cx="40" cy="50" rx="30" ry="22" fill={hue} />
        <ellipse cx="40" cy="58" rx="16" ry="10" fill="#fff" opacity="0.18" />
        <circle cx="28" cy="36" r="9" fill={hue} />
        <circle cx="52" cy="36" r="9" fill={hue} />
        {/* x-eyes */}
        <path d="M24 33l8 7 M32 33l-8 7" stroke="#0E100D" strokeWidth="2" strokeLinecap="round" />
        <path d="M48 33l8 7 M56 33l-8 7" stroke="#0E100D" strokeWidth="2" strokeLinecap="round" />
        {/* flat mouth */}
        <path d="M34 53q6 2 12 0" stroke="#0E100D" strokeWidth="2" fill="none" strokeLinecap="round" />
        <circle cx="22" cy="46" r="2.3" fill="#F4A9B6" opacity="0.75" />
        <circle cx="58" cy="46" r="2.3" fill="#F4A9B6" opacity="0.75" />
        {/* bandage rotated on top of head */}
        <g transform="rotate(-18 40 22)">
          <rect x="30" y="18" width="20" height="8" rx="2" fill="#F5E0C0" stroke="#0E100D" strokeWidth="1.3" />
          <line x1="36" y1="18" x2="36" y2="26" stroke="#0E100D" strokeWidth="1" />
          <line x1="44" y1="18" x2="44" y2="26" stroke="#0E100D" strokeWidth="1" />
        </g>
      </svg>
    );
  }
  ```

- [ ] **Step 3: Create PeepoLocked.tsx**

  ```tsx
  // frontend/src/components/peepo/PeepoLocked.tsx
  type Props = { size?: number; hue?: string };

  export function PeepoLocked({ size = 140, hue = "#7BC24F" }: Props) {
    return (
      <svg width={size} height={size} viewBox="0 0 80 80" aria-hidden>
        <ellipse cx="40" cy="50" rx="30" ry="22" fill={hue} />
        <ellipse cx="40" cy="58" rx="16" ry="10" fill="#fff" opacity="0.18" />
        <circle cx="28" cy="36" r="9" fill={hue} />
        <circle cx="52" cy="36" r="9" fill={hue} />
        {/* closed eyes */}
        <path d="M23 36q5 3 10 0" stroke="#0E100D" strokeWidth="1.8" fill="none" strokeLinecap="round" />
        <path d="M47 36q5 3 10 0" stroke="#0E100D" strokeWidth="1.8" fill="none" strokeLinecap="round" />
        {/* flat concerned mouth */}
        <path d="M34 54 L46 54" stroke="#0E100D" strokeWidth="2" fill="none" strokeLinecap="round" />
        <circle cx="22" cy="46" r="2.3" fill="#F4A9B6" opacity="0.75" />
        <circle cx="58" cy="46" r="2.3" fill="#F4A9B6" opacity="0.75" />
        {/* padlock badge floating above head */}
        <g transform="translate(52 4)">
          <rect x="0" y="8" width="18" height="14" rx="3" fill="#fff" stroke="#0E100D" strokeWidth="1.5" />
          <path d="M4 8 v-3 a5 5 0 0 1 10 0 v3" fill="none" stroke="#0E100D" strokeWidth="1.5" />
          <circle cx="9" cy="14" r="1.7" fill="#0E100D" />
          <rect x="8.2" y="14" width="1.6" height="4" fill="#0E100D" />
        </g>
      </svg>
    );
  }
  ```

- [ ] **Step 4: Create PeepoOffline.tsx**

  ```tsx
  // frontend/src/components/peepo/PeepoOffline.tsx
  type Props = { size?: number; hue?: string };

  export function PeepoOffline({ size = 140, hue = "#7BC24F" }: Props) {
    return (
      <svg width={size} height={size} viewBox="0 0 90 80" aria-hidden>
        <ellipse cx="45" cy="50" rx="30" ry="22" fill={hue} />
        <ellipse cx="45" cy="58" rx="16" ry="10" fill="#fff" opacity="0.18" />
        <circle cx="33" cy="36" r="9" fill={hue} />
        <circle cx="57" cy="36" r="9" fill={hue} />
        {/* dot eyes looking down */}
        <circle cx="33" cy="38" r="2.2" fill="#0E100D" />
        <circle cx="57" cy="38" r="2.2" fill="#0E100D" />
        <path d="M39 54q6 -2 12 0" stroke="#0E100D" strokeWidth="2" fill="none" strokeLinecap="round" />
        <circle cx="27" cy="46" r="2.3" fill="#F4A9B6" opacity="0.75" />
        <circle cx="63" cy="46" r="2.3" fill="#F4A9B6" opacity="0.75" />
        {/* unplugged cord trailing right */}
        <path d="M72 30 q10 -5 14 5" stroke="#0E100D" strokeWidth="2" fill="none" strokeLinecap="round" />
        <rect x="84" y="33" width="5" height="7" rx="1" fill="#fff" stroke="#0E100D" strokeWidth="1.3" />
        {/* fading wifi dashes on left */}
        <path d="M12 20 q8 -8 18 0" stroke="#0E100D" strokeWidth="1.8" fill="none" strokeLinecap="round" strokeDasharray="2 3" opacity="0.5" />
        <path d="M16 26 q5 -5 10 0" stroke="#0E100D" strokeWidth="1.8" fill="none" strokeLinecap="round" strokeDasharray="2 3" opacity="0.3" />
      </svg>
    );
  }
  ```

- [ ] **Step 5: Commit**

  ```bash
  cd frontend && git add src/components/peepo/
  git commit -m "feat(errors): add four Peepo mascot SVG variants for error states"
  ```

---

### Task 2: Create the shared ErrorPage shell component

**Files:**
- Create: `frontend/src/components/errors/ErrorPage.tsx`

- [ ] **Step 1: Write the component**

  ```tsx
  // frontend/src/components/errors/ErrorPage.tsx
  import type { ReactNode } from "react";
  import Link from "next/link";
  import { Peepo } from "@/components/Peepo";

  type Props = {
    code: string;
    accent: string;
    mascot: ReactNode;
    title: ReactNode;
    body: ReactNode;
    onRetry?: (() => void) | null;
    retryLabel?: string;
    onBack?: (() => void) | null;
    backLabel?: string;
    showContact?: boolean;
  };

  export function ErrorPage({
    code,
    accent,
    mascot,
    title,
    body,
    onRetry,
    retryLabel = "try again",
    onBack,
    backLabel = "back to #outings",
    showContact = true,
  }: Props) {
    return (
      <div
        className="relative min-h-screen overflow-hidden bg-paper font-sans text-ink flex items-center justify-center px-8 py-10"
      >
        {/* soft radial wash */}
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(ellipse at 50% 0%, rgba(255,255,255,0.6), transparent 55%)",
          }}
        />

        {/* logo top-left */}
        <div className="absolute top-[22px] left-7 flex items-center gap-2.5 z-10">
          <Link href="/" className="flex items-center gap-2.5">
            <span className="inline-flex items-center justify-center w-9 h-9 rounded-[10px] bg-leaf border-[1.5px] border-ink shadow-chunky-sm">
              <Peepo size={26} />
            </span>
            <div className="flex flex-col leading-none">
              <span className="text-[16px] font-extrabold tracking-[-0.02em]">peepbot</span>
              <span className="mt-0.5 text-[9.5px] font-extrabold tracking-[0.18em] text-mute">
                PLANS, SORTED
              </span>
            </div>
          </Link>
        </div>

        {/* center card */}
        <div className="relative z-10 max-w-[540px] w-full text-center">
          {/* mascot badge */}
          <div
            className="relative mx-auto mb-7 w-[200px] h-[200px] rounded-full bg-paper2 border-2 border-ink shadow-chunky-lg flex items-center justify-center"
            style={{ transform: "rotate(-3deg)" }}
          >
            {mascot}
            {/* code sticker */}
            <div
              className="absolute text-[18px] font-extrabold tracking-[-0.03em] text-ink rounded-[12px] border-[1.5px] border-ink px-3.5 py-1.5 shadow-chunky"
              style={{
                bottom: -12,
                right: -18,
                background: accent,
                transform: "rotate(8deg)",
              }}
            >
              {code}
            </div>
          </div>

          {/* eyebrow */}
          <p className="mb-2.5 text-[11.5px] font-extrabold tracking-[0.22em] text-mute uppercase">
            error {code}
          </p>

          {/* headline */}
          <h1 className="mb-4 text-[54px] font-extrabold tracking-[-0.04em] leading-none lowercase">
            {title}
          </h1>

          {/* body */}
          <p className="mx-auto mb-8 max-w-[440px] text-[17px] text-ink2 font-medium leading-[1.5]">
            {body}
          </p>

          {/* action buttons */}
          <div className="flex items-center justify-center gap-2.5 flex-wrap mb-6">
            {onRetry && (
              <button
                onClick={onRetry}
                className="inline-flex items-center gap-2 rounded-[12px] border-[1.5px] border-ink bg-leaf text-ink px-[22px] py-[13px] text-[15px] font-extrabold tracking-[-0.01em] shadow-chunky-md active:shadow-chunky-active active:translate-x-[2px] active:translate-y-[2px] transition-[box-shadow,transform]"
              >
                <span aria-hidden className="text-[17px]">↻</span>
                {retryLabel}
              </button>
            )}
            {onBack && (
              <button
                onClick={onBack}
                className="inline-flex items-center gap-2 rounded-[12px] border-[1.5px] border-ink bg-white text-ink px-[22px] py-[13px] text-[15px] font-extrabold tracking-[-0.01em] shadow-chunky-md active:shadow-chunky-active active:translate-x-[2px] active:translate-y-[2px] transition-[box-shadow,transform]"
              >
                ← {backLabel}
              </button>
            )}
          </div>

          {/* support line */}
          {showContact && (
            <p className="text-[13px] text-mute font-semibold">
              still stuck? email{" "}
              <a
                href="mailto:support@tylercash.dev"
                className="text-leafDk font-extrabold underline decoration-2 underline-offset-[3px]"
              >
                support@tylercash.dev
              </a>
            </p>
          )}
        </div>
      </div>
    );
  }
  ```

- [ ] **Step 2: Commit**

  ```bash
  cd frontend && git add src/components/errors/ErrorPage.tsx
  git commit -m "feat(errors): add shared ErrorPage shell component"
  ```

---

### Task 3: Wire up 404 — `app/not-found.tsx`

**Files:**
- Create: `frontend/src/app/not-found.tsx`

- [ ] **Step 1: Create not-found.tsx**

  ```tsx
  // frontend/src/app/not-found.tsx
  "use client";

  import { useRouter } from "next/navigation";
  import { ErrorPage } from "@/components/errors/ErrorPage";
  import { PeepoConfused } from "@/components/peepo/PeepoConfused";

  export default function NotFound() {
    const router = useRouter();
    return (
      <ErrorPage
        code="404"
        accent="#FFF0A6"
        mascot={<PeepoConfused size={150} />}
        title="we can't find that one."
        body="maybe the event got cancelled, maybe the link's wonky. either way — it's not here."
        onBack={() => router.push("/")}
        backLabel="back to #outings"
        onRetry={null}
      />
    );
  }
  ```

- [ ] **Step 2: Visual check** — navigate to `http://localhost:3000/some-nonexistent-route` and verify the 404 error page renders with PeepoConfused, trivia-yellow sticker, and a "← back to #outings" button.

- [ ] **Step 3: Commit**

  ```bash
  cd frontend && git add src/app/not-found.tsx
  git commit -m "feat(errors): add 404 not-found page with PeepoConfused"
  ```

---

### Task 4: Wire up 500 — `app/error.tsx`

**Files:**
- Create: `frontend/src/app/error.tsx`

Note: Next.js `error.tsx` receives `error: Error` and `reset: () => void` props. It **must** be a client component.

- [ ] **Step 1: Create error.tsx**

  ```tsx
  // frontend/src/app/error.tsx
  "use client";

  import { ErrorPage } from "@/components/errors/ErrorPage";
  import { PeepoBandaged } from "@/components/peepo/PeepoBandaged";

  export default function GlobalError({
    reset,
  }: {
    error: Error & { digest?: string };
    reset: () => void;
  }) {
    return (
      <ErrorPage
        code="500"
        accent="#FFB8D9"
        mascot={<PeepoBandaged size={150} />}
        title="peepo dropped the ball."
        body="something broke on our end. we're looking at it. give it another go in a sec."
        onRetry={reset}
        retryLabel="try again"
        onBack={null}
      />
    );
  }
  ```

- [ ] **Step 2: Commit**

  ```bash
  cd frontend && git add src/app/error.tsx
  git commit -m "feat(errors): add 500 error boundary with PeepoBandaged"
  ```

---

### Task 5: Wire up 403 — `app/403/page.tsx`

**Files:**
- Create: `frontend/src/app/403/page.tsx`

This page is rendered by server components when `session.user` is not a member of the target guild (the backend returns HTTP 403). It can also be navigated to directly from API error handling.

- [ ] **Step 1: Create the 403 page**

  ```tsx
  // frontend/src/app/403/page.tsx
  "use client";

  import { useRouter } from "next/navigation";
  import { ErrorPage } from "@/components/errors/ErrorPage";
  import { PeepoLocked } from "@/components/peepo/PeepoLocked";

  export default function ForbiddenPage() {
    const router = useRouter();
    return (
      <ErrorPage
        code="403"
        accent="#FFD89B"
        mascot={<PeepoLocked size={150} />}
        title="this one's locked."
        body="you're not in the server this event lives in. ask your mate to invite you, or switch to a server you're already in."
        onBack={() => router.push("/")}
        backLabel="switch server"
        onRetry={null}
      />
    );
  }
  ```

- [ ] **Step 2: Wire 403 responses from api.ts** — open `frontend/src/lib/api.ts` and add a 403 redirect after the 401 check:

  ```ts
  if (res.status === 403) {
    if (typeof window !== "undefined") {
      window.location.href = "/403";
    }
    throw new Error("forbidden");
  }
  ```

  The full updated block in `api.ts` (replace the existing 401 block and add the 403 block):

  ```ts
  if (res.status === 401) {
    if (typeof window !== "undefined" && MODE === "live") {
      window.location.href = `${API_BASE.replace(/\/api$/, "")}/api/oauth2/authorization/discord`;
    }
    throw new Error("unauthorized");
  }
  if (res.status === 403) {
    if (typeof window !== "undefined") {
      window.location.href = "/403";
    }
    throw new Error("forbidden");
  }
  ```

- [ ] **Step 3: Visual check** — navigate to `http://localhost:3000/403` and verify the page renders with PeepoLocked, amber sticker, and "← switch server" button.

- [ ] **Step 4: Commit**

  ```bash
  cd frontend && git add src/app/403/page.tsx src/lib/api.ts
  git commit -m "feat(errors): add 403 forbidden page and wire api.ts 403 response"
  ```

---

### Task 6: Offline detection — `OfflineProvider` + layout wiring

**Files:**
- Create: `frontend/src/components/errors/OfflineProvider.tsx`
- Modify: `frontend/src/app/layout.tsx`

- [ ] **Step 1: Create OfflineProvider.tsx**

  ```tsx
  // frontend/src/components/errors/OfflineProvider.tsx
  "use client";

  import { useEffect, useState, type ReactNode } from "react";
  import { useRouter } from "next/navigation";
  import { ErrorPage } from "@/components/errors/ErrorPage";
  import { PeepoOffline } from "@/components/peepo/PeepoOffline";

  export function OfflineProvider({ children }: { children: ReactNode }) {
    const [offline, setOffline] = useState(false);
    const router = useRouter();

    useEffect(() => {
      const goOffline = () => setOffline(true);
      const goOnline = () => setOffline(false);

      // Check initial state (SSR may not have window.navigator)
      if (typeof navigator !== "undefined") {
        setOffline(!navigator.onLine);
      }

      window.addEventListener("offline", goOffline);
      window.addEventListener("online", goOnline);
      return () => {
        window.removeEventListener("offline", goOffline);
        window.removeEventListener("online", goOnline);
      };
    }, []);

    if (offline) {
      return (
        <ErrorPage
          code="offline"
          accent="#A5D8E0"
          mascot={<PeepoOffline size={150} />}
          title="can't reach Discord."
          body="your internet looks spotty, or Discord's having a moment. once you're back online, we'll catch up automatically."
          onRetry={() => {
            if (navigator.onLine) setOffline(false);
            else router.refresh();
          }}
          retryLabel="reconnect"
          onBack={null}
          showContact={false}
        />
      );
    }

    return <>{children}</>;
  }
  ```

- [ ] **Step 2: Add OfflineProvider to layout.tsx**

  The current `layout.tsx`:
  ```tsx
  import type { Metadata } from "next";
  import { Space_Grotesk } from "next/font/google";
  import { Providers } from "@/components/Providers";
  import "./globals.css";
  ```

  Replace with:
  ```tsx
  import type { Metadata } from "next";
  import { Space_Grotesk } from "next/font/google";
  import { Providers } from "@/components/Providers";
  import { OfflineProvider } from "@/components/errors/OfflineProvider";
  import "./globals.css";

  const spaceGrotesk = Space_Grotesk({
    subsets: ["latin"],
    weight: ["400", "500", "600", "700"],
    variable: "--font-space-grotesk",
  });

  export const metadata: Metadata = {
    title: "peepbot — plans, sorted",
    description: "a discord bot for irl friendships",
  };

  export default function RootLayout({ children }: { children: React.ReactNode }) {
    return (
      <html lang="en" className={spaceGrotesk.variable}>
        <body>
          <Providers>
            <OfflineProvider>{children}</OfflineProvider>
          </Providers>
        </body>
      </html>
    );
  }
  ```

- [ ] **Step 3: Visual check** — open DevTools → Network → check "Offline" checkbox. The page should switch to the full-page `ErrorOffline` view with PeepoOffline, outdoor-blue sticker, and "reconnect" button. Uncheck "Offline" — page should automatically restore.

- [ ] **Step 4: Commit**

  ```bash
  cd frontend && git add src/components/errors/OfflineProvider.tsx src/app/layout.tsx
  git commit -m "feat(errors): add offline detection provider with PeepoOffline takeover"
  ```

---

### Task 7: Generic fallback — nested error boundaries

**Files:**
- Create: `frontend/src/app/events/error.tsx` (events subtree error boundary)

The generic fallback (`peepo's a bit lost.`) is used for unhandled errors inside deeply-nested client components that don't fit the root error.tsx. Placing an `error.tsx` in the `events/` segment catches errors thrown by event detail/feed components without taking over the whole app.

- [ ] **Step 1: Create events/error.tsx**

  ```tsx
  // frontend/src/app/events/error.tsx
  "use client";

  import { ErrorPage } from "@/components/errors/ErrorPage";
  import { PeepoSleep } from "@/components/Peepo";

  export default function EventsError({
    reset,
  }: {
    error: Error & { digest?: string };
    reset: () => void;
  }) {
    return (
      <ErrorPage
        code="oops"
        accent="#7BC24F"
        mascot={<PeepoSleep size={150} />}
        title="peepo's a bit lost."
        body="something didn't go to plan. not sure what. try again, or head back to the feed."
        onRetry={reset}
        retryLabel="try again"
        onBack={() => (window.location.href = "/")}
        backLabel="back to #outings"
      />
    );
  }
  ```

- [ ] **Step 2: Commit**

  ```bash
  cd frontend && git add src/app/events/error.tsx
  git commit -m "feat(errors): add generic error boundary for events subtree"
  ```

---

### Task 8: Lint, type-check, and final visual review

- [ ] **Step 1: Run lint and format check**

  ```bash
  cd frontend && export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && npm run lint && npm run format:check
  ```

  Expected: no errors. If lint errors, run `npm run lint:fix && npm run format`.

- [ ] **Step 2: Type check**

  ```bash
  cd frontend && export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && npm run typecheck
  ```

  Expected: no TypeScript errors.

- [ ] **Step 3: Run tests**

  ```bash
  cd frontend && export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && npm test
  ```

  Expected: all tests pass.

- [ ] **Step 4: Manual route verification** — with `npm run dev` running:
  - `http://localhost:3000/nonexistent` → 404 with PeepoConfused ✓
  - `http://localhost:3000/403` → 403 with PeepoLocked ✓
  - DevTools → Network → Offline → full-page offline takeover ✓
  - DevTools → Network → re-enable → page restores ✓

- [ ] **Step 5: Final commit**

  ```bash
  cd frontend && git add -p  # stage any remaining changes
  git commit -m "feat(errors): complete error pages — 404, 500, 403, offline, generic"
  ```
