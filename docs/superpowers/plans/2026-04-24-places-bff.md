# Places BFF Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Google Maps API key server-side by adding two Next.js Route Handlers that proxy Google Places Autocomplete and Details, protected by Upstash rate limiting.

**Architecture:** Two GET handlers under `src/app/api/places/` — `autocomplete` (rate-limited, returns `PlaceSuggestion[]`) and `details` (session closer, always returns 204). `places.ts` is updated to call the BFF instead of Google directly, tracking a `blockedUntil` timestamp for client-side backoff. `LocationAutocomplete` gains three rate-limit tiers: ≤10s (spinner), 10–60s (inline warning), >60s (disable + clear).

**Tech Stack:** Next.js 15 App Router Route Handlers, `@upstash/ratelimit` v2, `@upstash/redis`, Vitest

**Spec:** `docs/superpowers/specs/2026-04-24-places-bff-design.md`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/lib/rateLimiter.ts` | Create | Upstash 300ms sliding window + 50/hr fixed window, keyed by SESSION cookie |
| `src/app/api/places/autocomplete/route.ts` | Create | Auth check, rate limit, proxy to Google Places Autocomplete (New) |
| `src/app/api/places/details/route.ts` | Create | Proxy to Google Place Details (New), field mask `id`, always 204 |
| `src/__tests__/lib/rateLimiter.test.ts` | Create | Fail-open test for Upstash errors |
| `src/__tests__/api/places/autocomplete.test.ts` | Create | Handler unit tests |
| `src/__tests__/api/places/details.test.ts` | Create | Handler unit tests |
| `src/__tests__/lib/places.test.ts` | Create | blockedUntil logic, rate-limit sentinel |
| `vitest.config.ts` | Create | Vitest config with `@/` path alias |
| `src/lib/places.ts` | Modify | Remove key, add BFF calls, `blockedUntil`, `fetchPlaceDetails`, `_resetRateLimit` |
| `src/components/ui/LocationAutocomplete.tsx` | Modify | 300ms debounce, rate-limit tiers, retryTimeoutRef, fire-and-forget details |
| `src/components/event/CreateEventForm.tsx` | Modify | Conditional location spread (omit empty) |
| `frontend/.env.local.example` | Modify | `GOOGLE_MAPS_KEY` + Upstash vars, remove `NEXT_PUBLIC_GOOGLE_MAPS_KEY` |
| `frontend/package.json` | Modify | Add `@upstash/ratelimit`, `@upstash/redis`; add `vitest` devDep + `test` script |

---

## Task 1: Configure Vitest

**Files:**
- Create: `frontend/vitest.config.ts`
- Modify: `frontend/package.json`

- [ ] **Step 1: Install Vitest**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm install --save-dev vitest
```

Expected: vitest added to devDependencies in package.json.

- [ ] **Step 2: Create vitest.config.ts**

```ts
import { defineConfig } from 'vitest/config'
import path from 'path'

export default defineConfig({
  test: {
    environment: 'node',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
```

- [ ] **Step 3: Add test script to package.json**

In `frontend/package.json`, add to `"scripts"`:
```json
"test": "vitest run",
"test:watch": "vitest"
```

- [ ] **Step 4: Verify Vitest runs**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test
```

Expected output: `No test files found` (or similar — no failure, just no tests yet).

- [ ] **Step 5: Commit**

```bash
git add frontend/vitest.config.ts frontend/package.json frontend/package-lock.json
git commit -m "chore(frontend): add vitest"
```

---

## Task 2: Install Upstash packages and create rateLimiter.ts

**Files:**
- Modify: `frontend/package.json`
- Create: `src/lib/rateLimiter.ts`
- Create: `src/__tests__/lib/rateLimiter.test.ts`

- [ ] **Step 1: Install Upstash packages**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm install @upstash/ratelimit @upstash/redis
```

Expected: both packages added to dependencies.

- [ ] **Step 2: Write the failing test**

Create `frontend/src/__tests__/lib/rateLimiter.test.ts`:

