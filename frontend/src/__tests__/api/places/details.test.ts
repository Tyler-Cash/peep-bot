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
