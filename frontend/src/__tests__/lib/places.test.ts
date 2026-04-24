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
    mockFetch.mockClear()
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