```ts
import { vi, describe, it, expect, beforeEach } from 'vitest'

// Mock Upstash modules before any import that uses them.
// The Ratelimit constructor is called lazily (on first checkPlacesRateLimit call),
// so mocks must be in place before that happens.
vi.mock('@upstash/redis', () => ({
  Redis: { fromEnv: vi.fn(() => ({})) },
}))

const mockWindowLimit = vi.fn()
const mockHourlyLimit = vi.fn()

vi.mock('@upstash/ratelimit', () => {
  const MockRatelimit = vi.fn()
    .mockImplementationOnce(() => ({ limit: mockWindowLimit }))
    .mockImplementationOnce(() => ({ limit: mockHourlyLimit }))
  MockRatelimit.slidingWindow = vi.fn(() => ({}))
  MockRatelimit.fixedWindow = vi.fn(() => ({}))
  return { Ratelimit: MockRatelimit }
})

describe('checkPlacesRateLimit — fail open', () => {
  beforeEach(() => {
    vi.resetModules()
    mockWindowLimit.mockReset()
    mockHourlyLimit.mockReset()
    process.env.UPSTASH_REDIS_REST_URL = 'https://example.upstash.io'
    process.env.UPSTASH_REDIS_REST_TOKEN = 'test-token'
  })

  it('returns allowed:true when Upstash throws', async () => {
    mockWindowLimit.mockRejectedValue(new Error('connection refused'))
    const { checkPlacesRateLimit } = await import('@/lib/rateLimiter')
    const result = await checkPlacesRateLimit('test-session')
    expect(result).toEqual({ allowed: true })
  })
})
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test src/__tests__/lib/rateLimiter.test.ts
```

Expected: FAIL — `Cannot find module '@/lib/rateLimiter'`

- [ ] **Step 4: Create src/lib/rateLimiter.ts**

```ts
import { Ratelimit } from '@upstash/ratelimit'
import { Redis } from '@upstash/redis'

let windowLimiter: Ratelimit | undefined
let hourlyLimiter: Ratelimit | undefined

function getWindowLimiter(): Ratelimit {
  if (!windowLimiter) {
    windowLimiter = new Ratelimit({
      redis: Redis.fromEnv(),
      limiter: Ratelimit.slidingWindow(1, '300 ms'),
      prefix: 'places:window',
    })
  }
  return windowLimiter
}

function getHourlyLimiter(): Ratelimit {
  if (!hourlyLimiter) {
    hourlyLimiter = new Ratelimit({
      redis: Redis.fromEnv(),
      limiter: Ratelimit.fixedWindow(50, '1 h'),
      prefix: 'places:hourly',
    })
  }
  return hourlyLimiter
}

export type RateLimitResult =
  | { allowed: true }
  | { allowed: false; retryAfter: number }

export async function checkPlacesRateLimit(sessionKey: string): Promise<RateLimitResult> {
  try {
    const windowResult = await getWindowLimiter().limit(sessionKey)
    if (!windowResult.success) {
      return {
        allowed: false,
        retryAfter: Math.max(1, Math.ceil((windowResult.reset - Date.now()) / 1000)),
      }
    }
    const hourlyResult = await getHourlyLimiter().limit(sessionKey)
    if (!hourlyResult.success) {
      return {
        allowed: false,
        retryAfter: Math.max(1, Math.ceil((hourlyResult.reset - Date.now()) / 1000)),
      }
    }
    return { allowed: true }
  } catch {
    return { allowed: true }
  }
}
```

> **Note:** `Ratelimit.slidingWindow(1, '300 ms')` — verify `'300 ms'` is a valid Duration in the installed version of `@upstash/ratelimit`. The type is `` `${number} ${'ms' | 's' | 'm' | 'h' | 'd'}` `` so `'300 ms'` should be accepted. If TypeScript rejects it, use `'1 s'` as a fallback.

- [ ] **Step 5: Run test to verify it passes**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test src/__tests__/lib/rateLimiter.test.ts
```

Expected: PASS — 1 test passes.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/rateLimiter.ts frontend/src/__tests__/lib/rateLimiter.test.ts frontend/package.json frontend/package-lock.json
git commit -m "feat(frontend): add Upstash rate limiter for Places BFF"
```

---

## Task 3: Autocomplete route handler

**Files:**
- Create: `src/app/api/places/autocomplete/route.ts`
- Create: `src/__tests__/api/places/autocomplete.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/__tests__/api/places/autocomplete.test.ts`:

