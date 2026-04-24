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