```ts
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest'

vi.mock('@/lib/rateLimiter')

import { checkPlacesRateLimit } from '@/lib/rateLimiter'
import { GET } from '@/app/api/places/autocomplete/route'

const mockRateLimit = vi.mocked(checkPlacesRateLimit)

function req(url: string, cookie?: string): Request {
  return new Request(url, { headers: cookie ? { Cookie: cookie } : {} })
}

const BASE = 'http://localhost/api/places/autocomplete'
const AUTHED = (q = 'test', tok = 'abc') =>
  req(`${BASE}?q=${q}&sessionToken=${tok}`, 'SESSION=test-sess')

describe('GET /api/places/autocomplete', () => {
  beforeEach(() => {
    mockRateLimit.mockReset()
    delete process.env.GOOGLE_MAPS_KEY
  })

  it('returns 401 when SESSION cookie is absent', async () => {
    const res = await GET(req(`${BASE}?q=test&sessionToken=abc`))
    expect(res.status).toBe(401)
  })

  it('returns 200 [] for blank query without calling rate limiter', async () => {
    const res = await GET(req(`${BASE}?q=+&sessionToken=abc`, 'SESSION=sess'))
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual([])
    expect(mockRateLimit).not.toHaveBeenCalled()
  })

  it('returns 429 with Retry-After when rate limited', async () => {
    mockRateLimit.mockResolvedValue({ allowed: false, retryAfter: 5 })
    const res = await GET(AUTHED())
    expect(res.status).toBe(429)
    expect(res.headers.get('Retry-After')).toBe('5')
  })

  it('returns 200 [] when GOOGLE_MAPS_KEY is unset', async () => {
    mockRateLimit.mockResolvedValue({ allowed: true })
    const res = await GET(AUTHED())
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual([])
  })

  it('returns 200 [] when Google responds non-OK', async () => {
    mockRateLimit.mockResolvedValue({ allowed: true })
    process.env.GOOGLE_MAPS_KEY = 'key'
    const spy = vi.spyOn(global, 'fetch').mockResolvedValueOnce(
      new Response(null, { status: 500 }),
    )
    const res = await GET(AUTHED())
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual([])
    spy.mockRestore()
  })

  it('maps Google structuredFormat response to PlaceSuggestion[]', async () => {
    mockRateLimit.mockResolvedValue({ allowed: true })
    process.env.GOOGLE_MAPS_KEY = 'key'
    const googleBody = {
      suggestions: [
        {
          placePrediction: {
            placeId: 'place1',
            structuredFormat: {
              mainText: { text: "McDonald's" },
              secondaryText: { text: 'Greenacre NSW' },
            },
          },
        },
      ],
    }
    const spy = vi.spyOn(global, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify(googleBody), { status: 200 }),
    )
    const res = await GET(AUTHED('mcdonald'))
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual([
      { id: 'place1', title: "McDonald's", subtitle: 'Greenacre NSW' },
    ])
    spy.mockRestore()
  })

  it('falls back to text.text when structuredFormat is absent', async () => {
    mockRateLimit.mockResolvedValue({ allowed: true })
    process.env.GOOGLE_MAPS_KEY = 'key'
    const googleBody = {
      suggestions: [
        {
          placePrediction: {
            placeId: 'place2',
            text: { text: 'Some Place, Sydney' },
          },
        },
      ],
    }
    const spy = vi.spyOn(global, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify(googleBody), { status: 200 }),
    )
    const res = await GET(AUTHED('some'))
    expect(await res.json()).toEqual([{ id: 'place2', title: 'Some Place, Sydney' }])
    spy.mockRestore()
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test src/__tests__/api/places/autocomplete.test.ts
```

Expected: FAIL — `Cannot find module '@/app/api/places/autocomplete/route'`

- [ ] **Step 3: Create the route handler**

Create `frontend/src/app/api/places/autocomplete/route.ts`:

```ts
import { checkPlacesRateLimit } from '@/lib/rateLimiter'

function getCookie(req: Request, name: string): string | undefined {
  const cookie = req.headers.get('cookie') ?? ''
  for (const part of cookie.split(';')) {
    const trimmed = part.trim()
    const eq = trimmed.indexOf('=')
    if (eq !== -1 && trimmed.slice(0, eq) === name) return trimmed.slice(eq + 1)
  }
}

type GoogleSuggestion = {
  placePrediction?: {
    placeId: string
    structuredFormat?: {
      mainText?: { text: string }
      secondaryText?: { text: string }
    }
    text?: { text: string }
  }
}

export async function GET(req: Request) {
  const sessionKey = getCookie(req, 'SESSION')
  if (!sessionKey) {
    return Response.json({ error: 'unauthorized' }, { status: 401 })
  }

  const url = new URL(req.url)
  const q = url.searchParams.get('q') ?? ''
  const sessionToken = url.searchParams.get('sessionToken') ?? ''

  if (!q.trim()) {
    return Response.json([])
  }

  const rateLimit = await checkPlacesRateLimit(sessionKey)
  if (!rateLimit.allowed) {
    return Response.json(
      { error: 'rate limited' },
      { status: 429, headers: { 'Retry-After': String(rateLimit.retryAfter) } },
    )
  }

  const key = process.env.GOOGLE_MAPS_KEY
  if (!key) return Response.json([])

  try {
    const res = await fetch('https://places.googleapis.com/v1/places:autocomplete', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Api-Key': key,
      },
      body: JSON.stringify({ input: q, sessionToken }),
    })

    if (!res.ok) return Response.json([])

    const data = (await res.json()) as { suggestions?: GoogleSuggestion[] }

    return Response.json(
      (data.suggestions ?? [])
        .map((s) => s.placePrediction)
        .filter((p): p is NonNullable<typeof p> => Boolean(p))
        .map((p) => ({
          id: p.placeId,
          title: p.structuredFormat?.mainText?.text ?? p.text?.text ?? '',
          subtitle: p.structuredFormat?.secondaryText?.text,
        })),
    )
  } catch {
    return Response.json([])
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test src/__tests__/api/places/autocomplete.test.ts
```

Expected: PASS — all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/api/places/autocomplete/route.ts frontend/src/__tests__/api/places/autocomplete.test.ts
git commit -m "feat(frontend): add Places autocomplete BFF route"
```

---

## Task 4: Details route handler

**Files:**
- Create: `src/app/api/places/details/route.ts`
- Create: `src/__tests__/api/places/details.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/__tests__/api/places/details.test.ts`:

```ts
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest'
import { GET } from '@/app/api/places/details/route'

const BASE = 'http://localhost/api/places/details'

describe('GET /api/places/details', () => {
  beforeEach(() => {
    delete process.env.GOOGLE_MAPS_KEY
  })

  it('returns 400 when placeId is missing', async () => {
    const res = await GET(new Request(`${BASE}?sessionToken=tok`))
    expect(res.status).toBe(400)
  })

  it('returns 204 without calling Google when GOOGLE_MAPS_KEY is unset', async () => {
    const spy = vi.spyOn(global, 'fetch')
    const res = await GET(new Request(`${BASE}?placeId=abc&sessionToken=tok`))
    expect(res.status).toBe(204)
    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })

  it('calls Google with correct headers and returns 204', async () => {
    process.env.GOOGLE_MAPS_KEY = 'my-key'
    const spy = vi.spyOn(global, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ id: 'abc' }), { status: 200 }),
    )
    const res = await GET(new Request(`${BASE}?placeId=abc123&sessionToken=tok`))
    expect(res.status).toBe(204)
    expect(spy).toHaveBeenCalledOnce()
    const [calledUrl, calledInit] = spy.mock.calls[0] as [string, RequestInit]
    expect(calledUrl).toContain('abc123')
    expect((calledInit.headers as Record<string, string>)['X-Goog-Api-Key']).toBe('my-key')
    expect((calledInit.headers as Record<string, string>)['X-Goog-FieldMask']).toBe('id')
    spy.mockRestore()
  })

  it('still returns 204 when Google call throws', async () => {
    process.env.GOOGLE_MAPS_KEY = 'my-key'
    const spy = vi.spyOn(global, 'fetch').mockRejectedValueOnce(new Error('timeout'))
    const res = await GET(new Request(`${BASE}?placeId=abc&sessionToken=tok`))
    expect(res.status).toBe(204)
    spy.mockRestore()
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test src/__tests__/api/places/details.test.ts
```

Expected: FAIL — `Cannot find module '@/app/api/places/details/route'`

- [ ] **Step 3: Create the route handler**

Create `frontend/src/app/api/places/details/route.ts`:

```ts
export async function GET(req: Request) {
  const url = new URL(req.url)
  const placeId = url.searchParams.get('placeId')
  const sessionToken = url.searchParams.get('sessionToken') ?? ''

  if (!placeId) return new Response(null, { status: 400 })

  const key = process.env.GOOGLE_MAPS_KEY
  if (!key) return new Response(null, { status: 204 })

  try {
    const detailUrl = new URL(
      `https://places.googleapis.com/v1/places/${encodeURIComponent(placeId)}`,
    )
    if (sessionToken) detailUrl.searchParams.set('sessionToken', sessionToken)

    await fetch(detailUrl.toString(), {
      headers: {
        'X-Goog-Api-Key': key,
        'X-Goog-FieldMask': 'id',
      },
    })
  } catch {
    // Ignore — purely for billing session closure
  }

  return new Response(null, { status: 204 })
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test src/__tests__/api/places/details.test.ts
```

Expected: PASS — all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/api/places/details/route.ts frontend/src/__tests__/api/places/details.test.ts
git commit -m "feat(frontend): add Places details BFF route"
```

---

## Task 5: Update places.ts

**Files:**
- Modify: `src/lib/places.ts`
- Create: `src/__tests__/lib/places.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/__tests__/lib/places.test.ts`:

```ts
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest'

const mockFetch = vi.fn()
global.fetch = mockFetch

describe('searchPlaces (live mode)', () => {
  beforeEach(async () => {
    vi.resetModules()
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000)
    mockFetch.mockReset()
    process.env.NEXT_PUBLIC_API_MODE = 'live'
  })

  afterEach(() => {
    vi.useRealTimers()
    delete process.env.NEXT_PUBLIC_API_MODE
  })

  it('returns [] for whitespace-only query without fetching', async () => {
    const { searchPlaces } = await import('@/lib/places')
    const result = await searchPlaces('   ', 'tok')
    expect(result).toEqual([])
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('returns results array on 200', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify([{ id: 'p1', title: 'The Oak', subtitle: 'Newtown' }]), {
        status: 200,
      }),
    )
    const { searchPlaces } = await import('@/lib/places')
    const result = await searchPlaces('oak', 'tok')
    expect(result).toEqual([{ id: 'p1', title: 'The Oak', subtitle: 'Newtown' }])
  })

  it('returns { rateLimited, retryAfter } on 429 and blocks subsequent calls', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(null, { status: 429, headers: { 'Retry-After': '30' } }),
    )
    const { searchPlaces } = await import('@/lib/places')

    const first = await searchPlaces('oak', 'tok')
    expect(first).toEqual({ rateLimited: true, retryAfter: 30 })

    // Second call within block window — no fetch
    mockFetch.mockReset()
    vi.setSystemTime(1_000_000 + 10_000) // +10s, still blocked (30s window)
    const second = await searchPlaces('oak', 'tok')
    expect(second).toEqual({ rateLimited: true, retryAfter: 20 })
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('fetches normally once block window expires', async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(null, { status: 429, headers: { 'Retry-After': '30' } }),
    )
    const { searchPlaces } = await import('@/lib/places')
    await searchPlaces('oak', 'tok') // sets blockedUntil = 1_000_000 + 30_000

    vi.setSystemTime(1_000_000 + 31_000) // past the block
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify([{ id: 'p2', title: 'Oak Bar' }]), { status: 200 }),
    )
    const result = await searchPlaces('oak', 'tok')
    expect(mockFetch).toHaveBeenCalledOnce()
    expect(result).toEqual([{ id: 'p2', title: 'Oak Bar' }])
  })

  it('returns [] on non-429 error response', async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 500 }))
    const { searchPlaces } = await import('@/lib/places')
    expect(await searchPlaces('oak', 'tok')).toEqual([])
  })

  it('returns [] when fetch throws', async () => {
    mockFetch.mockRejectedValueOnce(new Error('network'))
    const { searchPlaces } = await import('@/lib/places')
    expect(await searchPlaces('oak', 'tok')).toEqual([])
  })
})

describe('searchPlaces (mock mode)', () => {
  beforeEach(async () => {
    vi.resetModules()
    mockFetch.mockReset()
    process.env.NEXT_PUBLIC_API_MODE = 'mock'
  })

  afterEach(() => {
    delete process.env.NEXT_PUBLIC_API_MODE
  })

  it('returns mock suggestions without fetching', async () => {
    const { searchPlaces } = await import('@/lib/places')
    const result = await searchPlaces('oak', 'tok')
    expect(Array.isArray(result)).toBe(true)
    expect(mockFetch).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test src/__tests__/lib/places.test.ts
```

Expected: FAIL — tests using `blockedUntil` and BFF URL logic will fail since the current `places.ts` calls Google directly.

- [ ] **Step 3: Rewrite places.ts**

Replace `frontend/src/lib/places.ts` entirely with:

```ts
export type PlaceSuggestion = {
  id: string
  title: string
  subtitle?: string
}

export type SearchResult = PlaceSuggestion[] | { rateLimited: true; retryAfter: number }

export function suggestionToLocation(s: PlaceSuggestion): string {
  return s.subtitle ? `${s.title}, ${s.subtitle}` : s.title
}

const MODE = process.env.NEXT_PUBLIC_API_MODE ?? 'mock'

const MOCK_PLACES: PlaceSuggestion[] = [
  { id: 'm-royal-oak', title: 'The Royal Oak', subtitle: '127 King St, Newtown' },
  { id: 'm-glass-barrel', title: 'The Glass Barrel', subtitle: '44 Brunswick St, Fitzroy' },
  { id: 'm-lumen', title: 'Lumen Cinema', subtitle: '201 Lygon St, Carlton' },
  { id: 'm-basement', title: 'Basement Bar', subtitle: '88 Sydney Rd, Brunswick' },
  { id: 'm-noodle', title: 'Little Noodle House', subtitle: '14 Victoria St, Richmond' },
  { id: 'm-comedy-store', title: 'The Comedy Store', subtitle: 'Entertainment Quarter, Moore Park' },
  { id: 'm-george-cinema', title: 'George St Cinemas', subtitle: '505 George St, Sydney' },
  { id: 'm-bundeena', title: 'Bundeena Ferry Terminal', subtitle: 'Brighton St, Bundeena' },
  { id: 'm-apollo', title: 'Apollo Bay Trailhead', subtitle: 'Great Ocean Walk, Apollo Bay' },
  { id: 'm-dumpling', title: 'Dumpling Corner', subtitle: '312 Smith St, Collingwood' },
  { id: 'm-pavilion', title: 'Park Pavilion', subtitle: 'Princes Park, Carlton North' },
  { id: 'm-ramen', title: 'Tonkotsu Ramen House', subtitle: '77 Little Lonsdale St, CBD' },
  { id: 'm-rooftop', title: 'Rooftop Cinema', subtitle: 'Curtin House, Swanston St, CBD' },
  { id: 'm-night-market', title: 'Queen Vic Night Market', subtitle: '513 Elizabeth St, CBD' },
  { id: 'm-botanic', title: 'Royal Botanic Gardens', subtitle: 'Birdwood Ave, South Yarra' },
  { id: 'm-pinball', title: 'The Pinball Room', subtitle: '120 Gertrude St, Fitzroy' },
  { id: 'm-beer-garden', title: 'The Beer Garden', subtitle: '190 Gertrude St, Fitzroy' },
  { id: 'm-observatory', title: 'Melbourne Observatory', subtitle: 'Birdwood Ave, Melbourne' },
]

function mockSearch(query: string): PlaceSuggestion[] {
  const q = query.trim().toLowerCase()
  if (!q) return MOCK_PLACES.slice(0, 6)
  return MOCK_PLACES.filter(
    (p) => p.title.toLowerCase().includes(q) || p.subtitle?.toLowerCase().includes(q),
  ).slice(0, 8)
}

let blockedUntil = 0

export async function searchPlaces(
  query: string,
  sessionToken: string,
  signal?: AbortSignal,
): Promise<SearchResult> {
  if (MODE === 'mock') return mockSearch(query)
  if (!query.trim()) return []

  if (Date.now() < blockedUntil) {
    return { rateLimited: true, retryAfter: Math.ceil((blockedUntil - Date.now()) / 1000) }
  }

  try {
    const params = new URLSearchParams({ q: query, sessionToken })
    const res = await fetch(`/api/places/autocomplete?${params}`, { signal })

    if (res.status === 429) {
      const retryAfter = Number(res.headers.get('Retry-After') ?? '1')
      blockedUntil = Date.now() + retryAfter * 1000
      return { rateLimited: true, retryAfter }
    }

    if (!res.ok) return []
    return (await res.json()) as PlaceSuggestion[]
  } catch {
    return []
  }
}

export function fetchPlaceDetails(placeId: string, sessionToken: string): void {
  if (MODE === 'mock' || !placeId) return
  const params = new URLSearchParams({ placeId, sessionToken })
  fetch(`/api/places/details?${params}`).catch(() => {})
}

export function newPlacesSessionToken(): string {
  return crypto.randomUUID()
}

```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test src/__tests__/lib/places.test.ts
```

Expected: PASS — all 8 tests pass.

- [ ] **Step 5: Run all tests to verify nothing broke**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/places.ts frontend/src/__tests__/lib/places.test.ts
git commit -m "feat(frontend): move Places API calls to BFF, add blockedUntil backoff"
```

---

## Task 6: Update LocationAutocomplete.tsx

**Files:**
- Modify: `src/components/ui/LocationAutocomplete.tsx`

No new tests — mock mode is unchanged and the component logic is driven by `searchPlaces` which is tested in Task 5. Visual behaviour is verified manually.

- [ ] **Step 1: Replace LocationAutocomplete.tsx**

Replace `frontend/src/components/ui/LocationAutocomplete.tsx` entirely with:

```tsx
"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import clsx from "@/lib/clsx";
import {
  fetchPlaceDetails,
  newPlacesSessionToken,
  searchPlaces,
  suggestionToLocation,
  type PlaceSuggestion,
} from "@/lib/places";

export function LocationAutocomplete({
  value,
  onChange,
  placeholder,
  required,
  className,
  recent,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  required?: boolean;
  className?: string;
  recent?: string[];
}) {
  const [open, setOpen] = useState(false);
  const [suggestions, setSuggestions] = useState<PlaceSuggestion[]>([]);
  const [highlight, setHighlight] = useState(0);
  const [loading, setLoading] = useState(false);
  const [mode, setMode] = useState<"recent" | "search">("recent");
  const [rateLimitWarning, setRateLimitWarning] = useState(false);
  const [locationUnavailable, setLocationUnavailable] = useState(false);
  const sessionToken = useMemo(newPlacesSessionToken, []);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const retryTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const recentSuggestions: PlaceSuggestion[] = useMemo(
    () => (recent ?? []).map((title, i) => ({ id: `recent:${i}`, title })),
    [recent],
  );

  useEffect(() => {
    const onDocClick = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  useEffect(() => {
    if (!open) return;
    const q = value.trim();
    if (!q) {
      setMode("recent");
      setSuggestions(recentSuggestions);
      setHighlight(0);
      setLoading(false);
      return;
    }
    setMode("search");
    const ctrl = new AbortController();
    setLoading(true);

    const t = setTimeout(async () => {
      if (retryTimeoutRef.current) {
        clearTimeout(retryTimeoutRef.current);
        retryTimeoutRef.current = null;
      }

      const results = await searchPlaces(value, sessionToken, ctrl.signal);

      if (Array.isArray(results)) {
        setSuggestions(results);
        setHighlight(0);
        setLoading(false);
        setRateLimitWarning(false);
        setLocationUnavailable(false);
        return;
      }

      const { retryAfter } = results;

      if (retryAfter > 60) {
        setLocationUnavailable(true);
        setLoading(false);
        setOpen(false);
        onChange("");
      } else {
        setRateLimitWarning(retryAfter > 10);
      }

      retryTimeoutRef.current = setTimeout(async () => {
        retryTimeoutRef.current = null;
        setRateLimitWarning(false);

        if (retryAfter > 60) {
          setLocationUnavailable(false);
          return;
        }

        const retryResults = await searchPlaces(value, sessionToken);
        if (Array.isArray(retryResults)) {
          setSuggestions(retryResults);
          setHighlight(0);
          setLoading(false);
        }
      }, retryAfter * 1000);
    }, 300);

    return () => {
      clearTimeout(t);
      if (retryTimeoutRef.current) {
        clearTimeout(retryTimeoutRef.current);
        retryTimeoutRef.current = null;
      }
      ctrl.abort();
    };
  }, [value, open, sessionToken, recentSuggestions, onChange]);

  const pick = (s: PlaceSuggestion) => {
    onChange(suggestionToLocation(s));
    fetchPlaceDetails(s.id, sessionToken);
    setOpen(false);
    inputRef.current?.blur();
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Escape") {
      setOpen(false);
      return;
    }
    if (!open && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
      setOpen(true);
      return;
    }
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setHighlight((h) => Math.min(h + 1, suggestions.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setHighlight((h) => Math.max(h - 1, 0));
    } else if (e.key === "Enter" && open && suggestions[highlight]) {
      e.preventDefault();
      pick(suggestions[highlight]);
    }
  };

  return (
    <div ref={containerRef} className={clsx("relative", className)}>
      <div className="relative">
        <span
          className="absolute left-3 top-1/2 -translate-y-1/2 text-ink/60 pointer-events-none"
          aria-hidden
        >
          📍
        </span>
        <input
          ref={inputRef}
          value={value}
          required={required}
          disabled={locationUnavailable}
          placeholder={placeholder ?? "where?"}
          onChange={(e) => {
            onChange(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          autoComplete="off"
          aria-autocomplete="list"
          aria-expanded={open}
          role="combobox"
          className={clsx(
            "w-full rounded-[10px] border-[1.5px] border-ink bg-paper pl-9 pr-3 py-2 text-[15px] font-medium shadow-chunky-sm focus:outline-none focus:shadow-chunky-md",
            locationUnavailable && "opacity-50 cursor-not-allowed",
          )}
        />
      </div>

      {locationUnavailable && (
        <p className="mt-1.5 text-[12px] font-semibold text-mute">
          location unavailable — too many searches, try again later
        </p>
      )}

      {!locationUnavailable && open && (suggestions.length > 0 || loading) && (
        <div
          role="listbox"
          className="absolute z-30 left-0 right-0 mt-1.5 rounded-[12px] border-[1.5px] border-ink bg-paper shadow-chunky-md overflow-hidden"
        >
          {mode === "recent" && suggestions.length > 0 && (
            <div className="px-3 pt-2 pb-1 text-[10.5px] font-extrabold tracking-[0.18em] text-mute uppercase border-b-[1px] border-ink/10">
              usual spots
            </div>
          )}
          {rateLimitWarning && (
            <div className="px-3 py-2 text-[13px] text-mute border-b-[1px] border-ink/10">
              ⏱ too many searches — try again shortly
            </div>
          )}
          {loading && suggestions.length === 0 && (
            <div className="px-3 py-2 text-[13px] text-mute">looking…</div>
          )}
          {suggestions.map((s, i) => (
            <button
              key={s.id}
              type="button"
              role="option"
              aria-selected={i === highlight}
              onMouseEnter={() => setHighlight(i)}
              onClick={() => pick(s)}
              className={clsx(
                "w-full text-left px-3 py-2 flex flex-col gap-0.5 border-b-[1px] border-ink/10 last:border-b-0",
                i === highlight ? "bg-leaf/15" : "bg-paper hover:bg-paper2",
              )}
            >
              <span className="text-[14px] font-extrabold text-ink tracking-[-0.01em]">
                📍 {s.title}
              </span>
              {s.subtitle && (
                <span className="text-[12.5px] text-mute font-semibold pl-[22px]">
                  {s.subtitle}
                </span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Run lint and typecheck**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm run lint && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Run all tests**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/ui/LocationAutocomplete.tsx
git commit -m "feat(frontend): add rate-limit tiers and fire-and-forget Details call to LocationAutocomplete"
```

---

## Task 7: Update CreateEventForm.tsx and environment

**Files:**
- Modify: `src/components/event/CreateEventForm.tsx`
- Modify: `frontend/.env.local.example`

- [ ] **Step 1: Omit empty location from event creation payload**

In `frontend/src/components/event/CreateEventForm.tsx`, find the `createEvent` call (line ~30–38) and update the payload:

Change:
```ts
const created = await createEvent(guild.id, {
  name,
  description: info,
  location,
  capacity,
  dateTime: new Date(date).toISOString(),
});
```

To:
```ts
const created = await createEvent(guild.id, {
  name,
  description: info,
  ...(location.trim() ? { location } : {}),
  capacity,
  dateTime: new Date(date).toISOString(),
});
```

- [ ] **Step 2: Update .env.local.example**

Replace `frontend/.env.local.example` with:

```
# mock = MSW intercepts /api calls (no homelab needed)
# live = fetch against NEXT_PUBLIC_API_BASE
NEXT_PUBLIC_API_MODE=mock
NEXT_PUBLIC_API_BASE=http://localhost:8080/api

# Server-side Google Maps Platform key (Places API (New) enabled).
# NOT prefixed with NEXT_PUBLIC — never sent to the browser.
# In mock mode or when unset, location falls back to static mock suggestions.
# Restrict the key by server IP or referrer in Google Cloud Console before shipping.
GOOGLE_MAPS_KEY=

# Upstash Redis — used for Places API rate limiting (300ms window + 50/hr cap).
# Create a free database at https://console.upstash.com and copy the REST credentials.
UPSTASH_REDIS_REST_URL=
UPSTASH_REDIS_REST_TOKEN=
```

- [ ] **Step 3: Run lint, typecheck, and all tests**

```bash
cd frontend && export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && npm run lint && npx tsc --noEmit && npm test
```

Expected: no lint errors, no type errors, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/event/CreateEventForm.tsx frontend/.env.local.example
git commit -m "feat(frontend): omit empty location from event payload; update env example for Places BFF"
```

---

## Post-Implementation Checklist

Before marking complete:

- [ ] Add `GOOGLE_MAPS_KEY`, `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN` to the Vercel project environment variables (Production + Preview). Remove `NEXT_PUBLIC_GOOGLE_MAPS_KEY` from Vercel.
- [ ] Create an Upstash Redis database at https://console.upstash.com — free tier is sufficient. Copy REST URL and token.
- [ ] In Google Cloud Console: remove the HTTP referrer restriction from `GOOGLE_MAPS_KEY` (no longer needed since the key is server-side), and optionally add an IP restriction for Vercel's outbound IPs.
- [ ] Smoke test in live mode: open the create event form, type a venue name, verify suggestions appear, pick one, verify the location field shows "Place Name, Locality" format, verify event creation works without location when the field is empty.
